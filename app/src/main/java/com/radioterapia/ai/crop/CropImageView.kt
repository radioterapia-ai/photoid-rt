package com.radioterapia.ai.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * View de recorte com pinça-zoom e arrasto, com uma MOLDURA FIXA 16:9.
 *
 * O usuário move e amplia a imagem por trás da moldura; o recorte é a região
 * que fica dentro dela.
 *
 * A IMAGEM PODE FICAR MENOR QUE A MOLDURA. Antes não podia: o zoom parava
 * quando a imagem cobria a moldura inteira, o que garante recorte sem áreas
 * vazias mas torna impossível aproveitar uma foto em pé. O caso real: o
 * tecnólogo fotografou o rosto de muito perto, em retrato; ao girar para
 * horizontal, o rosto não cabia no recorte 16:9 em nenhuma posição, e a única
 * saída era refazer a foto — com o paciente já fora da sala.
 *
 * Quando a imagem não preenche a moldura, o que sobra nas laterais é pintado
 * com [COR_PREENCHIMENTO], um branco levemente acinzentado. Cinza e não branco
 * puro porque na folha impressa a faixa precisa se distinguir do papel: quem
 * confere tem que ver que a foto não preenche o quadro, e não achar que a
 * impressão saiu cortada.
 */
class CropImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private companion object {
        /** Branco levemente acinzentado das laterais vazias. */
        const val COR_PREENCHIMENTO = 0xFFF2F2F0.toInt()
        /** Teto do zoom, em múltiplos da escala que cobre a moldura. */
        const val ZOOM_MAX = 6f
    }

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()

    private val frame = RectF()            // moldura em coords da view
    /** Proporção da moldura (largura/altura). Padrão 16:9; ajustável (ex.: logo 2.2:1). */
    var frameAspect = 16f / 9f
        set(value) { field = value; requestLayout(); invalidate() }

    /** Escala em que a imagem COBRE a moldura inteira. É onde o recorte abre. */
    private var escalaCobrir = 1f
    /** Escala em que a imagem CABE inteira dentro da moldura. Piso do zoom. */
    private var escalaEncaixar = 1f
    private var curScale = 1f

    /** Notifica o progresso do zoom (0..100) para sincronizar a barra externa. */
    var onZoomMudou: ((Int) -> Unit)? = null

    // A barra percorre [encaixar .. cobrir*6]. Antes percorria [cobrir .. cobrir*6];
    // com o piso mais baixo, a posição inicial deixa de ser o zero da barra — e
    // isso é informação útil: mostra quanto ainda dá para reduzir.
    private fun progressoAtual(): Int {
        val faixa = (escalaCobrir * ZOOM_MAX) - escalaEncaixar
        if (faixa <= 0f) return 0
        return (((curScale - escalaEncaixar) / faixa) * 100f).toInt().coerceIn(0, 100)
    }
    private fun notificarZoom() { onZoomMudou?.invoke(progressoAtual()) }

    /** Define o zoom a partir da barra (0..100), centrado na moldura. */
    fun setZoomFracao(progresso: Int) {
        val faixa = (escalaCobrir * ZOOM_MAX) - escalaEncaixar
        val alvo = escalaEncaixar + faixa * (progresso.coerceIn(0, 100) / 100f)
        val real = alvo / curScale
        if (real == 1f) return
        matrix.postScale(real, real, frame.centerX(), frame.centerY())
        curScale = alvo
        limitar()
        invalidate()
    }

    private val paintDim = Paint().apply { color = Color.parseColor("#A6000000") }
    private val paintFrame = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
    }
    private val paintGrid = Paint().apply {
        color = Color.parseColor("#80FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val paintFundo = Paint().apply { color = COR_PREENCHIMENTO }
    private val paintImagem = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = d.scaleFactor
                val novo = (curScale * f).coerceIn(escalaEncaixar, escalaCobrir * ZOOM_MAX)
                val real = novo / curScale
                matrix.postScale(real, real, d.focusX, d.focusY)
                curScale = novo
                limitar()
                invalidate()
                notificarZoom()
                return true
            }
        })

    private var lastX = 0f
    private var lastY = 0f

    fun definirBitmap(bm: Bitmap) {
        bitmap = bm
        post { configurarInicial() }
    }

    /** Gira o bitmap 90° no sentido horário e reconfigura o enquadramento. */
    fun girar() {
        val bm = bitmap ?: return
        val m = Matrix().apply { postRotate(90f) }
        val rotacionado = try {
            Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        } catch (_: Throwable) {
            // Girar aloca uma segunda cópia do bitmap. Sem memória, a falta
            // chega como OutOfMemoryError, que é Error e não Exception.
            return
        }
        bitmap = rotacionado
        configurarInicial()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        configurarInicial()
    }

    private fun configurarInicial() {
        val bm = bitmap ?: return
        if (width == 0 || height == 0) return

        // Moldura 16:9 centrada, largura total (com pequena margem)
        val margem = 16f
        var fw = width - margem * 2
        var fh = fw / frameAspect
        if (fh > height - margem * 2) {
            fh = height - margem * 2
            fw = fh * frameAspect
        }
        val left = (width - fw) / 2f
        val top = (height - fh) / 2f
        frame.set(left, top, left + fw, top + fh)

        val sx = frame.width() / bm.width
        val sy = frame.height() / bm.height
        escalaCobrir = max(sx, sy)      // cobre a moldura: sem faixa vazia
        escalaEncaixar = min(sx, sy)    // cabe inteira: pode sobrar faixa

        // ABRE COBRINDO. O enquadramento cheio continua sendo o certo na
        // esmagadora maioria das fotos; reduzir é a exceção, e exceção não
        // deve ser o estado inicial.
        curScale = escalaCobrir

        matrix.reset()
        matrix.postScale(curScale, curScale)
        val dx = frame.centerX() - bm.width * curScale / 2f
        val dy = frame.centerY() - bm.height * curScale / 2f
        matrix.postTranslate(dx, dy)
        limitar()
        invalidate()
        notificarZoom()
    }

    /**
     * Mantém o enquadramento coerente, por eixo.
     *
     * No eixo em que a imagem é MAIOR que a moldura, impede que ela seja
     * arrastada para dentro e deixe faixa vazia por descuido. No eixo em que é
     * MENOR — que só acontece desde que o zoom passou a poder reduzir — não há
     * o que impedir: ali a faixa é intencional, e a imagem fica centrada.
     */
    private fun limitar() {
        val bm = bitmap ?: return
        val vals = FloatArray(9)
        matrix.getValues(vals)
        val transX = vals[Matrix.MTRANS_X]
        val transY = vals[Matrix.MTRANS_Y]
        val escala = vals[Matrix.MSCALE_X]
        val imgW = bm.width * escala
        val imgH = bm.height * escala

        var corrX = 0f
        var corrY = 0f

        if (imgW >= frame.width()) {
            if (transX > frame.left) corrX = frame.left - transX
            else if (transX + imgW < frame.right) corrX = frame.right - (transX + imgW)
        } else {
            corrX = frame.centerX() - (transX + imgW / 2f)
        }

        if (imgH >= frame.height()) {
            if (transY > frame.top) corrY = frame.top - transY
            else if (transY + imgH < frame.bottom) corrY = frame.bottom - (transY + imgH)
        } else {
            corrY = frame.centerY() - (transY + imgH / 2f)
        }

        if (corrX != 0f || corrY != 0f) matrix.postTranslate(corrX, corrY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrix.postTranslate(dx, dy)
                    limitar()
                    invalidate()
                }
                lastX = event.x; lastY = event.y
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = bitmap ?: return

        // FUNDO DA MOLDURA antes da imagem: é o que aparece nas laterais quando
        // a foto foi reduzida além da largura do quadro. Desenhar só a imagem
        // deixaria ali o preto da tela, e o técnico não teria como prever a cor
        // que vai sair no papel.
        canvas.drawRect(frame, paintFundo)
        canvas.save()
        canvas.clipRect(frame)
        canvas.drawBitmap(bm, matrix, paintImagem)
        canvas.restore()

        // Escurece fora da moldura (4 retângulos)
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, paintDim)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), paintDim)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, paintDim)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, paintDim)

        // Moldura + grade (regra dos terços)
        canvas.drawRect(frame, paintFrame)
        val t1x = frame.left + frame.width() / 3f
        val t2x = frame.left + frame.width() * 2f / 3f
        val t1y = frame.top + frame.height() / 3f
        val t2y = frame.top + frame.height() * 2f / 3f
        canvas.drawLine(t1x, frame.top, t1x, frame.bottom, paintGrid)
        canvas.drawLine(t2x, frame.top, t2x, frame.bottom, paintGrid)
        canvas.drawLine(frame.left, t1y, frame.right, t1y, paintGrid)
        canvas.drawLine(frame.left, t2y, frame.right, t2y, paintGrid)
    }

    /**
     * Gera o bitmap recortado: exatamente o que se vê dentro da moldura.
     *
     * DESENHADO NUM CANVAS, e não recortado por aritmética de pixels na imagem
     * de origem. A versão anterior mapeava os cantos da moldura para
     * coordenadas da imagem e chamava `createBitmap` naquele retângulo — o que
     * só funciona enquanto a moldura estiver inteiramente DENTRO da imagem.
     * Agora ela pode ser maior, e a região pedida cairia fora dos limites: a
     * conta era travada por `coerceIn` e devolvia um recorte de proporção
     * errada, sem erro nenhum.
     *
     * Redesenhando, o mesmo caminho serve aos dois casos — sobra da imagem é
     * cortada pelo tamanho do destino, falta é preenchida pelo fundo.
     */
    fun recortar(): Bitmap? {
        val bm = bitmap ?: return null
        if (frame.width() <= 0f || frame.height() <= 0f) return null

        val outW = 1280
        val outH = (outW / frameAspect).toInt()
        return try {
            val saida = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val c = Canvas(saida)
            c.drawColor(COR_PREENCHIMENTO)
            // Leva a moldura para a origem e a redimensiona ao tamanho de saída.
            val m = Matrix(matrix)
            m.postTranslate(-frame.left, -frame.top)
            m.postScale(outW / frame.width(), outH / frame.height())
            c.drawBitmap(bm, m, paintImagem)
            saida
        } catch (_: Throwable) { null }
    }
}
