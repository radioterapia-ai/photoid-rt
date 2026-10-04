package com.radioterapia.ai.ui

import com.radioterapia.ai.session.SessionManager
import com.radioterapia.ai.treatment.TreatmentPhotoFetcher
import com.radioterapia.ai.util.FotosArquivadas
import com.radioterapia.ai.util.NomeArquivo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/**
 * Regras da finalização que decidem ONDE se grava e O QUE se apaga: a
 * simulação reaberta vem só da pasta exata, a regravação em modo edição tira
 * da pasta só o que a sessão trouxe, o prontuário e a pasta da edição não
 * escorregam para uma homônima, e as arquivadas que não chegaram à pasta do
 * paciente são retidas, nunca apagadas.
 */
class RegrasFinalizacaoTest {

    @Test
    fun `so a arquivada deste rascunho vai para a pasta do paciente`() {
        val inicio = 1_700_000_000_000L
        assertTrue(RegrasFinalizacao.arquivadaDoRascunho("_rosto_ARQ1700000000500.jpg", inicio))
        assertTrue(RegrasFinalizacao.arquivadaDoRascunho("_rosto_ARQ1700000000500_ORIGINAL.jpg", inicio))
        assertFalse(RegrasFinalizacao.arquivadaDoRascunho("_rosto_ARQ1699999999000.jpg", inicio))
        assertFalse(RegrasFinalizacao.arquivadaDoRascunho("_rosto.jpg", inicio))
        assertTrue(RegrasFinalizacao.arquivadaDoRascunho("_rosto_ARQ1699999999000.jpg", 0L))
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val t0 = 1_790_000_000_000L

    private fun nome(tipo: NomeArquivo.Tipo, sim: Int, instante: Long, n: Int,
                     ext: String = "jpg"): String =
        NomeArquivo.montar("Maria Silva", tipo, sim, instante, n, ext, fuso = utc)

    // ---------- leitura da simulação da pasta ----------

    @Test
    fun `foto da simulacao exclui quadro cheio, PDF, oculto e outra simulacao`() {
        val pos = nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0, 1)
        assertTrue(RegrasFinalizacao.ehFotoDaSimulacao(pos, 1))
        assertFalse(RegrasFinalizacao.ehFotoDaSimulacao(pos, 2))
        assertFalse(RegrasFinalizacao.ehFotoDaSimulacao(NomeArquivo.nomeOriginal(pos), 1))
        assertFalse(RegrasFinalizacao.ehFotoDaSimulacao(nome(NomeArquivo.Tipo.FICHA, 1, t0, 1, "pdf"), 1))
        assertFalse(RegrasFinalizacao.ehFotoDaSimulacao(".oculta_sim1.jpg", 1))
        val reirradiacao = nome(NomeArquivo.Tipo.POSICIONAMENTO, 2, t0, 1)
        assertTrue(RegrasFinalizacao.ehFotoDaSimulacao(reirradiacao, 2))
        assertFalse(RegrasFinalizacao.ehFotoDaSimulacao(reirradiacao, 1))
    }

    @Test
    fun `simulacao da pasta junta so a simulacao pedida, com o nome da pasta exata`() {
        val pasta = tmp.newFolder("PHOTOS", "MARIA SILVA - 123")
        fun arq(n: String) = File(pasta, n).apply { writeText("x") }
        val rosto = arq(nome(NomeArquivo.Tipo.ROSTO, 1, t0, 1))
        val etiqueta = arq(nome(NomeArquivo.Tipo.ETIQUETA, 1, t0, 1))
        val pos1 = arq(nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0, 1))
        val pos2 = arq(nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0 + 1_000, 2))
        val original = arq(NomeArquivo.nomeOriginal(pos1.name))
        val ficha = arq(nome(NomeArquivo.Tipo.FICHA, 1, t0, 1, "pdf"))
        val outraSim = arq(nome(NomeArquivo.Tipo.POSICIONAMENTO, 2, t0, 1))
        val oculto = arq(".obs_sim1.txt")
        val arquivos = listOf(rosto, etiqueta, pos1, pos2, original, ficha, outraSim, oculto)
            .mapIndexed { i, f -> f to (t0 + i) }

        val sim = RegrasFinalizacao.simulacaoDaPasta("Maria Silva", pasta.name, 1, arquivos)

        assertNotNull(sim)
        sim!!
        assertEquals("MARIA SILVA - 123", sim.nomePastaCompleto)
        assertEquals(1, sim.numeroSimulacao)
        assertEquals(TreatmentPhotoFetcher.Origem.LOCAL_TABLET, sim.origem)
        assertEquals(rosto, sim.rosto?.arquivoLocal)
        assertEquals(etiqueta, sim.etiqueta?.arquivoLocal)
        assertEquals(listOf(pos1, pos2), sim.posicionamentos.map { it.arquivoLocal })
        assertEquals(setOf(rosto, etiqueta, pos1, pos2), sim.fotos.map { it.arquivoLocal }.toSet())
    }

    @Test
    fun `pasta sem foto da simulacao pedida nao devolve simulacao`() {
        val pasta = tmp.newFolder("PHOTOS", "MARIA SILVA - 123")
        val outra = File(pasta, nome(NomeArquivo.Tipo.POSICIONAMENTO, 2, t0, 1)).apply { writeText("x") }
        assertNull(RegrasFinalizacao.simulacaoDaPasta("Maria Silva", pasta.name, 1,
            listOf(outra to t0)))
        assertNull(RegrasFinalizacao.simulacaoDaPasta("Maria Silva", pasta.name, 1, emptyList()))
    }

    @Test
    fun `o rosto escolhido e o de instante mais recente informado`() {
        val pasta = tmp.newFolder("PHOTOS", "MARIA SILVA - 123")
        val antigo = File(pasta, nome(NomeArquivo.Tipo.ROSTO, 1, t0, 1)).apply { writeText("a") }
        val novo = File(pasta, nome(NomeArquivo.Tipo.ROSTO, 1, t0, 2)).apply { writeText("b") }
        val sim = RegrasFinalizacao.simulacaoDaPasta("Maria Silva", pasta.name, 1,
            listOf(novo to t0 + 5_000, antigo to t0))
        assertEquals(novo, sim?.rosto?.arquivoLocal)
        assertEquals(t0 + 5_000, sim?.timestampPrincipal)
    }

    // ---------- ficha substituída ----------

    @Test
    fun `ficha substituivel e so a do esquema atual desta simulacao`() {
        val ficha1 = nome(NomeArquivo.Tipo.FICHA, 1, t0, 1, "pdf")
        val ficha2 = nome(NomeArquivo.Tipo.FICHA, 2, t0, 1, "pdf")
        assertTrue(RegrasFinalizacao.fichaSubstituivel(ficha1, 1))
        assertFalse(RegrasFinalizacao.fichaSubstituivel(ficha1, 2))
        assertTrue(RegrasFinalizacao.fichaSubstituivel(ficha2, 2))
        assertFalse(RegrasFinalizacao.fichaSubstituivel(ficha2, 1))
        assertFalse(RegrasFinalizacao.fichaSubstituivel(
            "MARIA_SILVA_FOLHA_SIMULACAO_03-SET.-2026_14-22-05.pdf", 1))
        assertFalse(RegrasFinalizacao.fichaSubstituivel(".ficha_sim1.pdf", 1))
        assertFalse(RegrasFinalizacao.fichaSubstituivel(nome(NomeArquivo.Tipo.ROSTO, 1, t0, 1), 1))
    }

    // ---------- regravação em modo edição ----------

    @Test
    fun `regravacao apaga so o reconstruido e a ficha que a nova substitui`() {
        val reconstruida = nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0, 1)
        val originalReconstruido = NomeArquivo.nomeOriginal(reconstruida)
        val reconstruidaSemPar = nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0, 2)
        val parQueNaoVeio = NomeArquivo.nomeOriginal(reconstruidaSemPar)
        val copiaFalhou = nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0 + 1_000, 3)
        val legadoNaoReconstruido = "ANA_FACELI_POSICIONAMENTO_03-SET.-2026_14-22-05.jpg"
        val semTipo = "IMG_0001.jpg"
        val dicom = nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0, 5, "dcm")
        val fichaVelha = nome(NomeArquivo.Tipo.FICHA, 1, t0, 1, "pdf")
        val fichaLegada = "MARIA_SILVA_FOLHA_SIMULACAO_03-SET.-2026_14-22-05.pdf"
        val outraSimulacao = nome(NomeArquivo.Tipo.POSICIONAMENTO, 2, t0, 1)
        val fichaOutraSimulacao = nome(NomeArquivo.Tipo.FICHA, 2, t0, 1, "pdf")
        val novaFoto = nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0 + 60_000, 1)
        val novaFicha = nome(NomeArquivo.Tipo.FICHA, 1, t0 + 60_000, 1, "pdf")
        val mesmoNomeRegravado = nome(NomeArquivo.Tipo.ROSTO, 1, t0, 1)

        val naPasta = listOf(reconstruida, originalReconstruido, reconstruidaSemPar,
            parQueNaoVeio, copiaFalhou, legadoNaoReconstruido, semTipo, dicom, fichaVelha,
            fichaLegada, outraSimulacao, fichaOutraSimulacao, novaFoto, novaFicha,
            mesmoNomeRegravado, ".obs_sim1.txt", ".timeout_sim1.json")
        val reconstruidos = setOf(reconstruida, originalReconstruido, reconstruidaSemPar,
            outraSimulacao, mesmoNomeRegravado)
        val gravadosAgora = setOf(novaFoto, novaFicha, mesmoNomeRegravado)

        val apagar = RegrasFinalizacao.aApagarNaRegravacao(naPasta, reconstruidos, 1, gravadosAgora)

        assertEquals(setOf(reconstruida, originalReconstruido, reconstruidaSemPar, fichaVelha),
            apagar.toSet())
    }

    @Test
    fun `sem nada reconstruido a regravacao so troca a ficha`() {
        val foto = nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0, 1)
        val fichaVelha = nome(NomeArquivo.Tipo.FICHA, 1, t0, 1, "pdf")
        val novaFicha = nome(NomeArquivo.Tipo.FICHA, 1, t0 + 60_000, 1, "pdf")
        val apagar = RegrasFinalizacao.aApagarNaRegravacao(
            listOf(foto, NomeArquivo.nomeOriginal(foto), fichaVelha, novaFicha),
            emptySet(), 1, setOf(novaFicha))
        assertEquals(listOf(fichaVelha), apagar)
    }

    @Test
    fun `reconstruido conta so na propria pasta`() {
        val pasta = File(tmp.root, "PHOTOS/MARIA SILVA - 123")
        val homonima = File(tmp.root, "PHOTOS/MARIA SILVA - 456")
        val cache = File(tmp.root, "cache/simulacao_da_pasta")
        val n = nome(NomeArquivo.Tipo.POSICIONAMENTO, 1, t0, 1)
        val registrados = setOf(
            File(pasta, n).absolutePath,
            File(homonima, "outra.jpg").absolutePath,
            File(cache, "cache.jpg").absolutePath,
            File(File(pasta, FotosArquivadas.PASTA), "arquivada.jpg").absolutePath)
        assertEquals(setOf(n), RegrasFinalizacao.nomesNaPasta(registrados, pasta.absolutePath))
        assertTrue(RegrasFinalizacao.nomesNaPasta(emptySet(), pasta.absolutePath).isEmpty())
    }

    // ---------- pasta e cadastro da edição ----------

    @Test
    fun `em edicao a pasta e a reaberta, mesmo com prontuario divergente na sessao`() {
        assertEquals("MARIA SILVA - 123",
            RegrasFinalizacao.pastaDaFinalizacao("MARIA SILVA - 123", "MARIA SILVA", "456"))
        assertEquals("MARIA SILVA",
            RegrasFinalizacao.pastaDaFinalizacao("MARIA SILVA", "MARIA SILVA", "456"))
    }

    @Test
    fun `chave antiga de edicao nao vira pasta nova`() {
        assertEquals("MARIA SILVA - 123",
            RegrasFinalizacao.pastaDaFinalizacao("MARIA SILVA POSICIONAMENTO", "MARIA SILVA", "123"))
    }

    @Test
    fun `pasta reaberta de outro paciente nao e usada`() {
        assertEquals("ANA",
            RegrasFinalizacao.pastaDaFinalizacao("ANA MARIA - 9", "ANA", ""))
    }

    @Test
    fun `fora da edicao a pasta vem do nome e do prontuario`() {
        assertEquals("MARIA SILVA - 123",
            RegrasFinalizacao.pastaDaFinalizacao("", "MARIA SILVA", "123"))
        assertEquals("MARIA SILVA",
            RegrasFinalizacao.pastaDaFinalizacao("", "MARIA SILVA", ""))
    }

    @Test
    fun `cadastro sem prontuario so serve se tambem nao tiver prontuario`() {
        assertTrue(RegrasFinalizacao.cadastroServe("123", "123"))
        assertTrue(RegrasFinalizacao.cadastroServe("123", ""))
        assertTrue(RegrasFinalizacao.cadastroServe("", ""))
        assertFalse(RegrasFinalizacao.cadastroServe("", "456"))
    }

    // ---------- impressão no resultado ----------

    @Test
    fun `com IP de impressora a linha testa a rede, sem IP avisa que nao ha impressora`() {
        assertEquals(RegrasFinalizacao.LinhaImpressora.TESTAR_REDE,
            RegrasFinalizacao.linhaImpressora(true))
        assertEquals(RegrasFinalizacao.LinhaImpressora.SEM_IMPRESSORA_DE_REDE,
            RegrasFinalizacao.linhaImpressora(false))
    }

    // ---------- sessão: origem reconstruída ----------

    @Test
    fun `so conta como reconstruido o arquivo direto na pasta em edicao`() {
        val photos = File(tmp.root, "PhotoID_RT/PHOTOS")
        val pasta = File(photos, "MARIA SILVA - 123")
        assertTrue(SessionManager.ehDaPastaEditada(File(pasta, "a.jpg"), "MARIA SILVA - 123"))
        assertFalse(SessionManager.ehDaPastaEditada(
            File(File(photos, "MARIA SILVA - 456"), "a.jpg"), "MARIA SILVA - 123"))
        assertFalse(SessionManager.ehDaPastaEditada(
            File(File(pasta, FotosArquivadas.PASTA), "a.jpg"), "MARIA SILVA - 123"))
        assertFalse(SessionManager.ehDaPastaEditada(
            File(File(tmp.root, "cache/MARIA SILVA - 123"), "a.jpg"), "MARIA SILVA - 123"))
        assertFalse(SessionManager.ehDaPastaEditada(File(pasta, "a.jpg"), ""))
    }

    // ---------- sessão: arquivadas retidas ----------

    @Test
    fun `arquivadas que nao chegaram ao paciente sao retidas inteiras`() {
        val sessao = tmp.newFolder("sessao_atual")
        val retidas = File(tmp.root, "arquivadas_retidas")
        val arq = FotosArquivadas.pasta(sessao).apply { mkdirs() }
        File(arq, "_rosto_ARQ1.jpg").writeText("rosto")
        File(arq, "_rosto_ARQ1_ORIGINAL.jpg").writeText("quadro cheio")

        val destino = SessionManager.reterArquivadasEm(sessao, retidas, "MARIA SILVA - 123", 42L)

        assertNotNull(destino)
        assertEquals(retidas.path, destino!!.parentFile?.path)
        assertEquals("rosto", File(destino, "_rosto_ARQ1.jpg").readText())
        assertEquals("quadro cheio", File(destino, "_rosto_ARQ1_ORIGINAL.jpg").readText())
        assertFalse(FotosArquivadas.pasta(sessao).exists())
    }

    @Test
    fun `sem a pasta de retidas a retencao vai para o lado dela`() {
        val sessao = tmp.newFolder("sessao_atual")
        val retidas = File(tmp.root, "arquivadas_retidas").apply { writeText("ocupa o nome") }
        val arq = FotosArquivadas.pasta(sessao).apply { mkdirs() }
        File(arq, "_etiqueta_ARQ9.jpg").writeText("etiqueta")

        val destino = SessionManager.reterArquivadasEm(sessao, retidas, "MARIA SILVA - 123", 42L)

        assertNotNull(destino)
        assertEquals(tmp.root.path, destino!!.parentFile?.path)
        assertTrue(destino.name.startsWith("arquivadas_retidas_"))
        assertEquals("etiqueta", File(destino, "_etiqueta_ARQ9.jpg").readText())
        assertFalse(FotosArquivadas.pasta(sessao).exists())
    }

    @Test
    fun `sem arquivada na sessao nada e retido`() {
        val sessao = tmp.newFolder("sessao_atual")
        val retidas = File(tmp.root, "arquivadas_retidas")
        assertNull(SessionManager.reterArquivadasEm(sessao, retidas, "MARIA SILVA - 123", 42L))
        FotosArquivadas.pasta(sessao).mkdirs()
        assertNull(SessionManager.reterArquivadasEm(sessao, retidas, "MARIA SILVA - 123", 42L))
    }

    @Test
    fun `retencao repetida nao sobrescreve a anterior e o rotulo nao abre caminho`() {
        val sessao = tmp.newFolder("sessao_atual")
        val retidas = File(tmp.root, "arquivadas_retidas")
        fun arquivar(conteudo: String) {
            File(FotosArquivadas.pasta(sessao).apply { mkdirs() }, "_rosto_ARQ1.jpg")
                .writeText(conteudo)
        }
        arquivar("primeira")
        val a = SessionManager.reterArquivadasEm(sessao, retidas, "../MARIA/SILVA", 7L)
        arquivar("segunda")
        val b = SessionManager.reterArquivadasEm(sessao, retidas, "../MARIA/SILVA", 7L)

        assertNotNull(a)
        assertNotNull(b)
        assertEquals(retidas.path, a!!.parentFile?.path)
        assertEquals(retidas.path, b!!.parentFile?.path)
        assertTrue(a.path != b.path)
        assertEquals("primeira", File(a, "_rosto_ARQ1.jpg").readText())
        assertEquals("segunda", File(b, "_rosto_ARQ1.jpg").readText())
    }
}

/**
 * O cartão de resumo monta cada linha pelo MODELO da string: o texto fixo num
 * tom, o valor em outro. Estes testes guardam a leitura do modelo e o contrato
 * que ela pressupõe nos doze idiomas — cada modelo do cartão com o seu lugar de
 * valor, uma vez só.
 */
class ResumoFormatoTest {

    private fun lit(t: String) = ResumoFormato.Segmento.Literal(t)
    private fun valor(i: Int) = ResumoFormato.Segmento.Valor(i)

    @Test
    fun `rotulo seguido do valor`() {
        assertEquals(listOf(lit("Paciente: "), valor(1)), ResumoFormato.segmentos("Paciente: %1\$s"))
        assertEquals(listOf(lit("• Rosto: "), valor(1)), ResumoFormato.segmentos("• Rosto: %1\$d"))
    }

    @Test
    fun `dois-pontos de largura cheia fica no texto fixo`() {
        assertEquals(listOf(lit("患者："), valor(1)), ResumoFormato.segmentos("患者：%1\$s"))
    }

    @Test
    fun `modelo de duas posicoes mantem a ordem`() {
        assertEquals(listOf(valor(1), lit(": "), valor(2)), ResumoFormato.segmentos("%1\$s: %2\$s"))
        assertEquals(listOf(valor(2), lit(" ← "), valor(1)), ResumoFormato.segmentos("%2\$s ← %1\$s"))
    }

    @Test
    fun `valor antes do rotulo, como pode vir em arabe`() {
        assertEquals(listOf(valor(1), lit(" :الوجه")), ResumoFormato.segmentos("%1\$d :الوجه"))
        assertEquals(listOf(lit("• الوجه: "), valor(1)), ResumoFormato.segmentos("• الوجه: %1\$d"))
    }

    @Test
    fun `modelo sem lugar de valor e um texto fixo so`() {
        assertEquals(listOf(lit("Simulação salva.")), ResumoFormato.segmentos("Simulação salva."))
        assertEquals(emptyList<ResumoFormato.Segmento>(), ResumoFormato.segmentos(""))
    }

    @Test
    fun `marcador sem numero conta por conta propria e o porcento duplo e literal`() {
        assertEquals(listOf(valor(1), lit(" de "), valor(2)), ResumoFormato.segmentos("%s de %d"))
        assertEquals(listOf(valor(3), lit(" / "), valor(1)), ResumoFormato.segmentos("%3\$s / %s"))
        assertEquals(listOf(lit("100% de "), valor(1)), ResumoFormato.segmentos("100%% de %1\$d"))
    }

    @Test
    fun `so a linha de impressos nao traz o marcador de lista`() {
        assertTrue(ResumoFormato.temMarcador("  • Rosto: %1\$d"))
        assertTrue(ResumoFormato.temMarcador("• 顔：%1\$d"))
        assertFalse(ResumoFormato.temMarcador("Impressos escaneados: %1\$d (não entram no PDF)"))
        assertFalse(ResumoFormato.temMarcador(""))
    }

    // ---------- contrato dos modelos nos doze idiomas ----------

    private val res = File("src/main/res")
    private val regexString =
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val regexPastaIdioma = Regex("""^values(-[a-z]{2}(-r[A-Z]{2})?)?$""")

    /** values/ e cada values-XX com strings.xml, descobertos pela pasta. */
    private fun pastasDeIdioma(): List<File> {
        assertTrue("res/ não encontrado (rode a partir do módulo :app)", res.isDirectory)
        val pastas = res.listFiles()
            ?.filter { it.isDirectory && regexPastaIdioma.matches(it.name) }
            ?.filter { File(it, "strings.xml").exists() }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("nenhuma pasta de idioma além do base", pastas.size > 1)
        return pastas
    }

    private fun strings(pasta: File): Map<String, String> =
        regexString.findAll(File(pasta, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun indicesDeValor(modelo: String): List<Int> =
        ResumoFormato.segmentos(modelo)
            .filterIsInstance<ResumoFormato.Segmento.Valor>()
            .map { it.indice }

    @Test
    fun `cada modelo do cartao tem um lugar de valor em todo idioma`() {
        val umValor = listOf("fin_patient", "fin_record", "fin_birth", "fin_total_photos",
            "fin_face", "fin_label", "fin_positioning", "fin_accessories", "fin_documents",
            "fin_new_sim", "fin_fracoes_n")
        val erros = mutableListOf<String>()
        for (pasta in pastasDeIdioma()) {
            val s = strings(pasta)
            for (chave in umValor) {
                val modelo = s[chave] ?: continue
                if (indicesDeValor(modelo) != listOf(1)) erros += "$chave (${pasta.name})"
            }
            val linha = s["wiz_resumo_linha"]
            if (linha != null && indicesDeValor(linha).sorted() != listOf(1, 2)) {
                erros += "wiz_resumo_linha (${pasta.name})"
            }
        }
        assertTrue("Modelos do cartão de resumo fora do contrato: $erros", erros.isEmpty())
    }

    @Test
    fun `os modelos do cartao existem no idioma base`() {
        val base = strings(File(res, "values"))
        val faltando = listOf("fin_patient", "fin_record", "fin_birth", "fin_total_photos",
            "fin_face", "fin_label", "fin_positioning", "fin_accessories", "fin_documents",
            "fin_new_sim", "fin_fracoes_n", "wiz_resumo_linha").filter { it !in base }
        assertTrue("Faltam no values/strings.xml: $faltando", faltando.isEmpty())
    }
}

/**
 * O limite de linhas digitadas da observação (finalização e edição da
 * simulação): recusa só a quebra que passa do teto, e nunca o texto gravado
 * que chega ao campo vazio.
 */
class LimiteObservacaoTest {

    private fun linhas(n: Int) = (1..n).joinToString("\n") { "linha $it" }

    @Test
    fun `ate quatro linhas digitadas ficam`() {
        assertEquals(4, RegrasFinalizacao.MAX_LINHAS_OBS)
        assertTrue(RegrasFinalizacao.observacaoAceita(linhas(4), linhas(3)))
        assertTrue(RegrasFinalizacao.observacaoAceita(linhas(4) + " mais", linhas(4)))
    }

    @Test
    fun `a quinta linha digitada e recusada`() {
        assertFalse(RegrasFinalizacao.observacaoAceita(linhas(4) + "\n", linhas(4)))
        assertFalse(RegrasFinalizacao.observacaoAceita(linhas(5), linhas(4)))
    }

    @Test
    fun `texto gravado com mais linhas continua editavel e encurtavel`() {
        val gravado = linhas(6)
        assertTrue(RegrasFinalizacao.observacaoAceita(gravado + " editado", gravado))
        assertTrue(RegrasFinalizacao.observacaoAceita(linhas(5), gravado))
        assertFalse(RegrasFinalizacao.observacaoAceita(gravado + "\n", gravado))
    }

    @Test
    fun `texto que chega ao campo vazio fica inteiro`() {
        assertTrue(RegrasFinalizacao.observacaoAceita(linhas(7), ""))
    }

    @Test
    fun `a reposicao do texto recusado nao e recusada de novo`() {
        val aceito = linhas(4)
        val recusado = aceito + "\n"
        assertFalse(RegrasFinalizacao.observacaoAceita(recusado, aceito))
        // A chamada reentrante do vigia vê o recusado como "anterior".
        assertTrue(RegrasFinalizacao.observacaoAceita(aceito, recusado))
    }
}
