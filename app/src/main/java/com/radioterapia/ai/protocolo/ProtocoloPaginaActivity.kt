package com.radioterapia.ai.protocolo

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
 * Calibra onde a etiqueta e o logotipo caem numa página do protocolo.
 *
 * Tela própria, e não diálogo, porque o que se decide aqui só se decide
 * OLHANDO a página inteira: o ponto do ajuste é não cobrir o que já está
 * impresso no documento do serviço.
 *
 * A PRÉVIA É A PRIMEIRA PÁGINA do PDF carregado. Um arquivo com várias páginas
 * usa a mesma calibração em todas — é o caso comum (um termo de duas folhas com
 * o mesmo cabeçalho), e oferecer calibração por página de um mesmo arquivo
 * multiplicaria a tela sem resolver nada que separar os PDFs não resolva.
 */
class ProtocoloPaginaActivity : BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.prot_pagina_titulo)

    private lateinit var view: ProtocoloPaginaView
    private lateinit var txtPos: TextView
    private var idProtocolo = ""
    private var nomeArquivo = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protocolo_pagina)

        idProtocolo = intent.getStringExtra(EXTRA_PROTOCOLO).orEmpty()
        nomeArquivo = intent.getStringExtra(EXTRA_ARQUIVO).orEmpty()
        if (idProtocolo.isBlank() || nomeArquivo.isBlank()) { finish(); return }

        view = findViewById(R.id.viewPagina)
        txtPos = findViewById(R.id.txtProtPos)
        val chkEtq = findViewById<CheckBox>(R.id.chkProtEtq)
        val chkLogo = findViewById<CheckBox>(R.id.chkProtLogo)
        val edtW = findViewById<EditText>(R.id.edtProtEtqW)
        val edtH = findViewById<EditText>(R.id.edtProtEtqH)

        val store = ProtocoloStore(this)
        val prot = store.obter(idProtocolo)
        val pag = prot?.paginas?.firstOrNull { it.arquivo == nomeArquivo }
            ?: ProtocoloStore.Pagina(nomeArquivo)

        // ABRE COM A ETIQUETA DA CONFIGURAÇÃO. O serviço já declarou o tamanho
        // que usa na ficha; repetir a escolha aqui seria perguntar duas vezes a
        // mesma coisa. O ajuste fino continua disponível, com o teto de 100x50.
        val cfg = com.radioterapia.ai.AppConfig(this)
        view.etqAtiva = pag.etqAtiva
        view.logoAtivo = pag.logoAtivo
        view.etqXmm = pag.etqXmm; view.etqYmm = pag.etqYmm
        view.etqWmm = if (pag.etqWmm > 0f) pag.etqWmm else cfg.pdfEtiquetaLarguraMm.toFloat()
        view.etqHmm = if (pag.etqHmm > 0f) pag.etqHmm else cfg.pdfEtiquetaAlturaMm.toFloat()
        view.logoXmm = pag.logoXmm; view.logoYmm = pag.logoYmm

        chkEtq.isChecked = view.etqAtiva
        chkLogo.isChecked = view.logoAtivo
        edtW.setText(view.etqWmm.toInt().toString())
        edtH.setText(view.etqHmm.toInt().toString())

        chkEtq.setOnCheckedChangeListener { _, v -> view.etqAtiva = v; atualizarPos() }
        chkLogo.setOnCheckedChangeListener { _, v -> view.logoAtivo = v; atualizarPos() }
        view.aoMover = { atualizarPos() }

        fun lerTamanho() {
            val w = edtW.text.toString().toFloatOrNull() ?: view.etqWmm
            val h = edtH.text.toString().toFloatOrNull() ?: view.etqHmm
            view.etqWmm = w.coerceIn(20f, ProtocoloStore.ETQ_MAX_W)
            view.etqHmm = h.coerceIn(10f, ProtocoloStore.ETQ_MAX_H)
            view.invalidate(); atualizarPos()
        }
        edtW.setOnFocusChangeListener { _, f -> if (!f) lerTamanho() }
        edtH.setOnFocusChangeListener { _, f -> if (!f) lerTamanho() }

        findViewById<Button>(R.id.btnProtPagCancelar).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnProtPagSalvar).setOnClickListener {
            lerTamanho()
            salvar(store, prot)
        }

        carregarPrevia(store)
        atualizarPos()
    }

    /**
     * Renderiza a primeira página do PDF em resolução de TELA, não de impressão.
     *
     * O que se decide aqui é posição, e posição se enxerga numa miniatura. Usar
     * a resolução de impressão gastaria dezenas de MB para um bitmap que a view
     * reduz em seguida — e é justamente o tipo de alocação que derruba o tablet
     * da sala.
     */
    private fun carregarPrevia(store: ProtocoloStore) {
        val arq = File(store.pastaDe(idProtocolo), nomeArquivo)
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) { renderizarPrimeira(arq) }
            if (isFinishing || isDestroyed) return@launch
            if (res == null) {
                Toast.makeText(this@ProtocoloPaginaActivity,
                    R.string.prot_pdf_ilegivel, Toast.LENGTH_LONG).show()
                return@launch
            }
            view.definirPagina(res.first, res.second, res.third)
            // O box do logo tem a forma do logo REAL do serviço; um retângulo
            // genérico não diria se ele cabe onde está sendo posto.
            try {
                LogoManager(this@ProtocoloPaginaActivity).obterBitmap()?.let { lg ->
                    if (lg.height > 0) view.definirAspectoLogo(lg.width.toFloat() / lg.height)
                }
            } catch (_: Exception) {}
        }
    }

    private fun renderizarPrimeira(arq: File): Triple<Bitmap, Float, Float>? {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(arq, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            if (r.pageCount == 0) return null
            val page = r.openPage(0)
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
            page.close()
            Triple(bmp, wPt, hPt)
        } catch (_: Throwable) {
            null
        } finally {
            try { r?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun atualizarPos() {
        txtPos.text = getString(R.string.prot_pos_resumo,
            view.etqXmm.toInt(), view.etqYmm.toInt(),
            view.logoXmm.toInt(), view.logoYmm.toInt())
    }

    private fun salvar(store: ProtocoloStore, prot: ProtocoloStore.Protocolo?) {
        val p = prot ?: store.obter(idProtocolo) ?: return
        val nova = ProtocoloStore.Pagina(
            arquivo = nomeArquivo,
            etqAtiva = view.etqAtiva,
            etqXmm = view.etqXmm, etqYmm = view.etqYmm,
            etqWmm = view.etqWmm, etqHmm = view.etqHmm,
            logoAtivo = view.logoAtivo,
            logoXmm = view.logoXmm, logoYmm = view.logoYmm)
        val paginas = p.paginas.toMutableList()
        val i = paginas.indexOfFirst { it.arquivo == nomeArquivo }
        if (i >= 0) paginas[i] = nova else paginas.add(nova)
        if (store.salvar(p.copy(paginas = paginas))) {
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
