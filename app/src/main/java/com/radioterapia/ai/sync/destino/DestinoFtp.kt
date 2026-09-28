package com.radioterapia.ai.sync.destino

import com.radioterapia.ai.sync.DestinoSync
import com.radioterapia.ai.sync.LogConexao
import com.radioterapia.ai.sync.PerfilSync
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File

/**
 * Destino FTP.
 *
 * Existe porque serviço antigo tem FTP e não tem mais nada — e trocar o
 * servidor de arquivos do hospital não é decisão do setor de radioterapia.
 *
 * MODO PASSIVO SEMPRE. No ativo é o servidor que abre a conexão de dados de
 * volta para o tablet, e nenhuma rede de hospital deixa isso passar. O sintoma
 * é característico e engana: o login funciona, a listagem funciona, e a
 * transferência trava até o timeout — parece problema de arquivo grande.
 *
 * MODO BINÁRIO SEMPRE. O padrão do protocolo é ASCII, que "conserta" fim de
 * linha no meio do fluxo. Num JPEG isso é corrupção silenciosa: o arquivo chega,
 * tem tamanho parecido, e não abre.
 */
class DestinoFtp(
    private val perfil: PerfilSync,
    private val senha: String,
) : DestinoSync {

    private var ftp: FTPClient? = null

    private fun abrir(log: LogConexao?): FTPClient {
        ftp?.let { if (it.isConnected) return it }
        fechar()
        val c = FTPClient()
        c.connectTimeout = 20_000
        c.defaultTimeout = 20_000
        log?.passo("FTP: conectando em ${perfil.host}:${perfil.portaEfetiva()}")
        c.connect(perfil.host.trim(), perfil.portaEfetiva())
        c.setSoTimeout(60_000)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            val r = c.replyString
            try { c.disconnect() } catch (_: Exception) { }
            throw java.io.IOException("servidor recusou a conexão: $r")
        }
        log?.ok("FTP: conectado — ${c.replyString.trim()}")

        val usuario = perfil.usuario.trim().ifBlank { "anonymous" }
        log?.passo("FTP: autenticando como '$usuario'")
        if (perfil.usuario.isNotBlank()) log?.senha(senha)
        if (!c.login(usuario, if (perfil.usuario.isBlank()) "photoid@rt" else senha)) {
            val r = c.replyString
            try { c.disconnect() } catch (_: Exception) { }
            throw java.io.IOException("login recusado: $r")
        }
        log?.ok("FTP: autenticado")
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.controlEncoding = "UTF-8"
        log?.passo("FTP: modo passivo e transferência binária")
        ftp = c
        return c
    }

    private fun caminho(vararg partes: String): String =
        partes.filter { it.isNotBlank() }
            .joinToString("/") { it.trim('/', '\\').replace('\\', '/') }

    /**
     * Cria a cadeia de pastas sem perguntar se existe.
     *
     * `MKD` numa pasta existente devolve 550, que aqui é sucesso — a pasta
     * existe, que é o que se queria. Perguntar antes com `CWD` custaria uma ida
     * ao servidor por nível e falharia igual onde não há permissão de leitura
     * acima do destino.
     */
    private fun criarPastas(c: FTPClient, caminhoPasta: String, log: LogConexao?) {
        if (caminhoPasta.isBlank()) return
        var acumulado = ""
        for (parte in caminhoPasta.split('/').filter { it.isNotBlank() }) {
            acumulado = if (acumulado.isEmpty()) parte else "$acumulado/$parte"
            if (c.makeDirectory(acumulado)) log?.ok("FTP: criada '$acumulado'")
        }
    }

    override fun testar(log: LogConexao): Boolean = try {
        testarInterno(log)
    } catch (e: Exception) {
        log.excecao("FTP", e)
        false
    }

    private fun testarInterno(log: LogConexao): Boolean {
        val c = abrir(log)
        val pasta = caminho(perfil.caminhoRemoto)
        val nomeProva = ".photoid_teste_${System.currentTimeMillis()}.tmp"
        val alvo = caminho(pasta, nomeProva)
        log.passo("FTP: gravando arquivo de prova em '$alvo'")
        var ok = c.storeFile(alvo, java.io.ByteArrayInputStream(ByteArray(0)))
        if (!ok) {
            log.passo("FTP: não gravou (${c.replyString.trim()}). Criando '$pasta'.")
            criarPastas(c, pasta, log)
            ok = c.storeFile(alvo, java.io.ByteArrayInputStream(ByteArray(0)))
        }
        if (!ok) {
            log.falha("FTP: gravação recusada — ${c.replyString.trim()}")
            log.passo("Confira o caminho remoto e a permissão de escrita da conta.")
            return false
        }
        log.ok("FTP: gravação confirmada")
        if (c.deleteFile(alvo)) log.ok("FTP: arquivo de prova removido")
        else log.passo("FTP: não foi possível remover o arquivo de prova. Ele fica lá, com 0 byte.")
        return true
    }

    override fun enviar(local: File, caminhoRelativo: String, nomeRemoto: String,
                        log: LogConexao?): DestinoSync.Resultado {
        return try {
            val c = abrir(log)
            val pasta = caminho(perfil.caminhoRemoto, caminhoRelativo)
            val alvo = caminho(pasta, nomeRemoto)
            var ok = local.inputStream().use { c.storeFile(alvo, it) }
            if (!ok) {
                criarPastas(c, pasta, log)
                ok = local.inputStream().use { c.storeFile(alvo, it) }
            }
            if (ok) DestinoSync.Resultado.OK
            else {
                val r = c.replyString.trim()
                // 530 é "não autenticado", 550 no store é permissão ou caminho.
                // Os dois não melhoram sozinhos; repetir a noite inteira só
                // enche o log do servidor.
                if (c.replyCode == 530 || c.replyCode == 550)
                    DestinoSync.Resultado.recusado("FTP ${c.replyCode}: $r")
                else DestinoSync.Resultado.falha("FTP ${c.replyCode}: $r")
            }
        } catch (e: Exception) {
            log?.excecao("FTP", e)
            DestinoSync.Resultado.falha("${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}")
        }
    }

    override fun tamanhoRemoto(caminhoRelativo: String, nomeRemoto: String): Long = try {
        val c = abrir(null)
        val alvo = caminho(perfil.caminhoRemoto, caminhoRelativo, nomeRemoto)
        c.mlistFile(alvo)?.size ?: c.listFiles(alvo).firstOrNull()?.size ?: -1L
    } catch (_: Exception) { -1L }

    override fun fechar() {
        try { ftp?.logout() } catch (_: Exception) { }
        try { ftp?.disconnect() } catch (_: Exception) { }
        ftp = null
    }
}
