package com.radioterapia.ai.util

import com.radioterapia.ai.session.SessionManager
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Nome de todo arquivo que o app grava na pasta do paciente, e a leitura de
 * volta desse nome. Fonte única: quem grava monta aqui, quem lê classifica aqui.
 *
 * ESQUEMA
 *
 *     <INICIAIS>_<TIPO>[_TRAT][_NS<n>]_<DD>_<MMM>_<AAAA>_<HH>_<MM>_<SS>_<CONTAGEM>[_ARQ<ms>][_ORIGINAL].<ext>
 *
 *     M_A_S_POS_03_SET_2026_14_22_05_3.jpg
 *     M_A_S_POS_TRAT_NS1_10_OUT_2026_09_00_41_7.jpg
 *     M_A_S_FSIM_NS1_10_OUT_2026_09_01_02_1.pdf
 *     M_A_S_POS_03_SET_2026_14_22_05_3_ORIGINAL.jpg            quadro cheio da foto acima
 *     M_A_S_ROST_03_SET_2026_14_22_05_1_ARQ1727960000000.jpg   foto arquivada
 *
 * Radical só com `[A-Z0-9_]`, um único ponto antes da extensão, extensão em
 * minúsculas. A identidade do paciente fica no nome da PASTA; o arquivo guardado
 * leva só as iniciais. As cópias entregues fora da pasta do paciente (pen-drive,
 * pasta da impressora, compartilhar) levam o nome completo em ASCII, por
 * [montarEntrega] e [entregaDoGuardado]: ali não há pasta em volta dizendo de
 * quem é a foto. Nome sem letra latina sai com um código fixo do paciente
 * ([nomeEntregaAscii]).
 *
 * TIPO, exatamente um: ROST rosto, ETIQ etiqueta, POS posicionamento, ACES
 * acessórios, DOC impresso escaneado, FSIM ficha de posicionamento (PDF).
 * MODIFICADORES, nesta ordem e logo depois do tipo: TRAT (gravado pelo módulo
 * Tratamento; nunca na ficha) e NS<n> (reirradiação: n = simulação - 1, ausente
 * na primeira simulação).
 *
 * CONTAGEM: por (simulação, tipo) dentro da pasta do paciente, sem zeros à
 * esquerda. Simulação e tratamento do mesmo tipo dividem a sequência. NÃO é
 * chave de ordenação: quem lê continua ordenando por data de modificação.
 *
 * GUARDA: os códigos são permanentes. Depois que um tablet grava um deles, toda
 * versão seguinte precisa continuar lendo; trocar um código cria um terceiro
 * esquema. Todo tag tem de 2 a 4 letras, e é isso que o separa das iniciais (uma
 * letra cada) na leitura. Nenhum tag pode coincidir com um mês, com TRAT ou com
 * NS seguido de dígito.
 *
 * GUARDA: o companheiro de quadro cheio continua `_ORIGINAL`, como no esquema
 * anterior. As versões já instaladas só reconhecem `_original.jpg`; um sufixo
 * diferente apareceria nelas como mais uma foto de posicionamento no carrossel e
 * na ficha regenerada.
 *
 * Nenhum arquivo existente é renomeado para este esquema. A leitura aceita os
 * dois esquemas para sempre, e só a troca de nome do paciente renomeia, cada
 * esquema no seu próprio formato ([renomearParaPaciente]).
 *
 * Não chama API do Android nem abre rede: testável na JVM.
 */
object NomeArquivo {

    enum class Tipo(val tag: String) {
        ROSTO("ROST"),
        ETIQUETA("ETIQ"),
        POSICIONAMENTO("POS"),
        ACESSORIOS("ACES"),
        IMPRESSO("DOC"),
        FICHA("FSIM")
    }

    enum class Contexto { SIMULACAO, TRATAMENTO }

    /**
     * O que o nome de um arquivo diz sobre ele.
     *
     * @property tipo `null` quando o nome não se deixa classificar.
     * @property numeroSimulacao 1 na primeira simulação; 2 na primeira reirradiação.
     * @property contador só no esquema novo; `null` no legado.
     * @property esquemaNovo o nome casou com o esquema de iniciais e tags.
     * @property original é o quadro cheio (`_ORIGINAL`) de outra foto.
     * @property arquivada traz o carimbo `_ARQ<ms>` do arquivamento.
     */
    data class Analise(
        val tipo: Tipo?,
        val numeroSimulacao: Int,
        val tratamento: Boolean,
        val contador: Int?,
        val esquemaNovo: Boolean,
        val original: Boolean,
        val arquivada: Boolean
    )

    /**
     * Meses de todo nome de arquivo ou pasta.
     *
     * GUARDA: tabela fixa, nunca o `MMM` do SimpleDateFormat. Esse vem dos dados
     * de idioma do aparelho: o mesmo setembro sai `set.` num Android e `set` em
     * outro, e o nome do arquivo passaria a depender do tablet que o gravou.
     */
    val MESES = listOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN",
                       "JUL", "AGO", "SET", "OUT", "NOV", "DEZ")

    /** Partículas de sobrenome que não viram inicial, comparadas como palavra inteira. */
    val PARTICULAS = setOf("DA", "DE", "DO", "DAS", "DOS", "E", "DI", "DU",
                           "DEL", "LA", "LE", "Y", "VAN", "VON", "DER", "DEN")

    /** Sufixo do quadro cheio, antes da extensão. */
    const val SUFIXO_ORIGINAL = "_ORIGINAL"

    /** Teto do nome completo em [nomeCompletoAscii]. */
    private const val NOME_COMPLETO_MAX = 48

    private const val EXTENSAO_PADRAO = "jpg"
    private const val SEM_NOME = "X"

    /** Algarismos do código de [nomeEntregaAscii]: 8 hexadecimais, 32 bits do SHA-256. */
    private const val CODIGO_ALGARISMOS = 8
    private const val HEX = "0123456789ABCDEF"

    private val EXTENSOES_DA_SIMULACAO = setOf("jpg", "jpeg", "pdf", "dcm")

    /**
     * O esquema novo, inteiro, do início ao fim do nome.
     *
     * Nenhum nome legado casa aqui: todo nome guardado antes traz a data com
     * hífen (`03-SET.-2026_14-22-05`), os PDFs de cache e de servidor trazem `__`
     * e caixa mista, e o prefixo legado começa por uma palavra de várias letras.
     * Os limites de dígitos garantem que contagem e reirradiação cabem num Int.
     */
    private val REGEX_NOVO = Regex(
        "^((?:[A-Z]_)+)(" + Tipo.values().joinToString("|") { it.tag } + ")" +
            "(_TRAT)?(?:_NS([1-9][0-9]{0,8}))?" +
            "_([0-9]{2})_(" + MESES.joinToString("|") + ")_([0-9]{4})" +
            "_([0-9]{2})_([0-9]{2})_([0-9]{2})_([1-9][0-9]{0,8})" +
            "(?:_ARQ([0-9]+))?(" + SUFIXO_ORIGINAL + ")?[.](JPG|JPEG|PDF|DCM)$",
        RegexOption.IGNORE_CASE)

    private val REGEX_NOVASIM = Regex("(?i)_NOVASIM([0-9]+)")
    private val REGEX_ARQ = Regex("(?i)_ARQ[0-9]+")
    private val MARCAS = Regex("\\p{M}+")
    private val SEPARADOR = Regex("[^A-Z0-9]+")
    private val ESPACOS = Regex("\\s+")

    /**
     * Letras latinas que o NFD não decompõe: sem esta tabela, `Łukasz` perderia
     * a inicial e `Øster` viraria separador.
     */
    private val LETRAS_SEM_DECOMPOSICAO: Map<Char, String> = mapOf(
        'Ł' to "L", 'ł' to "l", 'Ø' to "O", 'ø' to "o",
        'Đ' to "D", 'đ' to "d", 'Ð' to "D", 'ð' to "d",
        'Æ' to "AE", 'æ' to "ae", 'Œ' to "OE", 'œ' to "oe",
        'ß' to "SS", 'ẞ' to "SS", 'Þ' to "TH", 'þ' to "th",
        'Ħ' to "H", 'ħ' to "h", 'ı' to "I", 'Ŀ' to "L", 'ŀ' to "l",
        'Ŧ' to "T", 'ŧ' to "t"
    )

    /**
     * Apóstrofos e sinais parecidos. São APAGADOS, não separam palavras:
     * `D'Arc` vira `DARC`, uma palavra só, e `O'Neil` dá uma inicial, não duas.
     */
    private const val APOSTROFOS = "'\u2019\u2018\u02BC\u02BB`\u00B4\u2032"

    // ------------------------------------------------------------ iniciais

    /**
     * Iniciais do paciente, separadas por `_`: `Maria Aparecida dos Santos` →
     * `M_A_S`.
     *
     * Regras: acentos caem (NFD), letras latinas sem decomposição são trocadas
     * pela forma básica, maiúsculas pelo locale raiz (no turco, `i` maiúsculo
     * seria `İ`), apóstrofo é apagado, e todo outro caractere fora de `[A-Z0-9]`
     * separa palavras (`Ana-Maria` são duas). Partículas inteiras caem. Cada
     * palavra restante contribui a primeira letra se ela for de A a Z: palavra
     * que começa por dígito ou por escrita não latina não contribui. Sem nada no
     * fim, `X`. Sem teto de quantidade.
     */
    fun iniciais(nomePaciente: String?): String {
        val letras = palavras(nomePaciente)
            .filterNot { it in PARTICULAS }
            .mapNotNull { p -> p[0].takeIf { it in 'A'..'Z' } }
        return if (letras.isEmpty()) SEM_NOME else letras.joinToString("_")
    }

    /**
     * Nome completo em ASCII, base do nome das cópias entregues fora da pasta
     * do paciente ([nomeEntregaAscii]): `Maria da Conceição` →
     * `MARIA_DA_CONCEICAO`.
     *
     * Mesma normalização de [iniciais], mas toda palavra fica, partículas e
     * números inclusive. Teto de [NOME_COMPLETO_MAX] caracteres, cortado entre
     * palavras para não deixar meio sobrenome; uma primeira palavra maior que o
     * teto é cortada nele. Nunca vazio: sem nada, `X`.
     */
    fun nomeCompletoAscii(nomePaciente: String?): String {
        val todas = palavras(nomePaciente)
        if (todas.isEmpty()) return SEM_NOME
        val sb = StringBuilder()
        for (p in todas) {
            val acrescimo = if (sb.isEmpty()) p.length else p.length + 1
            if (sb.length + acrescimo > NOME_COMPLETO_MAX) break
            if (sb.isNotEmpty()) sb.append('_')
            sb.append(p)
        }
        return if (sb.isEmpty()) todas[0].take(NOME_COMPLETO_MAX) else sb.toString()
    }

    /**
     * O nome do paciente nas cópias entregues: [nomeCompletoAscii] sempre que o
     * nome tem ao menos uma letra latina.
     *
     * Nome sem nenhuma letra latina — todo em chinês, japonês, coreano, árabe,
     * bengali, cirílico — daria `X` para todos os pacientes, e a cópia na
     * bandeja da impressora ou no pen-drive deixaria de distinguir um do outro.
     * Nesse caso sai `X` seguido de um código de 8 algarismos hexadecimais: os
     * primeiros do SHA-256 do nome normalizado (NFKC, que junta as formas
     * compostas e decompostas do hangul e troca o espaço ideográfico pelo
     * comum; espaços colapsados; maiúsculas pelo locale raiz). Não há
     * transliteração: o código não se lê como nome, mas é o mesmo para o mesmo
     * paciente em qualquer tablet e difere entre pacientes. Quem pega a folha
     * confere o nome impresso nela; o código separa os arquivos.
     *
     * GUARDA: o código depende só do nome e desta normalização. Mudar uma ou o
     * algoritmo troca o nome de entrega dos mesmos pacientes entre tablets da
     * mesma clínica com versões diferentes.
     *
     * Nome vazio continua `X`. Nunca vai para a pasta do paciente, onde o
     * arquivo leva as [iniciais]; e [nomeCompletoAscii] continua devolvendo `X`
     * para quem precisa saber que nada latino sobrou.
     */
    fun nomeEntregaAscii(nomePaciente: String?): String {
        if (nomePaciente.isNullOrBlank()) return SEM_NOME
        val temLetraLatina = palavras(nomePaciente).any { p -> p.any { it in 'A'..'Z' } }
        return if (temLetraLatina) nomeCompletoAscii(nomePaciente)
               else SEM_NOME + codigoDoNome(nomePaciente)
    }

    // GUARDA: hexadecimal montado à mão, nunca "%02X".format — o formatador
    // segue o locale padrão, que o app troca para ar ou bn.
    private fun codigoDoNome(nome: String): String {
        val normal = Normalizer.normalize(nome, Normalizer.Form.NFKC)
            .replace(ESPACOS, " ").trim().uppercase(Locale.ROOT)
        val resumo = MessageDigest.getInstance("SHA-256")
            .digest(normal.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(CODIGO_ALGARISMOS)
        for (i in 0 until CODIGO_ALGARISMOS / 2) {
            val b = resumo[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    /** Palavras `[A-Z0-9]+` do nome, na ordem, depois da normalização comum. */
    private fun palavras(nome: String?): List<String> {
        if (nome.isNullOrBlank()) return emptyList()
        val semMarcas = MARCAS.replace(Normalizer.normalize(nome, Normalizer.Form.NFD), "")
        val sb = StringBuilder(semMarcas.length)
        for (ch in semMarcas) {
            val troca = LETRAS_SEM_DECOMPOSICAO[ch]
            if (troca != null) sb.append(troca) else sb.append(ch)
        }
        val semApostrofo = sb.toString().uppercase(Locale.ROOT).filterNot { it in APOSTROFOS }
        return semApostrofo.split(SEPARADOR).filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ data

    /**
     * Data de nome de arquivo ou pasta: `DD_MMM_AAAA_HH_MM[_SS]`, mês pela tabela
     * [MESES]. Fonte única de toda data dentro de um nome.
     *
     * GUARDA: GregorianCalendar explícito, nunca `Calendar.getInstance` — num
     * aparelho em `th_TH` este devolve o calendário budista, com o ano 2569.
     */
    fun campoData(instanteMs: Long, fuso: TimeZone = TimeZone.getDefault(),
                  comSegundos: Boolean = true): String {
        val c = GregorianCalendar(fuso, Locale.ROOT)
        c.timeInMillis = instanteMs
        val sb = StringBuilder(20)
            .append(doisDigitos(c.get(Calendar.DAY_OF_MONTH))).append('_')
            .append(MESES[c.get(Calendar.MONTH)]).append('_')
            .append(c.get(Calendar.YEAR).toString().padStart(4, '0')).append('_')
            .append(doisDigitos(c.get(Calendar.HOUR_OF_DAY))).append('_')
            .append(doisDigitos(c.get(Calendar.MINUTE)))
        if (comSegundos) sb.append('_').append(doisDigitos(c.get(Calendar.SECOND)))
        return sb.toString()
    }

    // GUARDA: Int.toString com padStart, nunca String.format nem "%02d".format.
    // Esses seguem o locale padrão, que o app troca para ar ou bn, e podem
    // devolver algarismos arábico-índicos ou bengalis dentro do nome.
    private fun doisDigitos(n: Int): String = n.toString().padStart(2, '0')

    // ------------------------------------------------------------ montagem

    /**
     * Nome de arquivo guardado na pasta do paciente.
     *
     * Simulação abaixo de 1 conta como 1, e NS só aparece da segunda em diante.
     * TRAT só no contexto Tratamento e nunca na ficha. Contagem abaixo de 1 vira
     * 1. Extensão aparada, sem ponto inicial, em minúsculas; vazia vira `jpg`.
     */
    fun montar(
        nomePaciente: String?,
        tipo: Tipo,
        numeroSimulacao: Int,
        instanteMs: Long,
        contador: Int,
        extensao: String,
        contexto: Contexto = Contexto.SIMULACAO,
        fuso: TimeZone = TimeZone.getDefault()
    ): String = compor(iniciais(nomePaciente), tipo, numeroSimulacao, instanteMs,
                       contador, extensao, contexto, fuso)

    /**
     * Igual a [montar], com o nome de entrega ([nomeEntregaAscii]) no lugar das
     * iniciais. SÓ para cópias entregues: pen-drive, pasta da impressora,
     * compartilhar. Nunca para a pasta do paciente, onde a leitura reconhece
     * apenas o radical de iniciais.
     */
    fun montarEntrega(
        nomePaciente: String?,
        tipo: Tipo,
        numeroSimulacao: Int,
        instanteMs: Long,
        contador: Int,
        extensao: String,
        contexto: Contexto = Contexto.SIMULACAO,
        fuso: TimeZone = TimeZone.getDefault()
    ): String = compor(nomeEntregaAscii(nomePaciente), tipo, numeroSimulacao, instanteMs,
                       contador, extensao, contexto, fuso)

    /**
     * Nome de entrega de um arquivo já guardado na pasta do paciente: as
     * iniciais dão lugar a [nomeEntregaAscii], e todo o resto (tipo, simulação,
     * data, contagem, extensão) fica. É o nome que [montarEntrega] daria com os
     * dados que montaram o arquivo, sem precisar deles: o instante só existe,
     * aqui, dentro do nome.
     *
     * GUARDA: devolve `null` — e quem chama mantém o nome do arquivo — em três
     * casos. Nome do esquema anterior, que já traz o nome completo e nunca é
     * renomeado. Paciente sem nome. E iniciais do arquivo que não são as deste
     * paciente: trocar mesmo assim rotularia a ficha de uma pessoa com o nome de
     * outra, que é o erro que a cópia de entrega existe para evitar.
     */
    fun entregaDoGuardado(nomeArquivo: String, nomePaciente: String?): String? {
        if (nomePaciente.isNullOrBlank()) return null
        val prefixo = casarNovo(nomeArquivo)?.groupValues?.get(1) ?: return null
        if (!prefixo.dropLast(1).equals(iniciais(nomePaciente), ignoreCase = true)) return null
        return nomeEntregaAscii(nomePaciente) + "_" + nomeArquivo.substring(prefixo.length)
    }

    /**
     * Nomes de um lote salvo de uma vez, na ordem dos itens.
     *
     * A contagem é por tipo: começa em `inicio(tipo)` e sobe 1 a cada item
     * daquele tipo. É o que impede duas capturas do mesmo segundo de saírem com
     * o mesmo nome. Para continuar a numeração da pasta, passe
     * `inicio = { proximoContador(existentes, numeroSimulacao, it) }`.
     */
    fun nomearLote(
        nomePaciente: String?,
        itens: List<Pair<Tipo, Long>>,
        numeroSimulacao: Int,
        contexto: Contexto,
        extensao: String = EXTENSAO_PADRAO,
        inicio: (Tipo) -> Int = { 1 },
        fuso: TimeZone = TimeZone.getDefault()
    ): List<String> {
        val prefixo = iniciais(nomePaciente)
        val proximo = HashMap<Tipo, Int>()
        return itens.map { (tipo, instante) ->
            val n = proximo.getOrPut(tipo) { inicio(tipo).coerceAtLeast(1) }
            proximo[tipo] = n + 1
            compor(prefixo, tipo, numeroSimulacao, instante, n, extensao, contexto, fuso)
        }
    }

    private fun compor(
        prefixo: String,
        tipo: Tipo,
        numeroSimulacao: Int,
        instanteMs: Long,
        contador: Int,
        extensao: String,
        contexto: Contexto,
        fuso: TimeZone
    ): String {
        val sb = StringBuilder(64).append(prefixo).append('_').append(tipo.tag)
        if (contexto == Contexto.TRATAMENTO && tipo != Tipo.FICHA) sb.append("_TRAT")
        val sim = numeroSimulacao.coerceAtLeast(1)
        if (sim > 1) sb.append("_NS").append(sim - 1)
        sb.append('_').append(campoData(instanteMs, fuso, comSegundos = true))
        sb.append('_').append(contador.coerceAtLeast(1))
        sb.append('.').append(normalizarExtensao(extensao))
        return sb.toString()
    }

    private fun normalizarExtensao(extensao: String): String =
        extensao.trim().trimStart('.').lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .ifEmpty { EXTENSAO_PADRAO }

    // ------------------------------------------------------------ leitura

    /**
     * Classifica um nome dos dois esquemas.
     *
     * Primeiro o esquema novo, inteiro e estrito. Não casando, o LEGADO: PDF é
     * ficha; JPG é classificado pela mesma regra de substring que as versões
     * anteriores aplicavam, sem mudar uma vírgula, para nenhum arquivo já em
     * campo trocar de classe; outra extensão fica sem tipo. A reirradiação vem
     * de `_NOVASIMn` lido como número inteiro: `_NOVASIM10` é a simulação 11, e
     * não a 2 seguida de um zero.
     */
    fun analisar(nome: String): Analise {
        val m = casarNovo(nome) ?: return analisarLegado(nome)
        val g = m.groupValues
        return Analise(
            tipo = Tipo.values().first { it.tag.equals(g[2], ignoreCase = true) },
            numeroSimulacao = simulacaoDeMarca(g[4].toIntOrNull()),
            tratamento = g[3].isNotEmpty(),
            contador = g[11].toInt(),
            esquemaNovo = true,
            original = g[13].isNotEmpty(),
            arquivada = g[12].isNotEmpty()
        )
    }

    fun tipo(nome: String): Tipo? = analisar(nome).tipo

    fun numeroSimulacao(nome: String): Int = analisar(nome).numeroSimulacao

    /**
     * O arquivo é foto, ficha ou DICOM desta simulação?
     *
     * Ocultos (`.timeout_simN.json`, `.obs_simN.txt`) nunca casam: são dados da
     * simulação, não arquivos dela, e quem apaga por este filtro não pode
     * levá-los junto.
     */
    fun pertenceASimulacao(nome: String, numeroSimulacao: Int): Boolean =
        !nome.startsWith(".") && extensaoDe(nome) in EXTENSOES_DA_SIMULACAO &&
            numeroSimulacao(nome) == numeroSimulacao

    /** O arquivo é uma ficha (PDF) desta simulação? */
    fun ehFichaDaSimulacao(nome: String, numeroSimulacao: Int): Boolean =
        !nome.startsWith(".") && extensaoDe(nome) == "pdf" &&
            numeroSimulacao(nome) == numeroSimulacao

    /** Quadro cheio de outra foto: radical terminado em `_ORIGINAL`, JPG, qualquer caixa. */
    fun ehOriginal(nome: String): Boolean {
        val ext = extensaoDe(nome)
        if (ext != "jpg" && ext != "jpeg") return false
        return radical(nome).endsWith(SUFIXO_ORIGINAL, ignoreCase = true)
    }

    /**
     * Nome do quadro cheio de uma foto: o radical dela mais `_ORIGINAL`. O par
     * fica lado a lado em qualquer listagem por nome.
     */
    fun nomeOriginal(nomeFoto: String): String =
        radical(nomeFoto) + SUFIXO_ORIGINAL + "." + nomeFoto.substringAfterLast('.', "").ifEmpty { EXTENSAO_PADRAO }

    /**
     * Nomes em que o quadro cheio pode estar, na ordem de procura. O segundo
     * cobre foto `.jpeg` cujo par foi gravado com o sufixo fixo `_ORIGINAL.jpg`,
     * que é como a sessão de captura e o arquivamento o escrevem.
     */
    fun candidatosOriginal(nomeFoto: String): List<String> =
        listOf(nomeOriginal(nomeFoto), radical(nomeFoto) + SUFIXO_ORIGINAL + ".jpg").distinct()

    /** O quadro cheio da foto na mesma pasta, se existir e não estiver vazio. */
    fun originalDe(foto: File): File? {
        if (ehOriginal(foto.name)) return null
        val pai = foto.parentFile
        return candidatosOriginal(foto.name)
            .map { File(pai, it) }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    /**
     * Próxima contagem para (simulação, tipo) numa pasta.
     *
     * Ocultos, quadros cheios e arquivadas não contam. Do esquema novo vale a
     * maior contagem; cada legado do mesmo tipo e simulação conta como uma foto
     * já tirada. O resultado é o maior dos dois mais 1, para a numeração seguir
     * de onde a pasta parou mesmo quando ela mistura os dois esquemas.
     */
    fun proximoContador(nomesExistentes: Collection<String>, numeroSimulacao: Int, tipo: Tipo): Int {
        var maiorNovo = 0
        var legados = 0
        for (nome in nomesExistentes) {
            if (nome.startsWith(".")) continue
            val a = analisar(nome)
            if (a.original || a.arquivada) continue
            if (a.tipo != tipo || a.numeroSimulacao != numeroSimulacao) continue
            if (a.esquemaNovo) maiorNovo = maxOf(maiorNovo, a.contador ?: 0) else legados++
        }
        return maxOf(maiorNovo, legados) + 1
    }

    // ------------------------------------------------------------ troca de nome do paciente

    /**
     * Troca as iniciais de um nome do esquema novo e mantém todo o resto (tags,
     * data, contagem, `_ARQ`, `_ORIGINAL`, extensão). `null` para nome legado.
     */
    fun trocarIniciais(nome: String, novoNomePaciente: String?): String? {
        val m = casarNovo(nome) ?: return null
        return iniciais(novoNomePaciente) + "_" + nome.substring(m.groupValues[1].length)
    }

    /**
     * Troca o prefixo de nome de um arquivo LEGADO, que traz o nome completo.
     *
     * Os gravadores antigos usaram duas normalizações, e as duas existem em
     * campo: P1 mantém apóstrofo, hífen e ponto; P2 apaga tudo o que não é letra,
     * dígito ou espaço. A mais longa é tentada primeiro, porque a mais curta pode
     * ser prefixo dela. O novo nome sai na MESMA variante que casou, para o
     * arquivo continuar com a cara dos vizinhos. O prefixo precisa ser seguido de
     * `_`: `Ana` não toca `ANABELA_ROSTO_...`. `null` quando nada casa, quando o
     * nome é do esquema novo ou quando o novo nome normaliza para vazio.
     */
    fun renomearLegado(nome: String, nomeAntigo: String, nomeNovo: String): String? {
        if (casarNovo(nome) != null) return null
        val p1 = prefixoLegadoP1(nomeAntigo) to prefixoLegadoP1(nomeNovo)
        val p2 = prefixoLegadoP2(nomeAntigo) to prefixoLegadoP2(nomeNovo)
        val ordem = if (p2.first.length > p1.first.length) listOf(p2, p1) else listOf(p1, p2)
        for ((antigo, novo) in ordem) {
            if (antigo.isEmpty()) continue
            if (nome.startsWith(antigo + "_", ignoreCase = true)) {
                return if (novo.isEmpty()) null else novo + nome.substring(antigo.length)
            }
        }
        return null
    }

    /**
     * Novo nome de um arquivo quando o paciente muda de nome: esquema novo troca
     * as iniciais, legado troca o prefixo. Quem chama pula o arquivo quando o
     * resultado é `null` ou igual ao nome atual.
     */
    fun renomearParaPaciente(nome: String, nomeAntigo: String, nomeNovo: String): String? =
        if (casarNovo(nome) != null) trocarIniciais(nome, nomeNovo)
        else renomearLegado(nome, nomeAntigo, nomeNovo)

    // ------------------------------------------------------------ categorias da sessão

    fun tipoDe(c: SessionManager.Category): Tipo = when (c) {
        SessionManager.Category.FACE -> Tipo.ROSTO
        SessionManager.Category.LABEL -> Tipo.ETIQUETA
        SessionManager.Category.POSITIONING -> Tipo.POSICIONAMENTO
        SessionManager.Category.ACCESSORIES -> Tipo.ACESSORIOS
        SessionManager.Category.DOCUMENTS -> Tipo.IMPRESSO
    }

    /** Categoria da sessão de um tipo. A ficha não é foto: `null`. */
    fun categoriaDe(t: Tipo): SessionManager.Category? = when (t) {
        Tipo.ROSTO -> SessionManager.Category.FACE
        Tipo.ETIQUETA -> SessionManager.Category.LABEL
        Tipo.POSICIONAMENTO -> SessionManager.Category.POSITIONING
        Tipo.ACESSORIOS -> SessionManager.Category.ACCESSORIES
        Tipo.IMPRESSO -> SessionManager.Category.DOCUMENTS
        Tipo.FICHA -> null
    }

    // ------------------------------------------------------------ internos

    /**
     * Casa o esquema novo. Só ASCII entra: com IGNORE_CASE o Java compara letras
     * Unicode por caixa, e `ı` ou o sinal de Kelvin passariam por `I` e `K`.
     */
    private fun casarNovo(nome: String): MatchResult? =
        if (nome.all { it.code < 128 }) REGEX_NOVO.matchEntire(nome) else null

    private fun analisarLegado(nome: String): Analise {
        val tipo = when (extensaoDe(nome)) {
            "pdf" -> Tipo.FICHA
            "jpg", "jpeg" -> tipoLegado(nome)
            else -> null
        }
        val ns = REGEX_NOVASIM.find(nome)?.groupValues?.get(1)?.toIntOrNull()
        return Analise(
            tipo = tipo,
            numeroSimulacao = simulacaoDeMarca(ns),
            tratamento = tipo != Tipo.FICHA && nome.contains("_TRATAMENTO", ignoreCase = true),
            contador = null,
            esquemaNovo = false,
            original = ehOriginal(nome),
            arquivada = REGEX_ARQ.containsMatchIn(nome)
        )
    }

    /**
     * GUARDA: regra de classificação do legado, copiada sem alteração — mesmas
     * substrings, mesma ordem. Ela erra quando o nome do paciente contém uma
     * das palavras (um sobrenome com LABEL dentro vira etiqueta), e o erro fica:
     * corrigir aqui mudaria de classe arquivos que já estão em campo. Nome do
     * esquema novo não passa por aqui, porque não traz o nome do paciente.
     */
    private fun tipoLegado(nomeArquivo: String): Tipo? {
        val n = nomeArquivo.lowercase()
        return when {
            n.contains("rosto") || n.contains("face") -> Tipo.ROSTO
            n.contains("etiqueta") || n.contains("label") -> Tipo.ETIQUETA
            n.contains("acessorios") || n.contains("accessories") -> Tipo.ACESSORIOS
            n.contains("posicionamento") || n.contains("positioning") || n.contains("_pos_") -> Tipo.POSICIONAMENTO
            n.contains("_doc") || n.contains("documento") || n.contains("impresso") -> Tipo.IMPRESSO
            else -> null
        }
    }

    /** NSn e _NOVASIMn marcam a simulação n + 1; sem marca, a primeira. */
    private fun simulacaoDeMarca(n: Int?): Int =
        if (n == null) 1 else n.coerceIn(0, Int.MAX_VALUE - 1) + 1

    /** Prefixo que os gravadores de foto da simulação e de reedição da ficha usaram. */
    private fun prefixoLegadoP1(nome: String): String =
        StorageLocal.removerAcentosMaiusculas(nome).replace(Regex("\\s+"), "_")

    /** Prefixo que os gravadores do módulo Tratamento e da edição de cadastro usaram. */
    private fun prefixoLegadoP2(nome: String): String =
        Normalizer.normalize(nome, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase(Locale.ROOT)
            .replace(" ", "_")

    private fun radical(nome: String): String = nome.substringBeforeLast('.')

    private fun extensaoDe(nome: String): String =
        nome.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
