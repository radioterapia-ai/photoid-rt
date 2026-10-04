package com.radioterapia.ai

import android.content.Context
import android.content.res.Configuration
import com.radioterapia.ai.i18n.LocaleManager

/**
 * Modelo de um destino SMB. O destino primário é obrigatório.
 * Os 3 backups são opcionais e usam as mesmas credenciais (login + senha + domínio).
 */
data class DestinoSmb(
    val ativo: Boolean,
    val host: String,
    val porta: Int,
    val caminhoUNC: String
) {
    fun caminhoDecomposto(): Pair<String, String> {
        val limpo = caminhoUNC.removePrefix("\\\\").removePrefix("//")
        val partes = limpo.split(Regex("[\\\\/]")).filter { it.isNotEmpty() }
        if (partes.size < 2) return "" to ""
        val share = partes[1]
        val subpasta = partes.drop(2).joinToString("\\")
        return share to subpasta
    }

    fun valido(): Boolean = host.isNotBlank() && caminhoUNC.isNotBlank()
}

class AppConfig(context: Context) {

    private val prefs = context.getSharedPreferences("config_radioterapia", Context.MODE_PRIVATE)

    /**
     * Contexto da aplicação, para ler recurso num idioma escolhido. Guardar o da
     * Activity prenderia a tela enquanto este objeto vivesse; o da aplicação já
     * existe enquanto o processo existir.
     */
    private val app: Context = context.applicationContext ?: context

    var pastaBaseLocal: String
        get() = prefs.getString(KEY_PASTA_LOCAL, "Pictures") ?: "Pictures"
        set(value) = prefs.edit().putString(KEY_PASTA_LOCAL, value).apply()

    /** URI (SAF/tree) da pasta local escolhida para salvar fotos+PDF. Vazio = usa MediaStore. */
    /** Pasta base de armazenamento escolhida pelo usuário (caminho absoluto).
     *  Vazio = padrão (raiz do armazenamento interno /PhotoID_RT). */
    var pastaBaseCustom: String
        get() = prefs.getString(KEY_BASE_CUSTOM, "") ?: ""
        set(value) { prefs.edit().putString(KEY_BASE_CUSTOM, value).commit() }

    /** Ordenação das listas de pacientes: "recente" | "nome" | "prontuario". */
    /** true = ordem crescente na lista de pacientes. */
    var ordemHistoricoAsc: Boolean
        get() = prefs.getBoolean("ordem_hist_asc", false)
        set(value) = prefs.edit().putBoolean("ordem_hist_asc", value).apply()

    var ordemHistorico: String
        get() = prefs.getString(KEY_ORDEM_HIST, "recente") ?: "recente"
        set(value) { prefs.edit().putString(KEY_ORDEM_HIST, value).commit() }

    var pastaFotosUri: String
        get() = prefs.getString(KEY_PASTA_FOTOS_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASTA_FOTOS_URI, value).apply()

    /** URI (SAF/tree) da pasta local onde está a base de dados CSV, colocada ali
     *  pelo sincronizador — o do próprio app ou o que o serviço usar. */
    var pastaCsvUri: String
        get() = prefs.getString(KEY_PASTA_CSV_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASTA_CSV_URI, value).apply()

    // Credenciais compartilhadas (servem para o destino primário e os backups)
    var smbProtocolo: String
        get() = prefs.getString(KEY_PROTOCOLO, "SMB2") ?: "SMB2"
        set(value) = prefs.edit().putString(KEY_PROTOCOLO, value).apply()

    var smbDominio: String
        get() = prefs.getString(KEY_DOMINIO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DOMINIO, value).apply()

    var smbUsuario: String
        get() = prefs.getString(KEY_USUARIO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USUARIO, value).apply()

    /**
     * Obtém o destino na posição (0=primário, 1-3=backups).
     */
    fun obterDestino(indice: Int): DestinoSmb {
        if (indice == 0) {
            return DestinoSmb(
                ativo = true,
                host = prefs.getString(KEY_HOST, "") ?: "",
                porta = prefs.getInt(KEY_PORTA, 445),
                caminhoUNC = prefs.getString(KEY_CAMINHO_UNC, "") ?: ""
            )
        }
        return DestinoSmb(
            ativo = prefs.getBoolean("$KEY_BACKUP_ATIVO$indice", false),
            host = prefs.getString("$KEY_BACKUP_HOST$indice", "") ?: "",
            porta = prefs.getInt("$KEY_BACKUP_PORTA$indice", 445),
            caminhoUNC = prefs.getString("$KEY_BACKUP_UNC$indice", "") ?: ""
        )
    }

    fun salvarDestino(indice: Int, destino: DestinoSmb) {
        if (indice == 0) {
            prefs.edit()
                .putString(KEY_HOST, destino.host)
                .putInt(KEY_PORTA, destino.porta)
                .putString(KEY_CAMINHO_UNC, destino.caminhoUNC)
                .apply()
        } else {
            prefs.edit()
                .putBoolean("$KEY_BACKUP_ATIVO$indice", destino.ativo)
                .putString("$KEY_BACKUP_HOST$indice", destino.host)
                .putInt("$KEY_BACKUP_PORTA$indice", destino.porta)
                .putString("$KEY_BACKUP_UNC$indice", destino.caminhoUNC)
                .apply()
        }
    }

    /**
     * Lista todos os destinos ativos (incluindo o primário).
     */
    fun obterDestinosAtivos(): List<DestinoSmb> {
        val lista = mutableListOf<DestinoSmb>()
        lista.add(obterDestino(0))
        for (i in 1..3) {
            val d = obterDestino(i)
            if (d.ativo && d.valido()) lista.add(d)
        }
        return lista
    }

    // ----------- Impressora -----------

    var impressoraIp: String
        get() = prefs.getString(KEY_PRINTER_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PRINTER_IP, value).apply()

    var impressoraNome: String
        get() = prefs.getString(KEY_PRINTER_NOME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PRINTER_NOME, value).apply()

    /** Modo de impressão: "simplex" | "long" (frente-verso borda longa) | "short" (borda curta). */
    var printerDuplexMode: String
        get() = prefs.getString("printer_duplex", "simplex") ?: "simplex"
        set(value) { prefs.edit().putString("printer_duplex", value).commit() }

    fun temImpressora(): Boolean = impressoraIp.isNotBlank()

    // ----------- PDF -----------

    /**
     * Por padrão o PDF vai para o servidor junto com as fotos.
     */
    var pdfParaServidor: Boolean
        get() = prefs.getBoolean(KEY_PDF_SERVIDOR, true)
        set(value) = prefs.edit().putBoolean(KEY_PDF_SERVIDOR, value).apply()

    /** Marca se já pedimos "Acesso a todos os arquivos" (para não repetir o diálogo). */
    var allFilesSolicitado: Boolean
        get() = prefs.getBoolean("all_files_solicitado", false)
        set(value) = prefs.edit().putBoolean("all_files_solicitado", value).apply()

    // ==================== PROCURA DE ATUALIZAÇÃO ====================

    /**
     * Procurar versão nova no repositório público do projeto.
     *
     * NASCE LIGADA, e essa é a única decisão desta função que muda a postura de
     * rede do app. As outras três situações de rede vão para endereços que a
     * INSTITUIÇÃO informa — impressora, pasta SMB, destino de sincronia. Esta é
     * a primeira que sai para um endereço escolhido por NÓS, e por isso está
     * declarada na Política de Privacidade e nos Termos, seção própria, em vez
     * de ficar implícita.
     *
     * O que ela envia: um GET HTTPS. Não envia dado de paciente, identificador
     * do aparelho nem estatística de uso — não há o que enviar, porque a
     * pergunta é «qual a última versão», e a resposta não depende de quem
     * pergunta.
     *
     * Desligada, nenhuma checagem acontece, nem no arranque nem depois da
     * sincronia. O botão «Checar» das Configurações continua funcionando, porque
     * ali existe um humano pedindo.
     */
    var procurarAtualizacao: Boolean
        get() = prefs.getBoolean(KEY_ATU_PROCURAR, true)
        set(v) = prefs.edit().putBoolean(KEY_ATU_PROCURAR, v).apply()

    /** versionCode encontrado no repositório. 0 = nada encontrado ainda. */
    var atualizacaoCode: Int
        get() = prefs.getInt(KEY_ATU_CODE, 0)
        set(v) = prefs.edit().putInt(KEY_ATU_CODE, v).apply()

    /** versionName correspondente, para a mensagem dizer QUAL versão. */
    var atualizacaoNome: String
        get() = prefs.getString(KEY_ATU_NOME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ATU_NOME, v).apply()

    /**
     * O versionCode que o usuário mandou esperar.
     *
     * Guardar «dispensou» como BOOLEANO seria o defeito óbvio: quem adiasse a
     * 4.4 nunca mais ouviria falar da 4.5. Guardando o número, adiar silencia
     * aquela versão e só aquela — a próxima volta a avisar.
     */
    var atualizacaoDispensada: Int
        get() = prefs.getInt(KEY_ATU_DISPENSADA, 0)
        set(v) = prefs.edit().putInt(KEY_ATU_DISPENSADA, v).apply()

    /** Quando a última checagem aconteceu (epoch ms). Serve para não bater no
     *  repositório a cada abertura de tela. */
    var atualizacaoUltimaChecagem: Long
        get() = prefs.getLong(KEY_ATU_CHECAGEM, 0L)
        set(v) = prefs.edit().putLong(KEY_ATU_CHECAGEM, v).apply()

    // Marcador de pré-instalação: gravado logo antes de abrir o instalador e
    // conferido na primeira abertura da versão nova, que compara as contagens
    // de antes com as de depois. Nenhum dos três deve viajar em cópia ou pacote
    // de configuração: é estado deste aparelho, não do serviço.
    //
    // GUARDA: os três gravam com commit(), não apply(). O instalador substitui
    // o app e encerra o processo logo em seguida; uma gravação assíncrona ainda
    // na fila se perderia, e a conferência pós-atualização não aconteceria.

    /** versionCode instalado quando o marcador foi gravado. 0 = sem marcador. */
    var atualizacaoDe: Int
        get() = prefs.getInt(KEY_ATU_DE, 0)
        set(v) { prefs.edit().putInt(KEY_ATU_DE, v).commit() }

    /** Contagens de antes da atualização, codificadas como `chave=n;chave=n`. */
    var atualizacaoContagens: String
        get() = prefs.getString(KEY_ATU_CONTAGENS, "") ?: ""
        set(v) { prefs.edit().putString(KEY_ATU_CONTAGENS, v).commit() }

    /** Caminho absoluto da cópia de segurança gravada antes de atualizar. */
    var atualizacaoBackup: String
        get() = prefs.getString(KEY_ATU_BACKUP, "") ?: ""
        set(v) { prefs.edit().putString(KEY_ATU_BACKUP, v).commit() }

    /**
     * Como o técnico DIGITA a data de nascimento.
     *
     * "dd/MM/yyyy" (padrão), "MM/dd/yyyy" ou "yyyy-MM-dd". A preferência vale
     * só na entrada e na releitura do campo: o que é GRAVADO continua canônico
     * em dd/MM/yyyy, porque uma configuração de tela não pode mudar o
     * significado do que já está em disco.
     *
     * A data impressa na ficha não usa isto — ela sai com o mês por extenso
     * ("15-JUL-1982"), que não é ambíguo em idioma nenhum.
     */
    var formatoData: String
        get() = prefs.getString(KEY_FORMATO_DATA, "dd/MM/yyyy") ?: "dd/MM/yyyy"
        set(value) = prefs.edit().putString(KEY_FORMATO_DATA, value).apply()

    /** Orientação do PDF: false = retrato (padrão), true = paisagem (8 fotos em 4x2). */
    var pdfLandscape: Boolean
        get() = prefs.getBoolean(KEY_PDF_LANDSCAPE, false)
        set(value) = prefs.edit().putBoolean(KEY_PDF_LANDSCAPE, value).apply()

    /** Rotina da clínica: PDF inclui a página de TIME-OUT (primeira página). */
    var pdfIncluiTimeOut: Boolean
        get() = prefs.getBoolean("pdf_inclui_timeout", true)
        set(value) = prefs.edit().putBoolean("pdf_inclui_timeout", value).apply()

    /**
     * Largura do quadro de identificação na ficha, em mm.
     *
     * O quadro existe em toda ficha e sempre com os dados do paciente dentro —
     * não há mais escolha entre etiqueta física e virtual. A medida serve a quem
     * cola etiqueta de papel por cima: é o tamanho da etiqueta do serviço.
     */
    var pdfEtiquetaLarguraMm: Int
        get() = prefs.getInt(KEY_PDF_ETIQ_LARG, 60)
        set(value) = prefs.edit().putInt(KEY_PDF_ETIQ_LARG, value.coerceIn(10, 120)).apply()

    /** Altura do quadro de identificação na ficha, em mm. Ver a largura. */
    var pdfEtiquetaAlturaMm: Int
        get() = prefs.getInt(KEY_PDF_ETIQ_ALT, 30)
        set(value) = prefs.edit().putInt(KEY_PDF_ETIQ_ALT, value.coerceIn(10, 80)).apply()

    /** Margem de impressão em mm (esquerda no retrato; superior no paisagem).
     *  Padrão 15 mm; a base do layout já embute ~10 mm. */
    var pdfMargemMm: Int
        get() = prefs.getInt(KEY_PDF_MARGEM, 15)
        set(value) = prefs.edit().putInt(KEY_PDF_MARGEM, value.coerceIn(10, 40)).apply()

    /** Pasta (SAF tree uri) usada para exportar/importar backup. Mesma para os dois. */
    var pastaBackupUri: String
        get() = prefs.getString(KEY_BACKUP_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKUP_URI, value).apply()

    // ----------- Compatibilidade com versões antigas -----------

    /**
     * Métodos legacy mantidos para o restante do código não quebrar.
     * Apenas leem/escrevem o destino primário.
     */
    var smbHost: String
        get() = obterDestino(0).host
        set(value) = salvarDestino(0, obterDestino(0).copy(host = value))

    var smbPorta: Int
        get() = obterDestino(0).porta
        set(value) = salvarDestino(0, obterDestino(0).copy(porta = value))

    var smbCaminhoUNC: String
        get() = obterDestino(0).caminhoUNC
        set(value) = salvarDestino(0, obterDestino(0).copy(caminhoUNC = value))

    fun smbCaminhoDecomposto(): Pair<String, String> = obterDestino(0).caminhoDecomposto()

    // ----------- Propriedades adicionadas para v7 -----------

    var nomeClinica: String
        get() = prefs.getString(KEY_NOME_CLINICA, "") ?: ""
        set(value) { prefs.edit().putString(KEY_NOME_CLINICA, value).commit() }

    /**
     * Sítios anatômicos / topografias (um por linha), editáveis em Configurações
     * → Equipe e Tratamentos.
     *
     * Sem lista gravada, vale a lista clínica padrão: no idioma fixado em
     * [sitiosIdiomaPadrao], pelo recurso `sitios_padrao`; sem idioma fixado, a
     * lista em português de [com.radioterapia.ai.util.TimeOutData], a mesma de
     * sempre, para uma instalação em uso não ver a lista mudar sozinha.
     *
     * O padrão NÃO é gravado em `sitios_lista`. Gravado, ele contaria como lista
     * preenchida, e a importação em modo SOMAR, que pula chave que já tem valor,
     * descartaria em silêncio a lista vinda de outro tablet.
     */
    var sitiosLista: String
        get() {
            val v = prefs.getString("sitios_lista", "") ?: ""
            if (v.isNotBlank()) return v
            val lang = sitiosIdiomaPadrao
            if (lang.isNotBlank()) {
                try {
                    val c = Configuration(app.resources.configuration)
                    c.setLocale(LocaleManager.localeDe(lang))
                    return app.createConfigurationContext(c).getString(R.string.sitios_padrao)
                } catch (_: Exception) { /* recurso ilegível: cai na lista de reserva */ }
            }
            return com.radioterapia.ai.util.TimeOutData.SITIOS.joinToString("\n")
        }
        set(value) { prefs.edit().putString("sitios_lista", value).commit() }

    /**
     * Idioma da lista padrão de sítios, fixado uma vez, no fim do onboarding.
     * Vazio = instalação sem fixação, que continua com a lista em português.
     *
     * Não acompanha trocas de idioma posteriores: a lista padrão é a do idioma
     * em que o serviço configurou o tablet, e trocar a interface depois não
     * troca, por baixo, o vocabulário clínico que a equipe já usa.
     */
    var sitiosIdiomaPadrao: String
        get() = prefs.getString(KEY_SITIOS_IDIOMA, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SITIOS_IDIOMA, value).commit() }

    /** Equipamentos da clínica (um por linha) — dropdown na finalização. */
    var equipamentos: String
        get() = prefs.getString("equipamentos", "") ?: ""
        set(value) { prefs.edit().putString("equipamentos", value).commit() }

    /** Médicos do Time-Out (um por linha), configuráveis nas Configurações. */
    var timeoutMedicos: String
        get() = prefs.getString("timeout_medicos", "") ?: ""
        set(value) { prefs.edit().putString("timeout_medicos", value).commit() }

    var csvPastaUnc: String
        get() = prefs.getString(KEY_CSV_PASTA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CSV_PASTA, value).apply()

    var csvTemCabecalho: Boolean
        get() = prefs.getBoolean(KEY_CSV_HEADER, true)
        set(value) = prefs.edit().putBoolean(KEY_CSV_HEADER, value).apply()

    var ultimoSyncCsv: Long
        get() = prefs.getLong(KEY_CSV_ULTIMO_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_CSV_ULTIMO_SYNC, value).apply()

    // ---------------------------------------------------------------- rubricario

    /**
     * Pagina de RUBRICARIO no PDF. Desligada por padrao: e pratica de servico,
     * nao de todos, e a folha so faz sentido com a equipe cadastrada.
     */
    var rubricarioAtivo: Boolean
        get() = prefs.getBoolean(KEY_RUB_ATIVO, false)
        set(value) = prefs.edit().putBoolean(KEY_RUB_ATIVO, value).apply()

    /** true = pagina em pe (retrato); false = deitada (paisagem). */
    var rubricarioRetrato: Boolean
        get() = prefs.getBoolean(KEY_RUB_RETRATO, true)
        set(value) = prefs.edit().putBoolean(KEY_RUB_RETRATO, value).apply()

    /**
     * Cargos do servico, uma linha "Cargo = Registro" por vez.
     *
     * Nao e lista fixa no codigo porque a nomenclatura do conselho muda entre
     * paises, e o app e universal — CRM, CNEN, CRT e COREN sao brasileiros.
     */
    var rubricarioCargos: String
        get() = prefs.getString(KEY_RUB_CARGOS, null)
            ?: com.radioterapia.ai.rubricario.RubricarioStore.CARGOS_PADRAO
        set(value) = prefs.edit().putString(KEY_RUB_CARGOS, value).apply()

    /** Grade da câmera FIXA na regra dos terços (sempre visível). */
    var cameraGrid: Boolean
        get() = true
        set(_) { /* fixo: regra dos terços sempre ligada */ }


    /**
     * Limpa todas as configurações persistidas (usado em "Restaurar padrões").
     */
    fun limparTudo() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PASTA_LOCAL = "pasta_base_local"
        private const val KEY_PASTA_FOTOS_URI = "pasta_fotos_uri"
        private const val KEY_PASTA_CSV_URI = "pasta_csv_uri"
        private const val KEY_HOST = "smb_host"
        private const val KEY_PORTA = "smb_porta"
        private const val KEY_PROTOCOLO = "smb_protocolo"
        private const val KEY_DOMINIO = "smb_dominio"
        private const val KEY_USUARIO = "smb_usuario"
        private const val KEY_CAMINHO_UNC = "smb_caminho_unc"

        private const val KEY_BACKUP_ATIVO = "smb_backup_ativo_"
        private const val KEY_BACKUP_HOST = "smb_backup_host_"
        private const val KEY_BACKUP_PORTA = "smb_backup_porta_"
        private const val KEY_BACKUP_UNC = "smb_backup_unc_"

        private const val KEY_PRINTER_IP = "printer_ip"
        private const val KEY_PRINTER_NOME = "printer_nome"
        private const val KEY_PDF_SERVIDOR = "pdf_servidor"
        private const val KEY_PDF_LANDSCAPE = "pdf_landscape"
        private const val KEY_FORMATO_DATA = "formato_data"
        private const val KEY_ATU_PROCURAR = "atualizacao_procurar"
        private const val KEY_ATU_CODE = "atualizacao_code"
        private const val KEY_ATU_NOME = "atualizacao_nome"
        private const val KEY_ATU_DISPENSADA = "atualizacao_dispensada"
        private const val KEY_ATU_CHECAGEM = "atualizacao_checagem"
        private const val KEY_ATU_DE = "atualizacao_de"
        private const val KEY_ATU_CONTAGENS = "atualizacao_contagens"
        private const val KEY_ATU_BACKUP = "atualizacao_backup"

        /**
         * Chaves que descrevem ESTE aparelho num instante (o marcador de
         * pré-instalação), e não a configuração do serviço. Ficam fora da
         * exportação e da importação em JSON: levadas a outro tablet, fariam a
         * conferência pós-atualização de lá comparar contagens que não são dele
         * e apontar uma cópia de segurança que lá não existe.
         */
        private val CHAVES_DO_APARELHO = setOf(KEY_ATU_DE, KEY_ATU_CONTAGENS, KEY_ATU_BACKUP)

        private const val KEY_SITIOS_IDIOMA = "sitios_idioma_padrao"
        private const val KEY_PDF_ETIQ_LARG = "pdf_etiqueta_largura_mm"
        private const val KEY_PDF_ETIQ_ALT = "pdf_etiqueta_altura_mm"
    private const val KEY_PDF_MARGEM = "pdf_margem_mm"
        private const val KEY_BACKUP_URI = "backup_uri"
        private const val KEY_BASE_CUSTOM = "base_custom_path"
        private const val KEY_ORDEM_HIST = "ordem_historico"

        // v7
        private const val KEY_NOME_CLINICA = "nome_clinica"
        private const val KEY_CSV_PASTA = "csv_pasta"
        private const val KEY_CSV_HEADER = "csv_header"
        private const val KEY_CSV_ULTIMO_SYNC = "csv_ultimo_sync"
        private const val KEY_RUB_ATIVO = "rubricario_ativo"
        private const val KEY_RUB_RETRATO = "rubricario_retrato"
        private const val KEY_RUB_CARGOS = "rubricario_cargos"
        private const val KEY_CAMERA_GRID = "camera_grid"
    }

    // ---------------- Exportar / importar configurações ----------------

    /**
     * Serializa TODAS as preferências num JSON. Motivação concreta: a cada
     * atualização do app o pessoal refazia nome da clínica, médicos,
     * equipamentos, sítios, tamanho de etiqueta e orientação da página. Com
     * isto, exporta-se uma vez e restaura-se em segundos — inclusive num
     * tablet novo.
     *
     * O LOGO fica de fora: é um arquivo binário e leva segundos para reenviar.
     */
    fun exportarJson(): String {
        val raiz = org.json.JSONObject()
        raiz.put("_versao", 1)
        raiz.put("_app", "PhotoID RT")
        raiz.put("_data", System.currentTimeMillis())
        val vals = org.json.JSONObject()
        for ((chave, valor) in prefs.all) {
            if (chave in CHAVES_DO_APARELHO) continue
            when (valor) {
                is String -> vals.put(chave, valor)
                is Boolean -> vals.put(chave, valor)
                is Int -> vals.put(chave, valor)
                is Long -> vals.put(chave, valor)
                is Float -> vals.put(chave, valor.toDouble())
                is Set<*> -> vals.put(chave, org.json.JSONArray(valor.toList()))
                else -> { /* tipo não suportado: ignora */ }
            }
        }
        raiz.put("valores", vals)
        return raiz.toString(2)
    }

    /**
     * Aplica um JSON gerado por [exportarJson]. Retorna quantas chaves entraram.
     * Preserva o que não estiver no arquivo, em vez de zerar a configuração.
     */
    fun importarJson(texto: String): Int {
        return try {
            val raiz = org.json.JSONObject(texto)
            val vals = raiz.optJSONObject("valores") ?: return 0
            val ed = prefs.edit()
            var n = 0
            for (chave in vals.keys()) {
                if (chave in CHAVES_DO_APARELHO) continue
                when (val v = vals.get(chave)) {
                    is String -> ed.putString(chave, v)
                    is Boolean -> ed.putBoolean(chave, v)
                    is Int -> ed.putInt(chave, v)
                    is Long -> ed.putLong(chave, v)
                    is Double -> ed.putFloat(chave, v.toFloat())
                    is org.json.JSONArray -> {
                        val set = mutableSetOf<String>()
                        for (i in 0 until v.length()) set.add(v.optString(i))
                        ed.putStringSet(chave, set)
                    }
                    else -> continue
                }
                n++
            }
            ed.commit()
            n
        } catch (_: Exception) { 0 }
    }
}

