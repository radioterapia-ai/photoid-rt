package com.radioterapia.ai.smb

import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbClient(
    private val host: String,
    private val porta: Int,
    private val dominio: String,
    private val usuario: String,
    private val senha: String,
    private val protocolo: String = "SMB2" // Pode ser "SMB1", "SMB2", "SMB3"
) {

    data class TentativaResultado(
        val sucesso: Boolean,
        val formatoUsuario: String,
        val mensagemErro: String? = null
    )

    private val client: SMBClient by lazy {
        val builder = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withDfsEnabled(false)
            // ASSINATURA sempre. Protege contra adulteração do conteúdo em
            // trânsito e é suportada por todo servidor SMB2+. Custo de CPU
            // desprezível para o volume aqui (um CSV e algumas fotos).
            .withSigningRequired(true)

        when (protocolo.uppercase()) {
            "SMB3" -> {
                // CIFRAGEM só existe no SMB3. O que trafega por aqui é foto de
                // paciente (TreatmentPhotoFetcher lê a pasta do servidor) e a
                // base de pacientes em CSV — sem isto, tudo em claro na rede da
                // clínica. Só é ligada quando a configuração declara SMB3,
                // porque exigir cifragem de um servidor SMB2 derruba a conexão
                // com erro que não explica a causa.
                builder.withEncryptData(true)
            }
            // SMB1 e SMB2: o smbj negocia SMB2/3 e não implementa SMB1. Não há
            // o que configurar aqui — a escolha "SMB1" na tela existe por
            // compatibilidade de rótulo com servidores antigos e cai no
            // comportamento padrão.
        }

        SMBClient(builder.build())
    }

    fun enviarArquivo(
        share: String,
        caminhoRelativo: String,
        nomeArquivoRemoto: String,
        arquivoLocal: File
    ): TentativaResultado {
        val tentativas = construirTentativas()
        var ultimoErro: String? = null

        for (tentativa in tentativas) {
            try {
                client.connect(host, porta).use { conn ->
                    conn.authenticate(tentativa.authContext).use { sess ->
                        val diskShare = sess.connectShare(share) as DiskShare
                        criarPastasRecursivamente(diskShare, caminhoRelativo)

                        val caminhoFinal = if (caminhoRelativo.isEmpty()) nomeArquivoRemoto
                        else "$caminhoRelativo\\$nomeArquivoRemoto"

                        diskShare.openFile(
                            caminhoFinal,
                            EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            null
                        ).use { f ->
                            arquivoLocal.inputStream().use { entrada ->
                                copiarParaSmb(entrada, f.outputStream)
                            }
                        }
                        return TentativaResultado(true, tentativa.descricao)
                    }
                }
            } catch (e: SMBApiException) {
                ultimoErro = "${tentativa.descricao}: ${e.message}"
                continue
            } catch (e: Exception) {
                return TentativaResultado(false, tentativa.descricao, "Erro de rede: ${e.message}")
            }
        }
        return TentativaResultado(false, "todas as tentativas", ultimoErro)
    }

    fun testarConexao(share: String): TentativaResultado {
        val tentativas = construirTentativas()
        var ultimoErro: String? = null
        for (tentativa in tentativas) {
            try {
                client.connect(host, porta).use { conn ->
                    conn.authenticate(tentativa.authContext).use { sess ->
                        sess.connectShare(share).close()
                        return TentativaResultado(true, tentativa.descricao)
                    }
                }
            } catch (e: SMBApiException) {
                ultimoErro = "${tentativa.descricao}: ${e.message}"
                continue
            } catch (e: Exception) {
                return TentativaResultado(false, tentativa.descricao, "Erro de rede: ${e.message}")
            }
        }
        return TentativaResultado(false, "todas as tentativas", ultimoErro)
    }

    /**
     * Lista as pastas dentro de um caminho. Útil para verificar se o paciente
     * já existe no servidor (consulta complementar ao cache local).
     */
    fun listarPastas(share: String, caminhoRelativo: String): List<String> {
        val tentativas = construirTentativas()
        for (tentativa in tentativas) {
            try {
                client.connect(host, porta).use { conn ->
                    conn.authenticate(tentativa.authContext).use { sess ->
                        val diskShare = sess.connectShare(share) as DiskShare
                        if (!diskShare.folderExists(caminhoRelativo)) return emptyList()
                        return diskShare.list(caminhoRelativo)
                            .filter { it.fileName != "." && it.fileName != ".." }
                            .map { it.fileName }
                    }
                }
            } catch (e: SMBApiException) { continue }
            catch (e: Exception) { return emptyList() }
        }
        return emptyList()
    }

    private data class Tentativa(val authContext: AuthenticationContext, val descricao: String)

    private fun construirTentativas(): List<Tentativa> {
        val lista = mutableListOf<Tentativa>()
        val variantesUsuario = listOfNotNull(
            usuario.takeIf { it.isNotEmpty() },
            usuario.lowercase().takeIf { it.isNotEmpty() && it != usuario }
        )
        val variantesSenha = listOf(
            senha,
            urlEncodeSenha(senha).takeIf { it != senha }
        ).filterNotNull()

        for (varSenha in variantesSenha) {
            for (varUsuario in variantesUsuario) {
                lista.add(Tentativa(
                    AuthenticationContext(varUsuario, varSenha.toCharArray(), dominio),
                    "UPN ($varUsuario@$dominio)"
                ))
                lista.add(Tentativa(
                    AuthenticationContext("$dominio\\$varUsuario", varSenha.toCharArray(), ""),
                    "NetBIOS ($dominio\\$varUsuario)"
                ))
                lista.add(Tentativa(
                    AuthenticationContext("$dominio;$varUsuario", varSenha.toCharArray(), ""),
                    "JCIFS ($dominio;$varUsuario)"
                ))
                lista.add(Tentativa(
                    AuthenticationContext(varUsuario, varSenha.toCharArray(), ""),
                    "Sem domínio ($varUsuario)"
                ))
            }
        }
        return lista
    }

    private fun urlEncodeSenha(s: String): String {
        val mapa = mapOf(
            '#' to "%23", '@' to "%40", '&' to "%26",
            '+' to "%2B", '%' to "%25", ' ' to "%20",
            '/' to "%2F", ':' to "%3A", ';' to "%3B"
        )
        val sb = StringBuilder()
        for (c in s) sb.append(mapa[c] ?: c.toString())
        return sb.toString()
    }

    private fun criarPastasRecursivamente(share: DiskShare, caminho: String) {
        if (caminho.isEmpty()) return
        val partes = caminho.split("\\").filter { it.isNotEmpty() }
        var atual = ""
        for (parte in partes) {
            atual = if (atual.isEmpty()) parte else "$atual\\$parte"
            if (!share.folderExists(atual)) share.mkdir(atual)
        }
    }

    private fun copiarParaSmb(entrada: InputStream, saida: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val lidos = entrada.read(buffer)
            if (lidos <= 0) break
            saida.write(buffer, 0, lidos)
        }
    }
}

