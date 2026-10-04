package com.radioterapia.ai.scan

import java.text.Normalizer

/**
 * Rótulos de campo das etiquetas hospitalares, nos doze idiomas do app.
 *
 * O ML Kit reconhece o texto da etiqueta; este arquivo interpreta o texto já
 * reconhecido. A pergunta que ele responde é: esta palavra é o RÓTULO de um
 * campo ("MÉDICO:", "DATA DE NASCIMENTO", "PRONTUÁRIO") ou faz parte do valor?
 * E, quando é rótulo, de QUAL campo ([Familia]): o nome deste paciente, outra
 * pessoa (médico, mãe, acompanhante), o prontuário, o nascimento ou outro dado.
 * Saber a família é o que permite ao [EtiquetaParser] recusar a linha inteira
 * quando o rótulo apresenta OUTRA pessoa, cujo nome passaria em qualquer teste
 * de plausibilidade.
 *
 * O vocabulário é dado de código, e não strings.xml, porque não é texto de
 * interface: a etiqueta sai do sistema do hospital no idioma DELE, e o app
 * precisa reconhecer os rótulos de todos os idiomas ao mesmo tempo, qualquer
 * que seja o idioma do tablet.
 *
 * DOBRA. Vocabulário e linha lida passam pelo MESMO tokenizador e pela MESMA
 * [dobrar]: sem acento, maiúsculas, letras com traço (Ł polonês, Ø nórdico)
 * reduzidas, diacríticos árabes removidos. Por isso o vocabulário é escrito do
 * jeito natural, com acento e na escrita nativa, e os dois lados sempre
 * concordam.
 *
 * GUARDA: não reaproveita StorageLocal.removerAcentosMaiusculas. Aquela função
 * dá nome às pastas de paciente que já existem em campo e não pode mudar; além
 * disso troca ':' por espaço (e o ':' é justamente o que separa rótulo de nome)
 * e não dobra as letras polonesas.
 *
 * SEPARADOR. ':' (ou ';', '=', '#', '.', ou travessão com espaço) logo depois
 * do rótulo é a prova de que ele é rótulo. A vírgula NÃO é separador:
 * "NASCIMENTO, ANA PAULA" é nome na ordem sobrenome-primeiro. Hífen colado ao
 * token seguinte ("NASCIMENTO-SILVA") é sobrenome composto.
 *
 * AMBIGUIDADE. Alguns rótulos também são nomes reais: NASCIMENTO, SALA, WARD,
 * ALTER, ZIMMER, e toda sigla latina de até três letras (MAE, REG, MAT). Esses
 * só valem como rótulo com evidência: separador, nada depois, ou um número.
 * Sem ela, "NASCIMENTO ANA PAULA" e "MAE JEMISON" continuam sendo nomes. O til
 * de "MÃE" também é evidência: o prenome inglês Mae não tem til. E o sobrenome
 * comum no meio da linha ("JOHN WARD 00123456") só possui número curto de
 * sala, leito ou idade, nunca o prontuário nem uma data.
 *
 * APROXIMAÇÃO. O OCR erra letra ("NASSCIMETO", "PACIENTF"). A distância de
 * edição só é aceita para rótulo latino de uma palavra com 8 letras ou mais
 * (até 1 edição com 8-9 letras, até 2 com 10 ou mais) e só em dois contextos:
 * a linha inteira é aquela palavra, ou ela vem seguida de separador. Nunca na
 * primeira palavra de uma linha sem separador. Assim MEDICI (MEDICO tem 6
 * letras), MEDINA, SEXTON, REGIS, IDALINA, LEITAO e NASCIMBENI nunca são
 * tomados por rótulo. Os rótulos que ficam a uma edição de sobrenome real
 * (CARTELLA de CASTELLA, PAZIENTE de PAZIENTI, SERVICIO de SERVIDIO, PATIENTE
 * de PATIENCE, CONVENIO de CONVENTO) só casam exatos; o teste unitário confere
 * essa invariante contra uma lista fixa de nomes.
 *
 * GUARDA: nenhum `require` na inicialização. Um erro ao carregar a classe
 * derrubaria o scanner em campo; as invariantes do vocabulário vivem no teste.
 */
internal object RotulosEtiqueta {

    /**
     * De que campo é o rótulo. NOME = o nome deste paciente. PESSOA = outra
     * pessoa (médico, mãe, pai, responsável, acompanhante). REGISTRO =
     * prontuário do hospital. NASCIMENTO = data de nascimento. OUTRO = o resto.
     */
    enum class Familia { NOME, PESSOA, REGISTRO, NASCIMENTO, OUTRO }

    /**
     * Que tipo de valor o rótulo pode POSSUIR.
     *
     * Operadora (convênio, plano, seguro) possui NUMERO e TEXTO: a carteirinha
     * ou o nome da operadora. LOCAL é o de leito, quarto e enfermaria: quase
     * sempre número, às vezes o nome da unidade ("ONCOLOGIA", "UTI 12"). O
     * texto logo abaixo do rótulo sozinho pode ser dele, e por isso não é o
     * nome do paciente; mas LOCAL não é TEXTO, e texto sem número não prova
     * o alinhamento de uma tabela. Se provasse, o valor quebrado em duas
     * linhas passaria por alinhado debaixo do leito, e o número do leito
     * desceria para o prontuário.
     */
    enum class Tipo { TEXTO, NUMERO, DATA, LOCAL }

    /**
     * Um rótulo encontrado numa linha. [inicio] e [fimValor] são posições na
     * linha ORIGINAL, e [resto] sai dela também, então acento e caixa do valor
     * sobrevivem. [letras] conta as letras do rótulo já dobrado. [homonimo]
     * marca o rótulo que também é sobrenome ou palavra comum (a lista
     * explícita de ambíguos), e [separadorForte] o separador que não deixa
     * dúvida: dois-pontos, ponto e vírgula, '=' ou '#', e não ponto nem
     * travessão.
     */
    data class Achado(
        val familia: Familia,
        val tipos: Set<Tipo>,
        val ambiguo: Boolean,
        val comSeparador: Boolean,
        val aproximado: Boolean,
        val primeiroToken: Int,
        val tokensDoRotulo: Int,
        val inicio: Int,
        val fimValor: Int,
        val resto: String,
        val letras: Int,
        val homonimo: Boolean,
        val separadorForte: Boolean,
    ) {
        /** Há prova de que a palavra é rótulo, e não nome. */
        val comEvidencia: Boolean
            get() = !ambiguo || comSeparador || resto.isBlank() || resto.any { it.isDigit() }

        /**
         * O rótulo é dono do valor que vem depois dele. Rótulo ambíguo só
         * possui valor separado explicitamente ou um número colado a ele; e,
         * se o rótulo é de data, só uma data: "MARIA DO NASCIMENTO 0012345"
         * não faz do prontuário uma data de nascimento.
         *
         * Sobrenome comum no meio da linha, sem separador, só possui número
         * curto: em "JOHN WARD 00123456" e "ANA SALA 123456" o número é o
         * prontuário que vem depois do nome, e em "MARIA DATA 12/03/1960" a
         * data é o nascimento. Sala, leito e idade cabem em quatro dígitos.
         */
        val possuiValor: Boolean
            get() = when {
                !ambiguo || comSeparador -> true
                homonimo && familia == Familia.OUTRO && primeiroToken > 0 ->
                    RotulosEtiqueta.aceitaNumeroCurto(tipos, resto)
                else -> RotulosEtiqueta.aceitaValorAdjacente(tipos, resto)
            }
    }

    /** O rótulo que abre a linha (no primeiro token), o mais longo. */
    fun noInicio(linha: String): Achado? {
        val tokens = tokenizar(linha)
        if (tokens.isEmpty()) return null
        return casarEm(linha, tokens, 0)
    }

    /** Todos os rótulos da linha, em ordem de leitura, sem sobreposição. */
    fun todos(linha: String): List<Achado> {
        val tokens = tokenizar(linha)
        val achados = ArrayList<Achado>()
        var i = 0
        while (i < tokens.size) {
            val a = casarEm(linha, tokens, i)
            if (a == null) {
                i++
            } else {
                achados.add(a)
                i = a.primeiroToken + maxOf(1, a.tokensDoRotulo)
            }
        }
        return achados
    }

    /** A linha começa com rótulo, e há evidência de que ele é rótulo. */
    fun comecaComRotulo(linha: String): Boolean = noInicio(linha)?.comEvidencia == true

    /**
     * Valor colado a um rótulo ambíguo, sem separador, pertence a ele? Só se
     * começar com dígito; e, para rótulo que só possui data, só se for data.
     */
    fun aceitaValorAdjacente(tipos: Set<Tipo>, texto: String): Boolean {
        val t = texto.trimStart()
        if (t.firstOrNull()?.isDigit() != true) return false
        if (Tipo.DATA in tipos && Tipo.NUMERO !in tipos) return comecaComData(t)
        return true
    }

    /** Número de até quatro dígitos, que não é data, colado a rótulo de número. */
    fun aceitaNumeroCurto(tipos: Set<Tipo>, texto: String): Boolean {
        val t = texto.trimStart()
        return Tipo.NUMERO in tipos && NUMERO_CURTO.containsMatchIn(t) && !comecaComData(t)
    }

    /** O texto começa com algo que tem forma de data. */
    fun comecaComData(texto: String): Boolean = FORMAS_DE_DATA.any { it.containsMatchIn(texto) }

    /**
     * Sem acento, maiúsculas (Locale.ROOT: sem o i turco, e o ß alemão vira
     * SS), letras com traço reduzidas, ordinais de "Nº" removidos, e a escrita
     * árabe sem diacríticos e com as variantes de alef unificadas. Os
     * caracteres especiais vão por código, e não literais: vários deles são
     * invisíveis no editor.
     */
    fun dobrar(s: String): String {
        val semMarcas = Normalizer.normalize(s, Normalizer.Form.NFD).filterNot { it.code in 0x0300..0x036F }
        val maiusculas = Normalizer.normalize(semMarcas, Normalizer.Form.NFC).uppercase()
        val r = StringBuilder(maiusculas.length)
        for (ch in maiusculas) {
            when (ch.code) {
                0x0141 -> r.append('L')                         // Ł
                0x00D8 -> r.append('O')                         // Ø
                0x00C6 -> r.append("AE")                        // Æ
                0x0152 -> r.append("OE")                        // Œ
                0x0110 -> r.append('D')                         // Đ
                0x00BA, 0x00AA -> Unit                          // º ª
                0x200C, 0x200D -> Unit                          // ZWNJ, ZWJ
                0x0640, 0x0670 -> Unit                          // tatweel, alef sobrescrito
                in 0x064B..0x065F -> Unit                       // harakat
                0x0622, 0x0623, 0x0625 -> r.append(ALEF)        // alef com madda ou hamza
                0x0649 -> r.append(YA)                          // alef maqsura
                0x0629 -> r.append(HA)                          // ta marbuta
                else -> r.append(ch)
            }
        }
        return r.toString()
    }

    /** Distância de Damerau-Levenshtein restrita (alinhamento ótimo). */
    fun distancia(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val custo = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + custo)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
            }
        }
        return d[n][m]
    }

    /** Edições toleradas para um rótulo de [tamanho] letras; 0 = só exato. */
    fun limiteAproximacao(tamanho: Int): Int = when {
        tamanho >= 10 -> 2
        tamanho >= 8 -> 1
        else -> 0
    }

    /** Rótulos (dobrados) aceitos por aproximação. Exposto para o teste. */
    fun aproximaveis(): List<String> = APROXIMAVEIS.map { it.tokens[0] }

    /** Rótulo dobrado -> famílias em que aparece. Exposto para o teste. */
    fun familiasPorRotulo(): Map<String, Set<Familia>> =
        VOCABULARIO.groupBy { it.tokens.joinToString(" ") }
            .mapValues { (_, lista) -> lista.map { it.familia }.toSet() }

    /** Rótulo dobrado -> ambíguo ou não. Exposto para o teste. */
    fun ambiguidadePorRotulo(): Map<String, Boolean> =
        VOCABULARIO.associate { it.tokens.joinToString(" ") to it.ambiguo }

    /** Rótulo dobrado -> conjuntos de tipos com que aparece. Exposto para o teste. */
    fun tiposPorRotulo(): Map<String, Set<Set<Tipo>>> =
        VOCABULARIO.groupBy { it.tokens.joinToString(" ") }
            .mapValues { (_, lista) -> lista.map { it.tipos }.toSet() }

    // ------------------------------------------------------------------
    // Tokenizador
    // ------------------------------------------------------------------

    private class Token(val inicio: Int, val fim: Int, val texto: String) {
        val dobrado: String = dobrar(texto)
    }

    /**
     * Token = sequência de letras, marcas combinantes e dígitos. As marcas
     * entram para que palavras em bengali e árabe não se partam no sinal de
     * vogal. Os ordinais ficam fora: "Nº" vira o token "N".
     */
    private fun tokenizar(linha: String): List<Token> {
        val tokens = ArrayList<Token>()
        var inicio = -1
        var i = 0
        while (i < linha.length) {
            val cp = linha.codePointAt(i)
            if (ehDeToken(cp)) {
                if (inicio < 0) inicio = i
            } else if (inicio >= 0) {
                tokens.add(Token(inicio, i, linha.substring(inicio, i)))
                inicio = -1
            }
            i += Character.charCount(cp)
        }
        if (inicio >= 0) tokens.add(Token(inicio, linha.length, linha.substring(inicio)))
        return tokens
    }

    private fun ehDeToken(cp: Int): Boolean {
        if (cp == 0x00BA || cp == 0x00AA) return false              // º ª
        if (cp == 0x200C || cp == 0x200D) return true               // ZWNJ, ZWJ
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
    }

    /**
     * Escrita sem espaço entre palavras (chinês, japonês): o rótulo casa como
     * PREFIXO do token, porque "姓名王小明" chega sem espaço. Coreano, árabe e
     * bengali usam espaço e casam por palavra inteira.
     *
     * GUARDA: Character.UnicodeScript existe desde a API 24, que é o minSdk.
     * Baixar o minSdk exige trocar isto por faixas de código.
     */
    private fun ehEscritaSemEspaco(cp: Int): Boolean {
        val s = Character.UnicodeScript.of(cp)
        return s == Character.UnicodeScript.HAN ||
            s == Character.UnicodeScript.HIRAGANA ||
            s == Character.UnicodeScript.KATAKANA
    }

    private fun temEscritaSemEspaco(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (ehEscritaSemEspaco(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun ehLatino(s: String): Boolean = s.isNotEmpty() && s.all { it in 'A'..'Z' }

    private fun ehCompatNasc(dobrado: String): Boolean =
        ehLatino(dobrado) && (dobrado.startsWith("NASC") || COMPAT_NASC.containsMatchIn(dobrado))

    /** Marcas de direção (LRM, RLM, ALM), invisíveis, comuns em texto árabe. */
    private fun ehMarcaBidi(c: Char): Boolean = c.code == 0x200E || c.code == 0x200F || c.code == 0x061C

    // ------------------------------------------------------------------
    // Casamento
    // ------------------------------------------------------------------

    /** Entre as palavras de UM rótulo só cabe espaço, ponto, parênteses, apóstrofo, hífen e barra. */
    private fun lacunaInterna(lacuna: String): Boolean = lacuna.all {
        it.isWhitespace() || it in ".()'-/" || it.code == APOSTROFO_TIPOGRAFICO || ehMarcaBidi(it)
    }

    /** Entre o prefixo de número e o rótulo ("Nº PRONTUÁRIO", "N° DOSSIER"). */
    private fun lacunaDePrefixo(lacuna: String): Boolean = lacuna.all {
        it.isWhitespace() || it == '.' || it.code in ORDINAIS_E_GRAU || ehMarcaBidi(it)
    }

    private fun ehSeparador(lacuna: String, semProximo: Boolean): Boolean {
        val limpa = lacuna.filterNot { ehMarcaBidi(it) }
        // ")" de "MÉDICO(A):" e "]" ficam antes do separador de verdade.
        val g = limpa.trim().trimStart(')', ']').trim()
        if (g.isEmpty()) return false
        val c = g[0]
        if (c in SEPARADORES) return true
        if (c in TRACOS) return limpa.any { it.isWhitespace() } || semProximo
        return false
    }

    private fun casarEm(linha: String, tokens: List<Token>, i: Int): Achado? {
        val direto = casarRotulo(linha, tokens, i)
        if (tokens[i].dobrado !in PREFIXOS_DE_NUMERO || i + 1 >= tokens.size) return direto
        if (!lacunaDePrefixo(linha.substring(tokens[i].fim, tokens[i + 1].inicio))) return direto
        val seguinte = casarRotulo(linha, tokens, i + 1) ?: return direto
        if (direto != null && direto.tokensDoRotulo >= seguinte.tokensDoRotulo + 1) return direto
        val aceita = when (seguinte.familia) {
            Familia.REGISTRO -> true
            Familia.OUTRO -> Tipo.NUMERO in seguinte.tipos
            // "Nº UTENTE", "N° PATIENT": o NÚMERO do paciente é identificador,
            // nunca o nome; fica como dado numérico, fora do prontuário.
            Familia.NOME -> true
            else -> false
        }
        if (!aceita) return direto
        val numerico = seguinte.familia == Familia.NOME
        return seguinte.copy(
            familia = if (numerico) Familia.OUTRO else seguinte.familia,
            tipos = if (numerico) SO_NUMERO else seguinte.tipos,
            // O prefixo de número já é a evidência: "Nº REG" não é sobrenome.
            ambiguo = false,
            primeiroToken = i,
            tokensDoRotulo = seguinte.tokensDoRotulo + 1,
            inicio = tokens[i].inicio,
            letras = seguinte.letras + tokens[i].dobrado.length,
        )
    }

    private fun casarRotulo(linha: String, tokens: List<Token>, i: Int): Achado? {
        val t = tokens[i]

        // 1) Palavras inteiras, o rótulo mais longo primeiro.
        POR_PRIMEIRO_TOKEN[t.dobrado]?.let { candidatos ->
            for (r in candidatos) {
                if (i + r.tokens.size > tokens.size) continue
                var casa = true
                for (k in 1 until r.tokens.size) {
                    val anterior = tokens[i + k - 1]
                    val atual = tokens[i + k]
                    if (atual.dobrado != r.tokens[k] ||
                        !lacunaInterna(linha.substring(anterior.fim, atual.inicio))) {
                        casa = false
                        break
                    }
                }
                if (casa) {
                    // "MÃE" com til não é o prenome inglês Mae: o til é a evidência.
                    val ambiguo = r.ambiguo && !(r.tokens == listOf("MAE") && temTil(t.texto))
                    return montar(linha, tokens, i, i + r.tokens.size - 1, null,
                        r.familia, r.tipos, ambiguo, false, r.letras, r.homonimo)
                }
            }
        }

        // 2) Chinês e japonês: rótulo como prefixo do token.
        if (temEscritaSemEspaco(t.texto)) {
            for (r in PREFIXOS_SEM_ESPACO) {
                val rot = r.tokens[0]
                if (!t.dobrado.startsWith(rot)) continue
                val corte = corteNoOriginal(t, rot) ?: continue
                return montar(linha, tokens, i, i, corte, r.familia, r.tipos, r.ambiguo, false, r.letras, r.homonimo)
            }
            return null
        }

        // 3) Rótulo latino colado aos dígitos: "PRONTUARIO123456", "NASC12/03/1960".
        val primeiroDigito = t.texto.indexOfFirst { it.isDigit() }
        val colado = primeiroDigito > 0 &&
            t.texto.substring(0, primeiroDigito).all { it.isLetter() } &&
            t.texto.substring(primeiroDigito).all { it.isDigit() }
        if (colado) {
            val letrasColadas = dobrar(t.texto.substring(0, primeiroDigito))
            val corte = t.inicio + primeiroDigito
            if (ehLatino(letrasColadas)) {
                val r = POR_PRIMEIRO_TOKEN[letrasColadas]?.firstOrNull {
                    it.tokens.size == 1 && (Tipo.NUMERO in it.tipos || Tipo.DATA in it.tipos)
                }
                if (r != null) {
                    return montar(linha, tokens, i, i, corte, r.familia, r.tipos, r.ambiguo, false, r.letras, r.homonimo)
                }
                // Um dígito só, no fim, é mais provavelmente letra mal lida
                // ("NASCIMENT0") do que valor: segue para a aproximação.
                if (t.texto.length - primeiroDigito >= 2 && ehCompatNasc(letrasColadas)) {
                    compatNasc(linha, tokens, i, corte, letrasColadas.length)?.let { return it }
                }
            }
        } else if (ehCompatNasc(t.dobrado)) {
            compatNasc(linha, tokens, i, null, t.dobrado.length)?.let { return it }
        }

        // 5) Aproximação, só com a linha inteira sendo a palavra ou com separador.
        val proximo = tokens.getOrNull(i + 1)
        val sozinho = tokens.size == 1
        val separado = ehSeparador(linha.substring(t.fim, proximo?.inicio ?: linha.length), proximo == null)
        if (!sozinho && !separado) return null
        var melhor: Rotulo? = null
        var melhorDistancia = Int.MAX_VALUE
        for (r in APROXIMAVEIS) {
            val alvo = r.tokens[0]
            val limite = limiteAproximacao(alvo.length)
            if (kotlin.math.abs(alvo.length - t.dobrado.length) > limite) continue
            val dist = distancia(t.dobrado, alvo)
            if (dist in 1..limite && dist < melhorDistancia) {
                melhor = r
                melhorDistancia = dist
            }
        }
        val r = melhor ?: return null
        return montar(linha, tokens, i, i, null, r.familia, r.tipos, r.ambiguo, true, r.letras, r.homonimo)
    }

    /**
     * 4) Compatibilidade com a busca antiga por "nasc" no meio da palavra:
     * NASCIM, DTNASC, DATANASC. Só conta com separador ou com uma data
     * colada; sem isso não é rótulo nenhum, porque NASCIMBENI é sobrenome, e
     * sozinho numa linha ele tem que poder se juntar ao nome de cima.
     */
    private fun compatNasc(linha: String, tokens: List<Token>, i: Int, corte: Int?, letras: Int): Achado? {
        val a = montar(linha, tokens, i, i, corte, Familia.NASCIMENTO, SO_DATA, true, false, letras, false)
        return if (a.comSeparador || aceitaValorAdjacente(SO_DATA, a.resto)) a else null
    }

    /** Menor prefixo do token original cuja dobra é o rótulo. */
    private fun corteNoOriginal(t: Token, rotulo: String): Int? {
        var p = 0
        while (p < t.texto.length) {
            p += Character.charCount(t.texto.codePointAt(p))
            if (dobrar(t.texto.substring(0, p)) == rotulo) return t.inicio + p
        }
        return null
    }

    private fun montar(
        linha: String,
        tokens: List<Token>,
        i: Int,
        j: Int,
        corte: Int?,
        familia: Familia,
        tipos: Set<Tipo>,
        ambiguo: Boolean,
        aproximado: Boolean,
        letras: Int,
        homonimo: Boolean,
    ): Achado {
        val dentroDoToken = corte != null && corte < tokens[j].fim
        val comSeparador: Boolean
        val separadorForte: Boolean
        val fimValor: Int
        if (dentroDoToken) {
            comSeparador = false
            separadorForte = false
            fimValor = corte!!
        } else {
            val proximo = tokens.getOrNull(j + 1)
            val lacuna = linha.substring(tokens[j].fim, proximo?.inicio ?: linha.length)
            comSeparador = ehSeparador(lacuna, proximo == null)
            // Basta o separador forte aparecer na lacuna: em "D.N.:" o ponto vem antes.
            separadorForte = comSeparador && lacuna.any { it in SEPARADORES_FORTES }
            fimValor = proximo?.inicio ?: linha.length
        }
        return Achado(
            familia = familia,
            tipos = tipos,
            ambiguo = ambiguo,
            comSeparador = comSeparador,
            aproximado = aproximado,
            primeiroToken = i,
            tokensDoRotulo = j - i + 1,
            inicio = tokens[i].inicio,
            fimValor = fimValor,
            resto = linha.substring(fimValor).trim(),
            letras = letras,
            homonimo = homonimo,
            separadorForte = separadorForte,
        )
    }

    /** O token traz til ("MÃE"), composto ou decomposto. */
    private fun temTil(texto: String): Boolean =
        Normalizer.normalize(texto, Normalizer.Form.NFD).any { it.code == 0x0303 }

    // ------------------------------------------------------------------
    // Constantes.
    //
    // GUARDA: a ordem importa. Num `object`, as propriedades iniciam na ordem
    // em que aparecem, e o VOCABULARIO usa tudo o que vem antes dele; mover
    // uma constante para baixo dele dá NullPointerException ao carregar a
    // classe, e o scanner para de abrir.
    // ------------------------------------------------------------------

    private val ALEF = Char(0x0627)
    private val YA = Char(0x064A)
    private val HA = Char(0x0647)

    private const val APOSTROFO_TIPOGRAFICO = 0x2019
    private val ORDINAIS_E_GRAU = setOf(0x00B0, 0x00BA, 0x00AA)              // ° º ª

    /** Dois-pontos (também o de largura cheia), ponto e vírgula (também o árabe), '=', '#' e '.'. */
    private val SEPARADORES = setOf(':', Char(0xFF1A), ';', Char(0x061B), '=', '#', '.')

    /** Hífen, meia-risca e travessão. */
    private val TRACOS = setOf('-', Char(0x2013), Char(0x2014))

    /** Os separadores sem ponto: nunca aparecem dentro de um nome. */
    private val SEPARADORES_FORTES = setOf(':', Char(0xFF1A), ';', Char(0x061B), '=', '#')

    /** Até quatro dígitos e nenhum outro colado: sala, leito, idade. */
    private val NUMERO_CURTO = Regex("^\\d{1,4}(?!\\d)")

    private val SO_TEXTO = setOf(Tipo.TEXTO)
    private val SO_NUMERO = setOf(Tipo.NUMERO)
    private val SO_DATA = setOf(Tipo.DATA)
    private val NUMERO_DATA = setOf(Tipo.NUMERO, Tipo.DATA)
    private val NUMERO_TEXTO = setOf(Tipo.NUMERO, Tipo.TEXTO)
    private val NUMERO_LOCAL = setOf(Tipo.NUMERO, Tipo.LOCAL)

    private val COMPAT_NASC = Regex("^(DT|DATA|D)NASC")

    private val FORMAS_DE_DATA = listOf(
        Regex("^\\d{1,2} ?[/.\\-] ?\\d{1,2} ?[/.\\-] ?\\d{2,4}(?!\\d)"),
        Regex("^\\d{1,2} \\d{1,2} \\d{2,4}(?!\\d)"),
        Regex("^\\d{1,2}[/.\\- ]?\\p{L}{3,9}[/.\\- ]?\\d{2,4}(?!\\d)"),
        Regex("^\\d{4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,2}(?!\\d)"),
        Regex("^\\d{4} ?年"),
        // ddMMaaaa sem separador, com dia, mês e século plausíveis.
        Regex("^(0[1-9]|[12]\\d|3[01])(0[1-9]|1[0-2])(19|20)\\d{2}(?!\\d)"),
    )

    /** "Nº", "N°", "NR", "NUM", "NÚMERO", "NUMBER" antes do rótulo. */
    private val PREFIXOS_DE_NUMERO = setOf("N", "NO", "NR", "NRO", "NUM", "NUMERO", "NUMER", "NUMBER")

    /** Siglas curtas que nunca são nome: continuam valendo sem evidência. */
    private val NUNCA_AMBIGUOS = setOf("DR", "DRA", "PT", "PAC", "NOM", "SEX")

    /**
     * GUARDA: rótulos a uma edição de sobrenome ou prenome real só casam
     * exatos. CARTELLA/CASTELLA, CANTELLA, CARTELLI; PAZIENTE/PAZIENTI;
     * SERVICIO/SERVIDIO; PATIENTE/PATIENCE; CONVENIO/CONVENTO. Tirar um daqui
     * faz o sobrenome sozinho numa linha virar rótulo, e o nome sai truncado.
     */
    private val SEM_APROXIMACAO = setOf("CARTELLA", "PAZIENTE", "SERVICIO", "PATIENTE", "CONVENIO")

    private fun chave(texto: String): String = tokenizar(texto).joinToString(" ") { it.dobrado }

    /** Rótulos que também são nome de gente: só valem com evidência. */
    private val AMBIGUOS_EXPLICITOS: Set<String> = listOf(
        "NASCIMENTO", "DATA", "PLANO", "WARD", "ROOM", "CHART", "DOCTOR", "BORN", "PAYER",
        "MADRE", "CAMA", "SALA", "SERVICE", "ALTER", "ZIMMER", "BETT", "ARZT", "SESSO",
        "LEKARZ", "WIEK", "NÉ LE", "NÉE LE", "NÉ(E) LE", "D.N.", "د", "ডা",
    ).map { chave(it) }.toSet()

    private class Rotulo(texto: String, val familia: Familia, val tipos: Set<Tipo>) {
        val tokens: List<String> = tokenizar(texto).map { it.dobrado }
        val letras: Int = tokens.sumOf { it.length }
        val prefixo: Boolean = tokens.size == 1 && temEscritaSemEspaco(texto)
        val homonimo: Boolean = tokens.joinToString(" ") in AMBIGUOS_EXPLICITOS
        val ambiguo: Boolean = homonimo ||
            (tokens.size == 1 && tokens[0].length <= 3 && ehLatino(tokens[0]) && tokens[0] !in NUNCA_AMBIGUOS)
        val aproximavel: Boolean = tokens.size == 1 && tokens[0].length >= 8 &&
            ehLatino(tokens[0]) && tokens[0] !in SEM_APROXIMACAO
    }

    private fun nome(vararg t: String) = t.map { Rotulo(it, Familia.NOME, SO_TEXTO) }
    private fun pessoa(vararg t: String) = t.map { Rotulo(it, Familia.PESSOA, SO_TEXTO) }
    private fun pessoaNumero(vararg t: String) = t.map { Rotulo(it, Familia.PESSOA, SO_NUMERO) }
    private fun registro(vararg t: String) = t.map { Rotulo(it, Familia.REGISTRO, SO_NUMERO) }
    private fun nascimento(vararg t: String) = t.map { Rotulo(it, Familia.NASCIMENTO, SO_DATA) }
    private fun texto(vararg t: String) = t.map { Rotulo(it, Familia.OUTRO, SO_TEXTO) }
    private fun numero(vararg t: String) = t.map { Rotulo(it, Familia.OUTRO, SO_NUMERO) }
    private fun datas(vararg t: String) = t.map { Rotulo(it, Familia.OUTRO, SO_DATA) }
    private fun numeroOuData(vararg t: String) = t.map { Rotulo(it, Familia.OUTRO, NUMERO_DATA) }
    private fun operadora(vararg t: String) = t.map { Rotulo(it, Familia.OUTRO, NUMERO_TEXTO) }
    private fun lugar(vararg t: String) = t.map { Rotulo(it, Familia.OUTRO, NUMERO_LOCAL) }

    // ------------------------------------------------------------------
    // Vocabulário
    // ------------------------------------------------------------------

    private val VOCABULARIO: List<Rotulo> = listOf(
        // ---- Português ----
        nome("PACIENTE", "PCTE", "PAC", "NOME", "NOME DO PACIENTE", "NOME DA PACIENTE",
            "NOME DO(A) PACIENTE", "NOME COMPLETO", "NOME CIVIL", "UTENTE", "NOME DO UTENTE",
            "SOBRENOME", "NOME E SOBRENOME"),
        // PROF vale em todos os idiomas latinos: "Prof. Dr." é como hospital
        // universitário imprime o médico assistente, em português, alemão,
        // italiano e polonês. PROFª dobra para PROF.
        pessoa("MÉDICO", "MÉDICA", "MÉDICO(A)", "MÉD", "MÉDICO RESPONSÁVEL", "MÉDICO ASSISTENTE",
            "MÉDICO SOLICITANTE", "MÉDICO REQUISITANTE", "NOME DO MÉDICO", "DR", "DRA", "DR(A)",
            "PROF", "PROFA", "PROFESSOR", "PROFESSORA", "SOLICITANTE", "REQUISITANTE",
            "RESPONSÁVEL", "ACOMPANHANTE", "MÃE", "NOME DA MÃE", "NOME MÃE", "FILIAÇÃO", "PAI",
            "NOME DO PAI"),
        pessoaNumero("CRM"),
        registro("PRONTUÁRIO", "PRONT", "REGISTRO", "REG", "MATRÍCULA", "MAT", "PROCESSO",
            "PROCESSO CLÍNICO"),
        nascimento("NASCIMENTO", "DATA DE NASCIMENTO", "DATA NASCIMENTO", "DATA DE NASC",
            "DATA NASC", "DT NASC", "DT DE NASC", "DT NASCIMENTO", "NASC", "DN", "D.N."),
        texto("SEXO", "GÊNERO", "NOME SOCIAL", "SETOR", "UNIDADE", "ALERGIA", "ALERGIAS"),
        numero("IDADE", "CARTEIRINHA", "MATRÍCULA DO CONVÊNIO", "MATRÍCULA CONVÊNIO", "CARTÃO SUS",
            "CNS", "CARTÃO NACIONAL DE SAÚDE", "CPF", "RG", "NÚMERO DE UTENTE", "UTENTE Nº", "SNS",
            "TELEFONE", "TEL", "FONE", "CELULAR", "CEP"),
        operadora("CONVÊNIO", "PLANO", "PLANO DE SAÚDE"),
        lugar("LEITO", "QUARTO", "ENFERMARIA"),
        numeroOuData("ATENDIMENTO", "ATEND"),
        datas("ADMISSÃO", "DATA DE ADMISSÃO", "INTERNAÇÃO", "DATA DE INTERNAÇÃO", "ENTRADA",
            "DATA DE ENTRADA", "EMISSÃO", "DATA DE EMISSÃO", "IMPRESSÃO", "IMPRESSO EM", "VALIDADE",
            "DATA", "ADM"),

        // ---- Inglês ----
        nome("PATIENT", "PATIENT NAME", "PT", "NAME", "FULL NAME", "SURNAME", "LAST NAME",
            "FIRST NAME", "GIVEN NAME", "FORENAME"),
        pessoa("DOCTOR", "DR", "PHYSICIAN", "CONSULTANT", "ATTENDING", "ATTENDING PHYSICIAN",
            "REFERRING PHYSICIAN", "GP", "NEXT OF KIN", "NOK", "MOTHER", "MOTHER'S NAME"),
        registro("MRN", "MEDICAL RECORD", "MEDICAL RECORD NUMBER", "RECORD", "RECORD NUMBER",
            "RECORD NO", "HOSPITAL NUMBER", "HOSPITAL NO", "HOSP NO", "CHART", "CHART NUMBER",
            "CHART NO", "URN", "UR NUMBER", "PATIENT ID", "PATIENT NUMBER", "PATIENT NO"),
        nascimento("DOB", "D.O.B.", "DATE OF BIRTH", "BIRTH DATE", "BIRTHDATE", "BORN", "BIRTH"),
        texto("SEX", "GENDER", "LOCATION", "ALLERGY", "ALLERGIES"),
        numero("AGE", "POLICY", "POLICY NUMBER", "NHS NUMBER", "NHS NO", "NHS", "SSN",
            "SOCIAL SECURITY", "PHONE", "ACCOUNT", "FIN"),
        operadora("INSURANCE", "INSURER", "PAYER", "HEALTH PLAN"),
        lugar("WARD", "BED", "ROOM"),
        datas("ADMITTED", "ADMISSION", "ADMISSION DATE", "ADMIT DATE", "DATE", "PRINTED", "ISSUED"),
        numeroOuData("ENCOUNTER", "VISIT"),

        // ---- Espanhol ----
        nome("PACIENTE", "NOMBRE", "NOMBRES", "NOMBRE DEL PACIENTE", "NOMBRE COMPLETO",
            "NOMBRE Y APELLIDOS", "APELLIDOS Y NOMBRE", "APELLIDOS", "APELLIDO"),
        pessoa("MÉDICO", "MÉDICA", "MÉDICO TRATANTE", "FACULTATIVO", "DR", "DRA", "MADRE",
            "NOMBRE DE LA MADRE", "RESPONSABLE", "ACOMPAÑANTE"),
        registro("HISTORIA", "HISTORIA CLÍNICA", "HC", "NHC", "EXPEDIENTE", "REGISTRO"),
        nascimento("FECHA DE NACIMIENTO", "FECHA NACIMIENTO", "FECHA NAC", "F. NAC.", "FEC NAC",
            "NACIMIENTO"),
        texto("SEXO", "GÉNERO", "SERVICIO", "ALERGIAS"),
        numero("EDAD", "CIP", "DNI", "NSS"),
        operadora("ASEGURADORA", "OBRA SOCIAL", "PREVISIÓN", "EPS"),
        lugar("CAMA", "HABITACIÓN", "SALA"),
        datas("FECHA", "FECHA DE INGRESO", "INGRESO"),

        // ---- Francês ----
        nome("PATIENT", "PATIENTE", "NOM", "NOM DU PATIENT", "NOM COMPLET", "PRÉNOM",
            "NOM DE NAISSANCE", "NOM D'USAGE", "NOM MARITAL"),
        // "Pr" é a abreviação francesa de professeur; com duas letras, só vale
        // com evidência.
        pessoa("MÉDECIN", "MÉDECIN TRAITANT", "DOCTEUR", "DR", "PR", "PRESCRIPTEUR"),
        registro("DOSSIER", "IPP", "NIP"),
        nascimento("DATE DE NAISSANCE", "NAISSANCE", "NÉ LE", "NÉE LE", "NÉ(E) LE", "DDN"),
        texto("SEXE", "SERVICE", "UF", "ALLERGIES"),
        numero("ÂGE", "SÉCURITÉ SOCIALE", "NIR", "NDA"),
        operadora("MUTUELLE", "CAISSE"),
        lugar("LIT", "CHAMBRE"),
        datas("DATE D'ENTRÉE", "ENTRÉE", "ADMISSION", "DATE"),

        // ---- Alemão ----
        nome("PATIENT", "PATIENTIN", "NAME", "PATIENTENNAME", "VORNAME", "NACHNAME",
            "VOLLSTÄNDIGER NAME"),
        pessoa("ARZT", "ÄRZTIN", "BEHANDELNDER ARZT", "DR", "DR. MED.", "ZUWEISER"),
        registro("PATIENTENNUMMER", "PATIENTEN-NR", "PAT.-NR.", "PID", "AKTE"),
        nascimento("GEBURTSDATUM", "GEB.-DATUM", "GEBOREN", "GEBOREN AM", "GEB. AM", "GEB", "GEBURT"),
        texto("GESCHLECHT", "ALLERGIEN"),
        numero("ALTER", "VERSICHERTENNUMMER", "FALLNUMMER", "FALL-NR"),
        operadora("KRANKENKASSE", "KASSE", "VERSICHERUNG"),
        lugar("STATION", "ZIMMER", "BETT"),
        datas("AUFNAHME", "AUFNAHMEDATUM", "DATUM"),

        // ---- Italiano ----
        nome("PAZIENTE", "NOME", "COGNOME", "COGNOME E NOME", "NOME E COGNOME", "NOME DEL PAZIENTE",
            "ASSISTITO"),
        pessoa("MEDICO", "MEDICO CURANTE", "DOTT", "DOTT.SSA", "DOTTORESSA", "DOTTORE"),
        registro("CARTELLA", "CARTELLA CLINICA", "ID PAZIENTE", "CODICE PAZIENTE"),
        nascimento("DATA DI NASCITA", "DATA NASCITA", "NASCITA", "NATO IL", "NATA IL"),
        texto("SESSO", "REPARTO", "ALLERGIE"),
        numero("ETÀ", "CODICE FISCALE", "TESSERA SANITARIA", "NOSOLOGICO"),
        lugar("LETTO", "STANZA"),
        datas("DATA DI RICOVERO", "RICOVERO", "DATA"),

        // ---- Polonês ----
        nome("PACJENT", "PACJENTKA", "IMIĘ I NAZWISKO", "NAZWISKO I IMIĘ", "NAZWISKO", "IMIĘ"),
        pessoa("LEKARZ", "LEKARZ PROWADZĄCY", "LEK", "DR"),
        registro("NR HISTORII CHOROBY", "HISTORIA CHOROBY", "NR KSIĘGI GŁÓWNEJ", "NR KSIĘGI",
            "KSIĘGA GŁÓWNA", "ID PACJENTA", "NUMER PACJENTA"),
        nascimento("DATA URODZENIA", "UR", "URODZONY", "URODZONA"),
        texto("PŁEĆ", "ODDZIAŁ", "ALERGIE"),
        numero("WIEK", "PESEL"),
        operadora("UBEZPIECZENIE", "NFZ"),
        lugar("ŁÓŻKO", "SALA"),
        datas("DATA PRZYJĘCIA", "DATA"),

        // ---- Chinês (simplificado e tradicional; casa como prefixo) ----
        nome("姓名", "患者姓名", "病人姓名", "患者", "病人"),
        pessoa("医生", "医师", "主治医生", "主治医师", "醫生", "醫師", "主治醫師"),
        registro("病历号", "病案号", "住院号", "门诊号", "登记号", "ID号", "病歷號"),
        nascimento("出生日期", "出生年月", "生日"),
        texto("性别", "性別", "科室", "科別", "病区", "费别"),
        numero("年龄", "年齡", "床号", "床號"),
        operadora("医保"),
        lugar("床位", "病房"),
        datas("入院日期", "日期"),

        // ---- Japonês (casa como prefixo). フリガナ é a leitura em kana do
        //      nome: fica como OUTRO para que o nome em kanji seja a sugestão.
        //      カナ sozinho não entra: casaria com prenomes em katakana. ----
        nome("氏名", "患者名", "患者氏名", "患者", "名前"),
        pessoa("主治医", "主治医師", "担当医", "医師"),
        registro("患者ID", "患者番号", "カルテ番号", "診察券番号"),
        nascimento("生年月日"),
        texto("性別", "病棟", "フリガナ"),
        numero("年齢"),
        operadora("保険"),
        lugar("病室", "ベッド"),
        datas("入院日", "日付"),

        // ---- Coreano (palavra inteira: 주민등록번호 não é 등록번호) ----
        nome("성명", "이름", "환자명", "환자 성명", "환자 이름", "환자"),
        pessoa("주치의", "담당의", "담당의사", "의사"),
        registro("등록번호", "병록번호", "환자번호", "차트번호"),
        nascimento("생년월일"),
        texto("성별", "진료과", "병동"),
        numero("나이", "연령", "주민등록번호"),
        operadora("보험", "보험유형"),
        lugar("병실"),
        datas("입원일", "날짜"),

        // ---- Árabe ----
        nome("الاسم", "اسم", "اسم المريض", "المريض", "الاسم الكامل"),
        pessoa("الطبيب", "الطبيب المعالج", "الدكتور", "اسم الطبيب", "د"),
        registro("رقم الملف", "رقم الملف الطبي", "رقم السجل", "الرقم الطبي", "رقم المريض"),
        nascimento("تاريخ الميلاد", "الميلاد"),
        texto("الجنس", "القسم", "الجناح", "الحساسية"),
        numero("العمر", "السن", "رقم الهوية", "الرقم الوطني"),
        operadora("التأمين", "شركة التأمين"),
        lugar("الغرفة", "السرير"),
        datas("تاريخ الدخول", "التاريخ"),

        // ---- Bengali ----
        nome("নাম", "রোগীর নাম", "রোগী"),
        pessoa("ডাক্তার", "চিকিৎসক", "ডাঃ", "ডা"),
        registro("রেজিস্ট্রেশন নম্বর", "রেজি. নং", "নিবন্ধন নম্বর"),
        nascimento("জন্ম তারিখ", "জন্মতারিখ", "জন্ম"),
        texto("লিঙ্গ", "ওয়ার্ড"),
        numero("বয়স"),
        operadora("বীমা"),
        lugar("বেড", "শয্যা", "কক্ষ"),
        datas("ভর্তির তারিখ", "তারিখ"),
    ).flatten()

    /** Rótulos de palavra inteira, pelo primeiro token; o mais longo primeiro. */
    private val POR_PRIMEIRO_TOKEN: Map<String, List<Rotulo>> = VOCABULARIO
        .filter { !it.prefixo && it.tokens.isNotEmpty() }
        .groupBy { it.tokens[0] }
        .mapValues { (_, lista) ->
            lista.sortedWith(compareByDescending<Rotulo> { it.tokens.size }.thenByDescending { it.letras })
        }

    /** Rótulos chineses e japoneses, o mais longo primeiro. */
    private val PREFIXOS_SEM_ESPACO: List<Rotulo> = VOCABULARIO
        .filter { it.prefixo }
        .sortedByDescending { it.tokens[0].length }

    private val APROXIMAVEIS: List<Rotulo> = VOCABULARIO
        .filter { it.aproximavel }
        .distinctBy { it.tokens[0] }
}
