package com.radioterapia.ai.util

import com.radioterapia.ai.session.SessionManager
import com.radioterapia.ai.util.NomeArquivo.Contexto
import com.radioterapia.ai.util.NomeArquivo.Tipo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Nome de arquivo da pasta do paciente: montagem e leitura dos dois esquemas.
 *
 * O nome é contrato com o que já está em disco. Um erro de LEITURA faz foto
 * existente sumir do carrossel ou entrar na ficha da simulação errada; um erro
 * de MONTAGEM grava, para sempre, um nome que versão nenhuma sabe ler. Os casos
 * legados abaixo são cópias literais do que os gravadores anteriores escreviam.
 *
 * Sem `Context`: só texto, datas com fuso explícito e uma pasta temporária para
 * o par foto e quadro cheio.
 */
class NomeArquivoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val saoPaulo: TimeZone = TimeZone.getTimeZone("America/Sao_Paulo")
    private val berlim: TimeZone = TimeZone.getTimeZone("Europe/Berlin")
    private val novaYork: TimeZone = TimeZone.getTimeZone("America/New_York")

    /** Instante a partir de campos em UTC, sem depender do fuso da máquina de teste. */
    private fun utc(ano: Int, mes: Int, dia: Int, h: Int, m: Int, s: Int): Long {
        val c = GregorianCalendar(utc, Locale.ROOT)
        c.clear()
        c.set(ano, mes - 1, dia, h, m, s)
        return c.timeInMillis
    }

    /** 2026-09-03 14:22:05 UTC, o instante dos exemplos. */
    private val t = utc(2026, 9, 3, 14, 22, 5)

    private inline fun <T> comLocale(l: Locale, bloco: () -> T): T {
        val antes = Locale.getDefault()
        Locale.setDefault(l)
        try {
            return bloco()
        } finally {
            Locale.setDefault(antes)
        }
    }

    private val charsetSeguro = Regex("^[A-Z0-9_]+[.][a-z]+$")

    // =========================================================== iniciais

    @Test
    fun `iniciais do exemplo`() {
        assertEquals("M_A_S", NomeArquivo.iniciais("Maria Aparecida dos Santos"))
    }

    @Test
    fun `cada particula cai`() {
        assertEquals("A_S", NomeArquivo.iniciais("Ana da Silva"))
        assertEquals("A_S", NomeArquivo.iniciais("Ana de Souza"))
        assertEquals("A_C", NomeArquivo.iniciais("Ana do Carmo"))
        assertEquals("A_N", NomeArquivo.iniciais("Ana das Neves"))
        assertEquals("A_R", NomeArquivo.iniciais("Ana dos Reis"))
        assertEquals("P_P", NomeArquivo.iniciais("Pedro e Paulo"))
        assertEquals("G_L", NomeArquivo.iniciais("Gina di Lorenzo"))
        assertEquals("J_P", NomeArquivo.iniciais("Jean du Pont"))
        assertEquals("M_M", NomeArquivo.iniciais("Maria del Mar"))
        assertEquals("R_T", NomeArquivo.iniciais("Rosa la Torre"))
        assertEquals("J_B", NomeArquivo.iniciais("Jean le Blanc"))
        assertEquals("J_L", NomeArquivo.iniciais("Juan y Lopez"))
        assertEquals("J_D", NomeArquivo.iniciais("Jan van Dijk"))
        assertEquals("O_B", NomeArquivo.iniciais("Otto von Bismarck"))
        assertEquals("J_B", NomeArquivo.iniciais("Jan van der Berg"))
        assertEquals("T_H", NomeArquivo.iniciais("Tom den Hartog"))
    }

    @Test
    fun `todas as particulas da tabela caem`() {
        for (p in NomeArquivo.PARTICULAS) {
            assertEquals(p, "A_B", NomeArquivo.iniciais("Ana $p Borges"))
            assertEquals(p, "A_B", NomeArquivo.iniciais("Ana ${p.lowercase()} Borges"))
        }
    }

    @Test
    fun `particula so como palavra inteira`() {
        assertEquals("D_E_D_D", NomeArquivo.iniciais("Dalva Edna Delma Danilo"))
        assertEquals("D_D_V", NomeArquivo.iniciais("Dante Dorival Vanderlei"))
    }

    @Test
    fun `particula em qualquer caixa e com acento no resto do nome`() {
        assertEquals("J_C", NomeArquivo.iniciais("JOÃO DA CONCEIÇÃO"))
        assertEquals("J_C", NomeArquivo.iniciais("joão Da conceição"))
    }

    @Test
    fun `acento cai`() {
        assertEquals("A_U_I_O_E_C_N",
            NomeArquivo.iniciais("Ângela Úrsula Ítalo Ôscar Émerson Çelik Ñandu"))
    }

    @Test
    fun `letras latinas sem decomposicao viram a forma basica`() {
        assertEquals("L_O_D_A_O_H", NomeArquivo.iniciais("Łukasz Øster Đorđe Ægir Œuvre Ħal"))
        assertEquals("L_O_D_A_O_H", NomeArquivo.iniciais("łukasz øster đorđe ægir œuvre ħal"))
        assertEquals("S_T", NomeArquivo.iniciais("Strauß Þór"))
        assertEquals("O", NomeArquivo.iniciais("Ǿlaf"))
        assertEquals("D", NomeArquivo.iniciais("Ðana"))
    }

    @Test
    fun `nome de uma palavra`() {
        assertEquals("M", NomeArquivo.iniciais("Madonna"))
    }

    @Test
    fun `sem nome vira X`() {
        assertEquals("X", NomeArquivo.iniciais(null))
        assertEquals("X", NomeArquivo.iniciais(""))
        assertEquals("X", NomeArquivo.iniciais("   "))
        assertEquals("X", NomeArquivo.iniciais("\t"))
        assertEquals("X", NomeArquivo.iniciais("\n \t "))
    }

    @Test
    fun `so particulas vira X`() {
        assertEquals("X", NomeArquivo.iniciais("da de dos"))
        assertEquals("X", NomeArquivo.iniciais("Y"))
    }

    @Test
    fun `escrita nao latina nao da inicial`() {
        assertEquals("X", NomeArquivo.iniciais("王小明"))
        assertEquals("W", NomeArquivo.iniciais("王小明 Wang"))
        assertEquals("X", NomeArquivo.iniciais("محمد"))
        assertEquals("X", NomeArquivo.iniciais("রহিম"))
        assertEquals("X", NomeArquivo.iniciais("김민준"))
        assertEquals("X", NomeArquivo.iniciais("さくら"))
    }

    @Test
    fun `apostrofo junta e hifen separa`() {
        assertEquals("M_D", NomeArquivo.iniciais("Maria D'Arc"))
        assertEquals("M_D", NomeArquivo.iniciais("Maria D’Arc"))
        assertEquals("O", NomeArquivo.iniciais("O'Neil"))
        assertEquals("A_M_S", NomeArquivo.iniciais("Ana-Maria Silva"))
        assertEquals("J_D", NomeArquivo.iniciais("Joana D'Ávila"))
    }

    @Test
    fun `ruido de OCR`() {
        assertEquals("M_S", NomeArquivo.iniciais("Maria 2 Silva"))
        assertEquals("X", NomeArquivo.iniciais("123 456"))
        assertEquals("J_S_J", NomeArquivo.iniciais("José / Santos: Jr."))
        assertEquals("M_S", NomeArquivo.iniciais("Maria 2ª Silva"))
    }

    @Test
    fun `aparelho em turco nao produz I com ponto`() {
        comLocale(Locale("tr", "TR")) {
            assertEquals("I_I", NomeArquivo.iniciais("ilda inácio"))
            assertEquals("ILDA_INACIO", NomeArquivo.nomeCompletoAscii("ilda inácio"))
        }
    }

    // =========================================================== nome completo

    @Test
    fun `nome completo mantem particulas e numeros`() {
        assertEquals("MARIA_DA_CONCEICAO", NomeArquivo.nomeCompletoAscii("Maria da Conceição"))
        assertEquals("MARIA_2_SILVA", NomeArquivo.nomeCompletoAscii("Maria 2 Silva"))
        assertEquals("JAN_VAN_DER_BERG", NomeArquivo.nomeCompletoAscii("Jan van der Berg"))
    }

    @Test
    fun `nome completo usa a mesma normalizacao das iniciais`() {
        assertEquals("LUKASZ_OSTER", NomeArquivo.nomeCompletoAscii("Łukasz Øster"))
        assertEquals("MARIA_DARC", NomeArquivo.nomeCompletoAscii("Maria D'Arc"))
        assertEquals("ANA_MARIA_SILVA", NomeArquivo.nomeCompletoAscii("Ana-Maria Silva"))
        assertEquals("JOSE_SANTOS_JR", NomeArquivo.nomeCompletoAscii("José / Santos: Jr."))
        assertEquals("WANG", NomeArquivo.nomeCompletoAscii("王小明 Wang"))
    }

    @Test
    fun `nome completo nunca vazio`() {
        assertEquals("X", NomeArquivo.nomeCompletoAscii(null))
        assertEquals("X", NomeArquivo.nomeCompletoAscii(""))
        assertEquals("X", NomeArquivo.nomeCompletoAscii("  \t "))
        assertEquals("X", NomeArquivo.nomeCompletoAscii("王小明"))
        assertEquals("X", NomeArquivo.nomeCompletoAscii("' - '"))
    }

    @Test
    fun `nome completo cortado entre palavras no teto`() {
        val r = NomeArquivo.nomeCompletoAscii(
            "Maria Aparecida Conceição dos Santos Oliveira Pereira")
        assertEquals("MARIA_APARECIDA_CONCEICAO_DOS_SANTOS_OLIVEIRA", r)
        assertTrue(r.length <= 48)
    }

    @Test
    fun `nome completo com exatamente o teto cabe inteiro`() {
        // 9 palavras de 4 letras + 8 separadores + 1 palavra de 3 = 48
        val nome = List(9) { "ABCD" }.joinToString(" ") + " XYZ"
        val r = NomeArquivo.nomeCompletoAscii(nome)
        assertEquals(48, r.length)
        assertTrue(r.endsWith("_XYZ"))
        assertEquals(r, NomeArquivo.nomeCompletoAscii("$nome W"))
    }

    @Test
    fun `primeira palavra maior que o teto e cortada nele`() {
        val r = NomeArquivo.nomeCompletoAscii("A".repeat(60) + " Silva")
        assertEquals("A".repeat(48), r)
    }

    // =========================================================== nome de entrega

    private val codigoNaoLatino = Regex("^X[0-9A-F]{8}$")

    @Test
    fun `entrega de nome com letra latina e o nome completo`() {
        val nomes = listOf("Maria da Conceição", "Łukasz Øster", "Maria 2 Silva",
                           "王小明 Wang", "José / Santos: Jr.", "A".repeat(60) + " Silva", "X")
        for (n in nomes) assertEquals(n, NomeArquivo.nomeCompletoAscii(n), NomeArquivo.nomeEntregaAscii(n))
    }

    @Test
    fun `entrega sem nome continua X`() {
        assertEquals("X", NomeArquivo.nomeEntregaAscii(null))
        assertEquals("X", NomeArquivo.nomeEntregaAscii(""))
        assertEquals("X", NomeArquivo.nomeEntregaAscii("  \t "))
    }

    @Test
    fun `entrega de nome sem letra latina leva codigo do paciente`() {
        val nomes = listOf("王伟", "王芳", "محمد علي", "محمد حسن", "김민준", "Иван Петров",
                           "রহিম উদ্দিন", "123 456", "' - '")
        val codigos = nomes.map { NomeArquivo.nomeEntregaAscii(it) }
        for ((n, c) in nomes.zip(codigos)) assertTrue("$n -> $c", codigoNaoLatino.matches(c))
        // Pacientes diferentes, arquivos diferentes: era o X de todos.
        assertEquals(codigos.size, codigos.toSet().size)
        // A pasta e quem precisa saber que nada latino sobrou continuam com X.
        assertEquals("X", NomeArquivo.nomeCompletoAscii("王伟"))
        assertEquals("X", NomeArquivo.iniciais("王伟"))
    }

    @Test
    fun `codigo do paciente e fixo`() {
        // GUARDA: o mesmo paciente sai com o mesmo código em todo tablet e em
        // toda versão. Mudar este valor muda o nome de entrega já em uso.
        assertEquals("X0C6E5FE8", NomeArquivo.nomeEntregaAscii("王伟"))
        comLocale(Locale("ar")) {
            assertEquals("X0C6E5FE8", NomeArquivo.nomeEntregaAscii("王伟"))
        }
        comLocale(Locale("tr", "TR")) {
            assertEquals(NomeArquivo.nomeEntregaAscii("Иван Петров"),
                         NomeArquivo.nomeEntregaAscii("ИВАН ПЕТРОВ"))
        }
    }

    @Test
    fun `codigo nao muda com a forma de digitar o mesmo nome`() {
        // Espaço ideográfico, espaços sobrando e as duas formas Unicode do hangul.
        assertEquals(NomeArquivo.nomeEntregaAscii("王 伟"), NomeArquivo.nomeEntregaAscii("王　伟"))
        assertEquals(NomeArquivo.nomeEntregaAscii("王 伟"), NomeArquivo.nomeEntregaAscii("  王   伟 "))
        val composto = java.text.Normalizer.normalize("김민준", java.text.Normalizer.Form.NFC)
        val decomposto = java.text.Normalizer.normalize("김민준", java.text.Normalizer.Form.NFD)
        assertNotEquals(composto, decomposto)
        assertEquals(NomeArquivo.nomeEntregaAscii(composto), NomeArquivo.nomeEntregaAscii(decomposto))
        assertEquals(NomeArquivo.nomeEntregaAscii("Иван Петров"), NomeArquivo.nomeEntregaAscii("иван петров"))
    }

    // =========================================================== data

    @Test
    fun `campo de data com e sem segundos`() {
        assertEquals("03_SET_2026_14_22_05", NomeArquivo.campoData(t, utc))
        assertEquals("03_SET_2026_14_22", NomeArquivo.campoData(t, utc, comSegundos = false))
    }

    @Test
    fun `tabela de meses`() {
        assertEquals(listOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN",
                            "JUL", "AGO", "SET", "OUT", "NOV", "DEZ"), NomeArquivo.MESES)
        assertEquals(12, NomeArquivo.MESES.toSet().size)
        assertTrue(NomeArquivo.MESES.all { it.matches(Regex("^[A-Z]{3}$")) })
    }

    // =========================================================== montar

    @Test
    fun `exemplo completo`() {
        assertEquals("M_S_POS_03_SET_2026_14_22_05_3.jpg",
            NomeArquivo.montar("Maria Silva", Tipo.POSICIONAMENTO, 1, t, 3, "jpg",
                Contexto.SIMULACAO, utc))
    }

    @Test
    fun `todo mes sai da tabela fixa`() {
        for (i in 0..11) {
            val nome = NomeArquivo.montar("Maria Silva", Tipo.ROSTO, 1,
                utc(2026, i + 1, 15, 12, 0, 0), 1, "jpg", Contexto.SIMULACAO, utc)
            assertTrue(nome, nome.contains("_15_${NomeArquivo.MESES[i]}_2026_12_00_00_"))
            assertFalse(nome, nome.substringBeforeLast('.').contains('.'))
        }
    }

    @Test
    fun `nome independe do idioma do aparelho`() {
        val esperado = "I_I_S_ACES_NS1_03_SET_2026_14_22_05_2.jpg"
        val locais = listOf(
            Locale("pt", "BR"), Locale.US, Locale("es", "ES"), Locale.FRENCH,
            Locale.GERMAN, Locale.ITALIAN, Locale("pl", "PL"), Locale.SIMPLIFIED_CHINESE,
            Locale.JAPAN, Locale.KOREA, Locale("ar", "EG"), Locale("bn", "BD"),
            Locale("th", "TH", "TH"), Locale("ja", "JP", "JP"), Locale("tr", "TR"),
            Locale("hi", "IN"), Locale("fa", "IR"),
            Locale.forLanguageTag("ar-SA-u-nu-arab"), Locale.forLanguageTag("bn-BD-u-nu-beng"))
        for (l in locais) {
            comLocale(l) {
                val nome = NomeArquivo.montar("ilda Inácio Silva", Tipo.ACESSORIOS, 2, t, 2,
                    "jpg", Contexto.SIMULACAO, utc)
                assertEquals(l.toString(), esperado, nome)
                assertTrue(l.toString(), charsetSeguro.matches(nome))
                assertEquals(l.toString(), "03_SET_2026_14_22_05", NomeArquivo.campoData(t, utc))
            }
        }
    }

    @Test
    fun `zeros a esquerda em dia, hora, minuto e segundo`() {
        val nome = NomeArquivo.montar("Ana", Tipo.ROSTO, 1, utc(2026, 1, 5, 4, 3, 2), 1,
            "jpg", Contexto.SIMULACAO, utc)
        assertTrue(nome, nome.contains("_05_JAN_2026_04_03_02_"))
    }

    @Test
    fun `meia-noite e ultimo segundo do dia`() {
        assertEquals("01_JAN_2026_00_00_00", NomeArquivo.campoData(utc(2026, 1, 1, 0, 0, 0), utc))
        assertEquals("31_DEZ_2026_23_59_59", NomeArquivo.campoData(utc(2026, 12, 31, 23, 59, 59), utc))
        val nome = NomeArquivo.montar("Ana", Tipo.ROSTO, 1, utc(2026, 1, 1, 0, 0, 0), 1,
            "jpg", Contexto.SIMULACAO, utc)
        assertTrue(nome, nome.contains("_00_00_00_"))
    }

    @Test
    fun `fuso do aparelho e respeitado`() {
        val i = utc(2026, 3, 1, 1, 0, 0)
        assertEquals("01_MAR_2026_01_00_00", NomeArquivo.campoData(i, utc))
        assertEquals("28_FEV_2026_22_00_00", NomeArquivo.campoData(i, saoPaulo))
        // Virada de ano no fuso local, ainda dezembro.
        assertEquals("31_DEZ_2026_23_00_00",
            NomeArquivo.campoData(utc(2027, 1, 1, 2, 0, 0), saoPaulo))
        val nome = NomeArquivo.montar("Ana", Tipo.ROSTO, 1, i, 1, "jpg", Contexto.SIMULACAO, saoPaulo)
        assertTrue(nome, nome.contains("_28_FEV_2026_22_"))
    }

    @Test
    fun `inicio do horario de verao pula uma hora do relogio`() {
        assertEquals("29_MAR_2026_01_59_59", NomeArquivo.campoData(utc(2026, 3, 29, 0, 59, 59), berlim))
        assertEquals("29_MAR_2026_03_00_00", NomeArquivo.campoData(utc(2026, 3, 29, 1, 0, 0), berlim))
        assertEquals("08_MAR_2026_01_59_59", NomeArquivo.campoData(utc(2026, 3, 8, 6, 59, 59), novaYork))
        assertEquals("08_MAR_2026_03_00_00", NomeArquivo.campoData(utc(2026, 3, 8, 7, 0, 0), novaYork))
    }

    @Test
    fun `fim do horario de verao repete a hora do relogio`() {
        // O nome leva a hora local de parede: a hora repetida dá o mesmo campo
        // para instantes uma hora distantes. Dentro de um lote a contagem
        // separa; entre lotes, o tipo e o segundo.
        val antes = NomeArquivo.campoData(utc(2026, 10, 25, 0, 30, 0), berlim)
        val depois = NomeArquivo.campoData(utc(2026, 10, 25, 1, 30, 0), berlim)
        assertEquals("25_OUT_2026_02_30_00", antes)
        assertEquals(antes, depois)
    }

    @Test
    fun `reirradiacao vira NS e a primeira simulacao nao tem marca`() {
        fun nome(sim: Int) = NomeArquivo.montar("Maria Silva", Tipo.POSICIONAMENTO, sim, t, 1,
            "jpg", Contexto.SIMULACAO, utc)
        assertTrue(nome(2).contains("_POS_NS1_"))
        assertTrue(nome(3).contains("_POS_NS2_"))
        assertTrue(nome(12).contains("_POS_NS11_"))
        assertFalse(nome(1).contains("_NS"))
        assertEquals(nome(1), nome(0))
        assertEquals(nome(1), nome(-1))
    }

    @Test
    fun `tratamento vira TRAT antes de NS`() {
        assertTrue(NomeArquivo.montar("Maria Silva", Tipo.POSICIONAMENTO, 1, t, 1, "jpg",
            Contexto.TRATAMENTO, utc).contains("_POS_TRAT_03_"))
        assertEquals("M_S_POS_TRAT_NS1_03_SET_2026_14_22_05_5.jpg",
            NomeArquivo.montar("Maria Silva", Tipo.POSICIONAMENTO, 2, t, 5, "jpg",
                Contexto.TRATAMENTO, utc))
        for (tipo in Tipo.values()) {
            if (tipo == Tipo.FICHA) continue
            assertTrue(tipo.name, NomeArquivo.montar("Ana", tipo, 1, t, 1, "jpg",
                Contexto.TRATAMENTO, utc).contains("_${tipo.tag}_TRAT_"))
        }
    }

    @Test
    fun `ficha nunca leva TRAT`() {
        assertEquals("M_S_FSIM_03_SET_2026_14_22_05_1.pdf",
            NomeArquivo.montar("Maria Silva", Tipo.FICHA, 1, t, 1, "pdf", Contexto.TRATAMENTO, utc))
        assertEquals("M_S_FSIM_NS1_03_SET_2026_14_22_05_1.pdf",
            NomeArquivo.montar("Maria Silva", Tipo.FICHA, 2, t, 1, "pdf", Contexto.TRATAMENTO, utc))
    }

    @Test
    fun `extensao normalizada`() {
        fun ext(e: String) = NomeArquivo.montar("Ana", Tipo.ROSTO, 1, t, 1, e,
            Contexto.SIMULACAO, utc).substringAfterLast('.')
        assertEquals("jpg", ext("JPG"))
        assertEquals("pdf", ext(".pdf"))
        assertEquals("jpeg", ext(" Jpeg "))
        assertEquals("jpg", ext(""))
        assertEquals("jpg", ext("   "))
        assertEquals("dcm", ext("..DCM"))
    }

    @Test
    fun `contagem minima 1 e sem zeros a esquerda`() {
        fun nome(c: Int) = NomeArquivo.montar("Ana", Tipo.ROSTO, 1, t, c, "jpg",
            Contexto.SIMULACAO, utc)
        assertTrue(nome(0).endsWith("_1.jpg"))
        assertTrue(nome(-5).endsWith("_1.jpg"))
        assertTrue(nome(12).endsWith("_12.jpg"))
        assertTrue(nome(7).endsWith("_05_7.jpg"))
    }

    @Test
    fun `nome hostil sai so com caracteres seguros`() {
        val hostis = listOf("Ana*Paula", "José/Santos", "MARIA.", "A<B>C|D?E\"F",
                            "Zoë 😀 Ünal", "  ", "..", "C:\\Users\\x", "王小明", null)
        for (h in hostis) {
            for (tipo in Tipo.values()) {
                val n = NomeArquivo.montar(h, tipo, 3, t, 2, "jpg", Contexto.TRATAMENTO, utc)
                assertTrue("$h -> $n", charsetSeguro.matches(n))
                val e = NomeArquivo.montarEntrega(h, tipo, 3, t, 2, "jpg", Contexto.TRATAMENTO, utc)
                assertTrue("$h -> $e", charsetSeguro.matches(e))
            }
        }
        assertEquals("A_P", NomeArquivo.iniciais("Ana*Paula"))
        assertEquals("J_S", NomeArquivo.iniciais("José/Santos"))
        assertEquals("M", NomeArquivo.iniciais("MARIA."))
        assertEquals("A_B_C_D_F", NomeArquivo.iniciais("A<B>C|D?E\"F"))
        assertEquals("Z_U", NomeArquivo.iniciais("Zoë 😀 Ünal"))
    }

    // =========================================================== montarEntrega

    @Test
    fun `entrega leva o nome completo e o resto igual`() {
        assertEquals("MARIA_DA_SILVA_POS_TRAT_NS1_03_SET_2026_14_22_05_3.jpg",
            NomeArquivo.montarEntrega("Maria da Silva", Tipo.POSICIONAMENTO, 2, t, 3, "jpg",
                Contexto.TRATAMENTO, utc))
        val nomes = listOf("Maria Aparecida dos Santos", "Łukasz Øster", "", "Madonna")
        for (n in nomes) for (tipo in Tipo.values()) for (ctx in Contexto.values()) {
            val guardado = NomeArquivo.montar(n, tipo, 2, t, 4, "pdf", ctx, utc)
            val entregue = NomeArquivo.montarEntrega(n, tipo, 2, t, 4, "pdf", ctx, utc)
            assertEquals(guardado.removePrefix(NomeArquivo.iniciais(n)),
                         entregue.removePrefix(NomeArquivo.nomeCompletoAscii(n)))
        }
    }

    @Test
    fun `entrega de pacientes sem letra latina sai distinta e segura`() {
        val a = NomeArquivo.montarEntrega("王伟", Tipo.FICHA, 1, t, 1, "pdf", Contexto.SIMULACAO, utc)
        val b = NomeArquivo.montarEntrega("محمد علي", Tipo.FICHA, 1, t, 1, "pdf", Contexto.SIMULACAO, utc)
        assertEquals("X0C6E5FE8_FSIM_03_SET_2026_14_22_05_1.pdf", a)
        assertNotEquals(a, b)
        assertTrue(b, charsetSeguro.matches(b))
        // Cópia de entrega nunca é lida como nome guardado.
        assertFalse(NomeArquivo.analisar(a).esquemaNovo)
    }

    @Test
    fun `entrega de arquivo guardado troca as iniciais pelo nome`() {
        val nomes = listOf("Maria Aparecida dos Santos", "Łukasz Øster", "王伟", "محمد علي")
        for (n in nomes) for (tipo in Tipo.values()) for (ctx in Contexto.values()) {
            val guardado = NomeArquivo.montar(n, tipo, 2, t, 4, "jpg", ctx, utc)
            assertEquals("$n / $guardado",
                NomeArquivo.montarEntrega(n, tipo, 2, t, 4, "jpg", ctx, utc),
                NomeArquivo.entregaDoGuardado(guardado, n))
        }
        assertEquals("X0C6E5FE8_FSIM_03_SET_2026_14_22_05_1.pdf",
            NomeArquivo.entregaDoGuardado("X_FSIM_03_SET_2026_14_22_05_1.pdf", "王伟"))
        // Arquivado e quadro cheio mantêm as marcas.
        assertEquals("MARIA_SILVA_POS_03_SET_2026_14_22_05_2_ARQ1727960000000_ORIGINAL.jpg",
            NomeArquivo.entregaDoGuardado(
                "M_S_POS_03_SET_2026_14_22_05_2_ARQ1727960000000_ORIGINAL.jpg", "Maria Silva"))
    }

    @Test
    fun `entrega de arquivo guardado recusa o que nao e deste paciente`() {
        val guardado = "M_S_FSIM_03_SET_2026_14_22_05_1.pdf"
        // Iniciais de outra pessoa: trocar rotularia a ficha com o nome errado.
        assertNull(NomeArquivo.entregaDoGuardado(guardado, "Ana Paula"))
        assertNull(NomeArquivo.entregaDoGuardado(guardado, "王伟"))
        assertNull(NomeArquivo.entregaDoGuardado("X_FSIM_03_SET_2026_14_22_05_1.pdf", "Maria Silva"))
        // Paciente sem nome e nome legado, que já traz o nome completo.
        assertNull(NomeArquivo.entregaDoGuardado(guardado, null))
        assertNull(NomeArquivo.entregaDoGuardado(guardado, " "))
        assertNull(NomeArquivo.entregaDoGuardado(
            "MARIA_SILVA_FOLHA_SIMULACAO_03-SET.-2026_14-22-05.pdf", "Maria Silva"))
        // Caixa diferente nas iniciais ainda é o mesmo paciente.
        assertEquals("MARIA_SILVA_FSIM_03_SET_2026_14_22_05_1.pdf",
            NomeArquivo.entregaDoGuardado("m_s_FSIM_03_SET_2026_14_22_05_1.pdf", "Maria Silva"))
    }

    // =========================================================== tags

    @Test
    fun `invariantes dos tags`() {
        val tags = Tipo.values().map { it.tag }
        assertEquals(listOf("ROST", "ETIQ", "POS", "ACES", "DOC", "FSIM"), tags)
        assertEquals(tags.size, tags.toSet().size)
        for (tag in tags) {
            assertTrue(tag, tag.matches(Regex("^[A-Z]{2,4}$")))
            assertFalse(tag, tag in NomeArquivo.MESES)
            assertNotEquals(tag, "TRAT", tag)
            assertFalse(tag, tag.matches(Regex("^NS[0-9].*")))
            assertNotEquals(tag, "ARQ", tag)
        }
    }

    @Test
    fun `categoria da sessao e tipo vao e voltam`() {
        for (c in SessionManager.Category.values())
            assertEquals(c, NomeArquivo.categoriaDe(NomeArquivo.tipoDe(c)))
        assertNull(NomeArquivo.categoriaDe(Tipo.FICHA))
        assertEquals(Tipo.ROSTO, NomeArquivo.tipoDe(SessionManager.Category.FACE))
        assertEquals(Tipo.ETIQUETA, NomeArquivo.tipoDe(SessionManager.Category.LABEL))
        assertEquals(Tipo.POSICIONAMENTO, NomeArquivo.tipoDe(SessionManager.Category.POSITIONING))
        assertEquals(Tipo.ACESSORIOS, NomeArquivo.tipoDe(SessionManager.Category.ACCESSORIES))
        assertEquals(Tipo.IMPRESSO, NomeArquivo.tipoDe(SessionManager.Category.DOCUMENTS))
    }

    // =========================================================== analisar: esquema novo

    @Test
    fun `ida e volta de todo tipo, simulacao, contexto e extensao`() {
        for (tipo in Tipo.values()) for (sim in listOf(1, 2, 11)) for (ctx in Contexto.values())
            for (ext in listOf("jpg", "pdf")) {
                val nome = NomeArquivo.montar("Maria Aparecida dos Santos", tipo, sim, t, 7, ext, ctx, utc)
                val a = NomeArquivo.analisar(nome)
                assertEquals(nome, tipo, a.tipo)
                assertEquals(nome, sim, a.numeroSimulacao)
                assertEquals(nome, ctx == Contexto.TRATAMENTO && tipo != Tipo.FICHA, a.tratamento)
                assertEquals(nome, 7, a.contador)
                assertTrue(nome, a.esquemaNovo)
                assertFalse(nome, a.original)
                assertFalse(nome, a.arquivada)
                assertEquals(nome, tipo, NomeArquivo.tipo(nome))
                assertEquals(nome, sim, NomeArquivo.numeroSimulacao(nome))
            }
    }

    @Test
    fun `leitura ignora caixa`() {
        val a = NomeArquivo.analisar("m_s_pos_03_set_2026_14_22_05_1.JPG")
        assertEquals(Tipo.POSICIONAMENTO, a.tipo)
        assertTrue(a.esquemaNovo)
        assertEquals(1, a.contador)
    }

    @Test
    fun `iniciais X sao lidas`() {
        val a = NomeArquivo.analisar("X_ROST_03_SET_2026_14_22_05_1.jpg")
        assertEquals(Tipo.ROSTO, a.tipo)
        assertTrue(a.esquemaNovo)
    }

    @Test
    fun `sufixos de quadro cheio e de arquivada`() {
        val base = "M_S_POS_03_SET_2026_14_22_05_1"
        val orig = NomeArquivo.analisar("${base}_ORIGINAL.jpg")
        assertTrue(orig.original); assertFalse(orig.arquivada); assertTrue(orig.esquemaNovo)
        val arq = NomeArquivo.analisar("${base}_ARQ1727960000000.jpg")
        assertTrue(arq.arquivada); assertFalse(arq.original); assertTrue(arq.esquemaNovo)
        val os2 = NomeArquivo.analisar("${base}_ARQ1727960000000_ORIGINAL.jpg")
        assertTrue(os2.arquivada); assertTrue(os2.original); assertTrue(os2.esquemaNovo)
        val minusc = NomeArquivo.analisar("${base}_original.jpg")
        assertTrue(minusc.original); assertTrue(minusc.esquemaNovo)
        for (a in listOf(orig, arq, os2, minusc)) {
            assertEquals(Tipo.POSICIONAMENTO, a.tipo)
            assertEquals(1, a.contador)
        }
    }

    @Test
    fun `DICOM e aceito com o tag da foto`() {
        val a = NomeArquivo.analisar("M_S_POS_NS1_03_SET_2026_14_22_05_2.dcm")
        assertEquals(Tipo.POSICIONAMENTO, a.tipo)
        assertEquals(2, a.numeroSimulacao)
        assertTrue(a.esquemaNovo)
    }

    @Test
    fun `leitura estrita do esquema novo`() {
        val quase = listOf(
            "M_S_XYZ_03_SET_2026_14_22_05_1.jpg",      // tag desconhecido
            "M_S_POS_03_SEP_2026_14_22_05_1.jpg",      // mês em inglês
            "M_S_POS_03_SET_26_14_22_05_1.jpg",        // ano com 2 dígitos
            "M_S_POS_03_SET_2026_14_22_05_0.jpg",      // contagem 0
            "M_S_POS_NS0_03_SET_2026_14_22_05_1.jpg",  // NS0
            "M_S_POS_03_SET_2026_14_22_1.jpg",         // sem segundos
            "M_S_POS_03_SET_2026_14_22_05_1.png",      // extensão
            "M_S_POS_03_SET_2026_14_22_05_1.x.jpg",    // dois pontos
            "M_S_POS_03_SET_2026_14_22_05_01x.jpg",    // contagem com letra
            "MS_POS_03_SET_2026_14_22_05_1.jpg",       // prefixo de duas letras
            "POS_03_SET_2026_14_22_05_1.jpg",          // sem iniciais
            "Ç_S_POS_03_SET_2026_14_22_05_1.jpg",      // não ASCII
            "M_\u0131_POS_03_SET_2026_14_22_05_1.jpg", // i sem ponto, que vira I por caixa
            "M_S_POS_03_SET_2026_14_22_05_1_ORIGINAL_ARQ5.jpg" // ordem trocada
        )
        for (n in quase) assertFalse(n, NomeArquivo.analisar(n).esquemaNovo)
        assertNull(NomeArquivo.analisar("M_S_XYZ_03_SET_2026_14_22_05_1.jpg").tipo)
    }

    // =========================================================== analisar: legado

    private data class Legado(val nome: String, val tipo: Tipo?, val sim: Int,
                              val original: Boolean = false, val tratamento: Boolean = false)

    /** Copiados do que os gravadores anteriores escreviam, nas duas variantes de mês do ICU. */
    private val legados = listOf(
        Legado("MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg", Tipo.ROSTO, 1),
        Legado("MARIA_SILVA_ETIQUETA_03-SET-2026_14-22-05_2.jpg", Tipo.ETIQUETA, 1),
        Legado("MARIA_SILVA_POSICIONAMENTO_NOVASIM1_03-SET.-2026_14-22-05_3.jpg", Tipo.POSICIONAMENTO, 2),
        Legado("MARIA_SILVA_ACESSORIOS_03-SET.-2026_14-22-05_4.jpg", Tipo.ACESSORIOS, 1),
        Legado("MARIA_SILVA_DOC_03-SET.-2026_14-22-05_5.jpg", Tipo.IMPRESSO, 1),
        Legado("MARIA_SILVA_POSICIONAMENTO_TRATAMENTO_NOVASIM2_03-SET.-2026_14-22-05_1.jpg",
            Tipo.POSICIONAMENTO, 3, tratamento = true),
        Legado("MARIA_SILVA_ACESSORIOS_TRATAMENTO_03-SET.-2026_14-22-05_2.jpg",
            Tipo.ACESSORIOS, 1, tratamento = true),
        Legado("MARIA_SILVA_DOC_NOVASIM1_03-SET.-2026_14-22-05_1.jpg", Tipo.IMPRESSO, 2),
        Legado("MARIA_SILVA_FOLHA_SIMULACAO_03-SET.-2026_14-22-05.pdf", Tipo.FICHA, 1),
        Legado("MARIA_SILVA_FOLHA_SIMULACAO_NOVASIM1_03-SET.-2026_14-22-05.pdf", Tipo.FICHA, 2),
        Legado("MARIA_SILVA_FolhaSimulacao_NOVASIM1_03_set._2026__14_22_05.pdf", Tipo.FICHA, 2),
        Legado("MARIA_D'ARC_ROSTO_03-SET.-2026_14-22-05_1.jpg", Tipo.ROSTO, 1),
        Legado("MARIA_SILVA_POSICIONAMENTO_03-SET.-2026_14-22-05_3_ORIGINAL.jpg",
            Tipo.POSICIONAMENTO, 1, original = true),
        Legado("pdf_folha_MARIA_SILVA_03_set._2026__14_22_05.pdf", Tipo.FICHA, 1)
    )

    @Test
    fun `nomes legados classificados como sempre`() {
        for (l in legados) {
            val a = NomeArquivo.analisar(l.nome)
            assertEquals(l.nome, l.tipo, a.tipo)
            assertEquals(l.nome, l.sim, a.numeroSimulacao)
            assertEquals(l.nome, l.original, a.original)
            assertEquals(l.nome, l.tratamento, a.tratamento)
            assertFalse(l.nome, a.arquivada)
            assertNull(l.nome, a.contador)
        }
    }

    @Test
    fun `nome legado nunca e lido como esquema novo`() {
        for (l in legados) assertFalse(l.nome, NomeArquivo.analisar(l.nome).esquemaNovo)
    }

    @Test
    fun `nomes da sessao e arquivadas legadas`() {
        val casos = mapOf(
            "_rosto_ARQ1727960000000.jpg" to Tipo.ROSTO,
            "_etiqueta_ARQ3.jpg" to Tipo.ETIQUETA,
            "_pos_1727960000000_ARQ1727960000001.jpg" to Tipo.POSICIONAMENTO,
            "_acessorios_1_ARQ2.jpg" to Tipo.ACESSORIOS,
            "_doc_1_ARQ2.jpg" to Tipo.IMPRESSO
        )
        for ((nome, tipo) in casos) {
            val a = NomeArquivo.analisar(nome)
            assertEquals(nome, tipo, a.tipo)
            assertTrue(nome, a.arquivada)
            assertFalse(nome, a.esquemaNovo)
        }
        val par = NomeArquivo.analisar("_rosto_ARQ1727960000000_ORIGINAL.jpg")
        assertTrue(par.original); assertTrue(par.arquivada); assertEquals(Tipo.ROSTO, par.tipo)
    }

    @Test
    fun `nomes antigos em ingles`() {
        assertEquals(Tipo.ROSTO, NomeArquivo.tipo("face.jpg"))
        assertEquals(Tipo.ETIQUETA, NomeArquivo.tipo("label_1.jpg"))
        assertEquals(Tipo.POSICIONAMENTO, NomeArquivo.tipo("positioning_2.jpg"))
        assertEquals(Tipo.ACESSORIOS, NomeArquivo.tipo("accessories.jpg"))
    }

    @Test
    fun `legado com outra extensao fica sem tipo`() {
        assertNull(NomeArquivo.tipo("MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.png"))
        assertNull(NomeArquivo.tipo(".timeout_sim2.json"))
        assertNull(NomeArquivo.tipo("RESUMO.txt"))
        assertNull(NomeArquivo.tipo("SEM_EXTENSAO"))
    }

    @Test
    fun `NOVASIM de dois digitos e lido inteiro`() {
        val n = "MARIA_SILVA_POSICIONAMENTO_NOVASIM10_03-SET.-2026_14-22-05_1.jpg"
        assertEquals(11, NomeArquivo.numeroSimulacao(n))
        assertFalse(NomeArquivo.pertenceASimulacao(n, 2))
        assertTrue(NomeArquivo.pertenceASimulacao(n, 11))
        assertEquals(20, NomeArquivo.numeroSimulacao("X_FOLHA_SIMULACAO_NOVASIM19_03-SET.-2026_14-22-05.pdf"))
    }

    // =========================================================== simulação

    @Test
    fun `pertence a simulacao, nos dois esquemas`() {
        val porSimulacao = mapOf(
            "M_S_POS_03_SET_2026_14_22_05_1.jpg" to 1,
            "M_S_POS_NS1_03_SET_2026_14_22_05_1.jpg" to 2,
            "M_S_POS_TRAT_NS10_03_SET_2026_14_22_05_1.jpg" to 11,
            "M_S_FSIM_03_SET_2026_14_22_05_1.pdf" to 1,
            "M_S_FSIM_NS1_03_SET_2026_14_22_05_1.pdf" to 2,
            "M_S_ROST_NS10_03_SET_2026_14_22_05_1.dcm" to 11,
            "M_S_POS_03_SET_2026_14_22_05_1_ORIGINAL.jpg" to 1,
            "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg" to 1,
            "MARIA_SILVA_ROSTO_NOVASIM1_03-SET.-2026_14-22-05_1.jpg" to 2,
            "MARIA_SILVA_ROSTO_NOVASIM10_03-SET.-2026_14-22-05_1.jpg" to 11,
            "MARIA_SILVA_FOLHA_SIMULACAO_03-SET.-2026_14-22-05.pdf" to 1,
            "MARIA_SILVA_FOLHA_SIMULACAO_NOVASIM1_03-SET.-2026_14-22-05.pdf" to 2
        )
        for ((nome, sim) in porSimulacao) for (s in listOf(1, 2, 11))
            assertEquals("$nome em $s", s == sim, NomeArquivo.pertenceASimulacao(nome, s))
    }

    @Test
    fun `ocultos e outros arquivos nunca pertencem a simulacao`() {
        val fora = listOf(".timeout_sim2.json", ".obs_sim1.txt", ".photoid_teste_1.tmp",
                          "RESUMO.txt", ".nomedia", ".M_S_POS_03_SET_2026_14_22_05_1.jpg")
        for (n in fora) for (s in listOf(1, 2, 11))
            assertFalse("$n em $s", NomeArquivo.pertenceASimulacao(n, s))
    }

    @Test
    fun `ficha da simulacao so casa a propria`() {
        val fichas = mapOf(
            "M_S_FSIM_03_SET_2026_14_22_05_1.pdf" to 1,
            "M_S_FSIM_NS1_03_SET_2026_14_22_05_1.pdf" to 2,
            "MARIA_SILVA_FOLHA_SIMULACAO_03-SET.-2026_14-22-05.pdf" to 1,
            "MARIA_SILVA_FOLHA_SIMULACAO_NOVASIM1_03-SET.-2026_14-22-05.pdf" to 2,
            "MARIA_SILVA_FolhaSimulacao_NOVASIM1_03_set._2026__14_22_05.pdf" to 2
        )
        for ((nome, sim) in fichas) for (s in listOf(1, 2))
            assertEquals("$nome em $s", s == sim, NomeArquivo.ehFichaDaSimulacao(nome, s))
        assertFalse(NomeArquivo.ehFichaDaSimulacao("M_S_POS_03_SET_2026_14_22_05_1.jpg", 1))
        assertFalse(NomeArquivo.ehFichaDaSimulacao(".oculto_FSIM.pdf", 1))
    }

    // =========================================================== quadro cheio

    @Test
    fun `reconhece o quadro cheio`() {
        val sim = listOf(
            "M_S_POS_03_SET_2026_14_22_05_1_ORIGINAL.jpg",
            "MARIA_SILVA_POSICIONAMENTO_03-SET.-2026_14-22-05_3_ORIGINAL.jpg",
            "X_original.JPG",
            "M_S_POS_03_SET_2026_14_22_05_1_ARQ1_ORIGINAL.jpeg",
            "_pos_123_ORIGINAL.jpg"
        )
        for (n in sim) assertTrue(n, NomeArquivo.ehOriginal(n))
        val nao = listOf(
            "M_S_POS_03_SET_2026_14_22_05_1.jpg",
            "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            "M_S_FSIM_03_SET_2026_14_22_05_1_ORIGINAL.pdf",
            "ORIGENES_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            // O companheiro é sempre _ORIGINAL; o sufixo curto não é reconhecido.
            "M_S_POS_03_SET_2026_14_22_05_1_ORIG.jpg"
        )
        for (n in nao) assertFalse(n, NomeArquivo.ehOriginal(n))
    }

    @Test
    fun `nome do quadro cheio e candidatos`() {
        assertEquals("M_S_POS_03_SET_2026_14_22_05_3_ORIGINAL.jpg",
            NomeArquivo.nomeOriginal("M_S_POS_03_SET_2026_14_22_05_3.jpg"))
        assertEquals(listOf("M_S_POS_03_SET_2026_14_22_05_3_ORIGINAL.jpg"),
            NomeArquivo.candidatosOriginal("M_S_POS_03_SET_2026_14_22_05_3.jpg"))
        assertEquals(listOf("MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1_ORIGINAL.jpg"),
            NomeArquivo.candidatosOriginal("MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg"))
        assertEquals(listOf("M_S_POS_03_SET_2026_14_22_05_3_ORIGINAL.jpeg",
                            "M_S_POS_03_SET_2026_14_22_05_3_ORIGINAL.jpg"),
            NomeArquivo.candidatosOriginal("M_S_POS_03_SET_2026_14_22_05_3.jpeg"))
        // O par ordena ao lado da foto.
        val foto = "M_S_POS_03_SET_2026_14_22_05_3.jpg"
        assertTrue(NomeArquivo.nomeOriginal(foto).startsWith(foto.substringBeforeLast('.')))
        // O nome montado é lido de volta como quadro cheio do mesmo tipo.
        val a = NomeArquivo.analisar(NomeArquivo.nomeOriginal(foto))
        assertTrue(a.original); assertTrue(a.esquemaNovo); assertEquals(Tipo.POSICIONAMENTO, a.tipo)
    }

    @Test
    fun `acha o quadro cheio na pasta`() {
        val pasta = tmp.newFolder("PAC")
        val foto = File(pasta, "M_S_POS_03_SET_2026_14_22_05_3.jpg").apply { writeText("f") }
        assertNull(NomeArquivo.originalDe(foto))

        val par = File(pasta, "M_S_POS_03_SET_2026_14_22_05_3_ORIGINAL.jpg").apply { writeText("o") }
        assertEquals(par, NomeArquivo.originalDe(foto))
        assertNull("quadro cheio não tem quadro cheio", NomeArquivo.originalDe(par))

        val jpeg = File(pasta, "M_S_ROST_03_SET_2026_14_22_05_1.jpeg").apply { writeText("f") }
        val parJpg = File(pasta, "M_S_ROST_03_SET_2026_14_22_05_1_ORIGINAL.jpg").apply { writeText("o") }
        assertEquals(parJpg, NomeArquivo.originalDe(jpeg))

        val vazio = File(pasta, "M_S_ETIQ_03_SET_2026_14_22_05_1.jpg").apply { writeText("f") }
        File(pasta, "M_S_ETIQ_03_SET_2026_14_22_05_1_ORIGINAL.jpg").createNewFile()
        assertNull("arquivo vazio não é quadro cheio", NomeArquivo.originalDe(vazio))

        val legado = File(pasta, "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg").apply { writeText("f") }
        val parLegado = File(pasta, "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1_ORIGINAL.jpg")
            .apply { writeText("o") }
        assertEquals(parLegado, NomeArquivo.originalDe(legado))
    }

    // =========================================================== contagem

    private fun pos(c: Int, sim: Int = 1, trat: Boolean = false): String =
        NomeArquivo.montar("Maria Silva", Tipo.POSICIONAMENTO, sim, t, c, "jpg",
            if (trat) Contexto.TRATAMENTO else Contexto.SIMULACAO, utc)

    private fun posLegado(c: Int, sim: Int = 1): String {
        val ns = if (sim > 1) "_NOVASIM${sim - 1}" else ""
        return "MARIA_SILVA_POSICIONAMENTO${ns}_03-SET.-2026_14-22-05_$c.jpg"
    }

    @Test
    fun `contagem de pasta vazia comeca em 1`() {
        assertEquals(1, NomeArquivo.proximoContador(emptyList(), 1, Tipo.POSICIONAMENTO))
    }

    @Test
    fun `contagem do esquema novo segue a maior`() {
        val nomes = mutableListOf(pos(1), pos(2), pos(3))
        assertEquals(4, NomeArquivo.proximoContador(nomes, 1, Tipo.POSICIONAMENTO))
        nomes += pos(7, sim = 2)
        nomes += NomeArquivo.montar("Maria Silva", Tipo.ROSTO, 1, t, 9, "jpg", Contexto.SIMULACAO, utc)
        assertEquals(4, NomeArquivo.proximoContador(nomes, 1, Tipo.POSICIONAMENTO))
        assertEquals(8, NomeArquivo.proximoContador(nomes, 2, Tipo.POSICIONAMENTO))
        assertEquals(10, NomeArquivo.proximoContador(nomes, 1, Tipo.ROSTO))
        // Tratamento divide a sequência com a simulação.
        nomes += pos(5, trat = true)
        assertEquals(6, NomeArquivo.proximoContador(nomes, 1, Tipo.POSICIONAMENTO))
    }

    @Test
    fun `contagem so com legado conta as fotos`() {
        val nomes = (1..4).flatMap { listOf(posLegado(it), NomeArquivo.nomeOriginal(posLegado(it))) } +
            "MARIA_SILVA_FOLHA_SIMULACAO_03-SET.-2026_14-22-05.pdf" +
            posLegado(1, sim = 2)
        assertEquals(5, NomeArquivo.proximoContador(nomes, 1, Tipo.POSICIONAMENTO))
        assertEquals(2, NomeArquivo.proximoContador(nomes, 2, Tipo.POSICIONAMENTO))
    }

    @Test
    fun `contagem mista e com lacunas`() {
        val legado4 = (1..4).map { posLegado(it) }
        assertEquals(7, NomeArquivo.proximoContador(legado4 + pos(5) + pos(6), 1, Tipo.POSICIONAMENTO))
        assertEquals(10, NomeArquivo.proximoContador(legado4 + pos(2) + pos(9), 1, Tipo.POSICIONAMENTO))
        assertEquals(5, NomeArquivo.proximoContador(legado4 + pos(2), 1, Tipo.POSICIONAMENTO))
    }

    @Test
    fun `contagem ignora quadro cheio, arquivada e oculto`() {
        val base = pos(1)
        val nomes = listOf(
            base,
            NomeArquivo.nomeOriginal(pos(8)),
            pos(9).substringBeforeLast('.') + "_ARQ1727960000000.jpg",
            "." + pos(12),
            "_pos_1727960000000_ARQ1727960000001.jpg"
        )
        assertEquals(2, NomeArquivo.proximoContador(nomes, 1, Tipo.POSICIONAMENTO))
    }

    // =========================================================== lote

    @Test
    fun `lote numera por tipo e nao repete nome`() {
        val tipos = listOf(Tipo.ROSTO, Tipo.ETIQUETA, Tipo.POSICIONAMENTO, Tipo.POSICIONAMENTO,
                           Tipo.POSICIONAMENTO, Tipo.ACESSORIOS, Tipo.ACESSORIOS,
                           Tipo.IMPRESSO, Tipo.IMPRESSO)
        val nomes = NomeArquivo.nomearLote("Maria Silva", tipos.map { it to t }, 1,
            Contexto.SIMULACAO, fuso = utc)
        assertEquals(listOf(1, 1, 1, 2, 3, 1, 2, 1, 2), nomes.map { NomeArquivo.analisar(it).contador })
        assertEquals(tipos, nomes.map { NomeArquivo.tipo(it) })
        assertEquals(nomes.size, nomes.toSet().size)
        assertEquals("M_S_ROST_03_SET_2026_14_22_05_1.jpg", nomes[0])
        assertEquals("M_S_POS_03_SET_2026_14_22_05_3.jpg", nomes[4])
    }

    @Test
    fun `lote continua a numeracao da pasta`() {
        val existentes = listOf(pos(1), pos(2), "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg")
        val nomes = NomeArquivo.nomearLote("Maria Silva",
            listOf(Tipo.ROSTO to t, Tipo.POSICIONAMENTO to t, Tipo.POSICIONAMENTO to t, Tipo.ETIQUETA to t),
            1, Contexto.SIMULACAO,
            inicio = { NomeArquivo.proximoContador(existentes, 1, it) }, fuso = utc)
        assertEquals(listOf(2, 3, 4, 1), nomes.map { NomeArquivo.analisar(it).contador })
        assertTrue(nomes.none { it in existentes })
    }

    @Test
    fun `lote de tratamento e reirradiacao`() {
        val nomes = NomeArquivo.nomearLote("Ana Souza",
            listOf(Tipo.POSICIONAMENTO to t, Tipo.IMPRESSO to t), 3, Contexto.TRATAMENTO,
            extensao = "JPEG", fuso = utc)
        assertEquals(listOf("A_S_POS_TRAT_NS2_03_SET_2026_14_22_05_1.jpeg",
                            "A_S_DOC_TRAT_NS2_03_SET_2026_14_22_05_1.jpeg"), nomes)
    }

    // =========================================================== troca de nome do paciente

    @Test
    fun `troca as iniciais e mantem o resto`() {
        assertEquals("M_S_C_POS_03_SET_2026_14_22_05_3.jpg",
            NomeArquivo.trocarIniciais("M_S_POS_03_SET_2026_14_22_05_3.jpg", "Maria Silva Costa"))
        assertEquals("A_B_POS_TRAT_NS1_10_OUT_2026_09_00_41_7.jpg",
            NomeArquivo.trocarIniciais("M_S_POS_TRAT_NS1_10_OUT_2026_09_00_41_7.jpg", "Ana Beatriz"))
        assertEquals("A_ROST_03_SET_2026_14_22_05_1_ARQ1727960000000_ORIGINAL.jpg",
            NomeArquivo.trocarIniciais(
                "M_S_ROST_03_SET_2026_14_22_05_1_ARQ1727960000000_ORIGINAL.jpg", "Ana"))
        assertEquals("M_S_POS_03_SET_2026_14_22_05_3.jpg",
            NomeArquivo.trocarIniciais("M_S_POS_03_SET_2026_14_22_05_3.jpg", "Mario Souza"))
        assertEquals("X_POS_03_SET_2026_14_22_05_3.jpg",
            NomeArquivo.trocarIniciais("M_S_POS_03_SET_2026_14_22_05_3.jpg", "  "))
        assertNull(NomeArquivo.trocarIniciais("MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg", "Ana"))
    }

    @Test
    fun `troca o prefixo do legado na mesma variante`() {
        val antigo = "Maria D'Arc"
        val novo = "Maria Darc Souza"
        // Variante que mantém o apóstrofo (fotos da simulação, reedição da ficha).
        assertEquals("MARIA_DARC_SOUZA_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            NomeArquivo.renomearLegado("MARIA_D'ARC_ROSTO_03-SET.-2026_14-22-05_1.jpg", antigo, novo))
        // Variante que apaga o apóstrofo (módulo Tratamento, edição de cadastro).
        assertEquals("MARIA_DARC_SOUZA_POSICIONAMENTO_TRATAMENTO_03-SET.-2026_14-22-05_2.jpg",
            NomeArquivo.renomearLegado(
                "MARIA_DARC_POSICIONAMENTO_TRATAMENTO_03-SET.-2026_14-22-05_2.jpg", antigo, novo))
        assertEquals("MARIA_DARC_SOUZA_POSICIONAMENTO_03-SET.-2026_14-22-05_3_ORIGINAL.jpg",
            NomeArquivo.renomearLegado(
                "MARIA_D'ARC_POSICIONAMENTO_03-SET.-2026_14-22-05_3_ORIGINAL.jpg", antigo, novo))
        // O novo nome sai na variante do arquivo: com apóstrofo, se ela o mantém.
        assertEquals("MARIA_D'ARC_SOUZA_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            NomeArquivo.renomearLegado("MARIA_D'ARC_ROSTO_03-SET.-2026_14-22-05_1.jpg",
                antigo, "Maria D'Arc Souza"))
    }

    @Test
    fun `legado de outro paciente ou do esquema novo nao e tocado`() {
        assertNull(NomeArquivo.renomearLegado("JOANA_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            "Maria D'Arc", "Maria Darc Souza"))
        assertNull(NomeArquivo.renomearLegado("M_D_ROST_03_SET_2026_14_22_05_1.jpg",
            "Maria D'Arc", "Maria Darc Souza"))
        assertNull(NomeArquivo.renomearLegado("ANABELA_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            "Ana", "Ana Paula"))
        assertEquals("ANA_PAULA_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            NomeArquivo.renomearLegado("ANA_ROSTO_03-SET.-2026_14-22-05_1.jpg", "Ana", "Ana Paula"))
        assertNull("nome antigo vazio não casa nada",
            NomeArquivo.renomearLegado("_rosto.jpg", "", "Ana"))
        assertNull("nome novo vazio não renomeia",
            NomeArquivo.renomearLegado("ANA_ROSTO_03-SET.-2026_14-22-05_1.jpg", "Ana", ""))
    }

    @Test
    fun `legado com acento e PDF de servidor`() {
        assertEquals("JOAO_DA_CONCEICAO_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            NomeArquivo.renomearLegado("JOAO_CONCEICAO_ROSTO_03-SET.-2026_14-22-05_1.jpg",
                "João Conceição", "João da Conceição"))
        assertEquals("MARIA_SILVA_COSTA_FolhaSimulacao_NOVASIM1_03_set._2026__14_22_05.pdf",
            NomeArquivo.renomearLegado("MARIA_SILVA_FolhaSimulacao_NOVASIM1_03_set._2026__14_22_05.pdf",
                "Maria Silva", "Maria Silva Costa"))
    }

    @Test
    fun `renomear para o paciente escolhe pelo esquema`() {
        val novo = "M_S_POS_03_SET_2026_14_22_05_3.jpg"
        val legado = "MARIA_SILVA_ROSTO_03-SET.-2026_14-22-05_1.jpg"
        assertEquals(NomeArquivo.trocarIniciais(novo, "Ana Beatriz"),
            NomeArquivo.renomearParaPaciente(novo, "Maria Silva", "Ana Beatriz"))
        assertEquals("A_B_POS_03_SET_2026_14_22_05_3.jpg",
            NomeArquivo.renomearParaPaciente(novo, "Maria Silva", "Ana Beatriz"))
        assertEquals(NomeArquivo.renomearLegado(legado, "Maria Silva", "Ana Beatriz"),
            NomeArquivo.renomearParaPaciente(legado, "Maria Silva", "Ana Beatriz"))
        assertEquals("ANA_BEATRIZ_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            NomeArquivo.renomearParaPaciente(legado, "Maria Silva", "Ana Beatriz"))
        assertNull(NomeArquivo.renomearParaPaciente("JOANA_ROSTO_03-SET.-2026_14-22-05_1.jpg",
            "Maria Silva", "Ana Beatriz"))
    }
}
