package com.radioterapia.ai.treatment

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.radioterapia.ai.session.SessionManager.Category
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.DestinoSmb
import com.radioterapia.ai.R
import com.radioterapia.ai.audit.AuditLogger
import com.radioterapia.ai.exif.ExifWatermark
import com.radioterapia.ai.pdf.PdfBuilder
import com.radioterapia.ai.security.CredentialStore
import com.radioterapia.ai.smb.SmbClient
import com.radioterapia.ai.ui.GridOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Adicionar foto durante o tratamento.
 *
 * Diferenças vs a captura normal:
 *   - Apenas categoria POSICIONAMENTO (não há rosto/etiqueta a refazer durante tratamento)
 *   - Após captura, usuário decide: descartar / salvar e enviar
 *   - Salvar e enviar:
 *     a. Aplica marca d'água (mesmo padrão da simulação)
 *     b. Salva localmente com nome `_Posicionamento_TRATAMENTO_<timestamp>.jpg`
 *     c. Envia para o servidor (todos os destinos ativos)
 *     d. Re-baixa todas as fotos da pasta para regerar o PDF da Folha de Posicionamento
 *     e. Envia novo PDF (sobrescreve antigo)
 *     f. Pergunta se quer imprimir nova versão
 *
 * Observação: NÃO mexe com a foto de rosto/etiqueta/acessórios — só adiciona posicionamento.
 */
class AddPhotoInTreatmentActivity : com.radioterapia.ai.BaseActivity() {

    /** UI cheia de câmera - sem toolbar da BaseActivity. */
    override fun mostrarToolbar(): Boolean = false

    private lateinit var config: AppConfig
    private lateinit var credentials: CredentialStore
    private lateinit var auditLogger: AuditLogger

    private var imageCapture: ImageCapture? = null
    private var cameraInfo: androidx.camera.core.Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private val shutterSound = MediaActionSound()

    private lateinit var viewFinder: PreviewView
    private lateinit var imgPreview: ImageView
    private lateinit var btnCapturar: android.widget.ImageButton
    private lateinit var btnSalvarEnviar: Button
    private lateinit var btnDescartar: Button
    private lateinit var btnDescartarRolo: Button
    private lateinit var layoutExistentes: android.widget.LinearLayout
    private lateinit var faixaExistentes: android.widget.LinearLayout
    private lateinit var btnGaleriaAp: Button
    private lateinit var btnSalvarRolo: Button
    /** Rolo local: fotos novas acumuladas; nada é salvo/enviado até "Salvar e adicionar". */
    private val roloFotos = mutableListOf<Pair<File, com.radioterapia.ai.session.SessionManager.Category>>()
    private lateinit var layoutPosFoto: LinearLayout
    private lateinit var seekZoom: SeekBar
    private lateinit var btnFlash: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var txtFlashLabel: TextView
    private lateinit var txtMuteLabel: TextView
    private lateinit var gridOverlay: GridOverlay
    private lateinit var txtProgresso: TextView
    private lateinit var txtInfo: TextView

    private var arquivoTemp: File? = null
    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var cliqueMudo: Boolean = false
    private var usarCameraFrontal = false

    // Dados recebidos da PatientViewerActivity
    private lateinit var nomePaciente: String
    private lateinit var prontuario: String
    private lateinit var nomePastaServidor: String
    private var numeroSimulacao: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_photo_treatment)

        nomePaciente = intent.getStringExtra(EXTRA_NOME) ?: ""
        prontuario = intent.getStringExtra(EXTRA_PRONTUARIO) ?: ""
        nomePastaServidor = intent.getStringExtra(EXTRA_PASTA) ?: ""
        numeroSimulacao = intent.getIntExtra(EXTRA_NUM_SIMULACAO, 1)

        config = AppConfig(this)
        credentials = CredentialStore(this)
        auditLogger = AuditLogger(this)

        viewFinder = findViewById(R.id.apViewFinder)
        viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER
        imgPreview = findViewById(R.id.apImgPreview)
        btnCapturar = findViewById(R.id.btnApCapturar)
        btnSalvarEnviar = findViewById(R.id.btnApSalvarEnviar)
        // Padrão unificado com o RT Sim: crop embutido + ícone girar + tabs
        apCropPreview = findViewById(R.id.apCropPreview)
        apBtnGirarVisor = findViewById(R.id.apBtnGirarVisor)
        apBtnGirarVisor.setOnClickListener { apCropPreview.girar(); seekZoom.progress = 0 }
        apCropPreview.onZoomMudou = { p -> seekZoom.progress = p }
        configurarTabsAp()
        selecionarCategoriaAp(com.radioterapia.ai.session.SessionManager.Category.POSITIONING)
        com.radioterapia.ai.util.UiText.uniformizar(
            findViewById<View>(R.id.tabFace).findViewById(R.id.tabTitle),
            findViewById<View>(R.id.tabLabel).findViewById(R.id.tabTitle),
            findViewById<View>(R.id.tabPositioning).findViewById(R.id.tabTitle),
            findViewById<View>(R.id.tabAccessories).findViewById(R.id.tabTitle),
            findViewById<View>(R.id.tabDocs).findViewById(R.id.tabTitle))
        com.radioterapia.ai.util.UiText.uniformizar(
            findViewById(R.id.btnApDescartar), findViewById(R.id.btnApSalvarEnviar))

        // Celular deitado (altura baixa): shutter e barra encolhem para sobrar
        // área de preview — o círculo aparece inteiro em qualquer tela.
        if (resources.configuration.screenHeightDp < 480) {
            val d56 = (56 * resources.displayMetrics.density).toInt()
            btnCapturar.layoutParams = btnCapturar.layoutParams.apply {
                width = d56; height = d56
            }
            ((btnCapturar.parent as? View)?.parent as? android.widget.LinearLayout)
                ?.minimumHeight = (64 * resources.displayMetrics.density).toInt()
        }
        btnDescartar = findViewById(R.id.btnApDescartar)
        layoutExistentes = findViewById(R.id.layoutApExistentes)
        faixaExistentes = findViewById(R.id.faixaApExistentes)
        btnGaleriaAp = findViewById(R.id.btnApGaleria)
        btnDescartarRolo = findViewById(R.id.btnApDescartarRolo)
        btnSalvarRolo = findViewById(R.id.btnApSalvarRolo)
        com.radioterapia.ai.util.UiText.uniformizar(btnGaleriaAp, btnDescartarRolo, btnSalvarRolo)

        layoutPosFoto = findViewById(R.id.layoutApPosFoto)
        seekZoom = findViewById(R.id.apSeekZoom)
        btnFlash = findViewById(R.id.apBtnFlash)
        val btnSwitch = findViewById<android.widget.ImageButton>(R.id.apBtnSwitchCam)
        val txtSwitch = findViewById<TextView>(R.id.apTxtSwitchCamLabel)
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
        btnMute = findViewById(R.id.apBtnMute)
        txtFlashLabel = findViewById(R.id.apTxtFlashLabel)
        txtMuteLabel = findViewById(R.id.apTxtMuteLabel)
        gridOverlay = findViewById(R.id.apGridOverlay)
        txtProgresso = findViewById(R.id.txtApProgresso)
        txtInfo = findViewById(R.id.txtApInfo)

        configurarMudoClique()

        txtInfo.text = "Paciente: $nomePaciente\n" +
            "Pasta no servidor: $nomePastaServidor"

        cameraExecutor = Executors.newSingleThreadExecutor()
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERM)

        configurarZoomEFlash()
        gridOverlay.visibility = if (config.cameraGrid) View.VISIBLE else View.GONE

        btnCapturar.setOnClickListener { tirarFoto() }
        btnSalvarEnviar.setOnClickListener { adicionarAoCarrossel() }
        btnDescartar.setOnClickListener { descartar() }
        btnGaleriaAp.setOnClickListener { abrirGaleriaAp() }
        // Fotos ja gravadas nesta simulacao, para poder refazer uma delas.
        carregarFotosExistentes()
        btnDescartarRolo.setOnClickListener { confirmarDescartarRolo() }
        btnSalvarRolo.setOnClickListener {
            if (roloFotos.isEmpty()) {
                android.widget.Toast.makeText(this, R.string.ap_roll_empty,
                    android.widget.Toast.LENGTH_SHORT).show()
            } else pedirObservacaoESalvarRolo()
        }
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { confirmarDescartarRolo() }
            })
    }

    // A seta ← do cabeçalho seguia direto para finish(), descartando em silêncio
    // as fotos já capturadas no rolo. Agora passa pela MESMA confirmação do back.
    override fun onSupportNavigateUp(): Boolean { confirmarDescartarRolo(); return true }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        shutterSound.release()
        arquivoTemp?.delete()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERM) {
            if (allPermissionsGranted()) startCamera()
            else { Toast.makeText(this, R.string.camera_required, Toast.LENGTH_LONG).show(); finish() }
        }
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val cp = f.get()
            val rotacao = rotacaoAtualDoDisplay()

            val preview = Preview.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                .build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .setTargetRotation(rotacao)
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                .build()
            try {
                cp.unbindAll()
                cameraInfo = cp.bindToLifecycle(this,
                    if (usarCameraFrontal) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture)
                seekZoom.progress = 0
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.err_camera, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun configurarZoomEFlash() {
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val zoomState = cameraInfo?.cameraInfo?.zoomState?.value ?: return
                val ratio = zoomState.minZoomRatio +
                    (zoomState.maxZoomRatio - zoomState.minZoomRatio) * progress / 100f
                cameraInfo?.cameraControl?.setZoomRatio(ratio)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val gestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoomState = cameraInfo?.cameraInfo?.zoomState?.value ?: return false
                val current = zoomState.zoomRatio
                val novo = (current * detector.scaleFactor)
                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                cameraInfo?.cameraControl?.setZoomRatio(novo)
                val pct = ((novo - zoomState.minZoomRatio) * 100f /
                    (zoomState.maxZoomRatio - zoomState.minZoomRatio)).toInt()
                seekZoom.progress = pct.coerceIn(0, 100)
                return true
            }
        })
        viewFinder.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (!gestureDetector.isInProgress && event.action == MotionEvent.ACTION_UP) {
                val factory = viewFinder.meteringPointFactory
                val ponto = factory.createPoint(event.x, event.y)
                cameraInfo?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(ponto).build())
            }
            true
        }

        btnFlash.setOnClickListener {
            flashMode = if (flashMode == ImageCapture.FLASH_MODE_ON) ImageCapture.FLASH_MODE_OFF
                        else ImageCapture.FLASH_MODE_ON
            imageCapture?.flashMode = flashMode
            atualizarIconeFlash()
        }
        atualizarIconeFlash()
    }

    private fun atualizarIconeFlash() {
        val flashOn = flashMode == ImageCapture.FLASH_MODE_ON
        btnFlash.setColorFilter(if (flashOn) 0xFFFFD54F.toInt() else 0xFF9E9E9E.toInt())
        txtFlashLabel.setTextColor(if (flashOn) 0xFFFFD54F.toInt() else 0xFFCCCCCC.toInt())
        txtFlashLabel.text = if (flashMode == ImageCapture.FLASH_MODE_ON)
            getString(R.string.flash_on) else getString(R.string.flash_off)
    }

    // Som do clique liga/desliga (toggle independente)
    private fun configurarMudoClique() {
        atualizarVisualMudo()
        btnMute.setOnClickListener {
            cliqueMudo = !cliqueMudo
            atualizarVisualMudo()
        }
    }

    private fun atualizarVisualMudo() {
        btnMute.setColorFilter(if (!cliqueMudo) 0xFFFFD54F.toInt() else 0xFF9E9E9E.toInt())
        txtMuteLabel.setTextColor(if (!cliqueMudo) 0xFFFFD54F.toInt() else 0xFFCCCCCC.toInt())
        txtMuteLabel.text = if (cliqueMudo) getString(R.string.mute_off)
                            else getString(R.string.mute_on)
    }

    private val pinchCameraDetector by lazy {
        android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val zoomState = cameraInfo?.cameraInfo?.zoomState?.value ?: return false
                    val novo = (zoomState.zoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    cameraInfo?.cameraControl?.setZoomRatio(novo)
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

    private fun tirarFoto() {
        if (modoDocumento() && !scannerDocsFalhou) { iniciarScannerDocs(); return }
        renderTabsAp(bloqueadas = true)
        val ic = imageCapture ?: return
        btnCapturar.isEnabled = false
        val arq = File(cacheDir, "treat_temp_${System.currentTimeMillis()}.jpg")
        arquivoTemp = arq
        if (!cliqueMudo) shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        ic.flashMode = flashMode

        // Rotação livre: orientação ATUAL do display no instante do disparo.

        ic.targetRotation = rotacaoAtualDoDisplay()


        ic.takePicture(ImageCapture.OutputFileOptions.Builder(arq).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(baseContext, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
                    btnCapturar.isEnabled = true
                }
                override fun onImageSaved(o: ImageCapture.OutputFileResults) {
                    mostrarPreview(arq)
                }
            })
    }

    private lateinit var apCropPreview: com.radioterapia.ai.crop.CropImageView
    private lateinit var apBtnGirarVisor: android.widget.ImageButton
    private var categoriaAp = com.radioterapia.ai.session.SessionManager.Category.POSITIONING

    private fun modoDocumento(): Boolean = categoriaAp == com.radioterapia.ai.session.SessionManager.Category.DOCUMENTS

    /**
     * Tabs no padrão RT Sim. TODAS habilitadas; Impressos usa o scanner.
     *
     * ROSTO E ETIQUETA ERAM APAGADAS, por a regra de que não se refaz
     * identificação depois da simulação. A regra caiu diante do caso real: a
     * foto do rosto saiu de perto demais, em retrato, e ao girar o rosto não
     * coube no recorte 16:9 — sem como refazer, a ficha ficou com uma foto
     * inservível de forma permanente, e o único conserto era apagar o arquivo
     * pelo gerenciador do tablet.
     *
     * Refazer não destrói nada: rosto e etiqueta são categorias ÚNICAS, e
     * substituir arquiva a anterior em vez de apagá-la (ver FotosArquivadas).
     * O que era protegido pela aba morta continua protegido pelo arquivamento,
     * e agora com volta.
     */
    private fun configurarTabsAp() {
        val mapa = mapOf(
            com.radioterapia.ai.session.SessionManager.Category.FACE to R.id.tabFace,
            com.radioterapia.ai.session.SessionManager.Category.LABEL to R.id.tabLabel,
            com.radioterapia.ai.session.SessionManager.Category.POSITIONING to R.id.tabPositioning,
            com.radioterapia.ai.session.SessionManager.Category.ACCESSORIES to R.id.tabAccessories,
            com.radioterapia.ai.session.SessionManager.Category.DOCUMENTS to R.id.tabDocs)
        val titulos = mapOf(
            com.radioterapia.ai.session.SessionManager.Category.FACE to R.string.cat_face,
            com.radioterapia.ai.session.SessionManager.Category.LABEL to R.string.cat_label,
            com.radioterapia.ai.session.SessionManager.Category.POSITIONING to R.string.cat_positioning,
            com.radioterapia.ai.session.SessionManager.Category.ACCESSORIES to R.string.cat_accessories,
            com.radioterapia.ai.session.SessionManager.Category.DOCUMENTS to R.string.cat_documents)
        mapa.forEach { (cat, id) ->
            val tab = findViewById<View>(id)
            // Sem !!: se um dia o mapa de títulos divergir do de abas, a aba fica
            // sem rótulo em vez de derrubar a tela.
            titulos[cat]?.let { tab.findViewById<TextView>(R.id.tabTitle).setText(it) }
            tab.setOnClickListener { selecionarCategoriaAp(cat) }
        }
    }

    private fun selecionarCategoriaAp(cat: com.radioterapia.ai.session.SessionManager.Category) {
        // Categoria ÚNICA já gravada: avisa que a nova entra no lugar e que a
        // anterior vai para as arquivadas. O aviso é o que separa "refazer
        // porque a foto ficou ruim" de "trocar sem perceber que havia uma".
        if (cat.unico && existeFotoDaCategoria(cat) && categoriaAp != cat) {
            val rotulo = getString(when (cat) {
                com.radioterapia.ai.session.SessionManager.Category.FACE -> R.string.cat_face
                else -> R.string.cat_label
            })
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ap_refazer_unica, rotulo))
                .setMessage(getString(R.string.ap_refazer_msg, rotulo))
                .setPositiveButton(R.string.confirm) { _, _ -> aplicarCategoriaAp(cat) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        aplicarCategoriaAp(cat)
    }

    /** Já existe foto gravada desta categoria nesta simulação? */
    private fun existeFotoDaCategoria(cat: com.radioterapia.ai.session.SessionManager.Category): Boolean = try {
        fotosExistentes.any { arq ->
            arq.name.lowercase().contains(cat.nomeArquivoBase.lowercase().removePrefix("_"))
        }
    } catch (_: Exception) { false }

    private fun aplicarCategoriaAp(cat: com.radioterapia.ai.session.SessionManager.Category) {
        categoriaAp = cat
        renderTabsAp(bloqueadas = false)
        if (cat == com.radioterapia.ai.session.SessionManager.Category.DOCUMENTS)
            android.widget.Toast.makeText(this, R.string.docs_not_in_pdf,
                android.widget.Toast.LENGTH_LONG).show()
    }

    /** Quantas fotos novas (desta sessão de adição) já entraram na categoria. */
    private fun fotosNovasPorCategoria(cat: com.radioterapia.ai.session.SessionManager.Category): Int {
        return try { roloFotos.count { it.second == cat } } catch (_: Exception) { 0 }
    }

    private fun renderTabsAp(bloqueadas: Boolean) {
        val mapa = mapOf(
            com.radioterapia.ai.session.SessionManager.Category.FACE to R.id.tabFace,
            com.radioterapia.ai.session.SessionManager.Category.LABEL to R.id.tabLabel,
            com.radioterapia.ai.session.SessionManager.Category.POSITIONING to R.id.tabPositioning,
            com.radioterapia.ai.session.SessionManager.Category.ACCESSORIES to R.id.tabAccessories,
            com.radioterapia.ai.session.SessionManager.Category.DOCUMENTS to R.id.tabDocs)
        mapa.forEach { (cat, id) ->
            val tab = findViewById<View>(id)
            tab.setBackgroundColor(
                if (cat == categoriaAp) 0xFF1565C0.toInt() else 0xFF263238.toInt())
            tab.isEnabled = !bloqueadas
            tab.alpha = if (bloqueadas && cat != categoriaAp) 0.35f else 1f
            // Contador laranja das fotos já capturadas nesta sessão de adição.
            val cnt = tab.findViewById<android.widget.TextView>(R.id.tabCount)
            val n = fotosNovasPorCategoria(cat)
            val mostra = (cat == Category.POSITIONING ||
                cat == Category.ACCESSORIES || cat == Category.DOCUMENTS) && n > 0
            cnt?.visibility = if (mostra) View.VISIBLE else View.GONE
            cnt?.text = n.toString()
        }
    }

    private fun ligarSeekACameraAp() {
        seekZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) cameraInfo?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
    }

    private fun ligarSeekAoCropAp() {
        seekZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) apCropPreview.setZoomFracao(progress)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
    }


    // ===== Scanner de impressos (ML Kit) no tratamento =====
    private var scannerDocsFalhou = false
    private val scannerDocsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val res = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
            .fromActivityResultIntent(result.data) ?: return@registerForActivityResult
        val paginas = res.pages ?: emptyList()
        if (paginas.isEmpty()) return@registerForActivityResult
        txtProgresso.visibility = View.VISIBLE
        txtProgresso.text = getString(R.string.saving_locally)
        CoroutineScope(Dispatchers.Main).launch {
            var n = 0
            withContext(Dispatchers.IO) {
                val nomeNorm = normalizarNome(nomePaciente)
                val prefArq = nomeNorm.trim().uppercase().replace(Regex("\\s+"), " ").replace(" ", "_")
                val tagSim = if (numeroSimulacao > 1) "_NOVASIM${numeroSimulacao - 1}" else ""
                for (p in paginas) {
                    try {
                        val tmp = File(cacheDir, "doc_${System.currentTimeMillis()}_${n}.jpg")
                        contentResolver.openInputStream(p.imageUri)?.use { inp ->
                            java.io.FileOutputStream(tmp).use { out -> inp.copyTo(out) }
                        }
                        if (tmp.exists() && tmp.length() > 0) {
                            roloFotos.add(tmp to com.radioterapia.ai.session.SessionManager.Category.DOCUMENTS)
                            n++
                        }
                    } catch (_: Exception) {}
                }
            }
            txtProgresso.visibility = View.GONE
            atualizarBotaoRolo()
            if (n > 0) android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                getString(R.string.ap_roll_added, roloFotos.size),
                android.widget.Toast.LENGTH_LONG).show()
            // Fica na tela: o usuário pode escanear mais; salva tudo no fim.
        }
    }

    private fun iniciarScannerDocs() {
        try {
            val opts = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setResultFormats(
                    com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(
                    com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(opts)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    scannerDocsLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener {
                    scannerDocsFalhou = true
                    android.widget.Toast.makeText(this, R.string.doc_scan_fail_fallback, android.widget.Toast.LENGTH_LONG).show()
                    tirarFoto()
                }
        } catch (_: Throwable) {
            scannerDocsFalhou = true
            android.widget.Toast.makeText(this, R.string.doc_scan_fail_fallback, android.widget.Toast.LENGTH_LONG).show()
            tirarFoto()
        }
    }


    private fun mostrarPreview(arq: File) {
        viewFinder.visibility = View.GONE
        gridOverlay.visibility = View.GONE
        btnCapturar.visibility = View.GONE
        if (modoDocumento()) {
            // Impresso (fallback câmera): sem moldura fixa — doc pode ser retrato.
            imgPreview.setImageBitmap(com.radioterapia.ai.util.ImagemUtils.decodificarComExif(arq))
            imgPreview.visibility = View.VISIBLE
            apCropPreview.visibility = View.GONE
            apBtnGirarVisor.visibility = View.GONE
        } else {
            // ETAPA ÚNICA: moldura 16:9 + regra dos terços + pinça + ⟳ girar.
            val bm = com.radioterapia.ai.util.ImagemUtils.decodificarComExif(arq)
            if (bm != null) { apCropPreview.definirBitmap(bm); apCropPreview.visibility = View.VISIBLE }
            imgPreview.visibility = View.GONE
            apBtnGirarVisor.visibility = View.VISIBLE
            ligarSeekAoCropAp()
            seekZoom.progress = 0
        }
        renderTabsAp(bloqueadas = true)
        // Confirmação da foto: só Descartar/Adicionar têm função aqui —
        // a barra do rolo some para não confundir (e libera altura no celular).
        findViewById<View>(R.id.layoutApRolo).visibility = View.GONE
        layoutPosFoto.visibility = View.VISIBLE
        btnCapturar.isEnabled = true
    }

    private fun voltarParaCamera() {
        imgPreview.setImageBitmap(null)
        viewFinder.visibility = View.VISIBLE
        if (config.cameraGrid) gridOverlay.visibility = View.VISIBLE
        imgPreview.visibility = View.GONE
        apCropPreview.visibility = View.GONE
        apBtnGirarVisor.visibility = View.GONE
        btnCapturar.visibility = View.VISIBLE
        layoutPosFoto.visibility = View.GONE
        findViewById<View>(R.id.layoutApRolo).visibility = View.VISIBLE
        renderTabsAp(bloqueadas = false)
        ligarSeekACameraAp()
        seekZoom.progress = 0
    }

    private fun descartar() {
        arquivoTemp?.delete()
        arquivoTemp = null
        voltarParaCamera()
    }

    /** ETAPA ÚNICA: exporta exatamente o recorte da moldura (girado/zoom),
     *  salva e envia a FOTO, e pergunta — com as observações editáveis — se o
     *  usuário adiciona mais fotos ou finaliza. O PDF é regenerado UMA vez,
     *  somente ao Finalizar. */
    /** Adiciona a captura ao ROLO local (nada é salvo/enviado ainda). */
    private fun adicionarAoCarrossel() {
        val arq = arquivoTemp ?: return

        if (!modoDocumento()) {
            // O que se vê é o que vai: grava o recorte da moldura no arquivo.
            try {
                val rec = apCropPreview.recortar()
                if (rec != null) {
                    java.io.FileOutputStream(arq).use { out ->
                        rec.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    rec.recycle()
                }
            } catch (_: Exception) {}
        }

        val ehAcessorio = categoriaAp == com.radioterapia.ai.session.SessionManager.Category.ACCESSORIES
        val tagExifBase = when {
            modoDocumento() -> "DOCUMENTO TRATAMENTO"
            ehAcessorio -> "ACESSORIO TRATAMENTO"
            else -> "POSICIONAMENTO TRATAMENTO"
        }
        val tag = if (numeroSimulacao > 1) "$tagExifBase NOVA SIMULACAO ${numeroSimulacao - 1}" else tagExifBase
        ExifWatermark.aplicar(this, arq, nomePaciente, "TRATAMENTO_${System.currentTimeMillis()}", tag)

        try {
            val tmp = File(cacheDir, "rolo_${System.currentTimeMillis()}_${roloFotos.size}.jpg")
            arq.copyTo(tmp, overwrite = true)
            roloFotos.add(tmp to categoriaAp)
        } catch (_: Exception) {}

        layoutPosFoto.visibility = View.GONE
        arquivoTemp = null
        atualizarBotaoRolo()
        android.widget.Toast.makeText(this,
            getString(R.string.ap_roll_added, roloFotos.size),
            android.widget.Toast.LENGTH_SHORT).show()
        voltarParaCamera()
    }

    // ============= ADICIONAR DA GALERIA =============

    /**
     * Importa fotos ja existentes no aparelho para o ROLO desta rodada.
     *
     * Entram no rolo, e nao direto na pasta do paciente, de proposito: o rolo e
     * o ponto onde o tecnico ainda pode descartar tudo antes de gravar. Foto
     * importada por engano — a errada da galeria, a de outro paciente — sai com
     * "Descartar novas fotos", igual a qualquer foto capturada aqui.
     *
     * Vao para a CATEGORIA ATIVA, a mesma que a captura usaria.
     *
     * PASSAM PELO RECORTE 16:9, uma a uma, como a foto da camera. A foto da
     * galeria vem no enquadramento do celular de quem a tirou; entrando crua, a
     * ficha impressa misturava foto em pe com foto deitada e o grid ficava
     * desarmonico.
     */
    private val escolherDaGaleriaAp = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> aoEscolherDaGaleriaAp(uris.orEmpty()) }

    /** De ONDE a foto vem. Mesmas tres origens da tela de simulacao. */
    private fun abrirGaleriaAp() {
        val opcoes = arrayOf(
            getString(R.string.origem_rolo),
            getString(R.string.origem_arquivadas),
            getString(R.string.origem_procurar))
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.origem_titulo)
            .setItems(opcoes) { _, i ->
                when (i) {
                    0 -> abrirRoloAp()
                    1 -> abrirArquivadasAp()
                    else -> abrirProcurarAp()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun abrirRoloAp() {
        try {
            val i = Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            escolherDoRoloAp.launch(i)
        } catch (_: Exception) {
            abrirProcurarAp()
        }
    }

    private fun abrirProcurarAp() {
        try {
            escolherDaGaleriaAp.launch(arrayOf(com.radioterapia.ai.gallery.GaleriaImport.MIME))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.gallery_fail,
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val escolherDoRoloAp = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val dados = res.data
        val uris = mutableListOf<android.net.Uri>()
        // ACTION_PICK devolve de dois jeitos: clipData para varias, data para uma
        // so. Ler so um perde metade dos casos, e uma foto e o caso comum.
        dados?.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
        if (uris.isEmpty()) dados?.data?.let { uris.add(it) }
        aoEscolherDaGaleriaAp(uris)
    }

    /**
     * Fotos desta simulacao que foram arquivadas, para devolver ao rolo.
     *
     * Volta para o ROLO, e nao direto para a pasta: a foto devolvida passa pelo
     * mesmo "Finalizar" das novas, e ate la o tecnico ainda pode desistir.
     */
    private fun abrirArquivadasAp() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val base = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { acharOuCriarPastaPacienteFile() } catch (_: Exception) { null }
            }
            if (isFinishing || isDestroyed) return@launch
            if (base == null) {
                android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                    R.string.arq_vazio, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            com.radioterapia.ai.ui.ArquivadasDialog.mostrar(
                this@AddPhotoInTreatmentActivity, listOf(base)
            ) { arq ->
                val tmp = File(cacheDir, "rolo_arq_${System.currentTimeMillis()}.jpg")
                if (com.radioterapia.ai.util.FotosArquivadas.copiarParaTemp(arq, tmp)) {
                    roloFotos.add(tmp to categoriaAp)
                    atualizarBotaoRolo()
                    android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                        R.string.arq_restaurada, android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    private fun aoEscolherDaGaleriaAp(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.gallery_none_picked,
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cat = categoriaAp
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.radioterapia.ai.gallery.GaleriaImport.copiarParaTemp(
                    this@AddPhotoInTreatmentActivity, uris, cacheDir, "rolo_galeria")
            }
            if (isFinishing || isDestroyed) return@launch
            if (res.falhas > 0) {
                android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                    getString(R.string.gallery_fail, res.falhas),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            filaRecorteAp.clear()
            filaRecorteAp.addAll(res.arquivos)
            categoriaDaImportacaoAp = cat
            importadasAp = 0
            proximoRecorteAp()
        }
    }

    private val filaRecorteAp = ArrayDeque<java.io.File>()
    private var categoriaDaImportacaoAp =
        com.radioterapia.ai.session.SessionManager.Category.POSITIONING
    private var importadasAp = 0

    /** Manda a proxima foto importada para o recorte 16:9. Uma de cada vez,
     *  porque a selecao e multipla e cada foto precisa do proprio enquadramento. */
    private fun proximoRecorteAp() {
        val arq = filaRecorteAp.removeFirstOrNull()
        if (arq == null) {
            if (importadasAp > 0) {
                atualizarBotaoRolo()
                android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                    getString(R.string.gallery_added, importadasAp),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            recortarDaGaleriaAp.launch(
                Intent(this, com.radioterapia.ai.crop.CropActivity::class.java).apply {
                    putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_PATH, arq.absolutePath)
                    putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_ARQUIVO_TMP, arq.absolutePath)
                })
        } catch (_: Exception) {
            // Sem tela de recorte, a foto entra como veio: melhor importada
            // torta que perdida.
            roloFotos.add(arq to categoriaDaImportacaoAp)
            importadasAp++
            proximoRecorteAp()
        }
    }

    private val recortarDaGaleriaAp = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val caminho = res.data?.getStringExtra(
            com.radioterapia.ai.crop.CropActivity.EXTRA_ARQUIVO_TMP)
        if (res.resultCode == android.app.Activity.RESULT_OK && caminho != null) {
            roloFotos.add(java.io.File(caminho) to categoriaDaImportacaoAp)
            importadasAp++
        } else {
            // Cancelou o recorte desta foto: ela NAO entra no rolo.
            caminho?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
        }
        proximoRecorteAp()
    }

    // ============= FOTOS JA GRAVADAS: VER E EXCLUIR =============

    /** Fotos desta simulacao que ja estao na pasta do paciente. */
    private var fotosExistentes: List<java.io.File> = emptyList()

    /**
     * Carrega e desenha as fotos ja gravadas nesta simulacao.
     *
     * POR QUE EXISTE: o tecnologo fez a foto de rosto, finalizou a simulacao e
     * depois quis refazer. Nao conseguia — esta tela so ADICIONAVA, e a
     * categoria "rosto" e unica, entao a nova foto substituiria a antiga apenas
     * numa simulacao em andamento, nunca numa ja gravada. Refazer exigia apagar
     * o arquivo pelo gerenciador de arquivos do tablet.
     */
    private fun carregarFotosExistentes() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val achadas = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val sims = TreatmentPhotoFetcher(this@AddPhotoInTreatmentActivity)
                        .buscarSimulacoes(nomePaciente)
                    val sim = sims.find { it.numeroSimulacao == numeroSimulacao }
                        ?: sims.maxByOrNull { it.timestampPrincipal }
                    sim?.fotos?.map { it.arquivoLocal }?.sortedBy { it.name }.orEmpty()
                } catch (_: Exception) { emptyList() }
            }
            if (isFinishing || isDestroyed) return@launch
            fotosExistentes = achadas
            desenharFaixaExistentes()
        }
    }

    private fun desenharFaixaExistentes() {
        faixaExistentes.removeAllViews()
        if (fotosExistentes.isEmpty()) {
            layoutExistentes.visibility = View.GONE
            return
        }
        layoutExistentes.visibility = View.VISIBLE
        val lado = (76 * resources.displayMetrics.density).toInt()
        fotosExistentes.forEach { arq ->
            val img = android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(lado, lado).apply {
                    marginEnd = (6 * resources.displayMetrics.density).toInt()
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                contentDescription = arq.name
                setOnClickListener { acoesDaFoto(arq) }
            }
            // Miniatura subsampleada: a faixa pode ter dezenas de fotos, e
            // carregar cada JPEG inteiro estoura a memoria do tablet.
            try {
                val op = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                img.setImageBitmap(android.graphics.BitmapFactory.decodeFile(arq.absolutePath, op))
            } catch (_: Exception) {
                img.setImageResource(R.drawable.bg_sem_foto)
            }
            faixaExistentes.addView(img)
        }
    }

    /**
     * Menu da foto ja gravada: girar/recortar, ARQUIVAR ou excluir.
     *
     * Arquivar fica ANTES de excluir de proposito. Na maioria das vezes o que o
     * tecnico quer e tirar a foto da ficha impressa — enquadramento ruim, foto
     * repetida, acessorio que mudou — e nao destruir o registro do atendimento.
     * Com so "excluir" a mao, a unica saida para "sai da ficha" era apagar.
     */
    private fun acoesDaFoto(arq: java.io.File) {
        val opcoes = arrayOf(
            getString(R.string.photo_edit_rotate),
            getString(R.string.arq_arquivar),
            getString(R.string.rub_excluir))
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.photo_existing_title)
            .setItems(opcoes) { _, i ->
                when (i) {
                    0 -> editarFotoSalva(arq)
                    1 -> arquivarFotoSalva(arq)
                    else -> confirmarExcluirFoto(arq)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Tira a foto da ficha SEM apaga-la, e regenera o PDF.
     *
     * Regerar e o mesmo motivo da exclusao: sem isso o PDF na pasta continuaria
     * mostrando a foto que acabou de sair, e a ficha impressa deixaria de
     * corresponder ao que esta em disco.
     */
    private fun arquivarFotoSalva(arq: java.io.File) {
        txtProgresso.visibility = View.VISIBLE
        txtProgresso.text = getString(R.string.ap_finishing)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.radioterapia.ai.util.FotosArquivadas.arquivar(arq) != null
            }
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                txtProgresso.visibility = View.GONE
                android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                    R.string.arq_arquivar_falha, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            auditLogger.registrar(
                AuditLogger.Tipo.EDIT, "Foto arquivada (saiu da ficha, mantida em disco)",
                mapOf("paciente" to nomePaciente, "arquivo" to arq.name,
                      "simulacao" to numeroSimulacao))
            regerarFichaAposMudanca()
            android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                R.string.arq_arquivada_ok, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Regera a ficha e regrava o PDF na pasta do paciente.
     *
     * Extraida porque tres caminhos precisam dela — excluir, arquivar e
     * reenquadrar — e as tres copias ja tinham comecado a divergir no nome do
     * arquivo gerado.
     */
    private suspend fun regerarFichaAposMudanca() {
        val pdf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { regerarPdf(observacoesAtuais()) } catch (_: Exception) { null }
        }
        if (pdf != null) {
            val ts = SimpleDateFormat("dd-MMM-yyyy_HH-mm-ss", Locale("pt", "BR"))
                .format(Date()).uppercase()
            val pref = normalizarNome(nomePaciente).trim().uppercase()
                .replace(Regex("\\s+"), " ").replace(" ", "_")
            val tag = if (numeroSimulacao > 1) "_NOVASIM${numeroSimulacao - 1}" else ""
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    salvarPdfNaPastaPaciente(pdf, "${pref}_FOLHA_SIMULACAO${tag}_$ts.pdf")
                } catch (_: Exception) {}
            }
        }
        txtProgresso.visibility = View.GONE
        carregarFotosExistentes()
    }

    /**
     * Reabre a foto na tela de recorte, para GIRAR e reenquadrar.
     *
     * O caso: o tecnico fotografou o paciente deitado sem a tela girar, e a foto
     * ficou de lado. Antes nao havia conserto — a foto ja estava gravada e
     * recortada, e refazer exigia o paciente de volta.
     *
     * PARTE DO ORIGINAL, quando ele existe. O arquivo gravado ja foi recortado
     * em 16:9; girar um recorte e recortar de novo tiraria pedaco de pedaco, e a
     * foto sairia pequena. O "_ORIGINAL" e o quadro cheio da captura, entao girar
     * a partir dele devolve o enquadramento inteiro para reescolher.
     */
    private fun editarFotoSalva(arq: java.io.File) {
        val original = java.io.File(arq.parentFile,
            arq.nameWithoutExtension + com.radioterapia.ai.session.SessionManager.SUFIXO_ORIGINAL)
        val fonte = if (original.exists() && original.length() > 0) original else arq
        try {
            // Trabalha numa COPIA: se o tecnico cancelar no meio, a foto gravada
            // fica intacta. Sobrescrever direto arriscaria perder a original.
            val tmp = java.io.File(cacheDir, "editar_${System.currentTimeMillis()}.jpg")
            fonte.copyTo(tmp, overwrite = true)
            fotoSendoEditada = arq
            recortarFotoSalva.launch(
                Intent(this, com.radioterapia.ai.crop.CropActivity::class.java).apply {
                    putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_PATH, tmp.absolutePath)
                    putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_ARQUIVO_TMP, tmp.absolutePath)
                })
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.photo_delete_fail,
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var fotoSendoEditada: java.io.File? = null

    private val recortarFotoSalva = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val tmpPath = res.data?.getStringExtra(
            com.radioterapia.ai.crop.CropActivity.EXTRA_ARQUIVO_TMP)
        val alvo = fotoSendoEditada
        fotoSendoEditada = null
        if (res.resultCode != android.app.Activity.RESULT_OK || tmpPath == null || alvo == null) {
            tmpPath?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
            return@registerForActivityResult
        }
        substituirFotoSalva(alvo, java.io.File(tmpPath))
    }

    /** Grava o novo enquadramento por cima e regenera a ficha. */
    private fun substituirFotoSalva(alvo: java.io.File, novo: java.io.File) {
        txtProgresso.visibility = View.VISIBLE
        txtProgresso.text = getString(R.string.ap_finishing)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    novo.copyTo(alvo, overwrite = true)
                    novo.delete()
                    // O EXIF e reescrito para a foto editada continuar
                    // rastreavel ao paciente e a simulacao.
                    try {
                        ExifWatermark.aplicar(this@AddPhotoInTreatmentActivity, alvo,
                            nomePaciente, "EDICAO_${System.currentTimeMillis()}",
                            "REENQUADRADA")
                    } catch (_: Exception) {}
                    true
                } catch (_: Exception) { false }
            }
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                txtProgresso.visibility = View.GONE
                android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                    R.string.photo_delete_fail, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            auditLogger.registrar(
                AuditLogger.Tipo.EDIT, "Foto reenquadrada em simulacao finalizada",
                mapOf("paciente" to nomePaciente, "arquivo" to alvo.name,
                      "simulacao" to numeroSimulacao))
            regerarFichaAposMudanca()
            android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                R.string.photo_edited, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmarExcluirFoto(arq: java.io.File) {
        val msg = StringBuilder(getString(R.string.photo_delete_warn))
        // Aviso extra quando e a UNICA da categoria: apagar a unica foto de
        // rosto deixa a ficha sem o rosto, e quem esta refazendo quer justamente
        // substituir — nao ficar sem.
        val tipo = arq.name.uppercase()
        val mesmoTipo = fotosExistentes.count { outra ->
            listOf("_ROSTO", "_ETIQUETA", "_POSICIONAMENTO", "_ACESSORIOS", "_DOC")
                .firstOrNull { tipo.contains(it) }
                ?.let { outra.name.uppercase().contains(it) } ?: false
        }
        if (mesmoTipo <= 1) msg.append("\n\n").append(getString(R.string.photo_delete_last))

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.photo_delete_q)
            .setMessage(msg.toString())
            .setPositiveButton(R.string.rub_excluir) { _, _ -> excluirFoto(arq) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Apaga a foto e REGENERA a ficha.
     *
     * Regenerar nao e detalhe: sem isso o PDF na pasta continuaria mostrando a
     * foto que acabou de ser apagada, e a ficha impressa deixaria de
     * corresponder ao que esta em disco — que e achado de auditoria, nao bug.
     */
    private fun excluirFoto(arq: java.io.File) {
        txtProgresso.visibility = View.VISIBLE
        txtProgresso.text = getString(R.string.ap_finishing)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val apagou = !arq.exists() || arq.delete()
                    // O "_ORIGINAL" e o quadro cheio da mesma foto: manter um
                    // sem o outro deixaria a pasta com um original orfao, que
                    // o FileSync levaria para o servidor sem par.
                    val orig = java.io.File(arq.parentFile,
                        arq.nameWithoutExtension + "_ORIGINAL.jpg")
                    if (orig.exists()) orig.delete()
                    apagou
                } catch (_: Exception) { false }
            }
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                txtProgresso.visibility = View.GONE
                android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                    R.string.photo_delete_fail, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            auditLogger.registrar(
                AuditLogger.Tipo.EDIT, "Foto excluida de simulacao finalizada",
                mapOf("paciente" to nomePaciente, "arquivo" to arq.name,
                      "simulacao" to numeroSimulacao))
            regerarFichaAposMudanca()
            android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                R.string.photo_deleted, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Observacao ja gravada nesta simulacao, para a ficha regerada nao perde-la. */
    private fun observacoesAtuais(): String = try {
        val pasta = com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
            this, nomePaciente, numeroSimulacao, nomePastaServidor, prontuario)
        com.radioterapia.ai.util.ObsStore.ler(pasta, numeroSimulacao)
    } catch (_: Exception) { "" }

    private fun atualizarBotaoRolo() {
        btnSalvarRolo.text = if (roloFotos.isEmpty())
            getString(R.string.ap_save_roll_zero)
        else getString(R.string.ap_save_roll, roloFotos.size)
    }

    private fun confirmarDescartarRolo() {
        if (roloFotos.isEmpty()) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.ap_discard_roll)
            .setMessage(getString(R.string.ap_discard_roll_confirm, roloFotos.size))
            .setPositiveButton(R.string.confirm) { _, _ ->
                roloFotos.forEach { it.first.delete() }
                roloFotos.clear()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Salva e envia TODAS as fotos do rolo, depois regenera o PDF uma única vez. */
    private fun processarRoloESalvar(observacoes: String) {
        txtProgresso.visibility = View.VISIBLE
        txtProgresso.text = getString(R.string.saving_locally)
        CoroutineScope(Dispatchers.Main).launch {
            var falhas = 0
            withContext(Dispatchers.IO) {
                val nomeNorm = normalizarNome(nomePaciente)
                val prefArq = nomeNorm.trim().uppercase().replace(Regex("\\s+"), " ").replace(" ", "_")
                val tagSim = if (numeroSimulacao > 1) "_NOVASIM${numeroSimulacao - 1}" else ""
                roloFotos.forEachIndexed { idx, (tmp, cat) ->
                    try {
                        val ts = SimpleDateFormat("dd-MMM-yyyy_HH-mm-ss", Locale("pt", "BR"))
                            .format(Date()).uppercase()
                        val nomeArq = when (cat) {
                            com.radioterapia.ai.session.SessionManager.Category.DOCUMENTS ->
                                "${prefArq}_DOC${tagSim}_${ts}_${idx + 1}.jpg"
                            com.radioterapia.ai.session.SessionManager.Category.ACCESSORIES ->
                                "${prefArq}_ACESSORIOS_TRATAMENTO${tagSim}_${ts}_${idx + 1}.jpg"
                            else ->
                                "${prefArq}_POSICIONAMENTO_TRATAMENTO${tagSim}_${ts}_${idx + 1}.jpg"
                        }
                        salvarLocalmente(tmp, nomeArq)
                        enviarFotoTodosDestinos(tmp, nomeArq)
                        tmp.delete()
                    } catch (_: Exception) { falhas++ }
                }
            }
            auditLogger.registrar(
                AuditLogger.Tipo.UPLOAD, "Rolo de fotos adicionado no tratamento",
                mapOf("paciente" to nomePaciente, "pasta" to nomePastaServidor,
                      "fotos" to roloFotos.size.toString(), "falhas" to falhas.toString()))
            roloFotos.clear()
            atualizarBotaoRolo()
            if (falhas > 0) android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                "Algumas fotos ficaram pendentes de envio ($falhas).",
                android.widget.Toast.LENGTH_LONG).show()
            finalizarComPdf(observacoes)
        }
    }

    /** Observação ÚNICA ao salvar o rolo: mostra a observação em uso (editável). */
    private fun pedirObservacaoESalvarRolo() {
        CoroutineScope(Dispatchers.Main).launch {
            val obsAtual = withContext(Dispatchers.IO) {
                try {
                    // Resolve a pasta local pelo NOME + número (o nome do servidor
                    // pode divergir da pasta gravada), então lê a observação da
                    // simulação ORIGINAL para pré-preencher o campo.
                    val pasta = com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
                        this@AddPhotoInTreatmentActivity, nomePaciente,
                        numeroSimulacao, nomePastaServidor, prontuario)
                    com.radioterapia.ai.util.ObsStore.ler(pasta, numeroSimulacao)
                } catch (_: Exception) { "" }
            }
            val cont = android.widget.LinearLayout(this@AddPhotoInTreatmentActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 16, 48, 8)
            }
            val msg = TextView(this@AddPhotoInTreatmentActivity).apply {
                text = getString(R.string.ap_save_roll_msg, roloFotos.size)
                setTextColor(0xFFECEFF1.toInt()); textSize = 14f
            }
            val edt = android.widget.EditText(this@AddPhotoInTreatmentActivity).apply {
                setText(obsAtual)
                minLines = 2; maxLines = 3
                setHint(R.string.fin_observation_hint)
                setTextColor(0xFF212121.toInt()); setHintTextColor(0xFF9E9E9E.toInt())
                setBackgroundResource(R.drawable.bg_input_white)
                setPadding(28, 22, 28, 22)
            }
            cont.addView(msg)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            cont.addView(edt, lp)
            AlertDialog.Builder(this@AddPhotoInTreatmentActivity)
                .setTitle(R.string.ap_save_roll_title)
                .setView(cont)
                .setCancelable(false)
                .setPositiveButton(R.string.ap_finish) { _, _ ->
                    processarRoloESalvar(edt.text.toString().trim())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun finalizarComPdf(observacoes: String) {
        txtProgresso.visibility = View.VISIBLE
        txtProgresso.text = getString(R.string.ap_finishing)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val timestamp = SimpleDateFormat("dd-MMM-yyyy_HH-mm-ss", Locale("pt", "BR")).format(Date()).uppercase()
                val prefArq = normalizarNome(nomePaciente).trim().uppercase()
                    .replace(Regex("\\s+"), " ").replace(" ", "_")
                val tagSim = if (numeroSimulacao > 1) "_NOVASIM${numeroSimulacao - 1}" else ""

                val pdfRegerado = withContext(Dispatchers.IO) { regerarPdf(observacoes) }
                if (pdfRegerado != null) {
                    val nomePdfNovo = "${prefArq}_FOLHA_SIMULACAO${tagSim}_${timestamp}.pdf"
                    withContext(Dispatchers.IO) { salvarPdfNaPastaPaciente(pdfRegerado, nomePdfNovo) }
                    txtProgresso.text = getString(R.string.hc_sending_new_pdf)
                    withContext(Dispatchers.IO) { enviarPdfTodosDestinos(pdfRegerado) }
                    auditLogger.registrar(
                        AuditLogger.Tipo.UPLOAD, "PDF regenerado ao finalizar adição de fotos",
                        mapOf("paciente" to nomePaciente, "pasta" to nomePastaServidor))
                    txtProgresso.visibility = View.GONE
                    // Após salvar: imprimir, apenas visualizar o novo PDF, ou sair.
                    AlertDialog.Builder(this@AddPhotoInTreatmentActivity)
                        .setTitle(R.string.ap_saved_title)
                        .setMessage(R.string.ap_saved_msg)
                        .setCancelable(false)
                        .apply {
                            // ITEM 7: não imprime "às cegas". O seletor de modo
                            // testa a impressora de rede e oferece sistema,
                            // pen-drive OTG ou pasta — nada de barra de progresso
                            // enquanto o JetDirect 9100 falha em silêncio.
                            setPositiveButton(R.string.print_sheet) { _, _ ->
                                escolherModoImpressao(pdfRegerado)
                            }
                        }
                        .setNeutralButton(R.string.view_pdf_only) { _, _ ->
                            abrirPdfExterno(pdfRegerado); finalizar()
                        }
                        .setNegativeButton(R.string.finish_close) { _, _ -> finalizar() }
                        .show()
                } else {
                    txtProgresso.visibility = View.GONE
                    AlertDialog.Builder(this@AddPhotoInTreatmentActivity)
                        .setTitle(getString(R.string.hc_photos_saved))
                        .setMessage(getString(R.string.hc_photos_saved_no_pdf) +
                            "agora. Ao abrir novamente, tente finalizar de novo.")
                        .setPositiveButton(R.string.ok) { _, _ -> finalizar() }
                        .show()
                }
            } catch (e: Exception) {
                txtProgresso.visibility = View.GONE
                AlertDialog.Builder(this@AddPhotoInTreatmentActivity)
                    .setTitle(getString(R.string.hc_finish_error))
                    .setMessage(getString(R.string.err_generic, e.message ?: ""))
                    .setPositiveButton(R.string.ok) { _, _ -> finalizar() }
                    .show()
            }
        }
    }


    /** Abre um PDF no leitor padrão do sistema (via FileProvider). */
    private fun abrirPdfExterno(pdf: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", pdf)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.pdf_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun finalizar() {
        arquivoTemp = null
        finish()
    }

    /** Re-lê todas as fotos da pasta (com a nova) e gera o PDF cumulativo. */
    private suspend fun regerarPdf(observacoes: String = ""): File? {
        val fetcher = TreatmentPhotoFetcher(this)
        val sims = fetcher.buscarSimulacoes(nomePaciente)
        // Casa pelo nome da pasta; se mudou (modelo novo), usa a mais recente.
        // Casa pela pasta E pelo NUMERO da simulacao. So a pasta nao basta
        // mais: desde que as simulacoes deixaram de ser devolvidas como uma
        // so, varias dividem o mesmo diretorio do paciente, e casar apenas
        // pelo nome da pasta regeneraria o PDF da simulacao errada.
        val sim = sims.find {
            it.nomePastaCompleto == nomePastaServidor && it.numeroSimulacao == numeroSimulacao
        } ?: sims.find { it.numeroSimulacao == numeroSimulacao }
            ?: sims.maxByOrNull { it.timestampPrincipal } ?: return null

        // Ordena: rosto, etiqueta, posicionamentos, TODOS os acessórios
        val ordenadas = mutableListOf<File>()
        val rotulos = mutableListOf<String>()
        sim.rosto?.let { ordenadas.add(it.arquivoLocal); rotulos.add("Rosto") }
        sim.etiqueta?.let { ordenadas.add(it.arquivoLocal); rotulos.add("Etiqueta") }
        sim.posicionamentos.forEachIndexed { i, f -> ordenadas.add(f.arquivoLocal); rotulos.add("Posicionamento.${i + 1}") }
        sim.acessoriosLista.forEachIndexed { i, f -> ordenadas.add(f.arquivoLocal); rotulos.add("Acessório.${i + 1}") }

        val data = SimpleDateFormat("dd_MMM_yyyy__HH_mm_ss", Locale("pt", "BR")).format(Date())
        val pdfFile = File(cacheDir, "pdf_treat_${normalizarNome(nomePaciente).replace(" ", "_")}_${data}.pdf")
        val dadosPac = try {
            com.radioterapia.ai.patient.PatientCache(this).obterDadosPaciente(nomePaciente)
        } catch (_: Exception) { null }
        val nascFmt = com.radioterapia.ai.util.DateUtils.formatarNascimento(
            dadosPac?.nascimento ?: "",
            com.radioterapia.ai.i18n.LocaleManager.obterIdiomaAtual(this))
        val dados = PdfBuilder.DadosCabecalho(
            nomePaciente = nomePaciente,
            nascimento = nascFmt,
            prontuario = prontuario.ifBlank { dadosPac?.prontuario ?: "" },
            idsExtras = emptyList(),
            dataSimulacao = Date(),
            numeroSimulacao = numeroSimulacao,
            nomeClinica = config.nomeClinica,
            sexo = dadosPac?.sexo ?: "",
            medicoAssistente = dadosPac?.medicoAssistente ?: ""
        )
        // Persiste a observação junto da simulação (reeditável depois)
        val pastaSim = com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
            this, nomePaciente, numeroSimulacao, sim.nomePastaCompleto, prontuario)
        com.radioterapia.ai.util.ObsStore.gravar(pastaSim, numeroSimulacao, observacoes)
        val toReg = com.radioterapia.ai.util.TimeOutStore.ler(pastaSim, numeroSimulacao)
        val timeOut = if (toReg != null && toReg.ativo)
            PdfBuilder.DadosTimeOut(toReg.medico, toReg.sitio, toReg.riscoQueda,
                toReg.precaucaoContato, sim.rosto?.arquivoLocal,
                toReg.equipamento, toReg.alergia, toReg.fracoesMax)
        else null
        return PdfBuilder.gerarFolhaPosicionamento(this, dados, ordenadas, pdfFile,
            rotulos = rotulos, landscape = config.pdfLandscape,
            usarEtiqueta = config.pdfUsarEtiqueta,
            etiquetaLarguraMm = config.pdfEtiquetaLarguraMm,
            etiquetaAlturaMm = config.pdfEtiquetaAlturaMm,
            observacoes = observacoes,
            timeOut = timeOut,
            margemImpressaoMm = config.pdfMargemMm,
            protocoloId = toReg?.protocoloId.orEmpty())
    }

    private fun enviarFotoTodosDestinos(foto: File, nomeArquivo: String): List<String> {
        val erros = mutableListOf<String>()
        for ((idx, destino) in config.obterDestinosAtivos().withIndex()) {
            try {
                val resultado = enviarUm(destino, foto, nomeArquivo)
                if (!resultado) erros.add("destino $idx")
            } catch (e: Exception) {
                erros.add("destino $idx: ${e.message}")
            }
        }
        return erros
    }

    private fun enviarPdfTodosDestinos(pdf: File): List<String> {
        if (!config.pdfParaServidor) return emptyList()
        val nomeNorm = normalizarNome(nomePaciente).replace(' ', '_')
        val data = SimpleDateFormat("dd_MMM_yyyy__HH_mm_ss", Locale("pt", "BR")).format(Date())
        val tagSim = if (numeroSimulacao > 1) "_NOVASIM${numeroSimulacao - 1}" else ""
        val nomePdf = "${nomeNorm}_FolhaSimulacao${tagSim}_${data}.pdf"

        val erros = mutableListOf<String>()
        for ((idx, destino) in config.obterDestinosAtivos().withIndex()) {
            try {
                if (!enviarUm(destino, pdf, nomePdf)) erros.add("destino $idx")
            } catch (e: Exception) {
                erros.add("destino $idx: ${e.message}")
            }
        }
        return erros
    }

    private fun enviarUm(destino: DestinoSmb, arquivo: File, nomeArquivo: String): Boolean {
        if (config.smbUsuario.isBlank() || credentials.obterSenha().isBlank()) return false
        val (share, subpastaBase) = destino.caminhoDecomposto()
        if (share.isEmpty()) return false

        val client = SmbClient(
            host = destino.host, porta = destino.porta,
            dominio = config.smbDominio, usuario = config.smbUsuario,
            senha = credentials.obterSenha(), protocolo = config.smbProtocolo
        )
        val caminho = if (subpastaBase.isEmpty()) nomePastaServidor else "$subpastaBase\\$nomePastaServidor"
        val r = client.enviarArquivo(share, caminho, nomeArquivo, arquivo)
        return r.sucesso
    }

    /**
     * Salva a foto adicional NA PASTA DO PACIENTE do modelo novo
     * (PhotoID_RT/PHOTOS/<PACIENTE>), para o carrossel e o PDF enxergarem.
     * Usa SAF se o usuário escolheu pasta própria; senão StorageLocal (raiz/app).
     * Também faz cópia no rolo da câmera (best-effort).
     */
    private fun salvarLocalmente(foto: File, nomeArquivo: String) {
        try {
            if (config.pastaFotosUri.isNotBlank()) {
                val base = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                    this, Uri.parse(config.pastaFotosUri)) ?: return
                val photos = obterOuCriarPastaDoc(obterOuCriarPastaDoc(base, "PhotoID_RT"), "PHOTOS") ?: return
                val pastaPac = acharOuCriarPastaPacienteDoc(photos) ?: return
                pastaPac.findFile(nomeArquivo)?.delete()
                val doc = pastaPac.createFile("image/jpeg", nomeArquivo) ?: return
                contentResolver.openOutputStream(doc.uri)?.use { saida ->
                    foto.inputStream().use { it.copyTo(saida) }
                }
            } else {
                val pastaPac = acharOuCriarPastaPacienteFile()
                foto.copyTo(File(pastaPac, nomeArquivo), overwrite = true)
            }
        } catch (_: Exception) {}

        // Cópia no rolo (Pictures/PhotoID_RT) — o rolo não aceita PDF, só fotos.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nomeArquivo)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoID_RT")
                }
                val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
                contentResolver.openOutputStream(uri)?.use { saida ->
                    foto.inputStream().use { it.copyTo(saida) }
                }
            }
        } catch (_: Exception) {}
    }

    /** Salva o PDF regenerado na pasta do paciente, removendo os PDFs antigos
     *  (a folha é cumulativa — só a versão mais nova vale). */
    private fun salvarPdfNaPastaPaciente(pdf: File, nomePdf: String) {
        try {
            if (config.pastaFotosUri.isNotBlank()) {
                val base = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                    this, Uri.parse(config.pastaFotosUri)) ?: return
                val photos = obterOuCriarPastaDoc(obterOuCriarPastaDoc(base, "PhotoID_RT"), "PHOTOS") ?: return
                val pastaPac = acharOuCriarPastaPacienteDoc(photos) ?: return
                pastaPac.listFiles().filter { it.name?.endsWith(".pdf", true) == true }
                    .forEach { it.delete() }
                val doc = pastaPac.createFile("application/pdf", nomePdf) ?: return
                contentResolver.openOutputStream(doc.uri)?.use { saida ->
                    pdf.inputStream().use { it.copyTo(saida) }
                }
            } else {
                val pastaPac = acharOuCriarPastaPacienteFile()
                pastaPac.listFiles { _, n -> n.endsWith(".pdf", true) }?.forEach { it.delete() }
                pdf.copyTo(File(pastaPac, nomePdf), overwrite = true)
            }
        } catch (_: Exception) {}
    }

    /** Acha (ignorando _/espacos/caixa) ou cria a pasta do paciente — SAF. */
    private fun acharOuCriarPastaPacienteDoc(
        photos: androidx.documentfile.provider.DocumentFile
    ): androidx.documentfile.provider.DocumentFile? {
        photos.listFiles().firstOrNull {
            it.isDirectory && com.radioterapia.ai.util.StorageLocal.pastaCasaPaciente(it.name, nomePaciente)
        }?.let { return it }
        return photos.createDirectory(
            com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(nomePaciente, prontuario))
    }

    /** Acha (por nome + prontuário) ou cria a pasta do paciente — File (raiz/app). */
    private fun acharOuCriarPastaPacienteFile(): File {
        val photos = com.radioterapia.ai.util.StorageLocal.photos(this)
        photos.listFiles { f ->
            f.isDirectory && com.radioterapia.ai.util.StorageLocal.pastaCasaPaciente(f.name, nomePaciente)
        }?.firstOrNull()?.let { return it }
        return File(photos,
            com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(nomePaciente, prontuario)).apply { mkdirs() }
    }

    private fun obterOuCriarPastaDoc(pai: androidx.documentfile.provider.DocumentFile?,
                                     nome: String): androidx.documentfile.provider.DocumentFile? {
        if (pai == null) return null
        return pai.findFile(nome)?.takeIf { it.isDirectory } ?: pai.createDirectory(nome)
    }

    private fun normalizarNome(nome: String): String {
        val s = Normalizer.normalize(nome, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return s.replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ").trim().uppercase(Locale.getDefault())
    }

    companion object {
        const val EXTRA_NOME = "nome"
        const val EXTRA_PRONTUARIO = "prontuario"
        const val EXTRA_PASTA = "pasta"
        const val EXTRA_NUM_SIMULACAO = "num_sim"
        private const val REQUEST_PERM = 11
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
