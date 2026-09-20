package com.radioterapia.ai.sync.destino

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.radioterapia.ai.sync.DestinoSync
import com.radioterapia.ai.sync.LogConexao
import com.radioterapia.ai.sync.PerfilSync
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Destino SMB — o caso do servidor de arquivos do hospital.
 *
 * INJEÇÃO DIRETA é a decisão que manda neste arquivo. A implementação óbvia
 * seria: listar a pasta, ver se existe, criar se não existir, então gravar. Em
 * hospital isso falha por um motivo que não aparece na mensagem de erro — é
 * comum a conta do tablet ter permissão de ESCRITA na pasta final e NENHUMA
 * LEITURA nas pastas acima dela. A verificação falha com acesso negado numa
 * pasta que nem é o destino, e o técnico lê "acesso negado a \\servidor\rt" sem
 * saber que a pasta que ele quer usar está logo abaixo e é gravável.
 *
 * Então grava-se direto no caminho final, sem perguntar nada. Só quando o
 * servidor responde `STATUS_OBJECT_PATH_NOT_FOUND` — que é ele dizendo que o
 * caminho realmente não existe, e não que não podemos olhar — é que as pastas
 * intermediárias são criadas, uma a uma, ignorando "já existe".
 *
 * O DOMÍNIO TEM CAMPO PRÓPRIO. Quem digita `DOMINIO\usuario` no campo de
 * usuário está montando o pacote NTLM à mão, e erra a barra metade das vezes.
 * Aqui o domínio entra separado e o [AuthenticationContext] monta o pacote.
 */
class DestinoSmb(
    private val perfil: PerfilSync,
    private val senha: String,
) : DestinoSync {

    private var cliente: SMBClient? = null
    private var conexao: Connection? = null
    private var sessao: Session? = null
    private var share: DiskShare? = null

    private fun configurar(): SmbConfig {
        val b = SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            // DFS DESLIGADO de propósito: resolver referral exige consultar o
            // controlador de domínio, e a conta do tablet costuma não poder.
            // Quem usa DFS configura o caminho já resolvido.
            .withDfsEnabled(false)
            .withSigningRequired(true)
        // CIFRAGEM SÓ NO SMB3, e não por padrão: o que sobe é foto de paciente,
        // então cifrar é o certo onde existe — mas exigir cifragem de um
        // servidor SMB2 derruba a conexão com um erro que não explica a causa.
        if (perfil.protocolo.uppercase() == "SMB3") b.withEncryptData(true)
        return b.build()
    }

    private fun autenticacao(): AuthenticationContext =
        if (perfil.usuario.isBlank()) AuthenticationContext.anonymous()
        else AuthenticationContext(
            perfil.usuario.trim(),
            senha.toCharArray(),
            perfil.dominio.trim().ifBlank { null }
        )

    private fun abrir(log: LogConexao?): DiskShare {
        share?.let { if (it.isConnected) return it }
        fechar()
        val porta = perfil.portaEfetiva()
        log?.passo("SMB: protocolo declarado ${perfil.protocolo}, " +
                "cifragem ${if (perfil.protocolo.uppercase() == "SMB3") "ligada" else "desligada"}")
        log?.passo("SMB: conectando em ${perfil.host}:$porta")
        val c = SMBClient(configurar())
        cliente = c
        val conn = c.connect(perfil.host.trim(), porta)
        conexao = conn
        log?.ok("SMB: socket aberto, dialeto negociado " +
                (conn.connectionContext.negotiatedProtocol?.dialect?.name ?: "(não informado)"))
        log?.passo("SMB: autenticando usuário '${perfil.usuario}'" +
                (if (perfil.dominio.isNotBlank()) " no domínio '${perfil.dominio}'" else " sem domínio"))
        senha.let { log?.senha(it) }
        val s = conn.authenticate(autenticacao())
        sessao = s
        log?.ok("SMB: autenticado")
        log?.passo("SMB: abrindo share '${perfil.share}'")
        val d = s.connectShare(perfil.share.trim()) as DiskShare
        share = d
        log?.ok("SMB: share aberto")
        return d
    }

    /** Caminho remoto no formato do SMB: separador `\`, sem barra inicial. */
    private fun caminho(vararg partes: String): String =
        partes.filter { it.isNotBlank() }
            .joinToString("\\") { it.trim('/', '\\').replace('/', '\\') }

    override fun testar(log: LogConexao): Boolean = try {
        val d = abrir(log)
        val pastaAlvo = caminho(perfil.caminhoRemoto)
        // A PROVA DE ESCRITA é gravar de verdade. "Conectou no share" responde
        // uma pergunta que ninguém fez: o que se quer saber é se a foto vai
        // chegar lá. Um arquivo vazio, com nome datado, escrito e apagado em
        // seguida — e se o apagar falhar, fica um arquivo de 0 byte, que é
        // infinitamente melhor que descobrir o problema com o paciente na mesa.
        val nomeProva = ".photoid_teste_${System.currentTimeMillis()}.tmp"
        val alvo = caminho(pastaAlvo, nomeProva)
        log?.passo("SMB: gravando arquivo de prova em '$alvo'")
        try {
            escrever(d, alvo, null)
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) {
                log.passo("SMB: caminho não existe, criando '$pastaAlvo'")
                criarPastas(d, pastaAlvo, log)
                escrever(d, alvo, null)
            } else throw e
        }
        log.ok("SMB: gravação confirmada")
        try {
            d.rm(alvo); log.ok("SMB: arquivo de prova removido")
        } catch (e: Exception) {
            log.passo("SMB: não foi possível remover o arquivo de prova " +
                    "(${e.javaClass.simpleName}). Ele fica lá, com 0 byte.")
        }
        true
    } catch (e: Exception) {
        log.excecao("SMB", e)
        log.passo(dica(e))
        false
    }

    override fun enviar(local: File, caminhoRelativo: String, nomeRemoto: String,
                        log: LogConexao?): DestinoSync.Resultado {
        return try {
            val d = abrir(log)
            val pasta = caminho(perfil.caminhoRemoto, caminhoRelativo)
            val alvo = caminho(pasta, nomeRemoto)
            try {
                escrever(d, alvo, local)
            } catch (e: SMBApiException) {
                if (e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) {
                    criarPastas(d, pasta, log)
                    escrever(d, alvo, local)
                } else throw e
            }
            DestinoSync.Resultado.OK
        } catch (e: SMBApiException) {
            log?.excecao("SMB", e)
            // Credencial recusada e acesso negado não melhoram com repetição.
            // Marcá-los como permanentes é o que impede a fila de girar a noite
            // inteira contra um servidor que já respondeu que não.
            val permanente = e.status == NtStatus.STATUS_LOGON_FAILURE ||
                    e.status == NtStatus.STATUS_ACCESS_DENIED ||
                    e.status == NtStatus.STATUS_ACCOUNT_DISABLED ||
                    e.status == NtStatus.STATUS_BAD_NETWORK_NAME
            if (permanente) DestinoSync.Resultado.recusado("${e.status}: ${e.message}")
            else DestinoSync.Resultado.falha("${e.status}: ${e.message}")
        } catch (e: Exception) {
            log?.excecao("SMB", e)
            DestinoSync.Resultado.falha("${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}")
        }
    }

    private fun escrever(d: DiskShare, alvo: String, local: File?) {
        d.openFile(
            alvo,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        ).use { f ->
            if (local == null) return@use
            f.outputStream.use { saida ->
                local.inputStream().use { entrada -> entrada.copyTo(saida, 64 * 1024) }
            }
        }
    }

    /**
     * Cria a cadeia de pastas, ignorando as que já existem.
     *
     * `mkdir` numa pasta existente devolve `STATUS_OBJECT_NAME_COLLISION`, que
     * aqui é sucesso — a pasta existe, que é o que se queria. Tratar como erro
     * faria a criação parar no primeiro nível já existente, que é o caso comum.
     */
    private fun criarPastas(d: DiskShare, caminhoPasta: String, log: LogConexao?) {
        if (caminhoPasta.isBlank()) return
        var acumulado = ""
        for (parte in caminhoPasta.split("\\").filter { it.isNotBlank() }) {
            acumulado = if (acumulado.isEmpty()) parte else "$acumulado\\$parte"
            try {
                d.mkdir(acumulado)
                log?.ok("SMB: criada '$acumulado'")
            } catch (e: SMBApiException) {
                if (e.status == NtStatus.STATUS_OBJECT_NAME_COLLISION) continue
                throw e
            }
        }
    }

    override fun tamanhoRemoto(caminhoRelativo: String, nomeRemoto: String): Long = try {
        val d = abrir(null)
        val alvo = caminho(perfil.caminhoRemoto, caminhoRelativo, nomeRemoto)
        d.getFileInformation(alvo).standardInformation.endOfFile
    } catch (_: Exception) { -1L }

    override fun fechar() {
        try { share?.close() } catch (_: Exception) { }
        try { sessao?.close() } catch (_: Exception) { }
        try { conexao?.close() } catch (_: Exception) { }
        try { cliente?.close() } catch (_: Exception) { }
        share = null; sessao = null; conexao = null; cliente = null
    }

    /**
     * A tradução do erro para o que se faz a respeito.
     *
     * Não substitui a mensagem do servidor — ela continua no log, inteira, para
     * a TI do hospital. Esta linha é para quem está na sala e precisa saber se
     * o problema é dele ou de outra pessoa.
     */
    private fun dica(e: Exception): String = when {
        e is SMBApiException && e.status == NtStatus.STATUS_LOGON_FAILURE ->
            "Usuário ou senha recusados pelo servidor. Se a conta é de domínio, " +
            "confira se o domínio está no campo próprio e não junto do usuário."
        e is SMBApiException && e.status == NtStatus.STATUS_BAD_NETWORK_NAME ->
            "O share '${perfil.share}' não existe nesse servidor. O share é a " +
            "primeira pasta do caminho de rede; o resto vai no caminho remoto."
        e is SMBApiException && e.status == NtStatus.STATUS_ACCESS_DENIED ->
            "Autenticou, mas a conta não pode escrever aí. É permissão no servidor."
        e is java.net.SocketTimeoutException || e is java.net.ConnectException ->
            "Não houve resposta em ${perfil.host}:${perfil.portaEfetiva()}. " +
            "Confira o endereço, se o tablet está na rede certa e se a porta " +
            "está liberada no firewall."
        e is java.net.UnknownHostException ->
            "O nome '${perfil.host}' não foi resolvido. Tente o IP."
        else -> "Sem tradução conhecida para este erro — copie o log inteiro."
    }
}
