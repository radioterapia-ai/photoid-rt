package com.radioterapia.ai.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observações da ficha: quebra, corpo comum às duas páginas e a caixa que
 * cresce com o texto.
 *
 * A medida é falsa e proporcional (meio corpo por caractere), para os testes
 * não dependerem de fonte. Os números de caixa, linha de tabela e faixa são os
 * da geometria real da folha: Time-Out A4 com a base da caixa em 814 pt e o
 * fim do cabeçalho da tabela em 352 pt; faixa das fotos de 432 pt em retrato.
 */
class PdfObservacoesTest {

    private val medir: (String, Float) -> Float = { s, corpo -> s.length * corpo * 0.5f }

    private val larguraTimeOut = 527f
    private val larguraFotos = 432f

    /** Fim da caixa de observações no Time-Out (ph - MARGIN) e fim do cabeçalho da tabela. */
    private val fimObs = 842f - 28f
    private val headerB = 352f

    private fun alturaLinhaTabela(caixa: Float) = (fimObs - caixa - 6f - headerB) / 20f

    /** Linha de [n] palavras de 4 letras: 5 caracteres por palavra com o espaço. */
    private fun linhaDePalavras(n: Int) = List(n) { "abcd" }.joinToString(" ")

    // ---------------------------------------------------------------- quebra

    @Test
    fun `quebra por palavra sem passar da largura`() {
        val m: (String) -> Float = { it.length * 5f }
        val linhas = PdfBuilder.quebrarLinhas("aaaa bbbb cccc dddd", 50f, m)
        assertEquals(listOf("aaaa bbbb", "cccc dddd"), linhas)
        assertTrue(linhas.all { m(it) <= 50f })
    }

    @Test
    fun `palavra maior que a largura e partida por caractere`() {
        val m: (String) -> Float = { it.length * 10f }
        val linhas = PdfBuilder.quebrarLinhas("abcdefghij", 40f, m)
        assertEquals(listOf("abcd", "efgh", "ij"), linhas)
    }

    @Test
    fun `texto vazio da lista vazia`() {
        assertTrue(PdfBuilder.quebrarLinhas("", 100f) { it.length.toFloat() }.isEmpty())
        assertTrue(PdfBuilder.quebrarLinhas("    ", 100f) { it.length.toFloat() }.isEmpty())
    }

    @Test
    fun `linhas das observacoes respeitam o enter e descartam linhas vazias`() {
        val linhas = PdfBuilder.linhasObservacoes("  primeira  \n\n   \nsegunda\n", 1000f, 11.5f, medir)
        assertEquals(listOf("primeira", "segunda"), linhas)
    }

    @Test
    fun `linha digitada longa vira duas desenhadas`() {
        // 20 palavras = 99 caracteres = 569 pt a 11,5: passa dos 527 do Time-Out.
        val linhas = PdfBuilder.linhasObservacoes(linhaDePalavras(20), larguraTimeOut, 11.5f, medir)
        assertEquals(2, linhas.size)
        assertTrue(linhas.all { medir(it, 11.5f) <= larguraTimeOut })
    }

    // ---------------------------------------------------------------- corpo

    @Test
    fun `texto curto sai em 11,5`() {
        val texto = "Retirar a protese.\nBraco elevado."
        assertEquals(11.5f, PdfBuilder.escolherCorpoObs(texto, listOf(larguraTimeOut, larguraFotos), medir), 0.001f)
    }

    @Test
    fun `quatro linhas digitadas que cabem saem em 11,5`() {
        val texto = List(4) { linhaDePalavras(10) }.joinToString("\n")
        assertEquals(11.5f, PdfBuilder.escolherCorpoObs(texto, listOf(larguraTimeOut, larguraFotos), medir), 0.001f)
    }

    @Test
    fun `cinco linhas desenhadas descem para 11`() {
        // Quatro digitadas, uma delas quebra: 5 linhas. A 11,5 dariam 71,875 pt,
        // acima do teto de 69; a 11 dão 68,75.
        val texto = listOf(linhaDePalavras(20), linhaDePalavras(5), linhaDePalavras(5), linhaDePalavras(5))
            .joinToString("\n")
        assertEquals(5, PdfBuilder.linhasObservacoes(texto, larguraTimeOut, 11f, medir).size)
        assertEquals(11f, PdfBuilder.escolherCorpoObs(texto, listOf(larguraTimeOut), medir), 0.001f)
    }

    @Test
    fun `a caixa mais estreita decide o corpo das duas paginas`() {
        // 16 palavras (79 caracteres): 454 pt a 11,5. Cabe em 1 linha no
        // Time-Out e quebra em 2 na faixa das fotos.
        val texto = List(4) { linhaDePalavras(16) }.joinToString("\n")
        val soTimeOut = PdfBuilder.escolherCorpoObs(texto, listOf(larguraTimeOut), medir)
        val comFotos = PdfBuilder.escolherCorpoObs(texto, listOf(larguraTimeOut, larguraFotos), medir)
        assertEquals(11.5f, soTimeOut, 0.001f)
        assertTrue(comFotos < soTimeOut)
        val linhasFotos = PdfBuilder.linhasObservacoes(texto, larguraFotos, comFotos, medir).size
        assertTrue(PdfBuilder.alturaTextoObs(linhasFotos, comFotos) <= 69f)
    }

    @Test
    fun `seis linhas cabem em 9`() {
        val texto = List(6) { linhaDePalavras(5) }.joinToString("\n")
        assertEquals(9f, PdfBuilder.escolherCorpoObs(texto, listOf(larguraFotos), medir), 0.001f)
        assertEquals(67.5f, PdfBuilder.alturaTextoObs(6, 9f), 0.001f)
    }

    @Test
    fun `o corpo nunca desce abaixo de 8,5`() {
        // 6 linhas a 9 pt = 67,5 cabem; 7 linhas a 8,5 pt = 74,4 nao cabem, e o
        // corpo para no piso em vez de descer mais.
        val seis = List(6) { "linha" }.joinToString("\n")
        val sete = List(7) { "linha" }.joinToString("\n")
        assertEquals(9f, PdfBuilder.escolherCorpoObs(seis, listOf(larguraFotos), medir), 0.001f)
        assertEquals(8.5f, PdfBuilder.escolherCorpoObs(sete, listOf(larguraFotos), medir), 0.001f)
    }

    @Test
    fun `sem caber nem no piso devolve o piso`() {
        val texto = List(4) { linhaDePalavras(300) }.joinToString("\n")
        assertEquals(8.5f, PdfBuilder.escolherCorpoObs(texto, listOf(larguraFotos), medir), 0.001f)
    }

    @Test
    fun `texto vazio fica no corpo padrao`() {
        assertEquals(11.5f, PdfBuilder.escolherCorpoObs("  \n ", listOf(larguraFotos), medir), 0.001f)
    }

    // ---------------------------------------------------------------- arranjo

    @Test
    fun `altura do texto e linhas vezes corpo vezes entrelinha`() {
        val texto = List(4) { linhaDePalavras(5) }.joinToString("\n")
        val a = PdfBuilder.arranjarObservacoes(texto, larguraFotos, 11.5f, medir)
        assertEquals(4, a.linhas.size)
        assertEquals(57.5f, a.alturaTexto, 0.001f)
        assertFalse(a.cortado)
    }

    @Test
    fun `corte so no piso, com reticencias que cabem na largura`() {
        val texto = List(4) { linhaDePalavras(300) }.joinToString("\n")
        val a = PdfBuilder.arranjarObservacoes(texto, larguraFotos, 8.5f, medir)
        assertTrue(a.cortado)
        assertTrue(a.alturaTexto <= 69f)
        assertEquals(6, a.linhas.size)               // 69 / (8,5 x 1,25) = 6,49
        assertTrue(a.linhas.last().endsWith(" …"))
        assertTrue(a.linhas.all { medir(it, 8.5f) <= larguraFotos })
    }

    @Test
    fun `texto vazio nao tem linhas nem altura`() {
        val a = PdfBuilder.arranjarObservacoes("", larguraFotos, 11.5f, medir)
        assertTrue(a.linhas.isEmpty())
        assertEquals(0f, a.alturaTexto, 0.001f)
        assertEquals(0f, PdfBuilder.alturaFaixaObsFotos(a), 0.001f)
    }

    // ---------------------------------------------------------------- Time-Out

    @Test
    fun `caixa do Time-Out vazia ou com duas linhas e a de sempre`() {
        assertEquals(66f, PdfBuilder.alturaCaixaObsTimeOut(0f), 0.001f)
        assertEquals(66f, PdfBuilder.alturaCaixaObsTimeOut(PdfBuilder.alturaTextoObs(2, 11.5f)), 0.001f)
        assertEquals(19.5f, alturaLinhaTabela(66f), 0.001f)
    }

    @Test
    fun `quatro linhas em 11,5 crescem a caixa para cima ate 84,5`() {
        val caixa = PdfBuilder.alturaCaixaObsTimeOut(PdfBuilder.alturaTextoObs(4, 11.5f))
        assertEquals(84.5f, caixa, 0.001f)
        assertEquals(729.5f, fimObs - caixa, 0.001f)            // topo da caixa
        assertEquals(18.575f, alturaLinhaTabela(caixa), 0.001f)
        assertEquals(9.075f, PdfBuilder.ladoCaixaSimNao(alturaLinhaTabela(caixa)), 0.001f)
    }

    @Test
    fun `cinco linhas em 11 ficam no teto`() {
        val caixa = PdfBuilder.alturaCaixaObsTimeOut(PdfBuilder.alturaTextoObs(5, 11f))
        assertEquals(95.75f, caixa, 0.001f)
        assertEquals(18.0125f, alturaLinhaTabela(caixa), 0.001f)
    }

    @Test
    fun `no teto a linha da tabela chega a 18 e nao passa disso`() {
        assertEquals(96f, PdfBuilder.alturaCaixaObsTimeOut(69f), 0.001f)
        assertEquals(96f, PdfBuilder.alturaCaixaObsTimeOut(500f), 0.001f)
        assertEquals(18f, alturaLinhaTabela(PdfBuilder.alturaCaixaObsTimeOut(69f)), 0.001f)
    }

    @Test
    fun `caixa Sim Nao fica em 10 enquanto a linha nao encolhe`() {
        assertEquals(10f, PdfBuilder.ladoCaixaSimNao(19.5f), 0.001f)
        assertEquals(8.5f, PdfBuilder.ladoCaixaSimNao(18f), 0.001f)
        // O topo da caixa fica 8,2 pt abaixo do topo da linha, em qualquer altura.
        for (linha in listOf(18f, 18.575f, 19.29f)) {
            val lado = PdfBuilder.ladoCaixaSimNao(linha)
            assertEquals(8.2f, linha - 1.3f - lado, 0.001f)
        }
    }

    // ---------------------------------------------------------------- fotos

    @Test
    fun `faixa das fotos acompanha as linhas desenhadas`() {
        fun faixa(n: Int, corpo: Float) = PdfBuilder.alturaFaixaObsFotos(
            PdfBuilder.ArranjoObs(corpo, List(n) { "x" }, PdfBuilder.alturaTextoObs(n, corpo), false))
        assertEquals(32.375f, faixa(1, 11.5f), 0.001f)
        assertEquals(46.75f, faixa(2, 11.5f), 0.001f)
        assertEquals(75.5f, faixa(4, 11.5f), 0.001f)     // caixa de 69,5 pt
        assertEquals(85.5f, faixa(6, 9f), 0.001f)        // caixa de 79,5 pt
    }
}
