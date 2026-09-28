package com.radioterapia.ai.util


/**
 * Formata datas de nascimento para exibição/PDF no padrão DD-MMM-AAAA
 * (ex.: 15-JUL-1982). Aceita entrada como "15071982", "15/07/1982" ou
 * "1982-07-15". Se não conseguir interpretar, devolve o texto original.
 */
object DateUtils {

    // TABELAS FIXAS SÓ PARA OS TRÊS IDIOMAS ORIGINAIS.
    //
    // Elas ficam escritas à mão de propósito: já estão impressas em fichas de
    // pacientes reais, e trocá-las pelo que o sistema devolve mudaria o texto
    // de "FEV" para "FEV." em base instalada. Os nove idiomas novos não têm
    // essa herança e usam o CLDR do próprio Android (ver [abreviacaoMes]).
    private val MESES_PT = arrayOf("JAN","FEV","MAR","ABR","MAI","JUN","JUL","AGO","SET","OUT","NOV","DEZ")
    private val MESES_EN = arrayOf("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC")
    private val MESES_ES = arrayOf("ENE","FEB","MAR","ABR","MAY","JUN","JUL","AGO","SEP","OCT","NOV","DIC")

    /** Cache: DateFormatSymbols relê os dados de locale a cada construção, e
     *  esta função é chamada uma vez por linha de lista de pacientes. */
    private val cacheMeses = HashMap<String, Array<String>>()

    /**
     * Abreviação do mês no idioma escolhido.
     *
     * NOS NOVE IDIOMAS NOVOS quem responde é o CLDR embutido no Android, e não
     * uma tabela nossa. Escrever à mão a abreviação de mês em polonês, árabe e
     * bengali seria inventar dado que o sistema já tem correto — e em chinês,
     * japonês e coreano nem existe abreviação de três letras: o CLDR devolve
     * "2月" e "2월", que é a forma certa e que uma tabela latina erraria.
     *
     * O ponto final ("févr.", "janv.") é retirado: o formato da ficha é
     * DD-MMM-AAAA, com hífen separando, e o ponto no meio confunde a leitura.
     */
    private fun abreviacaoMes(mes: Int, idioma: String): String {
        val tabela = when (idioma) {
            "pt" -> MESES_PT
            "en" -> MESES_EN
            "es" -> MESES_ES   // ENE/ABR/MAY/AGO/SEP/DIC divergem do PT
            else -> cacheMeses.getOrPut(idioma) {
                try {
                    val loc = com.radioterapia.ai.i18n.LocaleManager.localeDe(idioma)
                    val curtos = java.text.DateFormatSymbols(loc).shortMonths
                    Array(12) { i ->
                        (curtos.getOrNull(i) ?: "").trim().removeSuffix(".").uppercase(loc)
                            .ifBlank { MESES_PT[i] }
                    }
                } catch (_: Throwable) {
                    // Locale que a versão do Android não conhece: melhor a
                    // abreviação do idioma base que uma data sem mês.
                    MESES_PT
                }
            }
        }
        return tabela.getOrNull(mes - 1) ?: MESES_PT[mes - 1]
    }

    /**
     * Valida uma data dd/MM/yyyy de verdade: mês 1-12, dia existente no mês
     * (com ano bissexto), ano plausível e NÃO no futuro. O check antigo só
     * media o comprimento do texto, então 31/02/2020 e 99/99/9999 passavam.
     */
    fun nascimentoValido(txt: String?): Boolean {
        val s = txt?.trim() ?: return false
        if (!Regex("^\\d{2}/\\d{2}/\\d{4}$").matches(s)) return false
        val d = s.substring(0, 2).toInt()
        val m = s.substring(3, 5).toInt()
        val a = s.substring(6, 10).toInt()
        if (m !in 1..12) return false
        val hoje = java.util.Calendar.getInstance()
        val anoAtual = hoje.get(java.util.Calendar.YEAR)
        if (a < anoAtual - 130 || a > anoAtual) return false
        val bissexto = (a % 4 == 0 && a % 100 != 0) || a % 400 == 0
        val diasNoMes = intArrayOf(31, if (bissexto) 29 else 28, 31, 30, 31, 30,
                                   31, 31, 30, 31, 30, 31)
        if (d < 1 || d > diasNoMes[m - 1]) return false
        val cal = java.util.Calendar.getInstance().apply {
            isLenient = false
            set(a, m - 1, d, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return try { !cal.time.after(hoje.time) } catch (_: Exception) { false }
    }

    /** Devolve DD-MMM-AAAA ou o texto original se não der pra interpretar. */
    fun formatarNascimento(raw: String?, idioma: String = "pt"): String {
        if (raw.isNullOrBlank()) return ""
        val txt = raw.trim()

        var dia = 0; var mes = 0; var ano = 0
        try {
            when {
                // 1982-07-15 (ISO)
                Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$").matches(txt) -> {
                    val p = txt.split("-")
                    ano = p[0].toInt(); mes = p[1].toInt(); dia = p[2].toInt()
                }
                // 15/07/1982 ou 15-07-1982 ou 15.07.1982
                Regex("^\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}$").matches(txt) -> {
                    val p = txt.split('/', '.', '-')
                    dia = p[0].toInt(); mes = p[1].toInt(); ano = normalizarAno(p[2].toInt())
                }
                // 15071982 (8 dígitos) ddmmaaaa
                Regex("^\\d{8}$").matches(txt) -> {
                    dia = txt.substring(0, 2).toInt()
                    mes = txt.substring(2, 4).toInt()
                    ano = txt.substring(4, 8).toInt()
                }
                else -> return txt
            }
        } catch (_: Exception) { return txt }

        if (mes !in 1..12 || dia !in 1..31 || ano < 1) return txt
        val mm = abreviacaoMes(mes, idioma)
        return "%02d-%s-%04d".format(dia, mm, ano)
    }

    // ==================== FORMATO DE ENTRADA ====================

    /** Os formatos de digitacao oferecidos. O primeiro e o padrao. */
    val FORMATOS_ENTRADA = listOf("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd")

    /**
     * Converte o que o tecnico digitou para o CANONICO dd/MM/yyyy.
     *
     * Devolve "" quando a data nao e valida no formato escolhido — quem chama
     * trata isso como recusa, e e de proposito: aceitar "meio valido" aqui
     * significa gravar data de nascimento errada no cadastro do paciente.
     */
    fun entradaParaCanonico(txt: String?, formato: String): String {
        val dig = (txt ?: "").filter { it.isDigit() }
        if (dig.length != 8) return ""
        val (d, m, a) = when (formato) {
            "MM/dd/yyyy" -> Triple(dig.substring(2, 4), dig.substring(0, 2), dig.substring(4, 8))
            "yyyy-MM-dd" -> Triple(dig.substring(6, 8), dig.substring(4, 6), dig.substring(0, 4))
            else          -> Triple(dig.substring(0, 2), dig.substring(2, 4), dig.substring(4, 8))
        }
        val canonico = "$d/$m/$a"
        return if (nascimentoValido(canonico)) canonico else ""
    }

    /**
     * O caminho de volta: canonico dd/MM/yyyy para o formato de digitacao.
     *
     * Usado ao PREENCHER o campo com um valor ja gravado. Sem ele, um servico
     * em MM/dd abriria a revisao do cadastro e leria a data no formato do
     * vizinho — e corrigiria um campo que estava certo.
     */
    fun canonicoParaEntrada(canonico: String?, formato: String): String {
        val dig = (canonico ?: "").filter { it.isDigit() }
        if (dig.length != 8) return canonico.orEmpty()
        val d = dig.substring(0, 2); val m = dig.substring(2, 4); val a = dig.substring(4, 8)
        return when (formato) {
            "MM/dd/yyyy" -> "$m/$d/$a"
            "yyyy-MM-dd" -> "$a-$m-$d"
            else          -> "$d/$m/$a"
        }
    }

    /** Onde a mascara insere o separador, e qual separador, por formato. */
    fun cortesDaMascara(formato: String): Pair<List<Int>, Char> = when (formato) {
        "yyyy-MM-dd" -> listOf(4, 6) to '-'
        else          -> listOf(2, 4) to '/'
    }

    private fun normalizarAno(a: Int): Int {
        if (a in 1000..9999) return a
        // ano com 2 dígitos: assume 19xx/20xx
        return if (a <= 29) 2000 + a else 1900 + a
    }
}
