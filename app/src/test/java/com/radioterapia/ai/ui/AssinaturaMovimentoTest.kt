package com.radioterapia.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O movimento da abertura: trilhas, estados inicial e final, curva, ponto de
 * partida da rota e encaixe.
 */
class AssinaturaMovimentoTest {

    private val eps = 1e-4f

    @Test
    fun `as tres trilhas terminam juntas aos 680 ms`() {
        assertEquals(AssinaturaMovimento.Trilha(0f, 320f), AssinaturaMovimento.PREFIXO)
        assertEquals(AssinaturaMovimento.Trilha(70f, 350f), AssinaturaMovimento.SUFIXO)
        assertEquals(AssinaturaMovimento.Trilha(210f, 470f), AssinaturaMovimento.TRACO)
        val fim = AssinaturaMovimento.TRILHAS.maxOf { it.inicioMs + it.duracaoMs }
        assertEquals(680f, fim, eps)
        assertEquals(fim, AssinaturaMovimento.TOTAL_MS, eps)
    }

    @Test
    fun `a curva e 1 menos o resto a quarta, limitada a zero e um`() {
        assertEquals(0f, AssinaturaMovimento.curva(0f), eps)
        assertEquals(1f, AssinaturaMovimento.curva(1f), eps)
        assertEquals(0f, AssinaturaMovimento.curva(-0.5f), eps)
        assertEquals(1f, AssinaturaMovimento.curva(1.5f), eps)
        assertEquals(1f - 0.5f * 0.5f * 0.5f * 0.5f, AssinaturaMovimento.curva(0.5f), eps)
        var anterior = -1f
        for (i in 0..100) {
            val v = AssinaturaMovimento.curva(i / 100f)
            assertTrue("curva nao decresce", v >= anterior)
            anterior = v
        }
    }

    @Test
    fun `no instante zero cada elemento esta no estado inicial`() {
        val q = AssinaturaMovimento.quadro(0f)
        assertEquals(0.16f, q.alfaPrefixo, eps)
        assertEquals(10f, q.descidaPrefixoDp, eps)
        assertEquals(0.08f, q.alfaSufixo, eps)
        assertEquals(8f, q.descidaSufixoDp, eps)
        assertEquals(0.975f, q.escalaSufixo, eps)
        assertEquals(0f, q.alfaTraco, eps)
        assertEquals(0f, q.escalaTraco, eps)
    }

    @Test
    fun `no fim cada elemento esta no estado final`() {
        val q = AssinaturaMovimento.quadro(AssinaturaMovimento.TOTAL_MS)
        assertEquals(1f, q.alfaPrefixo, eps)
        assertEquals(0f, q.descidaPrefixoDp, eps)
        assertEquals(1f, q.alfaSufixo, eps)
        assertEquals(0f, q.descidaSufixoDp, eps)
        assertEquals(1f, q.escalaSufixo, eps)
        assertEquals(1f, q.alfaTraco, eps)
        assertEquals(1f, q.escalaTraco, eps)
    }

    @Test
    fun `RT so comeca aos 70 ms e o traco so aos 210 ms`() {
        val antesDoRt = AssinaturaMovimento.quadro(69.9f)
        assertEquals(0.08f, antesDoRt.alfaSufixo, 1e-3f)
        val antesDoTraco = AssinaturaMovimento.quadro(209.9f)
        assertEquals(0f, antesDoTraco.escalaTraco, 1e-3f)
        assertTrue(AssinaturaMovimento.quadro(211f).escalaTraco > 0f)
    }

    @Test
    fun `a escala zero e so do traco, nunca das letras`() {
        for (t in 0..680 step 10) {
            val q = AssinaturaMovimento.quadro(t.toFloat())
            assertTrue(q.escalaSufixo >= 0.975f - eps)
            assertTrue(q.alfaPrefixo >= 0.16f - eps)
        }
    }

    @Test
    fun `a rota parte com tudo assentado, antes do fim nominal`() {
        val partida = AssinaturaMovimento.PARTIDA_MS
        assertTrue("antes do fim", partida < AssinaturaMovimento.TOTAL_MS)
        for (trilha in AssinaturaMovimento.TRILHAS) {
            assertTrue(trilha.progresso(partida) >= AssinaturaMovimento.ASSENTADO - eps)
        }
        // O traco e a trilha que decide: um instante antes, ele ainda nao assentou.
        assertTrue(AssinaturaMovimento.TRACO.progresso(partida - 1f) < AssinaturaMovimento.ASSENTADO)
        assertEquals(484.4f, partida, 0.5f)
    }

    @Test
    fun `o encaixe nunca aumenta e reduz pela medida que falta`() {
        // Cabe com folga: fica em 1.
        assertEquals(1f, AssinaturaMovimento.escalaDeEncaixe(840f, 800f, 300f, 1500f, 2000f), eps)
        // Largura manda: o maior entre nome e traco.
        assertEquals(0.5f, AssinaturaMovimento.escalaDeEncaixe(840f, 800f, 300f, 420f, 2000f), eps)
        assertEquals(0.5f, AssinaturaMovimento.escalaDeEncaixe(600f, 800f, 300f, 400f, 2000f), eps)
        // Altura manda.
        assertEquals(0.25f, AssinaturaMovimento.escalaDeEncaixe(840f, 800f, 400f, 2000f, 100f), eps)
        // Medidas invalidas nao deformam: 1.
        assertEquals(1f, AssinaturaMovimento.escalaDeEncaixe(0f, 0f, 0f, 100f, 100f), eps)
        assertEquals(1f, AssinaturaMovimento.escalaDeEncaixe(840f, 800f, 300f, 0f, 100f), eps)
    }
}
