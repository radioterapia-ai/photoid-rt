package com.radioterapia.ai.branding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Gerencia o logotipo da empresa que aparece no PDF da simulação.
 * O logo é salvo internamente (filesDir) com tamanho otimizado (max 800x800px).
 */
class LogoManager(private val context: Context) {

    private val arquivo: File = File(context.filesDir, "logo_empresa.png")

    fun temLogo(): Boolean = arquivo.exists() && arquivo.length() > 0

    fun obterArquivo(): File? = if (temLogo()) arquivo else null

    fun obterBitmap(): Bitmap? {
        if (!temLogo()) return null
        return try {
            BitmapFactory.decodeFile(arquivo.absolutePath)
        } catch (e: Exception) { null }
    }

    /**
     * Salva o logo a partir de um Uri (geralmente vindo do seletor de imagens).
     * Redimensiona para max 800x800 mantendo proporção, salva como PNG.
     */
    fun salvarLogo(uri: Uri): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            val bitmapOriginal = BitmapFactory.decodeStream(input)
            input.close()
            if (bitmapOriginal == null) return false

            val bitmapRedim = redimensionar(bitmapOriginal, MAX_DIMENSAO)
            FileOutputStream(arquivo).use { saida ->
                bitmapRedim.compress(Bitmap.CompressFormat.PNG, 100, saida)
            }
            if (bitmapRedim != bitmapOriginal) bitmapRedim.recycle()
            bitmapOriginal.recycle()
            true
        } catch (e: Exception) { false }
    }

    fun removerLogo() {
        if (arquivo.exists()) arquivo.delete()
    }

    private fun redimensionar(b: Bitmap, max: Int): Bitmap {
        val w = b.width
        val h = b.height
        if (w <= max && h <= max) return b
        val ratio = w.toFloat() / h.toFloat()
        val (novoW, novoH) = if (w > h) max to (max / ratio).toInt()
                             else (max * ratio).toInt() to max
        return Bitmap.createScaledBitmap(b, novoW, novoH, true)
    }

    companion object {
        private const val MAX_DIMENSAO = 800
    }
}

