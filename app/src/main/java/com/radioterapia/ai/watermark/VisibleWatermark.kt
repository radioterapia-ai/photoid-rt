package com.radioterapia.ai.watermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Aplica marca d'água VISÍVEL nas fotos de posicionamento:
 *   - Faixa preta semi-transparente na parte inferior
 *   - Linha 1 (texto branco, bold): nome completo do paciente
 *   - Linha 2 (texto branco): data de nascimento
 *
 * Aplica APENAS em fotos de posicionamento. Etiqueta, rosto e acessórios ficam intactos.
 *
 * Reescreve o arquivo original (não cria cópia) — preserva orientação EXIF.
 */
object VisibleWatermark {

    /**
     * Aplica a faixa de marca d'água diretamente sobre o arquivo dado.
     * @return true em sucesso, false em falha (foto fica sem watermark mas mantém integridade).
     */
    fun aplicar(arquivo: File, nome: String, nascimento: String): Boolean {
        if (!arquivo.exists()) return false

        return try {
            // Preserva orientação EXIF original
            val orientacaoOriginal = try {
                ExifInterface(arquivo.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

            // Carrega bitmap em qualidade total
            val opcoes = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(arquivo.absolutePath, opcoes) ?: return false

            // Desenha a faixa
            val canvas = Canvas(bitmap)
            desenharFaixa(canvas, bitmap.width, bitmap.height, nome, nascimento)

            // Salva sobrescrevendo
            FileOutputStream(arquivo).use { saida ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, saida)
            }
            bitmap.recycle()

            // Restaura orientação EXIF (compress da Bitmap perde isso)
            try {
                val exif = ExifInterface(arquivo.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientacaoOriginal.toString())
                exif.saveAttributes()
            } catch (e: Exception) { /* não crítico */ }

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun desenharFaixa(canvas: Canvas, w: Int, h: Int, nome: String, nascimento: String) {
        val faixaAltura = (h * FAIXA_ALTURA_PCT).toInt()
        val faixaTopo = (h - faixaAltura).toFloat()

        // Faixa preta semi-transparente (60% opacidade)
        val paintFaixa = Paint().apply {
            color = Color.BLACK
            alpha = 153 // 60% de 255
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRect(0f, faixaTopo, w.toFloat(), h.toFloat(), paintFaixa)

        // Tamanho da fonte proporcional à altura da foto
        val tamanhoNome = h * FONTE_NOME_PCT
        val tamanhoNasc = h * FONTE_NASC_PCT

        val paintNome = Paint().apply {
            color = Color.WHITE
            textSize = tamanhoNome
            isFakeBoldText = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val paintNasc = Paint().apply {
            color = Color.WHITE
            textSize = tamanhoNasc
            typeface = Typeface.SANS_SERIF
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // Nome (truncado se muito longo)
        val nomeFinal = ajustarParaCaber(nome.uppercase(), paintNome, w * 0.92f)
        val nascFinal = nascimento.ifBlank { "—" }

        val centroX = w / 2f
        val gap = h * 0.012f
        val totalAltura = tamanhoNome + tamanhoNasc + gap
        val baseTexto = faixaTopo + (faixaAltura - totalAltura) / 2f

        canvas.drawText(nomeFinal, centroX, baseTexto + tamanhoNome, paintNome)
        canvas.drawText(nascFinal, centroX, baseTexto + tamanhoNome + gap + tamanhoNasc, paintNasc)
    }

    private fun ajustarParaCaber(texto: String, paint: Paint, larguraMax: Float): String {
        if (paint.measureText(texto) <= larguraMax) return texto
        var corte = texto.length
        while (corte > 4) {
            val cortado = texto.substring(0, corte) + "…"
            if (paint.measureText(cortado) <= larguraMax) return cortado
            corte--
        }
        return texto
    }

    private const val FAIXA_ALTURA_PCT = 0.10f      // 10% da altura
    private const val FONTE_NOME_PCT = 0.030f       // 3% da altura
    private const val FONTE_NASC_PCT = 0.024f       // 2.4% da altura
}
