package com.radioterapia.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.AspectRatio
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radioterapia.ai.exif.ExifWatermark
import com.radioterapia.ai.patient.PatientCache
import com.radioterapia.ai.patient.SuspectNameValidator
import com.radioterapia.ai.quality.PhotoQualityDetector
import com.radioterapia.ai.scan.EtiquetaParser
import com.radioterapia.ai.scan.ScanPacienteActivity
import com.radioterapia.ai.session.SessionManager
import com.radioterapia.ai.session.SessionManager.Category
import com.radioterapia.ai.ui.FinalizarActivity
import com.radioterapia.ai.ui.ThumbAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tela principal de captura — versão v7.
 *
 *  - 4 abas categorizadas no rodapé: ROSTO / ETIQUETA / POSICIONAMENTO / ACESSÓRIOS
 *  - Categoria ativa muda automaticamente ao avançar (rosto → etiqueta → posicionamento → acessórios)
 *  - Técnico pode tocar em outra aba para refazer
 *  - Pinch-to-zoom + slider
 *  - Flash 3 modos: Auto / On / Off
 *  - Grid (regra dos terços) toggle das configurações
 *  - Faixa horizontal de thumbnails sempre visível (todas as fotos da sessão)
 *  - Marca d'água visível aplicada apenas em POSICIONAMENTO
 *  - Indicador leve de qualidade (borrada/escura)
 *
 *  Não recriaria a Activity em caso de rotação (configChanges).
 */
class MainActivity : BaseActivity() {

    /** UI cheia de câmera - sem toolbar da BaseActivity. */
    override fun mostrarToolbar(): Boolean = false

    /** Estado: clique da câmera mudo? (item 9) */
    private var cliqueMudo: Boolean = false
    private var usarCameraFrontal = false

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private val shutterSound = MediaActionSound()

    private lateinit var config: AppConfig
    private lateinit var sessionManager: SessionManager
    private lateinit var patientCache: PatientCache

    // Views
    private lateinit var viewFinder: PreviewView
    private lateinit var imgPreview: ImageView
    private lateinit var cropPreview: com.radioterapia.ai.crop.CropImageView
    private var molduraRecorte: com.radioterapia.ai.camera.MolduraRecorteView? = null
    private var avisoEncaixar: View? = null
    private var imgPincaAviso: android.widget.ImageView? = null
    private lateinit var btnCapturar: ImageButton
    private lateinit var btnSalvar: Button
    private lateinit var btnDescartar: Button
    private lateinit var btnGirarVisor: android.widget.ImageButton
    private lateinit var cropLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
    private lateinit var btnFinalizar: Button
    private lateinit var btnVoltarLanding: Button
    private lateinit var btnGaleria: Button
    private lateinit var layoutAcoesPosFoto: LinearLayout
    private lateinit var barraNavegacao: LinearLayout
    private lateinit var layoutControlesCamera: LinearLayout
    private lateinit var holderCapturar: FrameLayout
    private lateinit var recyclerThumbs: RecyclerView
    private lateinit var seekZoom: SeekBar
    private lateinit var btnFlash: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var txtFlashLabel: TextView
    private lateinit var txtMuteLabel: TextView
    private lateinit var gridOverlay: View
    private lateinit var txtWifiStatus: TextView
    private lateinit var txtSessaoStatus: TextView
    private lateinit var txtCategoriaAtiva: TextView
    private lateinit var txtPacienteAtivo: TextView
    private lateinit var txtIndicadorQualidade: TextView
    private lateinit var tabsCategoria: LinearLayout

    private lateinit var thumbAdapter: ThumbAdapter

    private var arquivoTemporario: File? = null
    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF

    private lateinit var scanLauncher: ActivityResultLauncher<Intent>
    private var modoScanAtual: String = ScanPacienteActivity.MODO_OCR

    // ----- Lifecycle -----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = AppConfig(this)
        sessionManager = SessionManager(this)
        patientCache = PatientCache(this)

        registrarVoltar()
        verificarAcessoArmazenamento()
        bindViews()
        configurarRecyclerThumbs()
        configurarScanLauncher()
        configurarTabs()
        configurarZoomEFlash()
        configurarGrid()
        configurarMudoClique()

        cameraExecutor = Executors.newSingleThreadExecutor()
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        btnCapturar.setOnClickListener { tirarFoto() }
        btnSalvar.setOnClickListener { salvarParaSessao() }
        btnDescartar.setOnClickListener { descartarFoto() }
        btnFinalizar.setOnClickListener { acaoFinalizar() }
        btnGaleria.setOnClickListener { abrirGaleria() }
        btnVoltarLanding.setOnClickListener {
            // Com paciente identificado, voltar abre a IDENTIFICAÇÃO para revisão/edição;
            // um novo getString(R.string.hc_back) a partir dela retorna à home (com a simulação em andamento).
            if (sessionManager.nomePaciente.isNotBlank()) abrirDialogEditarIdentificacao()
            else voltarParaLanding()
        }

        viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = viewFinder.meteringPointFactory
                val ponto = factory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(ponto).build()
                )
                true
            } else false
        }

        val novaSimulacao = intent.getBooleanExtra(SimulationHomeActivity.EXTRA_NOVA_SIMULACAO, true)

        // "Adicionar mais fotos": continua a MESMA simulação, com a sessão já
        // reconstruída da pasta (fotos existentes carregadas). Volta ao modo de fotos.
        if (tratarContinuarSimulacao(intent)) return

        // Paciente reusado (vindo do Histórico/Resimular): pula a identificação e já
        // inicia a sessão com os dados dele preenchidos.
        val pacienteReusar = intent.getStringExtra("paciente_reusar")
        if (!pacienteReusar.isNullOrBlank()) {
            val prontReusar = intent.getStringExtra("prontuario_reusar") ?: ""
            var nascReusar = intent.getStringExtra("nascimento_reusar") ?: ""
            // Se não veio nascimento mas o cache tem, completa
            if (nascReusar.isBlank()) {
                nascReusar = patientCache.obterDadosPaciente(pacienteReusar)?.nascimento ?: ""
            }
            sessionManager.idSimulacao = UUID.randomUUID().toString().take(8)
            sessionManager.marcarInicio()
            atualizarCategoriaUI()
            atualizarStatusUI()
            txtPacienteAtivo.post {
                finalizarIdentificacao(pacienteReusar, prontReusar, nascReusar)
                copiarBaseDaSimulacaoAnterior(pacienteReusar)
            }
            return
        }

        if (!novaSimulacao && sessionManager.temRascunho()) {
            restaurarRascunho()
        } else {
            sessionManager.idSimulacao = UUID.randomUUID().toString().take(8)
            sessionManager.marcarInicio()
            atualizarCategoriaUI()
            atualizarStatusUI()
            txtPacienteAtivo.post { abrirDialogIdentificarPaciente() }
        }
    }

    override fun onResume() {
        super.onResume()
        atualizarStatusUI()
        atualizarCategoriaUI()
        com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
        // A câmera de scan (ScanPacienteActivity) e outras telas chamam unbindAll()
        // no ProcessCameraProvider (singleton), o que desliga o preview desta tela.
        // Re-vinculamos sempre que voltamos ao primeiro plano e não há foto em
        // pré-visualização (decisão salvar/descartar), evitando a "tela escura".
        if (allPermissionsGranted() && imgPreview.visibility != View.VISIBLE) {
            startCamera()
        }
    }

    override fun onSupportNavigateUp(): Boolean { voltarParaLanding(); return true }

    /**
     * Botão "voltar" do sistema pelo OnBackPressedDispatcher.
     *
     * Antes era um `override fun onBackPressed()` sem chamar o super, com
     * `@Suppress("MissingSuperCall")` em cima. O supressor era necessário
     * porque `super.onBackPressed()` encerraria a Activity na hora, descartando
     * o rascunho de fotos sem aviso — a perda que a confirmação existe para
     * impedir. Só que suprimir um aviso do lint não é o mesmo que resolver: a
     * API está obsoleta e, em Android 13+, o gesto de voltar previsto
     * (predictive back) não funciona com o override.
     *
     * Com o dispatcher a intenção fica explícita: enquanto este callback estiver
     * habilitado, o "voltar" é NOSSO — ninguém precisa lembrar de não chamar o
     * super, porque não há super para chamar.
     */
    // ============= ADICIONAR DA GALERIA =============

    /**
     * Importa fotos que ja estao no aparelho.
     *
     * O caso real: o tecnico fotografou com o proprio celular enquanto o
     * paciente estava na sala, ou percebeu depois que faltou uma foto e o
     * paciente ja tinha ido embora. Sem isto a unica saida era refazer — e com
     * o paciente ausente nao ha como.
     *
     * A foto entra na CATEGORIA ATIVA, a mesma que a captura usaria. Perguntar
     * a categoria a cada importacao custaria um passo extra, e a barra de
     * categorias ja esta na tela dizendo qual esta selecionada.
     *
     * PASSA pelo recorte 16:9, como a foto da camera. A primeira versao entrava
     * crua, e a ficha impressa saia com foto em pe no meio de fotos deitadas.
     * O quadro cheio e preservado no "_ORIGINAL" antes do recorte, igual a
     * captura, para o reenquadramento posterior partir dele.
     */
    private val escolherDaGaleria = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> aoEscolherDaGaleria(uris.orEmpty()) }

    /**
     * De ONDE a foto vem. Tres origens, porque sao tres situacoes diferentes.
     *
     * ROLO: o tecnico fotografou com o proprio celular ou tablet e a foto esta
     * na galeria — o caso mais comum, e por isso o primeiro.
     *
     * ARQUIVADAS: a foto ja foi deste paciente e saiu da ficha ao ser
     * substituida. Sem esta porta, arquivar seria indistinguivel de apagar para
     * quem usa o app.
     *
     * PROCURAR: qualquer outra pasta do aparelho, pen-drive incluso. E o
     * gerenciador de arquivos do sistema, para o caso que as duas primeiras nao
     * cobrem — foto vinda por mensagem, pasta compartilhada, cartao.
     */
    private fun abrirGaleria() {
        val opcoes = arrayOf(
            getString(R.string.origem_rolo),
            getString(R.string.origem_arquivadas),
            getString(R.string.origem_procurar))
        AlertDialog.Builder(this)
            .setTitle(R.string.origem_titulo)
            .setItems(opcoes) { _, i ->
                when (i) {
                    0 -> abrirRoloDaCamera()
                    1 -> abrirArquivadas()
                    else -> abrirProcurar()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Seletor de imagens do sistema (galeria/rolo). */
    private fun abrirRoloDaCamera() {
        try {
            val i = Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            escolherDoRolo.launch(i)
        } catch (_: Exception) {
            // Aparelho sem app de galeria: o gerenciador de arquivos resolve.
            abrirProcurar()
        }
    }

    /** Gerenciador de arquivos do sistema, para qualquer outra pasta. */
    private fun abrirProcurar() {
        try {
            escolherDaGaleria.launch(arrayOf(com.radioterapia.ai.gallery.GaleriaImport.MIME))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.gallery_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private val escolherDoRolo = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val dados = res.data
        val uris = mutableListOf<android.net.Uri>()
        // ACTION_PICK devolve a selecao de DOIS jeitos: clipData quando sao
        // varias, e data quando e uma so. Ler so um dos dois perde metade dos
        // casos, e a foto unica e justamente o caso comum.
        dados?.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
        if (uris.isEmpty()) dados?.data?.let { uris.add(it) }
        aoEscolherDaGaleria(uris)
    }

    /**
     * Fotos deste paciente que foram substituidas e sairam da ficha.
     *
     * Procura em DUAS pastas: a de trabalho da sessao (o que foi arquivado
     * agora, com o paciente na sala) e a do paciente (o que foi arquivado em
     * simulacoes anteriores). Quem edita uma simulacao ja gravada pode ter nas
     * duas, e oferecer so uma delas esconderia metade.
     */
    private fun abrirArquivadas() {
        CoroutineScope(Dispatchers.Main).launch {
            val bases = withContext(Dispatchers.IO) { basesDeArquivadas() }
            if (isFinishing || isDestroyed) return@launch
            com.radioterapia.ai.ui.ArquivadasDialog.mostrar(this@MainActivity, bases) { arq ->
                restaurarArquivada(arq)
                true
            }
        }
    }

    private fun basesDeArquivadas(): List<File> {
        val lista = mutableListOf(sessionManager.pastaDeTrabalho())
        try {
            val pasta = sessionManager.editandoNomePasta
            if (pasta.isNotBlank()) {
                lista.add(File(com.radioterapia.ai.util.StorageLocal.photos(this), pasta))
            }
        } catch (_: Exception) {}
        return lista.filter { it.exists() }
    }

    /**
     * Devolve a arquivada ao carrossel, na categoria em que ela estava.
     *
     * A categoria vem do NOME do arquivo, e nao da categoria ativa na tela: uma
     * foto de rosto arquivada tem que voltar como rosto, mesmo que o tecnico
     * esteja com "posicionamento" selecionado quando abre a lista. Errar isso
     * poria a foto do rosto no grid de posicionamento da ficha impressa.
     */
    private fun restaurarArquivada(arq: File) {
        val cat = categoriaDoArquivo(arq.name) ?: sessionManager.categoriaAtiva
        CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) {
                try { sessionManager.adicionarCopiando(arq, cat) != null }
                catch (_: Exception) { false }
            }
            if (isFinishing || isDestroyed) return@launch
            if (ok) {
                atualizarThumbnails(); atualizarStatusUI(); atualizarCategoriaUI()
                Toast.makeText(this@MainActivity, R.string.arq_restaurada,
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.gallery_fail,
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Categoria gravada no nome do arquivo ("..._rosto_ARQ123.jpg" -> FACE). */
    private fun categoriaDoArquivo(nome: String): SessionManager.Category? {
        val n = nome.lowercase()
        return SessionManager.Category.values()
            .firstOrNull { n.contains(it.nomeArquivoBase.lowercase()) }
    }

    /** Fila de fotos importadas esperando o recorte. Ver [proximoRecorteGaleria]. */
    private val filaRecorteGaleria = ArrayDeque<java.io.File>()
    private var categoriaDaImportacao = SessionManager.Category.POSITIONING
    private var importadasComRecorte = 0

    private fun aoEscolherDaGaleria(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.gallery_none_picked, Toast.LENGTH_SHORT).show()
            return
        }
        categoriaDaImportacao = sessionManager.categoriaAtiva
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) {
                com.radioterapia.ai.gallery.GaleriaImport.copiarParaTemp(
                    this@MainActivity, uris, cacheDir, "galeria")
            }
            if (isFinishing || isDestroyed) return@launch
            if (res.falhas > 0) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.gallery_fail, res.falhas),
                    Toast.LENGTH_LONG).show()
            }
            filaRecorteGaleria.clear()
            filaRecorteGaleria.addAll(res.arquivos)
            importadasComRecorte = 0
            proximoRecorteGaleria()
        }
    }

    /**
     * Manda a proxima foto importada para a tela de recorte 16:9.
     *
     * POR QUE UMA FILA: a foto da galeria vem no enquadramento do celular de
     * quem tirou — retrato, quadrado, o que for. Entrando crua, a ficha
     * impressa misturava fotos 16:9 com fotos em pe, e o grid ficava
     * desarmonico. Passando pelo MESMO recorte da camera, toda foto da ficha
     * tem a mesma proporcao.
     *
     * Uma de cada vez porque a selecao e multipla e cada foto precisa do proprio
     * enquadramento — recortar todas com a mesma regra automatica seria adivinhar.
     * A tela de recorte tambem GIRA, que resolve a foto que veio deitada.
     */
    private fun proximoRecorteGaleria() {
        val arq = filaRecorteGaleria.removeFirstOrNull()
        if (arq == null) {
            if (importadasComRecorte > 0) {
                atualizarThumbnails(); atualizarStatusUI(); atualizarCategoriaUI()
                Toast.makeText(this, getString(R.string.gallery_added, importadasComRecorte),
                    Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            // O recorte SOBRESCREVE o arquivo. A copia intacta e tirada antes,
            // senao o quadro cheio deixa de existir no instante do recorte e o
            // reenquadramento posterior nao teria de onde partir.
            preRecorteGaleria = try {
                java.io.File(cacheDir, arq.nameWithoutExtension + "_pre.jpg")
                    .also { arq.copyTo(it, overwrite = true) }
            } catch (_: Exception) { null }
            recortarDaGaleria.launch(
                Intent(this, com.radioterapia.ai.crop.CropActivity::class.java).apply {
                    putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_PATH, arq.absolutePath)
                    putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_ARQUIVO_TMP, arq.absolutePath)
                })
        } catch (_: Exception) {
            // Sem tela de recorte, a foto entra como veio: melhor importada
            // torta que perdida.
            adicionarImportada(arq)
        }
    }

    private var preRecorteGaleria: java.io.File? = null

    private val recortarDaGaleria = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val caminho = res.data?.getStringExtra(
            com.radioterapia.ai.crop.CropActivity.EXTRA_ARQUIVO_TMP)
        if (res.resultCode == android.app.Activity.RESULT_OK && caminho != null) {
            adicionarImportada(java.io.File(caminho))
        } else {
            // Cancelou o recorte desta foto: ela NAO entra, e os temporarios saem.
            caminho?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
            try { preRecorteGaleria?.delete() } catch (_: Exception) {}
            preRecorteGaleria = null
            proximoRecorteGaleria()
        }
    }

    /**
     * Grava a foto recortada na sessao e so entao chama a proxima da fila.
     *
     * A gravacao roda em IO — copia de JPEG na thread principal trava a tela
     * quando o tecnico importa varias fotos de uma vez.
     */
    private fun adicionarImportada(arq: java.io.File) {
        val pre = preRecorteGaleria
        preRecorteGaleria = null
        CoroutineScope(Dispatchers.Main).launch {
            var arquivou = false
            val ok = withContext(Dispatchers.IO) {
                try {
                    val destino = sessionManager.adicionarFoto(arq, categoriaDaImportacao)
                    arquivou = sessionManager.ultimaArquivada != null
                    if (pre != null) sessionManager.guardarOriginal(pre, destino)
                    true
                } catch (_: Exception) {
                    try { pre?.delete() } catch (_: Exception) {}
                    false
                }
            }
            if (ok) importadasComRecorte++
            if (isFinishing || isDestroyed) return@launch
            // Categoria unica: a foto que estava la saiu da ficha. Dizer isso na
            // hora e o que evita o susto de ver a anterior sumir — e o aviso e
            // tambem onde o tecnico descobre que ela da para recuperar.
            if (arquivou) {
                Toast.makeText(this@MainActivity, R.string.arq_substituida,
                    Toast.LENGTH_LONG).show()
            }
            proximoRecorteGaleria()
        }
    }

    private fun registrarVoltar() {
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { voltarParaLanding() }
            })
    }

    /**
     * Liga a moldura do recorte e ensina o enquadramento.
     *
     * O AVISO SO APARECE COM A CAMERA AO VIVO. Mostra-lo durante a revisao da
     * foto ja tirada seria pedir um ajuste que aquela tela nao faz.
     */
    private fun mostrarMolduraDoVisor() {
        molduraRecorte?.visibility = View.VISIBLE
        molduraRecorte?.molduraVisivel = true
        com.radioterapia.ai.ui.anim.Movimento.mostrarAviso(avisoEncaixar, imgPincaAviso)
    }

    private fun esconderMolduraDoVisor() {
        molduraRecorte?.visibility = View.GONE
        com.radioterapia.ai.ui.anim.Movimento.esconderAviso(avisoEncaixar)
    }

    private fun bindViews() {
        viewFinder = findViewById(R.id.viewFinder)
        // WYSIWYG: mostra todo o quadro 16:9 que será capturado (faixas pretas nas
        // laterais se necessário) em vez de preencher cortando as bordas.
        viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER
        imgPreview = findViewById(R.id.imgPreview)
        cropPreview = findViewById(R.id.cropPreview)
        molduraRecorte = findViewById(R.id.molduraRecorte)
        avisoEncaixar = findViewById(R.id.avisoEncaixar)
        imgPincaAviso = findViewById(R.id.imgPincaAviso)
        btnCapturar = findViewById(R.id.btnCapturar)
        btnSalvar = findViewById(R.id.btnSalvar)
        btnDescartar = findViewById(R.id.btnDescartar)
        // Fonte comum por LINHA: tabs de categoria (linha 1) e barra inferior (linha 2)
        com.radioterapia.ai.util.UiText.uniformizar(
            findViewById<View>(R.id.tabFace).findViewById(R.id.tabTitle),
            findViewById<View>(R.id.tabLabel).findViewById(R.id.tabTitle),
            findViewById<View>(R.id.tabPositioning).findViewById(R.id.tabTitle),
            findViewById<View>(R.id.tabAccessories).findViewById(R.id.tabTitle),
            findViewById<View>(R.id.tabDocs).findViewById(R.id.tabTitle))
        com.radioterapia.ai.util.UiText.uniformizar(
            findViewById(R.id.btnVoltarLanding), findViewById(R.id.btnGaleria),
            findViewById(R.id.btnFinalizar),
            findViewById(R.id.btnDescartar), findViewById(R.id.btnSalvar))
        btnGirarVisor = findViewById(R.id.btnGirarVisor)
        btnGirarVisor.setOnClickListener { cropPreview.girar(); seekZoom.progress = 0 }
        // A barrinha de zoom acompanha a pinça do editor pós-captura
        cropPreview.onZoomMudou = { p -> seekZoom.progress = p }
        btnFinalizar = findViewById(R.id.btnFinalizar)
        btnVoltarLanding = findViewById(R.id.btnVoltarLanding)
        btnGaleria = findViewById(R.id.btnGaleria)
        layoutAcoesPosFoto = findViewById(R.id.layoutAcoesPosFoto)
        barraNavegacao = findViewById(R.id.barraNavegacao)
        layoutControlesCamera = findViewById(R.id.layoutControlesCamera)
        holderCapturar = findViewById(R.id.holderCapturar)
        recyclerThumbs = findViewById(R.id.recyclerThumbs)
        seekZoom = findViewById(R.id.seekZoom)
        btnFlash = findViewById(R.id.btnFlash)
        // Trocar câmera (frontal/traseira) — padrão: traseira
        val btnSwitch = findViewById<android.widget.ImageButton>(R.id.btnSwitchCam)
        val txtSwitch = findViewById<TextView>(R.id.txtSwitchCamLabel)
        fun pintarSwitch() {
            btnSwitch.setColorFilter(if (usarCameraFrontal) 0xFFFFD54F.toInt() else 0xFF9E9E9E.toInt())
            txtSwitch.setTextColor(if (usarCameraFrontal) 0xFFFFD54F.toInt() else 0xFFCCCCCC.toInt())
        }
        pintarSwitch()
        btnSwitch.setOnClickListener {
            usarCameraFrontal = !usarCameraFrontal
            pintarSwitch()
            startCamera()
        }
        btnMute = findViewById(R.id.btnMute)
        txtFlashLabel = findViewById(R.id.txtFlashLabel)
        txtMuteLabel = findViewById(R.id.txtMuteLabel)
        gridOverlay = findViewById(R.id.gridOverlay)
        txtWifiStatus = findViewById(R.id.txtWifiStatus)
        txtSessaoStatus = findViewById(R.id.txtSessaoStatus)
        txtCategoriaAtiva = findViewById(R.id.txtCategoriaAtiva)
        txtPacienteAtivo = findViewById(R.id.txtPacienteAtivo)
        txtIndicadorQualidade = findViewById(R.id.txtIndicadorQualidade)
        tabsCategoria = findViewById(R.id.tabsCategoria)
    }

    // ============= TABS DE CATEGORIA =============

    private val tabIds = listOf(
        Category.FACE to R.id.tabFace,
        Category.LABEL to R.id.tabLabel,
        Category.POSITIONING to R.id.tabPositioning,
        Category.ACCESSORIES to R.id.tabAccessories,
        Category.DOCUMENTS to R.id.tabDocs
    )

    private fun configurarTabs() {
        tabIds.forEach { (cat, id) ->
            findViewById<View>(id).setOnClickListener { selecionarCategoria(cat) }
        }
    }

    /** Trata o retorno do "Adicionar mais fotos" (Finalizar → esta tela).
     *  Blindado: se a sessão chegar vazia por qualquer motivo, RECONSTRÓI da
     *  pasta do paciente aqui mesmo (nunca "0 fotos"). */
    private fun tratarContinuarSimulacao(it: Intent): Boolean {
        if (!it.getBooleanExtra(EXTRA_CONTINUAR_SIMULACAO, false)) return false
        sessionManager.recarregar()
        sessionManager.marcarInicio()
        val nome = sessionManager.nomePaciente
        val num = sessionManager.editandoNumSim.coerceAtLeast(1)
        atualizarBannerPaciente(nome, num)
        atualizarCategoriaUI()
        atualizarStatusUI()
        if (sessionManager.quantidade() == 0 &&
            sessionManager.editandoNomePasta.isNotBlank() && nome.isNotBlank()) {
            reconstruirSessaoDaPasta(nome, sessionManager.editandoNomePasta)
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tratarContinuarSimulacao(intent)
    }

    /** Reconstrução de emergência: repovoa a sessão com as fotos da pasta. */
    private fun reconstruirSessaoDaPasta(nome: String, nomePasta: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val sim = withContext(Dispatchers.IO) {
                    val sims = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this@MainActivity)
                        .buscarSimulacoes(nome)
                    // Pasta + numero: varias simulacoes dividem o mesmo
                    // diretorio, entao a pasta sozinha nao identifica mais.
                    sims.find {
                        it.nomePastaCompleto == nomePasta &&
                            it.numeroSimulacao == sessionManager.editandoNumSim
                    } ?: sims.find { it.numeroSimulacao == sessionManager.editandoNumSim }
                        ?: sims.maxByOrNull { it.timestampPrincipal }
                } ?: return@launch
                withContext(Dispatchers.IO) {
                    // Sem isto, a sessão voltava com as FOTOS mas sem
                    // identificação, e o app pedia o nome do paciente de novo.
                    if (sessionManager.nomePaciente.isBlank()) sessionManager.nomePaciente = nome
                    val dCache = patientCache.obterDadosPaciente(nome)
                    if (sessionManager.prontuario.isBlank())
                        dCache?.prontuario?.takeIf { p -> p.isNotBlank() }
                            ?.let { p -> sessionManager.prontuario = p }
                    if (sessionManager.dataNascimento.isBlank())
                        dCache?.nascimento?.takeIf { n -> n.isNotBlank() }
                            ?.let { n -> sessionManager.dataNascimento = n }

                    sim.rosto?.let { sessionManager.adicionarCopiando(it.arquivoLocal, Category.FACE) }
                    sim.etiqueta?.let { sessionManager.adicionarCopiando(it.arquivoLocal, Category.LABEL) }
                    sim.posicionamentos.forEach { sessionManager.adicionarCopiando(it.arquivoLocal, Category.POSITIONING) }
                    sim.acessoriosLista.forEach { sessionManager.adicionarCopiando(it.arquivoLocal, Category.ACCESSORIES) }
                    sim.documentos.forEach { sessionManager.adicionarCopiando(it.arquivoLocal, Category.DOCUMENTS) }
                }
                // Banner com o paciente de volta (a identificação foi restaurada acima).
                atualizarBannerPaciente(sessionManager.nomePaciente,
                    patientCache.obterContagemSimulacoes(
                        sessionManager.nomePaciente, sessionManager.prontuario).coerceAtLeast(1))
                atualizarStatusUI(); atualizarCategoriaUI()
            } catch (_: Exception) {}
        }
    }

    /** Resimulação confirmada: traz o ROSTO e a ETIQUETA da última simulação para a
     *  sessão nova e posiciona direto em POSICIONAMENTO (novos posicionamentos,
     *  acessórios e impressos seguem normalmente). */
    private fun copiarBaseDaSimulacaoAnterior(nome: String) {
        if (sessionManager.quantidadeCategoria(Category.FACE) > 0) return  // já copiado
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val sim = withContext(Dispatchers.IO) {
                    com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this@MainActivity)
                        .buscarSimulacoes(nome).maxByOrNull { it.timestampPrincipal }
                } ?: return@launch
                var copiou = false
                withContext(Dispatchers.IO) {
                    sim.rosto?.let { sessionManager.adicionarCopiando(it.arquivoLocal, Category.FACE); copiou = true }
                    sim.etiqueta?.let { sessionManager.adicionarCopiando(it.arquivoLocal, Category.LABEL); copiou = true }
                }
                if (copiou) {
                    selecionarCategoria(Category.POSITIONING)
                    atualizarStatusUI(); atualizarCategoriaUI()
                    Toast.makeText(this@MainActivity, R.string.reuse_prev_photos_ok, Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) { /* sem simulação anterior legível: segue normal */ }
        }
    }

    private fun selecionarCategoria(c: Category) {
        sessionManager.categoriaAtiva = c
        atualizarCategoriaUI()
        if (c == Category.DOCUMENTS) mostrarAvisoDocs()
    }

    // ===== Aviso flutuante (transparente e temporário): impressos não entram no PDF =====
    private var avisoDocsView: TextView? = null
    private fun mostrarAvisoDocs() {
        val root = window.decorView as? android.view.ViewGroup ?: return
        avisoDocsView?.let { root.removeView(it); avisoDocsView = null }
        val tv = TextView(this).apply {
            text = getString(R.string.docs_not_in_pdf)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(40, 26, 40, 26)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xB3000000.toInt()); cornerRadius = 24f
            }
            alpha = 0f
        }
        val lp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
            topMargin = (90 * resources.displayMetrics.density).toInt()
            marginStart = 40; marginEnd = 40
        }
        root.addView(tv, lp)
        avisoDocsView = tv
        tv.animate().alpha(1f).setDuration(220).withEndAction {
            tv.postDelayed({
                tv.animate().alpha(0f).setDuration(400).withEndAction {
                    (tv.parent as? android.view.ViewGroup)?.removeView(tv)
                    if (avisoDocsView == tv) avisoDocsView = null
                }.start()
            }, 2600)
        }.start()
    }

    // ===== Scanner de impressos (ML Kit Document Scanner) =====
    private var scannerDocsFalhou = false
    private var categoriaScanAlvo: Category = Category.DOCUMENTS
    private val scannerDocsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val res = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
            .fromActivityResultIntent(result.data) ?: return@registerForActivityResult
        val paginas = res.pages ?: emptyList()
        var n = 0
        for (p in paginas) {
            try {
                val uri = p.imageUri
                val tmp = java.io.File(cacheDir, "doc_${System.currentTimeMillis()}_${n}.jpg")
                contentResolver.openInputStream(uri)?.use { inp ->
                    java.io.FileOutputStream(tmp).use { out -> inp.copyTo(out) }
                }
                if (tmp.exists() && tmp.length() > 0) {
                    // Etiqueta: realce automático de contraste (nitidez na impressão)
                    if (categoriaScanAlvo == Category.LABEL)
                        com.radioterapia.ai.util.ImagemUtils.aplicarContrasteDocumento(tmp)
                    sessionManager.adicionarFoto(tmp, categoriaScanAlvo); n++
                }
            } catch (_: Exception) {}
        }
        if (n > 0) {
            if (categoriaScanAlvo == Category.LABEL) {
                Toast.makeText(this, R.string.label_scanned_ok, Toast.LENGTH_SHORT).show()
                avancarCategoriaAutomatica()
            } else {
                Toast.makeText(this, getString(R.string.docs_added_n, n), Toast.LENGTH_SHORT).show()
            }
            atualizarStatusUI(); atualizarCategoriaUI()
        }
    }

    /** Abre o scanner nativo (bordas + perspectiva + filtros). Se indisponível,
     *  marca fallback e a captura segue pela câmera comum. */
    private fun iniciarScannerDocs() {
        try {
            val builder = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setResultFormats(
                    com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(
                    com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            if (categoriaScanAlvo == Category.LABEL) builder.setPageLimit(1)
            val opts = builder.build()
            com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(opts)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    scannerDocsLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener {
                    scannerDocsFalhou = true
                    Toast.makeText(this, R.string.doc_scan_fail_fallback, Toast.LENGTH_LONG).show()
                    tirarFoto()  // reentra e cai na câmera comum
                }
        } catch (_: Throwable) {
            scannerDocsFalhou = true
            Toast.makeText(this, R.string.doc_scan_fail_fallback, Toast.LENGTH_LONG).show()
            tirarFoto()
        }
    }

    private fun atualizarCategoriaUI() {
        val ativa = sessionManager.categoriaAtiva
        tabIds.forEach { (cat, id) ->
            val tab = findViewById<View>(id)
            val ativo = cat == ativa
            val tem = sessionManager.quantidadeCategoria(cat) > 0
            val txtTitle = tab.findViewById<TextView>(R.id.tabTitle)
            val dot = tab.findViewById<View>(R.id.tabDot)
            val countLabel = tab.findViewById<TextView>(R.id.tabCount)

            txtTitle.text = nomeCategoria(cat)
            txtTitle.setTextColor(if (ativo) 0xFFFFFFFF.toInt() else 0xFFB0BEC5.toInt())
            tab.setBackgroundColor(if (ativo) 0xFF1565C0.toInt() else 0xFF263238.toInt())
            dot.visibility = if (tem) View.VISIBLE else View.GONE
            // Contador laranja nas categorias que acumulam várias fotos.
            val temContador = cat == Category.POSITIONING ||
                cat == Category.ACCESSORIES || cat == Category.DOCUMENTS
            countLabel.visibility = if (temContador && tem) View.VISIBLE else View.GONE
            countLabel.text = sessionManager.quantidadeCategoria(cat).toString()
        }
        txtCategoriaAtiva.text = nomeCategoria(ativa)
        com.radioterapia.ai.ui.anim.Movimento.deslizarIndicador(
            findViewById(R.id.indicadorAba), tabsCategoria,
            tabIds.indexOfFirst { it.first == ativa })
    }

    private fun nomeCategoria(c: Category): String = when (c) {
        Category.FACE -> getString(R.string.cat_face)
        Category.LABEL -> getString(R.string.cat_label)
        Category.POSITIONING -> getString(R.string.cat_positioning)
        Category.ACCESSORIES -> getString(R.string.cat_accessories)
        Category.DOCUMENTS -> getString(R.string.cat_documents)
    }

    /**
     * Avança a categoria automaticamente depois de salvar — e AVISA que avançou.
     *
     * A troca era silenciosa: o técnico salvava a foto do rosto, o app mudava
     * sozinho para Etiqueta, e a próxima foto entrava na categoria errada
     * porque ninguém percebeu a mudança. Numa tela de câmera, com o paciente
     * posicionado, ninguém confere a barra de abas antes de cada disparo.
     *
     * O aviso é o mínimo que resolve: um toast curto com a categoria nova e um
     * pisca na aba. Não custa clique nem passo — só torna visível o que o app
     * já fazia.
     */
    private fun avancarCategoriaAutomatica() {
        val antes = sessionManager.categoriaAtiva
        when (antes) {
            Category.FACE -> if (!sessionManager.temFotoEtiqueta()) selecionarCategoria(Category.LABEL)
                              else if (sessionManager.quantidadeCategoria(Category.POSITIONING) == 0) selecionarCategoria(Category.POSITIONING)
                              else if (!sessionManager.temFotoAcessorios()) selecionarCategoria(Category.ACCESSORIES)
            Category.LABEL -> if (sessionManager.quantidadeCategoria(Category.POSITIONING) == 0) selecionarCategoria(Category.POSITIONING)
                               else if (!sessionManager.temFotoAcessorios()) selecionarCategoria(Category.ACCESSORIES)
            Category.POSITIONING -> { /* fica em posicionamento até técnico mudar */ }
            Category.ACCESSORIES -> { /* fica em acessórios */ }
            Category.DOCUMENTS -> { /* fica em impressos; usuário escaneia quantos quiser */ }
        }
        val depois = sessionManager.categoriaAtiva
        if (depois != antes) anunciarTrocaDeCategoria(depois)
    }

    /** Toast curto + pisca na aba nova. Só quando a troca foi do APP, não do técnico. */
    private fun anunciarTrocaDeCategoria(nova: Category) {
        Toast.makeText(this, getString(R.string.cat_switched, nomeCategoria(nova)),
            Toast.LENGTH_SHORT).show()
        val id = tabIds.firstOrNull { it.first == nova }?.second ?: return
        val tab = findViewById<View>(id) ?: return
        try {
            tab.clearAnimation()
            val pisca = android.view.animation.AlphaAnimation(1f, 0.25f).apply {
                duration = 220; repeatCount = 3
                repeatMode = android.view.animation.Animation.REVERSE
            }
            tab.startAnimation(pisca)
        } catch (_: Exception) { /* animação é reforço, não requisito */ }
    }

    // ============= ZOOM + FLASH + GRID =============

    private fun configurarZoomEFlash() {
        ligarSeekACamera()

        // Pinch-to-zoom
        val gestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                val current = zoomState.zoomRatio
                val novo = (current * detector.scaleFactor)
                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(novo)
                val pct = ((novo - zoomState.minZoomRatio) * 100f /
                    (zoomState.maxZoomRatio - zoomState.minZoomRatio)).toInt()
                seekZoom.progress = pct.coerceIn(0, 100)
                return true
            }
        })
        viewFinder.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(event)
                if (!gestureDetector.isInProgress && event.action == MotionEvent.ACTION_UP) {
                    val factory = viewFinder.meteringPointFactory
                    val ponto = factory.createPoint(event.x, event.y)
                    camera?.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(ponto).build()
                    )
                    return true
                }
                return true
            }
        })

        btnFlash.setOnClickListener { alternarFlash() }
        atualizarIconeFlash()
    }

    private fun alternarFlash() {
        // Item 9: 2 estados - on/off
        flashMode = if (flashMode == ImageCapture.FLASH_MODE_ON) ImageCapture.FLASH_MODE_OFF
                    else ImageCapture.FLASH_MODE_ON
        imageCapture?.flashMode = flashMode
        atualizarIconeFlash()
    }

    private fun atualizarIconeFlash() {
        val ligado = flashMode == ImageCapture.FLASH_MODE_ON
        btnFlash.setImageResource(if (ligado) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        btnFlash.setColorFilter(if (ligado) 0xFFFFD54F.toInt() else 0xFF9E9E9E.toInt())
        btnFlash.alpha = 1.0f
        val label = if (ligado) getString(R.string.flash_on) else getString(R.string.flash_off)
        btnFlash.contentDescription = label
        txtFlashLabel.text = label
        txtFlashLabel.setTextColor(if (ligado) 0xFFFFD54F.toInt() else 0xFFCCCCCC.toInt())
    }

    // ===== MUDO DO CLIQUE DA CÂMERA (item 9) =====
    // Botão simples toggle on/off. Quando MUDO: alpha cheio (indica ação contrária).
    private fun configurarMudoClique() {
        atualizarVisualMudo()
        btnMute.setOnClickListener {
            cliqueMudo = !cliqueMudo
            atualizarVisualMudo()
        }
    }

    private fun atualizarVisualMudo() {
        // cliqueMudo = true → som DESLIGADO (silencioso). false → som LIGADO.
        val somLigado = !cliqueMudo
        btnMute.setImageResource(if (somLigado) R.drawable.ic_sound_on else R.drawable.ic_sound_off)
        btnMute.setColorFilter(if (somLigado) 0xFFFFD54F.toInt() else 0xFF9E9E9E.toInt())
        btnMute.alpha = 1.0f
        txtMuteLabel.text = if (somLigado) getString(R.string.mute_on) else getString(R.string.mute_off)
        txtMuteLabel.setTextColor(if (somLigado) 0xFFFFD54F.toInt() else 0xFFCCCCCC.toInt())
    }

    private fun configurarGrid() {
        gridOverlay.visibility = if (config.cameraGrid) View.VISIBLE else View.GONE
    }

    // ============= IDENTIFICAÇÃO DO PACIENTE =============

    private fun configurarScanLauncher() {
        cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Foto recortada (arquivo sobrescrito): atualiza o visor.
                arquivoTemporario?.let { mostrarFotoNoVisor(it, ehDaSessao = false) }
            }
        }
        scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                // Scan cancelado: se ainda não há paciente, reabre o seletor de modo
                // (em vez de deixar a câmera aberta sem paciente identificado).
                if (sessionManager.nomePaciente.isBlank()) abrirDialogIdentificarPaciente()
                return@registerForActivityResult
            }
            val data = result.data ?: return@registerForActivityResult
            val imagemPath = data.getStringExtra(ScanPacienteActivity.RESULT_IMAGEM_PATH) ?: ""
            if (modoScanAtual == ScanPacienteActivity.MODO_OCR) {
                val texto = data.getStringExtra(ScanPacienteActivity.RESULT_TEXTO_OCR) ?: ""
                processarOcr(texto, imagemPath)
            } else {
                val codigo = data.getStringExtra(ScanPacienteActivity.RESULT_CODIGO_BARRAS) ?: ""
                processarCodigoBarras(codigo, imagemPath)
            }
        }
    }

    private fun abrirDialogIdentificarPaciente() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_modo_paciente, null)
        val dialog = AlertDialog.Builder(this).setView(view).setCancelable(true).create()

        // Se o usuário cancelar (botão voltar do Android ou toque fora) sem ter
        // identificado paciente, volta pra tela anterior em vez de ficar numa
        // câmera sem paciente.
        dialog.setOnCancelListener {
            if (sessionManager.nomePaciente.isBlank()) voltarParaLanding()
        }

        view.findViewById<Button>(R.id.btnModoDigitar).setOnClickListener {
            dialog.dismiss(); abrirDialogDigitarNome()
        }
        view.findViewById<Button>(R.id.btnModoOcr).setOnClickListener {
            dialog.dismiss(); modoScanAtual = ScanPacienteActivity.MODO_OCR
            scanLauncher.launch(Intent(this, ScanPacienteActivity::class.java).apply {
                putExtra(ScanPacienteActivity.EXTRA_MODO, ScanPacienteActivity.MODO_OCR)
            })
        }

        dialog.show()
    }

    /**
     * Uso interno: para criar PhotoID_RT na RAIZ do armazenamento e gravar
     * fotos + PDF, pede "Acesso a todos os arquivos" (Android 11+). Se o usuário
     * recusar, o app ainda funciona salvando na pasta interna do app.
     */
    private fun verificarAcessoArmazenamento() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this); return
        }
        if (android.os.Environment.isExternalStorageManager()) {
            com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this); return
        }
        if (config.allFilesSolicitado) return  // já perguntamos antes
        config.allFilesSolicitado = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.all_files_title)
            .setMessage(R.string.all_files_message)
            .setPositiveButton(R.string.all_files_grant) { _, _ ->
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    catch (_: Exception) {}
                }
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun abrirDialogDigitarNome() {
        val view = layoutInflater.inflate(R.layout.dialog_novo_paciente, null)
        val lblNome = view.findViewById<TextView>(R.id.lblNome)
        val etNome = view.findViewById<EditText>(R.id.etNovoNome)
        val etNasc = view.findViewById<EditText>(R.id.etNovoNascimento)
        val etProt = view.findViewById<EditText>(R.id.etNovoProntuario)
        com.radioterapia.ai.util.UiText.aplicarMascaraData(etNasc)
        // Obrigatórios com asterisco vermelho
        fun asterisco(id: Int, resId: Int) {
            view.findViewById<TextView>(id).text = android.text.Html.fromHtml(
                getString(resId) + " <font color='#E53935'>*</font>",
                android.text.Html.FROM_HTML_MODE_LEGACY)
        }
        asterisco(R.id.lblNasc, R.string.birth_date_label)
        asterisco(R.id.lblPront, R.string.record_label)
        asterisco(R.id.lblSexo, R.string.cad_sexo_label_req)
        // Médico assistente: agora informado na tela de Confirmar dados (pós-fotos)
        // Nome com asterisco vermelho indicando obrigatório
        lblNome.text = android.text.Html.fromHtml(
            "${getString(R.string.full_name)} <font color='#E53935'>*</font>",
            android.text.Html.FROM_HTML_MODE_LEGACY)

        // Auto-insere as barras enquanto digita a data (dd/mm/aaaa).
        etNasc.addTextChangedListener(object : android.text.TextWatcher {
            private var editando = false
            override fun afterTextChanged(s: android.text.Editable?) {
                if (editando || s == null) return
                editando = true
                val digitos = s.toString().filter { it.isDigit() }.take(8)
                val sb = StringBuilder()
                for (i in digitos.indices) {
                    if (i == 2 || i == 4) sb.append('/')
                    sb.append(digitos[i])
                }
                s.replace(0, s.length, sb.toString())
                editando = false
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.type_name)
            .setView(view).setCancelable(true)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.back) { _, _ -> abrirDialogIdentificarPaciente() }
            .setOnCancelListener { abrirDialogIdentificarPaciente() }
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val nome = etNome.text.toString().trim()
                        val nasc = etNasc.text.toString().trim()
                        val pront = etProt.text.toString().trim()
                        if (nome.isEmpty()) {
                            etNome.error = getString(R.string.name_required)
                            com.radioterapia.ai.ui.anim.Movimento.sacudirErro(etNome)
                            Toast.makeText(this@MainActivity, R.string.name_required, Toast.LENGTH_SHORT).show()
                        } else if (!com.radioterapia.ai.util.DateUtils.nascimentoValido(nasc)) {
                            Toast.makeText(this@MainActivity, R.string.birth_invalid,
                                Toast.LENGTH_LONG).show()
                        } else if (pront.isBlank()) {
                            Toast.makeText(this@MainActivity, R.string.pront_required,
                                Toast.LENGTH_LONG).show()
                        } else {
                            val sexoSel = when {
                                view.findViewById<android.widget.RadioButton>(R.id.rbCadSexoM).isChecked -> "M"
                                view.findViewById<android.widget.RadioButton>(R.id.rbCadSexoF).isChecked -> "F"
                                else -> ""
                            }
                            if (sexoSel.isBlank()) {
                                Toast.makeText(this@MainActivity, R.string.cad_sexo_required,
                                    Toast.LENGTH_LONG).show()
                                return@setOnClickListener
                            }
                            dismiss()
                            confirmarSeNomeSuspeito(nome) { c ->
                                finalizarIdentificacao(c, pront, nasc)
                                patientCache.atualizarSexoMedico(c, sexoSel, "", pront)
                            }
                        }
                    }
                }
                show()
            }
    }

    private fun processarOcr(textoOcr: String, imagemPath: String) {
        // A foto da etiqueta capturada pelo ScanPaciente vira a foto da categoria ETIQUETA
        if (imagemPath.isNotEmpty()) {
            val arq = File(imagemPath)
            if (arq.exists()) sessionManager.adicionarFoto(arq, Category.LABEL)
        }
        sessionManager.textoEtiquetaOcr = textoOcr

        val prontSugerido = EtiquetaParser.extrairProntuario(textoOcr)

        // Se conseguiu extrair prontuário, tenta lookup no CSV primeiro
        if (prontSugerido.isNotBlank()) {
            CoroutineScope(Dispatchers.Main).launch {
                val resultado = withContext(Dispatchers.IO) {
                    com.radioterapia.ai.csv.PatientLookup(this@MainActivity).buscar(prontSugerido)
                }
                when (resultado) {
                    is com.radioterapia.ai.csv.PatientLookup.Resultado.Encontrado -> {
                        val p = resultado.paciente
                        if (resultado.divergencia != null) {
                            mostrarDialogDivergencia(p.prontuario, p.nome, p.nascimento, resultado.divergencia)
                        } else {
                            confirmarPacienteDoCsv(p)
                        }
                    }
                    is com.radioterapia.ai.csv.PatientLookup.Resultado.NaoEncontrado -> {
                        // Cai para fluxo manual com sugestão do parser
                        confirmarOcrManual(textoOcr, imagemPath)
                    }
                }
            }
            return
        }

        // Sem prontuário identificado: fluxo manual baseado no parser
        confirmarOcrManual(textoOcr, imagemPath)
    }

    /** Fluxo manual de confirmação OCR (quando CSV não ajudou ou não foi configurado). */
    private fun confirmarOcrManual(textoOcr: String, imagemPath: String) {
        val nomeSugerido = EtiquetaParser.extrairNome(textoOcr)
        val nascSugerida = EtiquetaParser.extrairDataNascimento(textoOcr)
        val prontSugerido = EtiquetaParser.extrairProntuario(textoOcr)

        mostrarDialogConfirmacao(getString(R.string.confirm_name_title),
            getString(R.string.lbl_patient_name), nomeSugerido,
            textoOcr, imagemPath, getString(R.string.hint_prefilled)) { nomeConf ->
            if (nomeConf.isBlank()) {
                Toast.makeText(this, getString(R.string.hc_name_empty), Toast.LENGTH_SHORT).show()
                return@mostrarDialogConfirmacao false
            }
            confirmarSeNomeSuspeito(nomeConf) { c ->
                mostrarDialogConfirmacao(getString(R.string.confirm_birth_title),
                    getString(R.string.lbl_birth_date), nascSugerida,
                    textoOcr, imagemPath, getString(R.string.confirm_birth_hint), ehData = true) { nasc ->
                    if (!com.radioterapia.ai.util.DateUtils.nascimentoValido(nasc)) {
                        Toast.makeText(this, R.string.birth_invalid, Toast.LENGTH_LONG).show()
                        return@mostrarDialogConfirmacao false
                    }
                    mostrarDialogConfirmacao(getString(R.string.confirm_record_title),
                        getString(R.string.lbl_record), prontSugerido,
                        textoOcr, imagemPath, getString(R.string.confirm_pront_hint)) { pront ->
                        if (pront.isBlank()) {
                            Toast.makeText(this, R.string.pront_required, Toast.LENGTH_LONG).show()
                            return@mostrarDialogConfirmacao false
                        }
                        coletarExtrasEConcluir(c, pront, nasc, textoOcr, imagemPath); true
                    }
                    true
                }
            }
            true
        }
    }

    private fun processarCodigoBarras(codigo: String, imagemPath: String) {
        // A foto da etiqueta capturada vira foto da categoria ETIQUETA
        if (imagemPath.isNotEmpty()) {
            val arq = File(imagemPath)
            if (arq.exists()) sessionManager.adicionarFoto(arq, Category.LABEL)
        }
        Toast.makeText(this, getString(R.string.ok_record_read, codigo), Toast.LENGTH_SHORT).show()

        // Tenta lookup no CSV antes de pedir confirmação manual
        CoroutineScope(Dispatchers.Main).launch {
            val resultado = withContext(Dispatchers.IO) {
                com.radioterapia.ai.csv.PatientLookup(this@MainActivity).buscar(codigo)
            }
            when (resultado) {
                is com.radioterapia.ai.csv.PatientLookup.Resultado.Encontrado -> {
                    val p = resultado.paciente
                    if (resultado.divergencia != null) {
                        mostrarDialogDivergencia(p.prontuario, p.nome, p.nascimento,
                            resultado.divergencia)
                    } else {
                        confirmarPacienteDoCsv(p)
                    }
                }
                is com.radioterapia.ai.csv.PatientLookup.Resultado.NaoEncontrado -> {
                    if (resultado.similares.isNotEmpty()) {
                        mostrarSugestoesPacientes(resultado.similares, codigo)
                    } else {
                        pedirNomeManual(codigo)
                    }
                }
            }
        }
    }

    /** Apresenta os dados do CSV ao técnico, que confirma ou edita. */
    private fun confirmarPacienteDoCsv(paciente: com.radioterapia.ai.data.PatientEntity) {
        val sb = StringBuilder()
        sb.append(getString(R.string.csv_found_header)).append("\n\n")
        sb.append(getString(R.string.csv_found_name, paciente.nome)).append("\n")
        sb.append(getString(R.string.patient_record, paciente.prontuario)).append("\n")
        if (paciente.nascimento.isNotBlank())
            sb.append(getString(R.string.csv_found_birth, paciente.nascimento)).append("\n")
        if (!paciente.campoExtra1Titulo.isNullOrBlank())
            sb.append("${paciente.campoExtra1Titulo}: ${paciente.campoExtra1Valor}\n")
        if (!paciente.campoExtra2Titulo.isNullOrBlank())
            sb.append("${paciente.campoExtra2Titulo}: ${paciente.campoExtra2Valor}\n")
        if (!paciente.campoExtra3Titulo.isNullOrBlank())
            sb.append("${paciente.campoExtra3Titulo}: ${paciente.campoExtra3Valor}\n")
        sb.append("\n").append(getString(R.string.csv_found_confirm))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hc_patient_identified))
            .setMessage(sb.toString())
            .setCancelable(false)
            .setPositiveButton(getString(R.string.hc_yes_proceed)) { _, _ ->
                // Salva os extras na sessão para irem para o PDF
                val extras = mutableListOf<Pair<String, String>>()
                if (!paciente.campoExtra1Titulo.isNullOrBlank() && !paciente.campoExtra1Valor.isNullOrBlank())
                    extras.add(paciente.campoExtra1Titulo to paciente.campoExtra1Valor)
                if (!paciente.campoExtra2Titulo.isNullOrBlank() && !paciente.campoExtra2Valor.isNullOrBlank())
                    extras.add(paciente.campoExtra2Titulo to paciente.campoExtra2Valor)
                if (!paciente.campoExtra3Titulo.isNullOrBlank() && !paciente.campoExtra3Valor.isNullOrBlank())
                    extras.add(paciente.campoExtra3Titulo to paciente.campoExtra3Valor)
                sessionManager.salvarIdsExtras(extras)

                confirmarSeNomeSuspeito(paciente.nome) { c ->
                    finalizarIdentificacao(c, paciente.prontuario, paciente.nascimento)
                }
            }
            .setNegativeButton(getString(R.string.hc_edit_manually)) { _, _ ->
                pedirNomeManual(paciente.prontuario, sugestao = paciente.nome)
            }
            .show()
    }

    private fun mostrarDialogDivergencia(
        prontuario: String, nomeCsv: String, nascCsv: String,
        divergencias: List<com.radioterapia.ai.csv.PatientLookup.Divergencia>
    ) {
        // Registra divergência no log
        com.radioterapia.ai.audit.AuditLogger(this).registrar(
            com.radioterapia.ai.audit.AuditLogger.Tipo.DIVERGENCE,
            "Divergência CSV ↔ histórico",
            mapOf(
                "prontuario" to prontuario,
                "nome_csv" to nomeCsv,
                "campos" to divergencias.joinToString("; ") { "${it.campo}: csv='${it.valorCsv}' local='${it.valorLocal}'" }
            )
        )

        val sb = StringBuilder()
        sb.append(getString(R.string.patient_record, prontuario)).append("\n\n")
        sb.append(getString(R.string.divergence_which)).append("\n\n")
        divergencias.forEach { d ->
            sb.append("• ${d.campo}:\n")
            sb.append("    CSV: ${d.valorCsv}\n")
            sb.append("    Local: ${d.valorLocal}\n\n")
        }
        sb.append(getString(R.string.divergence_note))

        AlertDialog.Builder(this)
            .setTitle(R.string.divergence_detected)
            .setMessage(sb.toString())
            .setCancelable(false)
            .setPositiveButton(R.string.use_csv) { _, _ ->
                confirmarSeNomeSuspeito(nomeCsv) { c ->
                    finalizarIdentificacao(c, prontuario, nascCsv)
                }
            }
            .setNeutralButton(R.string.use_local) { _, _ ->
                // Mantém os dados do histórico local — busca no PatientCache
                val dadosLocal = patientCache.obterDadosPaciente(nomeCsv)
                val nome = dadosLocal?.nome ?: nomeCsv
                val nasc = dadosLocal?.nascimento ?: ""
                confirmarSeNomeSuspeito(nome) { c ->
                    finalizarIdentificacao(c, prontuario, nasc)
                }
            }
            .setNegativeButton(R.string.cancel_verify) { _, _ ->
                abrirDialogIdentificarPaciente()
            }
            .show()
    }

    private fun mostrarSugestoesPacientes(
        sugestoes: List<com.radioterapia.ai.data.PatientEntity>,
        prontuarioBuscado: String
    ) {
        val itens = sugestoes.map { "${it.prontuario}  •  ${it.nome}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hc_did_you_mean))
            .setItems(itens) { _, idx -> confirmarPacienteDoCsv(sugestoes[idx]) }
            .setNegativeButton(getString(R.string.hc_type_manually)) { _, _ -> pedirNomeManual(prontuarioBuscado) }
            .setNeutralButton(R.string.back) { _, _ -> abrirDialogIdentificarPaciente() }
            .show()
    }

    private fun pedirNomeManual(prontuario: String, sugestao: String = "") {
        val edt = EditText(this).apply {
            hint = "Nome completo"
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or android.text.InputType.TYPE_CLASS_TEXT
            setText(sugestao)
            setSelection(sugestao.length)
        }
        AlertDialog.Builder(this)
            .setTitle(if (prontuario.isNotBlank()) "Prontuário $prontuario" else getString(R.string.type_name))
            .setMessage(getString(R.string.hc_patient_not_in_base))
            .setView(edt).setCancelable(false)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.back) { _, _ -> abrirDialogIdentificarPaciente() }
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val nome = edt.text.toString().trim()
                        if (nome.isEmpty()) Toast.makeText(this@MainActivity, getString(R.string.hc_empty), Toast.LENGTH_SHORT).show()
                        else {
                            dismiss()
                            confirmarSeNomeSuspeito(nome) { c -> finalizarIdentificacao(c, prontuario, "") }
                        }
                    }
                }
                show()
            }
    }

    private fun confirmarSeNomeSuspeito(nome: String, onOk: (String) -> Unit) {
        val res = SuspectNameValidator.validar(nome)
        when (res) {
            SuspectNameValidator.Resultado.Ok -> onOk(nome)
            is SuspectNameValidator.Resultado.Suspeito -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.suspicious_name)
                    .setMessage("\"$nome\"\n\n${res.motivo}\n\nProsseguir?")
                    .setCancelable(false)
                    .setPositiveButton(R.string.proceed_anyway) { _, _ -> onOk(nome) }
                    .setNegativeButton(R.string.edit_name) { _, _ ->
                        val edt = EditText(this).apply {
                            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or android.text.InputType.TYPE_CLASS_TEXT
                            setText(nome); setSelection(nome.length)
                        }
                        AlertDialog.Builder(this).setTitle(R.string.edit_name).setView(edt)
                            .setPositiveButton(R.string.confirm) { _, _ ->
                                val novo = edt.text.toString().trim()
                                if (novo.isNotBlank()) confirmarSeNomeSuspeito(novo, onOk)
                            }.setNegativeButton(R.string.cancel, null).show()
                    }.show()
            }
        }
    }

    private fun mostrarDialogConfirmacao(
        titulo: String, labelCampo: String, valor: String, ocr: String, imgPath: String,
        dica: String, ehData: Boolean = false, onConf: (String) -> Boolean
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirmar_campo, null)
        val img = view.findViewById<ImageView>(R.id.imgEtiquetaCapturada)
        val edtTxt = view.findViewById<EditText>(R.id.edtTextoDetectado)
        val edtCmp = view.findViewById<EditText>(R.id.edtCampoConfirmacao)
        val btnVisto = view.findViewById<android.widget.ImageButton>(R.id.btnVistoCampo)
        view.findViewById<TextView>(R.id.txtLabelCampo).text = labelCampo
        view.findViewById<TextView>(R.id.txtDicaCampo).text = dica
        if (imgPath.isNotEmpty()) {
            val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
            img.setImageBitmap(BitmapFactory.decodeFile(imgPath, opt))
            img.visibility = View.VISIBLE
        }
        edtTxt.setText(ocr); edtTxt.setKeyListener(null); edtCmp.setText(valor)
        if (ehData) {
            edtCmp.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            edtCmp.keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789/")
            com.radioterapia.ai.util.UiText.aplicarMascaraData(edtCmp)
        }

        // Visto de confirmação: apagado até o usuário tocar; editar o texto
        // desfaz a confirmação (precisa validar o valor final).
        var confirmado = false
        fun pintarVisto() {
            com.radioterapia.ai.ui.anim.Movimento.vistoConfirmado(btnVisto, confirmado)
        }
        pintarVisto()
        btnVisto.setOnClickListener { confirmado = !confirmado; pintarVisto() }
        edtCmp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (confirmado) { confirmado = false; pintarVisto() }
            }
        })

        AlertDialog.Builder(this).setTitle(titulo).setView(view).setCancelable(false)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.back) { _, _ -> abrirDialogIdentificarPaciente() }
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (!confirmado) {
                            // O TREMOR APONTA O VISTO, que e o que falta. O toast
                            // diz "confirme o campo" e o tecnico procura onde —
                            // num dialogo com foto, dois campos e um botao, "onde"
                            // nao e obvio.
                            com.radioterapia.ai.ui.anim.Movimento.sacudirErro(btnVisto)
                            Toast.makeText(this@MainActivity,
                                getString(R.string.confirm_field_alert, labelCampo),
                                Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        // Reprovou a validacao (nome vazio, data impossivel,
                        // prontuario em branco): o tremor vai no CAMPO, porque
                        // agora o problema e o conteudo, nao a confirmacao.
                        if (onConf(edtCmp.text.toString().trim())) dismiss()
                        else com.radioterapia.ai.ui.anim.Movimento.sacudirErro(edtCmp)
                    }
                }
                show()
            }
    }

    /** Pós-etiqueta: SEXO (obrigatório, com visto) e MÉDICO (opcional, mesma
     *  interface da confirmação do nome) — tudo confirmado no ✓ verde. */
    private fun coletarExtrasEConcluir(nome: String, prontuario: String, nascimento: String,
                                       textoOcr: String = "", imagemPath: String = "") {
        val cont = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF263238.toInt())
            setPadding(48, 28, 48, 20)
        }
        cont.addView(TextView(this).apply {
            text = android.text.Html.fromHtml(
                getString(R.string.cad_sexo_label_req) + " <font color='#E53935'>*</font>",
                android.text.Html.FROM_HTML_MODE_LEGACY)
            textSize = 14f; setTextColor(0xFFECEFF1.toInt())
        })
        val linha = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val rg = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val sugestao = com.radioterapia.ai.scan.EtiquetaParser.extrairSexo(textoOcr)
        val rbM = android.widget.RadioButton(this).apply {
            id = View.generateViewId(); text = "M"; setTextColor(0xFFECEFF1.toInt())
            isChecked = sugestao == "M"
        }
        val rbF = android.widget.RadioButton(this).apply {
            id = View.generateViewId(); text = "F"; setTextColor(0xFFECEFF1.toInt())
            isChecked = sugestao == "F"
        }
        rg.addView(rbM); rg.addView(rbF)
        val btnVisto = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_check_confirm)
            setBackgroundResource(R.drawable.bg_icon_visor)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            layoutParams = android.widget.LinearLayout.LayoutParams(110, 110)
        }
        var confirmado = false
        fun pintar() {
            com.radioterapia.ai.ui.anim.Movimento.vistoConfirmado(btnVisto, confirmado)
        }
        pintar()
        btnVisto.setOnClickListener { confirmado = !confirmado; pintar() }
        rg.setOnCheckedChangeListener { _, _ -> if (confirmado) { confirmado = false; pintar() } }
        linha.addView(rg, android.widget.LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        linha.addView(btnVisto)
        cont.addView(linha)

        AlertDialog.Builder(this)
            .setView(cont)
            .setCancelable(false)
            .setPositiveButton(R.string.confirm, null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val sexo = if (rbM.isChecked) "M" else if (rbF.isChecked) "F" else ""
                        if (sexo.isBlank() || !confirmado) {
                            com.radioterapia.ai.ui.anim.Movimento.sacudirErro(btnVisto)
                            Toast.makeText(this@MainActivity,
                                R.string.cad_sexo_required, Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        dismiss()
                        finalizarIdentificacao(nome, prontuario, nascimento)
                        patientCache.atualizarSexoMedico(nome, sexo, "", prontuario)
                    }
                }
                show()
            }
    }

    /** Revisão da identificação no meio da simulação (voltar da câmera):
     *  mesmo formulário do cadastro, pré-preenchido; corrigir salva na sessão.
     *  (v46: sem médico — ele agora é informado na tela de Confirmar dados.) */
    private fun abrirDialogEditarIdentificacao() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_novo_paciente, null)
        val etNome = view.findViewById<EditText>(R.id.etNovoNome)
        val etNasc = view.findViewById<EditText>(R.id.etNovoNascimento)
        val etProt = view.findViewById<EditText>(R.id.etNovoProntuario)
        com.radioterapia.ai.util.UiText.aplicarMascaraData(etNasc)
        fun asterisco2(id: Int, resId: Int) {
            view.findViewById<TextView>(id).text = android.text.Html.fromHtml(
                getString(resId) + " <font color='#E53935'>*</font>",
                android.text.Html.FROM_HTML_MODE_LEGACY)
        }
        view.findViewById<TextView>(R.id.lblNome).text = android.text.Html.fromHtml(
            getString(R.string.full_name) + " <font color='#E53935'>*</font>",
            android.text.Html.FROM_HTML_MODE_LEGACY)
        asterisco2(R.id.lblNasc, R.string.birth_date_label)
        asterisco2(R.id.lblPront, R.string.record_label)
        asterisco2(R.id.lblSexo, R.string.cad_sexo_label_req)

        etNome.setText(sessionManager.nomePaciente)
        etNasc.setText(sessionManager.dataNascimento)
        etProt.setText(sessionManager.prontuario)
        val dadosPac = patientCache.obterDadosPaciente(sessionManager.nomePaciente)
        when (dadosPac?.sexo) {
            "M" -> view.findViewById<android.widget.RadioButton>(R.id.rbCadSexoM).isChecked = true
            "F" -> view.findViewById<android.widget.RadioButton>(R.id.rbCadSexoF).isChecked = true
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.review_ident_title)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.back) { _, _ -> voltarParaLanding() }
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val nome = etNome.text.toString().trim()
                        val nasc = etNasc.text.toString().trim()
                        val pront = etProt.text.toString().trim()
                        if (nome.isBlank()) {
                            Toast.makeText(this@MainActivity, getString(R.string.hc_name_empty), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!com.radioterapia.ai.util.DateUtils.nascimentoValido(nasc)) {
                            Toast.makeText(this@MainActivity, R.string.birth_invalid,
                                Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        if (pront.isBlank()) {
                            Toast.makeText(this@MainActivity, R.string.pront_required,
                                Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        val sexoSel = when {
                            view.findViewById<android.widget.RadioButton>(R.id.rbCadSexoM).isChecked -> "M"
                            view.findViewById<android.widget.RadioButton>(R.id.rbCadSexoF).isChecked -> "F"
                            else -> ""
                        }
                        if (sexoSel.isBlank()) {
                            Toast.makeText(this@MainActivity, R.string.cad_sexo_required,
                                Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        sessionManager.nomePaciente = nome
                        sessionManager.dataNascimento = nasc
                        sessionManager.prontuario = pront
                        patientCache.atualizarSexoMedico(nome, sexoSel, "", pront)
                        atualizarBannerPaciente(nome,
                            patientCache.obterContagemSimulacoes(nome, pront) + 1)
                        dismiss()
                        Toast.makeText(this@MainActivity, R.string.edit_logged,
                            Toast.LENGTH_SHORT).show()
                    }
                }
                show()
            }
    }

    private fun finalizarIdentificacao(nome: String, prontuario: String, nascimento: String) {
        sessionManager.nomePaciente = nome
        if (prontuario.isNotBlank()) sessionManager.prontuario = prontuario
        if (nascimento.isNotBlank()) sessionManager.dataNascimento = nascimento
        sessionManager.marcarInicio()

        val ant = patientCache.obterContagemSimulacoes(nome, sessionManager.prontuario)
        val numAtual = ant + 1
        atualizarBannerPaciente(nome, numAtual)
        atualizarCategoriaUI()

        if (ant > 0) {
            val msg = StringBuilder()
            msg.append(getString(R.string.resim_existing, ant)).append("\n\n")
            msg.append(getString(R.string.resim_will_be, ant)).append("\n\n")
            msg.append(getString(R.string.resimulation_reasons))
            msg.append("\n\n" + getString(R.string.resim_confirm))
            AlertDialog.Builder(this).setTitle(R.string.resimulation_warning).setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton(R.string.same_patient, null)
                .setNeutralButton(R.string.cancel) { _, _ ->
                    // Aborta a resimulação: limpa a sessão recém-criada e volta
                    // à tela anterior sem nenhuma edição.
                    sessionManager.nomePaciente = ""
                    sessionManager.prontuario = ""
                    sessionManager.dataNascimento = ""
                    finish()
                }
                .setNegativeButton(R.string.not_same_patient) { _, _ ->
                    sessionManager.nomePaciente = ""
                    sessionManager.prontuario = ""
                    sessionManager.dataNascimento = ""
                    txtPacienteAtivo.visibility = View.GONE
                    abrirDialogIdentificarPaciente()
                }.show()
        }
    }

    private fun atualizarBannerPaciente(nome: String, num: Int) {
        val msg = if (num == 1) nome
                  else "$nome • Nova sim. ${num - 1}"
        txtPacienteAtivo.text = msg
        txtPacienteAtivo.visibility = View.VISIBLE
    }

    private fun restaurarRascunho() {
        val numeroSim = patientCache.obterContagemSimulacoes(
            sessionManager.nomePaciente, sessionManager.prontuario) + 1
        atualizarBannerPaciente(sessionManager.nomePaciente, numeroSim)
        atualizarStatusUI()
        atualizarCategoriaUI()
        Toast.makeText(this,
            getString(R.string.draft_restored, sessionManager.quantidade(), sessionManager.nomePaciente),
            Toast.LENGTH_LONG).show()
    }

    // ============= THUMBS =============

    private fun configurarRecyclerThumbs() {
        thumbAdapter = ThumbAdapter(
            sessionManager.fotos.toMutableList(),
            onClick = { f -> mostrarFotoNoVisor(f.arquivo, ehDaSessao = true) },
            onRemover = { f -> confirmarRemoverDaSessao(f.arquivo) }
        )
        recyclerThumbs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerThumbs.adapter = thumbAdapter
    }

    private fun atualizarThumbnails() {
        thumbAdapter.atualizar(sessionManager.fotos)
        recyclerThumbs.visibility = if (sessionManager.temFotos()) View.VISIBLE else View.GONE
    }

    private fun confirmarRemoverDaSessao(f: File) {
        AlertDialog.Builder(this).setTitle(R.string.discard)
            .setMessage(getString(R.string.hc_remove_photo_q))
            .setPositiveButton(R.string.discard) { _, _ ->
                sessionManager.removerFoto(f)
                atualizarThumbnails()
                atualizarStatusUI()
                atualizarCategoriaUI()
                if (arquivoTemporario == f) voltarParaCamera()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    // ============= STATUS / CÂMERA =============

    private fun atualizarStatusUI() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val ssid = info.ssid?.removeSurrounding("\"") ?: ""
        txtWifiStatus.text = if (info.networkId == -1 || ssid.contains("unknown", true) || ssid.isBlank())
            getString(R.string.wifi_disconnected) else getString(R.string.wifi_connected, ssid)
        txtSessaoStatus.text = getString(R.string.photos_count, sessionManager.quantidade())
        atualizarThumbnails()
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val cp = f.get()
            // Rotação atual do display (landscape fixo → ROTATION_90 ou ROTATION_270).
            // Definir targetRotation garante que o EXIF da foto saia coerente com a
            // orientação física, evitando foto girada 90° no PDF.
            val rotacao = rotacaoAtualDoDisplay()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .setTargetRotation(rotacao)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
            try {
                cp.unbindAll()
                camera = cp.bindToLifecycle(this,
                    if (usarCameraFrontal) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture)
                seekZoom.progress = 0

                /*
                    A MOLDURA PRECISA SABER A PROPORCAO DO QUADRO, nao a da tela.

                    Com o tablet deitado a captura e 16:9 e o recorte quase nao
                    tira nada. Em pe ela vira 9:16, e o recorte 16:9 descarta
                    faixas em cima e embaixo — que e exatamente a confusao que os
                    tecnicos relataram. Calcular isso pela proporcao da View daria
                    a moldura errada, porque o PreviewView esta em FIT_CENTER e
                    sobra tarja.
                 */
                val deitado = rotacao == android.view.Surface.ROTATION_90 ||
                              rotacao == android.view.Surface.ROTATION_270
                molduraRecorte?.aspectoVisor = if (deitado) 16f / 9f else 9f / 16f
                mostrarMolduraDoVisor()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.err_camera, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Pinça de zoom da CÂMERA capturada no dispatch: funciona em qualquer área da
     *  tela enquanto o visor está aberto (mesmo sobre a barra de zoom ou a grade). */
    private val pinchCameraDetector by lazy {
        android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                    val novo = (zoomState.zoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    camera?.cameraControl?.setZoomRatio(novo)
                    val pct = ((novo - zoomState.minZoomRatio) * 100f /
                        (zoomState.maxZoomRatio - zoomState.minZoomRatio)).toInt()
                    seekZoom.progress = pct.coerceIn(0, 100)
                    return true
                }
            })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::viewFinder.isInitialized && viewFinder.visibility == View.VISIBLE &&
            ev.pointerCount > 1) {
            pinchCameraDetector.onTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Barrinha de zoom controla a CÂMERA (modo padrão). */
    private fun ligarSeekACamera() {
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
                val ratio = zoomState.minZoomRatio +
                    (zoomState.maxZoomRatio - zoomState.minZoomRatio) * progress / 100f
                camera?.cameraControl?.setZoomRatio(ratio)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** Barrinha de zoom controla o EDITOR pós-captura (moldura 16:9). */
    private fun ligarSeekAoCrop() {
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) cropPreview.setZoomFracao(progress)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** No pós-captura a alocação é FIXA: tabs não-selecionadas ficam apagadas e
     *  nenhuma é clicável (para trocar a categoria, tire uma nova foto). */
    private fun bloquearTabs(bloquear: Boolean) {
        val tabs = mapOf(
            Category.FACE to R.id.tabFace,
            Category.LABEL to R.id.tabLabel,
            Category.POSITIONING to R.id.tabPositioning,
            Category.ACCESSORIES to R.id.tabAccessories,
            Category.DOCUMENTS to R.id.tabDocs
        )
        val ativa = sessionManager.categoriaAtiva
        tabs.forEach { (cat, id) ->
            val v = findViewById<View>(id)
            v.isEnabled = !bloquear
            v.alpha = if (!bloquear || cat == ativa) 1f else 0.35f
        }
    }

    private fun tirarFoto() {
        // Impressos E Etiqueta usam o scanner de documentos (bordas/perspectiva):
        // etiqueta plana e contrastada melhora o registro no PDF e a leitura por OCR.
        // Se o scanner falhou neste aparelho, segue com a câmera comum abaixo.
        val catAtual = sessionManager.categoriaAtiva
        if ((catAtual == Category.DOCUMENTS || catAtual == Category.LABEL) && !scannerDocsFalhou) {
            categoriaScanAlvo = catAtual
            iniciarScannerDocs(); return
        }
        val ic = imageCapture ?: return
        if (sessionManager.nomePaciente.isBlank()) {
            Toast.makeText(this, R.string.how_to_identify, Toast.LENGTH_SHORT).show()
            abrirDialogIdentificarPaciente(); return
        }
        btnCapturar.isEnabled = false
        btnCapturar.alpha = 0.5f
        val arq = File(cacheDir, "temp_${System.currentTimeMillis()}.jpg")
        arquivoTemporario = arq
        if (!cliqueMudo) shutterSound.play(MediaActionSound.SHUTTER_CLICK)

        ic.flashMode = flashMode
        // Rotação livre: usa a orientação ATUAL do display no instante do disparo,
        // senão a foto sai "girada" quando o aparelho mudou de orientação.
        ic.targetRotation = rotacaoAtualDoDisplay()

        ic.takePicture(ImageCapture.OutputFileOptions.Builder(arq).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(baseContext, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
                    btnCapturar.isEnabled = true
                    btnCapturar.alpha = 1.0f
                }
                override fun onImageSaved(o: ImageCapture.OutputFileResults) {
                    mostrarFotoNoVisor(arq, ehDaSessao = false)
                    avaliarQualidade(arq)
                }
            })
    }

    private fun avaliarQualidade(arq: File) {
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) { PhotoQualityDetector.avaliar(arq) }
            txtIndicadorQualidade.visibility = if (res.issue == PhotoQualityDetector.Issue.NONE) View.GONE
                                                 else View.VISIBLE
            txtIndicadorQualidade.text = when (res.issue) {
                PhotoQualityDetector.Issue.TOO_DARK -> getString(R.string.photo_dark)
                PhotoQualityDetector.Issue.TOO_BRIGHT -> getString(R.string.photo_bright)
                PhotoQualityDetector.Issue.BLURRY -> "⚠ Pode estar borrada"
                else -> ""
            }
        }
    }

    private fun mostrarFotoNoVisor(arq: File, ehDaSessao: Boolean) {
        viewFinder.visibility = View.GONE
        gridOverlay.visibility = View.GONE
        layoutControlesCamera.visibility = View.GONE
        btnCapturar.visibility = View.GONE
        holderCapturar.visibility = View.GONE
        barraNavegacao.visibility = View.GONE
        layoutAcoesPosFoto.visibility = View.VISIBLE
        btnCapturar.isEnabled = true
        btnCapturar.alpha = 1.0f

        if (ehDaSessao) {
            // Revisão de foto já salva: apenas visualizar (sem recorte).
            val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
            imgPreview.setImageBitmap(BitmapFactory.decodeFile(arq.absolutePath, opt))
            imgPreview.visibility = View.VISIBLE
            esconderMolduraDoVisor()
            cropPreview.visibility = View.GONE
            btnGirarVisor.visibility = View.GONE
            bloquearTabs(true)
            btnSalvar.text = getString(R.string.back)
            btnDescartar.text = getString(R.string.remove)
            btnSalvar.setOnClickListener { voltarParaCamera() }
            btnDescartar.setOnClickListener { confirmarRemoverDaSessao(arq) }
        } else if (sessionManager.categoriaAtiva == Category.DOCUMENTS) {
            // Impresso via câmera comum (fallback do scanner): sem moldura fixa,
            // documentos podem ser retrato ou paisagem.
            val bm = com.radioterapia.ai.util.ImagemUtils.decodificarComExif(arq)
            imgPreview.setImageBitmap(bm)
            imgPreview.visibility = View.VISIBLE
            esconderMolduraDoVisor()
            cropPreview.visibility = View.GONE
            btnGirarVisor.visibility = View.GONE
            bloquearTabs(true)
            btnSalvar.text = getString(R.string.save)
            btnDescartar.text = getString(R.string.discard)
            btnSalvar.setOnClickListener { salvarParaSessao() }
            btnDescartar.setOnClickListener { descartarFoto() }
        } else {
            // Foto recém-capturada: recorte 16:9 INTEGRADO (pinça/arrasto direto).
            // A moldura horizontal com a regra dos terços mostra COMO a foto será
            // cortada; a imagem abre já com a rotação EXIF aplicada e o botão
            // ⟳ GIRAR ajusta em passos de 90° para encaixar na moldura.
            imgPreview.visibility = View.GONE
            val bm = com.radioterapia.ai.util.ImagemUtils.decodificarComExif(arq)
            if (bm != null) {
                cropPreview.definirBitmap(bm)
                cropPreview.visibility = View.VISIBLE
            }
            // A MOLDURA SAI e o AVISO VOLTA. Aqui o recorte de verdade esta na
            // tela: manter a moldura por cima dele seria desenhar duas molduras.
            // O aviso reaparece porque este e o segundo momento em que da para
            // ajustar — e o unico em que o ajuste ainda e reversivel.
            esconderMolduraDoVisor()
            com.radioterapia.ai.ui.anim.Movimento.mostrarAviso(avisoEncaixar, imgPincaAviso)
            btnGirarVisor.visibility = View.VISIBLE
            bloquearTabs(true)
            ligarSeekAoCrop()
            seekZoom.progress = 0
            btnSalvar.text = getString(R.string.save)
            btnDescartar.text = getString(R.string.discard)
            btnSalvar.setOnClickListener { salvarParaSessao() }
            btnDescartar.setOnClickListener { descartarFoto() }
        }
    }


    private fun voltarParaCamera() {
        btnGirarVisor.visibility = View.GONE
        bloquearTabs(false)
        ligarSeekACamera()
        seekZoom.progress = 0
        imgPreview.setImageBitmap(null)
        imgPreview.visibility = View.GONE
        cropPreview.visibility = View.GONE
        viewFinder.visibility = View.VISIBLE
        // A moldura volta junto com o visor: quem descartou a foto vai enquadrar
        // outra, e e nesse instante que a licao vale de novo.
        mostrarMolduraDoVisor()
        if (config.cameraGrid) gridOverlay.visibility = View.VISIBLE
        layoutControlesCamera.visibility = View.VISIBLE
        btnCapturar.visibility = View.VISIBLE
        holderCapturar.visibility = View.VISIBLE
        barraNavegacao.visibility = View.VISIBLE
        layoutAcoesPosFoto.visibility = View.GONE
        txtIndicadorQualidade.visibility = View.GONE
        btnSalvar.text = getString(R.string.save)
        btnDescartar.text = getString(R.string.discard)
        btnSalvar.setOnClickListener { salvarParaSessao() }
        btnDescartar.setOnClickListener { descartarFoto() }
    }

    private fun descartarFoto() {
        arquivoTemporario?.delete()
        arquivoTemporario = null
        Toast.makeText(this, R.string.discard, Toast.LENGTH_SHORT).show()
        voltarParaCamera()
    }

    private fun salvarParaSessao() {
        val arq = arquivoTemporario ?: return
        val cat = sessionManager.categoriaAtiva

        // ORIGINAL INTACTO — copiado ANTES de qualquer edição.
        // O recorte abaixo sobrescreve o próprio arquivo, e o EXIF é reescrito
        // depois; sem esta cópia, o quadro cheio que a câmera capturou deixa de
        // existir no instante em que o técnico toca em "Salvar".
        val original: File? = try {
            File(cacheDir, "orig_${System.currentTimeMillis()}.jpg")
                .also { arq.copyTo(it, overwrite = true) }
        } catch (_: Exception) { null }

        // Recorte 16:9 integrado: grava a região escolhida no próprio arquivo.
        if (cropPreview.visibility == View.VISIBLE) {
            val recorte = cropPreview.recortar()
            if (recorte != null) {
                try {
                    java.io.FileOutputStream(arq).use { out ->
                        recorte.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                } catch (_: Exception) { /* mantém original se falhar */ }
            }
        }

        // Marca d'água queimada na foto foi desativada: a identificação do paciente
        // aparece no rodapé dinâmico do modo tratamento e na legenda de cada foto no
        // PDF. Evita a "tag dupla" sobreposta. (Reabilitar aqui se quiser ID gravado
        // no próprio JPEG exportado.)
        // if (cat == Category.POSITIONING) {
        //     VisibleWatermark.aplicar(arq, sessionManager.nomePaciente, sessionManager.dataNascimento)
        // }

        // EXIF (todas as categorias)
        val descCategoria = when (cat) {
            Category.FACE -> "ROSTO"
            Category.LABEL -> "ETIQUETA"
            Category.POSITIONING -> "POSICIONAMENTO"
            Category.ACCESSORIES -> "ACESSORIOS"
            Category.DOCUMENTS -> "IMPRESSO"
        }
        val numSim = patientCache.obterContagemSimulacoes(
            sessionManager.nomePaciente, sessionManager.prontuario) + 1
        val sufixo = if (numSim > 1) " - NOVA SIMULACAO ${numSim - 1}" else ""
        ExifWatermark.aplicar(this, arq, sessionManager.nomePaciente, sessionManager.idSimulacao,
            "$descCategoria$sufixo")

        val destino = sessionManager.adicionarFoto(arq, cat)
        original?.let { sessionManager.guardarOriginal(it, destino) }
        arquivoTemporario = null
        Toast.makeText(this, getString(R.string.ok_photo_added, sessionManager.quantidade()), Toast.LENGTH_SHORT).show()
        voltarParaCamera()
        atualizarStatusUI()
        avancarCategoriaAutomatica()
        atualizarCategoriaUI()
    }

    // ============= VOLTAR =============

    private fun voltarParaLanding() {
        if (sessionManager.temFotos() || sessionManager.nomePaciente.isNotBlank()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.hc_back))
                .setMessage(
                    if (sessionManager.temFotos())
                        getString(R.string.draft_kept_count, sessionManager.quantidade())
                    else getString(R.string.hc_draft_kept))
                .setPositiveButton(R.string.back) { _, _ -> finish() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else finish()
    }

    // ============= FINALIZAR =============

    private fun acaoFinalizar() {
        if (!sessionManager.temFotos()) {
            Toast.makeText(this, getString(R.string.hc_no_photo), Toast.LENGTH_SHORT).show(); return
        }
        if (sessionManager.nomePaciente.isBlank()) {
            Toast.makeText(this, getString(R.string.hc_patient_not_identified), Toast.LENGTH_LONG).show(); return
        }

        // Validações de categorias
        val avisos = mutableListOf<String>()
        if (!sessionManager.temFotoRosto()) avisos.add(getString(R.string.missing_face))
        if (!sessionManager.temFotoAcessorios()) avisos.add(getString(R.string.missing_accessories))

        if (avisos.isNotEmpty()) {
            val msg = avisos.joinToString("\n") + "\n\nProsseguir mesmo assim?"
            AlertDialog.Builder(this).setTitle(R.string.warning).setMessage(msg)
                .setPositiveButton(R.string.proceed_anyway) { _, _ -> abrirTelaFinalizar() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else abrirTelaFinalizar()
    }

    private fun abrirTelaFinalizar() {
        startActivity(Intent(this, FinalizarActivity::class.java))
    }

    // ============= PERMISSÕES / CICLO =============

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera()
            else { Toast.makeText(this, R.string.camera_required, Toast.LENGTH_LONG).show(); finish() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        shutterSound.release()
        arquivoTemporario?.delete()
    }

    companion object {
        const val EXTRA_CONTINUAR_SIMULACAO = "continuar_simulacao"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}
