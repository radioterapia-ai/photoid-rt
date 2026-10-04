package com.radioterapia.ai.sync

import com.radioterapia.ai.sync.EstadoSyncFinalizacao.Fase
import com.radioterapia.ai.sync.EstadoSyncFinalizacao.LIMITE_ESPERA_MS
import com.radioterapia.ai.sync.EstadoSyncFinalizacao.Linha
import com.radioterapia.ai.sync.EstadoSyncFinalizacao.calcular
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A tabela da linha de sincronia da finalização.
 *
 * O erro que importa aqui é a linha dizer «realizada» sem ter conferido: o
 * técnico deixaria de verificar o envio de um prontuário que não subiu.
 */
class EstadoSyncFinalizacaoTest {

    @Test
    fun `sem trabalho acompanhado a linha fica oculta`() {
        assertEquals(Linha.OCULTA, calcular(null, 0, 0L, null, null))
        assertEquals(Linha.OCULTA, calcular(null, 3, 999_999L, 12, 0))
    }

    @Test
    fun `rodando e em curso`() {
        assertEquals(Linha.EM_CURSO, calcular(Fase.RODANDO, 0, 0L, null, null))
        assertEquals(Linha.EM_CURSO, calcular(Fase.RODANDO, 2, 500_000L, null, null))
    }

    @Test
    fun `na fila sem tentativa e em curso ate o limite`() {
        assertEquals(Linha.EM_CURSO, calcular(Fase.AGUARDANDO, 0, 0L, null, null))
        assertEquals(Linha.EM_CURSO, calcular(Fase.AGUARDANDO, 0, 119_999L, null, null))
    }

    @Test
    fun `na fila sem tentativa passa a falhou no limite`() {
        assertEquals(120_000L, LIMITE_ESPERA_MS)
        assertEquals(Linha.FALHOU, calcular(Fase.AGUARDANDO, 0, 120_000L, null, null))
        assertEquals(Linha.FALHOU, calcular(Fase.AGUARDANDO, 0, 600_000L, null, null))
    }

    @Test
    fun `na fila depois de tentar e falhou`() {
        assertEquals(Linha.FALHOU, calcular(Fase.AGUARDANDO, 1, 0L, null, null))
        assertEquals(Linha.FALHOU, calcular(Fase.AGUARDANDO, 4, 10L, null, null))
    }

    @Test
    fun `falha e cancelamento sao falhou`() {
        assertEquals(Linha.FALHOU, calcular(Fase.FALHA, 0, 0L, null, null))
        assertEquals(Linha.FALHOU, calcular(Fase.CANCELADO, 0, 0L, null, null))
        // Mesmo com conferência limpa: o trabalho não terminou bem.
        assertEquals(Linha.FALHOU, calcular(Fase.FALHA, 1, 0L, 12, 0))
    }

    @Test
    fun `sucesso sem conferencia ainda e em curso`() {
        assertEquals(Linha.EM_CURSO, calcular(Fase.SUCESSO, 0, 0L, null, null))
        assertEquals(Linha.EM_CURSO, calcular(Fase.SUCESSO, 0, 0L, 12, null))
    }

    @Test
    fun `sucesso com pasta sem arquivos elegiveis e ok geral`() {
        assertEquals(Linha.OK_SEM_CONFERENCIA, calcular(Fase.SUCESSO, 0, 0L, 0, 0))
    }

    @Test
    fun `sucesso conferido sem pendencia e ok`() {
        assertEquals(Linha.OK, calcular(Fase.SUCESSO, 0, 0L, 12, 0))
    }

    @Test
    fun `sucesso com arquivo pendente e falhou`() {
        assertEquals(Linha.FALHOU, calcular(Fase.SUCESSO, 0, 0L, 12, 3))
        assertEquals(Linha.FALHOU, calcular(Fase.SUCESSO, 0, 0L, 1, 1))
    }
}
