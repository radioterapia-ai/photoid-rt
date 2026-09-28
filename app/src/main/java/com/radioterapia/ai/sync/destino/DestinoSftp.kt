package com.radioterapia.ai.sync.destino

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.radioterapia.ai.sync.DestinoSync
import com.radioterapia.ai.sync.LogConexao
import com.radioterapia.ai.sync.PerfilSync
import java.io.File
import java.util.Properties

/**
 * Destino SFTP — SSH, e não "FTP com S".
 *
 * A CHAVE DO SERVIDOR NÃO É VERIFICADA, e isto precisa estar escrito.
 * `StrictHostKeyChecking=no` aceita qualquer servidor que atenda no endereço
 * configurado. A alternativa honesta seria pedir ao técnico de radioterapia que
 * conferisse o fingerprint SSH do servidor na primeira conexão — uma pergunta
 * que ele não tem como responder, e que ele responderia "sim" sempre, o que é
 * pior que não perguntar porque cria aparência de verificação.
 *
 * O que sustenta a decisão: o destino é um endereço da rede interna da própria
 * instituição, digitado pela TI dela, e o SSH continua cifrando o transporte. O
 * que se perde é a proteção contra alguém que já esteja DENTRO da rede do
 * hospital se passando pelo servidor de arquivos. Está registrado aqui para que
 * a escolha seja reversível por quem souber o que está trocando — e não uma
 * linha de configuração que ninguém lembra de onde veio.
 *
 * A BIBLIOTECA É O FORK DO mwiede. O JSch original parou em 2018 e não negocia
 * os algoritmos de troca de chave que o OpenSSH atual exige; o erro é
 * "Algorithm negotiation fail", que não parece nem um pouco com "biblioteca
 * velha demais para este servidor".
 */
class DestinoSftp(
    private val perfil: PerfilSync,
    private val senha: String,
) : DestinoSync {

    private var sessao: Session? = null
    private var canal: ChannelSftp? = null

    private fun abrir(log: LogConexao?): ChannelSftp {
        canal?.let { if (it.isConnected) return it }
        fechar()
        val jsch = JSch()
        log?.passo("SFTP: conectando em ${perfil.host}:${perfil.portaEfetiva()}")
        val s = jsch.getSession(perfil.usuario.trim(), perfil.host.trim(), perfil.portaEfetiva())
        s.setPassword(senha)
        log?.passo("SFTP: usuário '${perfil.usuario}'")
        log?.senha(senha)
        s.setConfig(Properties().apply {
            // Ver o cabeçalho desta classe: a decisão está documentada lá.
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password,keyboard-interactive")
        })
        s.timeout = 20_000
        s.connect()
        sessao = s
        log?.ok("SFTP: sessão SSH aberta — servidor ${s.serverVersion}")
        val c = s.openChannel("sftp") as ChannelSftp
        c.connect(20_000)
        canal = c
        log?.ok("SFTP: canal aberto")
        return c
    }

    private fun caminho(vararg partes: String): String =
        partes.filter { it.isNotBlank() }
            .joinToString("/") { it.trim('/', '\\').replace('\\', '/') }

    /** `mkdir` em cada nível; "já existe" é sucesso. */
    private fun criarPastas(c: ChannelSftp, caminhoPasta: String, log: LogConexao?) {
        if (caminhoPasta.isBlank()) return
        var acumulado = ""
        for (parte in caminhoPasta.split('/').filter { it.isNotBlank() }) {
            acumulado = if (acumulado.isEmpty()) parte else "$acumulado/$parte"
            try {
                c.mkdir(acumulado)
                log?.ok("SFTP: criada '$acumulado'")
            } catch (e: SftpException) {
                // SSH_FX_FAILURE (4) é o que o OpenSSH devolve para "já existe".
                // Não há código dedicado a isso no protocolo até a versão 3, que
                // é a que praticamente todo servidor fala — por isso a única
                // saída é tratar o genérico como sucesso aqui.
                if (e.id != ChannelSftp.SSH_FX_FAILURE) throw e
            }
        }
    }

    override fun testar(log: LogConexao): Boolean = try {
        val c = abrir(log)
        val pasta = caminho(perfil.caminhoRemoto)
        val nomeProva = ".photoid_teste_${System.currentTimeMillis()}.tmp"
        val alvo = caminho(pasta, nomeProva)
        log.passo("SFTP: gravando arquivo de prova em '$alvo'")
        try {
            c.put(java.io.ByteArrayInputStream(ByteArray(0)), alvo)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                log.passo("SFTP: caminho não existe, criando '$pasta'")
                criarPastas(c, pasta, log)
                c.put(java.io.ByteArrayInputStream(ByteArray(0)), alvo)
            } else throw e
        }
        log.ok("SFTP: gravação confirmada")
        try { c.rm(alvo); log.ok("SFTP: arquivo de prova removido") }
        catch (_: Exception) {
            log.passo("SFTP: não foi possível remover o arquivo de prova. Ele fica lá, com 0 byte.")
        }
        true
    } catch (e: Exception) {
        log.excecao("SFTP", e)
        log.passo(dica(e))
        false
    }

    override fun enviar(local: File, caminhoRelativo: String, nomeRemoto: String,
                        log: LogConexao?): DestinoSync.Resultado {
        return try {
            val c = abrir(log)
            val pasta = caminho(perfil.caminhoRemoto, caminhoRelativo)
            val alvo = caminho(pasta, nomeRemoto)
            try {
                local.inputStream().use { c.put(it, alvo) }
            } catch (e: SftpException) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    criarPastas(c, pasta, log)
                    local.inputStream().use { c.put(it, alvo) }
                } else throw e
            }
            DestinoSync.Resultado.OK
        } catch (e: SftpException) {
            log?.excecao("SFTP", e)
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED)
                DestinoSync.Resultado.recusado("SFTP: permissão negada — ${e.message}")
            else DestinoSync.Resultado.falha("SFTP ${e.id}: ${e.message}")
        } catch (e: JSchException) {
            log?.excecao("SFTP", e)
            // "Auth fail" e "Algorithm negotiation fail" não melhoram sozinhos.
            val permanente = e.message?.contains("Auth", ignoreCase = true) == true ||
                    e.message?.contains("negotiation", ignoreCase = true) == true
            if (permanente) DestinoSync.Resultado.recusado("SFTP: ${e.message}")
            else DestinoSync.Resultado.falha("SFTP: ${e.message}")
        } catch (e: Exception) {
            log?.excecao("SFTP", e)
            DestinoSync.Resultado.falha("${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}")
        }
    }

    override fun tamanhoRemoto(caminhoRelativo: String, nomeRemoto: String): Long = try {
        abrir(null).lstat(caminho(perfil.caminhoRemoto, caminhoRelativo, nomeRemoto)).size
    } catch (_: Exception) { -1L }

    override fun fechar() {
        try { canal?.disconnect() } catch (_: Exception) { }
        try { sessao?.disconnect() } catch (_: Exception) { }
        canal = null; sessao = null
    }

    private fun dica(e: Exception): String = when {
        e.message?.contains("Auth fail", ignoreCase = true) == true ->
            "Usuário ou senha recusados. Se o servidor só aceita chave, senha não passa."
        e.message?.contains("negotiation", ignoreCase = true) == true ->
            "Servidor e app não têm algoritmo em comum. Copie o log e mostre à TI: " +
            "a lista negociada está nele."
        e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
            "Não houve resposta em ${perfil.host}:${perfil.portaEfetiva()}. SFTP costuma " +
            "ser a porta 22, e não a 21 do FTP."
        else -> "Sem tradução conhecida para este erro — copie o log inteiro."
    }
}
