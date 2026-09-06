package com.radioterapia.ai.protocolo

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.radioterapia.ai.BaseActivity
import com.radioterapia.ai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Edita um protocolo: nome, miniatura e as páginas que ele acrescenta à ficha.
 *
 * O PROTOCOLO PADRÃO É EDITÁVEL como qualquer outro — o nome e a miniatura dele
 * mudam, e ele até pode ganhar páginas. O que não se faz é excluí-lo, e essa
 * regra mora no [ProtocoloStore], não aqui: proibir só na tela deixaria o
 * caminho aberto para qualquer outro código apagar o padrão sem perceber.
 *
 * OS ARQUIVOS SÃO COPIADOS para dentro da pasta do protocolo, e não referenciados
 * de onde o usuário escolheu. Um Uri de SAF é uma permissão dada a este tablet;
 * na exportação para outro ele apontaria para o nada, e a ficha sairia sem as
 * páginas — em silêncio, que é o pior jeito de falhar num documento clínico.
 */
class ProtocoloActivity : BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.prot_titulo)

    private lateinit var store: ProtocoloStore
    private lateinit var atual: ProtocoloStore.Protocolo
    private lateinit var lista: LinearLayout
    private lateinit var imgMini: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protocolo)
        store = ProtocoloStore(this)

        val id = intent.getStringExtra(EXTRA_ID)
        atual = (id?.let { store.obter(it) })
            ?: ProtocoloStore.Protocolo(store.novoId(), "", false)

        lista = findViewById(R.id.listaProtPaginas)
        imgMini = findViewById(R.id.imgProtMiniatura)
        val edtNome = findViewById<EditText>(R.id.edtProtNome)
        edtNome.setText(atual.nome)

        montarSeletorRubricario()

        findViewById<Button>(R.id.btnProtMiniatura).setOnClickListener {
            try { escolherMiniatura.launch(arrayOf("image/*")) }
            catch (_: Exception) {
                Toast.makeText(this, R.string.gallery_fail, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnProtAddPagina).setOnClickListener {
            try { escolherPdf.launch(arrayOf("application/pdf")) }
            catch (_: Exception) {
                Toast.makeText(this, R.string.prot_sem_seletor, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnProtCancelar).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnProtSalvar).setOnClickListener {
            val nome = edtNome.text.toString().trim()
            if (nome.isBlank()) {
                Toast.makeText(this, R.string.prot_falta_nome, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            atual = atual.copy(nome = nome)
            if (store.salvar(atual)) {
                setResult(Activity.RESULT_OK); finish()
            } else {
                Toast.makeText(this, R.string.export_zip_fail, Toast.LENGTH_LONG).show()
            }
        }

        desenharMiniatura()
        desenharPaginas()
    }

    /**
     * Qual equipe assina a ficha deste protocolo.
     *
     * NAO GRAVA A CADA TOQUE: a escolha entra em `atual` e vai a disco com o
     * botao Salvar, junto do nome. Gravar aqui deixaria o protocolo com uma
     * equipe nova mesmo se o usuario cancelasse a edicao inteira.
     *
     * Com uma equipe so, o seletor mostra uma linha — a rotina de sempre, sem
     * decisao nova para quem tem uma clinica.
     */
    private fun montarSeletorRubricario() {
        val sp = findViewById<android.widget.Spinner>(R.id.spProtRubricario)
        val blocos = com.radioterapia.ai.rubricario.RubricarioStore(this).listarBlocos()
        sp.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, blocos.map { it.nome })
        sp.setSelection(
            blocos.indexOfFirst { it.id == atual.rubricarioId }.coerceAtLeast(0))
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(pai: android.widget.AdapterView<*>?, v: View?,
                                        pos: Int, id: Long) {
                atual = atual.copy(
                    rubricarioId = blocos.getOrNull(pos)?.id ?: return)
            }
            override fun onNothingSelected(pai: android.widget.AdapterView<*>?) {}
        }
    }

    // ---------------------------------------------------------------- miniatura

    private val escolherMiniatura = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        CoroutineScope(Dispatchers.Main).launch {
            val nome = withContext(Dispatchers.IO) {
                try {
                    val destino = File(store.pastaDe(atual.id), "miniatura.png")
                    contentResolver.openInputStream(uri)?.use { ent ->
                        destino.outputStream().use { ent.copyTo(it) }
                    }
                    if (destino.exists() && destino.length() > 0) destino.name else null
                } catch (_: Exception) { null }
            }
            if (isFinishing || isDestroyed) return@launch
            if (nome == null) {
                Toast.makeText(this@ProtocoloActivity, R.string.gallery_fail,
                    Toast.LENGTH_SHORT).show()
                return@launch
            }
            atual = atual.copy(miniatura = nome)
            // Grava JÁ: a miniatura é um arquivo em disco, e deixar a referência
            // só na memória até "Salvar" produziria um PNG órfão se a tela fosse
            // abandonada.
            store.salvar(atual)
            desenharMiniatura()
        }
    }

    private fun desenharMiniatura() {
        val arq = store.arquivoMiniatura(atual)
        if (arq == null) { imgMini.setImageResource(R.drawable.bg_sem_foto); return }
        val bmp = try {
            val op = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(arq.absolutePath, op)
        } catch (_: Throwable) { null }
        // decodeFile devolve null para arquivo corrompido SEM lançar nada: sem
        // esta checagem a miniatura ficaria em branco e pareceria não escolhida.
        if (bmp != null) imgMini.setImageBitmap(bmp)
        else imgMini.setImageResource(R.drawable.bg_sem_foto)
    }

    // ---------------------------------------------------------------- páginas

    private val escolherPdf = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        CoroutineScope(Dispatchers.Main).launch {
            val nome = withContext(Dispatchers.IO) { copiarPdf(uri) }
            if (isFinishing || isDestroyed) return@launch
            if (nome == null) {
                Toast.makeText(this@ProtocoloActivity, R.string.prot_pdf_ilegivel,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            val cfg = com.radioterapia.ai.AppConfig(this@ProtocoloActivity)
            val nova = ProtocoloStore.Pagina(
                arquivo = nome,
                etqWmm = cfg.pdfEtiquetaLarguraMm.toFloat(),
                etqHmm = cfg.pdfEtiquetaAlturaMm.toFloat())
            atual = atual.copy(paginas = atual.paginas + nova)
            store.salvar(atual)
            desenharPaginas()
            // Abre a calibração na hora: o PDF acabou de entrar e ninguém sabe
            // ainda onde os boxes caíram na página dele.
            ProtocoloPaginaActivity.abrir(this@ProtocoloActivity, editarPagina, atual.id, nome)
        }
    }

    private fun copiarPdf(uri: android.net.Uri): String? = try {
        val nome = "pag_${System.currentTimeMillis()}.pdf"
        val destino = File(store.pastaDe(atual.id), nome)
        contentResolver.openInputStream(uri)?.use { ent ->
            destino.outputStream().use { ent.copyTo(it) }
        }
        // Confere que é PDF legível ANTES de aceitar. Um arquivo protegido por
        // senha ou corrompido só falharia na hora de imprimir a ficha de um
        // paciente, que é o pior momento possível para descobrir.
        if (destino.exists() && destino.length() > 0 &&
            com.radioterapia.ai.pdf.PdfPaginas.contar(destino) > 0) nome
        else { destino.delete(); null }
    } catch (_: Exception) { null }

    private val editarPagina = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        atual = store.obter(atual.id) ?: atual
        desenharPaginas()
    }

    private fun desenharPaginas() {
        lista.removeAllViews()
        val d = resources.displayMetrics.density
        if (atual.paginas.isEmpty()) {
            lista.addView(TextView(this).apply {
                setText(R.string.prot_sem_paginas)
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@ProtocoloActivity, R.color.text_secondary))
                textSize = 13f
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            })
            return
        }
        atual.paginas.forEachIndexed { i, pag ->
            val linha = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            }
            val n = com.radioterapia.ai.pdf.PdfPaginas.contar(
                File(store.pastaDe(atual.id), pag.arquivo))
            linha.addView(TextView(this).apply {
                text = getString(R.string.prot_pagina_item, i + 1, n)
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@ProtocoloActivity, R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            linha.addView(Button(this).apply {
                setText(R.string.prot_calibrar)
                textSize = 12f
                setOnClickListener {
                    ProtocoloPaginaActivity.abrir(
                        this@ProtocoloActivity, editarPagina, atual.id, pag.arquivo)
                }
            })
            linha.addView(Button(this).apply {
                setText(R.string.rub_excluir)
                textSize = 12f
                setOnClickListener { confirmarExcluirPagina(pag) }
            })
            lista.addView(linha)
        }
    }

    private fun confirmarExcluirPagina(pag: ProtocoloStore.Pagina) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.prot_excluir_pagina_q)
            .setPositiveButton(R.string.rub_excluir) { _, _ ->
                try { File(store.pastaDe(atual.id), pag.arquivo).delete() } catch (_: Exception) {}
                atual = atual.copy(paginas = atual.paginas.filter { it.arquivo != pag.arquivo })
                store.salvar(atual)
                desenharPaginas()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "prot_id"

        fun abrir(origem: Activity,
                  launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
                  id: String? = null) {
            launcher.launch(Intent(origem, ProtocoloActivity::class.java).apply {
                if (id != null) putExtra(EXTRA_ID, id)
            })
        }
    }
}
