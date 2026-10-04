package com.radioterapia.ai.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---------- version.json ----------

    private val sha = "a".repeat(64)

    private fun versaoJson(
        formato: Int? = 2,
        campoApk: String = "apkUrl",
        sha256: String = sha
    ): String {
        val o = JSONObject()
        if (formato != null) o.put("formato", formato)
        o.put("versionCode", 30)
        o.put("versionName", "4.5")
        o.put(campoApk, "https://exemplo.invalid/PhotoID_RT_LATEST.apk")
        o.put("sha256", sha256)
        o.put("minSdk", 24)
        o.put("notas", "linha 1")
        return o.toString()
    }

    @Test
    fun `org json real esta no classpath de teste`() {
        // Com o stub do android.jar na frente, JSONObject devolveria valores
        // padrão em silêncio e os testes de version.json passariam vazios.
        assertEquals(1, JSONObject().put("a", 1).getInt("a"))
    }

    @Test
    fun `version json do formato atual e lido inteiro`() {
        val p = g.interpretarVersao(versaoJson())
        assertNotNull(p)
        assertEquals(30, p!!.versionCode)
        assertEquals("4.5", p.versionName)
        assertEquals("https://exemplo.invalid/PhotoID_RT_LATEST.apk", p.urlApk)
        assertEquals(sha, p.sha256)
        assertEquals(24, p.minSdk)
        assertEquals("linha 1", p.notas)
    }

    @Test
    fun `version json do formato antigo nao e versao publicada`() {
        // A trava deliberada: sem "formato" e com o link em "apk", que é o que o
        // leitor antigo exige. Ler "apk" aqui devolveria o convite a quem não
        // deve recebê-lo.
        assertNull(g.interpretarVersao(versaoJson(formato = null, campoApk = "apk")))
        assertNull(g.interpretarVersao(versaoJson(formato = 2, campoApk = "apk")))
    }

    @Test
    fun `formato desconhecido nao e versao publicada`() {
        assertNull(g.interpretarVersao(versaoJson(formato = 3)))
        assertNull(g.interpretarVersao(versaoJson(formato = 1)))
    }

    @Test
    fun `sha256 malformado nao e versao publicada`() {
        assertNull(g.interpretarVersao(versaoJson(sha256 = "a".repeat(63))))
        assertNull(g.interpretarVersao(versaoJson(sha256 = "g".repeat(64))))
    }

    @Test
    fun `sha256 em maiusculas e aceito e normalizado`() {
        assertEquals(sha, g.interpretarVersao(versaoJson(sha256 = "A".repeat(64)))?.sha256)
    }

    @Test
    fun `texto que nao e json nao lanca`() {
        assertNull(g.interpretarVersao("<html>404</html>"))
        assertNull(g.interpretarVersao(""))
    }

    // ---------- assinatura ----------

    @Test
    fun `mesmos certificados sao compativeis`() {
        assertEquals(true, g.mesmaAssinatura(setOf("aa", "bb"), setOf("aa", "bb")))
    }

    @Test
    fun `ordem dos certificados nao importa`() {
        assertEquals(true, g.mesmaAssinatura(linkedSetOf("aa", "bb"), linkedSetOf("bb", "aa")))
    }

    @Test
    fun `certificados diferentes nao sao compativeis`() {
        assertEquals(false, g.mesmaAssinatura(setOf("aa"), setOf("bb")))
    }

    @Test
    fun `lado ilegivel nao decide`() {
        // Falha aberta: não saber nunca vira bloqueio; o instalador decide.
        assertNull(g.mesmaAssinatura(emptySet(), setOf("aa")))
        assertNull(g.mesmaAssinatura(setOf("aa"), emptySet()))
        assertNull(g.mesmaAssinatura(emptySet(), emptySet()))
    }

    // ---------- contagens ----------

    @Test
    fun `contagens vao e voltam pelo texto`() {
        val m = linkedMapOf("pacientes" to 120, "protocolos" to 3,
            "rubricario" to 14, "tratamento" to 40)
        val txt = g.codificarContagens(m)
        assertEquals("pacientes=120;protocolos=3;rubricario=14;tratamento=40", txt)
        assertEquals(m, g.decodificarContagens(txt))
    }

    @Test
    fun `mapa vazio vira texto vazio e volta vazio`() {
        assertEquals("", g.codificarContagens(emptyMap()))
        assertEquals(emptyMap<String, Int>(), g.decodificarContagens(""))
        assertEquals(emptyMap<String, Int>(), g.decodificarContagens(null))
    }

    @Test
    fun `texto malformado vira mapa vazio sem excecao`() {
        assertEquals(emptyMap<String, Int>(), g.decodificarContagens("pacientes"))
        assertEquals(emptyMap<String, Int>(), g.decodificarContagens("pacientes=x"))
        assertEquals(emptyMap<String, Int>(), g.decodificarContagens("=3"))
        assertEquals(emptyMap<String, Int>(), g.decodificarContagens("pacientes=1;;protocolos=2"))
        assertEquals(emptyMap<String, Int>(), g.decodificarContagens("pacientes=-1"))
    }

    @Test
    fun `chave que quebraria o texto nao e gravada`() {
        assertEquals("pacientes=1",
            g.codificarContagens(linkedMapOf("pacientes" to 1, "a=b" to 2, "c;d" to 3, "" to 4)))
    }

    @Test
    fun `nada encolheu quando as contagens sao iguais`() {
        val m = mapOf("pacientes" to 10, "protocolos" to 2)
        assertTrue(g.encolheu(m, m).isEmpty())
    }

    @Test
    fun `so o item que diminuiu e informado`() {
        val r = g.encolheu(
            antes = mapOf("pacientes" to 10, "protocolos" to 2),
            depois = mapOf("pacientes" to 7, "protocolos" to 2))
        assertEquals(mapOf("pacientes" to (10 to 7)), r)
    }

    @Test
    fun `item que aumentou nao e informado`() {
        assertTrue(g.encolheu(mapOf("pacientes" to 10), mapOf("pacientes" to 11)).isEmpty())
    }

    @Test
    fun `item que sumiu depois conta como zero`() {
        // Leitura que falha depois da atualização é o defeito que a conferência
        // existe para mostrar.
        assertEquals(mapOf("rubricario" to (14 to 0)),
            g.encolheu(mapOf("rubricario" to 14), emptyMap()))
    }

    @Test
    fun `item sem base antes nao e comparado`() {
        assertTrue(g.encolheu(emptyMap(), mapOf("pacientes" to 0)).isEmpty())
    }

    // ---------- houve atualização? ----------

    @Test
    fun `versao nova depois do marcador e atualizacao`() {
        assertTrue(g.houveAtualizacao(marcadoDe = 29, instalado = 30))
    }

    @Test
    fun `instalacao cancelada nao e atualizacao`() {
        assertFalse(g.houveAtualizacao(marcadoDe = 29, instalado = 29))
    }

    @Test
    fun `sem marcador nao ha o que conferir`() {
        assertFalse(g.houveAtualizacao(marcadoDe = 0, instalado = 30))
    }

    // ---------- o marcador ainda fala desta instalação? ----------

    private val t0 = 1_800_000_000_000L
    private val hora = 60L * 60 * 1000

    @Test
    fun `marcador recente da versao esperada vale`() {
        assertTrue(g.marcadorVale(alvo = 30, gravadoEm = t0 - hora, instalado = 30, agora = t0))
    }

    @Test
    fun `outra versao que a esperada descarta o marcador`() {
        // Instalador cancelado na 30; depois a 31 chega instalada à mão. As
        // contagens gravadas não são as do momento dessa troca.
        assertFalse(g.marcadorVale(alvo = 30, gravadoEm = t0 - hora, instalado = 31, agora = t0))
    }

    @Test
    fun `alvo nao registrado nao decide`() {
        assertTrue(g.marcadorVale(alvo = 0, gravadoEm = t0 - hora, instalado = 31, agora = t0))
    }

    @Test
    fun `marcador velho nao vale nem com a versao esperada`() {
        val limite = GerenciadorAtualizacao.VALIDADE_MARCADOR_MS
        assertFalse(g.marcadorVale(30, t0 - limite - 1, 30, t0))
        assertTrue(g.marcadorVale(30, t0 - limite, 30, t0))
    }

    @Test
    fun `fim de semana entre o instalador e a primeira abertura ainda vale`() {
        // Sexta 18h «Concluído», segunda 8h primeira abertura: 62 horas.
        assertTrue(g.marcadorVale(30, t0 - 62 * hora, 30, t0))
    }

    @Test
    fun `marcador sem hora de gravacao nao vale`() {
        assertFalse(g.marcadorVale(30, 0L, 30, t0))
        assertFalse(g.marcadorVale(0, 0L, 30, t0))
    }

    @Test
    fun `relogio acertado para tras conta pela distancia`() {
        assertTrue(g.marcadorVale(30, t0 + hora, 30, t0))
        assertFalse(g.marcadorVale(30, t0 + GerenciadorAtualizacao.VALIDADE_MARCADOR_MS + 1, 30, t0))
    }

    // ---------- a versão de partida voltou a rodar ----------

    private val seg = 1000L

    @Test
    fun `versao de partida rodando depois da carencia recaptura`() {
        // Instalador cancelado ou pendente: o app antigo voltou a ser usado.
        assertTrue(g.deveRecapturar(marcadoDe = 30, instalado = 30,
            gravadoEm = t0 - hora, agora = t0))
    }

    @Test
    fun `dentro da carencia ainda e a entrega ao instalador`() {
        val c = GerenciadorAtualizacao.CARENCIA_RECAPTURA_MS
        assertFalse(g.deveRecapturar(30, 30, t0 - c + 1, t0))
        assertTrue(g.deveRecapturar(30, 30, t0 - c, t0))
    }

    @Test
    fun `versao nova confere e nao recaptura`() {
        assertFalse(g.deveRecapturar(marcadoDe = 30, instalado = 31,
            gravadoEm = t0 - hora, agora = t0))
    }

    @Test
    fun `sem marcador nao recaptura`() {
        assertFalse(g.deveRecapturar(0, 30, t0 - hora, t0))
        assertFalse(g.deveRecapturar(30, 30, 0L, t0))
    }

    @Test
    fun `marcador vencido nao recaptura`() {
        val limite = GerenciadorAtualizacao.VALIDADE_MARCADOR_MS
        assertFalse(g.deveRecapturar(30, 30, t0 - limite - 1, t0))
        assertTrue(g.deveRecapturar(30, 30, t0 - limite, t0))
    }

    @Test
    fun `recaptura com relogio para tras conta pela distancia`() {
        assertTrue(g.deveRecapturar(30, 30, t0 + hora, t0))
        assertFalse(g.deveRecapturar(30, 30, t0 + 2 * seg, t0))
    }

    @Test
    fun `recaptura troca o que foi lido e guarda o que falhou`() {
        val gravadas = linkedMapOf("pacientes" to 120, "tratamento" to 40, "rubricario" to 14)
        val atuais = linkedMapOf("pacientes" to 121, "tratamento" to 38, "protocolos" to 3)
        assertEquals(
            mapOf("pacientes" to 121, "tratamento" to 38, "rubricario" to 14, "protocolos" to 3),
            g.contagensRecapturadas(gravadas, atuais))
    }

    @Test
    fun `recaptura da base a marcador gravado sem contagens`() {
        assertEquals(mapOf("tratamento" to 38),
            g.contagensRecapturadas(emptyMap(), mapOf("tratamento" to 38)))
    }

    @Test
    fun `alta dada com o instalador pendente nao vira aviso de perda`() {
        // A cópia registra 40 em tratamento e o instalador da 31 fica pendente.
        // Meia hora depois a 30, ainda em uso, dá duas altas. Seis horas depois
        // a 31 é instalada pelos Recentes e aberta.
        val gravado = t0
        val gravadas = g.decodificarContagens("pacientes=120;tratamento=40")
        val depoisDasAltas = mapOf("pacientes" to 120, "tratamento" to 38)

        assertTrue(g.deveRecapturar(marcadoDe = 30, instalado = 30,
            gravadoEm = gravado, agora = gravado + hora / 2))
        val recapturadas = g.decodificarContagens(
            g.codificarContagens(g.contagensRecapturadas(gravadas, depoisDasAltas)))

        val abertura = gravado + 6 * hora
        assertTrue(g.houveAtualizacao(marcadoDe = 30, instalado = 31))
        assertTrue(g.marcadorVale(alvo = 31, gravadoEm = gravado, instalado = 31, agora = abertura))
        assertTrue(g.encolheu(recapturadas, depoisDasAltas).isEmpty())
        // Sem a recaptura, as mesmas altas virariam perda.
        assertEquals(mapOf("tratamento" to (40 to 38)), g.encolheu(gravadas, depoisDasAltas))
    }

    @Test
    fun `cadastro lido direto conta as chaves menos a de metadados`() {
        val texto = """{"__schema__":{"versao":2},"MARIA DA SILVA | 1001":{},"JOAO | 7":{}}"""
        assertEquals(2, g.pacientesNoTextoDoCadastro(texto))
        assertEquals(0, g.pacientesNoTextoDoCadastro("""{"__schema__":{"versao":2}}"""))
    }

    @Test
    fun `cadastro cortado no meio da gravacao nao vira contagem`() {
        // Arquivo truncado por uma gravação em curso: sem valor, a recaptura
        // fica com o gravado em vez de registrar zero pacientes.
        val inteiro = """{"__schema__":{"versao":2},"MARIA DA SILVA | 1001":{"nascimento":"01/02/1950"}}"""
        assertNull(g.pacientesNoTextoDoCadastro(inteiro.substring(0, inteiro.length - 1)))
        assertNull(g.pacientesNoTextoDoCadastro(""))
        assertNull(g.pacientesNoTextoDoCadastro(null))
        assertEquals(mapOf("pacientes" to 120, "tratamento" to 38),
            g.contagensRecapturadas(mapOf("pacientes" to 120, "tratamento" to 40),
                mapOf("tratamento" to 38)))
    }

    @Test
    fun `nomes do cadastro lido direto sao os do PatientCache`() {
        val fonte = java.io.File("src/main/java/com/radioterapia/ai/patient/PatientCache.kt")
        assertTrue("PatientCache.kt não encontrado em ${fonte.absolutePath}", fonte.isFile)
        val texto = fonte.readText()
        assertTrue(texto.contains("\"${GerenciadorAtualizacao.ARQUIVO_CADASTRO}\""))
        assertTrue(texto.contains("\"${GerenciadorAtualizacao.CHAVE_META_CADASTRO}\""))
    }

    @Test
    fun `recaptura nao esconde perda na versao nova`() {
        val recapturadas = g.contagensRecapturadas(
            mapOf("tratamento" to 40), mapOf("tratamento" to 38))
        assertEquals(mapOf("tratamento" to (38 to 0)),
            g.encolheu(recapturadas, mapOf("tratamento" to 0)))
    }
}
