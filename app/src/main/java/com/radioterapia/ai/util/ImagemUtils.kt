package com.radioterapia.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Utilidades de imagem compartilhadas. */
object ImagemUtils {

    /**
     * Decodifica o arquivo JÁ aplicando a rotação EXIF (foto "em pé" abre em pé).
     *
     * @param ladoMax teto do lado maior, em pixels. `0` decodifica em resolução
     *   cheia — só serve para imagem pequena e de tamanho conhecido, como o
     *   logotipo. Para foto de câmera, passar um teto.
     */
    @JvmOverloads
    fun decodificarComExif(arq: File, ladoMax: Int = 0): Bitmap? {
        val bm = try {
            BitmapFactory.decodeFile(arq.absolutePath, opcoesComTeto(arq, ladoMax))
        } catch (_: Throwable) {
            // Throwable, e não Exception: falta de memória chega como
            // OutOfMemoryError, que é Error. Um catch de Exception aqui
            // deixaria o app fechar em vez de devolver null — foi assim que
            // getDisplay() derrubou a captura no tablet antigo.
            null
        } ?: return null
        val graus = try {
            when (ExifInterface(arq.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (graus == 0f) return bm
        val m = Matrix().apply { postRotate(graus) }
        val rot = try {
            Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        } catch (_: Throwable) {
            // Girar aloca uma SEGUNDA cópia do bitmap. Se não couber, é melhor
            // devolver a foto sem girar do que fechar o app com a foto na mão.
            return bm
        }
        if (rot != bm) bm.recycle()
        return rot
    }

    /**
     * Calcula o `inSampleSize` que mantém o lado maior abaixo do teto.
     *
     * Uma foto de 12 MP ocupa cerca de 48 MB em ARGB_8888, e girar aloca outra
     * cópia do mesmo tamanho. O tablet da sala não tem essa folga, e o estouro
     * chega como OutOfMemoryError — que não é Exception e não é pego por
     * `try/catch (Exception)`. Subamostrar na própria decodificação evita o
     * problema em vez de tentar tratá-lo depois.
     *
     * O teto não custa qualidade de impressão: 2400 px no lado maior, numa foto
     * de ~120 mm na ficha, passa de 400 dpi.
     */
    private fun opcoesComTeto(arq: File, ladoMax: Int): BitmapFactory.Options? {
        if (ladoMax <= 0) return null
        return try {
            val medir = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(arq.absolutePath, medir)
            val maior = maxOf(medir.outWidth, medir.outHeight)
            if (maior <= 0) return null
            var amostra = 1
            while (maior / amostra > ladoMax) amostra *= 2
            BitmapFactory.Options().apply { inSampleSize = amostra }
        } catch (_: Throwable) { null }
    }

    /**
     * Pós-processamento de documento escaneado (etiquetas): realça o contraste
     * e clareia levemente — melhora MUITO a nitidez na impressão em P&B.
     * Regrava o próprio arquivo em JPEG.
     */
    fun aplicarContrasteDocumento(arquivo: java.io.File) {
        try {
            val original = android.graphics.BitmapFactory.decodeFile(arquivo.absolutePath) ?: return
            val saida = android.graphics.Bitmap.createBitmap(
                original.width, original.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(saida)
            val contraste = 1.35f
            val brilho = 10f
            val desloc = (-0.5f * contraste + 0.5f) * 255f + brilho
            val matriz = android.graphics.ColorMatrix(floatArrayOf(
                contraste, 0f, 0f, 0f, desloc,
                0f, contraste, 0f, 0f, desloc,
                0f, 0f, contraste, 0f, desloc,
                0f, 0f, 0f, 1f, 0f))
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(matriz)
                isAntiAlias = true
            }
            canvas.drawBitmap(original, 0f, 0f, paint)
            original.recycle()
            java.io.FileOutputStream(arquivo).use { out ->
                saida.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
            saida.recycle()
        } catch (_: Exception) { /* mantém a imagem original */ }
    }
}
