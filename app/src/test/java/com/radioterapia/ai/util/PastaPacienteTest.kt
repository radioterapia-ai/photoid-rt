package com.radioterapia.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolução da pasta do paciente.
 *
 * Cada caso aqui nasceu de um defeito que grava ou lê no paciente ERRADO — o
 * tipo de falha que não aparece na tela e só é descoberta quando alguém nota
 * que a ficha de uma paciente traz o sítio de tratamento de outra.
 *
 * Não tocam em `Context`: só a formação e a comparação de nomes, que é onde os
 * defeitos estavam.
 */
class PastaPacienteTest {

    // ---------- nome da pasta ----------

    @Test
    fun `pasta leva nome e prontuario`() {
        assertEquals("MARIA SILVA - 123",
            StorageLocal.nomePastaPaciente("Maria Silva", "123"))
    }

    @Test
    fun `sem prontuario a pasta fica so com o nome`() {
        assertEquals("MARIA SILVA", StorageLocal.nomePastaPaciente("Maria Silva", ""))
    }

    @Test
    fun `acento sai do nome da pasta`() {
        assertEquals("JOAO CONCEICAO - 9",
            StorageLocal.nomePastaPaciente("João Conceição", "9"))
    }

    /**
     * Hífen e apóstrofo são LEGAIS em nome de arquivo e não podem ser
     * removidos: a normalização que os apagava criava a pasta "MARIA DARC"
     * enquanto a busca procurava "MARIA D'ARC", e a pasta ficava invisível
     * para o próprio app.
     */
    @Test
    fun `apostrofo e hifen sobrevivem no nome da pasta`() {
        assertEquals("MARIA D'ARC SILVA-SOUZA - 5",
            StorageLocal.nomePastaPaciente("Maria D'Arc Silva-Souza", "5"))
    }

    // ---------- casamento pasta x paciente ----------

    @Test
    fun `casa a pasta do proprio paciente`() {
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA - 123", "Maria Silva"))
    }

    @Test
    fun `casa pasta antiga com separador underscore`() {
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA_123", "Maria Silva"))
    }

    /** Compatibilidade: pastas gravadas pela normalização antiga, sem apóstrofo. */
    @Test
    fun `casa pasta legada gravada sem apostrofo`() {
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA DARC - 5", "Maria D'Arc"))
    }

    /**
     * O caso que motivou tudo: "ANA" NÃO pode adotar a pasta de "ANA MARIA".
     * O sufixo aceito depois do nome é só o prontuário (dígitos).
     */
    @Test
    fun `nome que e prefixo de outro nao casa`() {
        assertFalse(StorageLocal.pastaCasaPaciente("ANA MARIA - 999", "Ana"))
    }

    @Test
    fun `homonimas com prontuarios diferentes sao pastas distintas`() {
        val a = StorageLocal.nomePastaPaciente("Maria Silva", "123")
        val b = StorageLocal.nomePastaPaciente("Maria Silva", "456")
        assertFalse("homônimas não podem compartilhar pasta", a == b)
    }

    // ---------- leitura de volta ----------

    @Test
    fun `extrai prontuario da pasta`() {
        assertEquals("123", StorageLocal.prontuarioDaPasta("MARIA SILVA - 123"))
    }

    @Test
    fun `extrai prontuario ignorando sufixo de reirradiacao`() {
        assertEquals("123",
            StorageLocal.prontuarioDaPasta("MARIA SILVA - 123 NOVA SIMULACAO 1"))
    }

    @Test
    fun `pasta sem prontuario devolve vazio`() {
        assertEquals("", StorageLocal.prontuarioDaPasta("MARIA SILVA"))
    }

    @Test
    fun `extrai nome da pasta sem prontuario nem sufixo`() {
        assertEquals("MARIA SILVA",
            StorageLocal.nomeDaPasta("MARIA SILVA - 123 NOVA SIMULACAO 2"))
    }
}
