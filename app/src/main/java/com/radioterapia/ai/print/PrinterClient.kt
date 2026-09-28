package com.radioterapia.ai.print

import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Envia um PDF para uma impressora de rede usando dois protocolos com fallback automático:
 *
 *  1. JetDirect (porta 9100, RAW) - simples, funciona em 90%+ das impressoras corporativas
 *  2. IPP (porta 631, HTTP POST) - mais moderno, fallback se JetDirect falhar
 *
 * Não converte o PDF para PostScript ou PCL — assume que a impressora aceita PDF nativo,
 * o que é verdade para a maioria das impressoras profissionais (HP LaserJet com PDF Direct,
 * Brother, Lexmark, Xerox WorkCentre, Konica Minolta, etc).
 */
class PrinterClient(
    private val ip: String,
    private val nomeFila: String = "lp1"
) {

    data class ResultadoImpressao(
        val sucesso: Boolean,
        val protocoloUsado: String,
        val mensagem: String
    )

    /**
     * Tenta JetDirect (9100) e em caso de falha, tenta IPP (631).
     * Retorna o resultado consolidado.
     */
    /**
     * @param duplexMode "simplex" (1 página por folha) | "long" (frente e verso,
     *        virar na borda LONGA / flip horizontal) | "short" (borda CURTA /
     *        flip vertical). Aplicado via PJL no JetDirect e via atributo IPP
     *        "sides" no protocolo IPP.
     */
    fun imprimirPdf(arquivo: File, duplexMode: String = "simplex",
                    timeoutMs: Int = 15_000): ResultadoImpressao {
        if (!arquivo.exists() || arquivo.length() == 0L) {
            return ResultadoImpressao(false, "—", "Arquivo PDF não existe ou está vazio.")
        }

        // 1ª tentativa: JetDirect com PJL (define duplex/borda na impressora)
        val resJet = tentarJetDirect(arquivo, duplexMode, timeoutMs)
        if (resJet.sucesso) return resJet

        // 2ª tentativa: IPP com envelope Print-Job (atributo "sides")
        val resIpp = tentarIppEnvelope(arquivo, duplexMode, timeoutMs)
        if (resIpp.sucesso) return resIpp

        // 3ª tentativa: POST simples (sem duplex — compatibilidade máxima)
        val resSimples = tentarIppSimples(arquivo, timeoutMs)
        if (resSimples.sucesso) return resSimples

        return ResultadoImpressao(
            false, "todos",
            "Falha nos protocolos.\nJetDirect (9100): ${resJet.mensagem}\n" +
                "IPP (631): ${resIpp.mensagem}\nIPP simples: ${resSimples.mensagem}"
        )
    }

    /**
     * JetDirect / RAW: abre socket TCP na porta 9100 e envia bytes do PDF direto.
     * Funciona em quase toda impressora corporativa.
     */
    private fun tentarJetDirect(arquivo: File, duplexMode: String, timeoutMs: Int): ResultadoImpressao {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 9100), timeoutMs)
                socket.soTimeout = timeoutMs

                val saida = DataOutputStream(socket.getOutputStream())
                // Cabeçalho PJL: configura frente-e-verso ANTES do PDF.
                val uel = "\u001B%-12345X"
                val pjl = StringBuilder()
                    .append(uel).append("@PJL\r\n")
                    .apply {
                        when (duplexMode) {
                            "long" -> append("@PJL SET DUPLEX=ON\r\n@PJL SET BINDING=LONGEDGE\r\n")
                            "short" -> append("@PJL SET DUPLEX=ON\r\n@PJL SET BINDING=SHORTEDGE\r\n")
                            else -> append("@PJL SET DUPLEX=OFF\r\n")
                        }
                    }
                    .append("@PJL ENTER LANGUAGE=PDF\r\n")
                    .toString()
                saida.write(pjl.toByteArray(Charsets.US_ASCII))
                FileInputStream(arquivo).use { entrada ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val lidos = entrada.read(buffer)
                        if (lidos <= 0) break
                        saida.write(buffer, 0, lidos)
                    }
                }
                saida.write(uel.toByteArray(Charsets.US_ASCII))
                saida.flush()
            }
            ResultadoImpressao(true, "JetDirect (9100)", "Enviado com sucesso (PJL $duplexMode).")
        } catch (e: Exception) {
            ResultadoImpressao(false, "JetDirect (9100)", e.message ?: "Erro desconhecido")
        }
    }

    /** Envelope IPP Print-Job (RFC 8011) com atributo "sides" — permite duplex
     *  em impressoras que ignoram PJL mas falam IPP corretamente. */
    private fun tentarIppEnvelope(arquivo: File, duplexMode: String, timeoutMs: Int): ResultadoImpressao {
        return try {
            val sides = when (duplexMode) {
                "long" -> "two-sided-long-edge"
                "short" -> "two-sided-short-edge"
                else -> "one-sided"
            }
            val printerUri = "ipp://$ip:631/printers/$nomeFila"
            val cab = java.io.ByteArrayOutputStream()
            val dos = DataOutputStream(cab)
            fun attr(tag: Int, nome: String, valor: String) {
                dos.writeByte(tag)
                dos.writeShort(nome.length); dos.write(nome.toByteArray(Charsets.UTF_8))
                dos.writeShort(valor.toByteArray(Charsets.UTF_8).size)
                dos.write(valor.toByteArray(Charsets.UTF_8))
            }
            dos.writeByte(1); dos.writeByte(1)      // versão IPP 1.1
            dos.writeShort(0x0002)                   // operação: Print-Job
            dos.writeInt(1)                          // request-id
            dos.writeByte(0x01)                      // operation-attributes
            attr(0x47, "attributes-charset", "utf-8")
            attr(0x48, "attributes-natural-language", "en")
            attr(0x45, "printer-uri", printerUri)
            attr(0x42, "requesting-user-name", "PhotoID-RT")
            attr(0x49, "document-format", "application/pdf")
            dos.writeByte(0x02)                      // job-attributes
            attr(0x44, "sides", sides)               // keyword
            dos.writeByte(0x03)                      // end-of-attributes
            dos.flush()

            val url = URL("http://$ip:631/printers/$nomeFila")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/ipp")
            conn.outputStream.use { saida ->
                saida.write(cab.toByteArray())
                FileInputStream(arquivo).use { entrada ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val lidos = entrada.read(buffer)
                        if (lidos <= 0) break
                        saida.write(buffer, 0, lidos)
                    }
                }
            }
            val resp = conn.responseCode
            if (resp in 200..299) {
                ResultadoImpressao(true, "IPP (631)", "Print-Job aceito (HTTP $resp, sides=$sides).")
            } else {
                ResultadoImpressao(false, "IPP (631)", "HTTP $resp - ${conn.responseMessage}")
            }
        } catch (e: Exception) {
            ResultadoImpressao(false, "IPP (631)", e.message ?: "Erro desconhecido")
        }
    }

    /**
     * IPP / Internet Printing Protocol via HTTP POST simples.
     * Envia o PDF como corpo de POST para http://ip:631/printers/<fila>
     * Algumas impressoras não exigem o envelope IPP completo e aceitam só o PDF como body.
     */
    private fun tentarIppSimples(arquivo: File, timeoutMs: Int): ResultadoImpressao {
        return try {
            val url = URL("http://$ip:631/printers/$nomeFila")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/pdf")
            conn.setRequestProperty("Content-Length", arquivo.length().toString())

            FileInputStream(arquivo).use { entrada ->
                conn.outputStream.use { saida ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val lidos = entrada.read(buffer)
                        if (lidos <= 0) break
                        saida.write(buffer, 0, lidos)
                    }
                }
            }

            val resp = conn.responseCode
            if (resp in 200..299) {
                ResultadoImpressao(true, "IPP simples (631)", "Enviado com sucesso (HTTP $resp).")
            } else {
                ResultadoImpressao(false, "IPP simples (631)", "HTTP $resp - ${conn.responseMessage}")
            }
        } catch (e: Exception) {
            ResultadoImpressao(false, "IPP (631)", e.message ?: "Erro desconhecido")
        }
    }

    /**
     * Teste de conectividade — apenas tenta abrir socket nas duas portas.
     * Não envia nada. Útil para o botão "Imprimir página de teste".
     */
    fun testarConexao(timeoutMs: Int = 5_000): ResultadoImpressao {
        var jetOk = false
        var ippOk = false
        var msgJet = ""
        var msgIpp = ""

        try {
            Socket().use { it.connect(InetSocketAddress(ip, 9100), timeoutMs); jetOk = true }
        } catch (e: Exception) { msgJet = e.message ?: "" }

        try {
            Socket().use { it.connect(InetSocketAddress(ip, 631), timeoutMs); ippOk = true }
        } catch (e: Exception) { msgIpp = e.message ?: "" }

        return when {
            jetOk && ippOk -> ResultadoImpressao(true, "Ambos", "JetDirect e IPP respondendo.")
            jetOk -> ResultadoImpressao(true, "JetDirect (9100)", "Apenas JetDirect respondeu (IPP: $msgIpp)")
            ippOk -> ResultadoImpressao(true, "IPP (631)", "Apenas IPP respondeu (JetDirect: $msgJet)")
            else -> ResultadoImpressao(false, "—",
                "Nenhum protocolo respondeu. Verifique IP e rede.\nJetDirect: $msgJet\nIPP: $msgIpp")
        }
    }
}

