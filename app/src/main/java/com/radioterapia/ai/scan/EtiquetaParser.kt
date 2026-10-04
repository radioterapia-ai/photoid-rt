package com.radioterapia.ai.scan

import java.util.Locale

/**
 * Heurísticas simples para extrair informações de etiquetas hospitalares.
 * Não é perfeito — por isso o usuário sempre confirma e edita.
 *
 * O ML Kit reconhece o texto; este objeto interpreta o texto já reconhecido.
 * Os rótulos de campo de todos os idiomas, e a decisão de quando uma palavra
 * é rótulo e não nome, ficam em [RotulosEtiqueta].
 */
object EtiquetaParser {

    /**
     * Tenta detectar a linha mais provável de ser o nome do paciente.
     *
     * Em etiquetas hospitalares o nome geralmente vem na PRIMEIRA linha, e às
     * vezes quebra entre a primeira e a segunda. Por isso priorizamos:
     *
     *  1. Rótulo do NOME do paciente ("Paciente:", "Nome", "Patient",
     *     "Nombre"... em qualquer idioma), com ou sem separador, em qualquer linha
     *  2. A primeira linha que não começa com rótulo + a seguinte, se ambas
     *     parecerem ser nome
     *  3. Apenas essa linha, se for nome válido
     *  4. Fallback: melhor candidata em maiúsculas com 2+ palavras
     *
     * Linha que começa com rótulo de OUTRO campo (médico, mãe, prontuário,
     * nascimento, sexo, convênio...) nunca é nome, e a linha logo abaixo de um
     * rótulo de outra pessoa sozinho na linha também não. Na etiqueta impressa
     * em tabela, o valor de cada rótulo é achado pela posição ([lerColunas]).
     * Caixa vazia é melhor que o nome de outra pessoa: o técnico aceita com um
     * toque o que vier sugerido.
     */
    fun extrairNome(textoOcr: String): String {
        val brutas = textoOcr.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val linhas = brutas.map { cortarNoRotuloInterno(it) }

        if (linhas.isEmpty()) return ""

        // O corte leva junto o rótulo do fim da linha e o separador dele
        // ("MARIA DA SILVA MÃE:"), e com eles a prova de que o campo do nome
        // acabou ali. O rótulo fica guardado para as duas regras que dependem
        // dele: a linha de baixo pode ser o valor dele, e não se cola ao nome.
        val rotuloNoFim = List(linhas.size) { rotuloCortadoNoFim(brutas[it], linhas[it]) }

        val coluna = lerColunas(linhas)

        // Valor de rótulo de outra pessoa ou de outro campo de texto não é o
        // nome do paciente. Na tabela, é a linha que um rótulo que não é o do
        // nome pode possuir; fora dela, a linha logo abaixo de um rótulo
        // sozinho ("MÉDICO:" numa linha, o nome dele na seguinte) ou de um
        // rótulo que o corte tirou do fim da linha de cima.
        val deOutroRotulo = BooleanArray(linhas.size) { i ->
            val candidatos = coluna.candidatos[i]
            if (candidatos != null) {
                candidatos.any { it.familia != RotulosEtiqueta.Familia.NOME }
            } else {
                i > 0 && !coluna.emSequencia[i - 1] && (valorDoRotuloAcima(linhas[i - 1], linhas[i]) ||
                    rotuloNoFim[i - 1]?.let { possuiLinhaDeBaixo(it, linhas[i]) } == true)
            }
        }

        /*
            Critério 1: rótulo do NOME do paciente no início da linha.

            O rótulo sai ANTES de a linha ser aceita, e só o valor é avaliado.
            "PACIENTE MARIA DA SILVA" sem os dois-pontos passaria inteiro em
            pareceNome() — quatro palavras, nenhum dígito — e o nome iria para
            o cadastro e para a pasta em disco com "PACIENTE" na frente. O valor
            sai da linha original, com acento e caixa preservados.

            Só o rótulo do NOME é descolado. Depois de rótulo de médico, mãe ou
            acompanhante vem o nome real DE OUTRA PESSOA, que passa em qualquer
            teste de plausibilidade; essa linha é recusada inteira, abaixo. O
            mesmo vale para "NOME DO CÔNJUGE:": o rótulo do nome seguido de
            ligação, de um papel que o vocabulário não lista e de dois-pontos é
            rótulo de outra pessoa ([rotuloDeOutroCampo]).

            Na tabela, o valor do rótulo do nome é a linha que só ele pode
            possuir, e não a linha de baixo: ali está o rótulo seguinte.
         */
        for (i in linhas.indices) {
            val candidatos = coluna.candidatos[i]
            if (candidatos != null) {
                val soDoNome = candidatos.isNotEmpty() &&
                    candidatos.all { it.familia == RotulosEtiqueta.Familia.NOME }
                if (soDoNome && nomePlausivel(linhas[i])) {
                    return continuarNome(linhas[i], linhas, i + 1, deOutroRotulo)
                }
                continue
            }
            val r = RotulosEtiqueta.noInicio(linhas[i]) ?: continue
            if (r.familia != RotulosEtiqueta.Familia.NOME || !r.comEvidencia) continue
            if (r.resto.isNotBlank()) {
                if (!r.comSeparador && rotuloDeOutroCampo(r.resto)) continue
                if (nomePlausivel(r.resto)) return continuarNome(r.resto, linhas, i + 1, deOutroRotulo)
            } else if (!coluna.emSequencia[i] && i + 1 < linhas.size && nomePlausivel(linhas[i + 1])) {
                return continuarNome(linhas[i + 1], linhas, i + 2, deOutroRotulo)
            }
        }

        // Critérios 2 e 3: a primeira linha que NÃO começa com rótulo (nem é
        // valor de outro rótulo) faz o papel da linha 1. Linha aberta por
        // rótulo ambíguo de outra pessoa ou de outro campo de texto ("PAI JOSE
        // DA SILVA", "DOCTOR JOHN SMITH", "SESSO F") só serve quando não há
        // outra. A linha 2 é a vizinha FÍSICA dela, nunca uma linha pulada:
        // juntar linhas que não se tocam inventa um nome.
        //
        // Com o rótulo do nome numa coluna, o nome é um dos valores daquela
        // tabela. Quando a tabela recusa todos (desalinhada, dono incerto), a
        // linha de fora dela não toma o lugar: o cabeçalho do hospital em
        // cima, ou a linha que sobrou embaixo, viraria o nome do paciente.
        val elegivel = BooleanArray(linhas.size) { i ->
            !deOutroRotulo[i] && !RotulosEtiqueta.comecaComRotulo(linhas[i]) &&
                (!coluna.haTabelaDoNome || coluna.daTabelaDoNome[i])
        }
        val i0 = linhas.indices.firstOrNull { elegivel[it] && !abreComOutraPessoa(linhas[it]) }
            ?: linhas.indices.firstOrNull { elegivel[it] }
            ?: return ""
        val l1 = linhas[i0]
        val l1ParecesserNome = pareceNome(l1)
        if (l1ParecesserNome && terminaEmParticula(l1)) return continuarNome(l1, linhas, i0 + 1, deOutroRotulo)

        // Caso o nome esteja quebrado: linha 1 termina sem ponto/dois-pontos
        // e a linha 2 também parece ser texto puro de nome. A linha 2 não
        // pode ser valor de outro rótulo, nem abrir com rótulo ambíguo de
        // outra pessoa. Duas células da mesma tabela só se juntam quando as
        // duas são do rótulo do nome ("FIRST NAME:", "LAST NAME:"); célula de
        // dono desconhecido é outro campo, e não o resto do nome.
        val vizinha = i0 + 1
        val l2 = linhas.getOrElse(vizinha) { "" }
        val celulaSemDono = vizinha < linhas.size && coluna.bloco[i0] >= 0 &&
            coluna.bloco[i0] == coluna.bloco[vizinha] &&
            (coluna.candidatos[i0].isNullOrEmpty() || coluna.candidatos[vizinha].isNullOrEmpty())
        val l2ParecesserNome = vizinha < linhas.size && !deOutroRotulo[vizinha] && !celulaSemDono &&
            pareceNome(l2) && !abreComOutraPessoa(l2)

        // Heurística de quebra: linha 1 parece nome E linha 2 também parece nome
        // E a linha 1 NÃO termina com pontuação que indique fim de campo, nem
        // terminava num rótulo que o corte tirou: o nome acabou antes dele, e
        // a linha de baixo é de outro campo ("MARIA DA SILVA LEITO:" e
        // "ONCOLOGIA" não fazem "MARIA DA SILVA ONCOLOGIA").
        if (l1ParecesserNome && l2ParecesserNome && rotuloNoFim[i0] == null &&
            !l1.endsWith(":") && !l1.endsWith(".") && !l1.endsWith(",")) {
            // Verifica se juntar resulta em algo razoável
            val combinado = "$l1 $l2".replace(ESPACOS, " ").trim()
            if (combinado.length <= 80) return combinado
        }

        if (l1ParecesserNome) return l1

        // Critério 4 (fallback): maiúsculas, 2+ palavras, sem dígitos
        val candidatosMaiusculos = linhas.filterIndexed { i, linha ->
            val palavras = linha.split(ESPACOS)
            palavras.size >= 2 &&
                linha == linha.uppercase() &&
                !linha.any { it.isDigit() } &&
                linha.any { it.isLetter() } &&
                linha.length in 6..80 &&
                elegivel[i] &&
                !abreComOutraPessoa(linha)
        }
        if (candidatosMaiusculos.isNotEmpty()) {
            return candidatosMaiusculos.maxByOrNull { it.length } ?: ""
        }

        // Último fallback: linha mais longa só com letras. Sem nenhuma, caixa
        // vazia: a linha 1, nesse ponto, já falhou em pareceNome().
        val candidatosLetras = linhas.filterIndexed { i, linha ->
            val palavras = linha.split(ESPACOS)
            palavras.size >= 2 &&
                !linha.any { it.isDigit() } &&
                linha.length in 6..80 &&
                elegivel[i] &&
                !abreComOutraPessoa(linha)
        }
        return candidatosLetras.maxByOrNull { it.length } ?: ""
    }

    /**
     * A linha abre com rótulo ambíguo, sem evidência, de outra pessoa ou de
     * outro campo de texto: "PAI JOSE DA SILVA", "DOCTOR JOHN SMITH", "ARZT
     * HANS MULLER", "SESSO F". Pode ser sobrenome ("MAE JEMISON"), e por isso
     * a linha não é recusada; mas qualquer outra linha elegível vale mais que
     * ela, e ela nunca é colada ao nome de outra linha.
     */
    private fun abreComOutraPessoa(linha: String): Boolean {
        val r = RotulosEtiqueta.noInicio(linha) ?: return false
        if (!r.ambiguo || r.comEvidencia) return false
        return r.familia == RotulosEtiqueta.Familia.PESSOA ||
            (r.familia == RotulosEtiqueta.Familia.OUTRO && RotulosEtiqueta.Tipo.TEXTO in r.tipos)
    }

    /**
     * Nome que termina em partícula ("MARIA APARECIDA DO", "ANA PAULA DA") foi
     * quebrado pela etiqueta: nome nenhum termina em DO, DA, DOS ou DE. A linha
     * [j] é a continuação, mesmo sendo uma palavra só que também é rótulo
     * ambíguo (NASCIMENTO, SALA): rótulo de verdade não vem depois de
     * partícula solta. Linha que é valor de outro rótulo não continua nada, e
     * a que abre com rótulo ambíguo de outra pessoa ("PAI JOSE DA SILVA",
     * "DOCTOR JOHN SMITH") também não: DA, DE e DU fecham nomes chineses
     * ("LI DA"), e colar ali o nome do pai ou do médico é pior que deixar
     * o nome curto.
     */
    private fun continuarNome(valor: String, linhas: List<String>, j: Int, deOutroRotulo: BooleanArray): String {
        if (j >= linhas.size || deOutroRotulo[j] || !terminaEmParticula(valor)) return valor
        val seguinte = linhas[j]
        if (abreComOutraPessoa(seguinte)) return valor
        if (!pareceNome(seguinte) && !palavraAmbiguaSozinha(seguinte)) return valor
        val junto = "$valor $seguinte".replace(ESPACOS, " ").trim()
        return if (junto.length <= 80) junto else valor
    }

    private fun terminaEmParticula(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || !t.last().isLetter()) return false
        return RotulosEtiqueta.dobrar(t.split(ESPACOS).last()) in PARTICULAS
    }

    /** A linha é uma palavra só, de letras, que é rótulo ambíguo sem separador. */
    private fun palavraAmbiguaSozinha(linha: String): Boolean {
        if (linha.length < 2 || !linha.all { it.isLetter() }) return false
        val r = RotulosEtiqueta.noInicio(linha) ?: return false
        return r.ambiguo && !r.comSeparador && r.resto.isEmpty()
    }

    /**
     * Partículas de sobrenome que não terminam nome nenhum. LE e VAN ficam de
     * fora: também são o sobrenome vietnamita e um prenome, que podem fechar
     * a linha.
     */
    private val PARTICULAS = setOf(
        "DO", "DA", "DOS", "DAS", "DE", "DEL", "DELLA", "DELLE", "DELLO", "DEGLI", "DEI",
        "DI", "DU", "DES", "DER", "DEN", "VON", "LA", "E", "Y",
    )

    private val ESPACOS = Regex("\\s+")

    /**
     * Indica se a linha parece ser parte de um nome de pessoa.
     * Aceita 1 palavra (caso o nome esteja quebrado) ou mais.
     */
    private fun pareceNome(linha: String): Boolean {
        if (linha.isBlank() || linha.length < 2 || linha.length > 80) return false
        if (linha.any { it.isDigit() }) return false
        // Linha que COMEÇA com rótulo não é nome, e não só a linha que É um
        // rótulo. Sem isto, "PACIENTE" sozinho (o que sobra quando o OCR perde
        // os dois-pontos) ou "MEDICO FULANO DE TAL" viram o nome do paciente,
        // e a junção da linha 1 com a linha 2 cola o nome do médico ao dele.
        if (RotulosEtiqueta.comecaComRotulo(linha)) return false
        // Pelo menos 70% das letras
        val letras = linha.count { it.isLetter() }
        val total = linha.count { !it.isWhitespace() }
        if (total == 0) return false
        return letras.toDouble() / total >= 0.7
    }

    /**
     * Corta a linha no rótulo de outro campo que aparece DEPOIS do nome:
     * "MARIA DA SILVA SEXO: F" vira "MARIA DA SILVA". O OCR junta numa linha
     * só o que a etiqueta imprime lado a lado.
     *
     * Corta em rótulo com 4 letras ou mais ou com separador, e só se sobrarem
     * antes dele duas palavras de valor (uma, quando há separador, porque aí é
     * certo que o que vem depois não é nome). DR, DRA e MÃE, mesmo curtos e
     * sem ponto, cortam: abrem o nome de outra pessoa e não são nome de
     * ninguém. Rótulo ambíguo só corta com separador forte ("MÃE:", "DOB:",
     * "REG:"): sem ele, "ANA PAULA NASCIMENTO - 12/03/1960" não pode virar
     * "ANA PAULA". Rótulo do NOME nunca corta: "MARIA NOME DA SILVA".
     */
    private fun cortarNoRotuloInterno(linha: String): String {
        val inicial = RotulosEtiqueta.noInicio(linha)
        if (inicial != null) rotuloComposto(linha, inicial)?.let { return cortarNoRotuloInterno(it) }
        val k = if (inicial != null && inicial.comEvidencia) inicial.tokensDoRotulo else 0
        for (a in RotulosEtiqueta.todos(linha)) {
            if (a.familia == RotulosEtiqueta.Familia.NOME) continue
            if (a.ambiguo && !a.separadorForte) continue
            val outraPessoaCerta = a.familia == RotulosEtiqueta.Familia.PESSOA && !a.ambiguo
            if (a.letras < 4 && !a.comSeparador && !outraPessoaCerta) continue
            val palavrasAntes = a.primeiroToken - k
            if (palavrasAntes < (if (a.comSeparador) 1 else 2)) continue
            // 0x2013 e 0x2014: meia-risca e travessão.
            val cabeca = linha.substring(0, a.inicio)
                .trimEnd { it.isWhitespace() || it in ",;-/|([" || it.code == 0x2013 || it.code == 0x2014 }
            if (cabeca.isNotEmpty()) return cabeca
        }
        return linha
    }

    /**
     * O rótulo que fechava a linha [bruta] sem valor depois dele, quando o
     * corte mudou a linha ("MARIA DA SILVA MÃE:", "JOHN SMITH DOCTOR:"); null
     * nos outros casos. O valor dele quebrou para a linha de baixo, ou veio
     * da coluna ao lado. Rótulo do nome não conta: ele nunca corta a linha.
     */
    private fun rotuloCortadoNoFim(bruta: String, cortada: String): RotulosEtiqueta.Achado? {
        if (cortada == bruta) return null
        return RotulosEtiqueta.todos(bruta).lastOrNull()?.takeIf {
            it.primeiroToken > 0 && it.resto.isBlank() && it.familia != RotulosEtiqueta.Familia.NOME
        }
    }

    /**
     * Rótulo do nome seguido de até duas palavras de ligação e de outro
     * rótulo é um rótulo composto que o vocabulário não lista: "NOME DO
     * RESPONSÁVEL", "NOMBRE DEL MÉDICO", "NAME OF THE PHYSICIAN", "NOME
     * COMPLETO DO PACIENTE:". Quem diz de quem é o valor é o segundo rótulo,
     * e a linha passa a começar nele; sem isto, "DO RESPONSÁVEL JOÃO SOUZA"
     * sairia como nome do paciente. Devolve null quando não é o caso.
     *
     * Só vale sem separador depois do rótulo do nome (com ele, o valor é
     * explícito) e só com segundo rótulo não ambíguo: "NOME DO PLANO X"
     * recomeçaria em "PLANO X", que sem evidência passaria por nome. A
     * ligação vem de uma lista fechada, e não do tamanho da palavra: em
     * "PACIENTE ANA LUZ SEXO F", ANA LUZ é o nome, e não ligação.
     */
    private fun rotuloComposto(linha: String, inicial: RotulosEtiqueta.Achado): String? {
        if (inicial.familia != RotulosEtiqueta.Familia.NOME || inicial.comSeparador || !inicial.comEvidencia) return null
        val seguinte = RotulosEtiqueta.todos(linha).getOrNull(1) ?: return null
        if (seguinte.ambiguo || seguinte.inicio < inicial.fimValor) return null
        val ligacao = linha.substring(inicial.fimValor, seguinte.inicio)
        if (!ligacao.all { it.isLetter() || it.isWhitespace() || it == '.' || it == '\'' }) return null
        val palavras = ligacao.split(NAO_LETRA).filter { it.isNotEmpty() }
        if (palavras.size > 2 || palavras.any { RotulosEtiqueta.dobrar(it) !in CONECTORES }) return null
        return linha.substring(seguinte.inicio)
    }

    /**
     * O que vem depois do rótulo do nome é o resto de um rótulo de outra
     * pessoa: ligação, uma ou duas palavras e dois-pontos ("DO CÔNJUGE:",
     * "DEL FAMILIAR:", "OF SPOUSE:", "DO ESPOSO(A):"). O papel não precisa
     * estar no vocabulário, que não teria como listar todos.
     */
    private fun rotuloDeOutroCampo(resto: String): Boolean {
        val corte = resto.indexOfFirst { it == ':' || it.code == 0xFF1A }
        if (corte <= 0) return false
        val antes = resto.substring(0, corte)
        if (antes.any { it.isDigit() }) return false
        val palavras = antes.split(NAO_LETRA).filter { it.length >= 2 }.map { RotulosEtiqueta.dobrar(it) }
        return palavras.size in 2..3 && palavras[0] in CONECTORES
    }

    private val NAO_LETRA = Regex("[^\\p{L}]+")

    /** Palavras que ligam o rótulo do nome a outro rótulo, nos idiomas latinos. */
    private val CONECTORES = setOf(
        "DO", "DA", "DOS", "DAS", "DE", "DEL", "DELLA", "DELLO", "DEI", "DEGLI", "DI", "DU",
        "DES", "DER", "OF", "THE", "LA", "LE",
    )

    /** A linha é o valor de um rótulo sozinho na linha de cima ([possuiLinhaDeBaixo]). */
    private fun valorDoRotuloAcima(acima: String, linha: String): Boolean {
        val r = RotulosEtiqueta.noInicio(acima) ?: return false
        if (!r.comEvidencia || r.resto.isNotBlank()) return false
        return possuiLinhaDeBaixo(r, linha)
    }

    /**
     * O rótulo [r], sem valor na própria linha, pode possuir a [linha] de
     * baixo: rótulo de outra pessoa (médico, mãe, responsável), de outro campo
     * de texto (sexo, setor, alergia, nome social), de operadora (o nome do
     * convênio) ou de leito, quarto e enfermaria (o nome da unidade). Rótulo
     * do nome, de número e de data não entram: o do nome tem o critério 1, e
     * os outros não possuem texto ("PRONTUÁRIO" sozinho e o nome do paciente
     * na linha de baixo).
     */
    private fun possuiLinhaDeBaixo(r: RotulosEtiqueta.Achado, linha: String): Boolean {
        if (r.familia == RotulosEtiqueta.Familia.NOME) return false
        if (RotulosEtiqueta.Tipo.TEXTO !in r.tipos && RotulosEtiqueta.Tipo.LOCAL !in r.tipos) return false
        return !RotulosEtiqueta.comecaComRotulo(linha)
    }

    /**
     * Leitura da etiqueta impressa em tabela. [candidatos] diz, para cada
     * linha de valor da tabela, quais rótulos podem ser donos dela (null =
     * linha fora de tabela). [emSequencia] marca o rótulo sozinho que faz parte
     * de uma coluna de rótulos, e [bloco] agrupa as linhas de valor da mesma
     * tabela (-1 = nenhuma). [daTabelaDoNome] marca as linhas de valor das
     * tabelas cuja coluna tem o rótulo do nome, e [haTabelaDoNome] diz se
     * existe alguma com pelo menos um valor.
     */
    private class Colunas(n: Int) {
        val candidatos = arrayOfNulls<List<RotulosEtiqueta.Achado>>(n)
        val emSequencia = BooleanArray(n)
        val bloco = IntArray(n) { -1 }
        val daTabelaDoNome = BooleanArray(n)
        var haTabelaDoNome = false
    }

    /**
     * Quando a etiqueta imprime os rótulos numa coluna e os valores em outra,
     * o ML Kit pode entregar todos os rótulos primeiro e todos os valores
     * depois: "Paciente:", "Mãe:", "MARIA DA SILVA", "JOANA DA SILVA". A linha
     * abaixo do ÚLTIMO rótulo é o valor do PRIMEIRO, e tomá-la como valor do
     * rótulo de cima poria o nome da mãe na caixa do paciente.
     *
     * Dois ou mais rótulos sozinhos seguidos formam a coluna; ela começa num
     * rótulo com prova de que é rótulo (não ambíguo, ou com separador), porque
     * "NASCIMENTO" sozinho debaixo de um nome é mais provavelmente o sobrenome
     * que quebrou de linha. As linhas seguintes, até o próximo rótulo sozinho
     * com essa prova, são os valores: pela mesma razão, o rótulo ambíguo
     * sozinho e sem separador não encerra a lista. Se ele encerrasse, em
     * "Paciente:", "Mãe:", "MARIA APARECIDA DO", "NASCIMENTO", "JOANA DA
     * SILVA" a linha da mãe ficaria fora da tabela, livre para virar o nome.
     *
     * Se há pelo menos um valor por rótulo e cada valor tem a forma que o seu
     * rótulo possui, pela ordem, o valor j é do rótulo j e as linhas que
     * sobram ficam fora da tabela. O rótulo ambíguo sozinho entre os valores
     * não tem a forma de valor nenhum: é o sobrenome que quebrou ou o rótulo
     * de outra coluna, e nos dois casos a posição dos valores seguintes deixa
     * de valer. Sem valor por rótulo ou com forma que não confere, a tabela
     * está desalinhada (valor que faltou, valor quebrado em duas linhas), e
     * cada linha fica com todos os rótulos que podem ser donos dela: quem lê
     * só aceita a linha quando todos concordam, e recusa quando algum é de
     * outro campo. Na desalinhada, o rótulo ambíguo sozinho logo acima também
     * entra entre os donos possíveis, quando pode ser dono da linha: pode ser
     * rótulo de verdade ("DATA" e a data de impressão), e um dono a mais só
     * faz recusar mais.
     */
    private fun lerColunas(linhas: List<String>): Colunas {
        val c = Colunas(linhas.size)
        val sozinho = linhas.map { l -> RotulosEtiqueta.noInicio(l)?.takeIf { it.resto.isBlank() } }
        var i = 0
        while (i < linhas.size) {
            val primeiro = sozinho[i]
            if (primeiro == null || semProvaDeRotulo(primeiro)) {
                i++
                continue
            }
            var fim = i
            while (fim + 1 < linhas.size && sozinho[fim + 1] != null) fim++
            if (fim == i) {
                i++
                continue
            }
            val rotulos = sozinho.subList(i, fim + 1).filterNotNull()
            var depois = fim + 1
            while (depois < linhas.size && sozinho[depois].let { it == null || semProvaDeRotulo(it) }) depois++
            val valores = (fim + 1 until depois).toList()
            val alinhada = valores.size >= rotulos.size && rotulos.indices.all {
                sozinho[valores[it]] == null && cabe(rotulos[it], linhas[valores[it]])
            }
            val daTabela = if (alinhada) valores.take(rotulos.size) else valores
            if (daTabela.isNotEmpty() && rotulos.any { it.familia == RotulosEtiqueta.Familia.NOME }) {
                c.haTabelaDoNome = true
                for (v in daTabela) c.daTabelaDoNome[v] = true
            }
            for ((pos, v) in daTabela.withIndex()) {
                c.bloco[v] = i
                // Linha que abre com o próprio rótulo é dona de si mesma.
                if (RotulosEtiqueta.comecaComRotulo(linhas[v])) continue
                c.candidatos[v] = if (alinhada) {
                    listOf(rotulos[pos])
                } else {
                    // v - 1 > fim: o rótulo de cima está entre os valores, e
                    // não é o último da coluna.
                    val possiveis = rotulos + listOfNotNull(sozinho[v - 1]?.takeIf { v - 1 > fim })
                    possiveis.filter { cabe(it, linhas[v], donoPossivel = true) }
                }
            }
            for (k in i..fim) c.emSequencia[k] = true
            i = depois
        }
        return c
    }

    /** Rótulo ambíguo sem separador: sozinho na linha, pode ser o sobrenome que quebrou. */
    private fun semProvaDeRotulo(r: RotulosEtiqueta.Achado): Boolean = r.ambiguo && !r.comSeparador

    /**
     * O valor tem a forma que o rótulo possui: data para rótulo de data,
     * número para rótulo de número, texto para rótulo de texto. Os rótulos de
     * operadora (convênio, plano, seguro) declaram no vocabulário que possuem
     * número e texto. Linha que abre com outro rótulo só cabe debaixo de
     * rótulo da mesma família ("Dr. João" debaixo de "Médico:").
     *
     * Sem [donoPossivel], a forma tem de PROVAR a posição (o alinhamento), e
     * texto debaixo de idade, CPF, atendimento ou leito não prova nada: o
     * valor quebrado em duas linhas ("SUL AMERICA", "SAUDE") faria a tabela
     * desalinhada passar por alinhada, e cada valor seguinte cairia no rótulo
     * de baixo, o leito no prontuário. Só leito, quarto e enfermaria (LOCAL)
     * aceitam texto com número ("UTI 12").
     *
     * Com [donoPossivel], a pergunta é só se o rótulo PODE ser dono da linha,
     * para listar os donos na tabela desalinhada, onde um dono a mais só faz
     * recusar mais. Ali o rótulo de número de outro campo continua podendo
     * ter o texto (o nome da unidade debaixo do leito, ou a linha que não se
     * sabe de quem é), e o texto não vira nome por falta de dono.
     */
    private fun cabe(rotulo: RotulosEtiqueta.Achado, linha: String, donoPossivel: Boolean = false): Boolean {
        val proprio = RotulosEtiqueta.noInicio(linha)
        if (proprio != null && proprio.comEvidencia) return proprio.familia == rotulo.familia
        val t = linha.trimStart()
        return when {
            RotulosEtiqueta.comecaComData(t) -> RotulosEtiqueta.Tipo.DATA in rotulo.tipos
            t.firstOrNull()?.isDigit() == true -> RotulosEtiqueta.Tipo.NUMERO in rotulo.tipos
            RotulosEtiqueta.Tipo.TEXTO in rotulo.tipos -> true
            donoPossivel ->
                rotulo.familia == RotulosEtiqueta.Familia.OUTRO && RotulosEtiqueta.Tipo.NUMERO in rotulo.tipos
            else -> RotulosEtiqueta.Tipo.LOCAL in rotulo.tipos && t.any { it.isDigit() }
        }
    }

    /**
     * Valor plausível para o nome: até 80 caracteres, sem dígito, sem rótulo
     * no começo e com pelo menos duas palavras (e 4 caracteres). Em escrita
     * sem espaço entre nome e sobrenome (chinês, japonês, coreano) o nome
     * inteiro chega como uma palavra só, e bastam duas letras.
     */
    private fun nomePlausivel(s: String): Boolean {
        if (s.length > 80) return false
        if (s.any { it.isDigit() }) return false
        if (RotulosEtiqueta.comecaComRotulo(s)) return false
        if (temEscritaSemEspaco(s)) return s.count { it.isLetter() } >= 2
        if (s.length < 4) return false
        return s.split(Regex("\\s+")).size >= 2
    }

    /**
     * GUARDA: Character.UnicodeScript existe desde a API 24, que é o minSdk.
     * Baixar o minSdk exige trocar isto por faixas de código.
     */
    private fun temEscritaSemEspaco(s: String): Boolean = s.any {
        val sc = Character.UnicodeScript.of(it.code)
        sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA ||
            sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL
    }

    /**
     * Pedaço de linha e os rótulos que podem ser donos dele (vazio = de
     * ninguém). Fora de tabela há um dono só; na tabela desalinhada, podem
     * ser vários, e então o valor só é aceito quando todos concordam.
     */
    private class Trecho(val donos: List<RotulosEtiqueta.Achado>, val texto: String)

    /**
     * Parte o texto em trechos, cada um com o rótulo dono do valor.
     *
     * Numa linha, o trecho entre um rótulo e o seguinte pertence ao primeiro;
     * o que vem antes do primeiro rótulo não tem dono. Rótulo ambíguo sem
     * evidência de posse ("MARIA DO NASCIMENTO 0012345") não conta, e o texto
     * fica com quem vinha antes. Rótulo sozinho na linha ("PRONTUARIO",
     * "DATA DE NASCIMENTO") é dono do começo da linha seguinte, desde que ela
     * não comece com outro rótulo. Numa coluna de rótulos, quem decide o dono
     * é a posição ([lerColunas]).
     */
    private fun trechos(textoOcr: String): List<Trecho> {
        val linhas = textoOcr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val coluna = lerColunas(linhas)
        val resultado = ArrayList<Trecho>()
        var pendente: RotulosEtiqueta.Achado? = null
        for ((n, linha) in linhas.withIndex()) {
            val achados = RotulosEtiqueta.todos(linha).filter { it.possuiValor || it.resto.isBlank() }
            val primeiro = achados.firstOrNull()
            val comecaComRotulo = primeiro?.primeiroToken == 0
            val inicial = linha.substring(0, primeiro?.inicio ?: linha.length)
            val daTabela = coluna.candidatos[n]
            val p = pendente
            val donosIniciais = when {
                comecaComRotulo -> emptyList()
                daTabela != null -> daTabela
                p != null && (!p.ambiguo || RotulosEtiqueta.aceitaValorAdjacente(p.tipos, inicial)) -> listOf(p)
                else -> emptyList()
            }
            if (inicial.isNotBlank()) resultado.add(Trecho(donosIniciais, inicial))
            for ((k, a) in achados.withIndex()) {
                val fim = achados.getOrNull(k + 1)?.inicio ?: linha.length
                resultado.add(Trecho(listOf(a), if (a.fimValor < fim) linha.substring(a.fimValor, fim) else ""))
            }
            pendente = if (coluna.emSequencia[n]) null
                else achados.singleOrNull()?.takeIf { it.primeiroToken == 0 && it.resto.isBlank() }
        }
        return resultado
    }

    /**
     * Detecta data de nascimento. Aceita DD/MM/AAAA, DD-MM-AAAA, DD.MM.AAAA,
     * DD/MM/AA e mês por extenso ou abreviado (24-MAI-1964, 24 MAY 1964).
     *
     * Primeiro vale a data que pertence a um rótulo de nascimento, em qualquer
     * idioma do app ("Nasc:", "DN", "Date of birth", "Fecha de nacimiento",
     * "Geburtsdatum", "Data urodzenia"...), inclusive no começo da linha de
     * baixo quando o rótulo está sozinho. Sem ele, vale a primeira data que
     * não pertence a outro rótulo de data: admissão, emissão, impressão,
     * atendimento e o "DATA:" genérico têm datas reais e válidas, e é por isso
     * que a forma sozinha não basta para recusá-las.
     *
     * Só sai data que vira dd/MM/aaaa com ano entre 1900 e 2100. Mês que não
     * se reconhece ("24 ABC 1964") deixa a caixa vazia.
     */
    fun extrairDataNascimento(textoOcr: String): String {
        val canonica = Regex("[0-9]{2}/[0-9]{2}/[0-9]{4}")

        fun valida(bruta: String): String? {
            val c = normalizarData(bruta)
            return if (canonica.matches(c) && extrairAno(c) in 1900..2100) c else null
        }

        val ts = trechos(textoOcr)

        // 1) A data do rótulo de nascimento.
        for (t in ts) {
            if (t.donos.isEmpty() || t.donos.any { it.familia != RotulosEtiqueta.Familia.NASCIMENTO }) continue
            REGEX_DATA.findAll(t.texto).firstNotNullOfOrNull { valida(it.value) }?.let { return it }
        }

        // 2) A primeira data que não pertence a outro rótulo de data.
        for (t in ts) {
            val deOutraData = t.donos.any {
                it.familia != RotulosEtiqueta.Familia.NASCIMENTO && RotulosEtiqueta.Tipo.DATA in it.tipos
            }
            if (deOutraData) continue
            REGEX_DATA.findAll(t.texto).firstNotNullOfOrNull { valida(it.value) }?.let { return it }
        }
        return ""
    }

    /**
     * Numérica (24/05/1964) OU com mês por extenso/abreviado (24-MAI-1964),
     * formato comum em etiquetas de vários sistemas hospitalares.
     */
    private val REGEX_DATA = Regex(
        "\\b(\\d{1,2}[/.\\- ]\\d{1,2}[/.\\- ]\\d{2,4}" +
        "|\\d{1,2}[/.\\- ]?[A-Za-zÀ-ú]{3,9}[/.\\- ]?\\d{2,4})\\b")

    /**
     * Detecta o número de prontuário a partir de um texto OCR.
     *
     * O número tem que pertencer a um rótulo de prontuário ("Prontuário:",
     * "Pront.", "Reg", "Matrícula", "MRN", "IPP", "Historia clínica"... em
     * qualquer idioma do app), e então vale a primeira sequência de 4+
     * dígitos do trecho dele, tiradas antes as datas que estiverem ali
     * ([semDatas]): em "PRONTUARIO\n12/03/1960 0012345" e em
     * "MRN\n1960-03-12 00123456" o ano do nascimento não é o
     * prontuário. Sem esse rótulo, vale a maior sequência isolada
     * de 5+ dígitos que não pertence a rótulo nenhum de número, data ou
     * pessoa. CPF, CNS, PESEL, carteirinha do convênio, CRM do médico e
     * nascimento sem barras costumam ser justamente os maiores números da
     * etiqueta, e por isso a forma não basta. Número de atendimento também
     * fica de fora: muda a cada visita e partiria o paciente em duas pastas.
     * Quando o número do rótulo de prontuário é disputado por outro rótulo,
     * não há fallback: a caixa fica vazia.
     *
     * O valor busca o cadastro e, no histórico, abre direto as fotos quando
     * sobra um paciente só: número errado abre as fotos de outra pessoa.
     */
    fun extrairProntuario(textoOcr: String): String {
        val ts = trechos(textoOcr)

        // 1) Dono é rótulo de prontuário: primeiro número de 4+ dígitos do
        //    trecho, fora das datas.
        for (t in ts) {
            if (t.donos.isEmpty() || t.donos.any { it.familia != RotulosEtiqueta.Familia.REGISTRO }) continue
            val numero = Regex("\\d{4,}").find(semDatas(t.texto))
            if (numero != null) return numero.value
        }

        // Número que o rótulo de prontuário divide com outro rótulo (tabela
        // desalinhada: "Leito:", "Pront:", "1204", "0012345") mostra que o
        // prontuário está na etiqueta, sem que se saiba qual é. O maior número
        // sem dono, nesse caso, é outro campo (código de barras, CPF sem
        // rótulo), e a caixa fica vazia.
        val prontuarioIncerto = ts.any { t ->
            t.donos.any { it.familia == RotulosEtiqueta.Familia.REGISTRO } &&
                t.donos.any { it.familia != RotulosEtiqueta.Familia.REGISTRO } &&
                Regex("\\d{4,}").containsMatchIn(semDatas(t.texto))
        }
        if (prontuarioIncerto) return ""

        // 2) Fallback: maior número isolado de 5+ dígitos sem dono de número,
        //    data ou pessoa.
        val elegiveis = ts.filter { t ->
            t.donos.all { d ->
                d.familia == RotulosEtiqueta.Familia.NOME ||
                    (d.familia == RotulosEtiqueta.Familia.OUTRO &&
                        RotulosEtiqueta.Tipo.NUMERO !in d.tipos && RotulosEtiqueta.Tipo.DATA !in d.tipos)
            }
        }
        val numeros = elegiveis.flatMap { t ->
            Regex("\\b\\d{5,}\\b").findAll(t.texto).map { it.value }.toList()
        }
        return numeros.maxByOrNull { it.length } ?: ""
    }

    /**
     * O texto sem as datas, trocadas por espaço, para que o ano não passe por
     * número: as do dia primeiro (12/03/1960, 24-MAI-1964) e as do ano
     * primeiro (1960-03-12, 1960/03/12, 1960.03.12, 1960年), comuns nas
     * etiquetas em alemão, japonês, chinês e coreano.
     *
     * GUARDA: as do ano primeiro saem antes. Na ordem inversa, [REGEX_DATA]
     * lê "03-12 64" dentro de "1960-03-12 64" como data do dia primeiro com
     * ano de dois dígitos, o "1960-" que sobra já não é data, e o ano vira o
     * prontuário.
     */
    internal fun semDatas(texto: String): String =
        REGEX_DATA.replace(REGEX_DATA_ANO_PRIMEIRO.replace(texto, " "), " ")

    /**
     * Data do ano primeiro, com ano de 1900 a 2099 e mês e dia possíveis.
     * Sem essa conferência, um prontuário impresso em grupos ("1234-56-78")
     * sumiria inteiro como se fosse data.
     */
    private val REGEX_DATA_ANO_PRIMEIRO = Regex(
        "(?<!\\d)(19|20)\\d{2}[/.\\-](0?[1-9]|1[0-2])[/.\\-](0?[1-9]|[12]\\d|3[01])(?!\\d)" +
        "|(?<!\\d)(19|20)\\d{2} ?年")

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
        // GUARDA: Locale.ROOT nos três format. O idioma do app vira o Locale
        // padrão, e em bengali ou árabe "%02d" sai com os algarismos do idioma
        // ("12/০৩/1960"), que o formulário de nascimento não reconhece.
        // Ano com 2 dígitos: 30 → 2030, 64 → 1964 (janela usual de nascimento).
        if (ano.length == 2) {
            val n = ano.toIntOrNull() ?: return bruto
            ano = if (n <= 30) "20%02d".format(Locale.ROOT, n) else "19%02d".format(Locale.ROOT, n)
        }
        return "%s/%02d/%s".format(Locale.ROOT, dia, mes, ano)
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

