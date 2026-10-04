package com.radioterapia.ai.update

import com.radioterapia.ai.transfer.PacoteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As regras da cópia de segurança antes de atualizar.
 *
 * O que erra numa cópia assim não é gravar o arquivo: é decidir o que entra. Uma
 * escolha por omissão — «todos os itens» — inclui o acervo de fotos inteiro.
 * Estas regras são puras, sem `Context` e sem org.json, para
 * que o teste prove o que a cópia faz e não o que um stub devolve.
 */
class BackupPreAtualizacaoTest {

    private val b = BackupPreAtualizacao

    // ---------- o que vai para o configuracao.zip ----------

    @Test
    fun `fotos nunca entram no pacote da copia`() {
        assertFalse(PacoteConfig.Item.FOTOS in b.ITENS_EXPORTADOS)
    }

    @Test
    fun `todo item que nao e foto entra no pacote da copia`() {
        // Item novo no enum entra na cópia por padrão; só a foto fica fora.
        PacoteConfig.Item.values()
            .filter { it != PacoteConfig.Item.FOTOS }
            .forEach { assertTrue("faltou ${it.name}", it in b.ITENS_EXPORTADOS) }
        assertEquals(PacoteConfig.Item.values().size - 1, b.ITENS_EXPORTADOS.size)
    }

    // ---------- o que vai para o preferencias.json ----------

    private val todas: Map<String, Any?> = linkedMapOf(
        "csv_pasta" to "\\\\servidor\\base",
        "smb_backup_host_1" to "10.0.0.5",
        "formato_data" to "dd/MM/yyyy",
        "sync_ativo" to true,
        "sync_intervalo_min" to 60,
        "csv_ultimo_sync" to 1_727_000_000_000L,
        "pdf_margem_mm" to 7.5f,
        "equipamentos" to setOf("Acelerador 1", "Acelerador 2"),
        "atualizacao_procurar" to false,
        "pasta_fotos_uri" to "content://x",
        "pasta_csv_uri" to "content://y",
        "backup_uri" to "content://z",
        "atualizacao_code" to 30,
        "atualizacao_nome" to "4.5",
        "atualizacao_dispensada" to 30,
        "atualizacao_checagem" to 1_727_000_000_000L,
        "atualizacao_de" to 29,
        "atualizacao_contagens" to "pacientes=1",
        "atualizacao_backup" to "/storage/x",
        "smb_senha" to "nao-deveria-estar-aqui",
        "nome_vazio" to null,
    )

    @Test
    fun `preferencias do servico entram com o tipo preservado`() {
        val r = b.chavesParaCopia(todas)
        assertEquals("\\\\servidor\\base", r["csv_pasta"])
        assertEquals("10.0.0.5", r["smb_backup_host_1"])
        assertEquals("dd/MM/yyyy", r["formato_data"])
        assertEquals(true, r["sync_ativo"])
        assertEquals(60, r["sync_intervalo_min"])
        assertEquals(1_727_000_000_000L, r["csv_ultimo_sync"])
        assertEquals(7.5f, r["pdf_margem_mm"])
        assertEquals(setOf("Acelerador 1", "Acelerador 2"), r["equipamentos"])
    }

    @Test
    fun `a escolha de nao procurar atualizacao e preservada`() {
        // Restaurar a cópia não pode religar em silêncio uma conexão que a
        // clínica desligou.
        assertEquals(false, b.chavesParaCopia(todas)["atualizacao_procurar"])
    }

    @Test
    fun `uris do seletor de pastas ficam fora`() {
        val r = b.chavesParaCopia(todas)
        assertFalse("pasta_fotos_uri" in r)
        assertFalse("pasta_csv_uri" in r)
        assertFalse("backup_uri" in r)
    }

    @Test
    fun `estado da atualizacao e marcador ficam fora`() {
        val r = b.chavesParaCopia(todas)
        listOf("atualizacao_code", "atualizacao_nome", "atualizacao_dispensada",
            "atualizacao_checagem", "atualizacao_de", "atualizacao_contagens",
            "atualizacao_backup").forEach { assertFalse(it, it in r) }
    }

    @Test
    fun `chave com nome de segredo fica fora`() {
        assertFalse("smb_senha" in b.chavesParaCopia(todas))
        assertFalse("x_token" in b.chavesParaCopia(mapOf("x_token" to "abc")))
        assertFalse("PASSWORD" in b.chavesParaCopia(mapOf("PASSWORD" to "abc")))
    }

    @Test
    fun `valor nulo fica fora`() {
        assertFalse("nome_vazio" in b.chavesParaCopia(todas))
    }

    @Test
    fun `long que cabe em int fica fora`() {
        // A importação gravaria como Int, e o getLong de quem lê lançaria.
        val r = b.chavesParaCopia(mapOf("marca" to 0L, "outra" to 123L))
        assertTrue(r.isEmpty())
    }

    @Test
    fun `conjunto que nao e de texto fica fora`() {
        assertTrue(b.chavesParaCopia(mapOf("numeros" to setOf(1, 2))).isEmpty())
    }

    // ---------- o que vai para interno/ ----------

    @Test
    fun `dados internos do app entram na copia crua`() {
        assertTrue(b.deveCopiarInterno("pacientes_cache.json"))
        assertTrue(b.deveCopiarInterno("audit_log.jsonl"))
        assertTrue(b.deveCopiarInterno("protocolos/p1/pag1.pdf"))
        assertTrue(b.deveCopiarInterno("protocolos/p1/miniatura.png"))
        assertTrue(b.deveCopiarInterno("rubricario/p17.png"))
        assertTrue(b.deveCopiarInterno("sync/enviados_x.txt"))
    }

    @Test
    fun `fotos de paciente nao entram na copia crua`() {
        assertFalse(b.deveCopiarInterno("sessao_atual/_rosto.jpg"))
        assertFalse(b.deveCopiarInterno("sessao_atual/sessao.json"))
        assertFalse(b.deveCopiarInterno("historico_thumbs/FULANO_1.jpg"))
        assertFalse(b.deveCopiarInterno("treatment_cache/123/a.jpg"))
        assertFalse(b.deveCopiarInterno("PhotoID_RT/PHOTOS/X - 1/X_ROSTO.jpg"))
    }

    @Test
    fun `foto fora das pastas conhecidas tambem fica fora`() {
        assertFalse(b.deveCopiarInterno("pasta_nova/foto.JPG"))
        assertFalse(b.deveCopiarInterno("pasta_nova/foto.jpeg"))
        assertFalse(b.deveCopiarInterno("pasta_nova/serie/img.dcm"))
    }

    @Test
    fun `base csv baixada fica fora`() {
        assertFalse(b.deveCopiarInterno("csv_baixado.csv"))
    }

    @Test
    fun `pasta recusada e reconhecida com barra no fim`() {
        // É assim que a varredura deixa de descer nela.
        assertFalse(b.deveCopiarInterno("sessao_atual/"))
        assertFalse(b.deveCopiarInterno("PhotoID_RT/"))
        assertTrue(b.deveCopiarInterno("rubricario/"))
        assertTrue(b.deveCopiarInterno("protocolos/p1/"))
    }

    @Test
    fun `caminho vazio ou com barra invertida`() {
        assertFalse(b.deveCopiarInterno(""))
        assertFalse(b.deveCopiarInterno("sessao_atual\\_rosto.jpg"))
        assertTrue(b.deveCopiarInterno("rubricario\\p17.png"))
    }
}
