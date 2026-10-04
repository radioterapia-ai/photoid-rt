package com.radioterapia.ai.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cabeçalho, quadro da etiqueta e rodapé da ficha.
 *
 * Os números são os da folha real: margem de 28 pt, logo no alto da coluna da
 * direita a partir de 32 pt, linha de identificação 12 pt abaixo do quadro, e o
 * topo das letras dela a 0,72 do corpo de 9,5 pt (6,84 pt).
 */
class PdfCabecalhoRodapeTest {

    private val mm = 2.83465f
    private val topo = 28f
    private val topoLivre = 32f + 40f          // logo 3:1 sai com a altura cheia
    private val capIds = 9.5f * 0.72f
    private val entrelinha = 18f

    private fun alturaQuadro(mmAltura: Int) = minOf(mmAltura * mm, 150f - 16f)

    /** Bases do título para uma etiqueta, como desenharCabecalhoCompleto as calcula. */
    private fun bases(mmAltura: Int, cap: Float, linhas: Int = 2): Triple<Float, Float, Float> {
        val bloco = PdfBuilder.alturaBlocoTitulo(cap, entrelinha, linhas)
        val yIds = PdfBuilder.yLinhaIdentificacao(topo, alturaQuadro(mmAltura), topoLivre, bloco, capIds)
        val (b1, b2) = PdfBuilder.basesTituloCabecalho(topoLivre, yIds - capIds, cap, entrelinha, linhas)
        return Triple(b1, b2, yIds)
    }

    // ---------------------------------------------------------------- título

    @Test
    fun `folga de cima igual a de baixo, com uma e duas linhas`() {
        for (linhas in 1..2) {
            val cap = 11.52f
            val baseLivre = 118.2f
            val (b1, b2) = PdfBuilder.basesTituloCabecalho(topoLivre, baseLivre, cap, entrelinha, linhas)
            assertEquals((b1 - cap) - topoLivre, baseLivre - b2, 0.001f)
            if (linhas == 1) assertEquals(b1, b2, 0.0001f)
            else assertEquals(entrelinha, b2 - b1, 0.0001f)
        }
    }

    @Test
    fun `etiqueta 60x30 com a tinta medida`() {
        // Tinta de "FOTOS DE" a 16 pt: 11,52 pt.
        val (b1, b2, yIds) = bases(30, 11.52f)
        assertEquals(125.04f, yIds, 0.01f)                      // a linha nao se move
        assertEquals(91.86f, b1, 0.01f)
        assertEquals(109.86f, b2, 0.01f)
    }

    @Test
    fun `etiqueta 60x30 com o limite inteiro de getTextBounds`() {
        // O valor que o Rect inteiro devolveria (12): o deslocamento e de 0,24 pt.
        val (b1, b2, _) = bases(30, 12f)
        assertEquals(92.10f, b1, 0.01f)
        assertEquals(110.10f, b2, 0.01f)
    }

    @Test
    fun `centrado no vao o titulo fica mais alto que ancorado na linha`() {
        // Ancorada 10 pt acima da linha de IDs, a segunda base ficaria em 115,04,
        // a 3 pt das letras da linha e com o vão inteiro em cima.
        val (_, b2, _) = bases(30, 11.52f)
        assertTrue(b2 < 115.04f - 5f)
    }

    @Test
    fun `etiqueta 100x50 fica no centro do vao`() {
        val (b1, b2, yIds) = bases(50, 12f)
        assertEquals(174f, yIds, 0.01f)                          // quadro limitado a 134 pt
        assertEquals(116.58f, b1, 0.01f)
        assertEquals(134.58f, b2, 0.01f)
    }

    @Test
    fun `etiqueta baixa desce a linha de identificacao`() {
        val (b1, b2, yIds) = bases(20, 12f)
        assertEquals(116.84f, yIds, 0.01f)
        assertEquals(88f, b1, 0.01f)
        assertEquals(106f, b2, 0.01f)
        // O título nunca sobe por cima do logo.
        assertTrue(b1 - 12f >= topoLivre)
    }

    @Test
    fun `etiqueta de 30 mm nao muda a linha, de 10 mm abre espaco para o titulo`() {
        val bloco = PdfBuilder.alturaBlocoTitulo(11.52f, entrelinha, 2)
        assertEquals(topo + alturaQuadro(30) + 12f,
            PdfBuilder.yLinhaIdentificacao(topo, alturaQuadro(30), topoLivre, bloco, capIds), 0.001f)
        val y10 = PdfBuilder.yLinhaIdentificacao(topo, alturaQuadro(10), topoLivre, bloco, capIds)
        assertTrue(y10 >= topoLivre + 8f + bloco + capIds - 0.001f)
    }

    @Test
    fun `altura do logo como o desenho a calcula`() {
        val larguraMax = (595f - 56f) * 0.30f * 0.95f              // 153,6 pt
        assertEquals(40f, PdfBuilder.alturaLogo(300, 100, larguraMax), 0.001f)
        assertEquals(larguraMax / 5f, PdfBuilder.alturaLogo(500, 100, larguraMax), 0.001f)
        assertEquals(40f, PdfBuilder.alturaLogo(0, 100, larguraMax), 0.001f)
    }

    @Test
    fun `tinta medida no corpo ampliado tem precisao de fracao de ponto`() {
        // 11,52 pt a 16 pt ampliados 64 vezes: topo -737,3, que o Rect leva a -738.
        assertEquals(11.53f, PdfBuilder.capDeLimites(-738, 64f, 16f), 0.01f)
        // Medida vazia cai na reserva de 0,72 do corpo.
        assertEquals(11.52f, PdfBuilder.capDeLimites(0, 64f, 16f), 0.001f)
    }

    // ---------------------------------------------------------------- etiqueta

    @Test
    fun `quadro 60x30 fica no par aceito, nao no da tentativa recusada`() {
        // Nome em 2 linhas e 4 identificacoes; quadro de 85,04 pt, altura util 77,04.
        val altura: (Float, Float) -> Float = { n, i -> 2 * (n + 3f) + 4 * (i + 2.5f) }
        val (nome, ids) = PdfBuilder.corposEtiqueta(85.04f, 77.04f, altura)
        assertEquals(12.5f, nome, 0.001f)
        assertEquals(8.81f, ids, 0.001f)
        assertTrue(altura(nome, ids) <= 77.04f)
        // A tentativa recusada (13 / 9,12) passa da altura: e o par que fica nos
        // Paints ao fim do laco, e por isso o desenho nao pode usa-lo.
        assertTrue(altura(nome + 0.5f, ids + 0.31f) > 77.04f)
    }

    @Test
    fun `quadro alto cresce ate o teto`() {
        val altura: (Float, Float) -> Float = { n, i -> (n + 3f) + 4 * (i + 2.5f) }
        val (nome, ids) = PdfBuilder.corposEtiqueta(141.73f, 133.73f, altura)
        assertTrue(nome >= 20f && nome < 20.5f)
        assertEquals(12f, ids, 0.001f)
    }

    @Test
    fun `quadro pequeno encolhe ate o piso`() {
        val altura: (Float, Float) -> Float = { n, i -> 3 * (n + 3f) + 4 * (i + 2.5f) }
        val (nome, ids) = PdfBuilder.corposEtiqueta(28.35f, 20.35f, altura)
        assertEquals(7f, nome, 0.001f)
        assertEquals(6f, ids, 0.001f)
    }

    // ---------------------------------------------------------------- rodapé

    @Test
    fun `rodape com clinica usa um ponto centralizado com um espaco de cada lado`() {
        assertEquals("CLINICA EXEMPLO DE RADIOTERAPIA • Página 1",
            PdfBuilder.textoRodape("CLINICA EXEMPLO DE RADIOTERAPIA", "Página 1"))
        val t = PdfBuilder.textoRodape("X", "Página 2")
        assertTrue(t.contains(" • "))
        assertFalse(t.contains("●"))
        assertFalse(t.contains("  "))
    }

    @Test
    fun `rodape sem clinica e so a pagina`() {
        assertEquals("Página 3", PdfBuilder.textoRodape("", "Página 3"))
        assertEquals("Página 3", PdfBuilder.textoRodape("   ", "Página 3"))
    }

    @Test
    fun `rodape longo encolhe para caber entre as margens`() {
        assertEquals(9f, PdfBuilder.corpoRodape(209.7f, 539f), 0.001f)
        assertEquals(9f * 539f / 600f, PdfBuilder.corpoRodape(600f, 539f), 0.001f)
        assertEquals(6.5f, PdfBuilder.corpoRodape(1000f, 539f), 0.001f)
    }

    @Test
    fun `rodape apara os espacos`() {
        assertEquals("Clínica X • Página 3", PdfBuilder.textoRodape("  Clínica X  ", " Página 3 "))
    }
}
