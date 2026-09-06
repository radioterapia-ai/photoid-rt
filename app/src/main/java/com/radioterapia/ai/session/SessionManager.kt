package com.radioterapia.ai.session

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gerencia o rascunho da simulação atual: fotos categorizadas + metadados do paciente.
 *
 * Categorias e limites:
 *   - Rosto: 1 foto (substituída se for tirada outra)
 *   - Etiqueta: 1 foto (idem)
 *   - Acessórios: 1 foto (idem)
 *   - Posicionamento: ilimitado
 *
 * O rascunho é persistente — sobrevive ao fechamento do app.
 * Limite de validade: 30 dias.
 *
 * Estrutura no disco (filesDir/sessao_atual/):
 *   _metadata.json
 *   _rosto.jpg              (categoria FACE)
 *   _etiqueta.jpg           (categoria LABEL)
 *   _acessorios.jpg         (categoria ACCESSORIES)
 *   _pos_<timestamp>_N.jpg  (categoria POSITIONING)
 */
class SessionManager(context: Context) {

    enum class Category(val codigo: String, val unico: Boolean, val nomeArquivoBase: String) {
        FACE("rosto", true, "_rosto"),
        LABEL("etiqueta", true, "_etiqueta"),
        POSITIONING("posicionamento", false, "_pos"),
        ACCESSORIES("acessorios", false, "_acessorios"),
        // Scan de impressos (laudos/anotações). NÃO entra no PDF da folha.
        DOCUMENTS("documentos", false, "_doc")
    }

    data class FotoCategorizada(val arquivo: File, val categoria: Category, val timestampMs: Long)

    companion object {
        /** Sufixo do arquivo que guarda o quadro cheio, sem recorte. */
        const val SUFIXO_ORIGINAL = "_ORIGINAL.jpg"
    }

    private val baseDir: File = File(context.filesDir, "sessao_atual").apply {
        if (!exists()) mkdirs()
    }
    private val arquivoMetadados: File = File(baseDir, "_metadata.json")

    /** Lista global de fotos da sessão (todas as categorias). */
    private val _fotos = mutableListOf<FotoCategorizada>()
    val fotos: List<FotoCategorizada> get() = _fotos.toList()

    private var meta = JSONObject()

    init {
        carregar()
        verificarExpiracao()
    }

    /** Recarrega o estado do disco. Necessário em telas que ficam no back stack
     *  (ex.: RT Sim) e precisam refletir um rascunho criado em outra Activity. */
    fun recarregar() {
        _fotos.clear()
        meta = JSONObject()
        carregar()
        verificarExpiracao()
    }

    // ----------- Metadados -----------

    var nomePaciente: String
        get() = meta.optString("nome", "")
        set(value) { meta.put("nome", value); meta.put("ultima_modificacao", System.currentTimeMillis()); salvarMeta() }

    var prontuario: String
        get() = meta.optString("prontuario", "")
        set(value) { meta.put("prontuario", value); salvarMeta() }

    var dataNascimento: String
        get() = meta.optString("nascimento", "")
        set(value) { meta.put("nascimento", value); salvarMeta() }

    var idSimulacao: String
        get() = meta.optString("id_simulacao", "")
        set(value) { meta.put("id_simulacao", value); salvarMeta() }

    var textoEtiquetaOcr: String
        get() = meta.optString("ocr_etiqueta", "")
        set(value) { meta.put("ocr_etiqueta", value); salvarMeta() }

    /**
     * IDs extras vindos do CSV (Convênio, CPF, etc).
     * Lista serializada como JSON com pares título/valor.
     */
    fun salvarIdsExtras(extras: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        extras.forEach { (titulo, valor) ->
            val o = JSONObject()
            o.put("titulo", titulo)
            o.put("valor", valor)
            arr.put(o)
        }
        meta.put("ids_extras", arr)
        salvarMeta()
    }

    fun obterIdsExtras(): List<Pair<String, String>> {
        val arr = meta.optJSONArray("ids_extras") ?: return emptyList()
        val lista = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                lista.add(o.getString("titulo") to o.getString("valor"))
            } catch (_: Exception) {}
        }
        return lista
    }

    /** Timestamp de quando a sessão começou (ms). */
    val timestampInicio: Long
        get() = meta.optLong("inicio", 0)

    /** Categoria atualmente selecionada na UI da câmera. Persistida para sobreviver a giros/restart. */
    var categoriaAtiva: Category
        get() = try { Category.valueOf(meta.optString("categoria_ativa", Category.FACE.name)) }
                catch (e: Exception) { Category.FACE }
        set(value) { meta.put("categoria_ativa", value.name); salvarMeta() }

    fun marcarInicio() {
        if (meta.optLong("inicio", 0) == 0L) {
            meta.put("inicio", System.currentTimeMillis())
            salvarMeta()
        }
    }

    // ----------- Fotos -----------

    fun temFotos(): Boolean = _fotos.isNotEmpty()
    fun temRascunho(): Boolean = _fotos.isNotEmpty() || nomePaciente.isNotBlank()
    fun quantidade(): Int = _fotos.size
    fun quantidadeCategoria(c: Category): Int = _fotos.count { it.categoria == c }
    fun obterDeCategoria(c: Category): List<FotoCategorizada> = _fotos.filter { it.categoria == c }
    fun temFotoRosto(): Boolean = _fotos.any { it.categoria == Category.FACE }
    fun temFotoEtiqueta(): Boolean = _fotos.any { it.categoria == Category.LABEL }
    fun temFotoAcessorios(): Boolean = _fotos.any { it.categoria == Category.ACCESSORIES }

    /**
     * Adiciona foto à categoria.
     * Para categorias únicas (rosto, etiqueta, acessórios) substitui a anterior.
     */
    /** Adiciona uma foto copiando de uma origem SEM apagar o original (reconstrução da sessão da pasta). */
    fun adicionarCopiando(origem: File, categoria: Category): File? = try {
        val tmp = File(baseDir, "_import_${System.currentTimeMillis()}.jpg")
        origem.copyTo(tmp, overwrite = true)
        adicionarFoto(tmp, categoria)  // consome tmp; origem permanece
    } catch (e: Exception) {
        // Disco cheio / arquivo sumiu / permissão revogada: não derruba a
        // reconstrução da sessão — a foto que falhou simplesmente não entra.
        null
    }

    /** Nome da pasta da simulação sendo EDITADA via "adicionar mais fotos" (vazio = simulação nova). */
    var editandoNomePasta: String
        get() = meta.optString("edit_pasta", "")
        set(value) { meta.put("edit_pasta", value); salvarMeta() }

    /** Número da simulação sendo editada (para não incrementar a contagem). */
    var editandoNumSim: Int
        get() = meta.optInt("edit_numsim", 0)
        set(value) { meta.put("edit_numsim", value); salvarMeta() }

    /**
     * Última foto arquivada por [adicionarFoto] ao substituir uma categoria
     * única. A tela lê para avisar o que aconteceu; nula quando nada saiu.
     */
    var ultimaArquivada: File? = null
        private set

    fun adicionarFoto(temp: File, categoria: Category): File {
        val timestamp = System.currentTimeMillis()
        ultimaArquivada = null
        val destino = if (categoria.unico) {
            File(baseDir, "${categoria.nomeArquivoBase}.jpg").also { existente ->
                _fotos.removeAll { it.categoria == categoria && it.arquivo == existente }
                // A ANTERIOR É ARQUIVADA, não apagada.
                //
                // Categoria única guarda uma foto só, e mandar outra para o
                // rosto ou a etiqueta destruía a que estava lá — sem aviso e sem
                // volta. Quem trocava a foto do rosto por uma da galeria e se
                // arrependia não tinha o que fazer: o paciente já tinha saído.
                //
                // Arquivada, ela some da ficha e do carrossel mas continua na
                // pasta, e a tela de importação sabe trazê-la de volta.
                if (existente.exists()) {
                    ultimaArquivada =
                        com.radioterapia.ai.util.FotosArquivadas.arquivar(existente)
                    if (existente.exists()) existente.delete()
                }
            }
        } else {
            File(baseDir, "${categoria.nomeArquivoBase}_${timestamp}.jpg")
        }
        if (temp != destino) {
            temp.copyTo(destino, overwrite = true)
            temp.delete()
        }
        _fotos.add(FotoCategorizada(destino, categoria, timestamp))
        salvarFotos()
        return destino
    }

    /**
     * Guarda a foto ORIGINAL — o quadro cheio, como saiu da câmera, antes do
     * recorte 16:9 e antes do EXIF ser reescrito.
     *
     * O app grava a foto já processada: o recorte sobrescreve o arquivo no
     * lugar, e o original deixa de existir. Para a documentação de
     * posicionamento isso basta, mas o quadro cheio tem informação que o
     * recorte descarta (a maca inteira, o acessório na borda), e uma vez
     * jogado fora não volta. Fica ao lado, com sufixo, e o FileSync leva os
     * dois.
     *
     * Custo: praticamente dobra o espaço por foto. É por isso que o aviso de
     * disco existe.
     */
    fun guardarOriginal(temp: File, fotoDaSessao: File): Boolean = try {
        val dest = File(baseDir, fotoDaSessao.nameWithoutExtension + SUFIXO_ORIGINAL)
        temp.copyTo(dest, overwrite = true)
        temp.delete()
        true
    } catch (_: Exception) { false }

    /** Original intacto correspondente, ou null se não foi guardado. */
    fun originalDe(fotoDaSessao: File): File? =
        File(baseDir, fotoDaSessao.nameWithoutExtension + SUFIXO_ORIGINAL)
            .takeIf { it.exists() && it.length() > 0 }

    /**
     * Tira a foto da sessão e a APAGA.
     *
     * Descartar continua sendo destruir, ao contrário da substituição (ver
     * [adicionarFoto]): aqui o técnico olhou a foto e disse que não presta —
     * borrada, de teste, enquadramento errado. Guardar isso encheria o disco do
     * tablet com o que ninguém vai procurar.
     */
    fun removerFoto(arquivo: File) {
        val item = _fotos.find { it.arquivo == arquivo } ?: return
        _fotos.remove(item)
        if (item.arquivo.exists()) item.arquivo.delete()
        originalDe(item.arquivo)?.delete()
        salvarFotos()
    }

    /** Fotos arquivadas nesta sessão, para a tela de importação oferecer. */
    fun arquivadas(): List<File> =
        com.radioterapia.ai.util.FotosArquivadas.listar(baseDir)

    /** Pasta de trabalho da sessão, para quem precisa transferir o arquivo. */
    fun pastaDeTrabalho(): File = baseDir

    fun limparSessao() {
        _fotos.clear()
        if (baseDir.exists()) {
            baseDir.listFiles()?.forEach { it.delete() }
        }
        meta = JSONObject()
        salvarMeta()
    }

    /**
     * Lista das fotos ordenadas conforme aparecem no PDF:
     * 1) rosto, 2) etiqueta, 3) posicionamento (na ordem em que foram tiradas)
     * (acessórios não entra aqui — vai pra um PDF separado)
     */
    fun fotosOrdenadasParaPdf(): List<FotoCategorizada> {
        // Inclui TODAS as fotos no PDF principal (usuários fotografam acessórios e
        // posições com a ferramenta e precisam de tudo na folha).
        // Ordem: rosto → etiqueta → posicionamentos → acessórios.
        // DOCUMENTS (scan de impressos) fica DE FORA do PDF por decisão de produto.
        val ordenadas = mutableListOf<FotoCategorizada>()
        _fotos.firstOrNull { it.categoria == Category.FACE }?.let { ordenadas.add(it) }
        _fotos.firstOrNull { it.categoria == Category.LABEL }?.let { ordenadas.add(it) }
        ordenadas.addAll(_fotos.filter { it.categoria == Category.POSITIONING }.sortedBy { it.timestampMs })
        ordenadas.addAll(_fotos.filter { it.categoria == Category.ACCESSORIES }.sortedBy { it.timestampMs })
        return ordenadas
    }

    fun fotoAcessorios(): FotoCategorizada? = _fotos.firstOrNull { it.categoria == Category.ACCESSORIES }

    /** Rótulos paralelos a fotosOrdenadasParaPdf(): "Rosto", "Etiqueta",
     *  "Posicionamento.1..N", "Acessório.1..N". */
    fun rotulosParaPdf(): List<String> {
        val rotulos = mutableListOf<String>()
        _fotos.firstOrNull { it.categoria == Category.FACE }?.let { rotulos.add("Rosto") }
        _fotos.firstOrNull { it.categoria == Category.LABEL }?.let { rotulos.add("Etiqueta") }
        _fotos.filter { it.categoria == Category.POSITIONING }.sortedBy { it.timestampMs }
            .forEachIndexed { i, _ -> rotulos.add("Posicionamento.${i + 1}") }
        _fotos.filter { it.categoria == Category.ACCESSORIES }.sortedBy { it.timestampMs }
            .forEachIndexed { i, _ -> rotulos.add("Acessório.${i + 1}") }
        return rotulos
    }

    /** Tags de tipo (sem acento) paralelas a fotosOrdenadasParaPdf(), para nomear
     *  arquivos de forma que o leitor de tratamento reconheça a categoria.
     *  Valores: "Rosto", "Etiqueta", "Posicionamento", "Acessorios". */
    fun tiposParaPdf(): List<String> {
        val tipos = mutableListOf<String>()
        _fotos.firstOrNull { it.categoria == Category.FACE }?.let { tipos.add("Rosto") }
        _fotos.firstOrNull { it.categoria == Category.LABEL }?.let { tipos.add("Etiqueta") }
        _fotos.filter { it.categoria == Category.POSITIONING }.sortedBy { it.timestampMs }
            .forEach { tipos.add("Posicionamento") }
        _fotos.filter { it.categoria == Category.ACCESSORIES }.sortedBy { it.timestampMs }
            .forEach { tipos.add("Acessorios") }
        return tipos
    }

    // ----------- Persistência -----------

    private fun carregar() {
        if (arquivoMetadados.exists()) {
            try { meta = JSONObject(arquivoMetadados.readText()) } catch (_: Exception) {}
        }
        _fotos.clear()
        val arr = meta.optJSONArray("fotos") ?: return
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                val arquivo = File(obj.getString("path"))
                if (!arquivo.exists()) continue
                val categoria = Category.valueOf(obj.optString("categoria", Category.POSITIONING.name))
                val ts = obj.optLong("ts", arquivo.lastModified())
                _fotos.add(FotoCategorizada(arquivo, categoria, ts))
            } catch (_: Exception) {}
        }
    }

    private fun salvarFotos() {
        val arr = org.json.JSONArray()
        _fotos.forEach { f ->
            val obj = JSONObject()
            obj.put("path", f.arquivo.absolutePath)
            obj.put("categoria", f.categoria.name)
            obj.put("ts", f.timestampMs)
            arr.put(obj)
        }
        meta.put("fotos", arr)
        salvarMeta()
    }

    private fun salvarMeta() {
        try { arquivoMetadados.writeText(meta.toString()) } catch (_: Exception) {}
    }

    private fun verificarExpiracao() {
        val inicio = timestampInicio
        if (inicio == 0L) return
        val agora = System.currentTimeMillis()
        val limite = TimeUnit.DAYS.toMillis(30)
        if (agora - inicio > limite) limparSessao()
    }
}
