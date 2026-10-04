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
import com.radioterapia.ai.util.FotosArquivadas
import com.radioterapia.ai.util.NomeArquivo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Adicionar foto durante o tratamento.
 *
 * Diferenças vs a captura normal:
 *   - As cinco categorias estão disponíveis; rosto e etiqueta, que são únicas,
 *     substituem a anterior, que vai para ARQUIVADAS (ver [configurarTabsAp])
 *   - As fotos vão para um ROLO local; nada é gravado até "Salvar e adicionar"
 *   - Salvar:
 *     a. Aplica marca d'água (mesmo padrão da simulação)
 *     b. Grava na pasta do paciente com o nome de util/NomeArquivo, marcado
 *        TRAT (`<INICIAIS>_POS_TRAT[_NS<n>]_<data>_<contagem>.jpg`), com a
 *        contagem continuando a das fotos já gravadas daquele tipo
 *     c. Envia para o servidor (todos os destinos ativos)
 *     d. Relê as fotos da simulação e regera a Folha de Posicionamento
 *     e. Grava o PDF novo no lugar do anterior DESTA simulação
 *     f. Pergunta se quer imprimir nova versão
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
        apMolduraRecorte = findViewById(R.id.apMolduraRecorte)
        apAvisoEncaixar = findViewById(R.id.avisoEncaixar)
        apImgPinca = findViewById(R.id.imgPincaAviso)
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
            btnSwitch.setColorFilter(if (usarCameraFrontal) ContextCompat.getColor(this, R.color.brand_primary) else 0xFF9E9E9E.toInt())
            txtSwitch.setTextColor(if (usarCameraFrontal) ContextCompat.getColor(this, R.color.brand_primary) else 0xFFCCCCCC.toInt())
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

        /*
            DE RECURSO, E NUMA LINHA SO.

            Eram dois defeitos somados. O rotulo "Paciente:" era literal em
            portugues, visivel em toda abertura desta camera, em qualquer
            idioma. E o "\n" nunca renderizou: o TextView e maxLines="1", entao
            a segunda metade — "Pasta no servidor: ..." — jamais apareceu para
            ninguem. Ela saiu junto, porque era diagnostico de desenvolvedor num
            lugar que nao o mostrava.
         */
        txtInfo.text = getString(R.string.ap_faixa_paciente, nomePaciente)

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
                // Mesma conta da tela de simulacao: a moldura segue a proporcao
                // do QUADRO, que muda quando o tablet vira.
                aplicarProporcaoDaMoldura()
                apMolduraRecorte?.visibility = View.VISIBLE
                com.radioterapia.ai.ui.anim.Movimento.mostrarAviso(apAvisoEncaixar, apImgPinca)
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
        btnFlash.setColorFilter(if (flashOn) ContextCompat.getColor(this, R.color.brand_primary) else 0xFF9E9E9E.toInt())
        txtFlashLabel.setTextColor(if (flashOn) ContextCompat.getColor(this, R.color.brand_primary) else 0xFFCCCCCC.toInt())
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
        btnMute.setColorFilter(if (!cliqueMudo) ContextCompat.getColor(this, R.color.brand_primary) else 0xFF9E9E9E.toInt())
        txtMuteLabel.setTextColor(if (!cliqueMudo) ContextCompat.getColor(this, R.color.brand_primary) else 0xFFCCCCCC.toInt())
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
    private var apMolduraRecorte: com.radioterapia.ai.camera.MolduraRecorteView? = null

    /**
     * A ORIENTAÇÃO VEM DA CONFIGURAÇÃO, NÃO DA ROTAÇÃO DO DISPLAY. Mesma
     * correção da MainActivity, e pelo mesmo motivo: `ROTATION_90/270` só
     * significa "deitado" em aparelho cuja orientação natural é retrato. O
     * tablet, que é o aparelho alvo, tem orientação natural PAISAGEM e reporta
     * `ROTATION_0` deitado — a conta antiga invertia os dois casos, e a moldura
     * encolhia para uma faixa estreita no centro justamente no uso normal.
     */
    private fun aplicarProporcaoDaMoldura() {
        val deitado = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        apMolduraRecorte?.aspectoVisor = if (deitado) 16f / 9f else 9f / 16f
    }

    /**
     * `configChanges` inclui `orientation`, então girar não recria a Activity e
     * nada reavaliava a moldura: ela congelava na proporção de quando a câmera
     * foi ligada.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        aplicarProporcaoDaMoldura()
        imageCapture?.targetRotation = rotacaoAtualDoDisplay()
    }

    private var apAvisoEncaixar: View? = null
    private var apImgPinca: android.widget.ImageView? = null
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
        val tipo = NomeArquivo.tipoDe(cat)
        fotosExistentes.any { arq ->
            !NomeArquivo.ehOriginal(arq.name) && tipoDoArquivo(arq.name) == tipo
        }
    } catch (_: Exception) { false }

    /**
     * Tipo de uma foto já gravada, nos dois esquemas de nome, sem deixar o nome
     * do paciente decidir (ver FotosArquivadas.tipoProvavel). Serve a quem
     * rotula e conta; quem MOVE arquivo usa a versão estrita.
     */
    private fun tipoDoArquivo(nome: String): NomeArquivo.Tipo? =
        FotosArquivadas.tipoProvavel(nome, nomePaciente)

    /** Rótulo falado pelo leitor de tela: a categoria, não o nome do arquivo. */
    private fun descricaoDaFoto(nome: String): String = getString(when (tipoDoArquivo(nome)) {
        NomeArquivo.Tipo.ROSTO -> R.string.cat_face
        NomeArquivo.Tipo.ETIQUETA -> R.string.cat_label
        NomeArquivo.Tipo.POSICIONAMENTO -> R.string.cat_positioning
        NomeArquivo.Tipo.ACESSORIOS -> R.string.cat_accessories
        NomeArquivo.Tipo.IMPRESSO -> R.string.cat_documents
        else -> R.string.photo_existing_title
    })

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
            // Moldura sai, aviso volta: o recorte de verdade assume a tela.
            apMolduraRecorte?.visibility = View.GONE
            com.radioterapia.ai.ui.anim.Movimento.mostrarAviso(apAvisoEncaixar, apImgPinca)
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
        // A moldura volta com o visor: vem outra foto, vale a licao de novo.
        apMolduraRecorte?.visibility = View.VISIBLE
        com.radioterapia.ai.ui.anim.Movimento.mostrarAviso(apAvisoEncaixar, apImgPinca)
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

        // Uma etiqueta por categoria, sem "else": com ele, rosto e etiqueta
        // refeitos no Tratamento sairiam carimbados como posicionamento no
        // EXIF, contradizendo o tipo gravado no nome do arquivo.
        val tagExifBase = when (categoriaAp) {
            Category.FACE -> "ROSTO TRATAMENTO"
            Category.LABEL -> "ETIQUETA TRATAMENTO"
            Category.POSITIONING -> "POSICIONAMENTO TRATAMENTO"
            Category.ACCESSORIES -> "ACESSORIO TRATAMENTO"
            Category.DOCUMENTS -> "DOCUMENTO TRATAMENTO"
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
            android.widget.Toast.makeText(this, R.string.gallery_open_fail,
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
     *
     * Volta na categoria do NOME do arquivo, e nao na aba ativa, como na tela de
     * simulacao: um rosto arquivado devolvido com a aba Posicionamento aberta
     * entraria no grid de posicionamento da ficha impressa. A aba ativa so
     * decide quando o nome nao diz o tipo.
     */
    private fun abrirArquivadasAp() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            // Só leitura: a pasta é resolvida pela regra do prontuário e não é
            // criada. As arquivadas da homônima não aparecem para devolver ao rolo.
            val alvo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { resolverPastaAlvo(criar = false, saf = false) } catch (_: Exception) { PastaAlvo.Nenhuma }
            }
            if (isFinishing || isDestroyed) return@launch
            if (alvo is PastaAlvo.Recusada) {
                avisarPastaIncerta(fechar = false)
                return@launch
            }
            val base = (alvo as? PastaAlvo.Arquivo)?.pasta
            if (base == null) {
                android.widget.Toast.makeText(this@AddPhotoInTreatmentActivity,
                    R.string.arq_vazio, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            com.radioterapia.ai.ui.ArquivadasDialog.mostrar(
                this@AddPhotoInTreatmentActivity, listOf(base)
            ) { arq ->
                val tmp = File(cacheDir, "rolo_arq_${System.currentTimeMillis()}.jpg")
                val cat = tipoDoArquivo(arq.name)?.let { NomeArquivo.categoriaDe(it) } ?: categoriaAp
                if (FotosArquivadas.copiarParaTemp(arq, tmp)) {
                    roloFotos.add(tmp to cat)
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
            val (achadas, recusada) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val alvo = resolverPastaAlvo(criar = false)
                    if (alvo is PastaAlvo.Recusada) return@withContext emptyList<File>() to true
                    val sims = TreatmentPhotoFetcher(this@AddPhotoInTreatmentActivity)
                        .buscarSimulacoes(nomePaciente, prontuario)
                    // GUARDA: pasta E número, sem alternativa. Estas fotos são as
                    // que o técnico pode excluir, arquivar ou reenquadrar; "a do
                    // mesmo número" ou "a mais recente" pode ser da homônima.
                    val sim = TreatmentPhotoFetcher.simulacaoExata(
                        sims, numeroSimulacao, nomePastaServidor, alvo.nome)
                    sim?.fotos?.map { it.arquivoLocal }?.sortedBy { it.name }.orEmpty() to false
                } catch (_: Exception) { emptyList<File>() to false }
            }
            if (isFinishing || isDestroyed) return@launch
            fotosExistentes = achadas
            desenharFaixaExistentes()
            if (recusada) avisarPastaIncerta(fechar = true)
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
                contentDescription = descricaoDaFoto(arq.name)
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
            // null: pasta do paciente incerta, nada foi movido.
            val ok = kotlinx.coroutines.withContext<Boolean?>(kotlinx.coroutines.Dispatchers.IO) {
                if (!podeAlterarFotoSalva(arq)) return@withContext null
                com.radioterapia.ai.util.FotosArquivadas.arquivar(arq) != null
            }
            if (isFinishing || isDestroyed) return@launch
            if (ok == null) {
                avisarPastaIncerta(fechar = false)
                return@launch
            }
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
     * arquivo gerado. O nome sai de um lugar so ([nomeFichaGuardada]).
     */
    private suspend fun regerarFichaAposMudanca() {
        val instante = System.currentTimeMillis()
        // Pasta resolvida sem criar: a ficha só é regravada na pasta exata
        // deste paciente. Incerta, nenhuma ficha é gerada nem apagada.
        val alvo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { resolverPastaAlvo(criar = false) } catch (_: Exception) { PastaAlvo.Nenhuma }
        }
        if (alvo is PastaAlvo.Recusada) {
            avisarPastaIncerta(fechar = false)
            carregarFotosExistentes()
            return
        }
        val pdf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { regerarPdf(alvo, observacoesAtuais(alvo), instante) } catch (_: Exception) { null }
        }
        if (pdf != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    salvarPdfNaPastaPaciente(pdf, nomeFichaGuardada(instante), alvo)
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
        // O par e procurado nas grafias que existem em campo (NomeArquivo).
        val fonte = NomeArquivo.originalDe(arq) ?: arq
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
            // null: pasta do paciente incerta, a foto gravada ficou intacta.
            val ok = kotlinx.coroutines.withContext<Boolean?>(kotlinx.coroutines.Dispatchers.IO) {
                if (!podeAlterarFotoSalva(alvo)) {
                    novo.delete()
                    return@withContext null
                }
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
            if (ok == null) {
                avisarPastaIncerta(fechar = false)
                return@launch
            }
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
        // substituir — nao ficar sem. Tipo desconhecido conta zero e o aviso
        // aparece: na duvida, avisar custa menos que apagar sem aviso.
        val tipo = tipoDoArquivo(arq.name)
        val mesmoTipo = if (tipo == null) 0
                        else fotosExistentes.count { tipoDoArquivo(it.name) == tipo }
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
            // null: pasta do paciente incerta, nada foi apagado.
            val ok = kotlinx.coroutines.withContext<Boolean?>(kotlinx.coroutines.Dispatchers.IO) {
                if (!podeAlterarFotoSalva(arq)) return@withContext null
                try {
                    val apagou = !arq.exists() || arq.delete()
                    // O "_ORIGINAL" e o quadro cheio da mesma foto: manter um
                    // sem o outro deixaria a pasta com um original orfao, que
                    // o sincronizador levaria para o servidor sem par. Sai
                    // toda grafia de par que existir (NomeArquivo).
                    val pai = arq.parentFile
                    if (pai != null) {
                        NomeArquivo.candidatosOriginal(arq.name)
                            .map { java.io.File(pai, it) }
                            .filter { it.exists() }
                            .forEach { it.delete() }
                    }
                    apagou
                } catch (_: Exception) { false }
            }
            if (isFinishing || isDestroyed) return@launch
            if (ok == null) {
                avisarPastaIncerta(fechar = false)
                return@launch
            }
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
    private fun observacoesAtuais(alvo: PastaAlvo): String = try {
        pastaDosRegistros(alvo)?.let { com.radioterapia.ai.util.ObsStore.ler(it, numeroSimulacao) }.orEmpty()
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
            // A pasta é resolvida UMA vez, antes de qualquer gravação. Incerta
            // (homônima de outro prontuário, ou homônimas sem prontuário), ou sem
            // como abrir a pasta: nada é gravado, criado, arquivado nem enviado,
            // e o rolo fica como estava.
            val alvo = withContext(Dispatchers.IO) {
                try { resolverPastaAlvo(criar = true) } catch (_: Exception) { PastaAlvo.Nenhuma }
            }
            if (isFinishing || isDestroyed) return@launch
            if (alvo is PastaAlvo.Recusada) {
                avisarPastaIncerta(fechar = false)
                return@launch
            }
            if (alvo is PastaAlvo.Nenhuma) {
                txtProgresso.visibility = View.GONE
                AlertDialog.Builder(this@AddPhotoInTreatmentActivity)
                    .setTitle(R.string.hc_finish_error)
                    .setMessage(R.string.storage_path_error)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            withContext(Dispatchers.IO) {
                // A contagem de cada tipo CONTINUA a da pasta: os nomes ja
                // gravados sao lidos uma vez, antes do lote, e cada foto do lote
                // recebe o proximo numero do seu tipo nesta simulacao. Fotos
                // legadas entram na conta pela quantidade, entao a primeira foto
                // nova depois de quatro posicionamentos antigos sai com 5.
                val existentes = try { nomesNaPastaDoPaciente(alvo) } catch (_: Exception) { emptyList() }
                val proximo = mutableMapOf<NomeArquivo.Tipo, Int>()
                roloFotos.forEach { (tmp, cat) ->
                    try {
                        val tipo = NomeArquivo.tipoDe(cat)
                        val n = proximo.getOrPut(tipo) {
                            NomeArquivo.proximoContador(existentes, numeroSimulacao, tipo)
                        }
                        proximo[tipo] = n + 1
                        val nomeArq = NomeArquivo.montar(nomePaciente, tipo, numeroSimulacao,
                            System.currentTimeMillis(), n, "jpg", NomeArquivo.Contexto.TRATAMENTO)
                        salvarLocalmente(tmp, nomeArq, alvo)
                        if (cat.unico) arquivarAnterioresDoTipo(tipo, nomeArq, alvo)
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
            finalizarComPdf(observacoes, alvo)
        }
    }

    /** Observação ÚNICA ao salvar o rolo: mostra a observação em uso (editável). */
    private fun pedirObservacaoESalvarRolo() {
        CoroutineScope(Dispatchers.Main).launch {
            val obsAtual = withContext(Dispatchers.IO) {
                try {
                    // Lê a observação da simulação ORIGINAL para pré-preencher o
                    // campo, da pasta de registros deste paciente: a de uma
                    // homônima iria para a ficha deste ao salvar.
                    observacoesAtuais(resolverPastaAlvo(criar = false))
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
                minLines = 2; maxLines = 4
                setHint(R.string.fin_observation_hint)
                setTextColor(0xFF212121.toInt()); setHintTextColor(0xFF9E9E9E.toInt())
                setBackgroundResource(R.drawable.bg_input_white)
                setPadding(28, 22, 28, 22)
            }
            // Mesma regra da tela de finalizacao e da edicao da simulacao: ate 4
            // linhas digitadas, ou seja, no maximo MAX_QUEBRAS_OBS quebras. O
            // vigia entra DEPOIS do setText acima, para o texto ja gravado nao
            // passar por ele. Recusa so a mudanca que AUMENTA as quebras acima do
            // teto: uma observacao ja gravada com mais linhas que o teto
            // continua editavel e pode ser encurtada, em vez de travar o campo
            // inteiro. O setText de reversao dispara o
            // vigia de novo, mas com menos quebras que o texto recusado, entao
            // nao entra em laco.
            edt.addTextChangedListener(object : android.text.TextWatcher {
                private var anterior = ""
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                    anterior = s?.toString() ?: ""
                }
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val quebras = (s?.toString() ?: "").count { it == '\n' }
                    if (quebras > MAX_QUEBRAS_OBS && quebras > anterior.count { it == '\n' }) {
                        // Copia antes do setText: a chamada reentrante do vigia
                        // sobrescreve `anterior` com o texto recusado.
                        val aceito = anterior
                        edt.setText(aceito)
                        edt.setSelection(aceito.length.coerceAtMost(edt.text.length))
                    }
                }
            })
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

    private fun finalizarComPdf(observacoes: String, alvo: PastaAlvo) {
        txtProgresso.visibility = View.VISIBLE
        txtProgresso.text = getString(R.string.ap_finishing)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Um instante so para as duas copias da mesma ficha: a de
                // entrega (cache, impressao) e a guardada (pasta, servidor).
                val instante = System.currentTimeMillis()
                val pdfRegerado = withContext(Dispatchers.IO) { regerarPdf(alvo, observacoes, instante) }
                if (pdfRegerado != null) {
                    val nomePdfNovo = nomeFichaGuardada(instante)
                    withContext(Dispatchers.IO) { salvarPdfNaPastaPaciente(pdfRegerado, nomePdfNovo, alvo) }
                    txtProgresso.text = getString(R.string.hc_sending_new_pdf)
                    withContext(Dispatchers.IO) { enviarPdfTodosDestinos(pdfRegerado, nomePdfNovo) }
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
                            // Não imprime "às cegas". O seletor de modo
                            // testa a impressora de rede e oferece sistema,
                            // pen-drive OTG ou pasta — nada de barra de progresso
                            // enquanto o JetDirect 9100 falha em silêncio.
                            // Nome de entrega explícito: pen-drive e pasta da
                            // impressora não dependem de como o cache foi nomeado.
                            setPositiveButton(R.string.print_sheet) { _, _ ->
                                escolherModoImpressao(pdfRegerado, nomeFichaEntrega(instante))
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
                        .setMessage(getString(R.string.hc_photos_saved_no_pdf))
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

    /**
     * Re-lê todas as fotos da pasta (com a nova) e gera o PDF cumulativo.
     *
     * O arquivo devolvido fica no cache e é a cópia de ENTREGA — a que vai para
     * a impressão, o pen-drive e o leitor de PDF —, por isso leva o nome
     * completo do paciente em ASCII ([nomeFichaEntrega]): quem pega a
     * folha na impressora precisa saber de quem ela é. A cópia guardada na
     * pasta do paciente e no servidor recebe o nome com iniciais
     * ([nomeFichaGuardada]), calculado com o MESMO [instante].
     */
    private suspend fun regerarPdf(alvo: PastaAlvo, observacoes: String,
                                   instante: Long): File? {
        // Sem pasta resolvida não há onde guardar a ficha nem a observação, e o
        // resolvedor por nome poderia achar a pasta de outra paciente.
        if (alvo !is PastaAlvo.Arquivo && alvo !is PastaAlvo.Documento) return null
        val fetcher = TreatmentPhotoFetcher(this)
        val sims = fetcher.buscarSimulacoes(nomePaciente, prontuario)
        // Casa pela pasta E pelo NUMERO da simulacao. So a pasta nao basta:
        // varias simulacoes dividem o mesmo diretorio do paciente, e casar
        // apenas pelo nome da pasta regeneraria o PDF da simulacao errada.
        // GUARDA: sem alternativa por numero nem "a mais recente". Com uma
        // homonima no tablet, as duas sao achadas pela busca, e a ficha da
        // outra paciente seria regravada na pasta desta. A pasta aberta vem
        // primeiro; a resolvida para gravar entra quando a aberta e de outro
        // layout (servidor) e nao aparece na busca local.
        val sim = TreatmentPhotoFetcher.simulacaoExata(
            sims, numeroSimulacao, nomePastaServidor, alvo.nome) ?: return null

        // Ordena: rosto, etiqueta, posicionamentos, TODOS os acessórios
        val ordenadas = mutableListOf<File>()
        val rotulos = mutableListOf<String>()
        sim.rosto?.let { ordenadas.add(it.arquivoLocal); rotulos.add("Rosto") }
        sim.etiqueta?.let { ordenadas.add(it.arquivoLocal); rotulos.add("Etiqueta") }
        sim.posicionamentos.forEachIndexed { i, f -> ordenadas.add(f.arquivoLocal); rotulos.add("Posicionamento.${i + 1}") }
        sim.acessoriosLista.forEachIndexed { i, f -> ordenadas.add(f.arquivoLocal); rotulos.add("Acessório.${i + 1}") }

        val pdfFile = File(cacheDir, nomeFichaEntrega(instante))
        // Cabeçalho pela chave nome + prontuário. Só pelo nome, a busca do
        // cadastro escolhe entre homônimas o registro mais completo, e a ficha
        // sairia com o prontuário desta e o nascimento, o sexo e o médico da
        // outra. Sem prontuário na Intent vale o da pasta onde se grava, que é
        // a pasta destas fotos. Registro que não serve deixa os campos em branco.
        val prontuarioFicha = prontuario.ifBlank {
            alvo.nome?.let { TreatmentPhotoFetcher.prontuarioDaPastaDoPaciente(it, nomePaciente) }.orEmpty()
        }
        val dadosPac = try {
            val cache = com.radioterapia.ai.patient.PatientCache(this)
            cache.obterDadosPaciente(nomePaciente, prontuarioFicha)?.takeIf { d ->
                TreatmentPhotoFetcher.cadastroServeAoPaciente(prontuarioFicha, d.prontuario,
                    prontuarioFicha.isBlank() && cache.temHomonimos(nomePaciente))
            }
        } catch (_: Exception) { null }
        val nascFmt = com.radioterapia.ai.util.DateUtils.formatarNascimento(
            dadosPac?.nascimento ?: "",
            com.radioterapia.ai.i18n.LocaleManager.obterIdiomaAtual(this))
        val dados = PdfBuilder.DadosCabecalho(
            nomePaciente = nomePaciente,
            nascimento = nascFmt,
            prontuario = prontuarioFicha,
            idsExtras = emptyList(),
            dataSimulacao = Date(),
            numeroSimulacao = numeroSimulacao,
            nomeClinica = config.nomeClinica,
            sexo = dadosPac?.sexo ?: "",
            medicoAssistente = dadosPac?.medicoAssistente ?: ""
        )
        // Persiste a observação junto da simulação (reeditável depois), na
        // pasta de registros deste paciente ([pastaDosRegistros]). Incerta,
        // a observação não é gravada e a ficha sai sem a página de Time-Out:
        // a de outra paciente traria os alertas dela.
        val pastaSim = pastaDosRegistros(alvo)
        pastaSim?.let { com.radioterapia.ai.util.ObsStore.gravar(it, numeroSimulacao, observacoes) }
        val toReg = pastaSim?.let { com.radioterapia.ai.util.TimeOutStore.ler(it, numeroSimulacao) }
        val timeOut = if (toReg != null && toReg.ativo)
            PdfBuilder.DadosTimeOut(toReg.medico, toReg.sitio, toReg.riscoQueda,
                toReg.precaucaoContato, sim.rosto?.arquivoLocal,
                toReg.equipamento, toReg.alergia, toReg.fracoesMax)
        else null
        return PdfBuilder.gerarFolhaPosicionamento(this, dados, ordenadas, pdfFile,
            rotulos = rotulos, landscape = config.pdfLandscape,
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

    /**
     * Envia a ficha ao servidor com o nome GUARDADO (iniciais), o mesmo da pasta
     * do paciente: o servidor é cópia da pasta, não cópia de entrega.
     */
    private fun enviarPdfTodosDestinos(pdf: File, nomePdf: String): List<String> {
        if (!config.pdfParaServidor) return emptyList()
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
     * A pasta chega resolvida ([resolverPastaAlvo]); sem ela, nada é gravado.
     * Também faz cópia no rolo da câmera (best-effort).
     */
    private fun salvarLocalmente(foto: File, nomeArquivo: String, alvo: PastaAlvo) {
        try {
            when (alvo) {
                is PastaAlvo.Documento -> {
                    val pastaPac = alvo.pasta
                    pastaPac.findFile(nomeArquivo)?.delete()
                    val doc = pastaPac.createFile("image/jpeg", nomeArquivo) ?: return
                    contentResolver.openOutputStream(doc.uri)?.use { saida ->
                        foto.inputStream().use { it.copyTo(saida) }
                    }
                }
                is PastaAlvo.Arquivo -> foto.copyTo(File(alvo.pasta, nomeArquivo), overwrite = true)
                else -> return
            }
            // GATILHO: foto de tratamento tambem e documentacao de
            // posicionamento, e chega dias depois da simulacao — se so a
            // simulacao disparasse o envio, o que se acrescenta durante o
            // tratamento esperaria o periodo configurado para sair do tablet.
            com.radioterapia.ai.sync.SyncWorker.aoSalvarFoto(this)
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

    /**
     * Salva o PDF regenerado na pasta do paciente, removendo os PDFs antigos
     * DESTA simulação (a folha é cumulativa — só a versão mais nova vale).
     *
     * GUARDA: só desta. Todas as simulações do paciente dividem a pasta, e
     * apagar todo PDF da pasta levaria junto a ficha das outras simulações.
     * E só na pasta resolvida ([resolverPastaAlvo]): a ficha apagada aqui é a
     * do paciente desta tela, nunca a de uma homônima.
     */
    private fun salvarPdfNaPastaPaciente(pdf: File, nomePdf: String, alvo: PastaAlvo) {
        try {
            when (alvo) {
                is PastaAlvo.Documento -> {
                    val pastaPac = alvo.pasta
                    pastaPac.listFiles()
                        .filter { NomeArquivo.ehFichaDaSimulacao(it.name ?: "", numeroSimulacao) }
                        .forEach { it.delete() }
                    val doc = pastaPac.createFile("application/pdf", nomePdf) ?: return
                    contentResolver.openOutputStream(doc.uri)?.use { saida ->
                        pdf.inputStream().use { it.copyTo(saida) }
                    }
                }
                is PastaAlvo.Arquivo -> {
                    val pastaPac = alvo.pasta
                    pastaPac.listFiles { _, n -> NomeArquivo.ehFichaDaSimulacao(n, numeroSimulacao) }
                        ?.forEach { it.delete() }
                    pdf.copyTo(File(pastaPac, nomePdf), overwrite = true)
                }
                else -> return
            }
        } catch (_: Exception) {}
    }

    /** Nome da ficha GUARDADA (pasta do paciente e servidor): com iniciais. */
    private fun nomeFichaGuardada(instante: Long): String =
        NomeArquivo.montar(nomePaciente, NomeArquivo.Tipo.FICHA, numeroSimulacao, instante, 1, "pdf")

    /**
     * Nome da ficha ENTREGUE (cache, impressão, pen-drive, leitor de PDF): com
     * o nome completo em ASCII. Mesmo [instante] de [nomeFichaGuardada], para
     * as duas cópias dizerem a mesma hora.
     */
    private fun nomeFichaEntrega(instante: Long): String =
        NomeArquivo.montarEntrega(nomePaciente, NomeArquivo.Tipo.FICHA, numeroSimulacao, instante, 1, "pdf")

    /**
     * Nomes de primeiro nível da pasta do paciente, para a contagem continuar
     * a das fotos já gravadas. Só leitura de disco (chamar fora da thread
     * principal); não toca em View.
     */
    private fun nomesNaPastaDoPaciente(alvo: PastaAlvo): List<String> = when (alvo) {
        is PastaAlvo.Documento -> alvo.pasta.listFiles().mapNotNull { it.name }
        is PastaAlvo.Arquivo -> alvo.pasta.list()?.toList().orEmpty()
        else -> emptyList()
    }

    /**
     * Rosto ou etiqueta refeitos no Tratamento: a foto anterior do MESMO tipo e
     * da MESMA simulação vai para ARQUIVADAS (com o par "_ORIGINAL"), como a
     * captura da simulação faz e como o aviso de refazer promete.
     *
     * GUARDA: três travas, todas pelo mesmo motivo — mover arquivo errado tira
     * foto da ficha impressa:
     *  - a nova precisa estar gravada; sem ela, arquivar a anterior deixaria a
     *    ficha sem rosto;
     *  - o tipo vem de [FotosArquivadas.tipoConfiavel], que tira o nome do
     *    paciente antes de classificar e devolve `null` na dúvida — e na dúvida
     *    nada se move;
     *  - só no modo de pasta do app. Na pasta escolhida pelo usuário (SAF) nada
     *    é movido: a anterior continua na pasta, e a leitura faz a mais recente
     *    vencer, de modo que ela sai da ficha do mesmo jeito.
     * E só na pasta resolvida, a mesma onde a nova foi gravada.
     */
    private fun arquivarAnterioresDoTipo(tipo: NomeArquivo.Tipo, nomeNovo: String, alvo: PastaAlvo) {
        if (config.pastaFotosUri.isNotBlank()) return
        val pasta = (alvo as? PastaAlvo.Arquivo)?.pasta ?: return
        val nova = File(pasta, nomeNovo)
        if (!nova.isFile || nova.length() == 0L) return
        pasta.listFiles()?.filter { f ->
            f.isFile && f.name != nomeNovo && !f.name.startsWith(".") &&
                !NomeArquivo.ehOriginal(f.name) &&
                NomeArquivo.numeroSimulacao(f.name) == numeroSimulacao &&
                FotosArquivadas.tipoConfiavel(f.name, nomePaciente) == tipo
        }?.forEach { FotosArquivadas.arquivar(it) }
    }

    /**
     * A pasta do paciente já resolvida para uma gravação desta tela.
     *
     * Resolvida UMA vez por operação e passada adiante: a foto, o arquivamento
     * da anterior, a contagem e a ficha caem todos na mesma pasta, em vez de
     * cada passo procurar a sua.
     */
    private sealed class PastaAlvo {
        open val nome: String? get() = null
        class Arquivo(val pasta: File) : PastaAlvo() {
            override val nome: String get() = pasta.name
        }
        class Documento(val pasta: androidx.documentfile.provider.DocumentFile) : PastaAlvo() {
            override val nome: String? get() = pasta.name
        }
        /** Não há pasta do paciente (e não se pediu para criar), ou a pasta SAF não abriu. */
        object Nenhuma : PastaAlvo()
        /** Há pasta com o nome, mas nenhuma é com certeza a deste paciente. */
        object Recusada : PastaAlvo()
    }

    /**
     * Resolve a pasta onde esta tela grava, pela regra de
     * [TreatmentPhotoFetcher.decidirPasta]: a pasta da simulação aberta
     * ([nomePastaServidor]) quando ela existe aqui e é deste prontuário; fora
     * disso, a única pasta aceita pelo prontuário. Pasta de homônima de outro
     * prontuário nunca, e sem prontuário com homônimas também não.
     *
     * GUARDA: casar só pelo NOME não basta. Com "MARIA DA SILVA - 1001" e
     * "MARIA DA SILVA - 2002" no tablet, a primeira pasta da listagem pode ser
     * a da outra: a foto de 2002 seria gravada na pasta de 1001, o rosto de
     * 1001 iria para as arquivadas e a ficha de 1001 seria trocada pela de 2002.
     *
     * Criar só acontece com [criar] e quando NENHUMA pasta casa com o nome.
     * Só disco, nenhuma View: chamar fora da thread principal.
     *
     * @param saf pasta escolhida pelo usuário (SAF) ou pasta do app (File).
     */
    private fun resolverPastaAlvo(criar: Boolean,
                                  saf: Boolean = config.pastaFotosUri.isNotBlank()): PastaAlvo {
        if (saf) {
            val base = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                this, Uri.parse(config.pastaFotosUri)) ?: return PastaAlvo.Nenhuma
            val photos = (if (criar) obterOuCriarPastaDoc(obterOuCriarPastaDoc(base, "PhotoID_RT"), "PHOTOS")
                          else base.findFile("PhotoID_RT")?.findFile("PHOTOS"))
                ?: return PastaAlvo.Nenhuma
            val pastas = photos.listFiles().filter { it.isDirectory }
            return when (val d = TreatmentPhotoFetcher.decidirPasta(
                pastas.mapNotNull { it.name }, nomePaciente, prontuario, nomePastaServidor,
                homonimosNoCadastro)) {
                is TreatmentPhotoFetcher.DecisaoPasta.Existente ->
                    pastas.firstOrNull { it.name == d.nomePasta }
                        ?.let { PastaAlvo.Documento(it) } ?: PastaAlvo.Nenhuma
                TreatmentPhotoFetcher.DecisaoPasta.Nenhuma ->
                    if (!criar) PastaAlvo.Nenhuma
                    else photos.createDirectory(
                        com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(nomePaciente, prontuario))
                        ?.let { PastaAlvo.Documento(it) } ?: PastaAlvo.Nenhuma
                TreatmentPhotoFetcher.DecisaoPasta.Incerta -> PastaAlvo.Recusada
            }
        }
        val photos = com.radioterapia.ai.util.StorageLocal.photos(this)
        val nomes = photos.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        return when (val d = TreatmentPhotoFetcher.decidirPasta(
            nomes, nomePaciente, prontuario, nomePastaServidor, homonimosNoCadastro)) {
            is TreatmentPhotoFetcher.DecisaoPasta.Existente -> PastaAlvo.Arquivo(File(photos, d.nomePasta))
            TreatmentPhotoFetcher.DecisaoPasta.Nenhuma -> {
                val nova = File(photos,
                    com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(nomePaciente, prontuario))
                if (criar && (nova.mkdirs() || nova.isDirectory)) PastaAlvo.Arquivo(nova)
                else PastaAlvo.Nenhuma
            }
            TreatmentPhotoFetcher.DecisaoPasta.Incerta -> PastaAlvo.Recusada
        }
    }

    /**
     * Sem prontuário nesta tela, o cadastro tem outro registro com o mesmo
     * nome? Então nenhuma pasta é com certeza a deste paciente, nem a única do
     * tablet. Lido uma vez, na primeira resolução (fora da thread principal):
     * o cadastro é um JSON inteiro relido a cada construção.
     */
    private val homonimosNoCadastro: Boolean by lazy {
        prontuario.isBlank() && try {
            com.radioterapia.ai.patient.PatientCache(this).temHomonimos(nomePaciente)
        } catch (_: Exception) { false }
    }

    /**
     * A pasta do PHOTOS do app onde moram o Time-Out e as observações desta
     * simulação — inclusive no modo SAF, em que as fotos ficam noutra árvore.
     *
     * Na pasta do app já resolvida para gravar, é ela. Fora disso, o
     * resolvedor por nome e número (StorageLocal.resolverPastaSim) parte do
     * nome da pasta confirmada, e o que ele devolve só vale se
     * [TreatmentPhotoFetcher.pastaDeRegistrosServe] aceitar: a regra dele é
     * mais fraca e pode cair na pasta de uma homônima. `null` quando não
     * serve. Pasta que ainda não existe volta como está: ler não acha nada, e
     * gravar cria a do nome confirmado. Só disco: fora da thread principal.
     */
    private fun pastaDosRegistros(alvo: PastaAlvo): File? {
        (alvo as? PastaAlvo.Arquivo)?.let { return it.pasta }
        if (alvo is PastaAlvo.Recusada) return null
        val confirmada = alvo.nome
        val pasta = com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
            this, nomePaciente, numeroSimulacao, confirmada ?: nomePastaServidor, prontuario)
        if (!pasta.exists()) return pasta
        val nomes = com.radioterapia.ai.util.StorageLocal.photos(this)
            .listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        return pasta.takeIf {
            TreatmentPhotoFetcher.pastaDeRegistrosServe(it.name, confirmada, nomes,
                nomePaciente, prontuario, homonimosNoCadastro)
        }
    }

    /**
     * Uma foto já gravada pode ser alterada aqui (excluir, arquivar,
     * reenquadrar)? Não, quando a pasta do paciente é incerta, nem quando a
     * foto está noutra pasta de paciente que não a resolvida. Só disco:
     * chamar fora da thread principal.
     */
    private fun podeAlterarFotoSalva(arq: File): Boolean = when (val alvo = resolverPastaAlvo(criar = false)) {
        is PastaAlvo.Recusada -> false
        is PastaAlvo.Arquivo -> {
            val pai = arq.parentFile
            // Foto fora do PHOTOS (cópia em cache) segue como sempre; dentro
            // dele, só a da pasta resolvida.
            pai == null || pai.parentFile?.absolutePath != alvo.pasta.parentFile?.absolutePath ||
                pai.absolutePath == alvo.pasta.absolutePath
        }
        else -> true
    }

    private var avisouPastaIncerta = false

    /**
     * Avisa que nada foi gravado porque a pasta do paciente é incerta. Na
     * abertura da tela ([fechar]), sai dela no OK quando o rolo está vazio:
     * fotografar para não poder salvar seria trabalho perdido.
     */
    private fun avisarPastaIncerta(fechar: Boolean) {
        txtProgresso.visibility = View.GONE
        if (fechar && avisouPastaIncerta) return
        avisouPastaIncerta = true
        AlertDialog.Builder(this)
            .setTitle(R.string.warning)
            .setMessage(R.string.ap_pasta_incerta)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ -> if (fechar && roloFotos.isEmpty()) finalizar() }
            .show()
    }

    private fun obterOuCriarPastaDoc(pai: androidx.documentfile.provider.DocumentFile?,
                                     nome: String): androidx.documentfile.provider.DocumentFile? {
        if (pai == null) return null
        return pai.findFile(nome)?.takeIf { it.isDirectory } ?: pai.createDirectory(nome)
    }

    companion object {
        const val EXTRA_NOME = "nome"
        const val EXTRA_PRONTUARIO = "prontuario"
        const val EXTRA_PASTA = "pasta"
        const val EXTRA_NUM_SIMULACAO = "num_sim"
        private const val REQUEST_PERM = 11
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        /** Quebras de linha aceitas na observacao: 3 quebras = 4 linhas. */
        private const val MAX_QUEBRAS_OBS = 3
    }
}
