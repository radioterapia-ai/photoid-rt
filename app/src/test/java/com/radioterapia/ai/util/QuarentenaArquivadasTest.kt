package com.radioterapia.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Quarentena das fotos arquivadas presentes em mais de um paciente.
 *
 * A decisão é pura (quem vai e quem fica) e o movimento é testado em pastas
 * temporárias: o que não pode acontecer aqui é apagar foto, nem tirar da pasta
 * a foto que tem um dono só, nem deixar na pasta de um paciente a foto que
 * esteve na de outro.
 */
class QuarentenaArquivadasTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun e(paciente: String, nome: String, tamanho: Long = 100L) =
        QuarentenaArquivadas.Entrada(paciente, paciente, nome, tamanho)

    private fun estado(rascunho: Boolean, inicio: Long = 0L) =
        QuarentenaArquivadas.EstadoSessao(rascunho, inicio)

    private val semRascunho: () -> QuarentenaArquivadas.EstadoSessao = { estado(false) }

    private fun comRascunho(inicio: Long): () -> QuarentenaArquivadas.EstadoSessao =
        { estado(true, inicio) }

    // ---------- decisão ----------

    @Test
    fun `arquivo de um paciente so fica onde esta`() {
        val entradas = listOf(
            e("ANA - 1", "_rosto_ARQ1.jpg"),
            e("ANA - 1", "_etiqueta_ARQ2.jpg"),
            e("BIA - 2", "_rosto_ARQ3.jpg"))
        assertTrue(QuarentenaArquivadas.selecionar(entradas).isEmpty())
    }

    @Test
    fun `arquivo em dois pacientes vai para a quarentena em todas as copias`() {
        val a = e("ANA - 1", "_rosto_ARQ1.jpg")
        val b = e("BIA - 2", "_rosto_ARQ1.jpg")
        val c = e("CAIO - 3", "_rosto_ARQ1.jpg")
        val so = e("ANA - 1", "_etiqueta_ARQ9.jpg")
        val escolhidas = QuarentenaArquivadas.selecionar(listOf(a, b, c, so)).toSet()
        assertEquals(setOf(a, b, c), escolhidas)
    }

    @Test
    fun `mesmo nome com tamanho diferente e outro arquivo`() {
        val entradas = listOf(
            e("ANA - 1", "_rosto_ARQ1.jpg", 100L),
            e("BIA - 2", "_rosto_ARQ1.jpg", 200L))
        assertTrue(QuarentenaArquivadas.selecionar(entradas).isEmpty())
    }

    @Test
    fun `pasta de reirradiacao do formato antigo e o mesmo paciente`() {
        val principal = QuarentenaArquivadas.identidadePaciente("MARIA SILVA - 123")
        val irma = QuarentenaArquivadas.identidadePaciente("MARIA SILVA - 123 NOVA SIMULACAO 1")
        assertEquals(principal, irma)
        val entradas = listOf(
            QuarentenaArquivadas.Entrada(principal, "MARIA SILVA - 123", "_rosto_ARQ1.jpg", 10L),
            QuarentenaArquivadas.Entrada(irma, "MARIA SILVA - 123 NOVA SIMULACAO 1", "_rosto_ARQ1.jpg", 10L))
        assertTrue(QuarentenaArquivadas.selecionar(entradas).isEmpty())
    }

    @Test
    fun `homonimas com prontuarios diferentes sao pacientes diferentes`() {
        assertNotEquals(
            QuarentenaArquivadas.identidadePaciente("MARIA SILVA - 123"),
            QuarentenaArquivadas.identidadePaciente("MARIA SILVA - 456"))
    }

    @Test
    fun `evidencia da quarentena conta no grupo e nunca e selecionada`() {
        val prova = QuarentenaArquivadas.Entrada("ANA - 1", "ANA - 1", "_rosto_ARQ1.jpg", 100L,
            evidencia = true)
        val sobra = e("JOAO - 2", "_rosto_ARQ1.jpg")
        assertEquals(listOf(sobra), QuarentenaArquivadas.selecionar(listOf(prova, sobra)))

        // Prova do mesmo paciente não faz grupo de dois.
        val mesma = e("ANA - 1", "_rosto_ARQ1.jpg")
        assertTrue(QuarentenaArquivadas.selecionar(listOf(prova, mesma)).isEmpty())
    }

    @Test
    fun `carimbo de arquivamento sai do nome, inclusive do par original`() {
        assertEquals(1726000000000L,
            QuarentenaArquivadas.carimboDeArquivamento("_rosto_ARQ1726000000000.jpg"))
        assertEquals(1726000000000L,
            QuarentenaArquivadas.carimboDeArquivamento("_rosto_ARQ1726000000000_ORIGINAL.jpg"))
        assertEquals(2L, QuarentenaArquivadas.carimboDeArquivamento("_rosto_ARQ1_ARQ2.jpg"))
        assertNull(QuarentenaArquivadas.carimboDeArquivamento("_rosto.jpg"))
    }

    @Test
    fun `nome com sufixo de colisao conta tambem pelo nome de origem`() {
        assertEquals(setOf("_rosto_ARQ1_1.jpg", "_rosto_ARQ1.jpg"),
            QuarentenaArquivadas.nomesDeOrigem("_rosto_ARQ1_1.jpg"))
        assertEquals(setOf("_rosto_ARQ1726000000000.jpg"),
            QuarentenaArquivadas.nomesDeOrigem("_rosto_ARQ1726000000000.jpg"))
        assertEquals(setOf("_rosto_ARQ1_ORIGINAL.jpg"),
            QuarentenaArquivadas.nomesDeOrigem("_rosto_ARQ1_ORIGINAL.jpg"))
    }

    @Test
    fun `sem rascunho todo o arquivo da sessao e residuo`() {
        val sessao = listOf("_rosto_ARQ1.jpg" to 10L, "_rosto_ARQ2.jpg" to 20L)
        val c = QuarentenaArquivadas.classificarSessao(sessao, emptyList(), estado(false))
        assertEquals(sessao, c.residuo)
        assertTrue(c.pendentes.isEmpty())
    }

    @Test
    fun `com rascunho e inicio desconhecido so e residuo o que ja esta na pasta de algum paciente`() {
        val sessao = listOf("_rosto_ARQ1.jpg" to 10L, "_rosto_ARQ2.jpg" to 20L)
        val pacientes = listOf(e("ANA - 1", "_rosto_ARQ1.jpg", 10L), e("ANA - 1", "_rosto_ARQ2.jpg", 99L))
        val c = QuarentenaArquivadas.classificarSessao(sessao, pacientes, estado(true, 0L))
        assertEquals(listOf("_rosto_ARQ1.jpg" to 10L), c.residuo)
        assertEquals(listOf("_rosto_ARQ2.jpg" to 20L), c.pendentes)
    }

    @Test
    fun `com rascunho o arquivado antes do inicio e residuo e o arquivado depois segue com ele`() {
        val antigoAna = "_rosto_ARQ1726000000000.jpg" to 10L
        val antigoJoao = "_etiqueta_ARQ1727000000000.jpg" to 20L
        val doRascunho = "_rosto_ARQ1729000000000.jpg" to 30L
        val parDoRascunho = "_rosto_ARQ1729000000000_ORIGINAL.jpg" to 90L
        val semCarimbo = "foto_solta.jpg" to 40L
        val c = QuarentenaArquivadas.classificarSessao(
            listOf(antigoAna, antigoJoao, doRascunho, parDoRascunho, semCarimbo),
            emptyList(), estado(true, 1728000000000L))
        assertEquals(listOf(antigoAna, antigoJoao), c.residuo)
        assertEquals(listOf(semCarimbo), c.pendentes)
    }

    @Test
    fun `rascunho lido em qualquer das duas leituras vale como aberto`() {
        val aberto = estado(true, 5000L)
        val fechado = estado(false)
        assertEquals(aberto, QuarentenaArquivadas.combinar(aberto, fechado))
        assertEquals(aberto, QuarentenaArquivadas.combinar(fechado, aberto))
        assertEquals(fechado, QuarentenaArquivadas.combinar(fechado, fechado))
        val novo = estado(true, 9000L)
        assertEquals(novo, QuarentenaArquivadas.combinar(aberto, novo))
    }

    // ---------- movimento em disco ----------

    private fun arquivada(photos: File, pasta: String, nome: String, conteudo: String): File {
        val dir = File(File(photos, pasta), FotosArquivadas.PASTA).apply { mkdirs() }
        return File(dir, nome).apply { writeText(conteudo) }
    }

    private fun naQuarentena(quarentena: File, pasta: String, nome: String, conteudo: String): File =
        File(File(quarentena, pasta), nome).apply { parentFile!!.mkdirs(); writeText(conteudo) }

    private fun contarArquivos(raiz: File): Int =
        raiz.walkTopDown().count { it.isFile && it.name != QuarentenaArquivadas.NOME_RELATORIO }

    private fun pastaArquivadas(photos: File, pasta: String): File =
        File(File(photos, pasta), FotosArquivadas.PASTA)

    @Test
    fun `move as copias repetidas, deixa a de dono unico e nao apaga nada`() {
        val base = tmp.newFolder("PhotoID_RT")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val sessao = File(tmp.newFolder("sessao_atual"), FotosArquivadas.PASTA).apply { mkdirs() }

        val repetidaAna = arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        val repetidaBia = arquivada(photos, "BIA - 2", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        val unica = arquivada(photos, "BIA - 2", "_etiqueta_ARQ5.jpg", "etiqueta-da-bia")
        val residuo = File(sessao, "_rosto_ARQ1.jpg").apply { writeText("rosto-de-alguem") }
        val antes = contarArquivos(base) + contarArquivos(sessao.parentFile!!)

        val r = QuarentenaArquivadas.executarEm(photos, quarentena, sessao, semRascunho)

        assertEquals(0, r.falhas)
        assertEquals(4, r.relatorio.examinados)
        assertEquals(3, r.relatorio.movidos)
        assertEquals(2, r.relatorio.pastas)
        assertFalse(repetidaAna.exists())
        assertFalse(repetidaBia.exists())
        assertFalse(residuo.exists())
        assertTrue(unica.exists())
        assertTrue(File(File(quarentena, "ANA - 1"), "_rosto_ARQ1.jpg").isFile)
        assertTrue(File(File(quarentena, "BIA - 2"), "_rosto_ARQ1.jpg").isFile)
        assertTrue(File(File(quarentena, QuarentenaArquivadas.PASTA_SESSAO), "_rosto_ARQ1.jpg").isFile)
        assertTrue(File(quarentena, QuarentenaArquivadas.NOME_RELATORIO).isFile)

        val depois = contarArquivos(base) + contarArquivos(sessao.parentFile!!)
        assertEquals("nenhuma foto pode sumir", antes, depois)
    }

    @Test
    fun `relatorio traz contagens e pastas, sem conteudo de foto`() {
        val base = tmp.newFolder("base")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "CONTEUDO-SECRETO")
        arquivada(photos, "BIA - 2", "_rosto_ARQ1.jpg", "CONTEUDO-SECRETO")

        QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)

        val texto = File(quarentena, QuarentenaArquivadas.NOME_RELATORIO).readText()
        assertTrue(texto.contains("ANA - 1"))
        assertTrue(texto.contains("BIA - 2"))
        assertFalse(texto.contains("CONTEUDO-SECRETO"))
    }

    @Test
    fun `segunda execucao nao move mais nada`() {
        val base = tmp.newFolder("base2")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "x")
        arquivada(photos, "BIA - 2", "_rosto_ARQ1.jpg", "x")

        QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)
        val r = QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)

        assertEquals(0, r.relatorio.movidos)
        assertEquals(0, r.falhas)
        assertTrue(r.limpa)
    }

    @Test
    fun `com rascunho aberto o arquivo proprio da sessao fica`() {
        val base = tmp.newFolder("base3")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val sessao = File(tmp.newFolder("sessao3"), FotosArquivadas.PASTA).apply { mkdirs() }
        arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "antigo")
        val residuo = File(sessao, "_rosto_ARQ1.jpg").apply { writeText("antigo") }
        val proprio = File(sessao, "_rosto_ARQ7.jpg").apply { writeText("do-paciente-na-sala") }

        val r = QuarentenaArquivadas.executarEm(photos, quarentena, sessao, comRascunho(5L))

        assertEquals(1, r.relatorio.movidos)
        assertEquals(0, r.pendentes)
        assertFalse(residuo.exists())
        assertTrue(proprio.exists())
        // A cópia na pasta da ANA tem um dono só entre as pastas de paciente: fica.
        assertTrue(File(pastaArquivadas(photos, "ANA - 1"), "_rosto_ARQ1.jpg").exists())
    }

    @Test
    fun `nome repetido no destino ganha sufixo e nao sobrescreve`() {
        val base = tmp.newFolder("base4")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        naQuarentena(quarentena, "ANA - 1", "_rosto_ARQ1.jpg", "ja-estava")
        arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "novo")
        arquivada(photos, "BIA - 2", "_rosto_ARQ1.jpg", "novo")

        QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)

        assertEquals("ja-estava", File(File(quarentena, "ANA - 1"), "_rosto_ARQ1.jpg").readText())
        assertEquals("novo", File(File(quarentena, "ANA - 1"), "_rosto_ARQ1_1.jpg").readText())
    }

    // ---------- rascunho aberto: resíduo pelo carimbo ----------

    @Test
    fun `com rascunho aberto e nenhuma pasta de paciente o residuo antigo da sessao sai`() {
        // Pasta de fotos escolhida pelo seletor do sistema: as pastas de
        // paciente não estão na base que a varredura enxerga, e o resíduo
        // antigo da sessão não aparece em pasta nenhuma.
        val base = tmp.newFolder("saf")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val sessao = File(tmp.newFolder("sessao_saf"), FotosArquivadas.PASTA).apply { mkdirs() }
        val rostoAna = File(sessao, "_rosto_ARQ1726000000000.jpg").apply { writeText("rosto-ana") }
        val etiquetaJoao = File(sessao, "_etiqueta_ARQ1727000000000.jpg").apply { writeText("etiqueta-joao") }
        val daMaria = File(sessao, "_rosto_ARQ1729000000000.jpg").apply { writeText("rosto-maria") }
        val parDaMaria = File(sessao, "_rosto_ARQ1729000000000_ORIGINAL.jpg").apply { writeText("quadro-maria") }

        val r = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, sessao, comRascunho(1728000000000L))
        }

        assertTrue(r.limpa)
        assertEquals(2, r.relatorio.movidos)
        assertFalse(rostoAna.exists())
        assertFalse(etiquetaJoao.exists())
        assertTrue(daMaria.exists())
        assertTrue(parDaMaria.exists())
        val sessaoQ = File(quarentena, QuarentenaArquivadas.PASTA_SESSAO)
        assertEquals("rosto-ana", File(sessaoQ, "_rosto_ARQ1726000000000.jpg").readText())
        assertEquals("etiqueta-joao", File(sessaoQ, "_etiqueta_ARQ1727000000000.jpg").readText())
    }

    @Test
    fun `com rascunho sem inicio conhecido nada sai da sessao e a base nao fica examinada`() {
        val base = tmp.newFolder("sem_inicio")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val sessao = File(tmp.newFolder("sessao_sem_inicio"), FotosArquivadas.PASTA).apply { mkdirs() }
        val duvida = File(sessao, "_rosto_ARQ1726000000000.jpg").apply { writeText("de-quem?") }

        val r = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, sessao, comRascunho(0L))
        }

        assertFalse("pendente impede marcar a base", r.limpa)
        assertEquals("pendente nao se resolve repetindo agora", 1, r.passadas)
        assertEquals(0, r.relatorio.movidos)
        assertTrue(duvida.exists())
    }

    // ---------- o que já está na quarentena como prova ----------

    @Test
    fun `retentativa depois de falha parcial ainda enxerga a copia repetida`() {
        val base = tmp.newFolder("parcial")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA).apply { mkdirs() }
        val ana = arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        val joao = arquivada(photos, "JOAO - 2", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        // Um ARQUIVO no lugar da subpasta da ANA impede o movimento dela.
        val bloqueio = File(quarentena, "ANA - 1").apply { writeText("bloqueio") }
        val antes = contarArquivos(base)

        val r1 = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)
        }
        assertFalse("falha impede marcar a base", r1.limpa)
        assertEquals(1, r1.passadas)
        assertTrue(ana.exists())
        assertFalse(joao.exists())

        assertTrue(bloqueio.delete())
        val r2 = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)
        }

        assertTrue(r2.limpa)
        assertFalse("a copia que sobrou tambem sai", ana.exists())
        assertEquals("rosto-de-alguem",
            File(File(quarentena, "ANA - 1"), "_rosto_ARQ1.jpg").readText())
        assertEquals("nenhuma foto pode sumir", antes - 1, contarArquivos(base))
    }

    @Test
    fun `copia na quarentena com sufixo de colisao ainda conta como prova`() {
        val base = tmp.newFolder("sufixo")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        naQuarentena(quarentena, "ANA - 1", "_rosto_ARQ1_1.jpg", "rosto-de-alguem")
        val sobra = arquivada(photos, "JOAO - 2", "_rosto_ARQ1.jpg", "rosto-de-alguem")

        val r = QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)

        assertEquals(1, r.relatorio.movidos)
        assertFalse(sobra.exists())
        assertTrue(File(File(quarentena, "JOAO - 2"), "_rosto_ARQ1.jpg").isFile)
        // A prova não se move.
        assertTrue(File(File(quarentena, "ANA - 1"), "_rosto_ARQ1_1.jpg").isFile)
    }

    @Test
    fun `prova do mesmo paciente ou da sessao nao tira a foto de dono unico`() {
        val base = tmp.newFolder("dono_unico")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        naQuarentena(quarentena, "ANA - 1", "_rosto_ARQ1.jpg", "rosto-da-ana")
        naQuarentena(quarentena, QuarentenaArquivadas.PASTA_SESSAO, "_rosto_ARQ1.jpg", "rosto-da-ana")
        val daAna = arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "rosto-da-ana")

        val r = QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)

        assertEquals(0, r.relatorio.movidos)
        assertTrue(daAna.exists())
    }

    @Test
    fun `fotos importadas depois da base examinada voltam a ser vistas`() {
        val base = tmp.newFolder("importacao")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        arquivada(photos, "JOAO - 2", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        val r1 = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)
        }
        assertTrue(r1.limpa)

        // Importação somando: a cópia do JOAO volta, a da ANA não (o caminho
        // dela também está livre, mas o pacote pode não trazê-la).
        val deVolta = arquivada(photos, "JOAO - 2", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        val r2 = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)
        }

        assertTrue(r2.limpa)
        assertEquals(1, r2.relatorio.movidos)
        assertFalse(deVolta.exists())
        assertEquals("rosto-de-alguem",
            File(File(quarentena, "JOAO - 2"), "_rosto_ARQ1_1.jpg").readText())
    }

    // ---------- concorrência com a captura e a finalização ----------

    @Test
    fun `copia que chega a pasta ja listada e pega na passada de conferencia`() {
        val base = tmp.newFolder("corrida")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val sessao = File(tmp.newFolder("sessao_corrida"), FotosArquivadas.PASTA).apply { mkdirs() }
        arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        arquivada(photos, "CAIO - 3", "_rosto_ARQ1.jpg", "rosto-de-alguem")
        arquivada(photos, "BIA - 2", "_etiqueta_ARQ5.jpg", "etiqueta-da-bia")
        val proprio = File(sessao, "_rosto_ARQ6000.jpg").apply { writeText("do-paciente-na-sala") }

        // A finalização copia para a BIA depois que a pasta dela foi listada:
        // a terceira leitura do rascunho acontece já depois da varredura.
        var leituras = 0
        val estadoComFinalizacao: () -> QuarentenaArquivadas.EstadoSessao = {
            leituras++
            if (leituras == 3) arquivada(photos, "BIA - 2", "_rosto_ARQ1.jpg", "rosto-de-alguem")
            estado(true, 5000L)
        }
        val antes = contarArquivos(base) + contarArquivos(sessao) + 1

        val r = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, sessao, estadoComFinalizacao)
        }

        assertTrue(r.limpa)
        assertEquals(3, r.passadas)
        assertEquals(3, r.relatorio.movidos)
        assertEquals(3, r.relatorio.pastas)
        assertFalse(File(pastaArquivadas(photos, "BIA - 2"), "_rosto_ARQ1.jpg").exists())
        assertTrue(File(File(quarentena, "BIA - 2"), "_rosto_ARQ1.jpg").isFile)
        assertTrue(File(pastaArquivadas(photos, "BIA - 2"), "_etiqueta_ARQ5.jpg").exists())
        assertTrue(proprio.exists())
        assertEquals("nenhuma foto pode sumir", antes, contarArquivos(base) + contarArquivos(sessao))
    }

    @Test
    fun `rascunho aberto durante a varredura protege o que foi arquivado nela`() {
        val base = tmp.newFolder("durante")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val sessao = File(tmp.newFolder("sessao_durante"), FotosArquivadas.PASTA).apply { mkdirs() }
        val antigo = File(sessao, "_rosto_ARQ1000.jpg").apply { writeText("sobra") }
        val novo = File(sessao, "_rosto_ARQ9000.jpg")

        // Sem rascunho no começo. Logo depois da primeira listagem o técnico
        // abre a captura (início 8000) e refaz o rosto, que é arquivado.
        var leituras = 0
        val estadoMudando: () -> QuarentenaArquivadas.EstadoSessao = {
            leituras++
            if (leituras == 2) novo.writeText("rosto-refeito-na-sala")
            if (leituras <= 2) estado(false) else estado(true, 8000L)
        }

        val r = QuarentenaArquivadas.executarEm(photos, quarentena, sessao, estadoMudando)

        assertEquals(1, r.relatorio.movidos)
        assertFalse(antigo.exists())
        assertTrue("o rosto arquivado durante a varredura fica", novo.exists())
        assertEquals(0, r.pendentes)
    }

    @Test
    fun `leitura sem rascunho entre duas com rascunho nao leva a foto do paciente na sala`() {
        val base = tmp.newFolder("leitura_quebrada")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val sessao = File(tmp.newFolder("sessao_quebrada"), FotosArquivadas.PASTA).apply { mkdirs() }
        val antigo = File(sessao, "_rosto_ARQ1000.jpg").apply { writeText("sobra") }
        val proprio = File(sessao, "_rosto_ARQ6000.jpg").apply { writeText("do-paciente-na-sala") }

        // A segunda leitura cai no meio da gravação dos metadados e não vê o rascunho.
        var leituras = 0
        val estadoComFalha: () -> QuarentenaArquivadas.EstadoSessao = {
            leituras++
            if (leituras == 2) estado(false) else estado(true, 5000L)
        }

        val r = QuarentenaArquivadas.executarEm(photos, quarentena, sessao, estadoComFalha)

        assertEquals(1, r.relatorio.movidos)
        assertFalse(antigo.exists())
        assertTrue(proprio.exists())
    }

    @Test
    fun `passada que move pede conferencia e so a limpa encerra`() {
        val base = tmp.newFolder("conferencia")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        arquivada(photos, "ANA - 1", "_rosto_ARQ1.jpg", "x")
        arquivada(photos, "BIA - 2", "_rosto_ARQ1.jpg", "x")

        val r = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, null, semRascunho)
        }

        assertTrue(r.limpa)
        assertEquals(2, r.passadas)
        assertEquals(2, r.relatorio.movidos)
        assertEquals(2, r.relatorio.pastas)
        assertEquals(2, r.relatorio.examinados)
    }

    @Test
    fun `sem passada limpa dentro do limite a base nao e marcada`() {
        var n = 0
        val r = QuarentenaArquivadas.executarAteLimpar(maxPassadas = 3) {
            n++
            QuarentenaArquivadas.Execucao(QuarentenaArquivadas.Relatorio(1, 1, 1), falhas = 0)
        }
        assertFalse(r.limpa)
        assertEquals(3, n)
        assertEquals(3, r.relatorio.movidos)
    }

    // ---------- o resíduo sai da sessão antes de qualquer cópia ----------

    @Test
    fun `residuo da sessao passa pelo preparo e chega a quarentena`() {
        val base = tmp.newFolder("preparo_ok")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val interno = tmp.newFolder("interno_ok")
        val sessao = File(File(interno, "sessao_atual"), FotosArquivadas.PASTA).apply { mkdirs() }
        val preparo = File(interno, QuarentenaArquivadas.PASTA_PREPARO)
        File(sessao, "_rosto_ARQ1.jpg").writeText("rosto-ana")
        File(sessao, "_etiqueta_ARQ2.jpg").writeText("etiqueta-joao")

        val r = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, sessao, semRascunho, preparo)
        }

        assertTrue(r.limpa)
        assertEquals(2, r.relatorio.movidos)
        assertEquals(0, contarArquivos(sessao))
        assertEquals(0, contarArquivos(preparo))
        val sessaoQ = File(quarentena, QuarentenaArquivadas.PASTA_SESSAO)
        assertEquals("rosto-ana", File(sessaoQ, "_rosto_ARQ1.jpg").readText())
        assertEquals("etiqueta-joao", File(sessaoQ, "_etiqueta_ARQ2.jpg").readText())
    }

    @Test
    fun `copia para a quarentena que falha deixa a sessao vazia e o residuo no preparo`() {
        // Uma finalização ou um descarte durante a cópia lenta não pode mais
        // alcançar o resíduo: ele já saiu da sessão antes da primeira cópia.
        val base = tmp.newFolder("preparo_falha")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA).apply { mkdirs() }
        val interno = tmp.newFolder("interno_falha")
        val sessao = File(File(interno, "sessao_atual"), FotosArquivadas.PASTA).apply { mkdirs() }
        val preparo = File(interno, QuarentenaArquivadas.PASTA_PREPARO)
        File(sessao, "_rosto_ARQ1.jpg").writeText("rosto-ana")
        File(sessao, "_etiqueta_ARQ2.jpg").writeText("etiqueta-joao")
        // Um ARQUIVO no lugar da subpasta da sessão na quarentena impede a cópia.
        val bloqueio = File(quarentena, QuarentenaArquivadas.PASTA_SESSAO).apply { writeText("bloqueio") }

        val r1 = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, sessao, semRascunho, preparo)
        }

        assertFalse("falha impede marcar a base", r1.limpa)
        assertEquals(1, r1.passadas)
        assertEquals("nada do residuo fica na sessao", 0, contarArquivos(sessao))
        assertEquals("nada se perde: tudo esta no preparo", 2, contarArquivos(preparo))

        // Desbloqueado, a execução seguinte leva o que sobrou no preparo, sem
        // nada novo na sessão.
        assertTrue(bloqueio.delete())
        val r2 = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, sessao, semRascunho, preparo)
        }

        assertTrue(r2.limpa)
        assertEquals(2, r2.relatorio.movidos)
        assertEquals(2, r2.relatorio.examinados)
        assertEquals(0, contarArquivos(preparo))
        val sessaoQ = File(quarentena, QuarentenaArquivadas.PASTA_SESSAO)
        assertEquals("rosto-ana", File(sessaoQ, "_rosto_ARQ1.jpg").readText())
        assertEquals("etiqueta-joao", File(sessaoQ, "_etiqueta_ARQ2.jpg").readText())
    }

    @Test
    fun `com rascunho aberto o arquivo proprio da sessao nao passa pelo preparo`() {
        val base = tmp.newFolder("preparo_rascunho")
        val photos = File(base, "PHOTOS")
        val quarentena = File(base, QuarentenaArquivadas.NOME_PASTA)
        val interno = tmp.newFolder("interno_rascunho")
        val sessao = File(File(interno, "sessao_atual"), FotosArquivadas.PASTA).apply { mkdirs() }
        val preparo = File(interno, QuarentenaArquivadas.PASTA_PREPARO)
        val antigo = File(sessao, "_rosto_ARQ1726000000000.jpg").apply { writeText("sobra") }
        val proprio = File(sessao, "_rosto_ARQ1729000000000.jpg").apply { writeText("do-paciente-na-sala") }

        val r = QuarentenaArquivadas.executarAteLimpar {
            QuarentenaArquivadas.executarEm(photos, quarentena, sessao,
                comRascunho(1728000000000L), preparo)
        }

        assertTrue(r.limpa)
        assertFalse(antigo.exists())
        assertTrue(proprio.exists())
        assertEquals(0, contarArquivos(preparo))
        assertTrue(File(File(quarentena, QuarentenaArquivadas.PASTA_SESSAO),
            "_rosto_ARQ1726000000000.jpg").isFile)
    }

    // ---------- mover: a cópia entre volumes ----------

    private val renomeacaoEntreVolumes: (File, File) -> Boolean = { _, _ -> false }

    @Test
    fun `entre volumes copia confere e so entao remove a origem`() {
        val origem = File(tmp.newFolder("mv_ok"), "_rosto_ARQ1.jpg").apply { writeText("rosto") }
        val destinoDir = File(tmp.root, "mv_ok_destino")

        assertTrue(QuarentenaArquivadas.mover(origem, destinoDir, renomear = renomeacaoEntreVolumes))

        assertFalse(origem.exists())
        assertEquals("rosto", File(destinoDir, "_rosto_ARQ1.jpg").readText())
    }

    @Test
    fun `origem removida durante a copia nao leva junto a copia completa`() {
        val origem = File(tmp.newFolder("mv_sumiu"), "_rosto_ARQ1.jpg").apply { writeText("rosto-unico") }
        val destinoDir = File(tmp.root, "mv_sumiu_destino")
        // A limpeza da sessão apaga a origem enquanto a cópia termina pelo
        // descritor já aberto.
        val copiarEApagar: (File, File) -> Unit = { o, d ->
            o.copyTo(d, overwrite = false)
            o.delete()
        }

        val movido = QuarentenaArquivadas.mover(origem, destinoDir,
            renomear = renomeacaoEntreVolumes, copiar = copiarEApagar)

        assertTrue(movido)
        assertFalse(origem.exists())
        assertEquals("a unica copia fica", "rosto-unico",
            File(destinoDir, "_rosto_ARQ1.jpg").readText())
    }

    @Test
    fun `copia incompleta com a origem presente sai e a origem fica`() {
        val origem = File(tmp.newFolder("mv_trunc"), "_rosto_ARQ1.jpg").apply { writeText("rosto-inteiro") }
        val destinoDir = File(tmp.root, "mv_trunc_destino")
        val copiarPelaMetade: (File, File) -> Unit = { _, d -> d.writeText("rosto") }

        val movido = QuarentenaArquivadas.mover(origem, destinoDir,
            renomear = renomeacaoEntreVolumes, copiar = copiarPelaMetade)

        assertFalse(movido)
        assertEquals("rosto-inteiro", origem.readText())
        assertFalse(File(destinoDir, "_rosto_ARQ1.jpg").exists())
    }

    @Test
    fun `origem removida no meio de uma copia incompleta deixa o que restou e conta como falha`() {
        val origem = File(tmp.newFolder("mv_trunc_sumiu"), "_rosto_ARQ1.jpg").apply { writeText("rosto-inteiro") }
        val destinoDir = File(tmp.root, "mv_trunc_sumiu_destino")
        val copiarMetadeEApagar: (File, File) -> Unit = { o, d ->
            d.writeText("rosto")
            o.delete()
        }

        val movido = QuarentenaArquivadas.mover(origem, destinoDir,
            renomear = renomeacaoEntreVolumes, copiar = copiarMetadeEApagar)

        assertFalse(movido)
        assertEquals("rosto", File(destinoDir, "_rosto_ARQ1.jpg").readText())
    }

    @Test
    fun `nome que passou a existir no destino antes da copia nao e apagado`() {
        val origem = File(tmp.newFolder("mv_colisao"), "_rosto_ARQ1.jpg").apply { writeText("meu") }
        val destinoDir = File(tmp.root, "mv_colisao_destino")
        val outroChegouAntes: (File, File) -> Unit = { o, d ->
            d.writeText("de-outro")
            o.copyTo(d, overwrite = false)
        }

        val movido = QuarentenaArquivadas.mover(origem, destinoDir,
            renomear = renomeacaoEntreVolumes, copiar = outroChegouAntes)

        assertFalse(movido)
        assertEquals("meu", origem.readText())
        assertEquals("de-outro", File(destinoDir, "_rosto_ARQ1.jpg").readText())
    }

    // ---------- marca e importações ----------

    @Test
    fun `passada sem importacao no caminho pode marcar a base`() {
        val imp = QuarentenaArquivadas.Importacoes()
        val inicio = imp.geracao
        assertTrue(imp.permitemMarcar(inicio))
    }

    @Test
    fun `importacao aberta durante a passada impede a marca`() {
        val imp = QuarentenaArquivadas.Importacoes()
        val inicio = imp.geracao
        imp.abrir()
        assertFalse(imp.permitemMarcar(inicio))
    }

    @Test
    fun `importacao que abriu e fechou durante a passada impede a marca`() {
        val imp = QuarentenaArquivadas.Importacoes()
        val inicio = imp.geracao
        imp.abrir()
        imp.fechar()
        assertFalse(imp.permitemMarcar(inicio))
    }

    @Test
    fun `importacao aberta antes da passada impede a marca aberta ou fechada no meio`() {
        val imp = QuarentenaArquivadas.Importacoes()
        imp.abrir()
        val inicio = imp.geracao
        assertFalse("ainda aberta no fim", imp.permitemMarcar(inicio))
        imp.fechar()
        assertFalse("fechou depois do comeco da passada", imp.permitemMarcar(inicio))

        // A passada que começa depois de tudo fechado pode marcar.
        assertTrue(imp.permitemMarcar(imp.geracao))
        assertEquals(0, imp.abertas)
    }

    @Test
    fun `fechar sem abrir nao deixa contagem negativa`() {
        val imp = QuarentenaArquivadas.Importacoes()
        imp.fechar()
        assertEquals(0, imp.abertas)
        assertTrue(imp.permitemMarcar(imp.geracao))
    }
}
