package com.radioterapia.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Funções puras de nomeação/casamento de pastas. São a base de todo o
 * armazenamento: um erro aqui faz o app "perder" simulações que existem em
 * disco — exatamente o sintoma de "simulação não localizada" que vimos em campo.
 */
class StorageLocalTest {

    // ---------- removerAcentosMaiusculas ----------

    @Test
    fun removeAcentosEMaiusculiza() {
        assertEquals("JOAO CONCEICAO", StorageLocal.removerAcentosMaiusculas("João Conceição"))
        assertEquals("ANGELA MUNOZ", StorageLocal.removerAcentosMaiusculas("Ângela Muñoz"))
    }

    @Test
    fun removeCaracteresIlegaisDeArquivo() {
        // Vetor real: o OCR da etiqueta pode injetar esses caracteres.
        assertFalse(StorageLocal.removerAcentosMaiusculas("MARIA/SILVA").contains("/"))
        assertFalse(StorageLocal.removerAcentosMaiusculas("JOSE:SANTOS").contains(":"))
        assertFalse(StorageLocal.removerAcentosMaiusculas("ANA*PAULA").contains("*"))
        assertFalse(StorageLocal.removerAcentosMaiusculas("A<B>C|D?E\"F").matches(Regex(".*[<>|?\"].*")))
    }

    @Test
    fun naoDeixaPontoOuEspacoNoFim() {
        // Windows/SMB rejeitam nomes terminados em ponto ou espaço.
        val r = StorageLocal.removerAcentosMaiusculas("PACIENTE JR. ")
        assertFalse(r.endsWith(".")); assertFalse(r.endsWith(" "))
    }

    @Test
    fun toleraNulo() {
        assertEquals("", StorageLocal.removerAcentosMaiusculas(null))
    }

    // ---------- chaveNome ----------

    @Test
    fun chaveNomeNormalizaEspacosESublinhados() {
        assertEquals(
            StorageLocal.chaveNome("MARIA_DA_SILVA"),
            StorageLocal.chaveNome("Maria  da   Silva")
        )
    }

    // ---------- nomePastaPaciente ----------

    @Test
    fun montaPastaComProntuario() {
        assertEquals("FOSSE CDEE - 5667789",
            StorageLocal.nomePastaPaciente("Fosse Cdee", "5667789"))
    }

    @Test
    fun montaPastaSemProntuario() {
        assertEquals("FOSSE CDEE", StorageLocal.nomePastaPaciente("Fosse Cdee", ""))
        assertEquals("FOSSE CDEE", StorageLocal.nomePastaPaciente("Fosse Cdee", null))
    }

    // ---------- pastaCasaPaciente ----------

    @Test
    fun casaPastaNosTresFormatosHistoricos() {
        val nome = "Maria da Silva"
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA DA SILVA - 12345", nome))
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA DA SILVA_12345", nome))
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA DA SILVA", nome))
    }

    @Test
    fun naoCasaPacienteDiferente() {
        assertFalse(StorageLocal.pastaCasaPaciente("MARIA DA SILVEIRA - 1", "Maria da Silva"))
        assertFalse(StorageLocal.pastaCasaPaciente("JOAO SILVA - 1", "Maria da Silva"))
    }

    @Test
    fun casaIgnorandoAcentoECaixa() {
        assertTrue(StorageLocal.pastaCasaPaciente("JOAO CONCEICAO - 77", "joão conceição"))
    }
}
