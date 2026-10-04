package com.radioterapia.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A lista padrão de sítios existe em dois lugares: a constante em português de
 * [TimeOutData], que é a reserva de quem não tem idioma fixado, e o recurso
 * `sitios_padrao`, um por idioma, que é o que lê quem tem.
 *
 * Uma instalação fixada em português e outra sem fixação mostram as duas a
 * lista em português; se recurso e constante divergirem, o mesmo serviço vê
 * duas listas diferentes no mesmo idioma, e ninguém sabe qual está certa.
 *
 * Lê os `strings.xml` direto do código-fonte, como StringsParidadeTest. Os
 * idiomas são DESCOBERTOS pela pasta, nunca listados: um idioma novo entra no
 * teste no mesmo instante em que entra no app.
 */
class SitiosPadraoTest {

    private val res = File("src/main/res")

    private val regexPastaIdioma = Regex("""^values-([a-z]{2})(?:-r[A-Z]{2})?$""")

    private val regexChave = Regex(
        """<string\s+name="sitios_padrao"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun pastasDeIdioma(): List<File> {
        assertTrue("res/ não encontrado (rode a partir do módulo :app)", res.isDirectory)
        val achadas = res.listFiles()
            ?.filter { it.isDirectory && regexPastaIdioma.matches(it.name) }
            ?.filter { File(it, "strings.xml").exists() }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("nenhuma pasta de idioma encontrada em res/", achadas.isNotEmpty())
        return achadas
    }

    private fun valorBruto(pasta: File): String? =
        regexChave.find(File(pasta, "strings.xml").readText(Charsets.UTF_8))?.groupValues?.get(1)

    /** Itens do recurso, como `getString` os entregaria, quebrados em `\n`. */
    private fun itens(pasta: File): List<String> {
        val bruto = valorBruto(pasta)
        assertNotNull("sitios_padrao ausente em ${pasta.name}/strings.xml", bruto)
        return decodificar(bruto!!).split('\n')
    }

    /**
     * O texto como o Android o entrega: entidades XML primeiro; depois, fora de
     * aspas, espaço em sequência vira um só e as pontas caem; `\n`, `\t`, `\uXXXX`
     * e as barras de escape (`\'`, `\"`, `\\`, `\@`, `\?`) viram o caractere.
     */
    private fun decodificar(bruto: String): String {
        val xml = decodificarEntidades(bruto)
        val sb = StringBuilder()
        var entreAspas = false
        var espacoPendente = false
        var i = 0
        while (i < xml.length) {
            val ch = xml[i]
            if (ch == '\\' && i + 1 < xml.length) {
                if (espacoPendente && sb.isNotEmpty()) sb.append(' ')
                espacoPendente = false
                when (val e = xml[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> {
                        sb.append(xml.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i += 2
                continue
            }
            when {
                ch == '"' -> entreAspas = !entreAspas
                !entreAspas && ch.isWhitespace() -> espacoPendente = true
                else -> {
                    if (espacoPendente && sb.isNotEmpty()) sb.append(' ')
                    espacoPendente = false
                    sb.append(ch)
                }
            }
            i++
        }
        return sb.toString()
    }

    private fun decodificarEntidades(s: String): String =
        s.replace(Regex("&#x([0-9A-Fa-f]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("&#([0-9]+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")

    @Test
    fun `o decodificador do teste le o recurso como o Android`() {
        // Sem esta conferência, um defeito no decodificador faria o teste
        // comparar a lista com uma leitura que o aparelho nunca faz.
        assertEquals("A\nB", decodificar("A\\nB"))
        assertEquals("L'OS\nB", decodificar("L\\'OS\\nB"))
        assertEquals("A B", decodificar("  A \n   B  "))
        assertEquals("A\n B", decodificar("A\\n B"))
        assertEquals("P&D", decodificar("P&amp;D"))
        assertEquals("ÉÉ", decodificar("\\u00C9&#201;"))
        assertEquals("  X  ", decodificar("\"  X  \""))
    }

    @Test
    fun `a constante em portugues nao tem item vazio nem repetido`() {
        val s = TimeOutData.SITIOS
        assertTrue(s.isNotEmpty())
        assertTrue("item vazio ou com espaço na ponta", s.all { it.isNotBlank() && it == it.trim() })
        assertEquals("item repetido na constante", s.size, s.toSet().size)
        assertTrue("item com quebra de linha", s.none { it.contains('\n') })
    }

    @Test
    fun `o recurso em portugues e identico a constante`() {
        // Ordem e texto exatos: a lista é curada (MAMA DIREITA, ESQUERDA e
        // BILATERAL juntas) e nenhuma ordenação é aplicada em tempo de execução.
        assertEquals(TimeOutData.SITIOS, itens(File(res, "values")))
    }

    @Test
    fun `toda traducao tem a mesma quantidade de itens, sem vazio nem repetido`() {
        val erros = mutableListOf<String>()
        for (pasta in pastasDeIdioma()) {
            val lista = itens(pasta)
            val nome = pasta.name
            if (lista.size != TimeOutData.SITIOS.size)
                erros += "$nome: ${lista.size} itens, esperados ${TimeOutData.SITIOS.size}"
            val ruins = lista.filter { it.isBlank() || it != it.trim() }
            if (ruins.isNotEmpty())
                erros += "$nome: item vazio ou com espaço na ponta ${ruins.map { "[$it]" }.take(4)}"
            val repetidos = lista.groupBy { it.trim() }.filter { it.value.size > 1 }.keys
            if (repetidos.isNotEmpty())
                erros += "$nome: itens repetidos ${repetidos.take(4)}"
        }
        assertTrue(erros.joinToString("; "), erros.isEmpty())
    }
}
