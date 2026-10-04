package com.radioterapia.ai.protocolo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.radioterapia.ai.BaseActivity
import com.radioterapia.ai.R
import com.radioterapia.ai.branding.LogoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Calibra, num PDF do protocolo, onde a etiqueta e o logotipo caem em cada
 * página, se o rodapé da ficha sai nela, e o nome com que o PDF aparece na
 * lista do protocolo.
 *
 * Tela própria, e não diálogo, porque o que se decide aqui só se decide
 * OLHANDO a página inteira: o ponto do ajuste é não cobrir o que já está
 * impresso no documento do serviço.
 *
 * POSIÇÃO POR PÁGINA, COM HERANÇA DA PRIMEIRA. A prévia mostra uma página por
 * vez, com "Página n de N" para navegar quando o PDF tem mais de uma. A página
 * 1 é a base; as demais a seguem enquanto "Mesma posição da página 1" estiver
 * marcado, e é assim que um termo de duas folhas com o mesmo cabeçalho
 * continua pedindo uma calibração só. Arrastar um box, mudar um tamanho ou
 * ligar e desligar algo numa página além da primeira desmarca a caixa
 * sozinho: a própria mudança diz que aquela página quer outra posição, e
 * perguntar seria um toque a mais.
 *
 * TUDO FICA NUM RASCUNHO até Salvar. Trocar de página guarda no rascunho a
 * posição da página que sai; Cancelar descarta todas. O rascunho parte da
 * página GRAVADA e só muda por `copy`: verso, nome e ajustes que a tela não
 * mostra passam intactos para o disco.
 *
 * EM PAISAGEM os controles ficam numa coluna ao lado da página. Empilhados,
 * eles tomariam a altura de que a prévia precisa. A Activity não é recriada ao
 * girar (configChanges no manifesto), então a troca de layout é feita aqui,
 * com o rascunho e a prévia já em memória.
 */
class ProtocoloPaginaActivity : BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.prot_pagina_titulo)

    private lateinit var store: ProtocoloStore
    private var idProtocolo = ""
    private var nomeArquivo = ""

    // Views do layout em uso. Ao girar o layout é trocado inteiro, e estas
    // passam a apontar para as do novo.
    private lateinit var view: ProtocoloPaginaView
    private lateinit var txtPos: TextView
    private lateinit var edtNome: EditText
    private lateinit var chkEtq: CheckBox
    private lateinit var chkLogo: CheckBox
    private lateinit var chkRodape: CheckBox
    private lateinit var chkMesma: CheckBox
    private lateinit var edtW: EditText
    private lateinit var edtH: EditText
    private lateinit var linhaNav: View
    private lateinit var txtPagNum: TextView
    private lateinit var btnAnt: Button
    private lateinit var btnProx: Button

    /** Layout em uso: true = controles na coluna ao lado (paisagem). */
    private var colunaLateral = false

    /** A página sendo calibrada, com o que já foi ajustado e ainda não salvo. */
    private lateinit var rascunho: ProtocoloStore.Pagina

    /** Página mostrada, base 0 dentro do PDF. */
    private var indice = 0

    /** Páginas do PDF; 0 enquanto a primeira prévia não chegou. */
    private var total = 0

    /** Tamanho da etiqueta na configuração, para página sem tamanho gravado. */
    private var etqPadraoW = 60f
    private var etqPadraoH = 30f

    /**
     * GUARDA: verdadeiro enquanto a tela é preenchida por código. Marcar uma
     * caixa por código dispara o mesmo listener do toque; sem esta trava,
     * carregar a página 2 desmarcaria "Mesma posição da página 1" sozinho.
     */
    private var aplicando = false

    private class Previa(val bmp: Bitmap, val wPt: Float, val hPt: Float,
                         val total: Int, val indice: Int)

    /** A prévia na tela. Guardada para reaproveitar ao girar e reciclar ao trocar de página. */
    private var previa: Previa? = null

    /**
     * Cada pedido de prévia ganha um número, e só o último chega à tela. Sem
     * isso, dois toques rápidos na seta podiam terminar com a página 3 na
     * prévia e os boxes da página 2 por cima.
     */
    private var geracao = 0

    /** Proporção do logo real; 0 enquanto não foi lida. */
    private var aspectoLogo = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        idProtocolo = intent.getStringExtra(EXTRA_PROTOCOLO).orEmpty()
        nomeArquivo = intent.getStringExtra(EXTRA_ARQUIVO).orEmpty()
        // Id e nome viram caminho em disco: fora das regras do store, a tela
        // fecha em vez de abrir arquivo de outra pasta (e pastaDe lancaria).
        if (!ProtocoloStore.idValido(idProtocolo) || !ProtocoloStore.nomeSimples(nomeArquivo)) {
            finish(); return
        }

        store = ProtocoloStore(this)
        // ABRE COM A ETIQUETA DA CONFIGURAÇÃO quando a página não tem tamanho
        // próprio. O serviço já declarou o tamanho que usa na ficha; repetir a
        // escolha aqui seria perguntar duas vezes a mesma coisa. O ajuste fino
        // continua disponível, com o teto de ETQ_MAX_W x ETQ_MAX_H.
        val cfg = com.radioterapia.ai.AppConfig(this)
        etqPadraoW = cfg.pdfEtiquetaLarguraMm.toFloat()
        etqPadraoH = cfg.pdfEtiquetaAlturaMm.toFloat()

        rascunho = store.obter(idProtocolo)?.paginas?.firstOrNull { it.arquivo == nomeArquivo }
            ?: ProtocoloStore.Pagina(nomeArquivo, etqWmm = etqPadraoW, etqHmm = etqPadraoH)

        montarTela(usarColunaLateral(resources.configuration))
        edtNome.setText(rascunho.nome)
        carregarPosicao()
        atualizarNav()
        carregarPrevia()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!this::rascunho.isInitialized) return
        val lateral = usarColunaLateral(newConfig)
        if (lateral == colunaLateral) return
        // O layout novo nasce vazio: o que está na tela vai para o rascunho
        // antes, e volta dele depois. O nome digitado ainda não está no
        // rascunho, então atravessa à parte.
        guardarAtual()
        val nome = edtNome.text.toString()
        montarTela(lateral)
        edtNome.setText(nome)
        carregarPosicao()
        atualizarNav()
    }

    private fun usarColunaLateral(c: Configuration): Boolean =
        c.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Infla o layout da orientação e liga as views. Chamado na abertura e a
     * cada giro que troca de layout; a prévia já renderizada é reaproveitada.
     */
    private fun montarTela(lateral: Boolean) {
        colunaLateral = lateral
        setContentView(if (lateral) R.layout.activity_protocolo_pagina_land
                       else R.layout.activity_protocolo_pagina)

        view = findViewById(R.id.viewPagina)
        txtPos = findViewById(R.id.txtProtPos)
        edtNome = findViewById(R.id.edtProtPagNome)
        chkEtq = findViewById(R.id.chkProtEtq)
        chkLogo = findViewById(R.id.chkProtLogo)
        chkRodape = findViewById(R.id.chkProtRodape)
        chkMesma = findViewById(R.id.chkProtMesmaPag)
        edtW = findViewById(R.id.edtProtEtqW)
        edtH = findViewById(R.id.edtProtEtqH)
        linhaNav = findViewById(R.id.linhaProtPagNav)
        txtPagNum = findViewById(R.id.txtProtPagNum)
        btnAnt = findViewById(R.id.btnProtPagAnt)
        btnProx = findViewById(R.id.btnProtPagProx)

        // O teto vem do modelo, e não do XML, para o campo e o que se grava
        // nunca divergirem.
        edtNome.filters = arrayOf(InputFilter.LengthFilter(ProtocoloStore.NOME_PAGINA_MAX))

        chkEtq.setOnCheckedChangeListener { _, v ->
            if (aplicando) return@setOnCheckedChangeListener
            view.etqAtiva = v
            mudouPelaMao()
            atualizarPos()
        }
        chkLogo.setOnCheckedChangeListener { _, v ->
            if (aplicando) return@setOnCheckedChangeListener
            view.logoAtivo = v
            mudouPelaMao()
            atualizarPos()
        }
        chkRodape.setOnCheckedChangeListener { _, v ->
            if (aplicando) return@setOnCheckedChangeListener
            view.rodapeAtivo = v
            mudouPelaMao()
        }
        chkMesma.setOnCheckedChangeListener { _, marcado ->
            if (aplicando) return@setOnCheckedChangeListener
            // Marcar devolve a página à posição da primeira. Desmarcar mantém
            // o que está na tela, que vira o ponto de partida do ajuste.
            if (marcado) aplicarPosicao(rascunho.posicaoBase)
        }
        view.aoMover = {
            mudouPelaMao()
            atualizarPos()
        }
        edtW.setOnFocusChangeListener { _, f -> if (!f) lerTamanho() }
        edtH.setOnFocusChangeListener { _, f -> if (!f) lerTamanho() }

        btnAnt.setOnClickListener { irPara(indice - 1) }
        btnProx.setOnClickListener { irPara(indice + 1) }
        findViewById<Button>(R.id.btnProtPagCancelar).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnProtPagSalvar).setOnClickListener { salvar() }

        previa?.let { view.definirPagina(it.bmp, it.wPt, it.hPt) }
        if (aspectoLogo > 0f) view.definirAspectoLogo(aspectoLogo)
    }

    // ---------------------------------------------------------------- posição

    /** Mostra a posição da página [indice] e o estado da herança dela. */
    private fun carregarPosicao() {
        aplicarPosicao(rascunho.posicaoDa(indice))
        aplicando = true
        try {
            chkMesma.isChecked = indice > 0 && !rascunho.temAjuste(indice)
        } finally {
            aplicando = false
        }
    }

    /** Põe [pos] na prévia e nos controles, sem contar como mudança do usuário. */
    private fun aplicarPosicao(pos: ProtocoloStore.Posicao) {
        aplicando = true
        try {
            view.etqAtiva = pos.etqAtiva
            view.logoAtivo = pos.logoAtivo
            view.rodapeAtivo = pos.rodapeAtivo
            view.etqXmm = pos.etqXmm; view.etqYmm = pos.etqYmm
            view.etqWmm = if (pos.etqWmm > 0f) pos.etqWmm else etqPadraoW
            view.etqHmm = if (pos.etqHmm > 0f) pos.etqHmm else etqPadraoH
            view.logoXmm = pos.logoXmm; view.logoYmm = pos.logoYmm
            chkEtq.isChecked = pos.etqAtiva
            chkLogo.isChecked = pos.logoAtivo
            chkRodape.isChecked = pos.rodapeAtivo
            edtW.setText(view.etqWmm.toInt().toString())
            edtH.setText(view.etqHmm.toInt().toString())
        } finally {
            aplicando = false
        }
        view.invalidate()
        atualizarPos()
    }

    private fun posicaoDaView(): ProtocoloStore.Posicao = ProtocoloStore.Posicao(
        etqAtiva = view.etqAtiva,
        etqXmm = view.etqXmm, etqYmm = view.etqYmm,
        etqWmm = view.etqWmm, etqHmm = view.etqHmm,
        logoAtivo = view.logoAtivo,
        logoXmm = view.logoXmm, logoYmm = view.logoYmm,
        rodapeAtivo = view.rodapeAtivo)

    /**
     * Uma mudança feita pelo usuário numa página além da primeira: ela deixa
     * de seguir a página 1. Na página 1 não há o que desmarcar — ela é a base.
     */
    private fun mudouPelaMao() {
        if (aplicando || indice <= 0 || !chkMesma.isChecked) return
        aplicando = true
        try {
            chkMesma.isChecked = false
        } finally {
            aplicando = false
        }
    }

    /**
     * Guarda no rascunho a página mostrada: sem ajuste próprio enquanto segue
     * a página 1, com a posição da tela em qualquer outro caso.
     */
    private fun guardarAtual() {
        lerTamanho()
        rascunho = if (indice > 0 && chkMesma.isChecked) rascunho.semAjuste(indice)
                   else rascunho.comPosicao(indice, posicaoDaView())
    }

    private fun lerTamanho() {
        val w = tamanhoDigitado(edtW, view.etqWmm, 20f, ProtocoloStore.ETQ_MAX_W)
        val h = tamanhoDigitado(edtH, view.etqHmm, 10f, ProtocoloStore.ETQ_MAX_H)
        if (w != view.etqWmm || h != view.etqHmm) {
            view.etqWmm = w
            view.etqHmm = h
            mudouPelaMao()
        }
        view.invalidate()
        atualizarPos()
    }

    /**
     * O tamanho digitado em [edt], dentro de [min]..[max]; [atual] quando o
     * campo mostra o mesmo número de antes ou não tem número.
     *
     * GUARDA: o campo mostra milímetros inteiros, e o tamanho gravado pode ter
     * fração. Comparar o TEXTO com o que a tela escreveu, e não o número lido
     * com o gravado, impede que só abrir e salvar arredonde 62,5 para 62 — e
     * que, numa página além da primeira, isso conte como mudança e desfaça a
     * herança da página 1.
     */
    private fun tamanhoDigitado(edt: EditText, atual: Float, min: Float, max: Float): Float {
        val txt = edt.text.toString().trim()
        if (txt == atual.toInt().toString()) return atual
        val lido = txt.toFloatOrNull() ?: return atual
        val v = lido.coerceIn(min, max)
        // Fora do limite, o campo passa a mostrar o tamanho que vale de fato.
        if (v != lido) edt.setText(v.toInt().toString())
        return v
    }

    private fun atualizarPos() {
        txtPos.text = getString(R.string.prot_pos_resumo,
            view.etqXmm.toInt(), view.etqYmm.toInt(),
            view.logoXmm.toInt(), view.logoYmm.toInt())
    }

    // ---------------------------------------------------------------- páginas

    private fun irPara(novo: Int) {
        if (novo == indice || novo < 0 || novo >= total) return
        guardarAtual()
        indice = novo
        carregarPosicao()
        atualizarNav()
        carregarPrevia()
    }

    /**
     * "Página n de N" só com mais de uma página: o PDF de página única
     * continua com a tela de sempre. A seta da ponta fica desabilitada e
     * apagada, e não some, para a outra não mudar de lugar sob o dedo.
     */
    private fun atualizarNav() {
        val varias = total > 1
        linhaNav.visibility = if (varias) View.VISIBLE else View.GONE
        if (varias) {
            txtPagNum.text = getString(R.string.prot_pag_n_de, indice + 1, total)
            habilitar(btnAnt, indice > 0)
            habilitar(btnProx, indice < total - 1)
        }
        chkMesma.visibility = if (indice > 0) View.VISIBLE else View.GONE
    }

    /**
     * GUARDA: o backgroundTint de cor única do XML vale para todos os estados,
     * então o botão desabilitado sairia igual ao habilitado. A transparência
     * é o que mostra que a seta não leva a lugar nenhum.
     */
    private fun habilitar(b: Button, ativo: Boolean) {
        b.isEnabled = ativo
        b.alpha = if (ativo) 1f else 0.38f
    }

    /**
     * Renderiza a página [indice] fora da thread principal, em resolução de
     * TELA, não de impressão.
     *
     * O que se decide aqui é posição, e posição se enxerga numa miniatura. Usar
     * a resolução de impressão gastaria dezenas de MB para um bitmap que a view
     * reduz em seguida — e é justamente o tipo de alocação que derruba o tablet
     * da sala. Pelo mesmo motivo a prévia anterior é reciclada assim que a
     * nova entra: sem isso, cada toque na seta somaria um bitmap à memória.
     */
    private fun carregarPrevia() {
        val arq = File(store.pastaDe(idProtocolo), nomeArquivo)
        val pedido = indice
        val g = ++geracao
        val lerLogo = aspectoLogo <= 0f
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val (res, aspecto) = withContext(Dispatchers.IO) {
                Pair(renderizarPagina(arq, pedido), if (lerLogo) aspectoDoLogo(app) else 0f)
            }
            if (isFinishing || isDestroyed || g != geracao) {
                // Nunca chegou à tela: ninguém mais aponta para ela.
                res?.bmp?.recycle()
                return@launch
            }
            // O box do logo tem a forma do logo REAL do serviço; um retângulo
            // genérico não diria se ele cabe onde está sendo posto.
            if (aspecto > 0f) {
                aspectoLogo = aspecto
                view.definirAspectoLogo(aspecto)
            }
            if (res == null) {
                Toast.makeText(this@ProtocoloPaginaActivity,
                    R.string.prot_pdf_ilegivel, Toast.LENGTH_LONG).show()
                return@launch
            }
            total = res.total
            if (res.indice != indice) {
                // O PDF tem menos páginas do que o pedido: a prévia mostra a
                // última, e os controles passam a ser os dela.
                indice = res.indice
                carregarPosicao()
            }
            val anterior = previa
            previa = res
            view.definirPagina(res.bmp, res.wPt, res.hPt)
            // GUARDA: reciclar só DEPOIS de a view apontar para a nova. O
            // próximo desenho já usa a nova, e a velha não volta à tela.
            if (anterior != null && anterior.bmp !== res.bmp) anterior.bmp.recycle()
            atualizarNav()
        }
    }

    /**
     * A página [pedido] do PDF (ou a última, se ele tiver menos), com o número
     * de páginas lido na MESMA abertura do arquivo. `null` se ele não abrir.
     */
    private fun renderizarPagina(arq: File, pedido: Int): Previa? {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(arq, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            val n = r.pageCount
            if (n <= 0) return null
            val i = pedido.coerceIn(0, n - 1)
            val page = r.openPage(i)
            try {
                val wPt = page.width.toFloat()
                val hPt = page.height.toFloat()
                val alvo = 1200f
                val esc = (alvo / maxOf(wPt, hPt)).coerceAtMost(3f)
                val bmp = Bitmap.createBitmap(
                    (wPt * esc).toInt().coerceAtLeast(1),
                    (hPt * esc).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                Previa(bmp, wPt, hPt, n, i)
            } finally {
                // GUARDA: o PdfRenderer recusa fechar com uma página aberta.
                page.close()
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { r?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Largura sobre altura do logo do serviço, lida só pelo cabeçalho do
     * arquivo, sem decodificar a imagem. 0 quando não há logo.
     */
    private fun aspectoDoLogo(ctx: Context): Float = try {
        val arq = LogoManager(ctx).obterArquivo()
        if (arq == null) 0f
        else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(arq.absolutePath, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 0f
        }
    } catch (_: Exception) { 0f }

    // ---------------------------------------------------------------- salvar

    /**
     * Grava a página pelo store, que relê o protocolo na hora e troca só esta
     * página, no lugar, pelo nome do arquivo.
     *
     * GUARDA: a página gravada é o rascunho com `copy`, nunca um
     * `Pagina(...)` novo. Construída do zero, ela perderia o verso, o nome e
     * as posições das outras páginas, que esta tela não mostra todas de uma
     * vez — e o PDF do verso ficaria órfão na pasta do protocolo.
     */
    private fun salvar() {
        guardarAtual()
        val final = rascunho.copy(nome = ProtocoloStore.nomeDePagina(edtNome.text.toString()))
        if (store.atualizarPagina(idProtocolo, final)) {
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, R.string.export_zip_fail, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_PROTOCOLO = "prot_id"
        const val EXTRA_ARQUIVO = "prot_arq"

        fun abrir(origem: Activity,
                  launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
                  idProtocolo: String, arquivo: String) {
            launcher.launch(Intent(origem, ProtocoloPaginaActivity::class.java).apply {
                putExtra(EXTRA_PROTOCOLO, idProtocolo)
                putExtra(EXTRA_ARQUIVO, arquivo)
            })
        }
    }
}
