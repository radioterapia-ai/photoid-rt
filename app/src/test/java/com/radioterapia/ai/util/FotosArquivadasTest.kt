package com.radioterapia.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Arquivamento de fotos e a classificação que decide o que pode ser movido.
 *
 * Os casos de [FotosArquivadas.listar] e [FotosArquivadas.arquivar] protegem o
 * PAR foto + "_ORIGINAL": o original não pode aparecer como foto a escolher, e
 * não pode ficar para trás, órfão, quando a foto sai da ficha.
 *
 * Os de [FotosArquivadas.tipoConfiavel] protegem a ficha impressa: o nome
 * legado começa pelo nome do paciente, e uma classificação que olhasse o nome
 * inteiro moveria para ARQUIVADAS as fotos erradas.
 *
 * Os do fim cobrem os outros nomes que dividem a pasta do paciente com as
 * fotos: os ocultos de Time-Out e observação, com o número da simulação em
 * ASCII e a leitura do que foi gravado com algarismos de outra escrita.
 *
 * Só disco temporário e nomes; nada de `Context`.
 */
class FotosArquivadasTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun criar(pasta: File, nome: String, conteudo: String = "x"): File =
        File(pasta, nome).apply {
            parentFile?.mkdirs()
            writeText(conteudo)
        }

    private fun carimbo(arquivada: File): String =
        Regex("_ARQ([0-9]+)\\.[A-Za-z]+$").find(arquivada.name)?.groupValues?.get(1)
            ?: throw AssertionError("sem carimbo _ARQ: ${arquivada.name}")

    // ---------- listar ----------

    /**
     * O par original não pode entrar na grade, em nenhuma caixa: converter só
     * um lado da comparação para maiúsculas faria o filtro nunca casar.
     */
    @Test
    fun `listar deixa o original de fora em qualquer caixa`() {
        val base = tmp.newFolder("PACIENTE")
        val arq = FotosArquivadas.pasta(base)
        criar(arq, "A_ARQ1.jpg")
        criar(arq, "A_ARQ1_ORIGINAL.jpg")
        criar(arq, "b_arq2_original.jpg")
        criar(arq, "C_ARQ3.JPG")
        criar(arq, "D_ARQ4.jpeg")
        criar(arq, "E_ARQ5.pdf")

        val nomes = FotosArquivadas.listar(base).map { it.name }.toSet()

        assertEquals(setOf("A_ARQ1.jpg", "C_ARQ3.JPG", "D_ARQ4.jpeg"), nomes)
    }

    @Test
    fun `listar ignora subpasta e devolve vazio sem ARQUIVADAS`() {
        val base = tmp.newFolder("SEM_ARQUIVADAS")
        assertTrue(FotosArquivadas.listar(base).isEmpty())
        assertFalse(FotosArquivadas.temAlguma(base))

        File(FotosArquivadas.pasta(base), "SUBPASTA.jpg").mkdirs()
        assertTrue(FotosArquivadas.listar(base).isEmpty())
    }

    // ---------- arquivar ----------

    @Test
    fun `arquivar leva foto do esquema novo e o par com o mesmo carimbo`() {
        val base = tmp.newFolder("NOVO")
        val foto = criar(base, "M_S_POS_03_SET_2026_14_22_05_3.jpg", "foto")
        val par = criar(base, "M_S_POS_03_SET_2026_14_22_05_3_ORIGINAL.jpg", "quadro cheio")

        val arquivada = FotosArquivadas.arquivar(foto)

        assertNotNull(arquivada)
        val c = carimbo(arquivada!!)
        assertEquals("M_S_POS_03_SET_2026_14_22_05_3_ARQ$c.jpg", arquivada.name)
        assertEquals(FotosArquivadas.PASTA, arquivada.parentFile?.name)
        assertEquals("foto", arquivada.readText())
        assertFalse(foto.exists())
        assertFalse(par.exists())

        val parArquivado = File(arquivada.parentFile,
            "M_S_POS_03_SET_2026_14_22_05_3_ARQ${c}_ORIGINAL.jpg")
        assertTrue(parArquivado.exists())
        assertEquals("quadro cheio", parArquivado.readText())

        // O par arquivado continua reconhecido como par: não vira foto a escolher.
        assertTrue(NomeArquivo.ehOriginal(parArquivado.name))
        assertEquals(listOf(arquivada.name), FotosArquivadas.listar(base).map { it.name })
    }

    /**
     * O nome legado tem um ponto DENTRO do mês ("SET."): o radical é tudo antes
     * do ÚLTIMO ponto, senão o par seria procurado com o nome cortado.
     */
    @Test
    fun `arquivar leva foto legada e o par com o mesmo carimbo`() {
        val base = tmp.newFolder("LEGADO")
        val foto = criar(base, "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg")
        val par = criar(base, "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1_ORIGINAL.jpg")

        val arquivada = FotosArquivadas.arquivar(foto)

        assertNotNull(arquivada)
        val c = carimbo(arquivada!!)
        assertEquals("MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1_ARQ$c.jpg", arquivada.name)
        assertFalse(par.exists())
        assertTrue(File(arquivada.parentFile,
            "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1_ARQ${c}_ORIGINAL.jpg").exists())
    }

    @Test
    fun `arquivar da sessao leva o par da sessao`() {
        val base = tmp.newFolder("SESSAO")
        val foto = criar(base, "_rosto.jpg")
        criar(base, "_rosto_ORIGINAL.jpg")

        val arquivada = FotosArquivadas.arquivar(foto)!!
        val c = carimbo(arquivada)

        assertEquals("_rosto_ARQ$c.jpg", arquivada.name)
        assertTrue(File(arquivada.parentFile, "_rosto_ARQ${c}_ORIGINAL.jpg").exists())
        assertFalse(File(base, "_rosto_ORIGINAL.jpg").exists())
    }

    @Test
    fun `arquivar sem par move so a foto e nao inventa original`() {
        val base = tmp.newFolder("SEM_PAR")
        val foto = criar(base, "M_S_ETIQ_03_SET_2026_14_22_05_1.jpg")

        val arquivada = FotosArquivadas.arquivar(foto)!!

        val conteudo = arquivada.parentFile!!.listFiles()!!.map { it.name }
        assertEquals(listOf(arquivada.name), conteudo)
    }

    @Test
    fun `arquivar nao move outra foto da pasta`() {
        val base = tmp.newFolder("VIZINHA")
        val foto = criar(base, "M_S_ROST_03_SET_2026_14_22_05_1.jpg")
        val vizinha = criar(base, "M_S_ROST_03_SET_2026_14_22_05_10.jpg")
        val parVizinha = criar(base, "M_S_ROST_03_SET_2026_14_22_05_10_ORIGINAL.jpg")

        FotosArquivadas.arquivar(foto)

        assertTrue(vizinha.exists())
        assertTrue(parVizinha.exists())
    }

    @Test
    fun `arquivar recusa arquivo inexistente e pasta`() {
        val base = tmp.newFolder("RECUSA")
        assertNull(FotosArquivadas.arquivar(File(base, "nao_existe.jpg")))
        assertNull(FotosArquivadas.arquivar(base))
    }

    // ---------- tipoConfiavel ----------

    @Test
    fun `tipo do esquema novo sai do codigo`() {
        assertEquals(NomeArquivo.Tipo.POSICIONAMENTO, FotosArquivadas.tipoConfiavel(
            "A_F_POS_TRAT_03_SET_2026_14_22_05_1.jpg", "Ana Faceli"))
        assertEquals(NomeArquivo.Tipo.ROSTO, FotosArquivadas.tipoConfiavel(
            "A_F_ROST_03_SET_2026_14_22_05_1.jpg", null))
    }

    @Test
    fun `tipo legado com prefixo do paciente`() {
        assertEquals(NomeArquivo.Tipo.ROSTO, FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg", "Maria Silva"))
        assertEquals(NomeArquivo.Tipo.ETIQUETA, FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_ETIQUETA_03-SET-2026_14-22-05_2.jpg", "Maria Silva"))
        assertEquals(NomeArquivo.Tipo.POSICIONAMENTO, FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_POSICIONAMENTO_TRATAMENTO_NOVASIM1_03-SET.-2026_14-22-05_3.jpg",
            "Maria Silva"))
        assertEquals(NomeArquivo.Tipo.FICHA, FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_FOLHA_SIMULACAO_03-SET.-2026_14-22-05.pdf", "Maria Silva"))
    }

    /**
     * O caso que motivou a função: "FACE" dentro de FACELI e "LABEL" dentro de
     * LABELLE. Classificado pelo nome inteiro, todo posicionamento dessas
     * pacientes seria rosto ou etiqueta, e refazer o rosto arquivaria a ficha.
     */
    @Test
    fun `nome do paciente nao decide o tipo`() {
        val faceli = "ANA_FACELI_POSICIONAMENTO_TRATAMENTO_03-SET.-2026_14-22-05_1.jpg"
        assertEquals(NomeArquivo.Tipo.POSICIONAMENTO,
            FotosArquivadas.tipoConfiavel(faceli, "Ana Faceli"))
        assertEquals(NomeArquivo.Tipo.ROSTO, NomeArquivo.tipo(faceli))

        assertEquals(NomeArquivo.Tipo.ACESSORIOS, FotosArquivadas.tipoConfiavel(
            "MARIA_LABELLE_ACESSORIOS_03-SET.-2026_14-22-05_4.jpg", "Maria Labelle"))
    }

    @Test
    fun `as duas grafias legadas do prefixo`() {
        // Com apóstrofo (finalização) e sem (tratamento e edição de cadastro).
        assertEquals(NomeArquivo.Tipo.ROSTO, FotosArquivadas.tipoConfiavel(
            "MARIA_D'ARC_ROSTO_03-SET.-2026_14-22-05_1.jpg", "Maria D'Arc"))
        assertEquals(NomeArquivo.Tipo.POSICIONAMENTO, FotosArquivadas.tipoConfiavel(
            "MARIA_DARC_POSICIONAMENTO_TRATAMENTO_03-SET.-2026_14-22-05_2.jpg", "Maria D'Arc"))
        // Acento: o arquivo foi gravado sem ele.
        assertEquals(NomeArquivo.Tipo.ETIQUETA, FotosArquivadas.tipoConfiavel(
            "JOAO_CONCEICAO_ETIQUETA_03-SET.-2026_14-22-05_1.jpg", "João Conceição"))
    }

    @Test
    fun `na duvida nao ha tipo`() {
        // Prefixo de outro paciente.
        assertNull(FotosArquivadas.tipoConfiavel(
            "JOANA_ROSTO_03-SET.-2026_14-22-05_1.jpg", "Maria Silva"))
        // Prefixo tem que terminar em "_": ANA não reconhece ANABELA.
        assertNull(FotosArquivadas.tipoConfiavel(
            "ANABELA_ROSTO_03-SET.-2026_14-22-05_1.jpg", "Ana"))
        // Sem paciente, o nome legado não pode ser separado.
        assertNull(FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg", ""))
        assertNull(FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg", null))
        // Arquivo oculto não é foto.
        assertNull(FotosArquivadas.tipoConfiavel(".timeout_sim1.json", "Maria Silva"))
        // Depois do nome, só vale a palavra de tipo que os gravadores escreveram:
        // uma palavra qualquer que CONTENHA "rosto" não decide.
        assertNull(FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_XROSTO_03-SET.-2026_14-22-05_1.jpg", "Maria Silva"))
    }

    @Test
    fun `ficha legada nas duas grafias`() {
        assertEquals(NomeArquivo.Tipo.FICHA, FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_FolhaSimulacao_NOVASIM1_03_set._2026__14_22_05.pdf", "Maria Silva"))
        assertEquals(NomeArquivo.Tipo.IMPRESSO, FotosArquivadas.tipoConfiavel(
            "MARIA_SILVA_DOC_NOVASIM1_03-SET.-2026_14-22-05_1.jpg", "Maria Silva"))
    }

    @Test
    fun `nome interno da sessao nao carrega paciente`() {
        assertEquals(NomeArquivo.Tipo.ROSTO,
            FotosArquivadas.tipoConfiavel("_rosto_ARQ1727960000000.jpg", "Ana Faceli"))
        assertEquals(NomeArquivo.Tipo.ETIQUETA,
            FotosArquivadas.tipoConfiavel("_etiqueta_ARQ1727960000000.jpg", "Maria Labelle"))
    }

    @Test
    fun `tipoProvavel recorre a classificacao geral na duvida`() {
        val semPrefixo = "JOANA_ROSTO_03-SET.-2026_14-22-05_1.jpg"
        assertNull(FotosArquivadas.tipoConfiavel(semPrefixo, "Maria Silva"))
        assertEquals(NomeArquivo.Tipo.ROSTO, FotosArquivadas.tipoProvavel(semPrefixo, "Maria Silva"))
        // Com certeza, as duas concordam.
        val faceli = "ANA_FACELI_POSICIONAMENTO_03-SET.-2026_14-22-05_1.jpg"
        assertEquals(NomeArquivo.Tipo.POSICIONAMENTO, FotosArquivadas.tipoProvavel(faceli, "Ana Faceli"))
    }

    @Test
    fun `resto apos o paciente comeca no separador`() {
        assertEquals("_ROSTO_03-SET.-2026_14-22-05_1.jpg", FotosArquivadas.restoAposPaciente(
            "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg", "maria   silva"))
        assertNull(FotosArquivadas.restoAposPaciente("MARIA_SILVA_", "Maria Silva"))
    }

    // ---------- nomes ocultos da simulação (Time-Out e observação) ----------
    //
    // Moram na mesma pasta do paciente que as fotos. O número da simulação no
    // nome tem de sair em ASCII em qualquer idioma, e o arquivo gravado com
    // algarismos de outra escrita (árabe-índicos, bengalis) continua legível.

    private val arabeUm = "١"
    private val bengaliDois = "২"

    @Test
    fun `nome oculto sai com digitos ASCII`() {
        assertEquals(".timeout_sim12.json", TimeOutStore.nomeOculto(".timeout_sim", 12, ".json"))
        assertEquals(".obs_sim1.txt", TimeOutStore.nomeOculto(".obs_sim", 1, ".txt"))
    }

    @Test
    fun `observacao gravada com algarismos de outra escrita ainda e lida`() {
        val pasta = tmp.newFolder("OCULTOS_LEITURA")
        criar(pasta, ".obs_sim$arabeUm.txt", "obs em arabe")
        criar(pasta, ".obs_sim$bengaliDois.txt", "obs em bengali")

        assertEquals("obs em arabe", ObsStore.ler(pasta, 1))
        assertEquals("obs em bengali", ObsStore.ler(pasta, 2))
        // Outra simulação não é lida por engano.
        assertEquals("", ObsStore.ler(pasta, 3))

        // Com os dois, vale o ASCII.
        criar(pasta, ".obs_sim1.txt", "obs ascii")
        assertEquals("obs ascii", ObsStore.ler(pasta, 1))
    }

    @Test
    fun `gravar migra para ASCII e apaga so a copia da mesma simulacao`() {
        val pasta = tmp.newFolder("OCULTOS_GRAVACAO")
        val local1 = criar(pasta, ".obs_sim$arabeUm.txt", "antiga")
        val local2 = criar(pasta, ".obs_sim$bengaliDois.txt", "outra simulacao")

        assertTrue(ObsStore.gravar(pasta, 1, "nova"))

        assertEquals("nova", File(pasta, ".obs_sim1.txt").readText())
        assertFalse(local1.exists())
        assertTrue(local2.exists())
        assertEquals(listOf(local2), TimeOutStore.variantesLocalizadas(pasta, ".obs_sim", 2, ".txt"))
    }
}
