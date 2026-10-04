package com.radioterapia.ai.transfer

import com.radioterapia.ai.transfer.PacoteConfig.Item
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * As regras da cópia da configuração que vai ao destino.
 *
 * O que erra aqui não aparece na tela: um item com dado de paciente entrando no
 * conjunto, um nome de arquivo com dígito não ASCII, ou uma deduplicação que
 * gera uma cópia por rodada e enche o servidor da instituição.
 */
class CopiaConfiguracaoTest {

    private val c = CopiaConfiguracao
    private val localeOriginal: Locale = Locale.getDefault()
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @After
    fun restaurarLocale() {
        Locale.setDefault(localeOriginal)
    }

    // ---------- o que entra ----------

    @Test
    fun `itens sao exatamente os de configuracao`() {
        // Item novo no enum quebra este teste de propósito: alguém precisa
        // decidir se ele vai ao servidor da instituição.
        assertEquals(
            setOf(Item.CLINICA, Item.LOGOTIPO, Item.MEDICOS, Item.SITIOS,
                Item.RUBRICARIO, Item.PROTOCOLOS, Item.IMPRESSORA, Item.FICHA,
                Item.SINCRONIZACAO, Item.REDE, Item.BASE_CSV),
            c.ITENS)
        assertTrue(c.ITENS.none { it.ehDadoDePaciente })
        assertFalse(Item.PACIENTES in c.ITENS)
        assertFalse(Item.FOTOS in c.ITENS)
        assertFalse(Item.TRATAMENTO in c.ITENS)
    }

    @Test
    fun `a pasta tem o nome combinado`() {
        assertEquals("_CONFIG_PHOTOID_RT", CopiaConfiguracao.NOME_PASTA)
    }

    // ---------- carimbo ----------

    @Test
    fun `carimbo em UTC`() {
        // 2026-10-03T14:25:00Z
        assertEquals("03_OUT_2026_14_25", c.carimbo(1_791_037_500_000L, utc))
    }

    @Test
    fun `carimbo de janeiro e dezembro`() {
        // 2026-01-05T04:03:00Z e 2026-12-31T23:59:00Z
        assertEquals("05_JAN_2026_04_03", c.carimbo(1_767_585_780_000L, utc))
        assertEquals("31_DEZ_2026_23_59", c.carimbo(1_798_761_540_000L, utc))
    }

    @Test
    fun `carimbo so com digitos ASCII em arabe e bengali`() {
        for (loc in listOf(Locale("ar"), Locale("ar", "EG"), Locale("bn"), Locale("bn", "BD"))) {
            Locale.setDefault(loc)
            val s = c.carimbo(1_791_037_500_000L, utc)
            assertEquals("locale $loc", "03_OUT_2026_14_25", s)
            assertTrue("locale $loc: $s", s.matches(Regex("^[0-9A-Z_]+$")))
        }
    }

    // ---------- unidade ----------

    @Test
    fun `unidade sem acento e com sublinhado`() {
        assertEquals("CLINICA_SAO_LUCAS", c.unidadeAscii("Clínica São Lucas"))
        assertEquals("A_B_C_D", c.unidadeAscii("A/B:C*D"))
    }

    @Test
    fun `unidade com letra latina que nao se decompoe`() {
        assertEquals("LODZ", c.unidadeAscii("Łódź"))
    }

    @Test
    fun `unidade vazia ou nao latina vira PHOTOID_RT`() {
        assertEquals("PHOTOID_RT", c.unidadeAscii(""))
        assertEquals("PHOTOID_RT", c.unidadeAscii(null))
        assertEquals("PHOTOID_RT", c.unidadeAscii("   "))
        assertEquals("PHOTOID_RT", c.unidadeAscii("放射治疗中心"))
        assertEquals("PHOTOID_RT", c.unidadeAscii("مركز العلاج الإشعاعي"))
    }

    @Test
    fun `unidade sempre segura para nome de arquivo e curta`() {
        val longo = "Hospital " + "Universitario Regional ".repeat(6) + "Unidade de Radioterapia"
        val entradas = listOf("Clínica São Lucas — Unidade 2", longo, "Zoë 😀 Ünal",
            "A<B>C|D?E\"F", "MARIA.", "Rádio-Oncologia D'Ávila")
        for (e in entradas) {
            val u = c.unidadeAscii(e)
            assertTrue("$e -> $u", u.matches(Regex("^[A-Z0-9_]+$")))
            assertFalse("$e -> $u", u.startsWith("_") || u.endsWith("_"))
        }
        assertTrue(c.unidadeAscii(longo).length <= 48)
    }

    // ---------- nome e impressão digital ----------

    @Test
    fun `nome e hash ida e volta`() {
        val nome = c.nomeArquivo("CLINICA_SAO_LUCAS", "03_OUT_2026_14_25", "A1B2C3D4")
        assertEquals("CONFIG_CLINICA_SAO_LUCAS_03_OUT_2026_14_25_A1B2C3D4.zip", nome)
        assertEquals("A1B2C3D4", c.hashDoNome(nome))
    }

    @Test
    fun `hash do nome com trecho parecido com hash na unidade`() {
        val nome = c.nomeArquivo("UNIDADE_DEADBEEF", "03_OUT_2026_14_25", "A1B2C3D4")
        assertEquals("A1B2C3D4", c.hashDoNome(nome))
    }

    @Test
    fun `nome que nao e de copia nao tem hash`() {
        assertNull(c.hashDoNome("rosto.jpg"))
        assertNull(c.hashDoNome("CONFIG_X.zip"))
        assertNull(c.hashDoNome(null))
        assertNull(c.hashDoNome(""))
        assertNull(c.hashDoNome(".CONFIG_X_03_OUT_2026_14_25_A1B2C3D4.zip.123.tmp"))
        // Hexadecimal minúsculo não é o que a impressão digital produz.
        assertNull(c.hashDoNome("CONFIG_X_03_OUT_2026_14_25_a1b2c3d4.zip"))
    }

    @Test
    fun `precisa nova so quando a mais recente difere`() {
        val atual = c.nomeArquivo("U", "03_OUT_2026_14_25", "A1B2C3D4")
        assertTrue(c.precisaNova("A1B2C3D4", null))
        assertFalse(c.precisaNova("A1B2C3D4", atual))
        assertTrue(c.precisaNova("FFFFFFFF", atual))
        assertTrue(c.precisaNova("A1B2C3D4", "rosto.jpg"))
    }

    // ---------- retenção ----------

    @Test
    fun `excedentes sao os mais antigos`() {
        val arquivos = (1..12).map { "C$it" to it * 1_000L }
        assertEquals(setOf("C1", "C2"), c.excedentes(arquivos, 10).toSet())
    }

    @Test
    fun `excedentes empate de data decidido pelo nome`() {
        val arquivos = listOf("B" to 5L, "A" to 5L, "C" to 5L)
        // Decrescente por nome no empate: C, B ficam; A sai.
        assertEquals(listOf("A"), c.excedentes(arquivos, 2))
    }

    @Test
    fun `excedentes vazio quando cabe tudo`() {
        val arquivos = (1..10).map { "C$it" to it.toLong() }
        assertTrue(c.excedentes(arquivos, 10).isEmpty())
        assertTrue(c.excedentes(arquivos, 50).isEmpty())
        assertTrue(c.excedentes(emptyList(), 10).isEmpty())
    }

    @Test
    fun `a mais recente nunca e excedente`() {
        val arquivos = (1..5).map { "C$it" to it.toLong() }
        assertFalse("C5" in c.excedentes(arquivos, 0))
        assertFalse("C5" in c.excedentes(arquivos, 1))
        assertEquals(listOf("C4", "C3", "C2", "C1"), c.excedentes(arquivos, 1))
    }
}
