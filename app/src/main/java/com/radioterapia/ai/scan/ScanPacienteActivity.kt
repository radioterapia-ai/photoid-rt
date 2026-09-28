package com.radioterapia.ai.scan

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.radioterapia.ai.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Tela que tira foto da etiqueta ou código de barras e devolve:
 *  - "texto_completo": tudo que o OCR detectou (modo OCR)
 *  - "codigo_barras": valor do código (modo barras)
 *  - "imagem_path": caminho da foto da etiqueta (para o dialog de revisão)
 */
class ScanPacienteActivity : com.radioterapia.ai.BaseActivity() {

    /** UI cheia de câmera de scan - sem toolbar. */
    override fun mostrarToolbar(): Boolean = false

    private lateinit var scanPreview: PreviewView
    private lateinit var btnCapturar: Button
    private lateinit var txtInstrucao: TextView
    private var imageCapture: ImageCapture? = null
    private var modo: String = MODO_OCR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        modo = intent.getStringExtra(EXTRA_MODO) ?: MODO_OCR
        supportActionBar?.title = if (modo == MODO_OCR) "Escanear etiqueta" else getString(R.string.scan_read_barcode)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        scanPreview = findViewById(R.id.scanPreview)
        scanPreview.scaleType = PreviewView.ScaleType.FIT_CENTER
        btnCapturar = findViewById(R.id.btnCapturarScan)
        txtInstrucao = findViewById(R.id.txtInstrucao)

        txtInstrucao.text = if (modo == MODO_OCR) {
            getString(R.string.scan_instruction_ocr)
        } else {
            getString(R.string.scan_instruction_barras)
        }

        // Etiqueta: tenta primeiro o scanner de documentos (imagem nítida p/ OCR).
        // Cancelar no scanner volta para esta câmera ao vivo (captura manual).
        if (modo == MODO_OCR) iniciarScannerEtiqueta()

        startCamera()
        btnCapturar.setOnClickListener { capturarEProcessar() }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(scanPreview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturarEProcessar() {
        val imageCapture = imageCapture ?: return
        btnCapturar.isEnabled = false
        btnCapturar.text = getString(R.string.hc_processing)

        val arquivoTemp = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")

        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(arquivoTemp).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, getString(R.string.err_capture, exc.message ?: ""), Toast.LENGTH_LONG).show()
                    btnCapturar.isEnabled = true
                    btnCapturar.text = getString(R.string.hc_capture)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (modo == MODO_OCR) processarOcr(arquivoTemp)
                    else processarCodigoBarras(arquivoTemp)
                }
            }
        )
    }

    private fun processarOcr(arquivo: File) {
        val bitmap = BitmapFactory.decodeFile(arquivo.absolutePath)
        if (bitmap == null) {
            erroProcessamento("Não foi possível ler a imagem.")
            return
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { resultado ->
                val texto = resultado.text
                if (texto.isBlank()) {
                    erroProcessamento(getString(R.string.scan_label_quick_fail))
                    return@addOnSuccessListener
                }
                val intent = Intent().apply {
                    putExtra(RESULT_TEXTO_OCR, texto)
                    putExtra(RESULT_IMAGEM_PATH, arquivo.absolutePath)
                }
                setResult(RESULT_OK, intent)
                finish()
            }
            .addOnFailureListener { e ->
                erroProcessamento("Erro no OCR: ${e.message}")
            }
    }

    private fun processarCodigoBarras(arquivo: File) {
        val bitmap = BitmapFactory.decodeFile(arquivo.absolutePath)
        if (bitmap == null) {
            erroProcessamento("Não foi possível ler a imagem.")
            return
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { codigos ->
                if (codigos.isEmpty()) {
                    erroProcessamento(getString(R.string.scan_no_barcode))
                    return@addOnSuccessListener
                }
                val codigo = codigos.first().rawValue ?: ""
                if (codigo.isBlank()) {
                    erroProcessamento(getString(R.string.scan_barcode_empty))
                    return@addOnSuccessListener
                }
                val intent = Intent().apply {
                    putExtra(RESULT_CODIGO_BARRAS, codigo)
                    putExtra(RESULT_IMAGEM_PATH, arquivo.absolutePath)
                }
                setResult(RESULT_OK, intent)
                finish()
            }
            .addOnFailureListener { e ->
                erroProcessamento("Erro ao ler código: ${e.message}")
            }
    }

    private fun erroProcessamento(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        btnCapturar.isEnabled = true
        btnCapturar.text = getString(R.string.hc_capture)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ===== Etiqueta via Document Scanner: imagem plana/contrastada => OCR muito
    // mais confiável. Cancelar no scanner cai na câmera ao vivo (captura manual). =====
    private val scannerEtqLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val res = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
            .fromActivityResultIntent(result.data) ?: return@registerForActivityResult
        val pagina = res.pages?.firstOrNull() ?: return@registerForActivityResult
        try {
            val tmp = java.io.File(cacheDir, "etq_scan_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(pagina.imageUri)?.use { inp ->
                java.io.FileOutputStream(tmp).use { out -> inp.copyTo(out) }
            }
            if (tmp.exists() && tmp.length() > 0) processarOcr(tmp)
        } catch (_: Exception) {}
    }

    /** Tenta abrir o scanner de documentos para a etiqueta. Em falha, segue a
     *  câmera ao vivo desta tela normalmente (fallback silencioso). */
    private fun iniciarScannerEtiqueta() {
        try {
            val opts = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(1)
                .setResultFormats(
                    com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(
                    com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_BASE)
                .build()
            com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(opts)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    scannerEtqLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { /* segue câmera ao vivo */ }
        } catch (_: Throwable) { /* segue câmera ao vivo */ }
    }

    companion object {
        const val EXTRA_MODO = "modo"
        const val MODO_OCR = "ocr"
        const val MODO_BARRAS = "barras"
        const val RESULT_TEXTO_OCR = "texto_ocr"
        const val RESULT_CODIGO_BARRAS = "codigo_barras"
        const val RESULT_IMAGEM_PATH = "imagem_path"
    }
}

