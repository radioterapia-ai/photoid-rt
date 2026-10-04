package com.radioterapia.ai.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * O ÚNICO arquivo novo desta função que toca a rede.
 *
 * Está sozinho de propósito. O ritual de validação quebra o build se primitiva
 * de rede aparecer fora da lista de arquivos autorizados, e acrescentar um
 * arquivo àquela lista aparece no diff. Concentrar aqui o `URL`, o
 * `HttpURLConnection` e o download significa que a revisão de «o que este app
 * manda para fora» continua cabendo numa tela.
 *
 * POR QUE NÃO A API DO GITHUB. `api.github.com` limita a 60 pedidos por hora
 * por IP quando não há token, e uma clínica inteira sai por um IP só: vinte
 * tablets atrás do mesmo NAT esgotariam a cota e a checagem passaria a falhar
 * em silêncio — o pior modo de falha possível, porque parece «não tem versão
 * nova». O link permanente de release não tem essa cota, não pede token, e por
 * isso nenhum segredo precisa viajar dentro do APK.
 *
 * A PERGUNTA FALHA CALADA. Sem DNS, sem rota, tempo esgotado, 404, JSON torto:
 * [checar] devolve `null`. Os tablets rodam a maior parte do tempo em
 * intranete, então não ter internet é o estado NORMAL, não um erro — e avisar
 * sobre isso seria treinar a equipe a ignorar avisos.
 *
 * O DOWNLOAD DIZ POR QUE FALHOU. [baixarApk] só roda porque alguém tocou no
 * botão e está esperando, e a mensagem certa depende do motivo: cancelar não
 * pede mensagem, falta de rede pede «tente de novo», e hash diferente pede
 * «não instale». Por isso ele devolve [Download], e não sim ou não.
 */
object AtualizacaoRemota {

    /**
     * O endereço do arquivo de versão.
     *
     * É o link PERMANENTE da última release: o GitHub responde 302 para a tag
     * mais recente, e o arquivo vem de lá. Não precisa mudar quando a versão
     * muda, que é justamente o ponto — caminho que precisa de manutenção a cada
     * release é caminho que uma release vai esquecer.
     */
    const val URL_VERSAO =
        "https://github.com/radioterapia-ai/photoid-rt/releases/latest/download/version.json"

    /** Página da release, para quem preferir baixar à mão. */
    const val URL_RELEASES =
        "https://github.com/radioterapia-ai/photoid-rt/releases/latest"

    private const val TIMEOUT_MS = 12_000

    /**
     * Resultado da pergunta «qual a última versão publicada».
     *
     * [urlApk] vem do campo `apkUrl` do version.json; a regra de leitura, e o
     * motivo de o campo não se chamar `apk`, estão em
     * [GerenciadorAtualizacao.interpretarVersao].
     */
    data class Publicada(
        val versionCode: Int,
        val versionName: String,
        val urlApk: String,
        val sha256: String,
        val minSdk: Int,
        val notas: String
    )

    /** Como terminou um [baixarApk]. */
    enum class Download {
        /** O arquivo chegou inteiro e o SHA-256 bate com o publicado. */
        OK,

        /** Quem chamou desistiu no meio. Não é erro e não pede mensagem. */
        CANCELADO,

        /** Rede, servidor, endereço não HTTPS ou disco: nada foi conferido. */
        FALHA,

        /** Chegou, mas não é o arquivo publicado. Foi apagado. */
        HASH_DIFERENTE,
    }

    /**
     * Pergunta ao repositório qual a última versão. `null` em qualquer falha.
     *
     * Não decide nada: só lê. Interpretar o version.json, comparar com a versão
     * instalada e concluir se cabe atualizar é do [GerenciadorAtualizacao], que
     * não tem rede e por isso pode ser testado.
     */
    fun checar(): Publicada? {
        val txt = baixarTexto(URL_VERSAO) ?: return null
        return GerenciadorAtualizacao.interpretarVersao(txt)
    }

    /**
     * Baixa o APK para [destino] e confere o SHA-256 contra o publicado.
     *
     * Só devolve [Download.OK] quando o arquivo chegou inteiro E o hash bate.
     * Em qualquer outro desfecho o arquivo é apagado: entregar ao instalador um
     * APK que não é o publicado seria pior do que não atualizar, e a assinatura
     * do Android pegaria o caso grosseiro mas não o arquivo truncado.
     *
     * [cancelado] é consultado a cada bloco de 64 KB lido.
     *
     * [aoProgresso] recebe (baixado, total). total = -1 quando o servidor não
     * informa tamanho.
     */
    fun baixarApk(
        url: String,
        destino: File,
        sha256Esperado: String,
        cancelado: () -> Boolean = { false },
        aoProgresso: (Long, Long) -> Unit = { _, _ -> }
    ): Download {
        var conexao: HttpURLConnection? = null
        try {
            if (!url.startsWith("https://")) return Download.FALHA
            conexao = abrir(url)
            if (conexao.responseCode !in 200..299) return Download.FALHA
            val total = conexao.contentLengthLong
            destino.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var lidos = 0L
            conexao.inputStream.use { entrada ->
                destino.outputStream().use { saida ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelado()) { destino.delete(); return Download.CANCELADO }
                        val n = entrada.read(buf)
                        if (n <= 0) break
                        saida.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        lidos += n
                        aoProgresso(lidos, total)
                    }
                }
            }
            val obtido = digest.digest().joinToString("") { "%02x".format(it) }
            if (!obtido.equals(sha256Esperado, ignoreCase = true)) {
                destino.delete()
                return Download.HASH_DIFERENTE
            }
            return Download.OK
        } catch (_: Throwable) {
            destino.delete()
            return Download.FALHA
        } finally {
            conexao?.disconnect()
        }
    }

    // ------------------------------------------------------------------

    private fun baixarTexto(endereco: String): String? {
        var c: HttpURLConnection? = null
        return try {
            if (!endereco.startsWith("https://")) return null
            c = abrir(endereco)
            if (c.responseCode !in 200..299) null
            // Teto de leitura: o version.json tem centenas de bytes. Ler sem
            // limite deixaria uma resposta inesperada crescer na memória do
            // tablet.
            else c.inputStream.bufferedReader().use { it.readText().take(8_192) }
        } catch (_: Throwable) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun abrir(endereco: String): HttpURLConnection =
        (URL(endereco).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            // O link permanente responde 302 para a tag atual, então seguir
            // redirecionamento é parte do funcionamento normal, não exceção.
            setRequestProperty("Accept", "application/json, application/octet-stream")
            setRequestProperty("User-Agent", "PhotoID-RT")
        }
}
