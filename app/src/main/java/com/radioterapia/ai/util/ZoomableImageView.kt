package com.radioterapia.ai.util

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView com zoom por pinça, arrasto e duplo-toque (reset/2.5x).
 * Pensada para conviver com ViewPager2: enquanto o zoom está ativo (>1x),
 * pede ao pai para NÃO interceptar o toque (o swipe de página fica suspenso);
 * ao voltar a 1x, o swipe do carrossel volta a funcionar normalmente.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrizZoom = Matrix()
    private var escala = 1f
    private val escalaMin = 1f
    private val escalaMax = 6f

    // Deslocamento atual (pan)
    private var transX = 0f
    private var transY = 0f

    private var ultimoX = 0f
    private var ultimoY = 0f
    private var arrastando = false

    private val detectorEscala = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val nova = (escala * d.scaleFactor).coerceIn(escalaMin, escalaMax)
                if (nova != escala) {
                    // Mantém o ponto sob os dedos fixo ao escalar
                    val fator = nova / escala
                    transX = d.focusX - (d.focusX - transX) * fator
                    transY = d.focusY - (d.focusY - transY) * fator
                    escala = nova
                    aplicar()
                }
                return true
            }
        })

    private val detectorGestos = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (escala > 1.01f) resetarZoom()
                else {
                    // Aproxima 2.5x centrado no toque
                    val alvo = 2.5f
                    transX = e.x - (e.x - transX) * (alvo / escala)
                    transY = e.y - (e.y - transY) * (alvo / escala)
                    escala = alvo
                    aplicar()
                }
                return true
            }
        })

    init {
        scaleType = ScaleType.FIT_CENTER
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // Nova imagem: zera o zoom para não "herdar" o estado da página anterior
        resetarZoom()
    }

    fun resetarZoom() {
        escala = 1f; transX = 0f; transY = 0f
        scaleType = ScaleType.FIT_CENTER
        imageMatrix = Matrix()
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun aplicar() {
        if (escala <= 1.01f) { resetarZoom(); return }
        scaleType = ScaleType.MATRIX
        // Base FIT_CENTER calculada manualmente + zoom/pan do usuário
        val d = drawable ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return
        val base = minOf(vw / dw, vh / dh)
        val bx = (vw - dw * base) / 2f
        val by = (vh - dh * base) / 2f

        // Limita o pan para a imagem não "fugir" completamente da tela
        val larguraImg = dw * base * escala
        val alturaImg = dh * base * escala
        val minTx = vw - larguraImg - bx * escala
        val minTy = vh - alturaImg - by * escala
        val maxTx = -bx * escala + 0f
        val maxTy = -by * escala + 0f
        if (larguraImg > vw) transX = transX.coerceIn(minTx + bx * escala * 0, maxTx).coerceIn(vw - larguraImg, 0f)
        else transX = (vw - larguraImg) / 2f
        if (alturaImg > vh) transY = transY.coerceIn(vh - alturaImg, 0f)
        else transY = (vh - alturaImg) / 2f

        matrizZoom.reset()
        matrizZoom.postScale(base * escala, base * escala)
        matrizZoom.postTranslate(transX, transY)
        imageMatrix = matrizZoom
        // Com zoom ativo, o carrossel não deve "roubar" o gesto
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detectorEscala.onTouchEvent(event)
        detectorGestos.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ultimoX = event.x; ultimoY = event.y
                arrastando = escala > 1.01f
                if (arrastando) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (escala > 1.01f && !detectorEscala.isInProgress && event.pointerCount == 1) {
                    transX += event.x - ultimoX
                    transY += event.y - ultimoY
                    ultimoX = event.x; ultimoY = event.y
                    aplicar()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (escala <= 1.01f) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
