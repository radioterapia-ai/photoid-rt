package com.radioterapia.ai.sync

import com.radioterapia.ai.transfer.CopiaConfiguracao
import com.radioterapia.ai.update.BackupPreAtualizacao
import com.radioterapia.ai.util.FotosArquivadas
import com.radioterapia.ai.util.QuarentenaArquivadas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * As regras puras do motor: o que entra na varredura, a chave de cada arquivo,
 * a ordem de envio, o prazo e a espera pela vez, e o que a varredura faz quando
 * o perfil muda no meio da rodada.
 *
 * A varredura e a conferência da pasta da finalização passam pelas mesmas
 * funções. Se a chave fosse calculada de dois jeitos, a conferência procuraria
 * no índice um caminho que a varredura nunca gravou, e todo prontuário
 * pareceria pendente.
 */
class MotorSyncRegrasTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val m = MotorSync

    // ---------- pastas ----------

    /** As exclusões fixas valem com a base examinada ou não. */
    private fun foraSempre(nome: String) {
        assertFalse(m.pastaEntraNaVarredura(nome, true))
        assertFalse(m.pastaEntraNaVarredura(nome, false))
    }

    @Test
    fun `pastas de trabalho em andamento ficam fora`() {
        foraSempre("_tmp")
        foraSempre(".thumbnails")
        foraSempre(".git")
    }

    @Test
    fun `copia antes de atualizar fica fora`() {
        assertEquals("BACKUP_ATUALIZACAO", BackupPreAtualizacao.NOME_PASTA)
        foraSempre(BackupPreAtualizacao.NOME_PASTA)
    }

    @Test
    fun `quarentena de arquivadas fica fora`() {
        assertEquals("_QUARENTENA_ARQUIVADAS", QuarentenaArquivadas.NOME_PASTA)
        foraSempre(QuarentenaArquivadas.NOME_PASTA)
    }

    @Test
    fun `pastas do acervo entram`() {
        assertTrue(m.pastaEntraNaVarredura("PhotoID_RT", true))
        assertTrue(m.pastaEntraNaVarredura("PHOTOS", true))
        assertTrue(m.pastaEntraNaVarredura("DATABASE", true))
        assertTrue(m.pastaEntraNaVarredura("MARIA SILVA - 123", true))
        assertTrue(m.pastaEntraNaVarredura("MARIA SILVA - 123 NOVA SIMULACAO 1", true))
        assertTrue(m.pastaEntraNaVarredura("ARQUIVADAS", true))
        assertTrue(m.pastaEntraNaVarredura(CopiaConfiguracao.NOME_PASTA, true))
    }

    @Test
    fun `arquivadas so entram com a base examinada pela quarentena`() {
        assertEquals("ARQUIVADAS", FotosArquivadas.PASTA)
        assertFalse(m.pastaEntraNaVarredura(FotosArquivadas.PASTA, false))
        assertTrue(m.pastaEntraNaVarredura(FotosArquivadas.PASTA, true))
        // O resto do acervo não espera a quarentena.
        assertTrue(m.pastaEntraNaVarredura("PhotoID_RT", false))
        assertTrue(m.pastaEntraNaVarredura("PHOTOS", false))
        assertTrue(m.pastaEntraNaVarredura("MARIA SILVA - 123", false))
        assertTrue(m.pastaEntraNaVarredura(CopiaConfiguracao.NOME_PASTA, false))
    }

    @Test
    fun `arquivo em arquivadas e reconhecido pelo caminho relativo`() {
        assertTrue(m.dentroDeArquivadas("MARIA SILVA - 123/ARQUIVADAS/_rosto_ARQ1.jpg"))
        assertTrue(m.dentroDeArquivadas("MARIA SILVA - 123 NOVA SIMULACAO 1/ARQUIVADAS/_rosto_ARQ1_ORIGINAL.jpg"))
        assertFalse(m.dentroDeArquivadas("MARIA SILVA - 123/M_S_ROST_03_SET_2026_14_22_05_1.jpg"))
        assertFalse("o nome do arquivo nao conta", m.dentroDeArquivadas("MARIA SILVA - 123/ARQUIVADAS"))
        assertFalse(m.dentroDeArquivadas("MARIA SILVA - 123/ARQUIVADAS_VELHAS/x.jpg"))
    }

    @Test
    fun `marca que caiu durante a listagem tira da lista so o que esta em arquivadas`() {
        val arquivada = "ANA - 1/ARQUIVADAS/_rosto_ARQ1.jpg"
        val foto = "ANA - 1/A_ROST_03_SET_2026_14_22_05_1.jpg"
        assertTrue(m.entraNaLista(arquivada, true))
        assertFalse(m.entraNaLista(arquivada, false))
        assertTrue(m.entraNaLista(foto, false))
        assertTrue(m.entraNaLista(CopiaConfiguracao.NOME_PASTA + "/CONFIG_X.zip", false))
    }

    @Test
    fun `sem base examinada a varredura nao desce em arquivadas`() {
        val raiz = tmp.newFolder("PhotoID_RT")
        val paciente = File(File(raiz, "PHOTOS"), "ANA - 1").apply { mkdirs() }
        File(paciente, "A_ROST_1.jpg").writeText("rosto")
        File(File(paciente, FotosArquivadas.PASTA).apply { mkdirs() }, "_rosto_ARQ1.jpg").writeText("arquivada")

        fun listar(liberadas: Boolean): Set<String> =
            raiz.walkTopDown()
                .onEnter { d -> m.pastaEntraNaVarredura(d.name, liberadas) }
                .filter { it.isFile }
                .mapNotNull { m.chaveRelativa(raiz.absolutePath, it.absolutePath) }
                .toSet()

        assertEquals(setOf("ANA - 1/A_ROST_1.jpg"), listar(false))
        assertEquals(setOf("ANA - 1/A_ROST_1.jpg", "ANA - 1/ARQUIVADAS/_rosto_ARQ1.jpg"), listar(true))
    }

    // ---------- arquivos ----------

    @Test
    fun `arquivo oculto temporario ou vazio nao e elegivel`() {
        assertFalse(m.arquivoElegivel(".timeout_sim1.json", 120L))
        assertFalse(m.arquivoElegivel(".obs_sim2.txt", 40L))
        assertFalse(m.arquivoElegivel("foto.tmp", 1_000L))
        assertFalse(m.arquivoElegivel("FOTO.TMP", 1_000L))
        assertFalse(m.arquivoElegivel(".CONFIG_X_03_OUT_2026_14_25_A1B2C3D4.zip.99.tmp", 1_000L))
        assertFalse(m.arquivoElegivel("M_S_ROST_03_SET_2026_14_22_05_1.jpg", 0L))
    }

    @Test
    fun `foto pdf e copia da configuracao sao elegiveis`() {
        assertTrue(m.arquivoElegivel("M_S_ROST_03_SET_2026_14_22_05_1.jpg", 1L))
        assertTrue(m.arquivoElegivel("M_S_FSIM_03_SET_2026_14_22_05_1.pdf", 90_000L))
        assertTrue(m.arquivoElegivel("CONFIG_X_03_OUT_2026_14_25_A1B2C3D4.zip", 4_096L))
    }

    // ---------- chave relativa ----------

    @Test
    fun `foto de paciente perde o PHOTOS da frente`() {
        assertEquals("MARIA - 1/a.jpg",
            m.chaveRelativa("/x/PhotoID_RT", "/x/PhotoID_RT/PHOTOS/MARIA - 1/a.jpg"))
    }

    @Test
    fun `pastas irmas de PHOTOS ficam como estao`() {
        assertEquals("DATABASE/b.csv",
            m.chaveRelativa("/x/PhotoID_RT", "/x/PhotoID_RT/DATABASE/b.csv"))
        assertEquals("_CONFIG_PHOTOID_RT/CONFIG_X_03_OUT_2026_14_25_A1B2C3D4.zip",
            m.chaveRelativa("/x/PhotoID_RT",
                "/x/PhotoID_RT/_CONFIG_PHOTOID_RT/CONFIG_X_03_OUT_2026_14_25_A1B2C3D4.zip"))
        // Só o nível PHOTOS exato some.
        assertEquals("PHOTOSX/a.jpg",
            m.chaveRelativa("/x/PhotoID_RT", "/x/PhotoID_RT/PHOTOSX/a.jpg"))
    }

    @Test
    fun `separador do Windows normalizado`() {
        assertEquals("MARIA - 1/ARQUIVADAS/a.jpg",
            m.chaveRelativa("C:\\x\\PhotoID_RT",
                "C:\\x\\PhotoID_RT\\PHOTOS\\MARIA - 1\\ARQUIVADAS\\a.jpg"))
        assertEquals("MARIA - 1/a.jpg",
            m.chaveRelativa("/x/PhotoID_RT/", "/x/PhotoID_RT/PHOTOS/MARIA - 1/a.jpg"))
    }

    @Test
    fun `arquivo fora da raiz nao tem chave`() {
        assertNull(m.chaveRelativa("/x/PhotoID_RT", "/y/PHOTOS/MARIA - 1/a.jpg"))
        // Prefixo de texto não é prefixo de pasta.
        assertNull(m.chaveRelativa("/x/PhotoID_RT", "/x/PhotoID_RT2/PHOTOS/a.jpg"))
        assertNull(m.chaveRelativa("/x/PhotoID_RT", "/x/PhotoID_RT"))
        assertNull(m.chaveRelativa("", "/x/a.jpg"))
    }

    @Test
    fun `sem prefixo PHOTOS`() {
        assertEquals("A/b.jpg", m.semPrefixoPhotos("PHOTOS/A/b.jpg"))
        assertEquals("DATABASE/b.csv", m.semPrefixoPhotos("DATABASE/b.csv"))
        assertEquals("PHOTOS", m.semPrefixoPhotos("PHOTOS"))
    }

    // ---------- ordem ----------

    @Test
    fun `copia da configuracao reconhecida so na raiz`() {
        assertTrue(m.ehCopiaConfiguracao("_CONFIG_PHOTOID_RT/CONFIG_X.zip"))
        assertFalse(m.ehCopiaConfiguracao("MARIA - 1/_CONFIG_PHOTOID_RT/a.zip"))
        assertFalse(m.ehCopiaConfiguracao("_CONFIG_PHOTOID_RTX/a.zip"))
        assertFalse(m.ehCopiaConfiguracao("_CONFIG_PHOTOID_RT"))
    }

    @Test
    fun `copia da configuracao vai na frente e o resto por data crescente`() {
        val itens = listOf(
            "MARIA - 1/a.jpg" to 300L,
            "_CONFIG_PHOTOID_RT/CONFIG_X.zip" to 900L,
            "DATABASE/b.csv" to 100L,
            "MARIA - 1/b.jpg" to 200L,
        )
        val ordem = itens.sortedWith(
            compareBy<Pair<String, Long>>({ m.prioridade(it.first) }, { it.second }))
            .map { it.first }
        assertEquals(listOf("_CONFIG_PHOTOID_RT/CONFIG_X.zip", "DATABASE/b.csv",
            "MARIA - 1/b.jpg", "MARIA - 1/a.jpg"), ordem)
    }

    // ---------- gatilho de finalização ----------

    @Test
    fun `atraso da finalizacao fica acima da janela de frescor`() {
        assertTrue(SyncWorker.ATRASO_FINALIZAR_S * 1_000L > MotorSync.JANELA_FRESCOR_MS)
    }

    // ---------- prazo ----------

    @Test
    fun `prazo padrao deixa folga antes dos 10 minutos do WorkManager`() {
        // Pelo menos um minuto para o envio em curso quando o prazo vence.
        assertTrue(MotorSync.LIMITE_PADRAO_MS <= 10 * 60 * 1000L - 60_000L)
    }

    @Test
    fun `prazo final soma o limite a entrada sem estourar`() {
        assertEquals(1_500L, m.prazoFinal(1_000L, 500L))
        assertEquals(1_000L, m.prazoFinal(1_000L, 0L))
        assertEquals(1_000L, m.prazoFinal(1_000L, -5L))
        assertEquals(Long.MAX_VALUE, m.prazoFinal(Long.MAX_VALUE - 10L, 100L))
        assertEquals(Long.MAX_VALUE, m.prazoFinal(1_000L, Long.MAX_VALUE))
    }

    @Test
    fun `espera da fatia nunca passa do prazo nem da fatia`() {
        assertEquals(0L, m.esperaDaFatia(fim = 1_000L, agora = 1_000L))
        assertEquals(0L, m.esperaDaFatia(fim = 1_000L, agora = 5_000L))
        assertEquals(300L, m.esperaDaFatia(fim = 1_300L, agora = 1_000L))
        assertEquals(MotorSync.FATIA_ESPERA_MS, m.esperaDaFatia(fim = Long.MAX_VALUE, agora = 1_000L))
    }

    // ---------- espera pela vez ----------

    @Test
    fun `trava livre e pega na hora mesmo com prazo vencido`() {
        val trava = ReentrantLock()
        assertTrue(m.pegarTrava(trava, fim = 0L) { true })
        assertTrue(trava.isHeldByCurrentThread)
        trava.unlock()
    }

    @Test
    fun `espera pela vez sai do mesmo prazo e desiste quando ele vence`() {
        val trava = ReentrantLock()
        comTravaEmOutraThread(trava) {
            val inicio = System.currentTimeMillis()
            val pegou = m.pegarTrava(trava, fim = inicio + 300L) { true }
            val levou = System.currentTimeMillis() - inicio
            assertFalse(pegou)
            assertFalse(trava.isHeldByCurrentThread)
            assertTrue("esperou $levou ms", levou >= 250L)
            assertTrue("esperou $levou ms", levou < 3_000L)
        }
    }

    @Test
    fun `sincronizacao desligada libera quem espera em ate uma fatia`() {
        val trava = ReentrantLock()
        comTravaEmOutraThread(trava) {
            val inicio = System.currentTimeMillis()
            val pegou = m.pegarTrava(trava, fim = inicio + 60_000L) { false }
            val levou = System.currentTimeMillis() - inicio
            assertFalse(pegou)
            assertTrue("esperou $levou ms", levou < MotorSync.FATIA_ESPERA_MS + 3_000L)
        }
    }

    @Test
    fun `quem espera pega a vez quando a varredura da frente termina`() {
        val trava = ReentrantLock()
        val segurando = CountDownLatch(1)
        val dona = thread {
            trava.lock()
            segurando.countDown()
            try { Thread.sleep(200L) } finally { trava.unlock() }
        }
        assertTrue(segurando.await(5, TimeUnit.SECONDS))
        val pegou = m.pegarTrava(trava, fim = System.currentTimeMillis() + 10_000L) { true }
        assertTrue(pegou)
        assertTrue(trava.isHeldByCurrentThread)
        trava.unlock()
        dona.join(5_000L)
    }

    /** Segura [trava] em outra thread enquanto [bloco] roda nesta. */
    private fun comTravaEmOutraThread(trava: ReentrantLock, bloco: () -> Unit) {
        val segurando = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        val dona = thread {
            trava.lock()
            try {
                segurando.countDown()
                soltar.await(30, TimeUnit.SECONDS)
            } finally {
                trava.unlock()
            }
        }
        assertTrue(segurando.await(5, TimeUnit.SECONDS))
        try {
            bloco()
        } finally {
            soltar.countDown()
            dona.join(5_000L)
        }
    }

    // ---------- perfil mudado no meio da rodada ----------

    private val base = PerfilSync(
        id = "p1", nome = "Servidor", tipo = PerfilSync.Tipo.SMB,
        host = "srv", share = "rt", caminhoRemoto = "FOTOS",
        usuario = "tablet", dominio = "HOSP", porta = 0, protocolo = "SMB2",
    )

    @Test
    fun `campos de onde o arquivo cai mudam o destino`() {
        assertTrue(m.mesmoDestino(base, base.copy()))
        assertFalse(m.mesmoDestino(base, base.copy(tipo = PerfilSync.Tipo.SFTP)))
        assertFalse(m.mesmoDestino(base, base.copy(host = "srv2")))
        assertFalse(m.mesmoDestino(base, base.copy(share = "rt2")))
        assertFalse(m.mesmoDestino(base, base.copy(urlBase = "https://nas/dav/")))
        assertFalse(m.mesmoDestino(base, base.copy(safUri = "content://x")))
        assertFalse(m.mesmoDestino(base, base.copy(caminhoRemoto = "FOTOS_CERTO")))
    }

    @Test
    fun `nome credencial porta e estado nao mudam o destino`() {
        assertTrue(m.mesmoDestino(base, base.copy(
            nome = "Outro nome", ativo = false, usuario = "outro", dominio = "X",
            porta = 4450, protocolo = "SMB3", injecaoDireta = false,
            ultimaSincronizacao = 123L, ultimoErro = "falhou")))
    }

    @Test
    fun `perfil intacto ou so renomeado continua valendo`() {
        assertEquals(MotorSync.Vigencia.VALE, m.vigencia(base, base))
        assertEquals(MotorSync.Vigencia.VALE, m.vigencia(base, base.copy(nome = "Novo")))
    }

    @Test
    fun `destino trocado no meio da rodada para sem marcar`() {
        val v = m.vigencia(base, base.copy(caminhoRemoto = "FOTOS_CERTO"))
        assertEquals(MotorSync.Vigencia.DESTINO_TROCADO, v)
        assertFalse(m.podeMarcar(v))
    }

    @Test
    fun `destino trocado vence desativacao`() {
        assertEquals(MotorSync.Vigencia.DESTINO_TROCADO,
            m.vigencia(base, base.copy(host = "srv2", ativo = false)))
    }

    @Test
    fun `perfil desativado no meio da rodada para mas marca o que chegou`() {
        val v = m.vigencia(base, base.copy(ativo = false))
        assertEquals(MotorSync.Vigencia.DESATIVADO, v)
        assertTrue(m.podeMarcar(v))
    }

    @Test
    fun `perfil que ja comecou desativado segue ate o fim`() {
        val inativo = base.copy(ativo = false)
        assertEquals(MotorSync.Vigencia.VALE, m.vigencia(inativo, inativo))
    }

    @Test
    fun `perfil removido no meio da rodada para sem marcar`() {
        val v = m.vigencia(base, null)
        assertEquals(MotorSync.Vigencia.REMOVIDO, v)
        assertFalse(m.podeMarcar(v))
    }

    // ---------- resumo ----------

    @Test
    fun `varredura em andamento sem pendencia nao e falha`() {
        assertFalse(MotorSync.Resumo("p1", jaEstavam = 10, emAndamento = true).houveFalha)
        assertTrue(MotorSync.Resumo("p1", jaEstavam = 7, pendentes = 3, emAndamento = true).houveFalha)
        assertTrue(MotorSync.Resumo("p1", pendentes = 5, interrompido = true).houveFalha)
    }
}
