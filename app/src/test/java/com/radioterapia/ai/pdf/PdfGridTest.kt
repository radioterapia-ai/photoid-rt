package com.radioterapia.ai.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometria do grid de fotos da folha de posicionamento.
 *
 * O PDF era a única parte crítica do app conferida só a olho. Estes testes
 * fixam as duas promessas feitas aos técnicos quando o grid foi reescrito:
 *
 *   1. com a grade cheia (8 fotos) a folha sai IDÊNTICA à de antes;
 *   2. com menos fotos a célula cresce, no máximo 25%.
 *
 * Medidas em pontos, aproximando a área útil de uma A4 retrato.
 */
class PdfGridTest {

    private val topo = 120f
    private val w = 555f
    private val h = 620f
    private val gap = 6f
    private val margem = 20f

    /**
     * Grade da folha EM PE, como o app a monta: uma coluna ate 4 fotos, e
     * linhas duplas abrindo de baixo para cima a partir da quinta.
     *
     * Passa a distribuicao de verdade. Antes chamava o caminho automatico, que
     * nao e o que a folha usa — e por isso os testes continuaram verdes quando
     * a ordem do retrato mudou sem que ninguem pedisse.
     */
    private fun grade(n: Int, cols: Int = 2, rows: Int = 4) =
        PdfBuilder.calcularLayoutFotos(n, topo, w, h, cols, rows, margem, gap,
            PdfBuilder.distribuicaoRetrato(n))

    /** Grade da folha DEITADA, com a distribuicao em duas linhas. */
    private fun paisagem(n: Int) =
        PdfBuilder.calcularLayoutFotos(n, topo, w, h, 4, 2, margem, gap,
            PdfBuilder.distribuicaoPaisagem(n), limitarPelaProporcao = true)

    /** Célula da grade cheia — a referência de tamanho "normal". */
    private fun baseW(cols: Int = 2) = (w - gap * (cols - 1)) / cols
    private fun baseH(rows: Int = 4) = (h - gap * (rows - 1)) / rows

    @Test
    fun `grade cheia devolve 8 celulas do tamanho de referencia`() {
        val cells = grade(8)
        assertEquals(8, cells.size)
        cells.forEach {
            assertEquals(baseW(), it.w, 0.01f)
            assertEquals(baseH(), it.h, 0.01f)
        }
    }

    @Test
    fun `grade cheia mantem a folha de 8 fotos igual a de antes`() {
        // Fórmula do layout antigo (calcularLayoutCheio): 2 colunas x 4 linhas
        // ancoradas no topo, sem ampliação nem centralização.
        val rowH = (h - gap * 3) / 4
        val colW = (w - gap) / 2
        val esperado = (0 until 4).flatMap { linha ->
            (0 until 2).map { col ->
                Pair(margem + col * (colW + gap), topo + linha * (rowH + gap))
            }
        }
        val cells = grade(8)
        esperado.forEachIndexed { i, (x, y) ->
            assertEquals("x da célula $i", x, cells[i].x, 0.01f)
            assertEquals("y da célula $i", y, cells[i].y, 0.01f)
        }
    }

    @Test
    fun `com espaco de sobra a celula cresce exatamente 25 por cento`() {
        // 3 fotos = 3 linhas de 1: sobra altura, e o teto de 25% e quem manda.
        val cells = grade(3)
        assertEquals(3, cells.size)
        assertEquals(baseH() * 1.25f, cells[0].h, 0.01f)
        assertEquals(baseW() * 1.25f, cells[0].w, 0.01f)  // coluna unica: ganha largura
    }

    @Test
    fun `retrato usa UMA coluna ate quatro fotos`() {
        // A coluna unica e o que da a foto deitada a largura inteira da folha.
        for (n in 1..4) {
            val cells = grade(n)
            assertEquals("n=$n", n, cells.size)
            val xs = cells.map { it.x }.distinct()
            assertEquals("n=$n deveria ter uma coluna so", 1, xs.size)
        }
    }

    @Test
    fun `retrato abre as linhas duplas de baixo para cima`() {
        assertEquals(listOf(1, 1, 1, 1), PdfBuilder.distribuicaoRetrato(4))
        assertEquals(listOf(1, 1, 1, 2), PdfBuilder.distribuicaoRetrato(5))
        assertEquals(listOf(1, 1, 2, 2), PdfBuilder.distribuicaoRetrato(6))
        assertEquals(listOf(1, 2, 2, 2), PdfBuilder.distribuicaoRetrato(7))
        assertEquals(listOf(2, 2, 2, 2), PdfBuilder.distribuicaoRetrato(8))
    }

    @Test
    fun `retrato nunca passa de quatro linhas`() {
        for (n in 1..8) {
            val d = PdfBuilder.distribuicaoRetrato(n)
            assertTrue("n=$n gerou ${d.size} linhas", d.size <= 4)
            assertEquals("n=$n perdeu foto", n, d.sum())
        }
    }

    @Test
    fun `uma foto so cresce nas duas dimensoes, ainda limitada a 25 por cento`() {
        val c = grade(1).single()
        assertEquals(baseW() * 1.25f, c.w, 0.01f)
        assertEquals(baseH() * 1.25f, c.h, 0.01f)
    }

    @Test
    fun `nenhuma quantidade produz celula menor que a da grade cheia`() {
        // A reclamação de origem era foto pequena. Nenhum caso pode regredir.
        for (n in 1..8) {
            grade(n).forEach {
                assertTrue("n=$n encolheu a largura", it.w >= baseW() - 0.01f)
                assertTrue("n=$n encolheu a altura", it.h >= baseH() - 0.01f)
            }
        }
    }

    @Test
    fun `nada transborda a area util`() {
        for (n in 1..8) {
            grade(n).forEach {
                assertTrue("n=$n passou da esquerda", it.x >= margem - 0.01f)
                assertTrue("n=$n passou da direita", it.x + it.w <= margem + w + 0.01f)
                assertTrue("n=$n passou do topo", it.y >= topo - 0.01f)
                assertTrue("n=$n passou da base", it.y + it.h <= topo + h + 0.01f)
            }
        }
    }

    @Test
    fun `linha de uma foto so fica centralizada`() {
        // 5 fotos = 1 + 1 + 1 + 2: as tres de cima ficam sozinhas na linha e
        // centralizam, em vez de encostar a esquerda.
        val cells = grade(5)
        assertEquals(5, cells.size)
        for (i in 0..2) {
            assertEquals("célula $i", margem + (w - cells[i].w) / 2f, cells[i].x, 0.01f)
        }
        // A quarta linha tem duas, lado a lado.
        assertTrue("as duas de baixo deveriam estar lado a lado", cells[3].x < cells[4].x)
        assertEquals("mesma linha", cells[3].y, cells[4].y, 0.01f)
    }

    @Test
    fun `paisagem cheia mantem as 8 celulas de referencia`() {
        val cheia = paisagem(8)
        assertEquals(8, cheia.size)
        cheia.forEach { assertEquals(baseH(rows = 2), it.h, 0.01f) }
    }

    /**
     * A folha deitada usa SEMPRE duas linhas, com a metade menor em cima.
     *
     * Antes, ate 4 fotos iam todas numa linha so: numa folha larga e baixa cada
     * foto ficava minuscula entre faixas de papel vazio. Como as fotos ja sao
     * landscape, espalhar na horizontal era o pior uso do espaco — foi a queixa
     * que originou esta regra.
     */
    @Test
    fun `paisagem distribui em duas linhas com a metade menor em cima`() {
        assertEquals(listOf(1), PdfBuilder.distribuicaoPaisagem(1))
        assertEquals(listOf(1, 1), PdfBuilder.distribuicaoPaisagem(2))
        assertEquals(listOf(1, 2), PdfBuilder.distribuicaoPaisagem(3))
        assertEquals(listOf(2, 2), PdfBuilder.distribuicaoPaisagem(4))
        assertEquals(listOf(2, 3), PdfBuilder.distribuicaoPaisagem(5))
        assertEquals(listOf(3, 3), PdfBuilder.distribuicaoPaisagem(6))
        assertEquals(listOf(3, 4), PdfBuilder.distribuicaoPaisagem(7))
        assertEquals(listOf(4, 4), PdfBuilder.distribuicaoPaisagem(8))
    }

    @Test
    fun `paisagem com 4 fotos vira 2x2, e nao uma linha de 4`() {
        val c = paisagem(4)
        assertEquals(4, c.size)
        // duas alturas distintas = duas linhas
        assertEquals(2, c.map { it.y }.distinct().size)
        assertEquals(2, c.filter { it.y == c[0].y }.size)
    }

    /** Numero impar: linha de cima com um a menos, ambas centralizadas. */
    @Test
    fun `paisagem com 3 fotos poe 1 em cima e 2 embaixo, centralizadas`() {
        val c = paisagem(3)
        assertEquals(3, c.size)
        val cima = c.filter { it.y == c.minOf { p -> p.y } }
        val baixo = c.filter { it.y > c.minOf { p -> p.y } }
        assertEquals(1, cima.size)
        assertEquals(2, baixo.size)
        val centro = margem + w / 2f
        // a unica foto de cima fica centrada no meio da pagina
        assertEquals(centro, cima[0].x + cima[0].w / 2f, 0.5f)
        // as duas de baixo tem a juncao no meio da pagina
        assertEquals(centro, baixo[0].x + baixo[0].w + gap / 2f, 0.5f)
    }

    /** A celula nao pode ficar mais larga que a proporcao da foto comporta. */
    @Test
    fun `paisagem nao cria celula com sobra lateral de papel`() {
        val c = paisagem(2)
        val alturaFoto = c[0].h - 12f          // LEGENDA_HEIGHT
        assertTrue("celula larga demais para a foto",
            c[0].w <= alturaFoto * (16f / 9f) + 0.5f)
    }

    @Test
    fun `quantidade acima da grade nao estoura nem devolve celula extra`() {
        assertEquals(8, grade(12).size)
        assertEquals(1, grade(0).size)
    }
}
