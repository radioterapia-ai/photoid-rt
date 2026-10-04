package com.radioterapia.ai.protocolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Acrescenta as páginas do protocolo ao fim da ficha.
 *
 * O PDF DO USUÁRIO NÃO É EDITADO. Ele é reproduzido página a página e o app
 * sobrepõe, por cima, no máximo três coisas: a etiqueta de identificação do
 * paciente, o logotipo do serviço e o rodapé da ficha. Nada é removido,
 * reposicionado ou reescrito — a promessa feita a quem carrega o arquivo é que
 * o documento sai como entrou.
 *
 * RASTERIZA, pelo mesmo motivo de [com.radioterapia.ai.pdf.PdfPaginas]: o
 * Android não expõe API para copiar página de PDF preservando o vetor.
 * `PdfRenderer` só desenha em bitmap. O layout é reproduzido na medida exata; o
 * que muda é a nitidez do texto.
 *
 * A ETIQUETA E O RODAPÉ VÊM DE QUEM GERA A FICHA, como funções: a mesma rotina
 * de etiqueta das primeiras páginas e o mesmo rodapé de toda página. Uma
 * rotina paralela aqui divergiria da original — contorno, corpo, rótulos
 * traduzidos — a cada ajuste feito só numa delas. Este objeto decide só ONDE
 * e EM QUE PÁGINA cada coisa entra.
 *
 * POSIÇÃO POR PÁGINA: cada página do PDF da frente usa
 * [ProtocoloStore.Pagina.posicaoDa] — o ajuste próprio dela ou, sem ajuste, a
 * posição da primeira página, que é o que um PDF salvo antes da posição por
 * página continua recebendo.
 *
 * O RODAPÉ sai em toda página, frente e verso, salvo onde o editor o desligou
 * (o impresso da clínica pode ter rodapé próprio no mesmo lugar). Sem ele, a
 * ordem das folhas soltas deixa de ser rastreável — e folha de ficha clínica
 * que perde a ordem é achado de auditoria.
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
     * Desenha todas as páginas do protocolo no documento aberto.
     *
     * @param desenharEtiqueta desenha a etiqueta do paciente no retângulo dado,
     *   em pontos da página.
     * @param rodape desenha o rodapé: canvas, número da página no documento,
     *   largura e altura da página em pontos. O número é a posição da página no
     *   arquivo, contadas as folhas em branco de pareamento.
     * @return quantas páginas foram acrescentadas, folhas em branco incluídas.
     */
    fun desenhar(
        doc: PdfDocument,
        context: Context,
        protocolo: ProtocoloStore.Protocolo,
        logo: Bitmap?,
        desenharEtiqueta: (Canvas, RectF) -> Unit,
        rodape: (Canvas, Int, Int, Int) -> Unit
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
            if (precisaFolhaEmBranco(doc.pages.size + 1, verso != null)) {
                desenhadas += paginaEmBranco(doc, arq)
            }

            desenhadas += desenharArquivo(doc, arq, pag, logo, desenharEtiqueta, rodape)

            if (verso != null) {
                // SEM SOBREPOSIÇÃO: a etiqueta e o logotipo já estão na frente
                // desta mesma folha. O rodapé, que numera, sai.
                desenhadas += desenharArquivo(
                    doc, verso, pag, logo, desenharEtiqueta, rodape, sobrepor = false)
            }
        }
        return desenhadas
    }

    /**
     * A frente de uma folha com verso precisa de uma página em branco antes?
     *
     * Sim quando ela cairia em posição PAR, que a impressora em frente-e-verso
     * põe no verso da folha anterior. [proximaPosicao] é a posição (base 1) que
     * a próxima página teria no documento.
     *
     * Página SÓ DE FRENTE nunca recebe a folha em branco: ela pode começar no
     * verso da última página anterior, o que economiza uma folha.
     */
    internal fun precisaFolhaEmBranco(proximaPosicao: Int, temVerso: Boolean): Boolean =
        temVerso && proximaPosicao % 2 == 0

    /**
     * Folha em branco, do tamanho da página que vem a seguir.
     *
     * Existe só para acertar o pareamento do frente-e-verso (ver [desenhar]).
     * Sai limpa: sem rodapé, porque ela não é uma página do documento do ponto
     * de vista de quem lê — é um efeito da impressão. Mas conta na numeração
     * das páginas seguintes, que é a ordem física que sai da impressora.
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

    /**
     * Um PDF do protocolo pode ter mais de uma página; todas entram.
     *
     * No VERSO, a página i segue a escolha de rodapé da página i da frente: é a
     * mesma folha quando frente e verso têm o mesmo número de páginas, e uma
     * página sem ajuste próprio segue a primeira.
     */
    private fun desenharArquivo(
        doc: PdfDocument,
        arq: File,
        pag: ProtocoloStore.Pagina,
        logo: Bitmap?,
        desenharEtiqueta: (Canvas, RectF) -> Unit,
        rodape: (Canvas, Int, Int, Int) -> Unit,
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

                val pos = pag.posicaoDa(i)
                if (sobrepor && pos.etqAtiva) sobreporEtiqueta(cv, pos, desenharEtiqueta)
                if (sobrepor && pos.logoAtivo && logo != null)
                    desenharLogo(cv, pos, logo, wPt.toFloat())
                // A página ainda não foi finalizada, e getPages() só conta as
                // finalizadas: esta é a de número pages.size + 1.
                if (pos.rodapeAtivo) {
                    try { rodape(cv, doc.pages.size + 1, wPt, hPt) } catch (_: Exception) {}
                }

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
     * Etiqueta no retângulo calibrado, em mm convertidos para pontos, com o
     * teto do maior formato que a configuração aceita. Não passa pelos tetos
     * do cabeçalho das primeiras páginas: aqui vale o box que o usuário
     * desenhou sobre o documento dele.
     */
    private fun sobreporEtiqueta(
        cv: Canvas, pos: ProtocoloStore.Posicao, desenharEtiqueta: (Canvas, RectF) -> Unit
    ) {
        try {
            val x = pos.etqXmm * MM_TO_PT
            val y = pos.etqYmm * MM_TO_PT
            val w = pos.etqWmm.coerceAtMost(ProtocoloStore.ETQ_MAX_W) * MM_TO_PT
            val h = pos.etqHmm.coerceAtMost(ProtocoloStore.ETQ_MAX_H) * MM_TO_PT
            desenharEtiqueta(cv, RectF(x, y, x + w, y + h))
        } catch (_: Exception) { /* a etiqueta nunca derruba a folha */ }
    }

    /** Logotipo ancorado à DIREITA, como nas demais páginas. */
    private fun desenharLogo(
        cv: Canvas, pos: ProtocoloStore.Posicao, logo: Bitmap, larguraPagina: Float
    ) {
        try {
            val esc = minOf(LOGO_LARGURA_MAX_PT / logo.width, LOGO_ALTURA_PT / logo.height)
            val w = logo.width * esc
            val h = logo.height * esc
            val direita = larguraPagina - pos.logoXmm * MM_TO_PT
            val topo = pos.logoYmm * MM_TO_PT
            cv.drawBitmap(logo, null, RectF(direita - w, topo, direita, topo + h), null)
        } catch (_: Exception) { /* logo é reforço, nunca requisito */ }
    }
}
