package com.radioterapia.ai.scan

/**
 * Heurísticas simples para extrair informações de etiquetas hospitalares.
 * Não é perfeito — por isso o usuário sempre confirma e edita.
 */
object EtiquetaParser {

    /**
     * Tenta detectar a linha mais provável de ser o nome do paciente.
     *
     * Em etiquetas hospitalares brasileiras o nome geralmente vem na PRIMEIRA linha,
     * e às vezes quebra entre a primeira e segunda. Por isso priorizamos:
     *
     *  1. Rótulo explícito ("Paciente:", "Nome:", etc) - se existir
     *  2. Linha 1 + linha 2 juntas, se ambas parecerem ser nome
     *  3. Apenas a linha 1, se for nome válido
     *  4. Fallback: melhor candidata em maiúsculas com 2+ palavras
     */
    fun extrairNome(textoOcr: String): String {
        val linhas = textoOcr.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (linhas.isEmpty()) return ""

        // Critério 1: rótulo explícito (raro mas mais confiável)
        for (i in linhas.indices) {
            val l = linhas[i].lowercase()
            if (Regex("^(paciente|nome|pcte|pac)\\s*[:\\-]").containsMatchIn(l)) {
                val depoisDoSeparador = linhas[i].substringAfter(":").substringAfter("-").trim()
                if (ehNomeValido(depoisDoSeparador)) return depoisDoSeparador
                if (i + 1 < linhas.size && ehNomeValido(linhas[i + 1])) return linhas[i + 1]
            }
        }

        // Critério 2 e 3: priorizar linha 1 (etiquetas hospitalares)
        val l1 = linhas[0]
        val l2 = if (linhas.size > 1) linhas[1] else ""

        // Caso o nome esteja quebrado: linha 1 termina sem ponto/dois-pontos
        // e a linha 2 também parece ser texto puro de nome
        val l1ParecesserNome = pareceNome(l1)
        val l2ParecesserNome = pareceNome(l2)

        // Heurística de quebra: linha 1 parece nome E linha 2 também parece nome
        // E a linha 1 NÃO termina com pontuação que indique fim de campo
        if (l1ParecesserNome && l2ParecesserNome &&
            !l1.endsWith(":") && !l1.endsWith(".") && !l1.endsWith(",")) {
            // Verifica se juntar resulta em algo razoável
            val combinado = "$l1 $l2".replace(Regex("\\s+"), " ").trim()
            if (combinado.length <= 80) return combinado
        }

        if (l1ParecesserNome) return l1

        // Critério 4 (fallback): maiúsculas, 2+ palavras, sem dígitos
        val candidatosMaiusculos = linhas.filter { linha ->
            val palavras = linha.split(Regex("\\s+"))
            palavras.size >= 2 &&
                linha == linha.uppercase() &&
                !linha.any { it.isDigit() } &&
                linha.any { it.isLetter() } &&
                linha.length in 6..80
        }
        if (candidatosMaiusculos.isNotEmpty()) {
            return candidatosMaiusculos.maxByOrNull { it.length } ?: ""
        }

        // Último fallback: linha mais longa só com letras
        val candidatosLetras = linhas.filter { linha ->
            val palavras = linha.split(Regex("\\s+"))
            palavras.size >= 2 &&
                !linha.any { it.isDigit() } &&
                linha.length in 6..80
        }
        return candidatosLetras.maxByOrNull { it.length } ?: l1
    }

    /**
     * Indica se a linha parece ser parte de um nome de pessoa.
     * Aceita 1 palavra (caso o nome esteja quebrado) ou mais.
     */
    private fun pareceNome(linha: String): Boolean {
        if (linha.isBlank() || linha.length < 2 || linha.length > 80) return false
        if (linha.any { it.isDigit() }) return false
        // Pelo menos 70% das letras
        val letras = linha.count { it.isLetter() }
        val total = linha.count { !it.isWhitespace() }
        if (total == 0) return false
        return letras.toDouble() / total >= 0.7
    }

    /**
     * Detecta data de nascimento. Aceita formatos comuns no Brasil:
     *  DD/MM/AAAA, DD-MM-AAAA, DD.MM.AAAA, DD/MM/AA
     *
     * Quando há várias datas na etiqueta (ex: data de emissão + nascimento),
     * tenta priorizar a que vem após rótulos como "Nasc:" ou "Data Nasc:".
     */
    fun extrairDataNascimento(textoOcr: String): String {
        // Numérica (24/05/1964) OU com mês por extenso/abreviado (24-MAI-1964),
        // formato comum em etiquetas de vários sistemas hospitalares.
        val regexData = Regex(
            "\\b(\\d{1,2}[/.\\- ]\\d{1,2}[/.\\- ]\\d{2,4}" +
            "|\\d{1,2}[/.\\- ]?[A-Za-zÀ-ú]{3,9}[/.\\- ]?\\d{2,4})\\b")

        val linhas = textoOcr.lines().map { it.trim() }

        // Procura linha com rótulo de nascimento
        for (linha in linhas) {
            val l = linha.lowercase()
            if (l.contains("nasc") || l.contains("dt.nasc") || l.contains("nascimento")) {
                val match = regexData.find(linha)
                if (match != null) return normalizarData(match.value)
            }
        }

        // Caso não tenha rótulo, retorna a primeira data encontrada com ano > 1900
        for (linha in linhas) {
            for (match in regexData.findAll(linha)) {
                val candidata = normalizarData(match.value)
                val ano = extrairAno(candidata)
                if (ano in 1900..2100) return candidata
            }
        }
        return ""
    }

    /**
     * Detecta número de prontuário a partir de um texto OCR.
     * Procura por rótulos como "Prontuário:", "Reg:", "Mat:", "Matrícula:"
     * ou padrões numéricos isolados de 5+ dígitos.
     */
    fun extrairProntuario(textoOcr: String): String {
        val linhas = textoOcr.lines().map { it.trim() }
        val rotulos = listOf("prontuário", "prontuario", "reg", "matrícula", "matricula", "mat", "registro")

        for (linha in linhas) {
            val l = linha.lowercase()
            for (rotulo in rotulos) {
                if (l.contains("$rotulo:") || l.contains("$rotulo ") || l.startsWith(rotulo)) {
                    val numero = Regex("\\d{4,}").find(linha)?.value
                    if (!numero.isNullOrBlank()) return numero
                }
            }
        }

        // Fallback: maior sequência numérica isolada
        val numeros = Regex("\\b\\d{5,}\\b").findAll(textoOcr).map { it.value }.toList()
        return numeros.maxByOrNull { it.length } ?: ""
    }

    private fun ehNomeValido(s: String): Boolean {
        if (s.length < 4 || s.length > 80) return false
        if (s.any { it.isDigit() }) return false
        val palavras = s.split(Regex("\\s+"))
        return palavras.size >= 2
    }

    /** Meses por extenso/abreviados aceitos (PT, EN e ES). */
    private val MESES = mapOf(
        "JAN" to 1, "ENE" to 1,
        "FEV" to 2, "FEB" to 2,
        "MAR" to 3,
        "ABR" to 4, "APR" to 4,
        "MAI" to 5, "MAY" to 5,
        "JUN" to 6,
        "JUL" to 7,
        "AGO" to 8, "AUG" to 8,
        "SET" to 9, "SEP" to 9,
        "OUT" to 10, "OCT" to 10,
        "NOV" to 11,
        "DEZ" to 12, "DIC" to 12, "DEC" to 12)

    /**
     * Uniformiza para dd/MM/yyyy. Além de trocar separadores, converte mês por
     * extenso: "24-MAI-1964" e "24 MAY 1964" viram "24/05/1964". Sem isto, o
     * OCR até lia a data da etiqueta, mas ela não chegava ao formulário.
     */
    private fun normalizarData(s: String): String {
        val bruto = s.trim().replace(".", "/").replace("-", "/").replace(" ", "/")
        val partes = bruto.split("/").filter { it.isNotBlank() }
        if (partes.size != 3) return bruto
        val dia = partes[0].padStart(2, '0')
        val mesTxt = partes[1]
        val mes = mesTxt.toIntOrNull()
            ?: MESES[removerAcentos(mesTxt).uppercase().take(3)]
            ?: return bruto
        var ano = partes[2]
        // Ano com 2 dígitos: 30 → 2030, 64 → 1964 (janela usual de nascimento).
        if (ano.length == 2) {
            val n = ano.toIntOrNull() ?: return bruto
            ano = if (n <= 30) "20%02d".format(n) else "19%02d".format(n)
        }
        return "%s/%02d/%s".format(dia, mes, ano)
    }

    private fun removerAcentos(t: String): String {
        val com = "ÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇáàâãäéèêëíìîïóòôõöúùûüç"
        val sem = "AAAAAEEEEIIIIOOOOOUUUUCaaaaaeeeeiiiiooooouuuuc"
        var r = t
        for (i in com.indices) r = r.replace(com[i], sem[i])
        return r
    }

    /** Sexo na etiqueta: "SEXO: F", "SEXO M", "FEM", "MASC" → "M"/"F" ou "". */
    fun extrairSexo(textoOcr: String): String {
        val m = Regex("SEXO\\s*[:\\-]?\\s*(MASC\\w*|FEM\\w*|M|F)\\b",
            RegexOption.IGNORE_CASE).find(textoOcr) ?: return ""
        val v = m.groupValues[1].uppercase()
        return if (v.startsWith("F")) "F" else "M"
    }

    /** Médico na etiqueta: valor após "MÉDICO:" ou nome após "DR./DRA.". */
    fun extrairMedico(textoOcr: String): String {
        val m1 = Regex("M[ÉE]DICO\\s*[:\\-]?\\s*([A-ZÀ-Úa-zà-ú][^\\n\\r]{2,45})",
            RegexOption.IGNORE_CASE).find(textoOcr)
        if (m1 != null) return m1.groupValues[1].trim().trimEnd('.', ',', ';')
        val m2 = Regex("\\b(DR[A]?\\.?\\s+[A-ZÀ-Ú][^\\n\\r]{2,40})",
            RegexOption.IGNORE_CASE).find(textoOcr)
        return m2?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', ';') ?: ""
    }

    private fun extrairAno(data: String): Int {
        val partes = data.split("/")
        if (partes.size < 3) return 0
        var ano = partes[2].toIntOrNull() ?: return 0
        if (ano < 100) ano += if (ano > 30) 1900 else 2000
        return ano
    }
}

