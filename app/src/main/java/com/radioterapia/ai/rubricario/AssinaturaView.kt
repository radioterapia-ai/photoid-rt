package com.radioterapia.ai.rubricario

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Quadro em branco onde a pessoa faz a própria rubrica, com o dedo ou a caneta
 * do tablet.
 *
 * O traço é guardado como PATH, não como bitmap da tela: a rubrica é redesenhada
 * na resolução que o PDF pedir, e não fica serrilhada quando a folha é impressa.
 * Guardar o que a View mostrou congelaria a rubrica na densidade daquele tablet.
 *
 * Fundo TRANSPARENTE na exportação. Na tela ele aparece branco (é o papel), mas
 * o PNG salvo não leva fundo: no PDF a rubrica é desenhada sobre a célula da
 * tabela, e um retângulo branco por baixo apagaria a linha da grade.
 */
class AssinaturaView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val tracos = mutableListOf<Path>()
    private var atual: Path? = null

    private companion object {
        /** Teto de largura em relação à altura. Ver [onMeasure]. */
        const val ASPECTO_QUADRO = 2.2f
    }

    private val tinta = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        // ESPESSURA: o traco chega ao PDF reduzido a altura de uma celula de
        // ~20 pt, e o que era 4,5 saia como fio na impressao — legivel na tela
        // do tablet e quase invisivel no papel. A rubrica existe para ser
        // conferida em papel, entao a espessura e dimensionada por ele.
        strokeWidth = 7.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val papel = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val tintaExistente = Paint().apply { alpha = 90; isAntiAlias = true }
    private val linhaGuia = Paint().apply {
        color = Color.parseColor("#CFD8DC"); strokeWidth = 1.5f; isAntiAlias = true
    }

    /** Há traço feito? Usado para não salvar pessoa sem rubrica. */
    fun temTraco(): Boolean = tracos.isNotEmpty()

    /**
     * Limita a LARGURA do quadro em relação à altura.
     *
     * Ocupando a largura inteira do tablet, o quadro virava uma faixa de mais de
     * 3:1 e as rubricas saíam de todos os tamanhos: num espaço muito maior que o
     * gesto, cada pessoa assina onde acha, e o recorte no traço devolvia
     * proporções que não se pareciam entre si. Numa área com a forma de uma
     * linha de assinatura, o gesto sai mais parecido com o do papel.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val h = measuredHeight
        val maxW = (h * ASPECTO_QUADRO).toInt()
        if (h > 0 && measuredWidth > maxW) setMeasuredDimension(maxW, h)
    }

    /**
     * Rubrica JÁ GRAVADA desta pessoa, mostrada como referência na edição.
     *
     * A tela abria em branco, e quem editava um nome não tinha como saber se
     * havia rubrica gravada nem qual era — parecia cadastro incompleto, e a
     * reação natural era assinar de novo sem necessidade.
     *
     * É só imagem: o traço não é reconstituível a partir do PNG. Por isso ela
     * não entra em [tracos] e não é reexportada; enquanto ninguém assina por
     * cima, o PNG antigo continua valendo.
     */
    private var existente: Bitmap? = null

    /** Disparado quando alguém toca no quadro tendo rubrica gravada. */
    var aoTentarSobrescrever: (() -> Unit)? = null

    fun definirExistente(bmp: Bitmap?) {
        existente = bmp; invalidate()
    }

    fun temExistente(): Boolean = existente != null

    /** Descarta a rubrica antiga e libera o quadro para uma nova. */
    fun liberarParaNovaRubrica() {
        existente = null; tracos.clear(); atual = null; invalidate()
    }

    fun limpar() {
        tracos.clear(); atual = null; existente = null; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), papel)
        // Linha de apoio, como a de um papel de assinatura.
        val yGuia = height * 0.78f
        canvas.drawLine(width * 0.06f, yGuia, width * 0.94f, yGuia, linhaGuia)
        // Rubrica gravada, esmaecida: presente o bastante para ser reconhecida,
        // apagada o bastante para nao ser confundida com traco novo.
        existente?.let { bmp ->
            try {
                val esc = minOf(width * 0.86f / bmp.width, height * 0.7f / bmp.height)
                val w = bmp.width * esc
                val h = bmp.height * esc
                val x = (width - w) / 2f
                val y = (height - h) / 2f
                canvas.drawBitmap(bmp, null,
                    android.graphics.RectF(x, y, x + w, y + h), tintaExistente)
            } catch (_: Exception) {}
        }
        tracos.forEach { canvas.drawPath(it, tinta) }
        atual?.let { canvas.drawPath(it, tinta) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Tocar com rubrica gravada NAO comeca um traco: avisa quem
                // chamou, que confirma a substituicao. O toque e engolido de
                // proposito — se o primeiro traco ja fosse aceito, a rubrica
                // antiga estaria perdida antes da pergunta.
                if (existente != null) {
                    aoTentarSobrescrever?.invoke()
                    return true
                }
                // Sem isto, um ScrollView pai rouba o gesto no primeiro
                // movimento e a rubrica sai picotada.
                parent?.requestDisallowInterceptTouchEvent(true)
                atual = Path().apply { moveTo(event.x, event.y) }
            }
            MotionEvent.ACTION_MOVE -> atual?.lineTo(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                atual?.let { tracos.add(it) }
                atual = null
            }
            else -> return super.onTouchEvent(event)
        }
        invalidate()
        return true
    }

    /**
     * Exporta a rubrica em PNG com fundo transparente, recortada no traço.
     *
     * O recorte importa: sem ele o PNG carrega a moldura inteira do quadro, e no
     * PDF a rubrica apareceria minúscula no meio de uma célula vazia. Recortada,
     * ela ocupa a célula de verdade.
     *
     * @param escala multiplicador de resolução, para a rubrica não serrilhar na
     *   impressão. 3x sobre um quadro de ~300 dp já cobre 300 dpi em A4.
     */
    fun exportar(escala: Float = 3f): Bitmap? {
        if (tracos.isEmpty() || width <= 0 || height <= 0) return null
        return try {
            val bmp = Bitmap.createBitmap(
                (width * escala).toInt(), (height * escala).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(escala, escala)
            val t = Paint(tinta).apply { strokeWidth = tinta.strokeWidth }
            tracos.forEach { c.drawPath(it, t) }
            recortarNoTraco(bmp)
        } catch (_: Exception) { null }
    }

    /** Corta a moldura vazia em volta do traço, com uma folga pequena. */
    private fun recortarNoTraco(bmp: Bitmap): Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if ((px[base + x] ushr 24) != 0) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return bmp
        // FOLGA PROPORCIONAL AO TRACO, e nao a largura do quadro.
        //
        // Uma folga fixa em pixels vira uma borda enorme em volta de rubrica
        // pequena e quase nada em volta de rubrica grande — e a folha do
        // rubricario mostra as duas lado a lado. Proporcional ao proprio traco,
        // toda rubrica chega ao PDF com a mesma respiracao relativa, que e o que
        // permite normaliza-las depois pela altura.
        val larguraTraco = x1 - x0 + 1
        val alturaTraco = y1 - y0 + 1
        val folgaX = (larguraTraco * 0.06f).toInt().coerceAtLeast(4)
        val folgaY = (alturaTraco * 0.12f).toInt().coerceAtLeast(4)
        x0 = (x0 - folgaX).coerceAtLeast(0); y0 = (y0 - folgaY).coerceAtLeast(0)
        x1 = (x1 + folgaX).coerceAtMost(w - 1); y1 = (y1 + folgaY).coerceAtMost(h - 1)
        return Bitmap.createBitmap(bmp, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}
