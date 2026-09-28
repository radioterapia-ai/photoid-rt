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
 * TUDO AQUI FALHA CALADO. Sem DNS, sem rota, tempo esgotado, 404, JSON torto:
 * devolve `null`. Os tablets rodam a maior parte do tempo em intranite, então
 * não ter internet é o estado NORMAL, não um erro — e avisar sobre isso seria
 * treinar a equipe a ignorar avisos. Quem quiser distinguir «não achei versão
 * nova» de «não consegui perguntar» usa [checar] e olha o retorno.
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

    /** Resultado da pergunta «qual a última versão publicada». */
    data class Publicada(
        val versionCode: Int,
        val versionName: String,
        val urlApk: String,
        val sha256: String,
        val minSdk: Int,
        val notas: String
    )

    /**
     * Pergunta ao repositório qual a última versão. `null` em qualquer falha.
     *
     * Não decide nada: só lê. Comparar com a versão instalada e concluir se
     * cabe atualizar é do [GerenciadorAtualizacao], que não tem rede e por isso
     * pode ser testado.
     */
    fun checar(): Publicada? = try {
        val txt = baixarTexto(URL_VERSAO)
        // Sem `return` aqui de propósito: corpo-expressão não aceita `return`,
        // e trocar por corpo-bloco só para caber um `return` precoce deixaria a
        // função mais longa sem ficar mais clara.
        if (txt == null) null else {
        val o = org.json.JSONObject(txt)
        val code = o.optInt("versionCode", 0)
        val nome = o.optString("versionName", "")
        val apk = o.optString("apk", "")
        val sha = o.optString("sha256", "").lowercase()
        // Um version.json sem os quatro campos é version.json quebrado, e
        // adivinhar o que falta seria oferecer instalação de coisa não
        // conferida. Sem eles, nao ha versao publicada.
        if (code <= 0 || nome.isBlank() || apk.isBlank() || sha.length != 64) null
        else Publicada(
            versionCode = code,
            versionName = nome,
            urlApk = apk,
            sha256 = sha,
            minSdk = o.optInt("minSdk", 24),
            notas = o.optString("notas", "")
        )
        }
    } catch (_: Throwable) {
        // Throwable, e não Exception: JSON torto lança Exception, mas uma API
        // ausente lança Error, e nas duas situacoes a resposta certa é a mesma
        // — o app segue sem novidade.
        null
    }

    /**
     * Baixa o APK para [destino] e confere o SHA-256 contra o publicado.
     *
     * Devolve `true` só quando o arquivo chegou inteiro E o hash bate. Hash
     * diferente apaga o arquivo: entregar ao instalador um APK que não é o
     * publicado seria pior do que não atualizar, e a assinatura do Android
     * pegaria o caso grosseiro mas não o arquivo truncado.
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
    ): Boolean {
        var conexao: HttpURLConnection? = null
        try {
            if (!url.startsWith("https://")) return false
            conexao = abrir(url)
            if (conexao.responseCode !in 200..299) return false
            val total = conexao.contentLengthLong
            destino.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var lidos = 0L
            conexao.inputStream.use { entrada ->
                destino.outputStream().use { saida ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelado()) { destino.delete(); return false }
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
                return false
            }
            return true
        } catch (_: Throwable) {
            destino.delete()
            return false
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
