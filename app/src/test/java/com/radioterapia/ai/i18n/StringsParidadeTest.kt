package com.radioterapia.ai.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guardião de tradução. Lê os `strings.xml` e `plurals.xml` direto do source e
 * falha se algo divergir entre idiomas.
 *
 * Motivação real: `cache_confirm_warning` chegou a existir só em EN/ES; num
 * tablet em português o diálogo de exclusão quebraria com ResourceNotFound.
 * O lint pega isso no build, este teste pega antes — e roda em segundos.
 *
 * OS IDIOMAS SÃO DESCOBERTOS, NÃO LISTADOS. A versão anterior tinha "values-en"
 * e "values-es" escritos no código. Ao passar de 3 para 12 idiomas ela
 * continuaria PASSANDO enquanto cobria um quarto da superfície — que é o pior
 * tipo de teste, o que fica verde sem olhar. Descobrindo pela pasta, um idioma
 * novo entra no teste no mesmo instante em que entra no app.
 */
class StringsParidadeTest {

    private val regexString =
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val regexPlural =
        Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
    private val regexItem =
        Regex("""<item\s+quantity="([^"]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    private val regexPlaceholder = Regex("""%(\d+)\$[sdf]|%[sdf]""")

    /** Só qualificador de IDIOMA: `values-fr`, `values-pt-rBR`. Fica de fora o
     *  qualificador de configuração (`values-sw600dp`, `values-night`), que não
     *  é tradução e não tem strings. */
    private val regexPastaIdioma = Regex("""^values-([a-z]{2})(?:-r[A-Z]{2})?$""")

    private val res = File("src/main/res")

    private fun idiomas(): List<Pair<String, File>> {
        assertTrue("res/ não encontrado (rode a partir do módulo :app)", res.isDirectory)
        val achados = res.listFiles()
            ?.filter { it.isDirectory && regexPastaIdioma.matches(it.name) }
            ?.filter { File(it, "strings.xml").exists() }
            ?.map { it.name.removePrefix("values-").uppercase() to it }
            ?.sortedBy { it.first }
            .orEmpty()
        // Rede de segurança: se o filtro quebrar, o teste passaria comparando o
        // português com nada. Melhor falhar dizendo que não achou tradução.
        assertTrue("nenhuma pasta de idioma encontrada em res/", achados.isNotEmpty())
        return achados
    }

    private val regexNaoTraduzivel =
        // Dentro de raw string do Kotlin a barra ja e literal: aqui vai UMA.
        // Com duas, a expressao procuraria uma barra seguida de "s".
        Regex("""<string\s+name="([^"]+)"[^>]*translatable\s*=\s*"false"""",
              RegexOption.IGNORE_CASE)

    /**
     * Chaves marcadas `translatable="false"` no idioma base.
     *
     * Ficam FORA da paridade: existem só no base de propósito. O rótulo
     * trilíngue da tela de escolha de idioma é o caso — ele precisa sair igual
     * em qualquer locale, porque quem ainda vai escolher o idioma não pode
     * depender de ler o padrão. Exigi-lo traduzido seria exigir o contrário do
     * que ele é.
     */
    private val naoTraduziveis: Set<String> by lazy {
        regexNaoTraduzivel.findAll(File(res, "values/strings.xml").readText())
            .map { it.groupValues[1] }.toSet()
    }

    private fun strings(pasta: File): Map<String, String> =
        regexString.findAll(File(pasta, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
            .filterKeys { it !in naoTraduziveis }

    private fun plurais(pasta: File): Map<String, Map<String, String>>? {
        val f = File(pasta, "plurals.xml")
        if (!f.exists()) return null
        return regexPlural.findAll(f.readText()).associate { m ->
            m.groupValues[1] to regexItem.findAll(m.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2] }
        }
    }

    private fun placeholders(s: String): List<String> =
        regexPlaceholder.findAll(s).map { it.value }.sorted().toList()

    @Test
    fun todasAsChavesExistemEmTodosOsIdiomas() {
        val pt = strings(res.resolve("values"))
        val erros = mutableListOf<String>()
        var todas = emptySet<String>()
        for ((rotulo, pasta) in idiomas()) {
            val outro = strings(pasta)
            todas = todas + outro.keys
            val falta = (pt.keys - outro.keys).sorted()
            if (falta.isNotEmpty()) erros += "sem tradução $rotulo: ${falta.take(6)}"
        }
        val sobra = (todas - pt.keys).sorted()
        if (sobra.isNotEmpty()) erros += "existem fora do PT (idioma base): ${sobra.take(6)}"
        assertTrue(erros.joinToString("; "), erros.isEmpty())
    }

    @Test
    fun placeholdersBatemEntreIdiomas() {
        val pt = strings(res.resolve("values"))
        val erros = mutableListOf<String>()
        for ((rotulo, pasta) in idiomas()) {
            val outro = strings(pasta)
            for ((chave, textoPt) in pt) {
                val t = outro[chave] ?: continue
                if (placeholders(t) != placeholders(textoPt)) erros += "$chave ($rotulo)"
            }
        }
        assertTrue("Placeholders divergentes: ${erros.take(10)}", erros.isEmpty())
    }

    @Test
    fun nenhumaStringVaziaNoIdiomaBase() {
        val vazias = strings(res.resolve("values")).filterValues { it.isBlank() }.keys.sorted()
        assertTrue("Strings vazias em PT: $vazias", vazias.isEmpty())
    }

    /**
     * Apóstrofo sem barra invertida quebra o recurso em tempo de compilação.
     *
     * Era um risco teórico enquanto o app falava português, espanhol e inglês —
     * o português não usa apóstrofo e o inglês tinha quatro. Com francês e
     * italiano são centenas por arquivo, e um único descuido derruba o build
     * com uma mensagem que aponta a linha errada.
     */
    @Test
    fun nenhumApostrofoSemEscape() {
        val erros = mutableListOf<String>()
        for ((rotulo, pasta) in listOf("PT" to res.resolve("values")) + idiomas()) {
            for ((chave, corpo) in strings(pasta)) {
                // Corpo inteiro entre aspas é a outra forma de escapar que o
                // Android aceita; ali o apóstrofo passa cru.
                if (corpo.startsWith("\"") && corpo.endsWith("\"")) continue
                var i = 0
                while (i < corpo.length) {
                    val c = corpo[i]
                    if (c == '\\') { i += 2; continue }
                    if (c == '\'') { erros += "$chave ($rotulo)"; break }
                    i++
                }
            }
        }
        assertTrue("Apóstrofo sem escape (use \\'): ${erros.take(10)}", erros.isEmpty())
    }

    /**
     * Plurais: mesmos nomes em todo idioma, `other` sempre presente, e nenhuma
     * categoria de quantidade inventada.
     *
     * NÃO exige as mesmas categorias entre idiomas, porque elas são POR idioma:
     * o polonês usa one/few/many/other e o japonês só other. Exigir igualdade
     * seria exigir o erro.
     */
    @Test
    fun pluraisCompletosEmTodosOsIdiomas() {
        val validas = setOf("zero", "one", "two", "few", "many", "other")
        val base = plurais(res.resolve("values")) ?: return
        val erros = mutableListOf<String>()
        for ((rotulo, pasta) in idiomas()) {
            val atual = plurais(pasta)
            if (atual == null) { erros += "$rotulo: plurals.xml ausente"; continue }
            for (nome in base.keys) {
                val itens = atual[nome]
                if (itens == null) { erros += "$rotulo/$nome ausente"; continue }
                if ("other" !in itens) erros += "$rotulo/$nome sem a categoria 'other'"
                val invalidas = itens.keys - validas
                if (invalidas.isNotEmpty()) erros += "$rotulo/$nome quantity inválida $invalidas"
                itens.forEach { (q, txt) ->
                    if (txt.isBlank()) erros += "$rotulo/$nome/$q vazio"
                }
            }
        }
        assertTrue(erros.take(10).joinToString("; "), erros.isEmpty())
    }
}
