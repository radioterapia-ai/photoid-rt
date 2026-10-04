package com.radioterapia.ai.protocolo

import com.radioterapia.ai.pdf.PdfBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Paridade frente-e-verso da ficha.
 *
 * A impressora em frente-e-verso junta as páginas aos pares — 1 com 2, 3 com
 * 4. As regras fixadas aqui são três:
 *
 *   1. a frente de um impresso com verso começa sempre em posição ímpar, com
 *      uma folha em branco antes quando preciso, para frente e verso caírem
 *      na mesma folha;
 *   2. página só de frente pode começar no verso da última página anterior;
 *   3. no lote em frente-e-verso, uma folha nunca leva dois pacientes.
 *
 * E a marca de "ficha com verso", que decide o modo da impressão, é por nome
 * de arquivo e sobrevive podada.
 */
class ProtocoloParidadeTest {

    /** Uma entrada do protocolo: páginas da frente e do verso (0 = sem verso). */
    private data class Entrada(val frente: Int, val verso: Int = 0)

    /**
     * Simula o que ProtocoloRenderer.desenhar acrescenta depois de [iniciais]
     * páginas: "B" para a folha em branco, "F" para frente, "V" para verso.
     */
    private fun simular(iniciais: Int, entradas: List<Entrada>): List<String> {
        val doc = MutableList(iniciais) { "I" }
        for (e in entradas) {
            if (ProtocoloRenderer.precisaFolhaEmBranco(doc.size + 1, e.verso > 0)) doc.add("B")
            repeat(e.frente) { doc.add("F") }
            repeat(e.verso) { doc.add("V") }
        }
        return doc
    }

    /** Folhas físicas em frente-e-verso: pares de posições (1-2, 3-4, ...). */
    private fun folhas(doc: List<String>) = doc.chunked(2)

    @Test
    fun `folha em branco so antes de frente com verso em posicao par`() {
        assertFalse(ProtocoloRenderer.precisaFolhaEmBranco(3, true))
        assertTrue(ProtocoloRenderer.precisaFolhaEmBranco(4, true))
        assertFalse(ProtocoloRenderer.precisaFolhaEmBranco(4, false))
        assertFalse(ProtocoloRenderer.precisaFolhaEmBranco(5, false))
    }

    @Test
    fun `caso A - uma pagina so de frente pode comecar no verso`() {
        // Sem rubricario (2 iniciais): P na frente da folha 2.
        assertEquals(listOf("I", "I", "F"), simular(2, listOf(Entrada(1))))
        // Com rubricario (3 iniciais): P no verso do rubricario, sem branco.
        val com = simular(3, listOf(Entrada(1)))
        assertEquals(listOf("I", "I", "I", "F"), com)
        assertEquals(listOf("I", "F"), folhas(com)[1])
    }

    @Test
    fun `caso B - frente e verso na mesma folha`() {
        val sem = simular(2, listOf(Entrada(1, 1)))
        assertEquals(listOf("I", "I", "F", "V"), sem)
        val com = simular(3, listOf(Entrada(1, 1)))
        assertEquals(listOf("I", "I", "I", "B", "F", "V"), com)
        assertEquals(listOf("F", "V"), folhas(com)[2])
        // Rodapé: a frente é a página 5 e o verso a 6; a branca conta, mas sem número.
        assertEquals(5, com.indexOf("F") + 1)
        assertEquals(6, com.indexOf("V") + 1)
    }

    @Test
    fun `caso C - duas paginas so de frente nunca recebem branco`() {
        assertEquals(4, simular(2, listOf(Entrada(1), Entrada(1))).size)
        val com = simular(3, listOf(Entrada(2)))
        assertEquals(5, com.size)
        assertFalse(com.contains("B"))
    }

    @Test
    fun `caso D - duas entradas com verso`() {
        val sem = simular(2, listOf(Entrada(1, 1), Entrada(1, 1)))
        assertEquals(6, sem.size)
        assertFalse(sem.contains("B"))
        val com = simular(3, listOf(Entrada(1, 1), Entrada(1, 1)))
        assertEquals(8, com.size)
        assertEquals(4, com.indexOf("B") + 1)
        // Toda frente começa em posição ímpar.
        com.forEachIndexed { i, p -> if (p == "F" && (i == 0 || com[i - 1] != "F")) assertEquals(0, i % 2) }
    }

    // ---------------------------------------------------------------- lote

    @Test
    fun `separador do lote so em frente e verso e com numero impar de paginas`() {
        assertTrue(PdfBuilder.precisaPaginaSeparadora(3, true))
        assertFalse(PdfBuilder.precisaPaginaSeparadora(4, true))
        assertFalse(PdfBuilder.precisaPaginaSeparadora(3, false))
        assertFalse(PdfBuilder.precisaPaginaSeparadora(0, true))
    }

    @Test
    fun `no lote em frente e verso cada paciente comeca numa folha nova`() {
        val paginasPorPaciente = listOf(3, 2, 1, 2)
        var total = 0
        val inicios = mutableListOf<Int>()
        for (n in paginasPorPaciente) {
            if (PdfBuilder.precisaPaginaSeparadora(total, true)) total++
            inicios.add(total + 1)
            total += n
        }
        assertEquals(listOf(1, 5, 7, 9), inicios)
        assertTrue(inicios.all { it % 2 == 1 })
    }

    @Test
    fun `no lote em simplex nada muda`() {
        var total = 0
        for (n in listOf(3, 2, 1)) {
            if (PdfBuilder.precisaPaginaSeparadora(total, false)) total++
            total += n
        }
        assertEquals(6, total)
    }

    // ---------------------------------------------------------------- marca

    @Test
    fun `a marca e por nome, igual no cache e na pasta do paciente`() {
        val noCache = File("cache", "MDSS_FSIM_20261004.pdf")
        val naPasta = File(File("PHOTOS", "MARIA DA SILVA SANTOS - 123456"), "MDSS_FSIM_20261004.pdf")
        val marcas = PdfBuilder.atualizarMarcasFrenteVerso(emptyList(), noCache.name, true)
        assertTrue(naPasta.name in marcas)
    }

    @Test
    fun `marcar acrescenta no fim e remarcar leva para o fim`() {
        var m = PdfBuilder.atualizarMarcasFrenteVerso(emptyList(), "a.pdf", true)
        m = PdfBuilder.atualizarMarcasFrenteVerso(m, "b.pdf", true)
        m = PdfBuilder.atualizarMarcasFrenteVerso(m, "a.pdf", true)
        assertEquals(listOf("b.pdf", "a.pdf"), m)
    }

    @Test
    fun `desmarcar tira a ficha regerada sem verso`() {
        val m = PdfBuilder.atualizarMarcasFrenteVerso(listOf("a.pdf", "b.pdf"), "a.pdf", false)
        assertEquals(listOf("b.pdf"), m)
        assertEquals(m, PdfBuilder.atualizarMarcasFrenteVerso(m, "x.pdf", false))
    }

    @Test
    fun `a poda guarda as mais recentes`() {
        var m = emptyList<String>()
        for (i in 1..10) m = PdfBuilder.atualizarMarcasFrenteVerso(m, "f$i.pdf", true, limite = 4)
        assertEquals(listOf("f7.pdf", "f8.pdf", "f9.pdf", "f10.pdf"), m)
    }

    @Test
    fun `nome vazio ou com quebra de linha nao entra`() {
        val base = listOf("a.pdf")
        assertEquals(base, PdfBuilder.atualizarMarcasFrenteVerso(base, "  ", true))
        assertEquals(base, PdfBuilder.atualizarMarcasFrenteVerso(base, "x\ny.pdf", true))
    }

    @Test
    fun `gravacao e leitura das marcas dao a mesma lista`() {
        val m = listOf("a.pdf", "b c.pdf", "d.pdf")
        assertEquals(m, PdfBuilder.marcasDeTexto(PdfBuilder.marcasParaTexto(m)))
        assertTrue(PdfBuilder.marcasDeTexto(null).isEmpty())
        assertEquals(listOf("a.pdf"), PdfBuilder.marcasDeTexto("a.pdf\n\n a.pdf \n"))
    }
}
