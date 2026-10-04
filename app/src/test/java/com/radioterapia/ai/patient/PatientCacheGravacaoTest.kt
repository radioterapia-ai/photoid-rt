package com.radioterapia.ai.patient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * O cadastro de pacientes nunca pode virar um cadastro vazio por acidente de
 * leitura: arquivo ilegível é sinalizado, não confundido com "sem pacientes", e
 * a gravação substitui o arquivo de uma vez.
 */
class PatientCacheGravacaoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun arquivo(): File = File(tmp.root, "pacientes_cache.json")

    @Test
    fun `sem arquivo o cadastro comeca vazio e a leitura nao falhou`() {
        val (obj, falhou) = PatientCache.lerCadastro(arquivo())
        assertEquals(0, obj.length())
        assertFalse(falhou)
    }

    @Test
    fun `cadastro legivel volta inteiro`() {
        arquivo().writeText("""{"MARIA DA SILVA | 1001":{"prontuario":"1001"}}""")
        val (obj, falhou) = PatientCache.lerCadastro(arquivo())
        assertFalse(falhou)
        assertTrue(obj.has("MARIA DA SILVA | 1001"))
    }

    @Test
    fun `arquivo com conteudo ilegivel e sinalizado e nao tratado como sem pacientes`() {
        arquivo().writeText("""{"MARIA DA SILVA | 1001":{"prontuario"}}""")
        val (obj, falhou) = PatientCache.lerCadastro(arquivo())
        assertTrue(falhou)
        assertEquals(0, obj.length())
    }

    @Test
    fun `gravacao atomica deixa o conteudo novo e nenhum temporario`() {
        arquivo().writeText("""{"A":{}}""")
        assertTrue(PatientCache.gravarAtomico(arquivo(), """{"A":{},"B":{}}"""))
        assertEquals(2, JSONObject(arquivo().readText()).length())
        assertFalse(File(tmp.root, "pacientes_cache.json.tmp").exists())
    }

    @Test
    fun `ilegivel sai do caminho sem ser apagado`() {
        arquivo().writeText("lixo")
        PatientCache.guardarIlegivel(arquivo())
        assertFalse(arquivo().exists())
        val guardados = tmp.root.listFiles { f -> f.name.startsWith("pacientes_cache.ilegivel_") }
        assertEquals(1, guardados?.size)
        assertEquals("lixo", guardados!![0].readText())
    }
}
