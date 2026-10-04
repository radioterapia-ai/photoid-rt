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
 *
 * O RODAPÉ DA FICHA aparece na prévia, no lugar e no corpo em que vai ser
 * impresso, enquanto estiver ligado para a página ([rodapeAtivo]). Sem isso um
 * logo arrastado para o pé da página o cobriria sem ninguém ver.
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
        /** Rodapé da ficha: corpo e distância da linha de base à borda de baixo, em pt. */
        const val RODAPE_CORPO_PT = 9f
        const val RODAPE_BASE_PT = 12f
        /** Margem lateral da ficha, em pt: o rodapé encolhe para caber entre elas. */
        const val RODAPE_MARGEM_PT = 28f
        /** Altura do rótulo do box, em px, desenhado logo acima dele. */
        const val ROTULO_ALTURA = 28f
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
    /** Rodapé da ficha nesta página. Ligado por padrão, como no PDF gerado. */
    var rodapeAtivo = true
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
    private val pRodapeFundo = Paint().apply { color = Color.parseColor("#22666666") }
    private val pRodape = Paint().apply {
        color = Color.parseColor("#666666"); isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    /**
     * Texto do rodapé como o PDF o escreve, com "n" no lugar do número: o
     * número de verdade depende de quantas páginas a ficha do paciente tem
     * antes do protocolo. Lido uma vez — o nome da clínica não muda com a tela
     * aberta.
     */
    private val textoRodape: String by lazy {
        try {
            val pagina = context.getString(com.radioterapia.ai.R.string.pdf_page)
                .replace(Regex("%(\\d+\\\$)?d"), "n")
            com.radioterapia.ai.pdf.PdfBuilder.textoRodape(
                com.radioterapia.ai.AppConfig(context).nomeClinica, pagina)
        } catch (_: Exception) { "" }
    }

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

        if (rodapeAtivo) desenharRodape(canvas, a)
        if (etqAtiva) {
            val r = rectEtiqueta()
            // Cantos com o raio da etiqueta impressa, na escala da prévia: o box
            // tem a forma do que vai sair no papel.
            val raio = com.radioterapia.ai.pdf.PdfBuilder.RAIO_CANTO * escalaAtual()
            canvas.drawRoundRect(r, raio, raio, pEtqFundo)
            canvas.drawRoundRect(r, raio, raio, pEtqBorda)
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

    /**
     * Etiqueta do box, sobre fundo escuro: sem ele o texto some numa página clara.
     *
     * Acima do box; DENTRO dele, no alto, quando acima sairia da view — o que
     * acontece com o box encostado no topo de uma prévia pequena.
     */
    private fun rotulo(canvas: Canvas, txt: String, x: Float, y: Float) {
        val w = pRotulo.measureText(txt) + 12f
        val topo = if (y - 2f - ROTULO_ALTURA >= 0f) y - 2f - ROTULO_ALTURA else y + 2f
        canvas.drawRect(x, topo, x + w, topo + ROTULO_ALTURA, pRotuloFundo)
        canvas.drawText(txt, x + 6f, topo + ROTULO_ALTURA - 7f, pRotulo)
    }

    /**
     * Rodapé da ficha na prévia: mesmo texto, corpo e linha de base do PDF
     * (9 pt, ou menos com nome de clínica longo, a 12 pt da borda de baixo,
     * centrado na largura da página), sobre
     * uma faixa clara que marca a área que ele ocupa.
     */
    private fun desenharRodape(canvas: Canvas, a: RectF) {
        val txt = textoRodape
        if (txt.isEmpty()) return
        val e = escalaAtual()
        if (e <= 0f) return
        pRodape.textSize = RODAPE_CORPO_PT * e
        // Nome de clínica longo encolhe como no PDF: mesma conta, sobre a largura
        // em pontos e as margens de 28 pt da ficha.
        val corpo = com.radioterapia.ai.pdf.PdfBuilder.corpoRodape(
            pRodape.measureText(txt) / e, pagWpt - RODAPE_MARGEM_PT * 2)
        pRodape.textSize = corpo * e
        val cx = a.centerX()
        val base = a.bottom - RODAPE_BASE_PT * e
        val meia = pRodape.measureText(txt) / 2f + 2f * e
        canvas.drawRect(cx - meia, base - RODAPE_CORPO_PT * e, cx + meia, base + 3f * e, pRodapeFundo)
        canvas.drawText(txt, cx, base, pRodape)
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
                // O teto nunca fica abaixo de zero: box maior que a página
                // (PDF pequeno) faria coerceIn lançar exceção no meio do arrasto.
                if (arrastando == 1) {
                    etqXmm = (etqXmm + dxMm).coerceIn(0f, (pagWpt / MM_TO_PT - etqWmm).coerceAtLeast(0f))
                    etqYmm = (etqYmm + dyMm).coerceIn(0f, (pagHpt / MM_TO_PT - etqHmm).coerceAtLeast(0f))
                } else {
                    // O logo é ancorado à DIREITA: arrastar para a direita
                    // DIMINUI a distância da borda. O limite é o tamanho REAL
                    // do logo impresso (a mesma conta de rectLogo), para ele
                    // não sair do papel pela esquerda nem por baixo.
                    val logoWmm = minOf(LOGO_ALTURA_PT * logoAspecto, LOGO_LARGURA_MAX_PT) / MM_TO_PT
                    val logoHmm = LOGO_ALTURA_PT / MM_TO_PT
                    logoXmm = (logoXmm - dxMm).coerceIn(0f, (pagWpt / MM_TO_PT - logoWmm).coerceAtLeast(0f))
                    logoYmm = (logoYmm + dyMm).coerceIn(0f, (pagHpt / MM_TO_PT - logoHmm).coerceAtLeast(0f))
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
