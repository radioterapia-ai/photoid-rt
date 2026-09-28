package com.radioterapia.ai.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Extrai um subconjunto de páginas de um PDF já gerado.
 *
 * POR QUE EXISTE: a ficha completa pode ter Time-Out, várias páginas de fotos,
 * rubricário e — quando houver — as páginas do protocolo do serviço. Reimprimir
 * tudo para repor uma folha que rasgou gasta papel e tempo do acelerador, e é o
 * que o técnico fazia por não haver alternativa.
 *
 * RASTERIZA, e é bom saber disso. O Android não tem API para copiar páginas de
 * um PDF preservando o vetor: `PdfRenderer` só sabe desenhar a página num
 * bitmap. A página selecionada sai como imagem em [DPI] pontos por polegada, e
 * não como texto selecionável.
 *
 * O LAYOUT NÃO MUDA — a página é reproduzida na mesma medida, sem
 * reposicionar nada. O que muda é a nitidez do texto, e 200 dpi é acima do que
 * uma laser comum resolve em papel comum. Regenerar a partir dos dados daria
 * vetor, mas exigiria reconstruir a ficha inteira a partir do paciente, e a
 * tela de onde isso é chamado tem só o arquivo em mãos.
 */
object PdfPaginas {

    /**
     * Resolução do rasterizado.
     *
     * 200 e não 300: uma A4 a 300 dpi são 2480x3508 pixels, cerca de 35 MB em
     * ARGB_8888 por página. O tablet da sala não tem essa folga, e a falta
     * chegaria como OutOfMemoryError bem no meio de uma impressão.
     */
    private const val DPI = 200f
    private const val PONTOS_POR_POLEGADA = 72f

    /** Quantas páginas o PDF tem. 0 quando não dá para abrir. */
    fun contar(pdf: File): Int {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            r.pageCount
        } catch (_: Throwable) {
            0
        } finally {
            try { r?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Gera um PDF novo só com as páginas pedidas, na ordem em que vierem.
     *
     * @param paginas índices base 1, como o usuário os vê na tela.
     * @return o arquivo gravado, ou `null` se nada pôde ser extraído.
     */
    fun extrair(pdf: File, paginas: List<Int>, destino: File): File? {
        if (paginas.isEmpty()) return null
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        val doc = PdfDocument()
        return try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            val total = r.pageCount
            var gravadas = 0

            for (n in paginas) {
                val idx = n - 1
                if (idx !in 0 until total) continue
                val page = r.openPage(idx)
                val larguraPt = page.width
                val alturaPt = page.height
                val escala = DPI / PONTOS_POR_POLEGADA
                val bmpW = (larguraPt * escala).toInt().coerceAtLeast(1)
                val bmpH = (alturaPt * escala).toInt().coerceAtLeast(1)

                val bmp = try {
                    Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                } catch (_: Throwable) {
                    // Sem memória para esta página: fecha e segue para a
                    // próxima em vez de derrubar a impressão inteira.
                    page.close(); continue
                }
                // FUNDO BRANCO antes de renderizar. O bitmap nasce transparente,
                // e o que o PdfRenderer não pinta ficaria preto ao virar página
                // de PDF — a folha sairia com fundo escuro na impressora.
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val info = PdfDocument.PageInfo.Builder(
                    larguraPt, alturaPt, doc.pages.size + 1).create()
                val nova = doc.startPage(info)
                nova.canvas.drawBitmap(
                    bmp, null,
                    android.graphics.RectF(0f, 0f, larguraPt.toFloat(), alturaPt.toFloat()),
                    null)
                doc.finishPage(nova)
                bmp.recycle()
                gravadas++
            }

            if (gravadas == 0) return null
            FileOutputStream(destino).use { doc.writeTo(it) }
            destino
        } catch (_: Throwable) {
            null
        } finally {
            try { doc.close() } catch (_: Exception) {}
            try { r?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}
