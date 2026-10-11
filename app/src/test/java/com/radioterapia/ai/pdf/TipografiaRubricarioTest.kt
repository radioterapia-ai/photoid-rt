package com.radioterapia.ai.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corpo comum, quebra dentro da célula e piso da página de rubricário.
 *
 * A medida é falsa mas próxima da Roboto do aparelho: maiúscula 0,62 em,
 * minúscula 0,50, algarismo 0,56, espaço 0,25, o resto 0,30. Nela, 28
 * maiúsculas seguidas cabem numa linha de 139 pt a 8 pt.
 */
class TipografiaRubricarioTest {

    /** Célula de nome mais estreita: retrato, duas colunas (262,5 × 0,56 − 8). */
    private val celulaNome = 139f
    /** Célula de registro na mesma folha (262,5 × 0,22 − 8). */
    private val celulaReg = 49.75f

    private fun largura(texto: String, corpo: Float): Float = texto.sumOf { c ->
        when {
            c == ' ' -> 0.25
            c.isUpperCase() -> 0.62
            c.isLowerCase() -> 0.50
            c.isDigit() -> 0.56
            else -> 0.30
        } * corpo.toDouble()
    }.toFloat()

    private val medir: (String, Float) -> Float = { t, c -> largura(t, c) }

    @Test
    fun `equipe de nomes curtos fica no corpo cheio`() {
        val c = TipografiaRubricario.corpoComum(
            listOf("Ana Maria Silva", "Roberto Faria Lemos", "JOSE SOUZA"), celulaNome,
            listOf("CRM 12345", "CNEN 1234"), celulaReg, medir)
        assertEquals(TipografiaRubricario.CORPO_MAX, c, 0.001f)
    }

    @Test
    fun `um nome longo baixa o corpo de toda a folha, nunca abaixo do piso`() {
        val longo = "MARIA APARECIDA DA CONCEICAO DOS SANTOS OLIVEIRA"
        val c = TipografiaRubricario.corpoComum(
            listOf("ANA SILVA", longo), celulaNome, emptyList(), celulaReg, medir)
        assertTrue("baixou", c < TipografiaRubricario.CORPO_MAX)
        assertTrue("no piso ou acima", c >= TipografiaRubricario.CORPO_MIN)
        // No corpo escolhido, o longo cabe em duas linhas.
        val linhas = TipografiaRubricario.quebrar(longo, celulaNome, { t -> largura(t, c) })
        assertEquals(2, linhas?.size)
    }

    @Test
    fun `registro longo quebra em duas linhas em vez de invadir a rubrica`() {
        val linhas = TipografiaRubricario.quebrar("CRM-SP 123456", celulaReg, { t -> largura(t, 10f) })
        assertEquals(listOf("CRM-SP", "123456"), linhas)
        // E por isso nao derruba o corpo da folha.
        val c = TipografiaRubricario.corpoComum(
            listOf("Ana Maria Silva"), celulaNome, listOf("CRM-SP 123456"), celulaReg, medir)
        assertEquals(TipografiaRubricario.CORPO_MAX, c, 0.001f)
    }

    @Test
    fun `registro digitado sem espaco quebra no hifen ou entre letra e algarismo`() {
        val medir10: (String) -> Float = { t -> largura(t, 10f) }
        assertEquals(listOf("CRM-SP", "123456"),
            TipografiaRubricario.quebrar("CRM-SP123456", celulaReg, medir10))
        assertEquals(listOf("CRM/SP-", "123456"),
            TipografiaRubricario.quebrar("CRM/SP-123456", celulaReg, medir10))
        // O numero inteiro numa linha: a folha nao desce por causa dele.
        val c = TipografiaRubricario.corpoComum(
            listOf("Ana Maria Silva"), celulaNome, listOf("CRM-SP123456"), celulaReg, medir)
        assertEquals(TipografiaRubricario.CORPO_MAX, c, 0.001f)
    }

    @Test
    fun `o limite de caracteres do cadastro cabe em duas linhas no piso`() {
        val nome = "MARIA APARECIDA DA CONCEICAO DOS SANTOS OLIVEIRA"
        assertEquals(TipografiaRubricario.NOME_MAX_CARACTERES, nome.length)
        val linhas = TipografiaRubricario.quebrar(
            nome, celulaNome, { t -> largura(t, TipografiaRubricario.CORPO_MIN) })
        assertEquals(2, linhas?.size)
    }

    @Test
    fun `nome gravado antes do limite sai no piso com reticencias e sem transbordar`() {
        val enorme = "MARIA APARECIDA DA CONCEICAO DOS SANTOS OLIVEIRA DE ALBUQUERQUE CAVALCANTI"
        val c = TipografiaRubricario.corpoComum(listOf(enorme), celulaNome, emptyList(), celulaReg, medir)
        assertEquals(TipografiaRubricario.CORPO_MIN, c, 0.001f)
        val linhas = TipografiaRubricario.linhas(enorme, celulaNome, { t -> largura(t, c) })
        // Palavras inteiras nas duas linhas, e as reticencias depois de uma delas.
        assertEquals(listOf("MARIA APARECIDA DA CONCEICAO", "DOS SANTOS OLIVEIRA DE…"), linhas)
        linhas.forEach { assertTrue("linha cabe: $it", largura(it, c) <= celulaNome + 0.001f) }
    }

    @Test
    fun `so a palavra maior que a linha e partida, as outras ficam inteiras`() {
        val texto = "ANA " + "W".repeat(40)
        val linhas = TipografiaRubricario.linhas(texto, celulaNome, { t -> largura(t, 8f) })
        assertEquals("ANA", linhas[0])
        assertEquals(TipografiaRubricario.MAX_LINHAS, linhas.size)
        assertTrue(linhas[1].endsWith("…"))
        linhas.forEach { assertTrue("linha cabe: $it", largura(it, 8f) <= celulaNome + 0.001f) }
    }

    @Test
    fun `palavra maior que a linha nao cabe na quebra e e partida no desenho`() {
        val palavra = "W".repeat(40)
        assertNull(TipografiaRubricario.quebrar(palavra, celulaNome, { t -> largura(t, 8f) }))
        val linhas = TipografiaRubricario.linhas(palavra, celulaNome, { t -> largura(t, 8f) })
        assertTrue(linhas.size in 1..TipografiaRubricario.MAX_LINHAS)
        linhas.forEach { assertTrue(largura(it, 8f) <= celulaNome + 0.001f) }
    }

    @Test
    fun `texto vazio nao desenha nada`() {
        assertEquals(emptyList<String>(), TipografiaRubricario.quebrar("   ", celulaNome, { 0f }))
        assertEquals(emptyList<Float>(), TipografiaRubricario.linhasDeBase(0, 100f, 26f, 10f))
    }

    @Test
    fun `uma linha cai onde sempre caiu e duas cabem na linha da tabela`() {
        val topo = 100f
        val altura = 26f
        val corpo = TipografiaRubricario.CORPO_MAX
        val uma = TipografiaRubricario.linhasDeBase(1, topo, altura, corpo)
        assertEquals(topo + altura / 2f + corpo * TipografiaRubricario.ALTURA_MAIUSCULA / 2f, uma[0], 0.001f)
        val duas = TipografiaRubricario.linhasDeBase(2, topo, altura, corpo)
        val topoTinta = duas[0] - corpo * TipografiaRubricario.ALTURA_MAIUSCULA
        val fimTinta = duas[1] + corpo * 0.25f   // descida da Roboto
        assertTrue("tinta comeca dentro da celula: $topoTinta", topoTinta > topo)
        assertTrue("tinta termina dentro da celula: $fimTinta", fimTinta < topo + altura)
    }
}
