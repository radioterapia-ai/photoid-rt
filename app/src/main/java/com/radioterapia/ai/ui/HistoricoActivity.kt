package com.radioterapia.ai.ui

import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radioterapia.ai.R
import com.radioterapia.ai.patient.PatientCache
import com.radioterapia.ai.treatment.TreatmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Listagem do histórico local de pacientes.
 *
 * TRÊS modos de uso (item: histórico unificado, sem edição fora das Configurações):
 *  - MODO_SELECAO (Tratamento/Check): toque retorna nome+prontuário pra Activity chamadora
 *    (que abre a visualização das fotos). Ativado por TreatmentActivity.EXTRA_MODO_SELECAO.
 *  - MODO_RETOMAR (Simulação/Sim): toque inicia nova captura de fotos para o paciente
 *    existente (abre MainActivity já com os dados dele). Ativado por EXTRA_MODO_RETOMAR.
 *  - MODO_EDICAO (Configurações): toque abre EditarPacienteActivity (renomear, excluir
 *    fotos, etc). Ativado por EXTRA_MODO_EDICAO. É o ÚNICO ponto de edição do app.
 *
 * Default (nenhuma extra) = MODO_RETOMAR, comportamento seguro (nunca edita por engano).
 */
class HistoricoActivity : com.radioterapia.ai.BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.patient_history)

    override fun acaoToolbar(): Int = R.drawable.ic_more_actions

    override fun aoTocarAcaoToolbar() {
        val view = layoutInflater.inflate(R.layout.dialog_acoes_lista, null)
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tituloPadrao())
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()
        view.findViewById<View>(R.id.popLoteImprimir).setOnClickListener {
            dlg.dismiss(); entrarModoLote()
        }
        dlg.show()
    }

    // ---------- Modo LOTE (seleção múltipla para impressão) ----------

    private fun entrarModoLote() {
        val ad = adapter ?: return
        ad.aoMudarSelecao = { n ->
            findViewById<TextView>(R.id.txtLoteContagem).text =
                getString(R.string.lote_contagem, n)
        }
        ad.entrarModoLote()
        findViewById<View>(R.id.barraLote).visibility = View.VISIBLE
        findViewById<TextView>(R.id.txtLoteContagem).text = getString(R.string.lote_contagem, 0)
        findViewById<View>(R.id.btnLoteTodos).setOnClickListener { ad.alternarTodos() }
        findViewById<View>(R.id.btnLoteCancelar).setOnClickListener { sairModoLote() }
        findViewById<View>(R.id.btnLoteImprimir).setOnClickListener { imprimirLote() }
    }

    private fun sairModoLote() {
        adapter?.sairModoLote()
        findViewById<View>(R.id.barraLote).visibility = View.GONE
    }

    /**
     * Imprimir o lote: vai direto ao SELETOR DE MODO. A distinção entre
     * individual e agrupado só faz diferença no pen-drive (são pastas
     * diferentes); pela impressora de rede ou pelo serviço do Android saem as
     * mesmas páginas físicas, então nem se pergunta — manda-se o agrupado, que
     * é um trabalho de impressão só.
     */
    private fun imprimirLote() {
        val escolhidos = adapter?.selecionados().orEmpty()
        if (escolhidos.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.lote_nenhum,
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val prog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.lote_preparando, escolhidos.size))
            .setCancelable(false).create()
        prog.show()
        CoroutineScope(Dispatchers.Main).launch {
            val dados = withContext(Dispatchers.IO) {
                val fetcher = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this@HistoricoActivity)
                val pares = escolhidos.mapNotNull { d ->
                    try {
                        val pdf = fetcher.buscarSimulacoes(d.nome)
                            .maxByOrNull { it.timestampPrincipal }?.arquivoPdfLocal
                        if (pdf != null && pdf.exists()) pdf to d.nome else null
                    } catch (_: Exception) { null }
                }
                pares to montarItensLote(escolhidos)
            }
            prog.dismiss()
            val (pares, itens) = dados
            if (pares.isEmpty() && itens.isEmpty()) {
                android.widget.Toast.makeText(this@HistoricoActivity, R.string.lote_sem_pdf,
                    android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            sairModoLote()
            escolherModoImpressaoLote(
                pares.map { it.first }, pares.map { it.second }, itens)
        }
    }

    /** Reúne fotos, cadastro, Time-Out e observações de cada paciente escolhido. */
    private suspend fun montarItensLote(
        escolhidos: List<PatientCache.DadosPaciente>
    ): List<com.radioterapia.ai.pdf.PdfBuilder.ItemLote> {
        val cfg = com.radioterapia.ai.AppConfig(this)
        val fetcher = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this)
        val itens = mutableListOf<com.radioterapia.ai.pdf.PdfBuilder.ItemLote>()
        for (d in escolhidos) {
            try {
                val sim = fetcher.buscarSimulacoes(d.nome)
                    .maxByOrNull { it.timestampPrincipal } ?: continue
                val fotos = mutableListOf<java.io.File>()
                val rotulos = mutableListOf<String>()
                // Vocabulário CANÔNICO em português, como nos demais caminhos: o
                // PdfBuilder traduz no desenho (traduzirRotulo). Passar o rótulo
                // já traduzido furava a tradução — "Label" caía no `else` e saía
                // em inglês num PDF em espanhol — e escaparia do filtro que tira
                // a etiqueta do grid.
                sim.rosto?.let { fotos.add(it.arquivoLocal); rotulos.add("Rosto") }
                sim.etiqueta?.let { fotos.add(it.arquivoLocal); rotulos.add("Etiqueta") }
                sim.posicionamentos.forEachIndexed { i, f ->
                    fotos.add(f.arquivoLocal); rotulos.add("Posicionamento." + (i + 1)) }
                sim.acessoriosLista.forEachIndexed { i, f ->
                    fotos.add(f.arquivoLocal); rotulos.add("Acessório." + (i + 1)) }
                if (fotos.isEmpty()) continue

                val pasta = com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
                    this, d.nome, sim.numeroSimulacao, sim.nomePastaCompleto, d.prontuario)
                val reg = try {
                    com.radioterapia.ai.util.TimeOutStore.ler(pasta, sim.numeroSimulacao)
                } catch (_: Exception) { null }
                val obs = try {
                    com.radioterapia.ai.util.ObsStore.ler(pasta, sim.numeroSimulacao)
                } catch (_: Exception) { "" }

                val dados = com.radioterapia.ai.pdf.PdfBuilder.DadosCabecalho(
                    nomePaciente = d.nome,
                    nascimento = d.nascimento,
                    prontuario = d.prontuario,
                    idsExtras = emptyList(),
                    dataSimulacao = java.util.Date(sim.timestampPrincipal),
                    numeroSimulacao = sim.numeroSimulacao,
                    nomeClinica = cfg.nomeClinica,
                    sexo = d.sexo,
                    medicoAssistente = reg?.medico ?: d.medicoAssistente)
                val timeOut = if (cfg.pdfIncluiTimeOut && reg != null)
                    com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut(
                        reg.medico, reg.sitio, reg.riscoQueda, reg.precaucaoContato,
                        sim.rosto?.arquivoLocal, reg.equipamento, reg.alergia,
                        reg.fracoesMax) else null

                itens.add(com.radioterapia.ai.pdf.PdfBuilder.ItemLote(
                    dados = dados, fotos = fotos, rotulos = rotulos,
                    landscape = cfg.pdfLandscape,
                    etiquetaLarguraMm = cfg.pdfEtiquetaLarguraMm,
                    etiquetaAlturaMm = cfg.pdfEtiquetaAlturaMm,
                    observacoes = obs, timeOut = timeOut,
                    margemImpressaoMm = cfg.pdfMargemMm))
            } catch (_: Exception) { /* paciente sem dados legíveis: pula */ }
        }
        return itens
    }

    private lateinit var patientCache: PatientCache
    private val config by lazy { com.radioterapia.ai.AppConfig(this) }
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var txtCount: TextView
    private lateinit var edtBusca: android.widget.EditText
    private var adapter: HistoricoAdapter? = null

    private var modoSelecao = false
    private var modoEdicao = false
    private var modoTratamento = false       // lista "Pacientes em Tratamento"
    /** C6/M2: busca e rolagem sobrevivem à rotação (a Activity é recriada). */
    private var buscaSalva: String = ""
    /** Dia escolhido no filtro (qualquer instante dele); null = todas as datas. */
    private var filtroDataMs: Long? = null
    private var rolagemSalva: android.os.Parcelable? = null
    private var modoHistoricoTrat = false    // histórico aberto pelo módulo Tratamento (com "Tratar")

    private lateinit var tratamento: com.radioterapia.ai.treatment.TreatmentListManager
    private var modoScanAtual = ""
    private lateinit var scanLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historico)
        modoSelecao = intent.getBooleanExtra(TreatmentActivity.EXTRA_MODO_SELECAO, false)
        modoEdicao = intent.getBooleanExtra(EXTRA_MODO_EDICAO, false)
        modoTratamento = intent.getBooleanExtra(EXTRA_MODO_TRATAMENTO, false)
        buscaSalva = savedInstanceState?.getString(ST_BUSCA) ?: ""
        rolagemSalva = savedInstanceState?.getParcelable(ST_ROLAGEM)
        modoHistoricoTrat = intent.getBooleanExtra(EXTRA_MODO_HISTORICO_TRAT, false)
        when {
            modoTratamento -> setToolbarTitle(getString(R.string.patients_in_treatment))
            modoHistoricoTrat -> setToolbarTitle(getString(R.string.patient_history_title))
            modoSelecao -> setToolbarTitle(getString(R.string.select_patient))
            modoEdicao -> setToolbarTitle("Editar pacientes")
        }

        patientCache = PatientCache(this)
        tratamento = com.radioterapia.ai.treatment.TreatmentListManager(this)
        recycler = findViewById(R.id.recyclerHistorico)
        txtEmpty = findViewById(R.id.txtHistoricoEmpty)
        txtCount = findViewById(R.id.txtHistoricoCount)
        edtBusca = findViewById(R.id.edtBuscaPaciente)
        if (buscaSalva.isNotBlank()) edtBusca.setText(buscaSalva)

        // Busca incremental (filtra por nome/prontuário enquanto digita)
        edtBusca.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                adapter?.filtrar(s?.toString() ?: "")
                atualizarVazio()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Cabeçalho de scan: só no modo "Pacientes em Tratamento"
        val layoutScan = findViewById<View>(R.id.layoutScanHeader)
        if (modoTratamento) {
            layoutScan.visibility = View.VISIBLE
            configurarScanLauncher()
            com.radioterapia.ai.util.UiText.uniformizar(
                findViewById(R.id.btnScanEtiqueta), findViewById(R.id.btnScanCodigo))
            findViewById<android.widget.Button>(R.id.btnScanEtiqueta).setOnClickListener {
                modoScanAtual = com.radioterapia.ai.scan.ScanPacienteActivity.MODO_OCR; iniciarScan()
            }
            findViewById<android.widget.Button>(R.id.btnScanCodigo).setOnClickListener {
                modoScanAtual = com.radioterapia.ai.scan.ScanPacienteActivity.MODO_BARRAS; iniciarScan()
            }
        } else {
            layoutScan.visibility = View.GONE
        }

        // Botão Ordenar (mais recente / por nome / por prontuário) — persiste a escolha.
        // Lupa: abre/fecha a caixa de busca flutuante abaixo dela
        findViewById<View>(R.id.btnLupa).setOnClickListener {
            val card = findViewById<View>(R.id.cardBusca)
            if (card.visibility == View.VISIBLE) fecharBusca() else {
                card.visibility = View.VISIBLE
                val edt = findViewById<android.widget.EditText>(R.id.edtBuscaPaciente)
                edt.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(edt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        findViewById<android.widget.Button>(R.id.btnOrdenar).text = getString(R.string.filtro_botao)
        findViewById<android.widget.Button>(R.id.btnOrdenar).setOnClickListener {
            fecharBusca(); abrirFiltro()
        }
    }

    /**
     * Filtrar e ordenar num só lugar. Antes era só "Ordenar" num menu suspenso;
     * em tela pequena não cabia campo + direção + data. Aqui os três convivem,
     * e o filtro por dia serve ao lote: seleciona-se tudo de uma data e imprime.
     */
    private fun abrirFiltro() {
        val view = layoutInflater.inflate(R.layout.dialog_filtro_lista, null)
        val rgCampo = view.findViewById<android.widget.RadioGroup>(R.id.rgFiltroCampo)
        val rgDir = view.findViewById<android.widget.RadioGroup>(R.id.rgFiltroDirecao)
        val txtData = view.findViewById<TextView>(R.id.txtFiltroDataAtual)

        when (config.ordemHistorico) {
            "nome" -> rgCampo.check(R.id.rbFiltroNome)
            "prontuario" -> rgCampo.check(R.id.rbFiltroProntuario)
            else -> rgCampo.check(R.id.rbFiltroData)
        }
        rgDir.check(if (config.ordemHistoricoAsc) R.id.rbFiltroAsc else R.id.rbFiltroDesc)

        var dataTemp: Long? = filtroDataMs
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        fun pintarData() {
            txtData.text = dataTemp?.let { getString(R.string.filtro_data_ativa, fmt.format(java.util.Date(it))) }
                ?: getString(R.string.filtro_data_nenhuma)
        }
        pintarData()

        view.findViewById<View>(R.id.btnFiltroEscolherData).setOnClickListener {
            val cal = java.util.Calendar.getInstance().apply { dataTemp?.let { timeInMillis = it } }
            android.app.DatePickerDialog(this, { _, ano, mes, dia ->
                dataTemp = java.util.Calendar.getInstance().apply {
                    set(ano, mes, dia, 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                pintarData()
            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
               cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        view.findViewById<View>(R.id.btnFiltroLimparData).setOnClickListener {
            dataTemp = null; pintarData()
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.filtro_titulo)
            .setView(view)
            .setPositiveButton(R.string.filtro_aplicar) { _, _ ->
                config.ordemHistorico = when (rgCampo.checkedRadioButtonId) {
                    R.id.rbFiltroNome -> "nome"
                    R.id.rbFiltroProntuario -> "prontuario"
                    else -> "recente"
                }
                config.ordemHistoricoAsc = rgDir.checkedRadioButtonId == R.id.rbFiltroAsc
                filtroDataMs = dataTemp
                recarregar()
                if (dataTemp != null) android.widget.Toast.makeText(this,
                    getString(R.string.filtro_resultado, adapter?.itemCount ?: 0),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun atualizarVazio() {
        val vazio = (adapter?.itemCount ?: 0) == 0
        txtEmpty.visibility = if (vazio) View.VISIBLE else View.GONE
        recycler.visibility = if (vazio) View.GONE else View.VISIBLE
    }

    // ===== Scan (só na lista de tratamento): filtra; se sobrar 1, abre direto =====

    private fun configurarScanLauncher() {
        scanLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val termo = if (modoScanAtual == com.radioterapia.ai.scan.ScanPacienteActivity.MODO_BARRAS) {
                data.getStringExtra(com.radioterapia.ai.scan.ScanPacienteActivity.RESULT_CODIGO_BARRAS) ?: ""
            } else {
                val texto = data.getStringExtra(com.radioterapia.ai.scan.ScanPacienteActivity.RESULT_TEXTO_OCR) ?: ""
                com.radioterapia.ai.scan.EtiquetaParser.extrairProntuario(texto)
            }
            if (termo.isBlank()) {
                android.widget.Toast.makeText(this, R.string.patient_not_found, android.widget.Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            // Filtra a lista pelo termo lido
            edtBusca.setText(termo)
            val visiveis = adapter?.itensVisiveis() ?: emptyList()
            if (visiveis.size == 1) {
                abrirVisualizador(visiveis[0])  // achou 1 → abre direto as fotos
            }
            // senão, os resultados já estão filtrados na própria lista
        }
    }

    private fun iniciarScan() {
        val intent = Intent(this, com.radioterapia.ai.scan.ScanPacienteActivity::class.java)
        intent.putExtra(com.radioterapia.ai.scan.ScanPacienteActivity.EXTRA_MODO, modoScanAtual)
        scanLauncher.launch(intent)
    }

    private fun abrirVisualizador(dados: PatientCache.DadosPaciente) {
        val intent = Intent(this, com.radioterapia.ai.treatment.PatientViewerActivity::class.java)
        intent.putExtra(com.radioterapia.ai.treatment.PatientViewerActivity.EXTRA_NOME, dados.nome)
        intent.putExtra(com.radioterapia.ai.treatment.PatientViewerActivity.EXTRA_PRONTUARIO, dados.prontuario)
        startActivity(intent)
    }

    /** Oculta a caixa de busca flutuante (o filtro digitado permanece aplicado). */
    private fun fecharBusca() {
        val card = findViewById<View>(R.id.cardBusca)
        if (card.visibility != View.VISIBLE) return
        card.visibility = View.GONE
        val edt = findViewById<android.widget.EditText>(R.id.edtBuscaPaciente)
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(edt.windowToken, 0)
    }

    /** Clique fora da caixa (e fora da lupa) fecha a busca. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val card = findViewById<View>(R.id.cardBusca)
        if (card?.visibility == View.VISIBLE &&
            ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val dentro = { v: View ->
                val p = IntArray(2); v.getLocationOnScreen(p)
                ev.rawX >= p[0] && ev.rawX <= p[0] + v.width &&
                    ev.rawY >= p[1] && ev.rawY <= p[1] + v.height
            }
            val lupa = findViewById<View>(R.id.btnLupa)
            if (!dentro(card) && !dentro(lupa)) fecharBusca()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // Lê o que o FolderSync trouxe para PHOTOS (pacientes novos do servidor).
        try { PatientCache(this).sincronizarComPastas(this) } catch (_: Exception) {}
        recarregar()
    }

    private fun recarregar() {
        // Preserva a posição da rolagem (ex.: ao clicar "Tratar" em vários pacientes)
        val estadoRolagem = recycler.layoutManager?.onSaveInstanceState()
        patientCache = PatientCache(this)
        val nomes = patientCache.listarPacientes()
        var pacientes = nomes.mapNotNull { patientCache.obterDadosPaciente(it) }

        // Filtro por DIA da simulação — pensado para o lote: dá para pegar todos
        // os pacientes de uma data e mandar imprimir de uma vez.
        filtroDataMs?.let { diaEscolhido ->
            val ini = java.util.Calendar.getInstance().apply {
                timeInMillis = diaEscolhido
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val fim = ini + 24L * 60 * 60 * 1000
            pacientes = pacientes.filter { it.ultimaSimulacao in ini until fim }
        }

        // Ordenação escolhida pelo usuário (campo + direção, ambos persistidos).
        pacientes = when (config.ordemHistorico) {
            "nome" -> pacientes.sortedBy { com.radioterapia.ai.util.StorageLocal.chaveNome(it.nome) }
            "prontuario" -> pacientes.sortedBy { it.prontuario }
            else -> pacientes.sortedBy { it.ultimaSimulacao }
        }
        if (!config.ordemHistoricoAsc) pacientes = pacientes.reversed()

        // No modo "Pacientes em Tratamento", mostra só os que estão em tratamento.
        if (modoTratamento) {
            val emTrat = tratamento.chavesEmTratamento()
            pacientes = pacientes.filter {
                emTrat.contains(com.radioterapia.ai.util.StorageLocal.chaveNome(it.nome))
            }
        }

        txtCount.text = if (modoTratamento)
            resources.getQuantityString(R.plurals.treatment_count_plural,
                if (pacientes.isEmpty()) 2 else pacientes.size, pacientes.size)
        else if (pacientes.isEmpty()) resources.getQuantityString(
            R.plurals.patient_count_plural, 2, 0)  // força plural: "0 pacientes registrados"
        else resources.getQuantityString(R.plurals.patient_count_plural, pacientes.size, pacientes.size)

        if (pacientes.isEmpty()) {
            txtEmpty.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            adapter = null
            return
        }
        txtEmpty.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        recycler.layoutManager = LinearLayoutManager(this)

        // PDF e impressora: disponíveis nas listas de tratamento e seleção.
        val mostrarPdf = modoSelecao || modoTratamento || modoHistoricoTrat
        val pdfHandler: ((PatientCache.DadosPaciente, android.widget.ImageView) -> Unit)? =
            if (mostrarPdf) { dados, _ -> abrirPdfDoPaciente(dados) } else null
        val configImp = com.radioterapia.ai.AppConfig(this)
        val printHandler: ((PatientCache.DadosPaciente) -> Unit)? =
            if (mostrarPdf && configImp.temImpressora()) { dados -> confirmarReimpressao(dados) } else null

        // Botão Alta (no tratamento) ou Tratar/Em tratamento (no histórico)
        var tratLabel: String? = null
        var tratCor = R.color.brand_primary
        var tratClick: ((PatientCache.DadosPaciente) -> Unit)? = null
        var tratProvider: ((PatientCache.DadosPaciente) -> Pair<String, Int>)? = null
        if (modoTratamento) {
            tratLabel = getString(R.string.discharge_action)
            tratCor = R.color.success_green
            tratClick = { dados -> confirmarAlta(dados) }
        } else if (modoHistoricoTrat || modoSelecao) {
            // Histórico (tratamento OU simulação RT SIM): mesmo visual.
            // "Em tratamento" (cinza, sem ação) ou "Tratar" (azul) por paciente.
            tratProvider = { dados ->
                if (tratamento.estaEmTratamento(dados.nome))
                    getString(R.string.in_treatment_tag) to R.color.text_secondary
                else getString(R.string.treat_action) to R.color.brand_primary
            }
            tratClick = { dados ->
                if (tratamento.estaEmTratamento(dados.nome)) {
                    android.widget.Toast.makeText(this, R.string.only_discharge_in_list, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    alocarTratamento(dados); recarregar()
                }
            }
        }

        adapter = HistoricoAdapter(pacientes, patientCache, pdfHandler, printHandler,
            tratLabel, tratCor, tratClick, tratProvider) { dadosClicado ->
            fecharBusca()
            when {
                modoSelecao -> {
                    val data = Intent().apply {
                        putExtra(TreatmentActivity.EXTRA_NOME_SELECIONADO, dadosClicado.nome)
                        putExtra(TreatmentActivity.EXTRA_PRONT_SELECIONADO, dadosClicado.prontuario)
                    }
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }
                modoTratamento || modoHistoricoTrat -> {
                    // Toque abre o visualizador (fotos/carrossel) do paciente.
                    abrirVisualizador(dadosClicado)
                }
                modoEdicao -> {
                    val intent = Intent(this, EditarPacienteActivity::class.java)
                    intent.putExtra(EditarPacienteActivity.EXTRA_NOME, dadosClicado.nome)
                    startActivity(intent)
                }
                else -> {
                    val intent = Intent(this, com.radioterapia.ai.MainActivity::class.java).apply {
                        putExtra("paciente_reusar", dadosClicado.nome)
                        putExtra("prontuario_reusar", dadosClicado.prontuario)
                        putExtra("nascimento_reusar", dadosClicado.nascimento)
                    }
                    startActivity(intent)
                    finish()
                }
            }
        }
        recycler.adapter = adapter
        recycler.layoutManager?.onRestoreInstanceState(estadoRolagem)
        rolagemSalva?.let { recycler.layoutManager?.onRestoreInstanceState(it); rolagemSalva = null }
        // Reaplica o filtro de busca atual (mantém o texto ao voltar de telas)
        adapter?.filtrar(edtBusca.text?.toString() ?: "")
        atualizarVazio()
    }

    /** Alta: confirma e remove o paciente da lista de tratamento. */
    private fun confirmarAlta(dados: PatientCache.DadosPaciente) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.discharge_action)
            .setMessage(getString(R.string.discharge_confirm, dados.nome))
            .setPositiveButton(R.string.confirm) { _, _ ->
                tratamento.darAlta(dados.nome)
                android.widget.Toast.makeText(this, R.string.discharge_done, android.widget.Toast.LENGTH_SHORT).show()
                recarregar()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Tratar: aloca o paciente do histórico para a lista de tratamento. */
    private fun alocarTratamento(dados: PatientCache.DadosPaciente) {
        if (tratamento.estaEmTratamento(dados.nome)) {
            android.widget.Toast.makeText(this, R.string.already_in_treatment, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        tratamento.alocar(dados.nome)
        android.widget.Toast.makeText(this, R.string.treat_done, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Busca o PDF da simulação mais recente do paciente e abre no leitor padrão. */
    /** Pergunta antes de reenviar a folha à impressora. */
    private fun confirmarReimpressao(dados: PatientCache.DadosPaciente) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.reprint_pdf)
            .setMessage(getString(R.string.reprint_confirm, dados.nome))
            .setPositiveButton(R.string.confirm) { _, _ -> reimprimirPdf(dados) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun reimprimirPdf(dados: PatientCache.DadosPaciente) {
        val progresso = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.printing)); setCancelable(false); show()
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val resultado = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val fetcher = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this@HistoricoActivity)
                    val pdf = fetcher.buscarSimulacoes(dados.nome)
                        .maxByOrNull { it.timestampPrincipal }?.arquivoPdfLocal
                    if (pdf == null || !pdf.exists()) null
                    else {
                        val config = com.radioterapia.ai.AppConfig(this@HistoricoActivity)
                        com.radioterapia.ai.print.PrinterClient(config.impressoraIp).imprimirPdf(pdf, config.printerDuplexMode)
                    }
                } catch (_: Exception) { null }
            }
            progresso.dismiss()
            when {
                resultado == null ->
                    android.widget.Toast.makeText(this@HistoricoActivity, R.string.pdf_not_found, android.widget.Toast.LENGTH_SHORT).show()
                resultado.sucesso ->
                    android.widget.Toast.makeText(this@HistoricoActivity,
                        getString(R.string.printed_via, resultado.protocoloUsado), android.widget.Toast.LENGTH_LONG).show()
                else ->
                    android.widget.Toast.makeText(this@HistoricoActivity, resultado.mensagem, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun abrirPdfDoPaciente(dados: PatientCache.DadosPaciente) {
        val progresso = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.loading)); setCancelable(false); show()
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val pdf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val fetcher = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this@HistoricoActivity)
                    val sims = fetcher.buscarSimulacoes(dados.nome)
                    sims.maxByOrNull { it.timestampPrincipal }?.arquivoPdfLocal
                } catch (_: Exception) { null }
            }
            progresso.dismiss()
            if (pdf != null && pdf.exists()) {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@HistoricoActivity, "${applicationContext.packageName}.fileprovider", pdf)
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(view, getString(R.string.pdf_button))
                        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivity(chooser)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@HistoricoActivity, R.string.pdf_open_error, android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                android.widget.Toast.makeText(this@HistoricoActivity, R.string.pdf_not_found, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun aoTocarVoltarToolbar() {
        if (adapter?.estaEmModoLote() == true) sairModoLote() else finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ST_BUSCA, edtBusca.text?.toString() ?: "")
        recycler.layoutManager?.onSaveInstanceState()?.let { outState.putParcelable(ST_ROLAGEM, it) }
    }

    companion object {
        private const val ST_BUSCA = "st_busca"
        private const val ST_ROLAGEM = "st_rolagem"
        const val EXTRA_MODO_EDICAO = "modo_edicao"
        const val EXTRA_MODO_TRATAMENTO = "modo_tratamento"
        const val EXTRA_MODO_HISTORICO_TRAT = "modo_historico_trat"
    }
}
