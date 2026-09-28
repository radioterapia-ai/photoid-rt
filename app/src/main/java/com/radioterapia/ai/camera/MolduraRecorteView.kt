package com.radioterapia.ai.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A moldura do recorte, desenhada por cima do visor da câmera.
 *
 * POR QUE ELA EXISTE
 * Os técnicos demoravam a entender que a foto é recortada para 16:9. Em paisagem
 * isso quase não aparece — a captura já é 16:9 e o recorte não tira nada. Em
 * RETRATO a captura vira 9:16, e o recorte fica com uma faixa horizontal do
 * meio: metade do que a pessoa enquadrou é descartada, e ela só descobre depois
 * de salvar.
 *
 * A MOLDURA NÃO PODE MENTIR. Ela mostra exatamente o retângulo que sobra, com os
 * mesmos cantos arredondados da ficha impressa. Uma moldura menor que o recorte
 * real — com margem "para dar folga" — ensinaria a enquadrar errado: o técnico
 * encaixaria dentro dela e a foto sairia com sobra nas laterais.
 *
 * FIT_CENTER É O QUE TORNA A CONTA POSSÍVEL. O `PreviewView` está em
 * `FIT_CENTER`, então o visor mostra o quadro inteiro da câmera, com tarjas nas
 * bordas quando a proporção da tela não bate com a do sensor. Esta View ocupa a
 * mesma área do `PreviewView`, calcula onde o quadro realmente aparece dentro
 * dela, e inscreve o recorte nesse quadro — não nos limites da própria View, que
 * incluem as tarjas.
 */
class MolduraRecorteView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /**
     * Proporção do quadro que a câmera está entregando, largura ÷ altura.
     *
     * 16/9 com o tablet deitado, 9/16 com ele em pé. Quem sabe disso é a
     * Activity, que conhece a rotação — por isso vem de fora em vez de ser
     * adivinhada aqui.
     */
    var aspectoVisor: Float = 16f / 9f
        set(v) { field = if (v > 0f) v else 16f / 9f; invalidate() }

    /** Proporção do recorte final. Espelha `PdfBuilder.ASPECTO_FOTO`. */
    var aspectoRecorte: Float = 16f / 9f
        set(v) { field = if (v > 0f) v else 16f / 9f; invalidate() }

    /** Desligar enquanto a foto tirada está em revisão — ali quem manda é o recorte. */
    var molduraVisivel: Boolean = true
        set(v) { field = v; invalidate() }

    private val escurecimento = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // LEVE de propósito. O escurecimento é para dizer "isto sai", não para
        // impedir de ver: quem enquadra precisa enxergar o que está fora da
        // moldura para saber para que lado mover o tablet.
        color = 0x66000000
    }

    private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 220
    }

    private val caminho = Path()
    private val recorte = RectF()
    private val quadro = RectF()

    init {
        // A View só pinta; toque nenhum é consumido, senão o zoom por pinça do
        // visor pararia de funcionar justamente na tela que ensina a usá-lo.
        isClickable = false
        isFocusable = false
    }

    /** Onde o recorte cai, em coordenadas desta View. Útil para posicionar o aviso. */
    fun areaRecorte(saida: RectF) {
        calcular()
        saida.set(recorte)
    }

    private fun calcular() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1) ONDE O QUADRO DA CÂMERA APARECE. FIT_CENTER encaixa o quadro inteiro
        //    dentro da View e centraliza, deixando tarja de um lado só.
        val larguraQuadro: Float
        val alturaQuadro: Float
        if (w / h > aspectoVisor) {
            alturaQuadro = h
            larguraQuadro = h * aspectoVisor
        } else {
            larguraQuadro = w
            alturaQuadro = w / aspectoVisor
        }
        quadro.set(
            (w - larguraQuadro) / 2f, (h - alturaQuadro) / 2f,
            (w + larguraQuadro) / 2f, (h + alturaQuadro) / 2f)

        // 2) O RECORTE, INSCRITO NO QUADRO. Largura inteira do quadro; a altura
        //    sai da proporção. Quando o quadro já é mais largo que o recorte
        //    (tablet deitado), sobra altura e a moldura quase coincide com ele —
        //    que é por que em paisagem "quase não muda".
        var larguraRec = quadro.width()
        var alturaRec = larguraRec / aspectoRecorte
        if (alturaRec > quadro.height()) {
            alturaRec = quadro.height()
            larguraRec = alturaRec * aspectoRecorte
        }
        recorte.set(
            quadro.centerX() - larguraRec / 2f, quadro.centerY() - alturaRec / 2f,
            quadro.centerX() + larguraRec / 2f, quadro.centerY() + alturaRec / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!molduraVisivel) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        calcular()

        // O RAIO ACOMPANHA A LARGURA, e não é um valor em dp fixo: na ficha o
        // canto é RAIO_CANTO = 10 pt numa foto de ~250 pt, ou seja 4% da
        // largura. Fixar em dp faria a moldura do tablet ter um canto diferente
        // do que sai impresso, que é exatamente o tipo de diferença pequena que
        // ninguém nota e que torna a moldura menos confiável.
        val raio = recorte.width() * 0.04f

        // Escurece tudo MENOS o recorte, num traço só. EVEN_ODD faz o retângulo
        // interno virar buraco; desenhar quatro retângulos em volta deixaria
        // costura visível nos cantos arredondados.
        caminho.reset()
        caminho.fillType = Path.FillType.EVEN_ODD
        caminho.addRect(0f, 0f, w, h, Path.Direction.CW)
        caminho.addRoundRect(recorte, raio, raio, Path.Direction.CW)
        canvas.drawPath(caminho, escurecimento)

        borda.strokeWidth = 2f * resources.displayMetrics.density
        canvas.drawRoundRect(recorte, raio, raio, borda)
    }
}
