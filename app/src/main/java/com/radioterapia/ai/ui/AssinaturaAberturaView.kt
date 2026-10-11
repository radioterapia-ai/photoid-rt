package com.radioterapia.ai.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.radioterapia.ai.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A assinatura da abertura: "PhotoID RT" numa linha e o traço fino embaixo.
 *
 * Mesma construção do [PhotoIdHeaderView] — prefixo branco, "RT" no gradiente
 * azul → violeta medido na largura de "RT", face bold do sistema —, mas é uma
 * View à parte: o cabeçalho é compartilhado pelas telas de operação, e animar
 * ou reescalar por ele mudaria todas.
 *
 * O desenho é função do tempo ([tempoMs]). [iniciar] conduz a sequência uma
 * vez e avisa quando ela assenta; mostrar o quadro final é só pôr o tempo no
 * fim.
 *
 * CENTRO NA ÁREA ÚTIL: o conjunto se centraliza nos limites da View, menos o
 * preenchimento. Quem a põe na tela a põe na área útil (ver
 * [AberturaSobreposta]).
 *
 * ENCAIXE PROPORCIONAL: a referência é de tablet (texto em 88 sp, traço de
 * 400 × 3 dp). Quando a área útil ou a fonte ampliada pelo usuário não
 * comportam o conjunto, texto, traço, vão e deslocamentos encolhem pelo mesmo
 * fator. A marca nunca quebra linha, nunca é cortada e nunca se deforma.
 */
class AssinaturaAberturaView(context: Context) : View(context) {

    /** Instante da abertura, em ms do movimento nominal (0 a [AssinaturaMovimento.TOTAL_MS]). */
    var tempoMs: Float = 0f
        set(valor) {
            val v = valor.coerceIn(0f, AssinaturaMovimento.TOTAL_MS)
            if (v != field) { field = v; invalidate() }
        }

    private val densidade = resources.displayMetrics.density

    private val gradiente = intArrayOf(
        ContextCompat.getColor(context, R.color.grad_start),
        ContextCompat.getColor(context, R.color.grad_mid),
        ContextCompat.getColor(context, R.color.grad_end),
    )

    private val pintaPrefixo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pintaSufixo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pintaTraco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Geometria: refeita a cada layout, não a cada quadro.
    private var escala = 1f
    private var xPrefixo = 0f
    private var xSufixo = 0f
    private var linhaBase = 0f
    private var pivoSufixoX = 0f
    private var pivoSufixoY = 0f
    private val traco = RectF()
    private var raioTraco = 0f

    private var animador: ValueAnimator? = null
    private var aoAssentar: (() -> Unit)? = null

    /**
     * Desenha a sequência uma vez e chama [aoAssentar] uma vez, quando ela
     * assenta ([AssinaturaMovimento.PARTIDA_MS]) ou termina, o que vier antes.
     */
    fun iniciar(aoAssentar: () -> Unit) {
        if (animador != null) return
        this.aoAssentar = aoAssentar
        animador = ValueAnimator.ofFloat(0f, AssinaturaMovimento.TOTAL_MS).apply {
            duration = AssinaturaMovimento.TOTAL_MS.toLong()
            // Linear de propósito: a curva de cada trilha é aplicada no quadro.
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                tempoMs = t
                if (t >= AssinaturaMovimento.PARTIDA_MS) assentou()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tempoMs = AssinaturaMovimento.TOTAL_MS
                    assentou()
                }
            })
            start()
        }
    }

    /** Vai ao quadro final na hora e avisa, se ainda não avisou. */
    fun encerrar() {
        parar()
        tempoMs = AssinaturaMovimento.TOTAL_MS
        assentou()
    }

    private fun assentou() {
        val f = aoAssentar ?: return
        aoAssentar = null
        f()
    }

    /** Sem disparar o fim: cancelar chamaria onAnimationEnd. */
    private fun parar() {
        animador?.let {
            it.removeAllUpdateListeners()
            it.removeAllListeners()
            it.cancel()
        }
        animador = null
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // A tela saiu de cena no meio da abertura (o botão Início, outra tela
        // por cima): encerra já. Ao voltar, não há sequência a repetir.
        if (visibility != VISIBLE && animador != null) encerrar()
    }

    override fun onDetachedFromWindow() {
        parar()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        montarGeometria(right - left, bottom - top)
    }

    private fun montarGeometria(w: Int, h: Int) {
        val uw = w - paddingLeft - paddingRight
        val uh = h - paddingTop - paddingBottom
        if (uw <= 0 || uh <= 0) return
        // sp pelo TypedValue: segue a fonte ampliada pelo usuário, inclusive a
        // escala não linear do Android 14.
        val corpoRef = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, AssinaturaMovimento.TEXTO_SP, resources.displayMetrics)
        pintaPrefixo.textSize = corpoRef
        pintaSufixo.textSize = corpoRef

        val tinta = Rect()
        pintaPrefixo.getTextBounds(NOME, 0, NOME.length, tinta)
        val margem = AssinaturaMovimento.MARGEM_DP * densidade
        escala = AssinaturaMovimento.escalaDeEncaixe(
            larguraNome = pintaPrefixo.measureText(PREFIXO) + pintaSufixo.measureText(SUFIXO),
            larguraTraco = AssinaturaMovimento.TRACO_LARGURA_DP * densidade,
            alturaConjunto = -tinta.top + pintaPrefixo.fontMetrics.descent +
                (AssinaturaMovimento.VAO_TRACO_DP + AssinaturaMovimento.TRACO_ALTURA_DP) * densidade,
            larguraUtil = uw - 2 * margem,
            alturaUtil = uh - 2 * margem,
        )

        val corpo = corpoRef * escala
        pintaPrefixo.textSize = corpo
        pintaSufixo.textSize = corpo
        val larguraPrefixo = pintaPrefixo.measureText(PREFIXO)
        val larguraSufixo = pintaSufixo.measureText(SUFIXO)
        xPrefixo = paddingLeft + (uw - (larguraPrefixo + larguraSufixo)) / 2f
        xSufixo = xPrefixo + larguraPrefixo

        // CENTRO PELA TINTA, não pela caixa da fonte: o que o olho centraliza é
        // do alto das letras à base do traço. A caixa da fonte tem ar acima das
        // maiúsculas e puxaria o conjunto para baixo.
        pintaPrefixo.getTextBounds(NOME, 0, NOME.length, tinta)
        val fm = pintaPrefixo.fontMetrics
        val vao = AssinaturaMovimento.VAO_TRACO_DP * densidade * escala
        val alturaTraco = max(1f, AssinaturaMovimento.TRACO_ALTURA_DP * densidade * escala)
        // O vão de 28 dp conta da CAIXA TIPOGRÁFICA (a descida da fonte), e não
        // da tinta: é assim que a marca é desenhada. Só o centro usa a tinta.
        val doTopoDaTintaAoFimDoTraco = -tinta.top + fm.descent + vao + alturaTraco
        linhaBase = paddingTop + (uh - doTopoDaTintaAoFimDoTraco) / 2f - tinta.top

        val larguraTraco = AssinaturaMovimento.TRACO_LARGURA_DP * densidade * escala
        val centroX = paddingLeft + uw / 2f
        val topoTraco = linhaBase + fm.descent + vao
        traco.set(centroX - larguraTraco / 2f, topoTraco, centroX + larguraTraco / 2f, topoTraco + alturaTraco)
        raioTraco = alturaTraco / 2f

        // O gradiente do "RT" mede a largura de "RT", não a da marca inteira.
        pintaSufixo.shader = LinearGradient(
            xSufixo, 0f, xSufixo + larguraSufixo, 0f, gradiente, null, Shader.TileMode.CLAMP)
        // O do traço mede o traço inteiro; a escala horizontal o comprime junto.
        pintaTraco.shader = LinearGradient(
            traco.left, 0f, traco.right, 0f, gradiente, null, Shader.TileMode.CLAMP)

        pivoSufixoX = xSufixo + larguraSufixo / 2f
        pivoSufixoY = linhaBase + (fm.ascent + fm.descent) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = AssinaturaMovimento.quadro(tempoMs)
        val dp = densidade * escala

        // PhotoID
        pintaPrefixo.alpha = (q.alfaPrefixo * 255f).roundToInt()
        canvas.drawText(PREFIXO_DESENHO, xPrefixo, linhaBase + q.descidaPrefixoDp * dp, pintaPrefixo)

        // RT
        pintaSufixo.alpha = (q.alfaSufixo * 255f).roundToInt()
        val salvo = canvas.save()
        canvas.translate(0f, q.descidaSufixoDp * dp)
        canvas.scale(q.escalaSufixo, q.escalaSufixo, pivoSufixoX, pivoSufixoY)
        canvas.drawText(SUFIXO, xSufixo, linhaBase, pintaSufixo)
        canvas.restoreToCount(salvo)

        // Traço
        if (q.escalaTraco > 0f && q.alfaTraco > 0f) {
            pintaTraco.alpha = (q.alfaTraco * 255f).roundToInt()
            val s2 = canvas.save()
            canvas.scale(q.escalaTraco, 1f, traco.centerX(), traco.centerY())
            canvas.drawRoundRect(traco, raioTraco, raioTraco, pintaTraco)
            canvas.restoreToCount(s2)
        }
    }

    private companion object {
        /** Com o espaço: o "RT" começa onde o cabeçalho das telas o põe. */
        const val PREFIXO = "PhotoID "
        const val PREFIXO_DESENHO = "PhotoID"
        const val SUFIXO = "RT"
        const val NOME = "PhotoID RT"
    }
}

/**
 * O movimento da abertura, sem Android: trilhas, curva, quadro e encaixe.
 *
 * Os números ficam aqui, e não espalhados pela View, para que o teste confira
 * cada um sem aparelho.
 */
object AssinaturaMovimento {

    const val TEXTO_SP = 88f
    const val TRACO_LARGURA_DP = 400f
    const val TRACO_ALTURA_DP = 3f
    const val VAO_TRACO_DP = 28f
    /** Respiro mínimo nas bordas quando o conjunto precisa encolher. */
    const val MARGEM_DP = 24f

    /** Duração nominal do desenho. Não é espera mínima: ver [PARTIDA_MS]. */
    const val TOTAL_MS = 680f

    /** Uma trilha: começa em [inicioMs] e dura [duracaoMs]. */
    data class Trilha(val inicioMs: Float, val duracaoMs: Float) {
        fun progresso(tempoMs: Float): Float = curva((tempoMs - inicioMs) / duracaoMs)
    }

    val PREFIXO = Trilha(0f, 320f)
    val SUFIXO = Trilha(70f, 350f)
    val TRACO = Trilha(210f, 470f)
    val TRILHAS = listOf(PREFIXO, SUFIXO, TRACO)

    /** `f(t) = 1 − (1 − t)⁴`, com t limitado a [0, 1]. */
    fun curva(t: Float): Float {
        val resto = 1f - t.coerceIn(0f, 1f)
        return 1f - resto * resto * resto * resto
    }

    /**
     * Fração do curso a partir da qual a diferença para o quadro final deixa de
     * ser visível: 97 %. No traço de 400 dp, faltam 6 dp de cada lado.
     */
    const val ASSENTADO = 0.97f

    /**
     * Quando a abertura sai de cena: o instante em que TODAS as trilhas
     * passaram de [ASSENTADO].
     *
     * O resto da curva é a cauda onde nada muda a olho; mantê-la na tela seria
     * segurar o técnico só para completar o desenho. Não é um temporizador:
     * deriva da curva e das trilhas, e muda com elas.
     */
    val PARTIDA_MS: Float = run {
        // curva(t) >= A  <=>  (1 − t)⁴ <= 1 − A  <=>  t >= 1 − (1 − A)^(1/4)
        val t = 1f - (1f - ASSENTADO).toDouble().pow(0.25).toFloat()
        TRILHAS.maxOf { it.inicioMs + it.duracaoMs * t }
    }

    /** O estado de cada elemento num instante. */
    data class Quadro(
        val alfaPrefixo: Float,
        val descidaPrefixoDp: Float,
        val alfaSufixo: Float,
        val descidaSufixoDp: Float,
        val escalaSufixo: Float,
        val alfaTraco: Float,
        val escalaTraco: Float,
    )

    fun quadro(tempoMs: Float): Quadro {
        val p = PREFIXO.progresso(tempoMs)
        val r = SUFIXO.progresso(tempoMs)
        val l = TRACO.progresso(tempoMs)
        return Quadro(
            alfaPrefixo = 0.16f + 0.84f * p,
            descidaPrefixoDp = 10f * (1f - p),
            alfaSufixo = 0.08f + 0.92f * r,
            descidaSufixoDp = 8f * (1f - r),
            escalaSufixo = 0.975f + 0.025f * r,
            alfaTraco = l,
            escalaTraco = l,
        )
    }

    /**
     * Fator único para o conjunto caber na área útil, sem nunca aumentar.
     * O mesmo fator vale para texto, traço, vão e deslocamentos.
     */
    fun escalaDeEncaixe(
        larguraNome: Float, larguraTraco: Float, alturaConjunto: Float,
        larguraUtil: Float, alturaUtil: Float,
    ): Float {
        val largura = max(larguraNome, larguraTraco)
        if (largura <= 0f || alturaConjunto <= 0f) return 1f
        if (larguraUtil <= 0f || alturaUtil <= 0f) return 1f
        return min(1f, min(larguraUtil / largura, alturaUtil / alturaConjunto))
    }
}
