package com.radioterapia.ai.protocolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Acrescenta as páginas do protocolo ao fim da ficha.
 *
 * O PDF DO USUÁRIO NÃO É EDITADO. Ele é reproduzido página a página e o app
 * sobrepõe, por cima, no máximo três coisas: a etiqueta de identificação do
 * paciente, o logotipo do serviço e o número da página. Nada é removido,
 * reposicionado ou reescrito — a promessa feita a quem carrega o arquivo é que
 * o documento sai como entrou.
 *
 * RASTERIZA, pelo mesmo motivo de [com.radioterapia.ai.pdf.PdfPaginas]: o
 * Android não expõe API para copiar página de PDF preservando o vetor.
 * `PdfRenderer` só desenha em bitmap. O layout é reproduzido na medida exata; o
 * que muda é a nitidez do texto.
 *
 * O NÚMERO DA PÁGINA é desenhado mesmo quando o logotipo está desligado, e
 * isso é deliberado. Sem ele, o rodapé das páginas que o app gera passaria a
 * mentir: "página 2 de 3" num documento que tem seis. Folha de ficha clínica
 * que perde a rastreabilidade da ordem é achado de auditoria, e é a herança
 * registrada do Portal Ficha Técnica.
 */
object ProtocoloRenderer {

    /** Ver PdfPaginas.DPI: 200 é o limite prático de memória do tablet. */
    private const val DPI = 200f
    private const val PONTOS_POR_POLEGADA = 72f
    private const val MM_TO_PT = 72f / 25.4f

    /** Altura fixa do logotipo, igual à das demais páginas (PdfBuilder.LOGO_ALTURA). */
    private const val LOGO_ALTURA_PT = 40f
    private const val LOGO_LARGURA_MAX_PT = 150f

    /**
     * Dados que a etiqueta virtual imprime. Só o que já existe no cabeçalho das
     * outras páginas — esta função não inventa campo novo.
     */
    data class Identificacao(
        val nome: String,
        val nascimento: String,
        val prontuario: String,
        val dataSimulacao: String
    )

    /**
     * Desenha todas as páginas do protocolo no documento aberto.
     *
     * @param numeroInicial número da primeira página do protocolo dentro da
     *   ficha inteira, para o rodapé continuar a contagem das anteriores.
     * @param totalDaFicha total de páginas do documento final.
     * @return quantas páginas foram acrescentadas.
     */
    fun desenhar(
        doc: PdfDocument,
        context: Context,
        protocolo: ProtocoloStore.Protocolo,
        ident: Identificacao,
        logo: Bitmap?,
        numeroInicial: Int,
        totalDaFicha: Int
    ): Int {
        if (protocolo.paginas.isEmpty()) return 0
        val store = ProtocoloStore(context)
        var desenhadas = 0

        protocolo.paginas.forEach { pag ->
            val arq = store.arquivoPagina(protocolo, pag) ?: return@forEach
            val verso = store.arquivoVerso(protocolo, pag)

            /*
                PAREAMENTO DA FOLHA, quando há verso.

                A impressora em frente-e-verso junta as páginas aos pares: 1 com
                2, 3 com 4. Para a frente e o verso caírem na MESMA folha, a
                frente precisa estar em posição ímpar — e isso depende de
                quantas páginas a ficha teve antes, que varia com o número de
                fotos do paciente.

                Sem esta folha em branco, o verso de um paciente com número par
                de páginas anteriores sairia impresso no verso da página de
                Time-Out, e a frente do impresso ficaria sozinha. Uma folha
                gasta é mais barata que um impresso clínico montado errado.
             */
            if (verso != null && (numeroInicial + desenhadas) % 2 == 0) {
                desenhadas += paginaEmBranco(doc, arq)
            }

            desenhadas += desenharArquivo(
                doc, arq, pag, ident, logo,
                numeroInicial + desenhadas, totalDaFicha)

            if (verso != null) {
                // SEM SOBREPOSIÇÃO: a etiqueta e o logotipo já estão na frente
                // desta mesma folha.
                desenhadas += desenharArquivo(
                    doc, verso, pag, ident, logo,
                    numeroInicial + desenhadas, totalDaFicha, sobrepor = false)
            }
        }
        return desenhadas
    }

    /**
     * Folha em branco, do tamanho da página que vem a seguir.
     *
     * Existe só para acertar o pareamento do frente-e-verso (ver [desenhar]).
     * Sai limpa: sem número de rodapé, porque ela não é uma página do documento
     * do ponto de vista de quem lê — é um efeito da impressão.
     */
    private fun paginaEmBranco(doc: PdfDocument, modelo: File): Int = try {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(modelo, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            val p0 = r.openPage(0)
            val w = p0.width; val h = p0.height
            p0.close()
            val nova = doc.startPage(
                PdfDocument.PageInfo.Builder(w, h, doc.pages.size + 1).create())
            nova.canvas.drawColor(Color.WHITE)
            doc.finishPage(nova)
            1
        } finally {
            try { r?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    } catch (_: Exception) {
        // Sem a folha em branco o pareamento fica errado, mas a ficha sai.
        0
    }

    /** Um PDF do protocolo pode ter mais de uma página; todas entram. */
    private fun desenharArquivo(
        doc: PdfDocument,
        arq: File,
        pag: ProtocoloStore.Pagina,
        ident: Identificacao,
        logo: Bitmap?,
        numeroInicial: Int,
        totalDaFicha: Int,
        /** `false` no verso: etiqueta e logotipo já vieram na frente da folha. */
        sobrepor: Boolean = true
    ): Int {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        var n = 0
        try {
            pfd = ParcelFileDescriptor.open(arq, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            for (i in 0 until r.pageCount) {
                val page = r.openPage(i)
                val wPt = page.width
                val hPt = page.height
                val esc = DPI / PONTOS_POR_POLEGADA
                val bmp = try {
                    Bitmap.createBitmap(
                        (wPt * esc).toInt().coerceAtLeast(1),
                        (hPt * esc).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888)
                } catch (_: Throwable) {
                    // Sem memória nesta página: segue para a próxima em vez de
                    // abortar a ficha inteira do paciente.
                    page.close(); continue
                }
                // O bitmap nasce transparente e o que não for pintado sairia
                // preto na folha. Fundo branco antes de renderizar.
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val info = PdfDocument.PageInfo.Builder(wPt, hPt, doc.pages.size + 1).create()
                val nova = doc.startPage(info)
                val cv = nova.canvas
                cv.drawBitmap(bmp, null, RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()), null)
                bmp.recycle()

                if (sobrepor && pag.etqAtiva) desenharEtiqueta(cv, pag, ident)
                if (sobrepor && pag.logoAtivo && logo != null)
                    desenharLogo(cv, pag, logo, wPt.toFloat())
                desenharNumero(cv, wPt.toFloat(), hPt.toFloat(), numeroInicial + n, totalDaFicha)

                doc.finishPage(nova)
                n++
            }
        } catch (_: Throwable) {
            // PDF ilegível ou protegido: a página não entra, e a ficha do
            // paciente sai assim mesmo. Perder a ficha inteira por causa de um
            // anexo do serviço seria trocar um problema pequeno por um grande.
        } finally {
            try { r?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
        return n
    }

    /**
     * Etiqueta virtual: a mesma identificação que abre as demais páginas.
     *
     * Fundo BRANCO OPACO sob a moldura. A etiqueta é colada sobre o documento
     * do serviço, e sem o fundo o texto dela se misturaria com o que já estiver
     * impresso naquele ponto — que é justamente onde o usuário calibrou para
     * não haver nada, mas o app não tem como garantir isso.
     */
    private fun desenharEtiqueta(
        cv: Canvas, pag: ProtocoloStore.Pagina, ident: Identificacao
    ) {
        val x = pag.etqXmm * MM_TO_PT
        val y = pag.etqYmm * MM_TO_PT
        val w = pag.etqWmm.coerceAtMost(ProtocoloStore.ETQ_MAX_W) * MM_TO_PT
        val h = pag.etqHmm.coerceAtMost(ProtocoloStore.ETQ_MAX_H) * MM_TO_PT
        val r = RectF(x, y, x + w, y + h)

        cv.drawRect(r, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL })
        cv.drawRect(r, Paint().apply {
            color = Color.parseColor("#666666"); style = Paint.Style.STROKE
            strokeWidth = 0.8f; isAntiAlias = true
        })

        val pRot = Paint().apply {
            color = Color.parseColor("#444444"); textSize = 6.5f
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        val pVal = Paint().apply { color = Color.BLACK; textSize = 8.5f; isAntiAlias = true }

        // Nome em corpo maior: é o que se procura numa folha solta.
        val pNome = Paint().apply {
            color = Color.BLACK; textSize = 10f
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        var ty = y + 12f
        val margem = 5f
        val util = w - margem * 2
        // Encolhe até caber: cortar o nome do paciente seria pior que reduzir.
        while (pNome.textSize > 6f && pNome.measureText(ident.nome) > util) {
            pNome.textSize -= 0.5f
        }
        cv.drawText(ident.nome, x + margem, ty, pNome)
        ty += 11f

        fun linha(rot: String, valor: String) {
            if (valor.isBlank() || ty > y + h - 3f) return
            cv.drawText(rot, x + margem, ty, pRot)
            cv.drawText(valor, x + margem + pRot.measureText(rot) + 4f, ty, pVal)
            ty += 10f
        }
        linha("NASC.:", ident.nascimento)
        linha("REG.:", ident.prontuario)
        linha("SIM.:", ident.dataSimulacao)
    }

    /** Logotipo ancorado à DIREITA, como nas demais páginas. */
    private fun desenharLogo(
        cv: Canvas, pag: ProtocoloStore.Pagina, logo: Bitmap, larguraPagina: Float
    ) {
        try {
            val esc = minOf(LOGO_LARGURA_MAX_PT / logo.width, LOGO_ALTURA_PT / logo.height)
            val w = logo.width * esc
            val h = logo.height * esc
            val direita = larguraPagina - pag.logoXmm * MM_TO_PT
            val topo = pag.logoYmm * MM_TO_PT
            cv.drawBitmap(logo, null, RectF(direita - w, topo, direita, topo + h), null)
        } catch (_: Exception) { /* logo é reforço, nunca requisito */ }
    }

    /** "n / total" discreto, na margem inferior direita. */
    private fun desenharNumero(
        cv: Canvas, w: Float, h: Float, numero: Int, total: Int
    ) {
        try {
            val p = Paint().apply {
                color = Color.parseColor("#777777"); textSize = 7f
                isAntiAlias = true; textAlign = Paint.Align.RIGHT
            }
            cv.drawText("$numero / $total", w - 20f, h - 14f, p)
        } catch (_: Exception) {}
    }
}
