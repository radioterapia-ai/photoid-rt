package com.radioterapia.ai.protocolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Protocolos: as páginas finais que cada serviço acrescenta à ficha.
 *
 * O QUE É UM PROTOCOLO. Um conjunto de PDFs que o próprio serviço já usa —
 * termo de consentimento, orientações ao paciente, checklist interno — e que
 * passa a sair impresso junto da ficha de posicionamento. O app **não edita**
 * esses PDFs: ele os reproduz página a página e sobrepõe, no máximo, a etiqueta
 * de identificação e o logotipo, nas posições que o usuário calibrar.
 *
 * O PROTOCOLO PADRÃO é a ficha como ela sempre foi: Time-Out, fotos e
 * rubricário, sem nenhuma página acrescentada. Ele existe como item da lista
 * para que a tela de seleção tenha sentido quando há mais de um — e não pode
 * ser excluído, porque é o estado ao qual sempre se pode voltar.
 *
 * ONDE MORA: `filesDir/protocolos/`, com um `lista.json` e uma subpasta por
 * protocolo guardando os PDFs e a miniatura. Subpasta por protocolo, e não
 * todos os arquivos juntos com prefixo: excluir um protocolo passa a ser apagar
 * uma pasta, e não varrer nomes — que é onde se esquece um arquivo e ele fica
 * ocupando espaço para sempre.
 *
 * As posições da etiqueta e do logotipo são guardadas em MILÍMETROS, não em
 * pixels nem em pontos. O PDF do usuário pode ter qualquer tamanho de página, e
 * milímetro é a única unidade que significa a mesma coisa em todos eles — foi
 * assim que a etiqueta física de 100x50 mm entrou na configuração.
 */
class ProtocoloStore(private val context: Context) {

    private val dir = File(context.filesDir, "protocolos").apply { mkdirs() }
    private val arquivo = File(dir, "lista.json")

    /** Pasta de um protocolo. Os PDFs e a miniatura moram aqui. */
    fun pastaDe(id: String): File = File(dir, id).apply { mkdirs() }

    /**
     * Uma página do protocolo.
     *
     * @param arquivo nome do PDF dentro da pasta do protocolo.
     * @param etqAtiva se a etiqueta de identificação é sobreposta nesta página.
     * @param etqXmm distância da BORDA ESQUERDA da página, em mm.
     * @param etqYmm distância do TOPO da página, em mm.
     * @param etqWmm largura da etiqueta, em mm. Limitada a 100 (ver [ETQ_MAX_W]).
     * @param etqHmm altura da etiqueta, em mm. Limitada a 50 (ver [ETQ_MAX_H]).
     * @param logoAtivo se o logotipo do serviço é sobreposto nesta página.
     * @param logoXmm distância da BORDA DIREITA, em mm — igual às demais
     *   páginas, o logo é ancorado à direita.
     * @param logoYmm distância do TOPO, em mm.
     */
    data class Pagina(
        val arquivo: String,
        val etqAtiva: Boolean = true,
        val etqXmm: Float = 10f,
        val etqYmm: Float = 10f,
        val etqWmm: Float = 60f,
        val etqHmm: Float = 30f,
        val logoAtivo: Boolean = false,
        val logoXmm: Float = MARGEM_PADRAO_MM,
        val logoYmm: Float = MARGEM_PADRAO_MM
    )

    data class Protocolo(
        val id: String,
        val nome: String,
        val padrao: Boolean,
        /** Nome do PNG da miniatura dentro da pasta. Vazio = sem miniatura. */
        val miniatura: String = "",
        /**
         * Qual equipe entra na página de rubricário desta ficha.
         *
         * É a PRIMEIRA página do protocolo, e a primeira coisa que se escolhe
         * ao editá-lo. Um tablet que atende duas clínicas tem um protocolo por
         * clínica, e é este campo que impede a folha de sair com a equipe da
         * outra — que é o defeito que os blocos vieram corrigir.
         */
        val rubricarioId: String =
            com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO,
        val paginas: List<Pagina> = emptyList()
    )

    companion object {
        const val ID_PADRAO = "padrao"

        /**
         * Distância da margem que o logotipo já usa nas demais páginas.
         *
         * MARGIN do PdfBuilder são 28 pt (~9,9 mm), e o logo é desenhado a
         * partir dela. Este é o valor com que o editor abre — a calibração fina
         * fica com o usuário, porque o PDF dele pode ter o próprio cabeçalho
         * ocupando exatamente esse canto.
         */
        const val MARGEM_PADRAO_MM = 10f

        /** Teto da etiqueta, em mm. É o maior formato que a configuração aceita. */
        const val ETQ_MAX_W = 100f
        const val ETQ_MAX_H = 50f
    }

    // ---------------------------------------------------------------- leitura

    /**
     * Todos os protocolos, com o padrão SEMPRE em primeiro e SEMPRE presente.
     *
     * Garantir o padrão na leitura, e não só na primeira gravação, é o que faz
     * a lista sobreviver a um `lista.json` corrompido, apagado à mão ou vindo
     * de uma importação incompleta: no pior caso o serviço volta a ter a ficha
     * como ela sempre foi, em vez de uma tela de seleção vazia.
     */
    fun listar(): List<Protocolo> {
        val lidos = try {
            if (!arquivo.exists()) emptyList()
            else {
                val arr = JSONArray(arquivo.readText())
                (0 until arr.length()).mapNotNull { i -> deJson(arr.optJSONObject(i)) }
            }
        } catch (_: Exception) { emptyList() }

        val padrao = lidos.firstOrNull { it.id == ID_PADRAO }
            ?: Protocolo(ID_PADRAO, nomePadrao(), true)
        return listOf(padrao) + lidos.filter { it.id != ID_PADRAO }
    }

    fun obter(id: String): Protocolo? = listar().firstOrNull { it.id == id }

    /** Há mais de um protocolo? A tela de seleção só aparece quando sim. */
    fun temEscolha(): Boolean = listar().size > 1

    fun arquivoMiniatura(p: Protocolo): File? =
        File(pastaDe(p.id), p.miniatura).takeIf { p.miniatura.isNotBlank() && it.exists() }

    fun bitmapMiniatura(p: Protocolo): Bitmap? = try {
        arquivoMiniatura(p)?.let {
            val op = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(it.absolutePath, op)
        }
    } catch (_: Throwable) { null }

    /** PDF de uma página, se ainda existir em disco. */
    fun arquivoPagina(p: Protocolo, pag: Pagina): File? =
        File(pastaDe(p.id), pag.arquivo).takeIf { it.exists() && it.length() > 0 }

    // ---------------------------------------------------------------- escrita

    /** Cria ou atualiza. O protocolo padrão pode ser editado como qualquer outro. */
    fun salvar(p: Protocolo): Boolean = try {
        val atuais = listar().toMutableList()
        val i = atuais.indexOfFirst { it.id == p.id }
        if (i >= 0) atuais[i] = p else atuais.add(p)
        gravar(atuais)
        true
    } catch (_: Exception) { false }

    /**
     * Exclui um protocolo e a pasta dele.
     *
     * O PADRÃO NÃO SAI. Ele é o estado ao qual sempre se pode voltar, e sem ele
     * um serviço que apagasse tudo ficaria sem nenhuma opção na tela de
     * seleção — sem forma de imprimir a ficha simples.
     */
    fun excluir(id: String): Boolean {
        if (id == ID_PADRAO) return false
        return try {
            val atuais = listar().filter { it.id != id }
            gravar(atuais)
            pastaDe(id).deleteRecursively()
            true
        } catch (_: Exception) { false }
    }

    fun novoId(): String = "p" + System.currentTimeMillis()

    private fun nomePadrao(): String =
        try { context.getString(com.radioterapia.ai.R.string.prot_padrao_nome) }
        catch (_: Exception) { "Padrão" }

    // ---------------------------------------------------------------- transferência

    /**
     * A lista de protocolos, SEM os binários.
     *
     * Os PDFs e as miniaturas viajam como arquivos próprios dentro do pacote,
     * não em Base64 aqui. Um termo de consentimento de três páginas passa
     * facilmente de um megabyte, e embutir isso num JSON produziria um arquivo
     * que o tablet não consegue nem abrir para ler o resto da configuração.
     */
    fun exportarJson(): JSONArray {
        val arr = JSONArray()
        // Para dizer QUAL EQUIPE por nome, e não pelo id: id de bloco é carimbo
        // de tempo do tablet de origem, e a mesma "Clínica A" nasce com outro id
        // no destino. Guardar só o id faria o protocolo importado cair no bloco
        // padrão — a ficha sairia assinada pela equipe errada, em silêncio.
        val nomesBloco = com.radioterapia.ai.rubricario.RubricarioStore(context).listarBlocos().associate { it.id to it.nome }
        listar().forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("nome", p.nome); put("miniatura", p.miniatura)
                put("rubricario", p.rubricarioId)
                put("rubricario_nome",
                    if (p.rubricarioId == com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO) ""
                    else nomesBloco[p.rubricarioId].orEmpty())
                put("paginas", JSONArray().apply {
                    p.paginas.forEach { pg ->
                        put(JSONObject().apply {
                            put("arquivo", pg.arquivo)
                            put("etq", pg.etqAtiva)
                            put("ex", pg.etqXmm.toDouble()); put("ey", pg.etqYmm.toDouble())
                            put("ew", pg.etqWmm.toDouble()); put("eh", pg.etqHmm.toDouble())
                            put("lg", pg.logoAtivo)
                            put("lx", pg.logoXmm.toDouble()); put("ly", pg.logoYmm.toDouble())
                        })
                    }
                })
            })
        }
        return arr
    }

    /**
     * Aplica uma lista vinda de outro tablet. Devolve quantos entraram.
     *
     * SOMAR não toca no que já existe: um protocolo cujo id já está aqui é
     * pulado inteiro, porque substituí-lo em silêncio trocaria os PDFs que o
     * serviço local carregou pelos de outra unidade. O PADRÃO é sempre pulado
     * no modo somar — ele existe em todo tablet, e importá-lo apagaria a
     * calibração local.
     */
    fun importarJson(arr: JSONArray, sobrescrever: Boolean): Int {
        var n = 0
        val atuais = listar().associateBy { it.id }
        val rub = com.radioterapia.ai.rubricario.RubricarioStore(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            // A equipe é reencontrada (ou recriada) PELO NOME. Nome vazio é o
            // padrão — inclusive em pacote antigo, que não tinha este campo e
            // vinha de um tablet sem blocos.
            val p = deJson(o)?.let {
                it.copy(rubricarioId = rub.garantirBloco(
                    o?.optString("rubricario_nome").orEmpty().trim()))
            } ?: continue
            val existe = atuais.containsKey(p.id)
            if (existe && !sobrescrever) continue
            if (p.id == ID_PADRAO && !sobrescrever) continue
            if (salvar(p)) n++
        }
        return n
    }

    // ---------------------------------------------------------------- json

    private fun deJson(o: JSONObject?): Protocolo? {
        if (o == null) return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        val arrP = o.optJSONArray("paginas")
        val paginas = if (arrP == null) emptyList() else
            (0 until arrP.length()).mapNotNull { i ->
                val po = arrP.optJSONObject(i) ?: return@mapNotNull null
                val arq = po.optString("arquivo")
                if (arq.isBlank()) null else Pagina(
                    arquivo = arq,
                    etqAtiva = po.optBoolean("etq", true),
                    etqXmm = po.optDouble("ex", 10.0).toFloat(),
                    etqYmm = po.optDouble("ey", 10.0).toFloat(),
                    etqWmm = po.optDouble("ew", 60.0).toFloat(),
                    etqHmm = po.optDouble("eh", 30.0).toFloat(),
                    logoAtivo = po.optBoolean("lg", false),
                    logoXmm = po.optDouble("lx", MARGEM_PADRAO_MM.toDouble()).toFloat(),
                    logoYmm = po.optDouble("ly", MARGEM_PADRAO_MM.toDouble()).toFloat())
            }
        return Protocolo(
            id = id,
            nome = o.optString("nome"),
            padrao = id == ID_PADRAO,
            miniatura = o.optString("miniatura"),
            rubricarioId = o.optString("rubricario",
                com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO)
                .ifBlank { com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO },
            paginas = paginas)
    }

    private fun gravar(lista: List<Protocolo>) {
        val arr = JSONArray()
        lista.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("nome", p.nome); put("miniatura", p.miniatura)
                put("rubricario", p.rubricarioId)
                put("paginas", JSONArray().apply {
                    p.paginas.forEach { pg ->
                        put(JSONObject().apply {
                            put("arquivo", pg.arquivo)
                            put("etq", pg.etqAtiva)
                            put("ex", pg.etqXmm.toDouble()); put("ey", pg.etqYmm.toDouble())
                            put("ew", pg.etqWmm.toDouble()); put("eh", pg.etqHmm.toDouble())
                            put("lg", pg.logoAtivo)
                            put("lx", pg.logoXmm.toDouble()); put("ly", pg.logoYmm.toDouble())
                        })
                    }
                })
            })
        }
        arquivo.writeText(arr.toString())
    }
}
