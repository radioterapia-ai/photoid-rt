package com.radioterapia.ai

import android.content.Context

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

    /** URI (SAF/tree) da pasta local onde está a base de dados CSV (lida pelo FolderSync). */
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

    /** Sítios anatômicos / topografias (um por linha). Padrão: lista clínica
     *  completa; editável em Configurações → Equipe e Tratamentos. */
    var sitiosLista: String
        get() {
            val v = prefs.getString("sitios_lista", "") ?: ""
            return if (v.isBlank())
                com.radioterapia.ai.util.TimeOutData.SITIOS.joinToString("\n")
            else v
        }
        set(value) { prefs.edit().putString("sitios_lista", value).commit() }

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

