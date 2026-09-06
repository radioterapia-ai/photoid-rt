package com.radioterapia.ai.csv

import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import java.io.File
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * Lê um arquivo CSV detectando automaticamente o separador.
 *
 * Heurística: lê as primeiras 5 linhas e conta a frequência de candidatos
 * (`,` `;` `\t` `|`). O separador escolhido é aquele que aparece com a mesma
 * frequência (não-zero) em todas as linhas. Se houver empate, prefere
 * ponto-e-vírgula > vírgula > tab > pipe.
 */
object CsvImporter {

    private val candidatosSeparador = listOf(';', ',', '\t', '|')

    data class ResultadoParse(
        val sucesso: Boolean,
        val linhas: List<List<String>>,
        val separadorUsado: Char,
        val mensagemErro: String? = null
    )

    fun parsear(arquivo: File, primeiraLinhaEhCabecalho: Boolean): ResultadoParse {
        if (!arquivo.exists() || arquivo.length() == 0L) {
            return ResultadoParse(false, emptyList(), ',', "Arquivo não existe ou vazio")
        }
        val separador = detectarSeparador(arquivo)
        return try {
            val parser = CSVParserBuilder().withSeparator(separador).build()
            InputStreamReader(arquivo.inputStream(), Charsets.UTF_8).use { reader ->
                val csvReader = CSVReaderBuilder(reader).withCSVParser(parser).build()
                val todas = csvReader.readAll().map { it.toList() }
                val dados = if (primeiraLinhaEhCabecalho && todas.isNotEmpty()) todas.drop(1) else todas
                ResultadoParse(true, dados, separador)
            }
        } catch (e: Exception) {
            ResultadoParse(false, emptyList(), separador, e.message)
        }
    }

    /**
     * Lê primeiras 5 linhas como texto e elege o separador.
     * Se o arquivo tem aspas ou cells com vírgula dentro, ainda funciona razoavelmente.
     */
    private fun detectarSeparador(arquivo: File): Char {
        val primeirasLinhas = mutableListOf<String>()
        try {
            arquivo.bufferedReader(Charsets.UTF_8).use { reader ->
                var linha = reader.readLine()
                var n = 0
                while (linha != null && n < 5) {
                    if (linha.isNotBlank()) {
                        primeirasLinhas.add(linha)
                        n++
                    }
                    linha = reader.readLine()
                }
            }
        } catch (e: Exception) { return ',' }

        if (primeirasLinhas.isEmpty()) return ','

        val pontuacoes = candidatosSeparador.map { sep ->
            sep to pontuarSeparador(primeirasLinhas, sep)
        }
        // Maior pontuação ganha. Empate: ordem de candidatos (; primeiro)
        return pontuacoes.maxByOrNull { it.second }?.first ?: ','
    }

    private fun pontuarSeparador(linhas: List<String>, sep: Char): Int {
        val contagens = linhas.map { it.count { c -> c == sep } }
        if (contagens.any { it == 0 }) return 0
        // Premia consistência: se todas as linhas têm a mesma quantidade de delimitadores,
        // é um forte sinal de que esse é o separador correto.
        val media = contagens.average()
        val variancia = contagens.map { (it - media) * (it - media) }.average()
        // Pontuação: contagem média alta é bom, variância baixa é bom.
        return (media * 100 - variancia * 50).toInt()
    }

    fun normalizarParaBusca(s: String): String {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .trim()
    }
}
