package com.radioterapia.ai.protocolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A página do PDF do serviço com os dois boxes que o app vai sobrepor.
 *
 * O usuário arrasta a etiqueta e o logotipo até onde eles NÃO cobrem o que já
 * está impresso na página dele. É calibração, não diagramação: o app não move
 * nada do documento original — só decide onde encostar o que acrescenta.
 *
 * COORDENADAS EM MILÍMETRO, e a view converte. O PDF carregado pode ser A4,
 * Letter ou qualquer outro formato, e uma posição guardada em pixels da tela
 * mudaria de lugar no papel a cada tablet diferente. Milímetro é o que
 * significa a mesma coisa na tela e na impressora.
 *
 * O LOGOTIPO NÃO REDIMENSIONA. Ele sai na mesma altura de todas as outras
 * páginas da ficha — deixar mudar o tamanho aqui produziria um documento com o
 * logo de um tamanho nas fotos e de outro no protocolo, que é exatamente o
 * defeito que a unificação do cabeçalho corrigiu.
 */
class ProtocoloPaginaView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private companion object {
        const val MM_TO_PT = 72f / 25.4f
        /** Altura fixa do logo, igual à das demais páginas. */
        const val LOGO_ALTURA_PT = 40f
        const val LOGO_LARGURA_MAX_PT = 150f
        /** Margem de toque em volta do box, para o dedo pegar a borda. */
        const val FOLGA_TOQUE = 24f
    }

    private var pagina: Bitmap? = null
    /** Tamanho da página em PONTOS — é o que amarra mm a pixels da tela. */
    private var pagWpt = 595f
    private var pagHpt = 842f

    /** Proporção do logo real, para o box ter a forma do que vai ser impresso. */
    private var logoAspecto = 3f

    var etqAtiva = true
        set(v) { field = v; invalidate() }
    var logoAtivo = false
        set(v) { field = v; invalidate() }

    var etqXmm = 10f; var etqYmm = 10f
    var etqWmm = 60f; var etqHmm = 30f
    /** Distância da borda DIREITA, como nas demais páginas. */
    var logoXmm = ProtocoloStore.MARGEM_PADRAO_MM
    var logoYmm = ProtocoloStore.MARGEM_PADRAO_MM

    /** Avisa a tela para atualizar os campos numéricos enquanto arrasta. */
    var aoMover: (() -> Unit)? = null

    private val pPagina = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    private val pFundoVazio = Paint().apply { color = Color.parseColor("#EEEEEE") }
    private val pEtqFundo = Paint().apply { color = Color.parseColor("#33119EE0") }
    private val pEtqBorda = Paint().apply {
        color = Color.parseColor("#119EE0"); style = Paint.Style.STROKE
        strokeWidth = 3f; isAntiAlias = true
    }
    private val pLogoFundo = Paint().apply { color = Color.parseColor("#33FF9800") }
    private val pLogoBorda = Paint().apply {
        color = Color.parseColor("#FF9800"); style = Paint.Style.STROKE
        strokeWidth = 3f; isAntiAlias = true
    }
    private val pRotulo = Paint().apply {
        color = Color.WHITE; textSize = 26f; isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val pRotuloFundo = Paint().apply { color = Color.parseColor("#CC000000") }

    fun definirPagina(bm: Bitmap?, larguraPt: Float, alturaPt: Float) {
        pagina = bm
        if (larguraPt > 0f) pagWpt = larguraPt
        if (alturaPt > 0f) pagHpt = alturaPt
        invalidate()
    }

    fun definirAspectoLogo(a: Float) {
        if (a > 0f) { logoAspecto = a; invalidate() }
    }

    /** Retângulo da página dentro da view, mantendo a proporção do papel. */
    private fun areaPagina(): RectF {
        val escala = minOf(width / pagWpt, height / pagHpt)
        val w = pagWpt * escala
        val h = pagHpt * escala
        val x = (width - w) / 2f
        val y = (height - h) / 2f
        return RectF(x, y, x + w, y + h)
    }

    private fun escalaAtual(): Float = minOf(width / pagWpt, height / pagHpt)

    private fun rectEtiqueta(): RectF {
        val a = areaPagina(); val e = escalaAtual()
        val x = a.left + etqXmm * MM_TO_PT * e
        val y = a.top + etqYmm * MM_TO_PT * e
        return RectF(x, y, x + etqWmm * MM_TO_PT * e, y + etqHmm * MM_TO_PT * e)
    }

    private fun rectLogo(): RectF {
        val a = areaPagina(); val e = escalaAtual()
        // Mesma conta do renderizador: altura fixa, largura pelo aspecto, com teto.
        val hPt = LOGO_ALTURA_PT
        val wPt = minOf(hPt * logoAspecto, LOGO_LARGURA_MAX_PT)
        val direita = a.right - logoXmm * MM_TO_PT * e
        val topo = a.top + logoYmm * MM_TO_PT * e
        return RectF(direita - wPt * e, topo, direita, topo + hPt * e)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = areaPagina()
        val bm = pagina
        if (bm != null) canvas.drawBitmap(bm, null, a, pPagina)
        else canvas.drawRect(a, pFundoVazio)

        if (etqAtiva) {
            val r = rectEtiqueta()
            canvas.drawRect(r, pEtqFundo)
            canvas.drawRect(r, pEtqBorda)
            rotulo(canvas, context.getString(
                com.radioterapia.ai.R.string.prot_box_etiqueta), r.left, r.top)
        }
        if (logoAtivo) {
            val r = rectLogo()
            canvas.drawRect(r, pLogoFundo)
            canvas.drawRect(r, pLogoBorda)
            rotulo(canvas, context.getString(
                com.radioterapia.ai.R.string.prot_box_logo), r.left, r.top)
        }
    }

    /** Etiqueta do box, sobre fundo escuro: sem ele o texto some numa página clara. */
    private fun rotulo(canvas: Canvas, txt: String, x: Float, y: Float) {
        val w = pRotulo.measureText(txt) + 12f
        canvas.drawRect(x, y - 30f, x + w, y - 2f, pRotuloFundo)
        canvas.drawText(txt, x + 6f, y - 9f, pRotulo)
    }

    // ---------------------------------------------------------------- arrasto

    private var arrastando = 0   // 0 = nada, 1 = etiqueta, 2 = logo
    private var ultX = 0f
    private var ultY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A ETIQUETA TEM PRIORIDADE quando os dois se sobrepõem: ela é
                // a maior e a que se move mais, e o logo costuma já estar no
                // canto onde foi calibrado.
                arrastando = when {
                    etqAtiva && perto(rectEtiqueta(), event.x, event.y) -> 1
                    logoAtivo && perto(rectLogo(), event.x, event.y) -> 2
                    else -> 0
                }
                if (arrastando != 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    ultX = event.x; ultY = event.y
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (arrastando == 0) return false
                val e = escalaAtual()
                if (e <= 0f) return true
                val dxMm = (event.x - ultX) / e / MM_TO_PT
                val dyMm = (event.y - ultY) / e / MM_TO_PT
                if (arrastando == 1) {
                    etqXmm = (etqXmm + dxMm).coerceIn(0f, pagWpt / MM_TO_PT - etqWmm)
                    etqYmm = (etqYmm + dyMm).coerceIn(0f, pagHpt / MM_TO_PT - etqHmm)
                } else {
                    // O logo é ancorado à DIREITA: arrastar para a direita
                    // DIMINUI a distância da borda.
                    logoXmm = (logoXmm - dxMm).coerceIn(0f, pagWpt / MM_TO_PT - 10f)
                    logoYmm = (logoYmm + dyMm).coerceIn(0f, pagHpt / MM_TO_PT - 10f)
                }
                ultX = event.x; ultY = event.y
                aoMover?.invoke()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                arrastando = 0
            }
        }
        return arrastando != 0
    }

    private fun perto(r: RectF, x: Float, y: Float): Boolean =
        x >= r.left - FOLGA_TOQUE && x <= r.right + FOLGA_TOQUE &&
        y >= r.top - FOLGA_TOQUE && y <= r.bottom + FOLGA_TOQUE
}
