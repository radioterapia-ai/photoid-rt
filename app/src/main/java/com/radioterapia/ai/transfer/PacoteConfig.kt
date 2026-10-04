package com.radioterapia.ai.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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
            listOf("pdf_landscape", "pdf_etiqueta_largura_mm",
                   "pdf_etiqueta_altura_mm", "pdf_margem_mm", "pdf_inclui_timeout")),

        /**
         * A sincronização própria: as preferências e os destinos.
         *
         * SEM SENHA, e é o ponto. As senhas dos perfis moram no
         * [com.radioterapia.ai.security.CredentialStore], cifradas sob uma
         * chave do Android Keystore que não sai do aparelho — este pacote viaja
         * por e-mail e por pen-drive. Quem recebe digita a senha uma vez, em
         * cada tablet.
         *
         * SEM O ÍNDICE DO QUE JÁ SUBIU, que é por aparelho: o tablet que recebe
         * a configuração não enviou nada ainda, e herdar o índice do outro faria
         * a primeira varredura concluir que está tudo no servidor.
         *
         * SEM O ESTADO DOS PERFIS: a data da última sincronização e o último
         * erro ficam de fora ([com.radioterapia.ai.sync.PerfilSync.semEstado]).
         * O erro guarda a mensagem da exceção do adaptador, que costuma citar o
         * caminho remoto — o nome da pasta do paciente —, e a data muda a cada
         * rodada.
         */
        SINCRONIZACAO("sincronizacao", com.radioterapia.ai.R.string.tr_sync,
            com.radioterapia.ai.R.string.tr_sync_desc,
            com.radioterapia.ai.sync.SyncConfig.CHAVES_TRANSFERIVEIS),

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

        /**
         * A agenda deste aparelho: quem está em tratamento.
         *
         * Ela ficou de fora do pacote por decisão explícita —
         * a lista é por tablet, e cada tablet cuida de um acelerador. O que a
         * decisão não previu foi o tablet sendo RESTAURADO: na migração de
         * setembro a base voltou inteira e a aba de Tratamentos veio vazia,
         * porque a agenda não estava no arquivo. Todo mundo caiu no histórico.
         *
         * Entra como item MARCÁVEL, e não como comportamento fixo, porque as
         * duas leituras continuam válidas: restaurar o mesmo aparelho quer a
         * agenda de volta; configurar um segundo aparelho, não.
         *
         * NÃO CARREGA A ALTA. O que viaja é quem está presente na lista; a alta
         * é ausência, e ausência só tem efeito em SUBSTITUIR. Em SOMAR — que é o
         * padrão — importar nunca tira ninguém.
         */
        TRATAMENTO("tratamento", com.radioterapia.ai.R.string.tr_tratamento,
            com.radioterapia.ai.R.string.tr_tratamento_desc),
        ;

        /** Leva dado de paciente — muda o que o arquivo exige de quem o guarda. */
        val ehDadoDePaciente: Boolean
            get() = this == PACIENTES || this == FOTOS || this == TRATAMENTO
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
     *
     * @param instante a hora gravada no manifesto. Quem põe a hora no nome do
     *   arquivo passa a mesma aqui, para que nome e manifesto concordem.
     */
    fun exportar(context: Context, selecao: Set<Item>, saida: OutputStream,
                 instante: Long = System.currentTimeMillis()): Int =
        ZipOutputStream(saida.buffered()).use { zip ->
            escrever(context, selecao, SaidaZip(zip), instante)
        }

    /**
     * A impressão digital do que [exportar] gravaria com esta [selecao]:
     * SHA-256, em hexadecimal maiúsculo.
     *
     * Responde «a configuração mudou?» sem montar o ZIP. Os bytes do ZIP não
     * servem para isso: cada entrada leva a hora em que foi escrita, e dois
     * pacotes da mesma configuração nunca saem iguais. A impressão percorre o
     * MESMO [escrever] do ZIP, então o que entra numa e na outra nunca diverge.
     * Arquivo binário entra por nome, tamanho e data de modificação, sem ser
     * lido — os PDFs de protocolo podem ter megabytes, e isto roda a cada
     * sincronização —, e o manifesto entra sem a hora.
     */
    fun impressaoDigital(context: Context, selecao: Set<Item>): String {
        val md = MessageDigest.getInstance("SHA-256")
        escrever(context, selecao, SaidaImpressao(md), instante = null)
        return hexMaiusculo(md.digest())
    }

    /**
     * O corpo comum do ZIP e da impressão digital.
     *
     * GUARDA: tudo o que passa por aqui precisa ser DETERMINÍSTICO — a mesma
     * configuração tem de produzir a mesma sequência de nomes e conteúdos.
     * Pastas e arquivos são percorridos em ordem de nome, o arquivo de
     * preferências levado por inteiro em ordem de chave, e o estado dos perfis
     * de sincronia fica de fora. Um valor que mudasse sozinho (hora, contador)
     * faria a cópia da configuração enviada ao destino nascer de novo a cada
     * sincronização, e o destino, que nunca apaga, acumularia uma por rodada.
     *
     * @param instante nulo na impressão digital: o manifesto sai sem hora.
     */
    private fun escrever(context: Context, selecao: Set<Item>, saida: Saida,
                         instante: Long?): Int {
        var n = 0
        val prefs = context.getSharedPreferences("config_radioterapia", Context.MODE_PRIVATE)
        val todasPrefs = prefs.all
        val valores = JSONObject()
        val outros = JSONObject()
        val contagens = JSONObject()

        selecao.forEach { item ->
            item.prefs.forEach { chave ->
                todasPrefs[chave]?.let { v -> porValor(valores, chave, v) }
            }
            // Arquivo de preferências levado por inteiro, sob o próprio nome,
            // em ordem de chave: a ordem do mapa do Android é a de um HashMap.
            item.arquivoInteiro?.let { nomeArq ->
                val outro = context.getSharedPreferences(nomeArq, Context.MODE_PRIVATE)
                val o = JSONObject()
                outro.all.toSortedMap().forEach { (k, v) -> if (v != null) porValor(o, k, v) }
                outros.put(nomeArq, o)
            }
        }

        if (Item.SINCRONIZACAO in selecao) {
            // Sem o estado dos perfis: ver Item.SINCRONIZACAO.
            val store = com.radioterapia.ai.sync.PerfilStore(context)
            store.jsonParaTransferencia()?.let { json ->
                saida.texto("sync_perfis.json", json); n++
                contagens.put(Item.SINCRONIZACAO.chave, store.listar().size)
            }
        }

        if (Item.LOGOTIPO in selecao) {
            com.radioterapia.ai.branding.LogoManager(context).obterArquivo()?.let {
                saida.arquivo("logo.png", it); n++
                contagens.put(Item.LOGOTIPO.chave, 1)
            }
        }

        if (Item.RUBRICARIO in selecao) {
            val store = com.radioterapia.ai.rubricario.RubricarioStore(context)
            val equipe = store.exportarJson()
            saida.texto("rubricario.json", equipe.toString())
            // A LISTA DE EQUIPES VAI ANTES da lista de pessoas ser lida, e
            // existe por causa da equipe VAZIA: cada pessoa ja carrega o
            // nome do bloco dela, mas uma equipe criada e ainda sem nenhuma
            // assinatura nao teria pessoa nenhuma para reconstrui-la, e
            // sumiria do tablet novo sem deixar rastro.
            saida.texto("rubricario_blocos.json",
                store.exportarBlocosJson().toString())
            n++
            contagens.put(Item.RUBRICARIO.chave, equipe.length())
        }

        if (Item.PROTOCOLOS in selecao) {
            val store = com.radioterapia.ai.protocolo.ProtocoloStore(context)
            saida.texto("protocolos.json", store.exportarJson().toString())
            n++
            // Os PDFs e as miniaturas vao como arquivos, sob "protocolos/".
            // O caminho relativo preserva a pasta por protocolo, que e o
            // que permite dois protocolos terem arquivos de mesmo nome.
            // Em ordem de nome: ver a GUARDA de escrever.
            val raiz = File(context.filesDir, "protocolos")
            var quantos = 0
            raiz.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { pasta ->
                pasta.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { arq ->
                    saida.arquivo("protocolos/" + pasta.name + "/" + arq.name, arq)
                    quantos++
                }
            }
            n += quantos
            contagens.put(Item.PROTOCOLOS.chave, store.listar().size)
        }

        if (Item.PACIENTES in selecao) {
            val f = File(context.filesDir, "pacientes_cache.json")
            if (f.exists()) {
                saida.arquivo("pacientes.json", f); n++
                contagens.put(Item.PACIENTES.chave, contarPacientes(f))
            }
        }

        if (Item.FOTOS in selecao) {
            val fotos = com.radioterapia.ai.util.StorageLocal.photos(context)
            var quantas = 0
            fotos.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { pasta ->
                pasta.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.forEach { arq ->
                    val rel = arq.absolutePath
                        .removePrefix(fotos.absolutePath)
                        .replace('\\', '/')
                        .trimStart('/')
                    saida.arquivo("fotos/$rel", arq)
                    quantas++
                }
            }
            n += quantas
            contagens.put(Item.FOTOS.chave, quantas)
        }

        if (Item.TRATAMENTO in selecao) {
            val chaves = com.radioterapia.ai.treatment.TreatmentListManager(context)
                .chavesEmTratamento()
            // CONJUNTO VAZIO NÃO VIRA ARQUIVO. Um pacote com uma lista vazia
            // dentro seria indistinguível de "esvazie a agenda do destino",
            // e em SUBSTITUIR é o que ele faria. Sem arquivo, não há ordem.
            if (chaves.isNotEmpty()) {
                saida.texto("tratamento.json", JSONArray(chaves.sorted()).toString())
                n++
                contagens.put(Item.TRATAMENTO.chave, chaves.size)
            }
        }

        val manifesto = JSONObject().apply {
            put("versao", VERSAO)
            put("app", "PhotoID RT")
            if (instante != null) put("data", instante)
            put("origem", prefs.getString("nome_clinica", "") ?: "")
            put("itens", JSONArray(selecao.map { it.chave }))
            put("valores", valores)
            put("outros_prefs", outros)
            put("contagens", contagens)
        }
        saida.texto(MANIFESTO, manifesto.toString(2))
        n++
        return n
    }

    /**
     * Para onde [escrever] manda cada entrada do pacote: o ZIP de verdade
     * ([SaidaZip]) ou o resumo que vira impressão digital ([SaidaImpressao]).
     */
    private interface Saida {
        fun arquivo(nome: String, arq: File)
        fun texto(nome: String, texto: String)
    }

    private class SaidaZip(private val zip: ZipOutputStream) : Saida {
        override fun arquivo(nome: String, arq: File) {
            PacoteConfig.gravar(zip, nome, arq)
        }

        override fun texto(nome: String, texto: String) {
            PacoteConfig.gravarTexto(zip, nome, texto)
        }
    }

    /**
     * Cada entrada vira uma linha com o nome e o tamanho antes do conteúdo,
     * para que a fronteira entre duas entradas faça parte da impressão: sem
     * ela, um texto que perdesse o fim e o seguinte que ganhasse o começo
     * dariam o mesmo resumo.
     */
    private class SaidaImpressao(private val md: MessageDigest) : Saida {
        override fun arquivo(nome: String, arq: File) {
            md.update("A|$nome|${arq.length()}|${arq.lastModified()}\n"
                .toByteArray(Charsets.UTF_8))
        }

        override fun texto(nome: String, texto: String) {
            val bytes = texto.toByteArray(Charsets.UTF_8)
            md.update("T|$nome|${bytes.size}\n".toByteArray(Charsets.UTF_8))
            md.update(bytes)
        }
    }

    /**
     * Hexadecimal maiúsculo, montado à mão: `String.format` segue o idioma do
     * aparelho, e o resultado entra em nome de arquivo.
     */
    private fun hexMaiusculo(bytes: ByteArray): String {
        val digitos = "0123456789ABCDEF"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digitos[v ushr 4]).append(digitos[v and 0x0F])
        }
        return sb.toString()
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
     * O destino de uma entrada do pacote, ou null quando ela sairia da pasta base.
     *
     * O pacote chega por e-mail e pen-drive, de outro tablet ou de qualquer
     * origem, entao o nome de cada entrada e dado nao confiavel. Sem esta
     * conferencia, uma entrada "protocolos/../shared_prefs/x.xml" gravaria fora
     * da pasta de protocolos e sobrescreveria arquivo do app (zip-slip).
     *
     * A comparacao e por caminho CANONICO, que resolve ".." e ligacoes, e nao
     * por texto: um filtro de texto deixa passar variacoes que o sistema de
     * arquivos ainda resolve para fora.
     */
    internal fun destinoDentroDe(base: File, rel: String): File? {
        if (rel.isBlank() || rel.startsWith("/") || rel.startsWith("\\")) return null
        if (rel.indexOf('\u0000') >= 0) return null
        if (rel.split('/', '\\').any { it == ".." }) return null
        return try {
            val raiz = base.canonicalFile
            val alvo = File(raiz, rel).canonicalFile
            if (alvo.path.startsWith(raiz.path + File.separator)) alvo else null
        } catch (_: java.io.IOException) {
            null
        }
    }

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
        var fotosGravadas = 0
        var importacaoAberta = false
        var senhaSmbCaduca = false
        val prefs = context.getSharedPreferences("config_radioterapia", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        val chavesSelecionadas = selecao.flatMap { it.prefs }.toSet()

        // GUARDA: a marca de base examinada sai ANTES da primeira foto gravada
        // (abrirImportacao) e só volta por uma passada limpa da quarentena
        // depois do fecharImportacao. Enquanto isso o motor de sincronia não
        // envia `ARQUIVADAS/`: o pacote pode trazer a mesma foto arquivada na
        // pasta de dois pacientes. O finally fecha a importação e roda a
        // quarentena mesmo quando o pacote quebra no meio (ZIP truncado, JSON
        // ilegivel): as fotos gravadas antes da quebra ja estao nas pastas de
        // paciente.
        try {
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
                                    val vindo = valores.get(chave)
                                    if (aplicarValor(ed, chave, vindo)) {
                                        nPrefs++
                                        // prefs ainda tem o valor deste aparelho:
                                        // o editor só grava no commit, abaixo.
                                        if (mudaDestinoSmb(chave, prefs.all[chave], vindo)) {
                                            senhaSmbCaduca = true
                                        }
                                    }
                                }
                            }
                        }

                        nome == "logo.png" && Item.LOGOTIPO in selecao -> {
                            val destino = File(context.filesDir, "logo_empresa.png")
                            if (modo == Modo.SOMAR && destino.exists()) pulados++
                            else { destino.outputStream().use { zip.copyTo(it) }; nArq++ }
                        }

                        nome == "sync_perfis.json" && Item.SINCRONIZACAO in selecao -> {
                            // SEM SENHA E DESATIVADOS. O perfil chega com endereço,
                            // usuário e caminho; a senha não viaja, e um destino
                            // sem senha que já nascesse ativo tentaria conectar e
                            // falharia a cada varredura, enchendo o log de erro que
                            // não é erro — é configuração pela metade, de
                            // propósito, esperando alguém digitar a senha.
                            val destino = File(
                                File(context.filesDir, "sync").apply { mkdirs() }, "perfis.json")
                            if (modo == Modo.SOMAR && destino.exists()) pulados++
                            else {
                                val store = com.radioterapia.ai.sync.PerfilStore(context)
                                // Os destinos deste aparelho ANTES de o pacote
                                // sobrescrevê-los: é contra eles que se decide qual
                                // senha guardada continua valendo.
                                val locais = store.listar()
                                destino.outputStream().use { zip.copyTo(it) }
                                val importados = store.listar()
                                store.salvarTodos(importados.map { it.copy(
                                    ativo = false, ultimaSincronizacao = 0L, ultimoErro = "") })
                                // A poda de salvarTodos só tira a senha de id que
                                // sumiu; a de id que chegou apontando para outro
                                // lugar sai aqui (ver idsQuePerdemASenha), junto com
                                // o índice do que já subiu: destino novo é destino
                                // vazio, e o índice do anterior diria que está tudo lá.
                                val cofre = com.radioterapia.ai.security.CredentialStore(context)
                                idsQuePerdemASenha(locais, importados).forEach { id ->
                                    cofre.limparSenhaPerfil(id)
                                    com.radioterapia.ai.sync.IndiceEnviados(context, id).limpar()
                                }
                                nArq++
                            }
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
                            // So `<id>/<arquivo>` com id e nome que o ProtocoloStore
                            // aceita: arquivo solto na raiz (`lista.json`) trocaria a
                            // lista inteira por fora de importarJson, que e onde ids e
                            // nomes sao conferidos.
                            val rel = nome.removePrefix("protocolos/")
                            val destino = if (e.isDirectory ||
                                !com.radioterapia.ai.protocolo.ProtocoloStore.entradaDoPacoteValida(rel)) null
                                else destinoDentroDe(File(context.filesDir, "protocolos"), rel)
                            if (destino == null) pulados++
                            else {
                                destino.parentFile?.mkdirs()
                                if (modo == Modo.SOMAR && destino.exists()) pulados++
                                else { destino.outputStream().use { zip.copyTo(it) }; nArq++ }
                            }
                        }

                        nome == "pacientes.json" && Item.PACIENTES in selecao -> {
                            val destino = File(context.filesDir, "pacientes_cache.json")
                            val vindo = zip.readBytes().toString(Charsets.UTF_8)
                            if (modo == Modo.SOBRESCREVER || !destino.exists()) {
                                if (com.radioterapia.ai.patient.PatientCache.gravarAtomico(destino, vindo)) nArq++
                            } else {
                                nArq += fundirPacientes(destino, vindo)
                            }
                        }

                        nome == "tratamento.json" && Item.TRATAMENTO in selecao -> {
                            val arr = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                            val chaves = (0 until arr.length())
                                .mapNotNull { arr.optString(it).takeIf { c -> c.isNotBlank() } }
                                .toSet()
                            // SUBSTITUIR espelha a agenda do aparelho de origem;
                            // SOMAR funde as duas e não dá alta em ninguém, que é o
                            // contrato de SOMAR em todo o resto do pacote.
                            val entraram = com.radioterapia.ai.treatment
                                .TreatmentListManager(context)
                                .importarChaves(chaves, modo == Modo.SOBRESCREVER)
                            nArq += entraram
                            pulados += chaves.size - entraram
                        }

                        nome.startsWith("fotos/") && Item.FOTOS in selecao -> {
                            val rel = nome.removePrefix("fotos/")
                            if (rel.isNotBlank() && !e.isDirectory) {
                                val destino = destinoDentroDe(
                                    com.radioterapia.ai.util.StorageLocal.photos(context), rel)
                                if (destino == null) pulados++
                                else if (modo == Modo.SOMAR && destino.exists()) pulados++
                                else {
                                    // A marca sai antes da primeira foto. A flag vem
                                    // antes da chamada: se ela lançar depois de abrir,
                                    // o finally ainda fecha.
                                    if (!importacaoAberta) {
                                        importacaoAberta = true
                                        com.radioterapia.ai.util.QuarentenaArquivadas
                                            .abrirImportacao(context)
                                    }
                                    // Conta ANTES de gravar: o arquivo que ficou pela
                                    // metade tambem chegou a uma pasta de paciente.
                                    fotosGravadas++
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
            // A senha SMB antiga sai ANTES de o endereço novo valer: entre as
            // duas gravações, uma busca de fotos do servidor levaria a senha
            // deste aparelho ao destino que chegou no pacote.
            if (senhaSmbCaduca) com.radioterapia.ai.security.CredentialStore(context).limparSenha()
            ed.commit()
        } finally {
            if (importacaoAberta) com.radioterapia.ai.util.QuarentenaArquivadas.fecharImportacao()
            if (fotosGravadas > 0) reexaminarArquivadas(context)
        }
        return Aplicado(nPrefs, nArq, pulados)
    }

    /**
     * Devolve a pasta base à quarentena de fotos arquivadas e a roda já.
     *
     * GUARDA: a quarentena examina cada base uma vez e guarda a marca. Fotos
     * gravadas depois disso — pacote de outro tablet, cópia antiga do próprio
     * aparelho — podem trazer a mesma foto arquivada na pasta de dois
     * pacientes, e com a marca ninguém as examinaria: a foto de um seria
     * oferecida para restaurar na ficha do outro e subiria ao servidor na pasta
     * errada. Roda aqui, e não só na próxima abertura, porque a Home dispara a
     * varredura uma vez por processo e a tela de captura mostraria o que veio
     * antes disso. [importar] roda fora da thread principal.
     *
     * Chamar DEPOIS de fechar a importação: com ela aberta, a passada examina
     * mas não grava a marca, e o motor de sincronia seguiria sem enviar
     * `ARQUIVADAS/` até a próxima abertura.
     */
    private fun reexaminarArquivadas(context: Context) {
        try {
            com.radioterapia.ai.util.QuarentenaArquivadas.reexaminar(context)
            com.radioterapia.ai.util.QuarentenaArquivadas.executarSeNecessario(context)
        } catch (_: Exception) {
            // As fotos já foram gravadas. Sem a marca, a varredura roda na
            // próxima abertura.
        }
    }

    /**
     * Ids dos perfis importados que NÃO podem continuar com a senha guardada
     * neste aparelho.
     *
     * A senha mora no cofre indexada pelo id do perfil, e o id viaja no pacote.
     * Um perfil que chega com o id de um destino local, mas apontando para
     * outro servidor, outra conta ou outra pasta, herdaria a senha guardada e a
     * entregaria ao endereço novo na primeira sincronização — sem o aviso de
     * destino sem senha, porque a senha existe. Fica com a senha só o perfil
     * que chega com o mesmo destino do perfil local de mesmo id ([mesmoDestino]);
     * qualquer diferença, ou id que não existia aqui, volta a pedir a senha.
     */
    internal fun idsQuePerdemASenha(
        locais: List<com.radioterapia.ai.sync.PerfilSync>,
        importados: List<com.radioterapia.ai.sync.PerfilSync>
    ): Set<String> {
        val locaisPorId = locais.groupBy { it.id }
        return importados
            .filter { imp ->
                val mesmoId = locaisPorId[imp.id].orEmpty()
                mesmoId.isEmpty() || mesmoId.any { !mesmoDestino(it, imp) }
            }
            .map { it.id }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Mesmo protocolo, mesmo endereço, mesma conta e mesma pasta: tudo o que
     * decide para onde a senha e as fotos vão. Nome, estado e a opção de
     * pular a verificação de pastas não mudam o destino.
     */
    internal fun mesmoDestino(a: com.radioterapia.ai.sync.PerfilSync,
                              b: com.radioterapia.ai.sync.PerfilSync): Boolean =
        a.tipo == b.tipo &&
            a.host == b.host &&
            a.porta == b.porta &&
            a.usuario == b.usuario &&
            a.protocolo == b.protocolo &&
            a.share == b.share &&
            a.dominio == b.dominio &&
            a.urlBase == b.urlBase &&
            a.safUri == b.safUri &&
            a.caminhoRemoto == b.caminhoRemoto

    /**
     * Gravar [vindo] na chave [chave] muda para onde a senha SMB antiga deste
     * aparelho seria entregue?
     *
     * A mesma regra de [idsQuePerdemASenha], para a configuração de rede de
     * antes dos perfis: a senha global mora no cofre e não viaja, mas
     * endereço, porta, protocolo, domínio, usuário e pasta viajam. Qualquer um
     * deles que chegue diferente do que está aqui faria a senha deste tablet
     * ir ao servidor, à conta ou à pasta de outra unidade na primeira busca de
     * fotos — sem o aviso de senha ausente, porque ela existe. Contam todas as
     * chaves de [Item.REDE]; a comparação é pelo texto do valor, e ausente
     * vale vazio. Na dúvida a senha sai: o custo é digitá-la de novo.
     *
     * @param local o valor gravado neste aparelho antes da importação.
     */
    internal fun mudaDestinoSmb(chave: String, local: Any?, vindo: Any?): Boolean =
        chave in Item.REDE.prefs && textoDoValor(local) != textoDoValor(vindo)

    private fun textoDoValor(v: Any?): String = v?.toString() ?: ""

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
        com.radioterapia.ai.patient.PatientCache.gravarAtomico(destino, atual.toString())
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
