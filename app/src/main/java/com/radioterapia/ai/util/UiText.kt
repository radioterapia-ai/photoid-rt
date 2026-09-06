package com.radioterapia.ai.util

import android.util.TypedValue
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/** Ajustes de texto de UI compartilhados. */
object UiText {

    /**
     * Menor fonte comum de um grupo de botões/labels da mesma tela.
     *
     * Fase 1: liga o auto-ajuste em TODOS (do tamanho atual para baixo, até 8sp),
     * mesmo nos que não têm autoSize no XML — é isso que faz o texto encolher
     * para caber em telas pequenas.
     * Fase 2 (pós-layout): mede o MENOR tamanho resultante e fixa esse mesmo
     * tamanho em todos — visual uniforme entre os botões.
     */
    // Tamanho MÁXIMO original (do XML) e última largura medida, por botão.
    private val uniMaxPx = java.util.WeakHashMap<TextView, Int>()
    private val uniLargura = java.util.WeakHashMap<TextView, Int>()

    /** Menor fonte comum entre botões irmãos — versão v3.
     *  • Respeita o maxLines do XML (botões de 2 linhas continuam de 2 linhas).
     *  • NÃO é one-shot: um listener permanente refaz o ciclo sempre que a
     *    LARGURA muda (girar a tela — com ou sem recriação da Activity —,
     *    acordeões abrindo, medidas transitórias da rotação). O congelamento
     *    em px de uma geometria antiga era a causa das fontes "mudando
     *    sozinhas" ao girar o aparelho. */
    fun uniformizar(vararg botoes: TextView) {
        if (botoes.isEmpty()) return
        val dm = botoes.first().resources.displayMetrics
        val minPx = (8f * dm.scaledDensity).toInt().coerceAtLeast(2)
        for (b in botoes) {
            if (uniMaxPx[b] == null)
                uniMaxPx[b] = b.textSize.toInt().coerceAtLeast(minPx + 2)
        }
        fun ligarAutosize() {
            for (b in botoes) try {
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    b, minPx, uniMaxPx[b] ?: (minPx + 2), 1, TypedValue.COMPLEX_UNIT_PX)
            } catch (_: Exception) {}
        }
        fun fixarMenor() {
            try {
                var menor = Float.MAX_VALUE
                for (b in botoes) {
                    val px = b.textSize
                    if (px in 1f..menor) menor = px
                }
                if (menor == Float.MAX_VALUE || menor <= 0f) return
                for (b in botoes) {
                    TextViewCompat.setAutoSizeTextTypeWithDefaults(
                        b, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
                    b.setTextSize(TypedValue.COMPLEX_UNIT_PX, menor)
                }
            } catch (_: Exception) { /* mantém como está */ }
        }
        fun ciclo(v: android.view.View) {
            // 1º passe: autosize religado se ajusta à largura ATUAL;
            // 2º passe: lê o resultado estável e fixa a menor fonte comum.
            ligarAutosize()
            v.post { v.post { fixarMenor() } }
        }
        val alvo = botoes.first()
        alvo.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && uniLargura[alvo] != w) {
                uniLargura[alvo] = w
                ciclo(v)
            }
        }
        if (alvo.width > 0) {
            uniLargura[alvo] = alvo.width
            ciclo(alvo)
        }
    }

    /** Abre o dropdown do AutoComplete escondendo o teclado antes —
     *  evita a lista ficar atrás do teclado em telas pequenas. */
    fun abrirDropdownSemTeclado(act: android.widget.AutoCompleteTextView) {
        try {
            val imm = act.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(act.windowToken, 0)
        } catch (_: Exception) {}
        act.postDelayed({ try { act.showDropDown() } catch (_: Exception) {} }, 120)
    }

    /** Máscara dd/MM/yyyy. A barra é inserida ANTES do próximo dígito (nunca
     *  fica sobrando no fim), senão o backspace trava ao chegar nela. */
    fun aplicarMascaraData(edt: android.widget.EditText) {
        edt.addTextChangedListener(object : android.text.TextWatcher {
            private var editando = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (editando || s == null) return
                editando = true
                val dig = s.toString().filter { it.isDigit() }.take(8)
                val sb = StringBuilder()
                for ((i2, ch) in dig.withIndex()) {
                    if (i2 == 2 || i2 == 4) sb.append('/')
                    sb.append(ch)
                }
                val novo = sb.toString()
                if (novo != s.toString()) {
                    s.replace(0, s.length, novo)
                    try { edt.setSelection(novo.length) } catch (_: Exception) {}
                }
                editando = false
            }
        })
    }
}
