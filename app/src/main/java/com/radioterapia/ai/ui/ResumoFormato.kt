package com.radioterapia.ai.ui

/**
 * Modelo de string do cartão de resumo da finalização, separado em texto fixo
 * e lugares de valor.
 *
 * O cartão pinta o texto fixo num tom e o valor em outro. Para isso precisa
 * saber ONDE o valor entra em cada idioma, e só o modelo diz isso: em árabe o
 * valor pode vir antes do rótulo, em japonês o rótulo termina em dois-pontos de
 * largura cheia. Procurar o valor dentro do texto já formatado (indexOf) erra
 * quando o valor repete um pedaço do rótulo, e quando os algarismos saem em
 * outro sistema de numeração.
 *
 * PURO, sem Android: o teste de JVM lê os modelos dos doze strings.xml.
 */
object ResumoFormato {

    /** Um pedaço do modelo. */
    sealed class Segmento {
        /** Texto fixo, como está no modelo (com `%%` já trocado por `%`). */
        data class Literal(val texto: String) : Segmento()

        /** Lugar do argumento [indice] (1 = primeiro), como em `%1$s` ou `%2$d`. */
        data class Valor(val indice: Int) : Segmento()
    }

    /** `%1$s`, `%2$d`, `%s`, `%d` e o `%%` literal. */
    private val MARCADOR = Regex("""%(?:(\d+)\$)?([sd])|%%""")

    /**
     * O modelo em pedaços, na ordem em que aparecem.
     *
     * Marcador sem número (`%s`, `%d`) recebe o próximo índice da sua própria
     * contagem, que não se mistura com a dos numerados — a mesma regra do
     * `String.format`. Texto fixo vizinho sai num pedaço só.
     */
    fun segmentos(modelo: String): List<Segmento> {
        val saida = mutableListOf<Segmento>()
        val literal = StringBuilder()
        var sequencial = 0
        var pos = 0
        for (m in MARCADOR.findAll(modelo)) {
            literal.append(modelo, pos, m.range.first)
            pos = m.range.last + 1
            if (m.value == "%%") {
                literal.append('%')
                continue
            }
            if (literal.isNotEmpty()) {
                saida.add(Segmento.Literal(literal.toString()))
                literal.setLength(0)
            }
            val indice = m.groupValues[1].toIntOrNull() ?: ++sequencial
            saida.add(Segmento.Valor(indice))
        }
        literal.append(modelo, pos, modelo.length)
        if (literal.isNotEmpty()) saida.add(Segmento.Literal(literal.toString()))
        return saida
    }

    /**
     * O modelo já começa com o marcador de lista («•»)? As linhas de contagem
     * de fotos trazem o marcador no próprio texto; a de impressos não, e o
     * cartão a recua um marcador a mais para o texto dela alinhar com o das
     * outras.
     */
    fun temMarcador(modelo: String): Boolean = modelo.trimStart().startsWith("•")
}
