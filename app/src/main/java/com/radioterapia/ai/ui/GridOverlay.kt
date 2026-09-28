package com.radioterapia.ai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Desenha uma grade 3x3 (regra dos terços) sobre o visor da câmera.
 *
 * Para usar como `<View android:background="...">` não funciona — precisa
 * substituir o `<View android:id="@+id/gridOverlay">` por
 * `<com.radioterapia.ai.ui.GridOverlay>` no layout.
 */
class GridOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.argb(128, 255, 255, 255)
        strokeWidth = 1f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // 2 linhas verticais (1/3 e 2/3)
        canvas.drawLine(w / 3f, 0f, w / 3f, h, paint)
        canvas.drawLine(2 * w / 3f, 0f, 2 * w / 3f, h, paint)
        // 2 linhas horizontais (1/3 e 2/3)
        canvas.drawLine(0f, h / 3f, w, h / 3f, paint)
        canvas.drawLine(0f, 2 * h / 3f, w, 2 * h / 3f, paint)
    }
}
