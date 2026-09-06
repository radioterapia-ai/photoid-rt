package com.radioterapia.ai.quality

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.abs

/**
 * Detector leve de qualidade de foto. Roda em ~50ms num bitmap reduzido.
 *
 * Avalia:
 *  - Brilho médio (escura demais? clara demais?)
 *  - Variância de Laplaciano simplificado (mede nitidez)
 *
 * Não bloqueia o usuário — apenas exibe um indicador discreto na UI.
 */
object PhotoQualityDetector {

    enum class Issue {
        NONE,        // foto OK
        TOO_DARK,    // muito escura
        TOO_BRIGHT,  // muito clara/estourada
        BLURRY       // baixa nitidez
    }

    data class Resultado(
        val issue: Issue,
        val brilhoMedio: Float,    // 0..255
        val nitidezScore: Float    // quanto maior, mais nítido
    )

    fun avaliar(arquivo: File): Resultado {
        val bitmap = decodeReduzido(arquivo) ?: return Resultado(Issue.NONE, 128f, 1000f)

        val (brilho, nitidez) = analisar(bitmap)
        bitmap.recycle()

        val issue = when {
            brilho < BRILHO_MIN -> Issue.TOO_DARK
            brilho > BRILHO_MAX -> Issue.TOO_BRIGHT
            nitidez < NITIDEZ_MIN -> Issue.BLURRY
            else -> Issue.NONE
        }
        return Resultado(issue, brilho, nitidez)
    }

    private fun decodeReduzido(arquivo: File): Bitmap? {
        return try {
            val opt = BitmapFactory.Options().apply {
                inSampleSize = 8
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(arquivo.absolutePath, opt)
        } catch (e: Exception) { null }
    }

    private fun analisar(bm: Bitmap): Pair<Float, Float> {
        val w = bm.width
        val h = bm.height
        val passo = 4 // amostra a cada 4 pixels — rápido

        var somaBrilho = 0L
        var contagem = 0L

        // Para nitidez: variância de diferenças entre pixels vizinhos (Laplaciano simplificado)
        var somaDiff = 0.0
        var somaDiffQuad = 0.0
        var nDiffs = 0

        var x = 1
        while (x < w - 1) {
            var y = 1
            while (y < h - 1) {
                val centro = luminancia(bm.getPixel(x, y))
                somaBrilho += centro
                contagem++

                // 4 vizinhos (cima/baixo/esq/dir)
                val cima = luminancia(bm.getPixel(x, y - 1))
                val baixo = luminancia(bm.getPixel(x, y + 1))
                val esq = luminancia(bm.getPixel(x - 1, y))
                val dir = luminancia(bm.getPixel(x + 1, y))
                val laplaciano = abs(4 * centro - cima - baixo - esq - dir)
                somaDiff += laplaciano
                somaDiffQuad += laplaciano.toDouble() * laplaciano
                nDiffs++
                y += passo
            }
            x += passo
        }

        val brilho = if (contagem > 0) (somaBrilho / contagem).toFloat() else 128f
        val media = if (nDiffs > 0) somaDiff / nDiffs else 0.0
        val variancia = if (nDiffs > 0) (somaDiffQuad / nDiffs - media * media).toFloat() else 0f

        return brilho to variancia.coerceAtLeast(0f)
    }

    private fun luminancia(rgb: Int): Int {
        val r = Color.red(rgb)
        val g = Color.green(rgb)
        val b = Color.blue(rgb)
        return ((0.299 * r) + (0.587 * g) + (0.114 * b)).toInt()
    }

    // Limiares (ajustáveis após uso real)
    private const val BRILHO_MIN = 50f
    private const val BRILHO_MAX = 230f
    private const val NITIDEZ_MIN = 80f
}
