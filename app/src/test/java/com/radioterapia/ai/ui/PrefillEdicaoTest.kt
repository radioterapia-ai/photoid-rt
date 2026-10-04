package com.radioterapia.ai.ui

import com.radioterapia.ai.util.TimeOutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rodada de fotos adicionais da mesma simulação: a tela de confirmação
 * começa com o Time-Out e a observação gravados, e a regravação não apaga
 * registro que ninguém limpou.
 */
class PrefillEdicaoTest {

    private val maxFracoes = 40

    /** A tela como o onCreate a deixa: médico e equipamento do cadastro, o resto vazio. */
    private val telaNova = PrefillEdicao.Campos(
        sitio = "", medico = "DR. CADASTRO", equipamento = "ACELERADOR 1",
        riscoQueda = false, precaucaoContato = false, alergia = false,
        fracoes = 0, protocolo = "", observacao = "")

    private val gravado = TimeOutStore.Registro(
        ativo = true, medico = "DRA. SIMULACAO", sitio = "MAMA E",
        riscoQueda = true, precaucaoContato = false,
        equipamento = "ACELERADOR 2", alergia = "SIM",
        fracoesMax = 25, protocoloId = "mama")

    /** O registro que a confirmação monta da tela, como montarTimeOut faz. */
    private fun registroDaTela(c: PrefillEdicao.Campos) = TimeOutStore.Registro(
        true, c.medico, c.sitio, c.riscoQueda, c.precaucaoContato, c.equipamento,
        if (c.alergia) "SIM" else "", c.fracoes, c.protocolo)

    // ---------- o que a tela mostra ----------

    @Test
    fun `sem registro nem observacao a tela fica como a de uma simulacao nova`() {
        assertEquals(telaNova, PrefillEdicao.aplicar(telaNova, telaNova, null, "", maxFracoes))
    }

    @Test
    fun `com registro alertas sitio fracoes protocolo e observacao voltam`() {
        val c = PrefillEdicao.aplicar(telaNova, telaNova, gravado, "Rampa 15 graus", maxFracoes)
        assertEquals("MAMA E", c.sitio)
        assertEquals("DRA. SIMULACAO", c.medico)
        assertEquals("ACELERADOR 2", c.equipamento)
        assertTrue(c.riscoQueda)
        assertFalse(c.precaucaoContato)
        assertTrue(c.alergia)
        assertEquals(25, c.fracoes)
        assertEquals("mama", c.protocolo)
        assertEquals("Rampa 15 graus", c.observacao)
    }

    @Test
    fun `regravar a tela carregada devolve o mesmo registro`() {
        val c = PrefillEdicao.aplicar(telaNova, telaNova, gravado, "Rampa 15 graus", maxFracoes)
        assertEquals(gravado, registroDaTela(c))
    }

    @Test
    fun `texto vazio no registro nao apaga o que o cadastro trouxe`() {
        val semTexto = gravado.copy(medico = "", equipamento = "", sitio = "")
        val c = PrefillEdicao.aplicar(telaNova, telaNova, semTexto, "", maxFracoes)
        assertEquals("DR. CADASTRO", c.medico)
        assertEquals("ACELERADOR 1", c.equipamento)
        assertEquals("", c.sitio)
        assertTrue(c.alergia)
    }

    @Test
    fun `interruptor desligado no registro vem desligado`() {
        val tela = telaNova.copy(riscoQueda = true)
        val c = PrefillEdicao.aplicar(tela, tela, gravado.copy(riscoQueda = false), "", maxFracoes)
        assertFalse(c.riscoQueda)
    }

    @Test
    fun `alergia so liga com SIM`() {
        assertFalse(PrefillEdicao.aplicar(telaNova, telaNova,
            gravado.copy(alergia = "NAO"), "", maxFracoes).alergia)
        assertFalse(PrefillEdicao.aplicar(telaNova, telaNova,
            gravado.copy(alergia = ""), "", maxFracoes).alergia)
    }

    @Test
    fun `campo mexido durante a leitura fica com o da tela`() {
        val mexida = telaNova.copy(sitio = "PROSTATA", alergia = true, fracoes = 3,
            protocolo = "prostata", observacao = "digitada agora")
        val c = PrefillEdicao.aplicar(telaNova, mexida,
            gravado.copy(alergia = ""), "Rampa 15 graus", maxFracoes)
        assertEquals("PROSTATA", c.sitio)
        assertTrue(c.alergia)
        assertEquals(3, c.fracoes)
        assertEquals("prostata", c.protocolo)
        assertEquals("digitada agora", c.observacao)
        // O que ninguém mexeu ainda vem do registro.
        assertTrue(c.riscoQueda)
        assertEquals("DRA. SIMULACAO", c.medico)
    }

    @Test
    fun `fracoes gravadas fora da lista entram nela`() {
        assertEquals(40, PrefillEdicao.aplicar(telaNova, telaNova,
            gravado.copy(fracoesMax = 55), "", maxFracoes).fracoes)
        assertEquals(0, PrefillEdicao.aplicar(telaNova, telaNova,
            gravado.copy(fracoesMax = -3), "", maxFracoes).fracoes)
    }

    @Test
    fun `observacao gravada vem mesmo sem registro do Time-Out`() {
        val c = PrefillEdicao.aplicar(telaNova, telaNova, null, "  Rampa 15 graus\n", maxFracoes)
        assertEquals("Rampa 15 graus", c.observacao)
        assertEquals(telaNova.copy(observacao = "Rampa 15 graus"), c)
    }

    @Test
    fun `a carga so roda na edicao e nao se repete na recriacao da tela`() {
        assertTrue(PrefillEdicao.precisaCarregar(editando = true, cargaRestaurada = false))
        // Recriada depois da carga: os campos voltam pelo estado salvo, com o
        // que o técnico mudou depois dela.
        assertFalse(PrefillEdicao.precisaCarregar(editando = true, cargaRestaurada = true))
        assertFalse(PrefillEdicao.precisaCarregar(editando = false, cargaRestaurada = false))
        assertFalse(PrefillEdicao.precisaCarregar(editando = false, cargaRestaurada = true))
    }

    // ---------- o que a regravação faz com o registro ----------

    @Test
    fun `com a pagina ligada vale a tela`() {
        val daTela = registroDaTela(telaNova)
        assertEquals(RegrasFinalizacao.AcaoTimeOut.Gravar(daTela),
            RegrasFinalizacao.acaoTimeOut(daTela, gravado, true, "mama"))
        assertEquals(RegrasFinalizacao.AcaoTimeOut.Gravar(daTela),
            RegrasFinalizacao.acaoTimeOut(daTela, null, false, ""))
    }

    @Test
    fun `simulacao nova com a pagina desligada fica sem registro`() {
        assertSame(RegrasFinalizacao.AcaoTimeOut.Apagar,
            RegrasFinalizacao.acaoTimeOut(null, null, false, ""))
    }

    @Test
    fun `edicao com a pagina desligada mantem o registro com o protocolo da tela`() {
        assertEquals(RegrasFinalizacao.AcaoTimeOut.Gravar(gravado.copy(protocoloId = "outro")),
            RegrasFinalizacao.acaoTimeOut(null, gravado, true, "outro"))
        assertEquals(RegrasFinalizacao.AcaoTimeOut.Gravar(gravado),
            RegrasFinalizacao.acaoTimeOut(null, gravado, true, "mama"))
    }

    @Test
    fun `edicao com a pagina desligada nunca apaga`() {
        assertSame(RegrasFinalizacao.AcaoTimeOut.Manter,
            RegrasFinalizacao.acaoTimeOut(null, null, true, ""))
        assertSame(RegrasFinalizacao.AcaoTimeOut.Manter,
            RegrasFinalizacao.acaoTimeOut(null, gravado.copy(ativo = false), true, "mama"))
    }
}
