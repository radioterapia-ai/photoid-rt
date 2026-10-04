package com.radioterapia.ai.protocolo

import com.radioterapia.ai.protocolo.ProtocoloStore.AjustePagina
import com.radioterapia.ai.protocolo.ProtocoloStore.Pagina
import com.radioterapia.ai.protocolo.ProtocoloStore.Posicao
import com.radioterapia.ai.protocolo.ProtocoloStore.Protocolo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regras do modelo de protocolos que não dependem de `Context`: leitura e
 * escrita do JSON, posição e rodapé por página, nome da cópia, ordem com o
 * padrão fixo no topo, contagem de páginas e o tratamento do pacote como dado
 * não confiável (id e nomes de arquivo, exclusão confinada a `protocolos/`).
 *
 * O grupo mais importante é o de compatibilidade. Todo tablet em campo tem um
 * `lista.json` e pacotes exportados sem `nome` nem `pp`; se a leitura desses
 * arquivos mudar um único campo, a etiqueta passa a sair em outro lugar da
 * folha do paciente sem ninguém ter tocado na calibração.
 */
class ProtocoloRegrasTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Com `returnDefaultValues = true`, se o stub do android.jar vencesse o
     * org.json real no classpath, todo método devolveria nulo ou zero e os
     * testes de JSON abaixo passariam a comparar valores padrão. Este falha
     * alto nesse caso.
     */
    @Test
    fun `org json real esta no classpath de teste`() {
        assertEquals(1, JSONObject().put("a", 1).getInt("a"))
        assertEquals("x", JSONArray().put("x").getString(0))
    }

    // ---------------------------------------------------------------- compatibilidade

    /** O `lista.json` exatamente como a versão anterior o gravava. */
    private val listaAntiga = """
        [{"id":"padrao","nome":"Padrao","miniatura":"","rubricario":"padrao","paginas":[]},
         {"id":"p1700000000000","nome":"Mama","miniatura":"mini.png","rubricario":"b17",
          "paginas":[
           {"arquivo":"pag_1.pdf","verso":"verso_2.pdf","etq":true,"ex":12.5,"ey":20,
            "ew":60,"eh":30,"lg":true,"lx":10,"ly":15},
           {"arquivo":"pag_3.pdf","verso":"","etq":false,"ex":10,"ey":10,
            "ew":100,"eh":50,"lg":false,"lx":10,"ly":10}]}]
    """.trimIndent()

    @Test
    fun `lista antiga sem nome nem pp le os mesmos campos de antes`() {
        val lidos = ProtocoloStore.listaDeTexto(listaAntiga)
        assertEquals(listOf("padrao", "p1700000000000"), lidos.map { it.id })
        assertTrue(lidos[0].padrao)
        assertFalse(lidos[1].padrao)
        assertEquals("b17", lidos[1].rubricarioId)
        assertEquals("mini.png", lidos[1].miniatura)

        val p1 = lidos[1].paginas[0]
        assertEquals(Pagina(
            arquivo = "pag_1.pdf", verso = "verso_2.pdf",
            etqAtiva = true, etqXmm = 12.5f, etqYmm = 20f, etqWmm = 60f, etqHmm = 30f,
            logoAtivo = true, logoXmm = 10f, logoYmm = 15f), p1)
        assertEquals("", p1.nome)
        assertTrue(p1.ajustes.isEmpty())

        val p2 = lidos[1].paginas[1]
        assertFalse(p2.etqAtiva)
        assertEquals(100f, p2.etqWmm)
        assertEquals(50f, p2.etqHmm)
        assertEquals("", p2.verso)
    }

    @Test
    fun `pagina antiga sem ajustes usa a base em todas as paginas`() {
        val pg = ProtocoloStore.listaDeTexto(listaAntiga)[1].paginas[0]
        val base = Posicao(true, 12.5f, 20f, 60f, 30f, true, 10f, 15f)
        assertEquals(base, pg.posicaoBase)
        assertEquals(base, pg.posicaoDa(0))
        assertEquals(base, pg.posicaoDa(1))
        assertEquals(base, pg.posicaoDa(5))
        assertFalse(pg.temAjuste(1))
    }

    /** Chave ausente cai no MESMO padrão de antes: a página é igual a Pagina(arquivo). */
    @Test
    fun `pagina com chaves ausentes cai nos padroes de sempre`() {
        val p = ProtocoloStore.deJson(JSONObject(
            """{"id":"p1","nome":"X","paginas":[{"arquivo":"a.pdf"}]}"""))!!
        assertEquals(listOf(Pagina("a.pdf")), p.paginas)
        assertEquals(com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO, p.rubricarioId)
        assertEquals("", p.miniatura)
    }

    /** Pacote exportado pela versão anterior: tem rubricario_nome e nenhum campo novo. */
    @Test
    fun `pacote exportado pela versao anterior le igual ao lista json`() {
        val pacote = JSONArray(listaAntiga).apply {
            getJSONObject(0).put("rubricario_nome", "")
            getJSONObject(1).put("rubricario_nome", "Clinica A")
        }
        val doPacote = (0 until pacote.length()).mapNotNull {
            ProtocoloStore.deJson(pacote.optJSONObject(it))
        }
        assertEquals(ProtocoloStore.listaDeTexto(listaAntiga), doPacote)
    }

    @Test
    fun `regravar a lista antiga nao muda nada`() {
        val lidos = ProtocoloStore.listaDeTexto(listaAntiga)
        val relidos = ProtocoloStore.listaDeTexto(ProtocoloStore.listaParaTexto(lidos))
        assertEquals(lidos, relidos)
    }

    /**
     * Protocolo sem nome de PDF nem ajuste grava só as chaves de antes. É o
     * que mantém o arquivo idêntico para quem não usou nada novo.
     */
    @Test
    fun `protocolo intocado grava so as chaves de antes`() {
        val p = ProtocoloStore.listaDeTexto(listaAntiga)[1]
        val o = ProtocoloStore.protocoloParaJson(p)
        assertEquals(setOf("id", "nome", "miniatura", "rubricario", "paginas"),
            o.keys().asSequence().toSet())
        val pg = o.getJSONArray("paginas").getJSONObject(0)
        assertEquals(setOf("arquivo", "verso", "etq", "ex", "ey", "ew", "eh", "lg", "lx", "ly"),
            pg.keys().asSequence().toSet())
    }

    @Test
    fun `exportacao acrescenta so o nome da equipe`() {
        val p = ProtocoloStore.listaDeTexto(listaAntiga)[1]
        val o = ProtocoloStore.protocoloParaJson(p, rubricarioNome = "Clinica A")
        assertEquals("Clinica A", o.getString("rubricario_nome"))
        assertEquals(setOf("id", "nome", "miniatura", "rubricario", "rubricario_nome", "paginas"),
            o.keys().asSequence().toSet())
    }

    @Test
    fun `sem id ou sem arquivo nao entra`() {
        assertNull(ProtocoloStore.deJson(null))
        assertNull(ProtocoloStore.deJson(JSONObject("""{"nome":"X"}""")))
        val p = ProtocoloStore.deJson(JSONObject(
            """{"id":"p1","paginas":[{"arquivo":""},{"verso":"v.pdf"},"lixo",{"arquivo":"ok.pdf"}]}"""))!!
        assertEquals(listOf("ok.pdf"), p.paginas.map { it.arquivo })
    }

    // ---------------------------------------------------------------- nome do PDF

    @Test
    fun `rotulo do arquivo tira a extensao e os espacos`() {
        assertEquals("Termo", ProtocoloStore.rotuloDeArquivo("Termo.pdf"))
        assertEquals("x", ProtocoloStore.rotuloDeArquivo("x.PDF"))
        assertEquals("a.pdf", ProtocoloStore.rotuloDeArquivo("a.pdf.pdf"))
        assertEquals("", ProtocoloStore.rotuloDeArquivo(null))
        assertEquals("Termo", ProtocoloStore.rotuloDeArquivo("  Termo  "))
        assertEquals("Termo", ProtocoloStore.rotuloDeArquivo("Termo .pdf"))
        assertEquals("Orientacoes", ProtocoloStore.rotuloDeArquivo("Orientacoes"))
    }

    @Test
    fun `rotulo do arquivo tem no maximo 80 caracteres`() {
        val r = ProtocoloStore.rotuloDeArquivo("a".repeat(200) + ".pdf")
        assertEquals(80, r.length)
        assertEquals(ProtocoloStore.NOME_PAGINA_MAX, r.length)
    }

    @Test
    fun `nome do pdf ida e volta no json`() {
        val pg = Pagina("a.pdf", nome = "Termo de consentimento")
        val o = ProtocoloStore.paginaParaJson(pg)
        assertEquals("Termo de consentimento", o.getString("nome"))
        val volta = ProtocoloStore.deJson(JSONObject().put("id", "p1")
            .put("paginas", JSONArray().put(o)))!!.paginas.single()
        assertEquals(pg, volta)
    }

    @Test
    fun `nome vazio nao e gravado`() {
        assertFalse(ProtocoloStore.paginaParaJson(Pagina("a.pdf")).has("nome"))
    }

    /** O que a calibração grava do campo "Nome deste PDF". */
    @Test
    fun `nome digitado vira uma linha de no maximo 80`() {
        assertEquals("Termo de consentimento", ProtocoloStore.nomeDePagina("  Termo de\nconsentimento "))
        assertEquals("Termo de  RT", ProtocoloStore.nomeDePagina("Termo de\r\nRT"))
        assertEquals("", ProtocoloStore.nomeDePagina("   "))
        assertEquals("", ProtocoloStore.nomeDePagina(""))
        assertEquals("Termo.pdf", ProtocoloStore.nomeDePagina("Termo.pdf"))
        assertEquals(ProtocoloStore.NOME_PAGINA_MAX, ProtocoloStore.nomeDePagina("b".repeat(120)).length)
        assertEquals("a".repeat(79), ProtocoloStore.nomeDePagina("a".repeat(79) + "\uD83D\uDE00"))
    }

    // ---------------------------------------------------------------- posição por página

    private val base = Pagina(
        arquivo = "pag_1.pdf", verso = "verso_1.pdf", nome = "Termo",
        etqAtiva = true, etqXmm = 15f, etqYmm = 25f, etqWmm = 60f, etqHmm = 30f,
        logoAtivo = true, logoXmm = 12f, logoYmm = 14f)

    private val outra = Posicao(false, 40f, 200f, 80f, 40f, false, 30f, 260f)

    @Test
    fun `ajuste vale so para a propria pagina`() {
        val pg = base.comPosicao(2, outra)
        assertEquals(outra, pg.posicaoDa(2))
        assertEquals(base.posicaoBase, pg.posicaoDa(1))
        assertEquals(base.posicaoBase, pg.posicaoDa(3))
        assertEquals(base.posicaoBase, pg.posicaoDa(0))
        assertTrue(pg.temAjuste(2))
        assertFalse(pg.temAjuste(1))
    }

    @Test
    fun `pagina zero grava nos campos planos e as outras herdam`() {
        val pg = base.comPosicao(0, outra)
        assertEquals(outra, pg.posicaoBase)
        assertEquals(40f, pg.etqXmm)
        assertEquals(260f, pg.logoYmm)
        assertFalse(pg.etqAtiva)
        assertTrue(pg.ajustes.isEmpty())
        assertEquals(outra, pg.posicaoDa(4))
        assertFalse(pg.temAjuste(0))
    }

    @Test
    fun `sem ajuste volta a seguir a pagina zero`() {
        val pg = base.comPosicao(2, outra).semAjuste(2)
        assertEquals(base.posicaoBase, pg.posicaoDa(2))
        assertFalse(pg.temAjuste(2))
        assertEquals(base, pg)
        assertSame(base, base.semAjuste(3))
    }

    @Test
    fun `trocar o ajuste nao duplica a entrada`() {
        val nova = outra.copy(etqXmm = 99f)
        val pg = base.comPosicao(2, outra).comPosicao(1, outra).comPosicao(2, nova)
        assertEquals(listOf(1, 2), pg.ajustes.map { it.indice })
        assertEquals(nova, pg.posicaoDa(2))
    }

    /**
     * A tela de calibração parte da página guardada. Se o modelo reconstruísse
     * a página em vez de copiá-la, o verso e o nome sumiriam a cada Salvar.
     */
    @Test
    fun `mudar a posicao preserva arquivo verso e nome`() {
        listOf(base.comPosicao(0, outra), base.comPosicao(3, outra),
            base.comPosicao(3, outra).semAjuste(3)).forEach { pg ->
            assertEquals("pag_1.pdf", pg.arquivo)
            assertEquals("verso_1.pdf", pg.verso)
            assertEquals("Termo", pg.nome)
        }
    }

    @Test
    fun `pp parcial herda da base chave a chave`() {
        val p = ProtocoloStore.deJson(JSONObject(
            """{"id":"p1","paginas":[{"arquivo":"a.pdf","ex":30,"lg":true,
               "pp":[{"i":1,"etq":false}]}]}"""))!!
        val pg = p.paginas.single()
        assertEquals(pg.posicaoBase.copy(etqAtiva = false), pg.posicaoDa(1))
        assertEquals(30f, pg.posicaoDa(1).etqXmm)
        assertTrue(pg.posicaoDa(1).logoAtivo)
        assertEquals(pg.posicaoBase, pg.posicaoDa(2))
    }

    @Test
    fun `pp descarta indice zero negativo ausente e repetido`() {
        val p = ProtocoloStore.deJson(JSONObject(
            """{"id":"p1","paginas":[{"arquivo":"a.pdf","pp":[
               {"i":0,"ex":1},{"i":-1,"ex":2},{"ex":3},"lixo",
               {"i":2,"ex":40},{"i":2,"ex":50}]}]}"""))!!
        val pg = p.paginas.single()
        assertEquals(listOf(AjustePagina(2, pg.posicaoBase.copy(etqXmm = 40f))), pg.ajustes)
        assertEquals(pg.posicaoBase, pg.posicaoDa(0))
    }

    @Test
    fun `protocolo com nome verso e dois ajustes ida e volta`() {
        val pg = base.comPosicao(1, outra).comPosicao(3, outra.copy(etqHmm = 12.3f))
        val p = Protocolo("p9", "Mama", false, miniatura = "m.png",
            rubricarioId = "b1", paginas = listOf(pg, Pagina("pag_2.pdf")))
        val texto = ProtocoloStore.protocoloParaJson(p).toString()
        assertEquals(p, ProtocoloStore.deJson(JSONObject(texto)))
        assertEquals(p, ProtocoloStore.deJson(ProtocoloStore.protocoloParaJson(p)))
    }

    @Test
    fun `pp so e gravado quando ha ajuste`() {
        assertFalse(ProtocoloStore.paginaParaJson(base).has("pp"))
        val o = ProtocoloStore.paginaParaJson(base.comPosicao(2, outra))
        val pp = o.getJSONArray("pp")
        assertEquals(1, pp.length())
        assertEquals(2, pp.getJSONObject(0).getInt("i"))
        assertEquals(setOf("i", "etq", "ex", "ey", "ew", "eh", "lg", "lx", "ly"),
            pp.getJSONObject(0).keys().asSequence().toSet())
    }

    @Test
    fun `troca da pagina e no mesmo lugar`() {
        val a = Pagina("a.pdf"); val b = Pagina("b.pdf"); val c = Pagina("c.pdf")
        val nova = b.copy(etqXmm = 50f)
        assertEquals(listOf(a, nova, c), ProtocoloStore.substituirPagina(listOf(a, b, c), nova))
        assertEquals(listOf(a, b, c), ProtocoloStore.substituirPagina(listOf(a, b), c))
    }

    // ---------------------------------------------------------------- rodapé por página

    /** Arquivo e pacote anteriores não têm `rd`: toda página sai com o rodapé. */
    @Test
    fun `rodape nasce ligado e arquivo antigo le ligado`() {
        assertTrue(Posicao().rodapeAtivo)
        assertTrue(Pagina("a.pdf").rodapeAtivo)
        val pg = ProtocoloStore.listaDeTexto(listaAntiga)[1].paginas[0]
        assertTrue(pg.rodapeAtivo)
        assertTrue(pg.posicaoDa(0).rodapeAtivo)
        assertTrue(pg.posicaoDa(3).rodapeAtivo)
    }

    @Test
    fun `rodape ligado nao grava chave`() {
        assertFalse(ProtocoloStore.paginaParaJson(Pagina("a.pdf")).has("rd"))
        val o = ProtocoloStore.paginaParaJson(base.comPosicao(2, outra))
        assertFalse(o.has("rd"))
        assertFalse(o.getJSONArray("pp").getJSONObject(0).has("rd"))
    }

    @Test
    fun `rodape desligado na base vale para as paginas sem ajuste`() {
        val pg = base.comPosicao(0, base.posicaoBase.copy(rodapeAtivo = false))
        assertFalse(pg.rodapeAtivo)
        assertFalse(pg.posicaoDa(0).rodapeAtivo)
        assertFalse(pg.posicaoDa(4).rodapeAtivo)
        assertEquals("verso_1.pdf", pg.verso)
        assertEquals("Termo", pg.nome)
        val o = ProtocoloStore.paginaParaJson(pg)
        assertFalse(o.getBoolean("rd"))
        val volta = ProtocoloStore.deJson(JSONObject().put("id", "p1")
            .put("paginas", JSONArray().put(o)))!!.paginas.single()
        assertEquals(pg, volta)
    }

    /**
     * A chave do ajuste é escrita quando difere da BASE da página, porque é na
     * base que a leitura cai sem ela. Os dois sentidos têm de voltar iguais.
     */
    @Test
    fun `rodape por pagina ida e volta nos dois sentidos`() {
        val desligadaComExcecao = base.comPosicao(0, base.posicaoBase.copy(rodapeAtivo = false))
            .comPosicao(2, outra.copy(rodapeAtivo = true))
        val o1 = ProtocoloStore.paginaParaJson(desligadaComExcecao)
        assertTrue(o1.getJSONArray("pp").getJSONObject(0).getBoolean("rd"))

        val ligadaComExcecao = Pagina("pag_2.pdf").comPosicao(3, outra.copy(rodapeAtivo = false))
        val o2 = ProtocoloStore.paginaParaJson(ligadaComExcecao)
        assertFalse(o2.has("rd"))
        assertFalse(o2.getJSONArray("pp").getJSONObject(0).getBoolean("rd"))

        val p = Protocolo("p9", "Mama", false,
            paginas = listOf(desligadaComExcecao, ligadaComExcecao))
        val relido = ProtocoloStore.deJson(JSONObject(ProtocoloStore.protocoloParaJson(p).toString()))!!
        assertEquals(p, relido)
        assertFalse(relido.paginas[0].posicaoDa(1).rodapeAtivo)
        assertTrue(relido.paginas[0].posicaoDa(2).rodapeAtivo)
        assertTrue(relido.paginas[1].posicaoDa(1).rodapeAtivo)
        assertFalse(relido.paginas[1].posicaoDa(3).rodapeAtivo)
    }

    @Test
    fun `pp sem rd herda o rodape da base`() {
        val p = ProtocoloStore.deJson(JSONObject(
            """{"id":"p1","paginas":[{"arquivo":"a.pdf","rd":false,"pp":[{"i":1,"ex":30}]}]}"""))!!
        val pg = p.paginas.single()
        assertFalse(pg.rodapeAtivo)
        assertFalse(pg.posicaoDa(1).rodapeAtivo)
        assertEquals(30f, pg.posicaoDa(1).etqXmm)
    }

    // ---------------------------------------------------------------- duplicar

    @Test
    fun `nome da copia`() {
        assertEquals("Mama (1)", ProtocoloStore.nomeDuplicado("Mama", emptyList()))
        assertEquals("Mama (2)", ProtocoloStore.nomeDuplicado("Mama", listOf("Mama", "Mama (1)")))
        assertEquals("Mama (2)", ProtocoloStore.nomeDuplicado("Mama (1)", listOf("Mama", "Mama (1)")))
    }

    @Test
    fun `nome da copia ignora maiusculas e espacos`() {
        assertEquals("Mama (2)", ProtocoloStore.nomeDuplicado("Mama", listOf("Mama", "mama (1)")))
        assertEquals("Mama (2)", ProtocoloStore.nomeDuplicado("Mama", listOf(" Mama (1) ")))
        assertEquals("Pelve (1)", ProtocoloStore.nomeDuplicado("  Pelve  ", emptyList()))
    }

    @Test
    fun `nome da copia preenche o buraco`() {
        assertEquals("Mama (1)", ProtocoloStore.nomeDuplicado("Mama", listOf("Mama (2)")))
    }

    @Test
    fun `nome que e so numero vira a base`() {
        assertEquals("(3) (1)", ProtocoloStore.nomeDuplicado("(3)", emptyList()))
    }

    @Test
    fun `id livre pula os ocupados`() {
        assertEquals("p100", ProtocoloStore.idLivre(100L) { false })
        assertEquals("p102", ProtocoloStore.idLivre(100L) { it == "p100" || it == "p101" })
    }

    @Test
    fun `so nome simples e copiado`() {
        assertTrue(ProtocoloStore.nomeSimples("pag_1.pdf"))
        assertFalse(ProtocoloStore.nomeSimples(""))
        assertFalse(ProtocoloStore.nomeSimples(".."))
        assertFalse(ProtocoloStore.nomeSimples("../lista.json"))
        assertFalse(ProtocoloStore.nomeSimples("a\\b.pdf"))
    }

    // ---------------------------------------------------------------- pacote não confiável

    /**
     * O id vira nome de pasta e é apagado com ela. Com `..`, excluir o
     * protocolo levaria o filesDir inteiro; com o nome do lista.json, a lista.
     */
    @Test
    fun `id inseguro vindo de pacote nao entra`() {
        listOf("", " ", ".", "..", "../..", "a/b", "a\\b", "lista.json", "lista.json.tmp",
            "p 1", "p1\u0000", "p1\n", "x".repeat(65)).forEach { id ->
            assertNull("id '$id'", ProtocoloStore.deJson(JSONObject().put("id", id)))
            assertFalse("id '$id'", ProtocoloStore.idValido(id))
        }
        listOf("padrao", "p1700000000000", "p_1-a", "x".repeat(64)).forEach { id ->
            assertEquals(id, ProtocoloStore.deJson(JSONObject().put("id", id))!!.id)
            assertTrue(id, ProtocoloStore.idValido(id))
        }
    }

    @Test
    fun `nome com caractere de controle nao e simples`() {
        assertFalse(ProtocoloStore.nomeSimples("a\u0000.pdf"))
        assertFalse(ProtocoloStore.nomeSimples("a\n.pdf"))
        assertFalse(ProtocoloStore.nomeSimples("."))
        assertTrue(ProtocoloStore.nomeSimples("Termo de consentimento.pdf"))
    }

    /**
     * Página cujo PDF ou verso aponta para fora da pasta sai inteira;
     * miniatura insegura vira "sem miniatura". As páginas boas ficam, na ordem.
     */
    @Test
    fun `pagina de nome inseguro sai e miniatura insegura fica vazia`() {
        val p = ProtocoloStore.deJson(JSONObject("""{"id":"p1","miniatura":"../m.png","paginas":[
            {"arquivo":"../pacientes_cache.json"},
            {"arquivo":"ok.pdf","verso":"../../shared_prefs/x.xml"},
            {"arquivo":"a\\b.pdf"},
            {"arquivo":".."},
            {"arquivo":"bom.pdf","verso":"verso.pdf"},
            {"arquivo":"so_frente.pdf","verso":""}]}"""))!!
        assertEquals(listOf("bom.pdf", "so_frente.pdf"), p.paginas.map { it.arquivo })
        assertEquals("verso.pdf", p.paginas[0].verso)
        assertEquals("", p.paginas[1].verso)
        assertEquals("", p.miniatura)
    }

    @Test
    fun `lista com protocolo de pacote montado le so os seguros`() {
        val texto = """[{"id":"..","nome":"Mama","paginas":[{"arquivo":"pacientes_cache.json"}]},
            {"id":"../..","nome":"X"},{"id":"p1","nome":"Bom","miniatura":"mini.png"}]"""
        val lidos = ProtocoloStore.listaDeTexto(texto)
        assertEquals(listOf("p1"), lidos.map { it.id })
        assertEquals("mini.png", lidos.single().miniatura)
    }

    @Test
    fun `entrada binaria do pacote e so id barra arquivo`() {
        assertTrue(ProtocoloStore.entradaDoPacoteValida("p1/pag_1.pdf"))
        assertTrue(ProtocoloStore.entradaDoPacoteValida("padrao/miniatura.png"))
        listOf("", "lista.json", "lista.json.tmp", "../shared_prefs/x.xml", "p1/../../x",
            "p1/sub/x.pdf", "p1/", "/p1/x.pdf", "p1/..", "../x.pdf", "p1\\..\\x",
            "p 1/x.pdf").forEach {
            assertFalse("entrada '$it'", ProtocoloStore.entradaDoPacoteValida(it))
        }
    }

    /** filesDir de mentira: cadastro ao lado de protocolos/, com lista e um protocolo. */
    private fun arvoreDoApp(nome: String): java.io.File {
        val files = tmp.newFolder(nome)
        java.io.File(files, "pacientes_cache.json").writeText("{}")
        val prot = java.io.File(files, "protocolos").apply { mkdirs() }
        java.io.File(prot, "lista.json").writeText("[]")
        java.io.File(prot, "p1").mkdirs()
        java.io.File(prot, "p1/pag_1.pdf").writeText("%PDF")
        return prot
    }

    private fun assertArvoreIntacta(prot: java.io.File) {
        assertTrue(java.io.File(prot.parentFile, "pacientes_cache.json").exists())
        assertTrue(java.io.File(prot, "lista.json").exists())
        assertTrue(java.io.File(prot, "p1/pag_1.pdf").exists())
    }

    @Test
    fun `resolver dentro aceita so caminho dentro da raiz`() {
        val prot = arvoreDoApp("files_resolver")
        val esperado = java.io.File(java.io.File(prot.canonicalFile, "p1"), "pag_1.pdf")
        assertEquals(esperado, ProtocoloStore.resolverDentro(prot, "p1", "pag_1.pdf"))
        assertEquals(java.io.File(prot.canonicalFile, "p2"), ProtocoloStore.resolverDentro(prot, "p2"))
        assertNull(ProtocoloStore.resolverDentro(prot))
        assertNull(ProtocoloStore.resolverDentro(prot, ".."))
        assertNull(ProtocoloStore.resolverDentro(prot, "p1", ".."))
        assertNull(ProtocoloStore.resolverDentro(prot, "p1", "../lista.json"))
        assertNull(ProtocoloStore.resolverDentro(prot, "../pacientes_cache.json"))
        assertNull(ProtocoloStore.resolverDentro(prot, "p1\\..\\.."))
        assertNull(ProtocoloStore.resolverDentro(prot, "p1", "a\u0000.pdf"))
    }

    @Test
    fun `apagar pasta com id inseguro nao apaga nada`() {
        val prot = arvoreDoApp("files_pasta_insegura")
        listOf("..", "../..", ".", "", "lista.json", "p1/..", "p1\\..", "..\\..").forEach {
            assertFalse("id '$it'", ProtocoloStore.apagarPasta(prot, it))
        }
        assertArvoreIntacta(prot)
    }

    @Test
    fun `apagar pasta valida leva so a pasta do protocolo`() {
        val prot = arvoreDoApp("files_pasta_valida")
        java.io.File(prot, "p1/sub").mkdirs()
        java.io.File(prot, "p1/sub/x.txt").writeText("x")
        assertTrue(ProtocoloStore.apagarPasta(prot, "p1"))
        assertFalse(java.io.File(prot, "p1").exists())
        assertTrue(java.io.File(prot, "lista.json").exists())
        assertTrue(java.io.File(prot.parentFile, "pacientes_cache.json").exists())
        assertTrue("pasta que nao existe ja esta apagada", ProtocoloStore.apagarPasta(prot, "p2"))
    }

    @Test
    fun `apagar arquivo com nome inseguro nao apaga nada`() {
        val prot = arvoreDoApp("files_arquivo")
        assertFalse(ProtocoloStore.apagarArquivo(prot, "p1", "../lista.json"))
        assertFalse(ProtocoloStore.apagarArquivo(prot, "p1", "../../pacientes_cache.json"))
        assertFalse(ProtocoloStore.apagarArquivo(prot, "..", "pacientes_cache.json"))
        assertFalse(ProtocoloStore.apagarArquivo(prot, "p1", ".."))
        assertFalse(ProtocoloStore.apagarArquivo(prot, "p1", ""))
        assertFalse(ProtocoloStore.apagarArquivo(prot, "p1/..", "lista.json"))
        assertArvoreIntacta(prot)

        assertTrue(ProtocoloStore.apagarArquivo(prot, "p1", "pag_1.pdf"))
        assertFalse(java.io.File(prot, "p1/pag_1.pdf").exists())
        assertFalse(ProtocoloStore.apagarArquivo(prot, "p1", "pag_1.pdf"))
        assertTrue(java.io.File(prot, "lista.json").exists())
    }

    // ---------------------------------------------------------------- ordem

    @Test
    fun `mover troca com o vizinho`() {
        assertEquals(listOf("b", "a", "c"), ProtocoloStore.moverItem(listOf("a", "b", "c"), 1, -1))
        assertEquals(listOf("a", "c", "b"), ProtocoloStore.moverItem(listOf("a", "b", "c"), 1, 1))
    }

    @Test
    fun `mover na ponta devolve a mesma lista`() {
        val l = listOf("a", "b", "c")
        assertSame(l, ProtocoloStore.moverItem(l, 0, -1))
        assertSame(l, ProtocoloStore.moverItem(l, 2, 1))
        assertSame(l, ProtocoloStore.moverItem(l, -1, 1))
        assertSame(l, ProtocoloStore.moverItem(l, 3, -1))
        assertSame(l, ProtocoloStore.moverItem(l, 1, 0))
    }

    private fun prot(id: String) = Protocolo(id, id.uppercase(), id == ProtocoloStore.ID_PADRAO)
    private val sintetico = Protocolo(ProtocoloStore.ID_PADRAO, "Padrao", true)

    /**
     * O padrão abre a lista venha de onde vier no arquivo, e entra a versão
     * GRAVADA dele (com o nome que o serviço lhe deu), não a sintética.
     */
    @Test
    fun `padrao sempre vem primeiro`() {
        val x = prot("x"); val pd = prot("padrao").copy(nome = "Ficha simples"); val y = prot("y")
        assertEquals(listOf(pd, x, y), ProtocoloStore.ordenarComPadrao(listOf(x, pd, y), sintetico))
        assertEquals(listOf(pd, x, y), ProtocoloStore.ordenarComPadrao(listOf(x, y, pd), sintetico))
        assertEquals(listOf(pd, x, y), ProtocoloStore.ordenarComPadrao(listOf(pd, x, y), sintetico))
    }

    @Test
    fun `padrao ausente entra no topo`() {
        val x = prot("x"); val y = prot("y")
        assertEquals(listOf(sintetico, x, y), ProtocoloStore.ordenarComPadrao(listOf(x, y), sintetico))
    }

    @Test
    fun `id repetido fica com a primeira ocorrencia`() {
        val x1 = prot("x"); val x2 = prot("x").copy(nome = "outro"); val pd = prot("padrao")
        assertEquals(listOf(pd, x1),
            ProtocoloStore.ordenarComPadrao(listOf(x1, pd, x2, pd.copy(nome = "z")), sintetico))
    }

    @Test
    fun `padrao nao se move`() {
        val l = listOf(prot("padrao"), prot("a"), prot("b"))
        assertSame(l, ProtocoloStore.moverProtocolo(l, "padrao", 1))
        assertSame(l, ProtocoloStore.moverProtocolo(l, "padrao", -1))
        assertSame(l, ProtocoloStore.moverProtocolo(l, "padrao", 2))
    }

    @Test
    fun `nenhum protocolo sobe acima do padrao`() {
        val l = listOf(prot("padrao"), prot("a"), prot("b"))
        assertSame(l, ProtocoloStore.moverProtocolo(l, "a", -1))
        assertSame(l, ProtocoloStore.moverProtocolo(l, "b", -2))
        assertSame(l, ProtocoloStore.moverProtocolo(l, "b", 1))
        assertSame(l, ProtocoloStore.moverProtocolo(l, "zz", 1))
        assertSame(l, ProtocoloStore.moverProtocolo(l, "a", 0))
        assertEquals(listOf("padrao", "b", "a"), ProtocoloStore.moverProtocolo(l, "b", -1).map { it.id })
        assertEquals(listOf("padrao", "b", "a"), ProtocoloStore.moverProtocolo(l, "a", 1).map { it.id })
    }

    @Test
    fun `ordem movida sobrevive a gravacao e a leitura`() {
        val lista = listOf(prot("padrao"), prot("a"), prot("b"), prot("c"))
        val movida = ProtocoloStore.moverProtocolo(
            ProtocoloStore.moverProtocolo(lista, "c", -1), "a", 1)
        assertEquals(listOf("padrao", "c", "a", "b"), movida.map { it.id })
        val relida = ProtocoloStore.ordenarComPadrao(
            ProtocoloStore.listaDeTexto(ProtocoloStore.listaParaTexto(movida)), sintetico)
        assertEquals(movida, relida)
    }

    @Test
    fun `ordem das paginas sobrevive a gravacao e a leitura`() {
        val p = Protocolo("p1", "Mama", false,
            paginas = listOf(Pagina("x.pdf"), Pagina("y.pdf"), Pagina("z.pdf", nome = "Z")))
        val movido = p.copy(paginas = ProtocoloStore.moverItem(p.paginas, 2, -1))
        val relido = ProtocoloStore.deJson(JSONObject(ProtocoloStore.protocoloParaJson(movido).toString()))!!
        assertEquals(listOf("x.pdf", "z.pdf", "y.pdf"), relido.paginas.map { it.arquivo })
        assertEquals(movido, relido)
    }

    @Test
    fun `gravacao atomica substitui e nao deixa temporario`() {
        val pasta = tmp.newFolder("protocolos")
        val alvo = java.io.File(pasta, "lista.json")
        ProtocoloStore.gravarAtomico(alvo, "[1]")
        assertEquals("[1]", alvo.readText())
        val texto = ProtocoloStore.listaParaTexto(listOf(prot("padrao"), prot("a")))
        ProtocoloStore.gravarAtomico(alvo, texto)
        assertEquals(texto, alvo.readText())
        assertFalse(java.io.File(pasta, "lista.json.tmp").exists())
        assertEquals(listOf("padrao", "a"), ProtocoloStore.listaDeTexto(alvo.readText()).map { it.id })
    }

    /**
     * Temporário impossível de escrever: o erro sobe e o arquivo bom fica
     * intacto, em vez de ser truncado por uma gravação direta que falharia
     * do mesmo jeito.
     */
    @Test
    fun `falha ao escrever o temporario nao toca no arquivo bom`() {
        val pasta = tmp.newFolder("protocolos_falha")
        val alvo = java.io.File(pasta, "lista.json")
        alvo.writeText("[1]")
        val bloqueio = java.io.File(pasta, "lista.json.tmp").apply { mkdirs() }
        java.io.File(bloqueio, "ocupado").writeText("x")
        var lancou = false
        try {
            ProtocoloStore.gravarAtomico(alvo, "[2]")
        } catch (_: Exception) {
            lancou = true
        }
        assertTrue(lancou)
        assertEquals("[1]", alvo.readText())
    }

    // ---------------------------------------------------------------- páginas impressas

    private fun contadorFalso(paginas: Map<String, Int>): (String) -> Int = { paginas[it] ?: 0 }

    @Test
    fun `total soma as paginas de cada pdf`() {
        assertEquals(3, ProtocoloStore.totalPaginas(listOf(Pagina("a.pdf")),
            contadorFalso(mapOf("a.pdf" to 3))))
    }

    @Test
    fun `verso conta como pagina impressa`() {
        val paginas = listOf(Pagina("a.pdf", verso = "b.pdf"), Pagina("c.pdf"))
        assertEquals(4, ProtocoloStore.totalPaginas(paginas,
            contadorFalso(mapOf("a.pdf" to 2, "b.pdf" to 1, "c.pdf" to 1))))
    }

    @Test
    fun `arquivo ilegivel conta zero`() {
        val paginas = listOf(Pagina("a.pdf"), Pagina("quebrado.pdf"))
        assertEquals(2, ProtocoloStore.totalPaginas(paginas, contadorFalso(mapOf("a.pdf" to 2))))
        assertEquals(2, ProtocoloStore.totalPaginas(paginas) { if (it == "a.pdf") 2 else -1 })
    }

    @Test
    fun `sem paginas o total e zero`() {
        assertEquals(0, ProtocoloStore.totalPaginas(emptyList()) { 99 })
    }
}
