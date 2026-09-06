package com.radioterapia.ai.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pacote de transferência da configuração entre tablets.
 *
 * POR QUE UM ZIP, E NÃO UM JSON. A exportação anterior era um JSON com todas as
 * preferências. Servia enquanto o que se transferia era texto, mas o que a
 * clínica precisa levar para um tablet novo inclui binário: o logotipo, as
 * rubricas da equipe e — quando pedido — as fotos dos pacientes. Em Base64
 * dentro de JSON, um acervo de fotos viraria um arquivo que o próprio tablet não
 * consegue abrir na memória. O ZIP carrega texto e binário lado a lado e é
 * aberto por qualquer sistema.
 *
 * O QUE ENTRA É ESCOLHIDO ITEM A ITEM ([Item]). Antes era tudo ou nada, o que na
 * prática impedia dois usos legítimos: levar só a identidade visual para uma
 * unidade nova, e levar a base de pacientes sem levar a configuração de rede da
 * outra unidade junto.
 *
 * O QUE NUNCA ENTRA, mesmo com tudo marcado:
 *
 * - **Senha do SMB.** Credencial não viaja em arquivo que vai por pen-drive.
 * - **Caminhos de pasta escolhidos pelo usuário** (`pasta_fotos_uri`,
 *   `pasta_csv_uri`, `backup_uri`). São URIs do SAF, concedidos a UM aparelho:
 *   no tablet de destino apontam para nada, e o pior é que apontam em silêncio —
 *   o app pareceria configurado e gravaria no lugar errado. Quem importa
 *   reescolhe a pasta, que é um toque.
 */
object PacoteConfig {

    const val MIME = "application/zip"
    private const val MANIFESTO = "manifesto.json"
    private const val VERSAO = 1

    /**
     * O que pode ser transferido, e quais preferências cada coisa carrega.
     *
     * A lista mora aqui, e não na tela: a tela desenha o que este enum declarar.
     * Um item novo aparece na exportação e na importação de uma vez só, sem
     * chance de as duas listas divergirem — que é como uma categoria acaba
     * exportada e nunca importada.
     */
    enum class Item(
        val chave: String,
        val rotulo: Int,
        val descricao: Int,
        /** Chaves de SharedPreferences que este item leva (de `config_radioterapia`). */
        val prefs: List<String> = emptyList(),
        /**
         * Outro arquivo de preferências levado POR INTEIRO.
         *
         * O mapeamento do CSV mora em `csv_mapping`, separado, e é ele que diz
         * qual coluna é nome, nascimento e prontuário — a parte que de fato
         * custa configurar. Sem isto, "Base de pacientes" exportaria só o
         * "tem cabeçalho" e chegaria ao destino sem servir para nada.
         *
         * INTEIRO, e não por chave: aquele arquivo guarda só o mapeamento, e as
         * colunas extras têm nome dinâmico (`extra_titulo_0`, `extra_col_0`…).
         * Listar chave a chave deixaria as extras de fora e ninguém notaria até
         * a primeira importação de base com coluna extra.
         */
        val arquivoInteiro: String? = null,
    ) {
        CLINICA("clinica", com.radioterapia.ai.R.string.tr_clinica,
            com.radioterapia.ai.R.string.tr_clinica_desc,
            listOf("nome_clinica")),

        LOGOTIPO("logotipo", com.radioterapia.ai.R.string.tr_logotipo,
            com.radioterapia.ai.R.string.tr_logotipo_desc),

        MEDICOS("medicos", com.radioterapia.ai.R.string.tr_medicos,
            com.radioterapia.ai.R.string.tr_medicos_desc,
            listOf("timeout_medicos", "equipamentos")),

        SITIOS("sitios", com.radioterapia.ai.R.string.tr_sitios,
            com.radioterapia.ai.R.string.tr_sitios_desc,
            listOf("sitios_lista")),

        RUBRICARIO("rubricario", com.radioterapia.ai.R.string.tr_rubricario,
            com.radioterapia.ai.R.string.tr_rubricario_desc,
            listOf("rubricario_ativo", "rubricario_cargos", "rubricario_retrato")),

        PROTOCOLOS("protocolos", com.radioterapia.ai.R.string.tr_protocolos,
            com.radioterapia.ai.R.string.tr_protocolos_desc),

        IMPRESSORA("impressora", com.radioterapia.ai.R.string.tr_impressora,
            com.radioterapia.ai.R.string.tr_impressora_desc,
            listOf("printer_ip", "printer_nome", "printer_duplex", "pdf_servidor")),

        FICHA("ficha", com.radioterapia.ai.R.string.tr_ficha,
            com.radioterapia.ai.R.string.tr_ficha_desc,
            listOf("pdf_landscape", "pdf_usar_etiqueta", "pdf_etiqueta_largura_mm",
                   "pdf_etiqueta_altura_mm", "pdf_margem_mm", "pdf_inclui_timeout")),

        REDE("rede", com.radioterapia.ai.R.string.tr_rede,
            com.radioterapia.ai.R.string.tr_rede_desc,
            listOf("smb_host", "smb_porta", "smb_protocolo", "smb_dominio",
                   "smb_usuario", "smb_caminho_unc")),

        BASE_CSV("base_csv", com.radioterapia.ai.R.string.tr_base_csv,
            com.radioterapia.ai.R.string.tr_base_csv_desc,
            listOf("csv_header"), arquivoInteiro = "csv_mapping"),

        PACIENTES("pacientes", com.radioterapia.ai.R.string.tr_pacientes,
            com.radioterapia.ai.R.string.tr_pacientes_desc),

        FOTOS("fotos", com.radioterapia.ai.R.string.tr_fotos,
            com.radioterapia.ai.R.string.tr_fotos_desc),
        ;

        /** Leva dado de paciente — muda o que o arquivo exige de quem o guarda. */
        val ehDadoDePaciente: Boolean get() = this == PACIENTES || this == FOTOS
    }

    /** Como aplicar o que veio, quando já existe algo equivalente no aparelho. */
    enum class Modo {
        /** O que vem no pacote manda; o que existe no aparelho é substituído. */
        SOBRESCREVER,

        /**
         * O que já existe no aparelho fica. Do pacote entra só o que não tem
         * equivalente aqui.
         *
         * É o padrão de propósito: importar não deveria ser capaz de apagar em
         * silêncio a configuração de quem já estava trabalhando no tablet.
         */
        SOMAR,
    }

    data class Resumo(
        val versao: Int,
        val origem: String,
        val data: Long,
        val itens: List<Item>,
        /** Quantos arquivos/registros cada item traz, para a tela mostrar. */
        val contagens: Map<Item, Int>,
    )

    // ================================================================ EXPORTAR

    /**
     * Escreve o pacote. Devolve quantos arquivos entraram.
     *
     * Roda em IO: monta ZIP, lê o cadastro e pode copiar milhares de fotos.
     */
    fun exportar(context: Context, selecao: Set<Item>, saida: OutputStream): Int {
        var n = 0
        ZipOutputStream(saida.buffered()).use { zip ->
            val prefs = context.getSharedPreferences("config_radioterapia", Context.MODE_PRIVATE)
            val valores = JSONObject()
            val outros = JSONObject()
            val contagens = JSONObject()

            selecao.forEach { item ->
                item.prefs.forEach { chave ->
                    prefs.all[chave]?.let { v -> porValor(valores, chave, v) }
                }
                // Arquivo de preferências levado por inteiro, sob o próprio nome.
                item.arquivoInteiro?.let { nomeArq ->
                    val outro = context.getSharedPreferences(nomeArq, Context.MODE_PRIVATE)
                    val o = JSONObject()
                    outro.all.forEach { (k, v) -> if (v != null) porValor(o, k, v) }
                    outros.put(nomeArq, o)
                }
            }

            if (Item.LOGOTIPO in selecao) {
                com.radioterapia.ai.branding.LogoManager(context).obterArquivo()?.let {
                    gravar(zip, "logo.png", it); n++
                    contagens.put(Item.LOGOTIPO.chave, 1)
                }
            }

            if (Item.RUBRICARIO in selecao) {
                val store = com.radioterapia.ai.rubricario.RubricarioStore(context)
                val equipe = store.exportarJson()
                gravarTexto(zip, "rubricario.json", equipe.toString())
                // A LISTA DE EQUIPES VAI ANTES da lista de pessoas ser lida, e
                // existe por causa da equipe VAZIA: cada pessoa ja carrega o
                // nome do bloco dela, mas uma equipe criada e ainda sem nenhuma
                // assinatura nao teria pessoa nenhuma para reconstrui-la, e
                // sumiria do tablet novo sem deixar rastro.
                gravarTexto(zip, "rubricario_blocos.json",
                    store.exportarBlocosJson().toString())
                n++
                contagens.put(Item.RUBRICARIO.chave, equipe.length())
            }

            if (Item.PROTOCOLOS in selecao) {
                val store = com.radioterapia.ai.protocolo.ProtocoloStore(context)
                gravarTexto(zip, "protocolos.json", store.exportarJson().toString())
                n++
                // Os PDFs e as miniaturas vao como arquivos, sob "protocolos/".
                // O caminho relativo preserva a pasta por protocolo, que e o
                // que permite dois protocolos terem arquivos de mesmo nome.
                val raiz = File(context.filesDir, "protocolos")
                var quantos = 0
                raiz.listFiles()?.filter { it.isDirectory }?.forEach { pasta ->
                    pasta.listFiles()?.filter { it.isFile }?.forEach { arq ->
                        gravar(zip, "protocolos/" + pasta.name + "/" + arq.name, arq)
                        quantos++
                    }
                }
                n += quantos
                contagens.put(Item.PROTOCOLOS.chave, store.listar().size)
            }

            if (Item.PACIENTES in selecao) {
                val f = File(context.filesDir, "pacientes_cache.json")
                if (f.exists()) {
                    gravar(zip, "pacientes.json", f); n++
                    contagens.put(Item.PACIENTES.chave, contarPacientes(f))
                }
            }

            if (Item.FOTOS in selecao) {
                val fotos = com.radioterapia.ai.util.StorageLocal.photos(context)
                var quantas = 0
                fotos.listFiles()?.filter { it.isDirectory }?.forEach { pasta ->
                    pasta.walkTopDown().filter { it.isFile }.forEach { arq ->
                        val rel = arq.absolutePath
                            .removePrefix(fotos.absolutePath)
                            .replace('\\', '/')
                            .trimStart('/')
                        gravar(zip, "fotos/$rel", arq)
                        quantas++
                    }
                }
                n += quantas
                contagens.put(Item.FOTOS.chave, quantas)
            }

            val manifesto = JSONObject().apply {
                put("versao", VERSAO)
                put("app", "PhotoID RT")
                put("data", System.currentTimeMillis())
                put("origem", prefs.getString("nome_clinica", "") ?: "")
                put("itens", JSONArray(selecao.map { it.chave }))
                put("valores", valores)
                put("outros_prefs", outros)
                put("contagens", contagens)
            }
            gravarTexto(zip, MANIFESTO, manifesto.toString(2))
            n++
        }
        return n
    }

    // =============================================================== INSPEÇÃO

    /**
     * Lê só o manifesto, para a tela mostrar o que o arquivo traz ANTES de
     * aplicar.
     *
     * Importar às cegas é como a configuração de uma unidade acaba dentro de
     * outra sem ninguém perceber. Devolve `null` se o arquivo não for um pacote
     * deste app.
     */
    fun inspecionar(entrada: InputStream): Resumo? = try {
        var json: JSONObject? = null
        ZipInputStream(entrada.buffered()).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                if (e.name == MANIFESTO) {
                    json = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    break
                }
                e = zip.nextEntry
            }
        }
        json?.let { m ->
            val chaves = m.optJSONArray("itens") ?: JSONArray()
            val itens = (0 until chaves.length()).mapNotNull { i ->
                val c = chaves.optString(i)
                Item.values().firstOrNull { it.chave == c }
            }
            val cont = m.optJSONObject("contagens")
            Resumo(
                versao = m.optInt("versao", 0),
                origem = m.optString("origem"),
                data = m.optLong("data"),
                itens = itens,
                contagens = itens.associateWith { cont?.optInt(it.chave, 0) ?: 0 },
            )
        }
    } catch (_: Throwable) { null }

    // ================================================================ IMPORTAR

    data class Aplicado(val prefs: Int, val arquivos: Int, val pulados: Int)

    /**
     * Aplica o pacote. Só os itens em [selecao], e só como [modo] permitir.
     *
     * Em [Modo.SOMAR] nada que já existe é tocado: preferência já preenchida
     * fica, foto de paciente já presente fica, pessoa do rubricário com mesmo
     * nome e cargo fica. É o que faz a importação ser reversível na prática —
     * quem soma pode importar de novo sem medo.
     */
    fun importar(context: Context, entrada: InputStream,
                 selecao: Set<Item>, modo: Modo): Aplicado {
        var nPrefs = 0
        var nArq = 0
        var pulados = 0
        val prefs = context.getSharedPreferences("config_radioterapia", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        val chavesSelecionadas = selecao.flatMap { it.prefs }.toSet()

        ZipInputStream(entrada.buffered()).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                val nome = e.name
                when {
                    nome == MANIFESTO -> {
                        val m = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        // Arquivos de preferências levados por inteiro.
                        val outros = m.optJSONObject("outros_prefs")
                        if (outros != null) {
                            val permitidos = selecao.mapNotNull { it.arquivoInteiro }.toSet()
                            for (nomeArq in outros.keys()) {
                                if (nomeArq !in permitidos) continue
                                val o = outros.optJSONObject(nomeArq) ?: continue
                                val alvo = context.getSharedPreferences(
                                    nomeArq, Context.MODE_PRIVATE)
                                val edOutro = alvo.edit()
                                for (chave in o.keys()) {
                                    if (modo == Modo.SOMAR && temValor(alvo, chave)) {
                                        pulados++; continue
                                    }
                                    if (aplicarValor(edOutro, chave, o.get(chave))) nPrefs++
                                }
                                edOutro.commit()
                            }
                        }
                        val valores = m.optJSONObject("valores")
                        if (valores != null) {
                            for (chave in valores.keys()) {
                                if (chave !in chavesSelecionadas) continue
                                if (modo == Modo.SOMAR && temValor(prefs, chave)) {
                                    pulados++; continue
                                }
                                if (aplicarValor(ed, chave, valores.get(chave))) nPrefs++
                            }
                        }
                    }

                    nome == "logo.png" && Item.LOGOTIPO in selecao -> {
                        val destino = File(context.filesDir, "logo_empresa.png")
                        if (modo == Modo.SOMAR && destino.exists()) pulados++
                        else { destino.outputStream().use { zip.copyTo(it) }; nArq++ }
                    }

                    nome == "rubricario_blocos.json" && Item.RUBRICARIO in selecao -> {
                        // So CRIA o que falta; equipe local com o mesmo nome
                        // nao e tocada. Nao entra na contagem porque o que o
                        // usuario conta sao pessoas, nao caixas vazias.
                        com.radioterapia.ai.rubricario.RubricarioStore(context)
                            .importarBlocosJson(
                                JSONArray(zip.readBytes().toString(Charsets.UTF_8)))
                    }

                    nome == "rubricario.json" && Item.RUBRICARIO in selecao -> {
                        val arr = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                        val store = com.radioterapia.ai.rubricario.RubricarioStore(context)
                        // importarJson JÁ soma sem duplicar (nome + cargo). Para
                        // sobrescrever, a equipe atual sai antes.
                        if (modo == Modo.SOBRESCREVER) {
                            store.listar().forEach { store.excluir(it.id) }
                        }
                        nArq += store.importarJson(arr)
                    }

                    nome == "protocolos.json" && Item.PROTOCOLOS in selecao -> {
                        val arr = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                        val store = com.radioterapia.ai.protocolo.ProtocoloStore(context)
                        nArq += store.importarJson(arr, modo == Modo.SOBRESCREVER)
                    }

                    nome.startsWith("protocolos/") && Item.PROTOCOLOS in selecao -> {
                        // Recria a pasta do protocolo e grava o binario. Em
                        // SOMAR, arquivo que ja existe nao e tocado: ele
                        // pertence a um protocolo local que tambem foi pulado.
                        val rel = nome.removePrefix("protocolos/")
                        val destino = File(File(context.filesDir, "protocolos"), rel)
                        destino.parentFile?.mkdirs()
                        if (modo == Modo.SOMAR && destino.exists()) pulados++
                        else { destino.outputStream().use { zip.copyTo(it) }; nArq++ }
                    }

                    nome == "pacientes.json" && Item.PACIENTES in selecao -> {
                        val destino = File(context.filesDir, "pacientes_cache.json")
                        val vindo = zip.readBytes().toString(Charsets.UTF_8)
                        if (modo == Modo.SOBRESCREVER || !destino.exists()) {
                            destino.writeText(vindo); nArq++
                        } else {
                            nArq += fundirPacientes(destino, vindo)
                        }
                    }

                    nome.startsWith("fotos/") && Item.FOTOS in selecao -> {
                        val rel = nome.removePrefix("fotos/")
                        if (rel.isNotBlank() && !e.isDirectory) {
                            val destino = File(
                                com.radioterapia.ai.util.StorageLocal.photos(context), rel)
                            if (modo == Modo.SOMAR && destino.exists()) pulados++
                            else {
                                destino.parentFile?.mkdirs()
                                destino.outputStream().use { zip.copyTo(it) }
                                nArq++
                            }
                        }
                    }
                }
                e = zip.nextEntry
            }
        }
        ed.commit()
        return Aplicado(nPrefs, nArq, pulados)
    }

    // ================================================================ apoio

    /** Escreve um valor no JSON preservando o tipo. */
    private fun porValor(o: JSONObject, chave: String, v: Any) {
        when (v) {
            is String -> o.put(chave, v)
            is Boolean -> o.put(chave, v)
            is Int -> o.put(chave, v)
            is Long -> o.put(chave, v)
            is Float -> o.put(chave, v.toDouble())
            else -> { /* tipo não suportado (Set): fica de fora */ }
        }
    }

    /** Devolve `true` se o valor foi aplicado. */
    private fun aplicarValor(ed: android.content.SharedPreferences.Editor,
                             chave: String, v: Any?): Boolean {
        when (v) {
            is String -> ed.putString(chave, v)
            is Boolean -> ed.putBoolean(chave, v)
            is Int -> ed.putInt(chave, v)
            is Long -> ed.putLong(chave, v)
            is Double -> ed.putFloat(chave, v.toFloat())
            else -> return false
        }
        return true
    }

    private fun temValor(p: android.content.SharedPreferences, chave: String): Boolean {
        val v = p.all[chave] ?: return false
        return !(v is String && v.isBlank())
    }

    /**
     * Junta o cadastro que veio com o que já existe, sem sobrescrever ninguém.
     *
     * A chave do cadastro é `"NOME | PRONTUÁRIO"`, então paciente já presente é
     * reconhecido pela própria chave — não há heurística aqui.
     */
    private fun fundirPacientes(destino: File, vindo: String): Int = try {
        val atual = JSONObject(destino.readText())
        val novo = JSONObject(vindo)
        val alvo = atual.optJSONObject("pacientes") ?: atual
        val fonte = novo.optJSONObject("pacientes") ?: novo
        var n = 0
        for (chave in fonte.keys()) {
            if (alvo.has(chave)) continue
            alvo.put(chave, fonte.get(chave))
            n++
        }
        destino.writeText(atual.toString())
        n
    } catch (_: Exception) { 0 }

    private fun contarPacientes(f: File): Int = try {
        val o = JSONObject(f.readText())
        (o.optJSONObject("pacientes") ?: o).length()
    } catch (_: Exception) { 0 }

    private fun gravar(zip: ZipOutputStream, nome: String, arq: File) {
        zip.putNextEntry(ZipEntry(nome))
        arq.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun gravarTexto(zip: ZipOutputStream, nome: String, texto: String) {
        zip.putNextEntry(ZipEntry(nome))
        zip.write(texto.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
