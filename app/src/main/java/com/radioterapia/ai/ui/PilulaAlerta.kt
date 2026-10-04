package com.radioterapia.ai.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pílula de alerta clínico: cápsula de 32dp com ⚠ e o texto do alerta.
 *
 * Uma implementação só para as telas que a mostram (resumo do Tratamento e
 * cartão de resumo da finalização). É a forma que espelha a tarja da ficha de
 * Time-Out impressa, e duas cópias divergiriam na primeira vez que só uma
 * mudasse.
 */
object PilulaAlerta {

    /**
     * @param fundoRes drawable da pílula (pill_alert_orange, _red ou _yellow).
     * @param cor cor do texto e do ⚠: branco no laranja e no vermelho, quase
     *   preto no amarelo, que é o que mantém o contraste legível.
     */
    fun criar(ctx: Context, texto: String, fundoRes: Int, cor: Int): View {
        val densidade = ctx.resources.displayMetrics.density
        fun dpPx(v: Int): Int = (v * densidade + 0.5f).toInt()
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(fundoRes)
            setPadding(dpPx(14), 0, dpPx(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dpPx(32)
            ).apply { bottomMargin = dpPx(7) }
        }
        ll.addView(TextView(ctx).apply {
            text = "⚠"; textSize = 15f; setTextColor(cor)
        })
        ll.addView(TextView(ctx).apply {
            text = texto
            textSize = 12f
            setTextColor(cor)
            letterSpacing = 0.05f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpPx(7) }
        })
        return ll
    }
}
