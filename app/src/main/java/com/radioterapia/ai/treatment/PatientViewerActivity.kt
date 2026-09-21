package com.radioterapia.ai.treatment

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.radioterapia.ai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela de visualização das fotos do paciente em tratamento (módulo Paperless).
 *
 * Layout:
 *  - Cabeçalho: nome do paciente + dropdown se houver múltiplas simulações
 *  - Esquerda: foto rosto (cima) + foto etiqueta (baixo)
 *  - Direita: carrossel iniciando por acessórios e seguindo posicionamentos
 *  - Rodapé: botão "Adicionar foto durante tratamento"
 *
 * Em paisagem (tablet): split horizontal.
 * Em retrato: tudo empilhado.
 */
class PatientViewerActivity : com.radioterapia.ai.BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.treatment)

    private lateinit var fetcher: TreatmentPhotoFetcher

    private lateinit var nomePaciente: String
    private lateinit var prontuario: String
    private val patientCache by lazy { com.radioterapia.ai.patient.PatientCache(this) }

    private lateinit var txtNome: TextView
    private lateinit var txtMeta: TextView
    private lateinit var spinnerSim: Spinner
    private lateinit var rowSimSelector: View
    private lateinit var imgDocs: android.widget.ImageView
    private lateinit var frameDocs: View
    private var rotulosAtuais: List<String> = emptyList()
    private var callbackRegistrado = false
    private lateinit var imgEtiqueta: ImageView
    private lateinit var imgPosic: ImageView
    private lateinit var viewPagerCarrossel: ViewPager2
    private lateinit var imgVisorUnico: ImageView
    private lateinit var txtVisorUnicoLabel: TextView
    private lateinit var btnVoltarCarrossel: Button
    private lateinit var txtCarrosselInfo: TextView
    private lateinit var progresso: ProgressBar
    private lateinit var layoutVazio: LinearLayout
    /** PDF da simulação exibida no carrossel (para o popup de ações). */
    private var pdfAtualCarrossel: java.io.File? = null
    private lateinit var txtOrigem: TextView
    private lateinit var btnAbrirPdf: ImageButton
    private lateinit var txtViewerPaciente: TextView

    private var simulacoes: List<TreatmentPhotoFetcher.Simulacao> = emptyList()
    private var simulacaoAtual: TreatmentPhotoFetcher.Simulacao? = null


    /** Qual bloco está no visor — preservado na rotação (C6/M2). */
    private var blocoAtual = BLOCO_RESUMO
    /** Página do carrossel — preservada na rotação. */
    private var paginaCarrossel = 0
    /** Para cada página do carrossel único, a miniatura correspondente. */
    private val blocoDaPagina = mutableListOf<Int>()
    /** Primeira página de cada bloco (clique na miniatura salta para cá). */
    private val paginaDoBloco = mutableMapOf<Int, Int>()
    /** Listas do carrossel único, guardadas para navegação entre blocos. */
    private var fotosUnificadas: List<TreatmentPhotoFetcher.FotoInfo> = emptyList()
    private var rotulosUnificados: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_viewer)

        nomePaciente = intent.getStringExtra(EXTRA_NOME) ?: ""
        prontuario = intent.getStringExtra(EXTRA_PRONTUARIO) ?: ""
        configurarVoltar()
        savedInstanceState?.let {
            blocoAtual = it.getInt(ST_BLOCO, BLOCO_RESUMO)
            paginaCarrossel = it.getInt(ST_PAGINA, 0)
        }

        fetcher = TreatmentPhotoFetcher(this)

        txtNome = findViewById(R.id.txtPvNome)
        txtMeta = findViewById(R.id.txtPvMeta)
        spinnerSim = findViewById(R.id.spinnerSim)
        rowSimSelector = findViewById(R.id.rowSimSelector)
        imgEtiqueta = findViewById(R.id.imgPvEtiqueta)
        imgPosic = findViewById(R.id.imgPvPosic)
        imgDocs = findViewById(R.id.imgPvDocs)
        frameDocs = findViewById(R.id.frameThumbDocs)
        viewPagerCarrossel = findViewById(R.id.viewPagerCarrossel)
        imgVisorUnico = findViewById(R.id.imgPvVisorUnico)
        txtVisorUnicoLabel = findViewById(R.id.txtVisorUnicoLabel)
        btnVoltarCarrossel = findViewById(R.id.btnVoltarCarrossel)
        txtCarrosselInfo = findViewById(R.id.txtCarrosselInfo)
        progresso = findViewById(R.id.progressPv)
        layoutVazio = findViewById(R.id.layoutPvVazio)
        txtOrigem = findViewById(R.id.txtPvOrigem)
        btnAbrirPdf = findViewById(R.id.btnAbrirPdf)
        txtViewerPaciente = findViewById(R.id.txtViewerPaciente)

        txtNome.text = nomePaciente
        txtMeta.text = if (prontuario.isNotBlank())
            getString(R.string.viewer_record, prontuario) else ""
        // Identificação do paciente exibida na faixa cinza inferior dos visores
        txtViewerPaciente.text = montarIdentificacao()

        btnVoltarCarrossel.setOnClickListener { mostrarCarrossel() }
    }

    override fun onResume() {
        super.onResume()
        carregarFotos()
    }

    // C6/M2: a Activity é recriada ao girar; sem isto o carrossel voltava
    // sempre para a primeira foto e o bloco selecionado se perdia.
    /**
     * ITEM 4: o botão voltar do sistema retorna ao RESUMO quando há uma foto ou
     * o carrossel em destaque — só sai do paciente estando já no resumo.
     */
    private fun configurarVoltar() {
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val sim = simulacaoAtual
                    if (blocoAtual != BLOCO_RESUMO && sim != null) mostrarResumo(sim)
                    else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            })
    }

    override fun acaoToolbar(): Int = R.drawable.ic_more_actions
    override fun aoTocarAcaoToolbar() { abrirPopupAcoes() }

    override fun aoTocarVoltarToolbar() {
        val sim = simulacaoAtual
        if (blocoAtual != BLOCO_RESUMO && sim != null) mostrarResumo(sim) else finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ST_BLOCO, blocoAtual)
        outState.putInt(ST_PAGINA, paginaCarrossel)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun carregarFotos() {
        progresso.visibility = View.VISIBLE
        layoutVazio.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            simulacoes = withContext(Dispatchers.IO) {
                fetcher.buscarSimulacoes(nomePaciente)
            }
            progresso.visibility = View.GONE

            if (simulacoes.isEmpty()) {
                layoutVazio.visibility = View.VISIBLE
                rowSimSelector.visibility = View.GONE
                txtOrigem.visibility = View.GONE
                btnAbrirPdf.isEnabled = false
                return@launch
            }

            // Configura dropdown de simulações
            if (simulacoes.size > 1) {
                rowSimSelector.visibility = View.VISIBLE
                val rotulos = simulacoes.map { sim ->
                    val data = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(sim.timestampPrincipal))
                    if (sim.numeroSimulacao == 1) getString(R.string.sim_original, data)
                    else getString(R.string.sim_new, sim.numeroSimulacao - 1, data)
                }
                val adapterSpin = ArrayAdapter(
                    this@PatientViewerActivity,
                    R.layout.spinner_item_sim,
                    rotulos
                )
                adapterSpin.setDropDownViewResource(R.layout.spinner_dropdown_sim)
                spinnerSim.adapter = adapterSpin
                spinnerSim.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        renderizarSimulacao(simulacoes[position])
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                spinnerSim.setSelection(0)  // mais recente (lista vem ordenada desc)
            } else {
                rowSimSelector.visibility = View.GONE
                renderizarSimulacao(simulacoes[0])
            }
        }
    }

    private fun renderizarSimulacao(sim: TreatmentPhotoFetcher.Simulacao) {
        simulacaoAtual = sim
        layoutVazio.visibility = View.GONE
        btnAbrirPdf.isEnabled = true

        // Origem (servidor/tablet) não é mais exibida — uso local é comum e o
        // alerta passava impressão de erro. Foco do RT Check é a visualização.

        // Ações rápidas do paciente (⋮): sempre visível no carrossel;
        // "Mostrar PDF" avisa se o arquivo não existir localmente.
        pdfAtualCarrossel = sim.arquivoPdfLocal?.takeIf { it.exists() }
        // O ⋮ vive na BARRA DE TÍTULO (acaoToolbar), não flutuando sobre a foto.
        btnAbrirPdf.visibility = View.GONE

        val opt = BitmapFactory.Options().apply { inSampleSize = 2 }

        // Miniatura do ROSTO: primeira posição do carrossel único.
        val imgMiniRosto = findViewById<android.widget.ImageView>(R.id.imgPvRosto)
        val rostoFile = sim.rosto?.arquivoLocal
        if (rostoFile != null && rostoFile.exists()) {
            imgMiniRosto.setImageBitmap(BitmapFactory.decodeFile(rostoFile.absolutePath, opt))
        } else {
            imgMiniRosto.setImageResource(R.drawable.bg_sem_foto)
        }
        imgMiniRosto.setOnClickListener { irParaBloco(R.id.frameThumbRosto) }
        findViewById<View>(R.id.frameThumbRosto).setOnClickListener { imgMiniRosto.performClick() }

        // Miniatura Etiqueta (clicável → visor grande)
        val etiqFile = sim.etiqueta?.arquivoLocal
        if (etiqFile != null) {
            imgEtiqueta.setImageBitmap(BitmapFactory.decodeFile(etiqFile.absolutePath, opt))
            imgEtiqueta.setOnClickListener { irParaBloco(R.id.frameThumbEtiqueta) }
            findViewById<View>(R.id.frameThumbEtiqueta).setOnClickListener { imgEtiqueta.performClick() }
        } else {
            imgEtiqueta.setImageResource(R.drawable.bg_sem_foto)
            imgEtiqueta.setOnClickListener(null)
        }

        // ITEM 5: carrossel ÚNICO e contínuo — etiqueta, posicionamentos,
        // acessórios e impressos numa sequência só. Rolar atravessa os blocos e
        // apenas o realce da miniatura acompanha; rolar antes do primeiro volta
        // ao RESUMO. `blocoDaPagina` diz a qual miniatura cada página pertence.
        val carrossel = mutableListOf<TreatmentPhotoFetcher.FotoInfo>()
        val rotulosCarrossel = mutableListOf<String>()
        blocoDaPagina.clear()
        sim.rosto?.let {
            carrossel.add(it); rotulosCarrossel.add(getString(R.string.cat_face))
            blocoDaPagina.add(R.id.frameThumbRosto)
        }
        sim.etiqueta?.let {
            carrossel.add(it); rotulosCarrossel.add(getString(R.string.cat_label))
            blocoDaPagina.add(R.id.frameThumbEtiqueta)
        }
        sim.posicionamentos.forEachIndexed { i, f ->
            carrossel.add(f); rotulosCarrossel.add("${getString(R.string.cat_positioning)}.${i + 1}")
            blocoDaPagina.add(R.id.frameThumbPosic)
        }
        sim.acessoriosLista.forEachIndexed { i, f ->
            carrossel.add(f); rotulosCarrossel.add("${getString(R.string.cat_accessories)}.${i + 1}")
            blocoDaPagina.add(R.id.frameThumbPosic)
        }
        sim.documentos.forEachIndexed { i, f ->
            carrossel.add(f); rotulosCarrossel.add("${getString(R.string.docs_viewer_label)}.${i + 1}")
            blocoDaPagina.add(R.id.frameThumbDocs)
        }
        // Primeira página de cada bloco, para o clique na miniatura saltar até lá.
        paginaDoBloco.clear()
        blocoDaPagina.forEachIndexed { i, id -> paginaDoBloco.putIfAbsent(id, i) }

        // Carrossel de IMPRESSOS: documentos escaneados (NÃO entram no PDF)
        val docsLista = sim.documentos

        // Tags com contagem quando há mais de uma foto.
        //
        // A contagem é das fotos DESTA miniatura — posicionamentos mais
        // acessórios — e não de `carrossel`, que é a lista única e inclui
        // rosto, etiqueta e impressos. Com `carrossel.size` a tag dizia
        // "Posicionamento (7)" numa simulação com três posicionamentos.
        //
        // Mesma causa do bloco logo abaixo: quando os três carrosséis viraram
        // uma lista só, `carrossel` deixou de significar "fotos de
        // posicionamento". A IMAGEM da miniatura foi corrigida na época; a
        // contagem, que estava duas linhas acima, passou despercebida.
        val qtdPosic = sim.posicionamentos.size + sim.acessoriosLista.size
        findViewById<TextView>(R.id.txtTagPosic).text =
            getString(R.string.cat_positioning) +
                (if (qtdPosic > 1) " ($qtdPosic)" else "")
        findViewById<TextView>(R.id.txtTagDocs).text =
            getString(R.string.docs_viewer_label) +
                (if (docsLista.size > 1) " (${docsLista.size})" else "")

        // Miniatura Posic.&Acess. = primeira foto DESSA categoria. Antes usava
        // carrossel.firstOrNull(), que passou a ser a etiqueta quando o
        // carrossel virou lista única — a miniatura mostrava a foto errada.
        val primeiraCarrossel = (sim.posicionamentos.firstOrNull()
            ?: sim.acessoriosLista.firstOrNull())?.arquivoLocal
        if (primeiraCarrossel != null) {
            imgPosic.setImageBitmap(BitmapFactory.decodeFile(primeiraCarrossel.absolutePath, opt))
        } else {
            imgPosic.setImageResource(R.drawable.bg_sem_foto)
        }
        imgPosic.setOnClickListener { irParaBloco(R.id.frameThumbPosic) }
        findViewById<View>(R.id.frameThumbPosic).setOnClickListener { imgPosic.performClick() }

        // Miniatura IMPRESSOS: só aparece se houver documentos escaneados
        if (docsLista.isNotEmpty()) {
            frameDocs.visibility = View.VISIBLE
            imgDocs.setImageBitmap(
                BitmapFactory.decodeFile(docsLista.first().arquivoLocal.absolutePath, opt))
            imgDocs.setOnClickListener { irParaBloco(R.id.frameThumbDocs) }
            findViewById<View>(R.id.frameThumbDocs).setOnClickListener { imgDocs.performClick() }
        } else {
            frameDocs.visibility = View.GONE
            imgDocs.setOnClickListener(null)
        }

        // Callback único de página: usa a lista de rótulos ATUAL (principal ou impressos)
        if (!callbackRegistrado) {
            callbackRegistrado = true
            // Puxar para a direita na PRIMEIRA página (antes da etiqueta/rosto)
            // devolve o visor ao RESUMO — a "landing page" do carrossel.
            viewPagerCarrossel.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                private var arrastando = false
                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING) arrastando = true
                    else if (state == ViewPager2.SCROLL_STATE_IDLE) arrastando = false
                }
                override fun onPageScrolled(position: Int, offset: Float, offsetPx: Int) {
                    if (arrastando && position == 0 && offsetPx == 0 &&
                        viewPagerCarrossel.visibility == View.VISIBLE) {
                        simulacaoAtual?.let { arrastando = false; mostrarResumo(it) }
                    }
                }
                override fun onPageSelected(position: Int) {
                    paginaCarrossel = position
                    // Realce acompanha o bloco a que a página pertence.
                    blocoDaPagina.getOrNull(position)?.let { marcarBlocoSelecionado(it) }
                    if (viewPagerCarrossel.visibility == View.VISIBLE) {
                        txtViewerPaciente.text = montarIdentificacao()
                            ?: getString(R.string.cat_positioning)
                    }
                }
            })
        }

        findViewById<View>(R.id.frameThumbResumo).setOnClickListener { mostrarResumo(sim) }

        // Restaura o bloco/página de antes da rotação; na primeira abertura o
        // padrão é o RESUMO (landing page do carrossel).
        fotosUnificadas = carrossel
        rotulosUnificados = rotulosCarrossel
        if (blocoAtual == BLOCO_CARROSSEL && carrossel.isNotEmpty()) {
            aplicarCarrossel(carrossel, rotulosCarrossel)
            val pag = paginaCarrossel.coerceIn(0, carrossel.size - 1)
            viewPagerCarrossel.setCurrentItem(pag, false)
            blocoDaPagina.getOrNull(pag)?.let { marcarBlocoSelecionado(it) }
        } else {
            mostrarResumo(sim)
        }
    }

    /**
     * RESUMO — conferência à beira do acelerador (design Claude Design).
     * Rail de identificação + rosto grande com miniaturas empilhadas e as
     * observações abaixo. Abre por padrão ao entrar no carrossel.
     */
    private fun mostrarResumo(sim: TreatmentPhotoFetcher.Simulacao) {
        blocoAtual = BLOCO_RESUMO
        val painel = findViewById<View>(R.id.layoutPvResumo)
        viewPagerCarrossel.visibility = View.GONE
        imgVisorUnico.visibility = View.GONE
        painel.visibility = View.VISIBLE
        ajustarOrientacaoResumo()
        marcarBlocoSelecionado(R.id.frameThumbResumo)

        val dados = patientCache.obterDadosPaciente(nomePaciente, prontuario)
        findViewById<TextView>(R.id.txtResumoNome).text = nomePaciente.uppercase()
        val ids = mutableListOf<String>()
        (prontuario.takeIf { it.isNotBlank() } ?: dados?.prontuario)
            ?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        dados?.nascimento?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        ids.add(getString(R.string.resumo_sim_numero, sim.numeroSimulacao))
        findViewById<TextView>(R.id.txtResumoIds).text = ids.joinToString(" · ")

        // ---- Rosto em destaque: toque abre o carrossel na página dele ----
        val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
        val imgR = findViewById<android.widget.ImageView>(R.id.imgResumoRosto)
        val rosto = sim.rosto?.arquivoLocal?.takeIf { it.exists() }
        if (rosto != null) {
            imgR.setImageBitmap(BitmapFactory.decodeFile(rosto.absolutePath, opt))
            imgR.setOnClickListener { irParaBloco(R.id.frameThumbRosto) }
        } else {
            imgR.setImageResource(R.drawable.bg_sem_foto)
            imgR.setOnClickListener(null)
        }
        montarMiniaturas(sim, opt)
        // A identificação já aparece no rail à esquerda; repeti-la na barra
        // inferior era redundante. No RESUMO a barra passa a mostrar a
        // observação (preenchida quando a leitura do Time-Out terminar).
        txtViewerPaciente.text = ""

        // Leitura em IO (varre pastas) e só então preenche a tela. Feita na
        // thread principal, travava o carrossel; e sem plano B, quando o
        // Time-Out não era encontrado o resumo saía todo "não informado".
        CoroutineScope(Dispatchers.Main).launch {
            val (reg, obs) = withContext(Dispatchers.IO) { lerTimeOutEObs(sim) }
            preencherResumoTimeOut(reg, obs, dados)
        }
    }

    /** Lê Time-Out + observações da simulação (IO). */
    private fun lerTimeOutEObs(sim: TreatmentPhotoFetcher.Simulacao):
            Pair<com.radioterapia.ai.util.TimeOutStore.Registro?, String> {
        val pasta = try {
            com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
                this, nomePaciente, sim.numeroSimulacao, sim.nomePastaCompleto, prontuario)
        } catch (_: Exception) { null }
        var reg = try {
            pasta?.let { com.radioterapia.ai.util.TimeOutStore.ler(it, sim.numeroSimulacao) }
        } catch (_: Exception) { null }
        var obs = try {
            pasta?.let { com.radioterapia.ai.util.ObsStore.ler(it, sim.numeroSimulacao) } ?: ""
        } catch (_: Exception) { "" }

        // Plano B: se a pasta resolvida não tinha os registros (nome no servidor
        // diferente do local, pasta renomeada), tenta a pasta REAL das fotos —
        // é onde o Time-Out e as observações foram gravados de fato.
        if (reg == null || obs.isBlank()) {
            val pastaFoto = sim.rosto?.arquivoLocal?.parentFile
                ?: sim.posicionamentos.firstOrNull()?.arquivoLocal?.parentFile
                ?: sim.etiqueta?.arquivoLocal?.parentFile
            if (pastaFoto != null && pastaFoto.absolutePath != pasta?.absolutePath) {
                if (reg == null) reg = try {
                    com.radioterapia.ai.util.TimeOutStore.ler(pastaFoto, sim.numeroSimulacao)
                } catch (_: Exception) { null }
                if (obs.isBlank()) obs = try {
                    com.radioterapia.ai.util.ObsStore.ler(pastaFoto, sim.numeroSimulacao)
                } catch (_: Exception) { "" }
            }
        }
        return reg to obs
    }

    /**
     * Preenche alertas, equipamento, sítio, médico e observações. Quando o
     * Time-Out não traz médico ou equipamento, cai para o CADASTRO do paciente
     * — que guarda os habituais — em vez de exibir "não informado".
     */
    private fun preencherResumoTimeOut(
        reg: com.radioterapia.ai.util.TimeOutStore.Registro?,
        obs: String,
        dados: com.radioterapia.ai.patient.PatientCache.DadosPaciente?
    ) {
        val boxObs = findViewById<View>(R.id.boxResumoObs)
        if (obs.isBlank()) {
            boxObs.visibility = View.GONE
            txtViewerPaciente.text = ""
        } else {
            findViewById<TextView>(R.id.txtResumoObs).text = obs
            boxObs.visibility = View.VISIBLE
            // Barra inferior do RESUMO: observação em vez da identificação.
            txtViewerPaciente.text = getString(R.string.resumo_obs_tag) + ": " + obs
        }

        // ---- Pílulas de alerta (mesmas cores da ficha de Time-Out) ----
        val box = findViewById<android.widget.LinearLayout>(R.id.boxResumoAlertas)
        box.removeAllViews()
        if (reg?.precaucaoContato == true)
            box.addView(criarPilula(getString(R.string.resumo_alerta_contato),
                R.drawable.pill_alert_orange, 0xFFFFFFFF.toInt()))
        if (reg?.alergia == "SIM")
            box.addView(criarPilula(getString(R.string.resumo_alerta_alergia),
                R.drawable.pill_alert_red, 0xFFFFFFFF.toInt()))
        if (reg?.riscoQueda == true)
            box.addView(criarPilula(getString(R.string.resumo_alerta_queda),
                R.drawable.pill_alert_yellow, 0xFF1A1A1A.toInt()))

        val naoInf = getString(R.string.resumo_sem_dados)
        findViewById<TextView>(R.id.txtResumoEquip).text =
            reg?.equipamento?.takeIf { it.isNotBlank() }
                ?: dados?.equipamento?.takeIf { it.isNotBlank() } ?: naoInf
        findViewById<TextView>(R.id.txtResumoSitio).text =
            reg?.sitio?.takeIf { it.isNotBlank() } ?: naoInf
        findViewById<TextView>(R.id.txtResumoMedico).text =
            reg?.medico?.takeIf { it.isNotBlank() }
                ?: dados?.medicoAssistente?.takeIf { it.isNotBlank() } ?: naoInf

    }

    /** Pílula de alerta 32dp, arredondada, com ⚠ e texto em caixa alta. */
    private fun criarPilula(texto: String, fundo: Int, cor: Int): View {
        val ll = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(fundo)
            setPadding(dpPx(14), 0, dpPx(14), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dpPx(32)
            ).apply { bottomMargin = dpPx(7) }
        }
        ll.addView(TextView(this).apply {
            text = "⚠"; textSize = 15f; setTextColor(cor)
        })
        ll.addView(TextView(this).apply {
            text = texto
            textSize = 12f
            setTextColor(cor)
            letterSpacing = 0.05f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpPx(7) }
        })
        return ll
    }

    /**
     * Pilha de miniaturas ao lado do rosto: posicionamentos + acessórios.
     * Até 4 aparecem; havendo mais, a última casa vira "+N mais…" e abre o
     * carrossel completo. Sem contorno de seleção (só a barra inferior tem).
     */
    private fun montarMiniaturas(sim: TreatmentPhotoFetcher.Simulacao,
                                 opt: BitmapFactory.Options) {
        val stack = findViewById<android.widget.LinearLayout>(R.id.stackResumoMiniaturas)
        stack.removeAllViews()
        val fotos = mutableListOf<TreatmentPhotoFetcher.FotoInfo>()
        fotos.addAll(sim.posicionamentos); fotos.addAll(sim.acessoriosLista)
        if (fotos.isEmpty()) { stack.visibility = View.GONE; return }
        stack.visibility = View.VISIBLE

        val rotulos = mutableListOf<String>()
        // TAG DE RECURSO, em caixa alta pela propria string quando o idioma tem
        // caixa. "POSIC." e "ACESSÓRIO" eram literais em portugues, visiveis em
        // toda abertura de paciente, em qualquer idioma.
        val tagPos = getString(R.string.cat_positioning).uppercase()
        val tagAce = getString(R.string.cat_accessories).uppercase()
        sim.posicionamentos.forEachIndexed { i, _ -> rotulos.add("$tagPos ${i + 1}") }
        sim.acessoriosLista.forEachIndexed { i, _ ->
            rotulos.add(if (sim.acessoriosLista.size > 1) "$tagAce ${i + 1}" else tagAce) }

        val cabem = if (fotos.size <= 4) fotos.size else 3
        for (i in 0 until cabem) {
            val fl = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpPx(87)
                ).apply { bottomMargin = dpPx(6) }
                setBackgroundColor(0xFF0B132C.toInt())
            }
            fl.addView(android.widget.ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(
                    fotos[i].arquivoLocal.absolutePath, opt))
            })
            fl.addView(TextView(this).apply {
                text = rotulos.getOrNull(i) ?: ""
                textSize = 10f
                setTextColor(0xFFFFFFFF.toInt())
                letterSpacing = 0.08f
                setBackgroundResource(R.drawable.bg_tag_foto)
                setPadding(dpPx(6), dpPx(3), dpPx(6), dpPx(3))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.START)
            })
            val idx = i
            fl.setOnClickListener { abrirCarrosselNaPosicao(idx) }
            stack.addView(fl)
        }
        if (fotos.size > 4) {
            stack.addView(TextView(this).apply {
                text = getString(R.string.resumo_mais, fotos.size - 3)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFB8C5D6.toInt())
                setBackgroundColor(0xFF0B132C.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpPx(87))
                setOnClickListener { abrirCarrosselNaPosicao(3) }
            })
        }
    }

    /** Abre o carrossel único já na foto de posicionamento tocada no resumo. */
    private fun abrirCarrosselNaPosicao(pos: Int) {
        val base = paginaDoBloco[R.id.frameThumbPosic] ?: 0
        irParaPagina(base + pos)
    }

    /** Salta para a primeira página do bloco pedido. */
    private fun irParaBloco(idBloco: Int) {
        irParaPagina(paginaDoBloco[idBloco] ?: return)
    }

    /** Mostra o carrossel único na página indicada. */
    private fun irParaPagina(pagina: Int) {
        if (fotosUnificadas.isEmpty()) return
        if (viewPagerCarrossel.visibility != View.VISIBLE)
            aplicarCarrossel(fotosUnificadas, rotulosUnificados)
        val alvo = pagina.coerceIn(0, fotosUnificadas.size - 1)
        viewPagerCarrossel.setCurrentItem(alvo, false)
        blocoDaPagina.getOrNull(alvo)?.let { marcarBlocoSelecionado(it) }
    }

    /**
     * Rail ao lado no tablet deitado; empilhado no celular em pé. Uma fonte
     * única de layout para as duas orientações (sem layout-land paralelo).
     */
    private fun ajustarOrientacaoResumo() {
        val deitado = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val cont = findViewById<android.widget.LinearLayout>(R.id.layoutPvResumo)
        val rail = findViewById<View>(R.id.scrollResumoRail)
        val fotos = findViewById<View>(R.id.boxResumoFotos)
        if (deitado) {
            cont.orientation = android.widget.LinearLayout.HORIZONTAL
            rail.layoutParams = android.widget.LinearLayout.LayoutParams(
                dpPx(264), android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
            fotos.layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            cont.orientation = android.widget.LinearLayout.VERTICAL
            rail.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            fotos.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
    }

    /**
     * Realce do bloco ativo. O contorno vai no FOREGROUND: como background ele
     * ficava atrás da miniatura (que ocupa todo o frame) e não aparecia — por
     * isso só o bloco Resumo, cujo ícone é pequeno, parecia funcionar.
     */
    private fun marcarBlocoSelecionado(idSelecionado: Int) {
        for (id in intArrayOf(R.id.frameThumbResumo, R.id.frameThumbRosto,
                              R.id.frameThumbEtiqueta, R.id.frameThumbPosic,
                              R.id.frameThumbDocs)) {
            val v = findViewById<android.widget.FrameLayout>(id) ?: continue
            v.setBackgroundResource(
                if (id == idSelecionado) R.drawable.bg_bloco_selecionado
                else R.drawable.bg_bloco_normal)
            v.foreground = if (id == idSelecionado)
                androidx.core.content.ContextCompat.getDrawable(
                    this, R.drawable.fg_bloco_selecionado) else null
        }
    }

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** Troca o conteúdo do visor grande para a lista dada (principal ou impressos). */    /** Troca o conteúdo do visor grande para a lista dada (principal ou impressos). */
    private fun aplicarCarrossel(fotos: List<TreatmentPhotoFetcher.FotoInfo>, rotulos: List<String>) {
        findViewById<View>(R.id.layoutPvResumo).visibility = View.GONE
        blocoAtual = BLOCO_CARROSSEL
        rotulosAtuais = rotulos
        mostrarCarrossel()
        viewPagerCarrossel.adapter = CarrosselAdapter(fotos, rotulos)
        viewPagerCarrossel.setCurrentItem(0, false)
    }


    private fun mostrarCarrossel() {
        imgVisorUnico.visibility = View.GONE
        viewPagerCarrossel.visibility = View.VISIBLE
    }

    /** Texto de identificação do paciente para a faixa inferior (nome + prontuário). */
    private fun montarIdentificacao(): String {
        // Busca dados completos do cache (nascimento/prontuário) para a faixa inferior.
        //
        // COM O PRONTUÁRIO, e o campo `patientCache` que já existe.
        //
        // Sem o prontuário, obterDadosPaciente cai na heurística do "registro
        // mais completo" e pode devolver o HOMÔNIMO — justamente nesta faixa,
        // que existe para confirmar que a foto é do paciente certo. É a mesma
        // família do bug de cadastro que a JORNADA descreve, cuja consequência
        // registrada foi a data de nascimento da paciente errada. A linha 394
        // deste mesmo arquivo já passava o prontuário; esta ficou para trás, e
        // ainda construía um PatientCache novo — que relê e reparseia o JSON
        // inteiro do cadastro — a cada página virada do carrossel.
        val dados = try { patientCache.obterDadosPaciente(nomePaciente, prontuario) } catch (_: Exception) { null }
        val idioma = com.radioterapia.ai.i18n.LocaleManager.obterIdiomaAtual(this)
        val nascRaw = dados?.nascimento?.takeIf { it.isNotBlank() }
        val nasc = nascRaw?.let { com.radioterapia.ai.util.DateUtils.formatarNascimento(it, idioma) }
        val pront = (prontuario.takeIf { it.isNotBlank() } ?: dados?.prontuario)?.takeIf { it.isNotBlank() }
        val partes = mutableListOf(nomePaciente)
        if (nasc != null) partes.add(nasc)
        if (pront != null) partes.add(pront)
        return partes.joinToString("  •  ")
    }

    /** Editar SÓ a simulação (Time-Out + observações) da simulação exibida. */
    private fun abrirEditarSimulacao() {
        val sim = simulacaoAtual ?: return
        val it = Intent(this, com.radioterapia.ai.ui.EditarSimulacaoActivity::class.java)
        it.putExtra(com.radioterapia.ai.ui.EditarSimulacaoActivity.EXTRA_NOME, nomePaciente)
        it.putExtra(com.radioterapia.ai.ui.EditarSimulacaoActivity.EXTRA_PRONTUARIO, prontuario)
        it.putExtra(com.radioterapia.ai.ui.EditarSimulacaoActivity.EXTRA_PASTA, sim.nomePastaCompleto)
        it.putExtra(com.radioterapia.ai.ui.EditarSimulacaoActivity.EXTRA_NUM_SIMULACAO, sim.numeroSimulacao)
        startActivity(it)
    }

    private fun abrirAdicionarFoto() {
        val sim = simulacaoAtual ?: return
        val intent = Intent(this, AddPhotoInTreatmentActivity::class.java)
        intent.putExtra(AddPhotoInTreatmentActivity.EXTRA_NOME, nomePaciente)
        intent.putExtra(AddPhotoInTreatmentActivity.EXTRA_PRONTUARIO, prontuario)
        intent.putExtra(AddPhotoInTreatmentActivity.EXTRA_PASTA, sim.nomePastaCompleto)
        intent.putExtra(AddPhotoInTreatmentActivity.EXTRA_NUM_SIMULACAO, sim.numeroSimulacao)
        startActivity(intent)
    }

    /** Abre o PDF atual da simulação no leitor padrão (via FileProvider). */
    /**
     * Popup de ações do paciente — agrupado por intenção (Documentos, Editar) e
     * com a ação destrutiva separada e em vermelho, nas cores do app.
     */
    private fun abrirPopupAcoes() {
        val view = layoutInflater.inflate(R.layout.dialog_acoes_paciente, null)
        val dlg = AlertDialog.Builder(this)
            .setTitle(nomePaciente)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun liga(id: Int, acao: () -> Unit) {
            view.findViewById<View>(id).setOnClickListener { dlg.dismiss(); acao() }
        }
        fun comPdf(acao: (java.io.File) -> Unit): () -> Unit = {
            pdfAtualCarrossel?.let(acao) ?: android.widget.Toast.makeText(
                this, R.string.pdf_not_found, android.widget.Toast.LENGTH_SHORT).show()
        }
        liga(R.id.popVerPdf, comPdf { abrirPdf(it) })
        liga(R.id.popImprimir, comPdf { escolherModoImpressao(it) })
        liga(R.id.popImprimirPaginas, comPdf { escolherPaginasEImprimir(it) })
        liga(R.id.popCompartilhar, comPdf { compartilharPdf(it) })
        liga(R.id.popExportarZip) { exportarSimulacaoZip() }
        liga(R.id.popEditarCadastro) {
            val it2 = Intent(this, com.radioterapia.ai.ui.EditarPacienteActivity::class.java)
            it2.putExtra(com.radioterapia.ai.ui.EditarPacienteActivity.EXTRA_NOME, nomePaciente)
            startActivity(it2)
        }
        liga(R.id.popEditarSimulacao) { abrirEditarSimulacao() }
        liga(R.id.popAddFotos) { abrirAdicionarFoto() }
        liga(R.id.popResimular) { resimularPaciente() }
        dlg.show()
    }

    /**
     * Exporta a simulação inteira num `.zip` e abre o diálogo do pen-drive.
     *
     * Fica aqui, no ⋮ do carrossel, junto de "Ver ficha", "Imprimir" e
     * "Compartilhar" — não no menu principal. Exportar é uma ação SOBRE a
     * simulação que está na tela, e o técnico já chegou aqui para olhá-la; um
     * item no menu principal exigiria escolher o paciente de novo.
     *
     * A compactação roda em IO e o diálogo do pen-drive só abre depois de o
     * arquivo existir: abrir antes mostraria "gravar" habilitado para um zip
     * que ainda não terminou de ser escrito.
     */
    private fun exportarSimulacaoZip() {
        val sim = simulacaoAtual ?: return
        val aviso = android.widget.Toast.makeText(
            this, R.string.export_zip_progress, android.widget.Toast.LENGTH_SHORT)
        aviso.show()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.radioterapia.ai.export.SimulacaoZipExporter.exportar(
                    this@PatientViewerActivity, nomePaciente, prontuario,
                    sim.nomePastaCompleto, sim.numeroSimulacao)
            }
            if (isFinishing || isDestroyed) return@launch
            aviso.cancel()
            val zip = res.arquivo
            if (!res.ok || zip == null) {
                android.widget.Toast.makeText(this@PatientViewerActivity,
                    if (res.qtdArquivos == 0) R.string.export_zip_empty else R.string.export_zip_fail,
                    android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            android.widget.Toast.makeText(this@PatientViewerActivity,
                getString(R.string.export_zip_ok, zip.name),
                android.widget.Toast.LENGTH_SHORT).show()
            // Mesmo diálogo animado do cabo OTG usado pela impressão em pen-drive.
            abrirDialogoPenDrive(listOf(zip))
        }
    }

    /** Compartilha o PDF via chooser do Android (WhatsApp, Gmail, Drive, etc.). */
    private fun compartilharPdf(pdf: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", pdf)
            val envio = Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(envio, getString(R.string.share_pdf)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, R.string.pdf_not_found,
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirPdf(pdf: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${applicationContext.packageName}.fileprovider", pdf)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.hc_pdf_open_fail), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Resimular: inicia uma simulação nova (do zero) para o paciente existente,
     * abrindo o fluxo de captura da simulação (MainActivity) com os dados do paciente
     * já preenchidos. Atende ao fluxo "Sim" sem precisar de tela separada.
     */
    private fun resimularPaciente() {
        val intent = Intent(this, com.radioterapia.ai.MainActivity::class.java).apply {
            putExtra("paciente_reusar", nomePaciente)
            putExtra("prontuario_reusar", prontuario)
            // data de nascimento não está no viewer; o MainActivity completa do cache se houver
        }
        startActivity(intent)
    }

    companion object {
        /** Blocos do visor (preservados na rotação). */
        const val BLOCO_RESUMO = 0
        const val BLOCO_FOTO = 1
        const val BLOCO_CARROSSEL = 2
        private const val ST_BLOCO = "st_bloco"
        private const val ST_PAGINA = "st_pagina"

        const val EXTRA_NOME = "nome"
        const val EXTRA_PRONTUARIO = "prontuario"
    }
}
