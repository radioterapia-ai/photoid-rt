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
 *   ARQUIVADAS/             substituídas NESTA sessão; esvaziada por [limparSessao]
 *
 * Fora da sessão, também em filesDir: arquivadas_retidas/, as arquivadas que a
 * finalização não conseguiu levar à pasta do paciente ([reterArquivadas]).
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

        /** Chave da meta com os arquivos que a reconstrução trouxe da pasta. */
        private const val CHAVE_RECONSTRUIDOS = "edit_reconstruidos"

        /** Pasta das pastas de paciente, dentro da base (ver StorageLocal.photos). */
        private const val PASTA_PHOTOS = "PHOTOS"

        /**
         * A origem mora DIRETO na pasta do paciente em edição (`PHOTOS/<pasta>/`)?
         *
         * Só esse arquivo pode ser contado como reconstruído: a regravação apaga
         * pelo caminho registrado, e o caminho de uma cópia de cache (servidor,
         * pasta escolhida por SAF) ou de uma foto de `ARQUIVADAS/` não é o do
         * arquivo da pasta. Pasta de outro paciente também não casa — o nome
         * comparado é o da pasta inteira, com o prontuário.
         */
        fun ehDaPastaEditada(origem: File, nomePastaEditada: String): Boolean {
            if (nomePastaEditada.isBlank()) return false
            val pai = origem.parentFile ?: return false
            return pai.name == nomePastaEditada && pai.parentFile?.name == PASTA_PHOTOS
        }

        /**
         * Move `ARQUIVADAS/` de [sessao] para uma subpasta nova de [retidas], sem
         * apagar nada.
         *
         * Primeiro por renomeação: sessão e retidas ficam no mesmo volume
         * (`filesDir`), e renomear não precisa de espaço livre — que é o que
         * costuma faltar quando a cópia para a pasta do paciente falhou. Pelo
         * mesmo motivo, se nem a pasta [retidas] puder ser criada, a subpasta
         * vai para o lado dela, com o nome dela como prefixo: renomear não cria
         * pasta nova. Se a renomeação falhar, copia, confere o tamanho de cada
         * arquivo e só então remove a origem. Cópia incompleta deixa a origem
         * intacta.
         *
         * @return a pasta onde as arquivadas ficaram, ou `null` quando não havia
         *   arquivo ou nenhum dos caminhos deu certo.
         */
        internal fun reterArquivadasEm(sessao: File, retidas: File, rotulo: String,
                                       agoraMs: Long): File? {
            val origem = com.radioterapia.ai.util.FotosArquivadas.pasta(sessao)
            val arquivos = origem.listFiles()?.filter { it.isFile }.orEmpty()
            if (arquivos.isEmpty()) return null
            retidas.mkdirs()
            val nome = rotuloSeguro(rotulo) + "_" + agoraMs
            val destino = if (retidas.isDirectory) destinoLivre(retidas, nome)
                          else destinoLivre(retidas.parentFile ?: return null, retidas.name + "_" + nome)
            if (origem.renameTo(destino)) return destino
            return try {
                if (!destino.mkdirs()) return null
                arquivos.forEach { a ->
                    val copia = File(destino, a.name)
                    a.copyTo(copia, overwrite = true)
                    if (copia.length() != a.length()) return null
                }
                arquivos.forEach { it.delete() }
                origem.delete()
                destino
            } catch (_: Exception) { null }
        }

        /** Rótulo da pasta retida só com letras, dígitos, espaço, `-` e `_`. */
        private fun rotuloSeguro(rotulo: String): String =
            rotulo.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().take(60).ifBlank { "SESSAO" }

        /** O nome pedido, ou o mesmo com `_2`, `_3`... quando já existe. */
        private fun destinoLivre(pai: File, nome: String): File {
            var f = File(pai, nome)
            var n = 2
            while (f.exists()) { f = File(pai, "${nome}_$n"); n++ }
            return f
        }
    }

    private val baseDir: File = File(context.filesDir, "sessao_atual").apply {
        if (!exists()) mkdirs()
    }
    private val arquivoMetadados: File = File(baseDir, "_metadata.json")

    /**
     * Para onde vai a subpasta de arquivadas que resistir à exclusão em
     * [limparSessao]. Fica no cache, e não em `filesDir`: o que sobrar ali é
     * descartável, e nada que copie `filesDir` o leva junto.
     */
    private val descarte: File = File(context.cacheDir, "sessao_descartada")

    /**
     * Para onde vão as arquivadas da sessão que não chegaram à pasta do
     * paciente (ver [reterArquivadas]). Fica em `filesDir`, e não no cache: o
     * sistema limpa o cache sozinho, e o que está aqui é a única cópia. Nenhuma
     * rotina do app apaga esta pasta.
     */
    private val retidas: File = File(context.filesDir, "arquivadas_retidas")

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
    /**
     * Adiciona uma foto copiando de uma origem SEM apagar o original (reconstrução
     * da sessão da pasta, restauração de arquivada).
     *
     * Quando a origem está direto na pasta em edição ([ehDaPastaEditada]), ela
     * entra em [reconstruidos] — e só depois de a cópia ter dado certo.
     */
    fun adicionarCopiando(origem: File, categoria: Category): File? = try {
        val tmp = File(baseDir, "_import_${System.currentTimeMillis()}.jpg")
        origem.copyTo(tmp, overwrite = true)
        adicionarFoto(tmp, categoria).also {  // consome tmp; origem permanece
            if (ehDaPastaEditada(origem, editandoNomePasta)) registrarReconstruido(origem)
        }
    } catch (e: Exception) {
        // Disco cheio / arquivo sumiu / permissão revogada: não derruba a
        // reconstrução da sessão — a foto que falhou simplesmente não entra, e
        // por isso também não entra em reconstruidos(): a regravação a deixa
        // na pasta.
        null
    }

    /**
     * Reconstrução da sessão a partir da pasta do paciente: a foto e, quando
     * existe, o quadro cheio (`_ORIGINAL`) que a acompanha.
     *
     * A regravação em modo edição grava a sessão inteira com nomes novos e
     * depois tira da pasta o que foi trazido para cá. O quadro cheio que não
     * viesse junto seria gravado de volta só se estivesse na sessão — por isso
     * ele é copiado aqui, e registrado em [reconstruidos] apenas quando a cópia
     * deu certo. Quadro cheio que falhou fica na pasta.
     *
     * Roda fora da thread principal: copia arquivos.
     *
     * @return a foto já na sessão, ou `null` quando ela não entrou.
     */
    fun adicionarDaPasta(origem: File, categoria: Category): File? {
        val daSessao = adicionarCopiando(origem, categoria) ?: return null
        val original = com.radioterapia.ai.util.NomeArquivo.originalDe(origem) ?: return daSessao
        val destino = File(baseDir, daSessao.nameWithoutExtension + SUFIXO_ORIGINAL)
        try {
            original.copyTo(destino, overwrite = true)
            if (ehDaPastaEditada(origem, editandoNomePasta)) registrarReconstruido(original)
        } catch (_: Exception) {
            destino.delete()
        }
        return daSessao
    }

    /**
     * Caminhos absolutos dos arquivos da pasta em edição que a reconstrução
     * trouxe para a sessão: fotos e quadros cheios que de fato entraram.
     *
     * A regravação em modo edição tira da pasta SÓ estes (e as fichas desta
     * simulação, que a ficha nova substitui). O que não voltou para a sessão —
     * cópia que falhou, quadro cheio que não veio, arquivo sem tipo
     * reconhecível, foto legada que a classificação por nome não alcança,
     * DICOM — fica onde está: apagar o que não vai ser gravado de novo é
     * perder o arquivo.
     */
    fun reconstruidos(): Set<String> {
        val arr = meta.optJSONArray(CHAVE_RECONSTRUIDOS) ?: return emptySet()
        val saida = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i, "").takeIf { it.isNotBlank() }?.let { saida.add(it) }
        }
        return saida
    }

    private fun registrarReconstruido(arquivo: File) {
        val arr = meta.optJSONArray(CHAVE_RECONSTRUIDOS)
            ?: org.json.JSONArray().also { meta.put(CHAVE_RECONSTRUIDOS, it) }
        arr.put(arquivo.absolutePath)
        salvarMeta()
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
     * jogado fora não volta. Fica ao lado, com sufixo, e o sincronizador leva os
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

    /**
     * Tira `ARQUIVADAS/` da sessão SEM apagar, para [retidas].
     *
     * Para quando a cópia das arquivadas para a pasta do paciente falhou (disco
     * cheio, pasta SAF recusada). [limparSessao] apaga `ARQUIVADAS/` de
     * propósito — deixá-la para a sessão seguinte poria as fotos deste paciente
     * na pasta do próximo —, e sem este passo a foto substituída na sala, que
     * não chegou a lugar nenhum, iria junto.
     *
     * Roda fora da thread principal.
     *
     * @param rotulo nome da pasta do paciente, para a pasta retida ser
     *   reconhecível.
     * @return a pasta onde ficaram, ou `null` quando não havia arquivo ou nada
     *   pôde ser movido.
     */
    fun reterArquivadas(rotulo: String): File? =
        reterArquivadasEm(baseDir, retidas, rotulo, System.currentTimeMillis())

    /**
     * Esvazia a sessão: fotos, metadados e a subpasta `ARQUIVADAS/`.
     *
     * GUARDA: subpasta sai com `deleteRecursively`, e o resultado é conferido.
     * `File.delete()` numa pasta com arquivos devolve `false` sem lançar nada, e
     * `ARQUIVADAS/` ficaria para a sessão seguinte. Como a finalização copia o
     * que está nessa subpasta para a pasta do paciente (`FotosArquivadas.transferir`),
     * o rosto e a etiqueta arquivados de um paciente iriam parar na pasta de
     * todos os que viessem depois, subiriam ao servidor e seriam oferecidos para
     * restaurar na ficha de outra pessoa.
     *
     * Quando a subpasta resiste à exclusão, ela é tirada da sessão por
     * renomeação, para [descarte]: o que importa é a sessão seguinte começar sem
     * arquivo de ninguém.
     *
     * Esta função não sabe se as arquivadas chegaram à pasta do paciente. Quem
     * finaliza confere a cópia e, quando ela ficou incompleta, chama
     * [reterArquivadas] ANTES — senão a única cópia vai embora aqui.
     *
     * @return `true` quando a sessão ficou vazia.
     */
    fun limparSessao(): Boolean {
        _fotos.clear()
        ultimaArquivada = null
        if (baseDir.exists()) {
            baseDir.listFiles()?.forEach { f ->
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
        meta = JSONObject()
        salvarMeta()

        val arquivadas = com.radioterapia.ai.util.FotosArquivadas.pasta(baseDir)
        if (arquivadas.exists()) {
            descarte.mkdirs()
            val fora = File(descarte, System.currentTimeMillis().toString())
            if (arquivadas.renameTo(fora)) fora.deleteRecursively()
        }
        val restantes = baseDir.listFiles()?.filter { it != arquivoMetadados }.orEmpty()
        return restantes.isEmpty()
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
        // VOCABULARIO CANONICO EM PORTUGUES, DE PROPOSITO — nao e literal
        // esquecido. O PdfBuilder recebe estes rotulos em PT e os traduz no
        // ponto de desenho, em traduzirRotulo(). Trocar por getString aqui
        // QUEBRA o mapa: "Rosto" deixa de casar e o rotulo sai sem traducao.
        _fotos.firstOrNull { it.categoria == Category.FACE }?.let { rotulos.add("Rosto") }
        _fotos.firstOrNull { it.categoria == Category.LABEL }?.let { rotulos.add("Etiqueta") }
        _fotos.filter { it.categoria == Category.POSITIONING }.sortedBy { it.timestampMs }
            .forEachIndexed { i, _ -> rotulos.add("Posicionamento.${i + 1}") }
        _fotos.filter { it.categoria == Category.ACCESSORIES }.sortedBy { it.timestampMs }
            .forEachIndexed { i, _ -> rotulos.add("Acessório.${i + 1}") }
        return rotulos
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
