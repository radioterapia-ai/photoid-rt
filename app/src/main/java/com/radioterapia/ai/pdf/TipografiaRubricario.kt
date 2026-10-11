package com.radioterapia.ai.pdf

/**
 * Tipografia da página de rubricário: um corpo só para nomes e registros da
 * folha inteira, quebra de linha dentro da célula e piso de legibilidade.
 *
 * CORPO COMUM. O corpo é decidido uma vez por folha: o maior, entre
 * [CORPO_MIN] e [CORPO_MAX], em que TODO nome e TODO registro cabem em até
 * [MAX_LINHAS] linhas. Com um corpo por célula, nomes vizinhos sairiam em
 * tamanhos diferentes, ao lado de registros maiores que eles.
 *
 * O PISO vem do limite de caracteres do nome no cadastro
 * ([NOME_MAX_CARACTERES]): na Roboto do aparelho, um nome desse tamanho em
 * maiúsculas cabe em duas linhas a 8 pt na célula mais estreita (retrato, duas
 * colunas). Texto que nem no piso cabe — nome gravado antes do limite,
 * registro sem onde quebrar — leva a folha ao piso e sai com reticências.
 *
 * ONDE A LINHA QUEBRA: no espaço, depois de hífen ou barra, e entre letra e
 * algarismo ("CRM-SP" | "123456"), para que um registro digitado sem espaço
 * não seja partido no meio do número. Por caractere, só a palavra que sozinha
 * é mais larga que a linha.
 *
 * A medição vem de fora ([medir]) para a regra rodar sem Android: na ficha,
 * é o Paint do texto.
 */
internal object TipografiaRubricario {

    const val CORPO_MAX = 10f
    const val CORPO_MIN = 8f
    private const val PASSO = 0.25f
    const val MAX_LINHAS = 2
    const val ENTRELINHA = 1.15f

    /** Altura das maiúsculas da Roboto em fração do corpo (1456/2048). */
    const val ALTURA_MAIUSCULA = 0.711f

    /** Limite do nome no cadastro da pessoa do rubricário. Ver o KDoc do objeto. */
    const val NOME_MAX_CARACTERES = 48

    private const val RETICENCIAS = "…"

    /** Trecho entre dois pontos de quebra; [colado] = sem espaço antes dele. */
    private data class Pedaco(val texto: String, val colado: Boolean)

    /**
     * Quebra por pedaço em até [maxLinhas] linhas de no máximo [largura].
     * `null` quando não cabe — inclusive quando um pedaço sozinho é mais largo
     * que a linha. Texto vazio vira lista vazia.
     */
    fun quebrar(
        texto: String, largura: Float, medir: (String) -> Float, maxLinhas: Int = MAX_LINHAS
    ): List<String>? {
        val pedacos = pedacos(texto)
        if (pedacos.any { medir(it.texto) > largura }) return null
        val linhas = encher(pedacos, largura, medir)
        return if (linhas.size <= maxLinhas) linhas else null
    }

    /**
     * O corpo da folha: o maior, em passos de 0,25 pt, em que todos os [nomes]
     * cabem em [larguraNome] e todos os [registros] em [larguraRegistro], cada
     * um em até [MAX_LINHAS] linhas. Se nem o piso comporta algum, o piso.
     */
    fun corpoComum(
        nomes: List<String>, larguraNome: Float,
        registros: List<String>, larguraRegistro: Float,
        medir: (String, Float) -> Float,
    ): Float {
        var corpo = CORPO_MAX
        while (corpo >= CORPO_MIN) {
            val c = corpo
            val cabe = nomes.all { quebrar(it, larguraNome, { t -> medir(t, c) }) != null } &&
                registros.all { quebrar(it, larguraRegistro, { t -> medir(t, c) }) != null }
            if (cabe) return corpo
            corpo -= PASSO
        }
        return CORPO_MIN
    }

    /**
     * As linhas a desenhar no corpo já decidido. Cabendo, é a quebra de
     * [quebrar]. Não cabendo, a palavra mais larga que a linha é partida por
     * caractere, e das linhas que resultam ficam as [maxLinhas] primeiras, com
     * reticências no fim da última — depois de uma palavra inteira sempre que
     * ela tem mais de uma. A célula nunca transborda para a coluna vizinha.
     */
    fun linhas(
        texto: String, largura: Float, medir: (String) -> Float, maxLinhas: Int = MAX_LINHAS
    ): List<String> {
        quebrar(texto, largura, medir, maxLinhas)?.let { return it }
        val pedacos = pedacos(texto).flatMap { p ->
            if (medir(p.texto) <= largura) listOf(p)
            else fatiar(p.texto, largura, medir).mapIndexed { i, f ->
                Pedaco(f, colado = p.colado || i > 0)
            }
        }
        val todas = encher(pedacos, largura, medir)
        if (todas.size <= maxLinhas) return todas
        val ficam = todas.take(maxLinhas).toMutableList()
        var fim = ficam.last()
        while (fim.isNotEmpty() && medir(fim + RETICENCIAS) > largura) {
            val espaco = fim.lastIndexOf(' ')
            fim = if (espaco > 0) fim.substring(0, espaco) else semUltimo(fim)
        }
        ficam[ficam.lastIndex] = fim + RETICENCIAS
        return ficam
    }

    /**
     * Linhas de base de [n] linhas centradas na linha da tabela que começa em
     * [topo] e tem [altura]. O centro é o das maiúsculas, que é o que o olho
     * alinha; uma linha só cai onde sempre caiu.
     */
    fun linhasDeBase(n: Int, topo: Float, altura: Float, corpo: Float): List<Float> {
        if (n <= 0) return emptyList()
        val entre = corpo * ENTRELINHA
        val primeira = topo + altura / 2f - (n - 1) * entre / 2f + corpo * ALTURA_MAIUSCULA / 2f
        return List(n) { primeira + it * entre }
    }

    /** O texto cortado nos pontos onde a linha pode quebrar (ver o KDoc do objeto). */
    private fun pedacos(texto: String): List<Pedaco> {
        val saida = mutableListOf<Pedaco>()
        for (palavra in texto.trim().split(Regex("\\s+"))) {
            if (palavra.isEmpty()) continue
            var inicio = 0
            for (i in 1 until palavra.length) {
                val a = palavra[i - 1]
                val b = palavra[i]
                val quebra = ((a == '-' || a == '/') && i - 1 > inicio) ||
                    (a.isLetter() && b.isDigit()) || (a.isDigit() && b.isLetter())
                if (quebra) {
                    saida.add(Pedaco(palavra.substring(inicio, i), colado = inicio > 0))
                    inicio = i
                }
            }
            saida.add(Pedaco(palavra.substring(inicio), colado = inicio > 0))
        }
        return saida
    }

    /** Enche as linhas pedaço a pedaço até [largura]; pedaço maior que ela fica sozinho. */
    private fun encher(pedacos: List<Pedaco>, largura: Float, medir: (String) -> Float): List<String> {
        val linhas = mutableListOf<String>()
        var atual = ""
        for (p in pedacos) {
            val tentativa = when {
                atual.isEmpty() -> p.texto
                p.colado -> atual + p.texto
                else -> "$atual ${p.texto}"
            }
            if (atual.isEmpty() || medir(tentativa) <= largura) {
                atual = tentativa
            } else {
                linhas.add(atual)
                atual = p.texto
            }
        }
        if (atual.isNotEmpty()) linhas.add(atual)
        return linhas
    }

    /** Palavra mais larga que a linha, em fatias que cabem, por caractere. */
    private fun fatiar(palavra: String, largura: Float, medir: (String) -> Float): List<String> {
        val fatias = mutableListOf<String>()
        var atual = ""
        for (c in palavra) {
            val tentativa = atual + c
            if (atual.isEmpty() || grudado(c) || medir(tentativa) <= largura) {
                atual = tentativa
            } else {
                fatias.add(atual)
                atual = c.toString()
            }
        }
        if (atual.isNotEmpty()) fatias.add(atual)
        return fatias
    }

    /**
     * Caractere que não se separa do anterior: a segunda metade de um par
     * substituto e o acento combinante, que é como chega o nome colado de um
     * PDF com acentos decompostos.
     */
    private fun grudado(c: Char): Boolean =
        c.isLowSurrogate() || c.category == CharCategory.NON_SPACING_MARK

    /** [s] sem o último caractere, com o acento ou a metade que vier grudada nele. */
    private fun semUltimo(s: String): String {
        var n = s.length - 1
        while (n > 0 && grudado(s[n])) n--
        return s.substring(0, n)
    }
}
