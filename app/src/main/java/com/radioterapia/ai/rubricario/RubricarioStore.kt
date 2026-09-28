package com.radioterapia.ai.rubricario

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Equipe do serviço para a página de RUBRICÁRIO, organizada em BLOCOS.
 *
 * O rubricário é a folha em que cada profissional que assina o tratamento
 * aparece com cargo, número de conselho e a própria rubrica — para que quem
 * conferir a ficha depois consiga identificar quem assinou.
 *
 * POR QUE BLOCOS. Um tablet só atende mais de uma clínica e mais de um
 * acelerador, e cada um tem a sua equipe. Com uma lista única, metade das
 * fichas saía com a equipe errada impressa — nomes de gente que não estava
 * naquela sala, o que é o oposto do que a folha existe para provar. Cada bloco
 * é uma equipe nomeada ("Clínica A", "Acelerador 2"), e o protocolo escolhido
 * na simulação diz qual bloco entra na ficha.
 *
 * O BLOCO PADRÃO sempre existe e não pode sumir: é o estado ao qual se volta, e
 * é onde cai quem foi cadastrado antes de os blocos existirem. Com apenas ele, a
 * rotina é exatamente a de antes.
 *
 * ONDE MORA: `filesDir/rubricario/` — `blocos.json`, `equipe.json` e uma PNG de
 * assinatura por pessoa. Não vai para a pasta do paciente de propósito: é dado
 * da instituição, não do atendimento, e replicá-lo em cada pasta multiplicaria a
 * mesma imagem por paciente.
 *
 * CARGO E TIPO DE REGISTRO andam juntos porque cada conselho tem o seu — CRM
 * para médico, CNEN para físico, CRT para tecnólogo, COREN para enfermagem. O
 * app não fixa essa lista: cada serviço declara a sua nas Configurações, já que
 * a nomenclatura muda entre países e o app é universal. Os cargos são GLOBAIS,
 * não por bloco: são o vocabulário de funções do serviço, e duplicá-los por
 * equipe faria a mesma função ser escrita de dois jeitos na mesma ficha.
 */
class RubricarioStore(private val context: Context) {

    private val dir = File(context.filesDir, "rubricario").apply { mkdirs() }
    private val arquivo = File(dir, "equipe.json")
    private val arquivoBlocos = File(dir, "blocos.json")

    /**
     * Uma pessoa do rubricário. `assinatura` é o nome do PNG dentro de `dir`.
     *
     * `blocoId` tem valor padrão porque o cadastro anterior aos blocos não o
     * traz: quem já estava cadastrado cai no bloco padrão sem migração e sem
     * perder nada.
     */
    data class Pessoa(
        val id: String,
        val cargo: String,
        val nome: String,
        val registro: String,
        val assinatura: String,
        val blocoId: String = ID_PADRAO
    )

    /** Uma equipe nomeada. */
    data class Bloco(
        val id: String,
        val nome: String,
        val padrao: Boolean
    )

    companion object {
        const val ID_PADRAO = "padrao"

        /** Teto por rubrica na exportação (~256 kB). Ver [exportarJson]. */
        private const val LIMITE_PNG = 256L * 1024

        const val CARGOS_PADRAO =
            "Médico = CRM\n" +
            "Físico = CNEN\n" +
            "Dosimetrista = CRT\n" +
            "Tecnólogo = CRT\n" +
            "Enfermagem = COREN"

        /** "Médico = CRM" -> ("Médico", "CRM"). Sem "=", o registro fica vazio. */
        fun separarCargo(linha: String): Pair<String, String> {
            val p = linha.split("=", limit = 2)
            return p[0].trim() to (p.getOrNull(1)?.trim().orEmpty())
        }
    }

    // ---------------------------------------------------------------- blocos

    /**
     * Todos os blocos, com o padrão SEMPRE em primeiro e SEMPRE presente.
     *
     * Garantido na LEITURA, e não só na primeira gravação: assim a lista
     * sobrevive a um `blocos.json` apagado, corrompido ou vindo de uma
     * importação incompleta. No pior caso o serviço volta a ter uma equipe só,
     * que é a rotina anterior — nunca uma tela de seleção vazia.
     */
    fun listarBlocos(): List<Bloco> {
        val lidos = try {
            if (!arquivoBlocos.exists()) emptyList()
            else {
                val arr = JSONArray(arquivoBlocos.readText())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    if (id.isBlank()) null
                    else Bloco(id, o.optString("nome"), id == ID_PADRAO)
                }
            }
        } catch (_: Exception) { emptyList() }

        val padrao = lidos.firstOrNull { it.id == ID_PADRAO }
            ?: Bloco(ID_PADRAO, nomePadrao(), true)
        return listOf(padrao) + lidos.filter { it.id != ID_PADRAO }
    }

    fun obterBloco(id: String): Bloco? = listarBlocos().firstOrNull { it.id == id }

    /** Há mais de um bloco? A seleção só faz sentido quando sim. */
    fun temEscolhaDeBloco(): Boolean = listarBlocos().size > 1

    fun novoIdBloco(): String = "b" + System.currentTimeMillis()

    fun salvarBloco(b: Bloco): Boolean = try {
        val atuais = listarBlocos().toMutableList()
        val i = atuais.indexOfFirst { it.id == b.id }
        if (i >= 0) atuais[i] = b else atuais.add(b)
        gravarBlocos(atuais)
        true
    } catch (_: Exception) { false }

    /**
     * Exclui um bloco E as pessoas dele.
     *
     * AS PESSOAS SAEM JUNTO, e a tela avisa quantas antes de perguntar. Movê-las
     * para o bloco padrão seria pior: elas passariam a sair impressas em toda
     * ficha daquele bloco — nomes de quem não estava na sala, que é exatamente o
     * que os blocos existem para impedir. Quem quiser guardá-las exporta o bloco
     * antes (ver [exportarBloco]).
     *
     * O PADRÃO NÃO SAI: é o estado ao qual sempre se pode voltar.
     */
    fun excluirBloco(id: String): Boolean {
        if (id == ID_PADRAO) return false
        return try {
            listar(id).forEach { excluir(it.id) }
            gravarBlocos(listarBlocos().filter { it.id != id })
            true
        } catch (_: Exception) { false }
    }

    /** Quantas pessoas há no bloco — a tela de exclusão precisa dizer. */
    fun contarNoBloco(blocoId: String): Int = listar(blocoId).size

    private fun nomePadrao(): String =
        try { context.getString(com.radioterapia.ai.R.string.rub_bloco_padrao) }
        catch (_: Exception) { "Equipe padrão" }

    // ---------------------------------------------------------------- leitura

    /**
     * A equipe. Com `blocoId`, só a daquele bloco; sem, todos.
     *
     * Pessoa cujo bloco foi apagado por fora (arquivo editado à mão, importação
     * parcial) é tratada como do PADRÃO em vez de sumir da listagem — cadastro
     * invisível é pior que cadastro no lugar errado, porque ninguém procura o
     * que não sabe que existe.
     */
    fun listar(blocoId: String? = null): List<Pessoa> {
        val idsValidos = listarBlocos().map { it.id }.toSet()
        val todos = try {
            if (!arquivo.exists()) emptyList()
            else {
                val arr = JSONArray(arquivo.readText())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val bruto = o.optString("bloco", ID_PADRAO).ifBlank { ID_PADRAO }
                    Pessoa(
                        id = o.optString("id"),
                        cargo = o.optString("cargo"),
                        // Normaliza tambem na LEITURA, e nao so ao gravar: quem ja
                        // tinha equipe cadastrada antes desta regra veria a folha
                        // continuar em caixa mista ate reeditar pessoa por pessoa.
                        // Assim o padrao vale para o cadastro antigo sem migracao.
                        nome = normalizarNomePessoa(o.optString("nome")),
                        registro = o.optString("registro"),
                        assinatura = o.optString("assinatura"),
                        blocoId = if (bruto in idsValidos) bruto else ID_PADRAO
                    )
                }.filter { it.nome.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
        return if (blocoId == null) todos else todos.filter { it.blocoId == blocoId }
    }

    /**
     * Agrupado por cargo, na ORDEM em que os cargos foram declarados.
     *
     * Ordem alfabética seria arbitrária: a folha impressa segue a hierarquia do
     * serviço, e é assim que a equipe procura o próprio nome nela.
     *
     * @param blocoId qual equipe entra na folha. Vem do protocolo escolhido na
     *   simulação; com um bloco só, é sempre o padrão.
     */
    fun porCargo(ordemCargos: List<String>,
                 blocoId: String = ID_PADRAO): List<Pair<String, List<Pessoa>>> {
        val todos = listar(blocoId)
        val agrupado = todos.groupBy { it.cargo }
        val saida = mutableListOf<Pair<String, List<Pessoa>>>()
        ordemCargos.forEach { c ->
            agrupado[c]?.let { saida.add(c to it.emOrdemAlfabetica()) }
        }
        // Cargo que existe em alguém mas saiu da lista declarada não pode sumir
        // da folha em silêncio — apareceria como pessoa faltando na conferência.
        agrupado.keys.filter { it !in ordemCargos }.sorted().forEach { c ->
            saida.add(c to agrupado.getValue(c).emOrdemAlfabetica())
        }
        return saida
    }

    /**
     * Ordem alfabética pelo nome como ele é escrito — ou seja, pelo PRIMEIRO
     * nome, que é por onde a equipe procura o próprio na folha.
     *
     * Ordenar por sobrenome exigiria adivinhar onde ele começa, e nome composto
     * ("Maria da Silva", "Ana Beatriz") não tem regra confiável. Comparação sem
     * distinguir maiúsculas de minúsculas por segurança: o cadastro antigo, de
     * antes da normalização, pode ter nomes em caixa mista.
     */
    private fun List<Pessoa>.emOrdemAlfabetica(): List<Pessoa> =
        sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nome.trim() })

    fun arquivoAssinatura(p: Pessoa): File? =
        File(dir, p.assinatura).takeIf { p.assinatura.isNotBlank() && it.exists() }

    fun bitmapAssinatura(p: Pessoa): Bitmap? = try {
        arquivoAssinatura(p)?.let { BitmapFactory.decodeFile(it.absolutePath) }
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- escrita

    /** Grava (ou atualiza) uma pessoa. `assinaturaPng` nulo mantém a atual. */
    fun salvar(id: String?, cargo: String, nome: String, registro: String,
               assinaturaPng: Bitmap?, blocoId: String = ID_PADRAO): Pessoa? = try {
        val atuais = listar().toMutableList()
        val idFinal = id ?: "p${System.currentTimeMillis()}"
        val existente = atuais.firstOrNull { it.id == idFinal }

        val nomeArq = existente?.assinatura?.takeIf { it.isNotBlank() } ?: "$idFinal.png"
        if (assinaturaPng != null) {
            File(dir, nomeArq).outputStream().use {
                assinaturaPng.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        // NOME EM MAIUSCULAS, sempre.
        //
        // A folha impressa mistura quem digitou "Maria Silva" com quem digitou
        // "MARIA SILVA", e na coluna do rubricario a diferenca salta aos olhos.
        // Normalizar na GRAVACAO, e nao so no desenho, faz a lista das
        // Configuracoes concordar com o papel — e evita que a ordem alfabetica
        // separe "Silva" de "SILVA", que e o que acontece quando se compara
        // texto de caixas diferentes.
        val nova = Pessoa(idFinal, cargo, normalizarNomePessoa(nome), registro.trim(),
                          nomeArq, blocoId)
        if (existente != null) atuais[atuais.indexOf(existente)] = nova else atuais.add(nova)
        gravar(atuais)
        nova
    } catch (_: Exception) { null }

    /**
     * Nome como ele sai na folha: maiúsculas, sem espaço duplicado.
     *
     * `uppercase()` sem locale usa a raiz e não o idioma do aparelho — para o
     * português dá no mesmo, mas é o comportamento estável, e o app roda em
     * doze idiomas.
     */
    private fun normalizarNomePessoa(nome: String): String =
        nome.trim().replace(Regex("\\s+"), " ").uppercase()

    fun excluir(id: String): Boolean = try {
        val atuais = listar().toMutableList()
        val alvo = atuais.firstOrNull { it.id == id }
        if (alvo != null) {
            atuais.remove(alvo)
            // A PNG sai junto: deixar a imagem orfa acumularia assinatura de
            // gente que nao trabalha mais ali, dentro do armazenamento do app.
            try { File(dir, alvo.assinatura).delete() } catch (_: Exception) {}
            gravar(atuais)
        }
        alvo != null
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- transferência

    /**
     * A equipe inteira num JSONArray, para viajar junto das configurações.
     *
     * POR QUE ENTRA NA EXPORTAÇÃO: o arquivo de configurações existe para montar
     * um tablet novo em segundos, e o rubricário é a parte mais cara de refazer
     * à mão — não é digitar um nome, é reunir a equipe para assinar de novo, uma
     * pessoa de cada vez. Um serviço com quinze profissionais perderia mais
     * tempo nisso do que em todo o resto da configuração somado.
     *
     * A RUBRICA VAI JUNTO, em Base64. A alternativa seria exportar só nome e
     * registro e pedir que todos assinassem de novo — o que funciona, mas
     * transforma "restaurar o tablet" numa tarefa que depende da agenda de
     * quinze pessoas. Cada PNG é um traço recortado com fundo transparente, de
     * poucas dezenas de kB.
     *
     * O TETO POR IMAGEM existe para o arquivo não virar algo que o tablet não
     * consegue abrir: uma rubrica muito grande é descartada e a pessoa entra sem
     * ela, assinando de novo depois. Perder uma rubrica é recuperável; um JSON
     * grande demais para ser lido perderia o rubricário inteiro.
     *
     * CONTÉM DADO PESSOAL DA EQUIPE — nome e número de conselho. É documento da
     * instituição, e a tela que exporta diz isso a quem clica.
     *
     * @param blocoId exporta só uma equipe. Nulo exporta todas, com o nome do
     *   bloco em cada pessoa para que a importação reconstrua a divisão.
     */
    fun exportarJson(blocoId: String? = null): JSONArray {
        val arr = JSONArray()
        val nomes = listarBlocos().associate { it.id to it.nome }
        listar(blocoId).forEach { p ->
            val o = JSONObject().apply {
                put("cargo", p.cargo); put("nome", p.nome); put("registro", p.registro)
                // O NOME do bloco, e não o id: ids são carimbos de tempo e não
                // significam nada no tablet de destino, onde "Clínica A" pode
                // ter sido criada noutro instante. O nome é o que identifica a
                // equipe para quem importa.
                // O PADRÃO VIAJA SEM NOME. O nome dele é traduzido, e um
                // tablet em inglês receberia "Equipe padrão" como se fosse uma
                // equipe estrangeira — duas equipes padrão, uma em cada idioma,
                // e a folha saindo com metade da gente. Vazio cai no padrão
                // local, seja ele chamado como for.
                put("bloco_nome",
                    if (p.blocoId == ID_PADRAO) "" else nomes[p.blocoId].orEmpty())
            }
            try {
                val f = arquivoAssinatura(p)
                if (f != null && f.length() in 1..LIMITE_PNG) {
                    o.put("rubrica_b64", android.util.Base64.encodeToString(
                        f.readBytes(), android.util.Base64.NO_WRAP))
                }
            } catch (_: Exception) { /* sem rubrica: a pessoa entra e assina depois */ }
            arr.put(o)
        }
        return arr
    }

    /**
     * Aplica um array gerado por [exportarJson]. Devolve quantas pessoas entraram.
     *
     * ACRESCENTA, não substitui: importar a configuração de outra unidade num
     * tablet que já tem equipe cadastrada não pode apagar quem está lá. O
     * critério de "já existe" é nome + cargo + bloco, que é o trio que
     * identifica a pessoa numa folha — importar duas vezes não duplica ninguém,
     * e a mesma pessoa pode legitimamente existir em duas equipes.
     *
     * BLOCO POR NOME: um bloco que ainda não existe aqui é criado. Sem isso a
     * equipe importada cairia toda no padrão e se misturaria à local, que é o
     * problema que os blocos vieram resolver.
     */
    fun importarJson(arr: JSONArray): Int {
        var n = 0
        val existentes = listar()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nome = o.optString("nome").trim()
            val cargo = o.optString("cargo").trim()
            if (nome.isBlank()) continue

            val idBloco = resolverBlocoPorNome(o.optString("bloco_nome").trim())
            if (existentes.any {
                    it.nome.equals(nome, true) && it.cargo.equals(cargo, true) &&
                    it.blocoId == idBloco
                }) continue

            val png = try {
                o.optString("rubrica_b64").takeIf { it.isNotBlank() }?.let { b64 ->
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (_: Throwable) { null }
            if (salvar(null, cargo, nome, o.optString("registro").trim(), png, idBloco) != null) n++
        }
        return n
    }

    /**
     * Acha o bloco pelo nome e cria se não existir. É o que o pacote de
     * transferência e o protocolo importado usam para reencontrar a equipe.
     */
    fun garantirBloco(nome: String): String = resolverBlocoPorNome(nome)

    /**
     * Só os NOMES das equipes, para o pacote de transferência.
     *
     * As pessoas já carregam o nome do bloco delas, então esta lista existe
     * apenas por causa da equipe VAZIA — a que foi criada e ainda não colheu
     * nenhuma assinatura. Sem ela, essa equipe sumiria do tablet novo e o
     * usuário refaria o cadastro sem entender por quê.
     *
     * O padrão fica de fora: ele existe em todo tablet e tem nome traduzido.
     */
    fun exportarBlocosJson(): JSONArray = JSONArray().apply {
        listarBlocos().filter { it.id != ID_PADRAO }.forEach { put(it.nome) }
    }

    /** Recria as equipes de [exportarBlocosJson]. Devolve quantas faltavam. */
    fun importarBlocosJson(arr: JSONArray): Int {
        var n = 0
        for (i in 0 until arr.length()) {
            val nome = arr.optString(i).trim()
            if (nome.isBlank()) continue
            if (listarBlocos().none { it.nome.equals(nome, true) }) {
                garantirBloco(nome); n++
            }
        }
        return n
    }

    /** Acha o bloco por nome; cria se não existir. Nome vazio cai no padrão. */
    private fun resolverBlocoPorNome(nome: String): String {
        if (nome.isBlank()) return ID_PADRAO
        listarBlocos().firstOrNull { it.nome.equals(nome, true) }?.let { return it.id }
        val novo = Bloco(novoIdBloco(), nome, false)
        return if (salvarBloco(novo)) novo.id else ID_PADRAO
    }

    /**
     * Um bloco sozinho, num objeto que carrega o nome da equipe.
     *
     * Formato próprio, e não o array de [exportarJson], porque este arquivo é
     * escolhido a mão pelo usuário e precisa dizer o que é ao ser reaberto —
     * inclusive quando o bloco está vazio, caso em que um array nu seria
     * indistinguível de um arquivo corrompido.
     */
    fun exportarBloco(blocoId: String): JSONObject {
        val b = obterBloco(blocoId)
        return JSONObject().apply {
            put("_tipo", TIPO_BLOCO)
            put("nome", b?.nome.orEmpty())
            // Marca o padrão em vez de confiar no nome dele, que é traduzido.
            put("padrao", b?.padrao == true)
            put("pessoas", exportarJson(blocoId))
        }
    }

    /**
     * Lê um arquivo gerado por [exportarBloco]. Devolve o nome do bloco e
     * quantas pessoas entraram, ou `null` se o arquivo não for um bloco.
     */
    fun importarBloco(texto: String): Pair<String, Int>? = try {
        val o = JSONObject(texto)
        if (o.optString("_tipo") != TIPO_BLOCO) null
        else {
            val nome = o.optString("nome").trim()
            val arr = o.optJSONArray("pessoas") ?: JSONArray()
            // Arquivo do PADRÃO entra no padrão daqui, com o nome local. As
            // pessoas dele viajam sem nome de bloco (ver exportarJson), então
            // caem sozinhas no lugar certo.
            if (o.optBoolean("padrao")) nomePadrao() to importarJson(arr)
            else {
                // Garante o bloco mesmo vazio: importar uma equipe que ainda
                // não assinou tem que criar a equipe, senão o usuário importa,
                // não vê nada mudar e conclui que o arquivo estava errado.
                resolverBlocoPorNome(nome)
                nome to importarJson(arr)
            }
        }
    } catch (_: Exception) { null }

    private fun gravar(lista: List<Pessoa>) {
        val arr = JSONArray()
        lista.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("cargo", p.cargo); put("nome", p.nome)
                put("registro", p.registro); put("assinatura", p.assinatura)
                put("bloco", p.blocoId)
            })
        }
        arquivo.writeText(arr.toString())
    }

    private fun gravarBlocos(lista: List<Bloco>) {
        val arr = JSONArray()
        lista.forEach { b ->
            arr.put(JSONObject().apply { put("id", b.id); put("nome", b.nome) })
        }
        arquivoBlocos.writeText(arr.toString())
    }
}

/** Marca do arquivo de bloco avulso. Ver [RubricarioStore.exportarBloco]. */
private const val TIPO_BLOCO = "photoid_rt_rubricario_bloco"
