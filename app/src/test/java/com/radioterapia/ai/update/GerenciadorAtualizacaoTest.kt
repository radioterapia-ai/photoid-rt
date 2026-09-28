package com.radioterapia.ai.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As decisões da procura de atualização.
 *
 * Nenhuma delas toca rede ou `Context`: é de propósito que elas moram fora do
 * [AtualizacaoRemota]. O que erra numa função de atualização não é o download —
 * é a comparação de versão, o «depois» que silencia para sempre, e o convite
 * oferecido a um aparelho que não pode instalar.
 */
class GerenciadorAtualizacaoTest {

    private val g = GerenciadorAtualizacao

    // ---------- cabe atualizar? ----------

    @Test
    fun `versao mais nova pode ser instalada`() {
        assertTrue(g.cabeAtualizar(instalado = 28, publicado = 29,
            minSdkPublicado = 24, sdkDoAparelho = 29))
    }

    @Test
    fun `versao igual nao e atualizacao`() {
        assertFalse(g.cabeAtualizar(28, 28, 24, 29))
    }

    @Test
    fun `publicacao mais antiga nao rebaixa o app`() {
        // Cenário real: alguém marca uma release velha como «latest» por engano.
        assertFalse(g.cabeAtualizar(28, 27, 24, 29))
    }

    @Test
    fun `minSdk acima do aparelho nao e oferecido`() {
        // O tablet API 24 não pode receber convite para uma versão que exige 26:
        // o download terminaria num erro do instalador que o técnico não tem
        // como resolver, e ele passaria a desconfiar do aviso.
        assertFalse(g.cabeAtualizar(28, 29, minSdkPublicado = 26, sdkDoAparelho = 24))
    }

    @Test
    fun `minSdk igual ao do aparelho ainda cabe`() {
        assertTrue(g.cabeAtualizar(28, 29, minSdkPublicado = 24, sdkDoAparelho = 24))
    }

    // ---------- cabe avisar? ----------

    @Test
    fun `avisa quando a publicada e mais nova e nada foi dispensado`() {
        assertTrue(g.deveAvisar(instalado = 28, publicado = 29, dispensado = 0))
    }

    @Test
    fun `depois silencia a versao adiada`() {
        assertFalse(g.deveAvisar(28, 29, dispensado = 29))
    }

    @Test
    fun `depois nao silencia a versao seguinte`() {
        // O ponto do «dispensado» ser NÚMERO e não sim-ou-não: adiar a 4.4 não
        // pode desligar o aviso da 4.5.
        assertTrue(g.deveAvisar(28, 30, dispensado = 29))
    }

    @Test
    fun `nao avisa sobre a versao ja instalada`() {
        assertFalse(g.deveAvisar(instalado = 29, publicado = 29, dispensado = 0))
    }

    // ---------- hora de perguntar? ----------

    @Test
    fun `primeira vez sempre pergunta`() {
        assertTrue(g.horaDeChecar(agora = 1_000_000L, ultima = 0L))
    }

    @Test
    fun `nao repergunta dentro do intervalo`() {
        val agora = 1_000_000_000L
        assertFalse(g.horaDeChecar(agora, ultima = agora - 60_000L))
    }

    @Test
    fun `pergunta de novo passado o intervalo`() {
        val agora = 1_000_000_000L
        assertTrue(g.horaDeChecar(agora, ultima = agora - GerenciadorAtualizacao.INTERVALO_CHECAGEM_MS))
    }

    @Test
    fun `relogio para tras nao trava a checagem para sempre`() {
        // Tablet de sala tem a data corrigida à mão de vez em quando. Se a marca
        // de tempo gravada estiver no FUTURO, a subtração fica negativa e sem
        // esta regra o app pararia de procurar até aquela data chegar.
        val agora = 1_000_000_000L
        assertTrue(g.horaDeChecar(agora, ultima = agora + 999_999_999L))
    }
}
