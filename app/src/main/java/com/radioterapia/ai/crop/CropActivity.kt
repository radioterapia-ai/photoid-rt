package com.radioterapia.ai.crop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.radioterapia.ai.R
import java.io.File
import java.io.FileOutputStream

/**
 * Tela de recorte 16:9. Recebe o caminho de uma foto (EXTRA_PATH), permite
 * zoom/arrasto dentro de uma moldura 16:9 e, ao confirmar, SOBRESCREVE o
 * arquivo com a versão recortada e retorna RESULT_OK.
 */
class CropActivity : AppCompatActivity() {

    private lateinit var cropView: CropImageView
    private lateinit var arquivo: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        val caminho = intent.getStringExtra(EXTRA_PATH)
        if (caminho.isNullOrBlank() || !File(caminho).exists()) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        arquivo = File(caminho)
        cropView = findViewById(R.id.cropView)
        // Proporção opcional da moldura (padrão 16:9). Ex.: logo usa ~2.2:1.
        val aspect = intent.getFloatExtra(EXTRA_ASPECT, 16f / 9f)
        if (aspect > 0f) cropView.frameAspect = aspect

        // Teto de resolucao: esta tela nasceu para o logotipo, que e pequeno, e
        // agora recebe foto de camera em quadro cheio.
        val bm = com.radioterapia.ai.util.ImagemUtils.decodificarComExif(File(caminho), 2400)
        if (bm == null) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        cropView.definirBitmap(bm)

        findViewById<Button>(R.id.btnCropCancelar).setOnClickListener {
            setResult(Activity.RESULT_CANCELED, Intent().apply {
                putExtra(EXTRA_ARQUIVO_TMP,
                    intent.getStringExtra(EXTRA_ARQUIVO_TMP) ?: arquivo.absolutePath)
            })
            finish()
        }
        findViewById<Button>(R.id.btnCropGirar).setOnClickListener { cropView.girar() }
        findViewById<Button>(R.id.btnCropConfirmar).setOnClickListener { confirmar() }
        com.radioterapia.ai.util.UiText.uniformizar(
            findViewById(R.id.btnCropCancelar),
            findViewById(R.id.btnCropGirar),
            findViewById(R.id.btnCropConfirmar))
    }

    private fun confirmar() {
        val recorte = cropView.recortar()
        if (recorte == null) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            FileOutputStream(arquivo).use { out ->
                recorte.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(EXTRA_ARQUIVO_TMP,
                    intent.getStringExtra(EXTRA_ARQUIVO_TMP) ?: arquivo.absolutePath)
            })
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    companion object {
        const val EXTRA_PATH = "crop_path"

        /**
         * Caminho devolvido no resultado, para quem chamou saber QUAL foto foi
         * recortada. Necessario quando varias passam pela tela em fila — sem
         * isso, quem importa cinco fotos de uma vez nao consegue casar cada
         * resultado com o seu arquivo.
         */
        const val EXTRA_ARQUIVO_TMP = "crop_tmp"
        const val EXTRA_ASPECT = "crop_aspect"
    }
}
