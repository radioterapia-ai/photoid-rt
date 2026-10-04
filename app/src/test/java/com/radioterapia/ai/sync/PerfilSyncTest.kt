package com.radioterapia.ai.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O perfil sem estado, que é o que viaja no pacote de configuração e na cópia
 * enviada ao destino.
 *
 * O risco medido: o último erro guarda a mensagem da exceção do adaptador, que
 * cita o caminho remoto — e o caminho remoto traz o nome da pasta do paciente.
 */
class PerfilSyncTest {

    private fun perfil(ultima: Long, erro: String) = PerfilSync(
        id = "abc123def456",
        nome = "Servidor do hospital",
        tipo = PerfilSync.Tipo.SMB,
        ativo = true,
        host = "srv.hospital.local",
        porta = 445,
        usuario = "tablet_rt",
        protocolo = "SMB3",
        share = "rt",
        dominio = "HOSPITAL",
        caminhoRemoto = "PhotoID",
        ultimaSincronizacao = ultima,
        ultimoErro = erro,
    )

    @Test
    fun `org json real no classpath de teste`() {
        // Com o stub do android.jar à frente, org.json devolveria valores
        // padrão em silêncio e os testes abaixo passariam sem testar nada.
        assertEquals(1, JSONObject().put("a", 1).getInt("a"))
    }

    @Test
    fun `sem estado estabiliza o json`() {
        val a = perfil(0L, "")
        val b = perfil(1_791_037_500_000L,
            "STATUS_ACCESS_DENIED: \\\\srv\\rt\\MARIA - 123\\MARIA_ROSTO.jpg")
        assertEquals(a.semEstado().paraJson().toString(), b.semEstado().paraJson().toString())
    }

    @Test
    fun `sem estado nao leva nome de paciente nem senha`() {
        val json = perfil(1_791_037_500_000L,
            "STATUS_ACCESS_DENIED: \\\\srv\\rt\\MARIA - 123").semEstado().paraJson()
        val texto = json.toString()
        assertFalse(texto.contains("MARIA"))
        assertTrue(json.keys().asSequence().none {
            it.contains("senha", ignoreCase = true) || it.contains("password", ignoreCase = true)
        })
        assertEquals(0L, json.getLong("ultimaSincronizacao"))
        assertEquals("", json.getString("ultimoErro"))
    }

    @Test
    fun `sem estado preserva a configuracao`() {
        val original = perfil(1_791_037_500_000L, "erro qualquer")
        val limpo = original.semEstado()
        assertEquals(original.copy(ultimaSincronizacao = 0L, ultimoErro = ""), limpo)
        // Ida e volta pelo JSON que o importador grava como está.
        assertEquals(limpo, PerfilSync.deJson(JSONObject(limpo.paraJson().toString())))
    }
}
