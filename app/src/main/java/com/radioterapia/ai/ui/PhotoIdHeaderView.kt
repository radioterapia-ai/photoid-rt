package com.radioterapia.ai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.radioterapia.ai.R
import kotlin.math.max

/**
 * Header tipo "PhotoID RT" / "PhotoID RT Sim" / "PhotoID RT Check".
 *
 * Renderiza em uma única linha:
 *  - Prefixo "PhotoID " em branco sólido (text_primary)
 *  - Sufixo (RT, RT Sim, RT Check) com gradiente AZUL → ROXO (paleta do logo)
 *
 * O sufixo é configurável por setSuffix(). O tamanho da fonte é configurável por
 * setTextSizeSp() para criar hierarquia visual (Home = 56sp, Sim/Check = 44sp).
 */
class PhotoIdHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val prefix = "PhotoID "
    private var suffix = "RT"

    private val paintPrefix = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = sp(48f)
    }

    private val paintSuffix = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = sp(48f)
    }

    init {
        // Lê atributo XML opcional 'photoIdSuffix' (default: "RT") e photoIdSize
        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.PhotoIdHeaderView)
            try {
                val sz = ta.getDimension(
                    R.styleable.PhotoIdHeaderView_android_textSize, sp(48f))
                paintPrefix.textSize = sz
                paintSuffix.textSize = sz
                val txt = ta.getString(R.styleable.PhotoIdHeaderView_android_text)
                if (!txt.isNullOrBlank()) suffix = txt
            } finally { ta.recycle() }
        }
    }

    fun setSuffix(s: String) {
        suffix = s
        requestLayout()
        invalidate()
    }

    fun setTextSizeSp(sp: Float) {
        paintPrefix.textSize = sp(sp)
        paintSuffix.textSize = sp(sp)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wPrefix = paintPrefix.measureText(prefix)
        val wSuffix = paintSuffix.measureText(suffix)
        val largura = (wPrefix + wSuffix + paddingLeft + paddingRight).toInt()
        val fm = paintPrefix.fontMetrics
        val altura = (fm.descent - fm.ascent + paddingTop + paddingBottom).toInt()
        val w = resolveSize(largura, widthMeasureSpec)
        val h = resolveSize(altura, heightMeasureSpec)
        // Aplicar gradiente ao sufixo somente quando temos largura
        if (wSuffix > 0) {
            paintSuffix.shader = LinearGradient(
                wPrefix, 0f, wPrefix + wSuffix, 0f,
                intArrayOf(
                    Color.parseColor("#42A5F5"),
                    Color.parseColor("#7B6FE0"),
                    Color.parseColor("#B254E8")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        setMeasuredDimension(max(w, largura), max(h, altura))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fm = paintPrefix.fontMetrics
        val baseline = paddingTop - fm.ascent
        val xPrefix = paddingLeft.toFloat()
        val wPrefix = paintPrefix.measureText(prefix)
        canvas.drawText(prefix, xPrefix, baseline, paintPrefix)
        canvas.drawText(suffix, xPrefix + wPrefix, baseline, paintSuffix)
    }

    private fun sp(value: Float): Float =
        value * resources.displayMetrics.scaledDensity
}
