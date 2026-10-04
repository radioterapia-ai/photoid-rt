package com.radioterapia.ai.protocolo

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
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
 *
 * CADA PDF TEM UM NOME, sugerido pelo nome do arquivo escolhido e editável na
 * calibração, e a ordem da lista é a ordem de impressão, mudada pelas setas.
 * Nome e ordem vão a disco na hora, como tudo o que mexe nas páginas.
 */
class ProtocoloActivity : BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.prot_titulo)

    private lateinit var store: ProtocoloStore
    private lateinit var atual: ProtocoloStore.Protocolo
    private lateinit var lista: LinearLayout
    private lateinit var imgMini: ImageView

    /**
     * Frente cuja folha está esperando um verso.
     *
     * Guardado num campo porque o seletor de arquivo do sistema volta por
     * callback, e ali não há como saber em qual linha o usuário tocou.
     */
    private var frenteAguardandoVerso: String = ""

    /**
     * Páginas de cada PDF da frente, lidas uma vez por arquivo. O nome do
     * arquivo leva o carimbo de tempo da cópia e nunca é reaproveitado, então
     * o número não muda; sem esta memória, cada toque numa seta reabriria
     * todos os PDFs na thread principal.
     */
    private val folhasPorArquivo = HashMap<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protocolo)
        store = ProtocoloStore(this)

        // GUARDA: recriada pelo sistema (giro, processo reciclado com o seletor
        // de arquivo aberto), a tela volta ao MESMO id. Protocolo novo ganha
        // lugar na lista ao receber o primeiro PDF; com um id novo a cada
        // recriação, aquele ficaria na lista sem nome e o Salvar criaria outro.
        val idSalvo = savedInstanceState?.getString(ESTADO_ID)
        val id = idSalvo ?: intent.getStringExtra(EXTRA_ID)
        atual = (id?.let { store.obter(it) })
            ?: ProtocoloStore.Protocolo(
                idSalvo?.takeIf { ProtocoloStore.idValido(it) } ?: store.novoId(), "", false)
        frenteAguardandoVerso = savedInstanceState?.getString(ESTADO_VERSO).orEmpty()

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
        findViewById<Button>(R.id.btnProtGirarMini).setOnClickListener {
            if (atual.miniatura.isBlank()) {
                Toast.makeText(this, R.string.prot_sem_miniatura, Toast.LENGTH_SHORT).show()
            } else if (store.girarMiniatura(atual)) {
                desenharMiniatura()
            } else {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::atual.isInitialized) outState.putString(ESTADO_ID, atual.id)
        outState.putString(ESTADO_VERSO, frenteAguardandoVerso)
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

    /**
     * Grava JÁ uma mudança que mora em disco — as páginas, a miniatura —,
     * aplicada sobre a versão GRAVADA do protocolo, e a repete em `atual`.
     *
     * Páginas e miniatura são arquivos na pasta do protocolo: deixar a
     * referência só na memória até Salvar produziria arquivo órfão se a tela
     * fosse abandonada. O nome e a equipe do rubricário, não: eles esperam o
     * Salvar, e gravar `atual` inteiro aqui levaria junto uma escolha que o
     * usuário ainda pode cancelar. Protocolo novo, ainda fora da lista, entra
     * inteiro.
     */
    private fun gravarJa(mudanca: (ProtocoloStore.Protocolo) -> ProtocoloStore.Protocolo): Boolean {
        val gravado = store.obter(atual.id) ?: atual
        if (!store.salvar(mudanca(gravado))) return false
        atual = mudanca(atual)
        return true
    }

    private fun avisarFalhaAoGravar() {
        Toast.makeText(this, R.string.export_zip_fail, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- miniatura

    private val escolherMiniatura = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val idProt = atual.id
        CoroutineScope(Dispatchers.Main).launch {
            val nome = withContext(Dispatchers.IO) {
                try {
                    val destino = File(store.pastaDe(idProt), "miniatura.png")
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
            if (!gravarJa { it.copy(miniatura = nome) }) avisarFalhaAoGravar()
            desenharMiniatura()
        }
    }

    private fun desenharMiniatura() {
        val arq = store.arquivoMiniatura(atual)
        if (arq == null) { imgMini.setImageResource(R.drawable.bg_sem_foto); return }
        // Mesma razao do ProtocoloStore.bitmapMiniatura: decodeFile ignora a
        // orientacao do EXIF e devolve deitada a foto tirada em pe.
        val bmp = try {
            com.radioterapia.ai.util.ImagemUtils.decodificarComExif(arq, 640)
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
        val idProt = atual.id
        CoroutineScope(Dispatchers.Main).launch {
            val copia = withContext(Dispatchers.IO) {
                val nome = copiarPdf(uri, idProt)
                // O nome do PDF já vem sugerido pelo do arquivo: o caso comum
                // não custa toque nenhum, e a calibração, que abre em seguida,
                // permite trocá-lo.
                if (nome == null) null
                else nome to ProtocoloStore.rotuloDeArquivo(nomeExibido(uri))
            }
            if (isFinishing || isDestroyed) return@launch
            if (copia == null) {
                Toast.makeText(this@ProtocoloActivity, R.string.prot_pdf_ilegivel,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            val (nome, rotulo) = copia
            val cfg = com.radioterapia.ai.AppConfig(this@ProtocoloActivity)
            val nova = ProtocoloStore.Pagina(
                arquivo = nome,
                etqWmm = cfg.pdfEtiquetaLarguraMm.toFloat(),
                etqHmm = cfg.pdfEtiquetaAlturaMm.toFloat(),
                nome = rotulo)
            val novas = atual.paginas + nova
            if (!gravarJa { it.copy(paginas = novas) }) {
                store.excluirArquivo(idProt, nome)
                avisarFalhaAoGravar()
                return@launch
            }
            desenharPaginas()
            // Abre a calibração na hora: o PDF acabou de entrar e ninguém sabe
            // ainda onde os boxes caíram na página dele.
            ProtocoloPaginaActivity.abrir(this@ProtocoloActivity, editarPagina, atual.id, nome)
        }
    }

    private val escolherVerso = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        val frente = frenteAguardandoVerso
        frenteAguardandoVerso = ""
        if (uri == null || frente.isBlank()) return@registerForActivityResult
        val idProt = atual.id
        CoroutineScope(Dispatchers.Main).launch {
            val nome = withContext(Dispatchers.IO) { copiarPdf(uri, idProt, "verso") }
            if (isFinishing || isDestroyed) return@launch
            if (nome == null) {
                Toast.makeText(this@ProtocoloActivity, R.string.prot_pdf_ilegivel,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            val novas = atual.paginas.map {
                if (it.arquivo == frente) it.copy(verso = nome) else it
            }
            if (!gravarJa { it.copy(paginas = novas) }) {
                store.excluirArquivo(idProt, nome)
                avisarFalhaAoGravar()
                return@launch
            }
            desenharPaginas()
            // NÃO abre a calibração: o verso não recebe etiqueta nem logotipo,
            // então não há box nenhum para posicionar nele.
            Toast.makeText(this@ProtocoloActivity, R.string.prot_verso_add,
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmarRemoverVerso(pag: ProtocoloStore.Pagina) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.prot_verso_remover)
            .setMessage(R.string.prot_verso_remover_q)
            .setPositiveButton(R.string.rub_excluir) { _, _ ->
                val novas = atual.paginas.map {
                    if (it.arquivo == pag.arquivo) it.copy(verso = "") else it
                }
                // A lista vai a disco ANTES de o arquivo sair: se gravar
                // falhar, a página continua apontando para um verso que existe.
                if (gravarJa { it.copy(paginas = novas) }) {
                    store.excluirArquivo(atual.id, pag.verso)
                    desenharPaginas()
                } else avisarFalhaAoGravar()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copiarPdf(uri: android.net.Uri, idProt: String, prefixo: String = "pag"): String? = try {
        val nome = "${prefixo}_${System.currentTimeMillis()}.pdf"
        val destino = File(store.pastaDe(idProt), nome)
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

    /**
     * Nome do arquivo como o provedor de documentos o mostra ("Termo.pdf").
     * Consulta local ao provedor, sem rede; `null` quando ele não informa,
     * e aí o PDF entra sem rótulo.
     */
    private fun nomeExibido(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    /**
     * Volta da calibração: as páginas são relidas do disco, onde a calibração
     * gravou. Só as páginas — o nome e a equipe escolhidos aqui e ainda não
     * salvos continuam valendo.
     */
    private val editarPagina = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        atual = store.obter(atual.id)?.let { atual.copy(paginas = it.paginas) } ?: atual
        desenharPaginas()
    }

    private fun calibrar(pag: ProtocoloStore.Pagina) {
        ProtocoloPaginaActivity.abrir(this, editarPagina, atual.id, pag.arquivo)
    }

    /**
     * Uma linha por PDF: setas de ordem, nome e resumo, Calibrar, Verso e
     * Excluir.
     *
     * As setas vêm no INÍCIO da linha. A da ponta fica INVISIBLE, e não GONE,
     * para o nome começar na mesma coluna em todas as linhas. Não há
     * confirmação: cada toque se desfaz com a seta oposta.
     */
    private fun desenharPaginas() {
        lista.removeAllViews()
        if (atual.paginas.isEmpty()) {
            lista.addView(TextView(this).apply {
                setText(R.string.prot_sem_paginas)
                setTextColor(cor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val ultima = atual.paginas.lastIndex
        atual.paginas.forEachIndexed { i, pag ->
            val linha = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            linha.addView(botaoSeta(R.string.seta_cima, R.string.prot_mover_cima, i > 0) {
                moverPagina(i, -1)
            })
            linha.addView(botaoSeta(R.string.seta_baixo, R.string.prot_mover_baixo, i < ultima) {
                moverPagina(i, +1)
            })
            linha.addView(colunaDoPdf(i, pag))
            linha.addView(Button(this).apply {
                setText(R.string.prot_calibrar)
                textSize = 12f
                setOnClickListener { calibrar(pag) }
            })
            linha.addView(Button(this).apply {
                setText(if (pag.verso.isNotBlank()) R.string.prot_verso_remover
                        else R.string.prot_verso_add)
                textSize = 12f
                setOnClickListener {
                    if (pag.verso.isNotBlank()) confirmarRemoverVerso(pag)
                    else {
                        frenteAguardandoVerso = pag.arquivo
                        try { escolherVerso.launch(arrayOf("application/pdf")) }
                        catch (_: Exception) {
                            frenteAguardandoVerso = ""
                            Toast.makeText(this@ProtocoloActivity,
                                R.string.prot_sem_seletor, Toast.LENGTH_SHORT).show()
                        }
                    }
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

    /**
     * Nome do PDF em negrito e, embaixo, o resumo de sempre ("1. PDF com 2
     * página(s)"). Sem nome, só o resumo, numa linha de 14sp. Tocar no texto
     * abre a calibração, como o botão Calibrar: é lá que o nome se edita.
     *
     * As duas linhas somam menos que a altura dos botões, então a linha da
     * lista não cresce com o nome.
     */
    private fun colunaDoPdf(i: Int, pag: ProtocoloStore.Pagina): LinearLayout {
        val n = folhasDe(pag)
        val resumo = if (pag.verso.isNotBlank())
            getString(R.string.prot_pagina_item_verso, i + 1, n)
        else getString(R.string.prot_pagina_item, i + 1, n)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // O fundo de toque ANTES do padding: trocar o fundo pode
            // redefinir o padding da view.
            val toque = TypedValue()
            if (this@ProtocoloActivity.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, toque, true)) {
                setBackgroundResource(toque.resourceId)
            }
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = dp(48)
            setOnClickListener { calibrar(pag) }
        }
        if (pag.nome.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = pag.nome
                setTypeface(null, Typeface.BOLD)
                setTextColor(cor(R.color.text_primary))
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = resumo
                setTextColor(cor(R.color.text_secondary))
                textSize = 12f
            })
        } else {
            col.addView(TextView(this).apply {
                text = resumo
                setTextColor(cor(R.color.text_primary))
                textSize = 14f
            })
        }
        return col
    }

    /**
     * Seta de reordenar, com largura FIXA de 48dp nos LayoutParams.
     *
     * GUARDA: o Button do framework lê o minWidth duas vezes, no View e no
     * TextView, e com WRAP_CONTENT ficaria com os 88dp do estilo mesmo com um
     * dos dois zerado. A largura exata nos LayoutParams vence os dois. Sem
     * ação, a seta fica INVISIBLE: ocupa a vaga e mantém a coluna do nome
     * alinhada entre as linhas.
     */
    private fun botaoSeta(@StringRes glifo: Int, @StringRes descricao: Int,
                          ativa: Boolean, aoTocar: () -> Unit): Button =
        Button(this).apply {
            setText(glifo)
            contentDescription = getString(descricao)
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
            visibility = if (ativa) View.VISIBLE else View.INVISIBLE
            if (ativa) setOnClickListener { aoTocar() }
        }

    /**
     * Troca o PDF [i] com o vizinho e grava na hora. A ordem da lista é a
     * ordem em que as páginas saem impressas, e o rodapé conta as páginas
     * nessa mesma ordem.
     */
    private fun moverPagina(i: Int, delta: Int) {
        val novas = ProtocoloStore.moverItem(atual.paginas, i, delta)
        if (novas === atual.paginas) return
        if (gravarJa { it.copy(paginas = novas) }) desenharPaginas()
        else avisarFalhaAoGravar()
    }

    private fun folhasDe(pag: ProtocoloStore.Pagina): Int =
        folhasPorArquivo.getOrPut(pag.arquivo) {
            store.arquivoPagina(atual, pag)
                ?.let { com.radioterapia.ai.pdf.PdfPaginas.contar(it) } ?: 0
        }

    private fun cor(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun confirmarExcluirPagina(pag: ProtocoloStore.Pagina) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.prot_excluir_pagina_q)
            .setPositiveButton(R.string.rub_excluir) { _, _ ->
                val novas = atual.paginas.filter { it.arquivo != pag.arquivo }
                // A lista vai a disco ANTES de os arquivos saírem: se gravar
                // falhar, a página continua apontando para um PDF que existe.
                if (!gravarJa { it.copy(paginas = novas) }) {
                    avisarFalhaAoGravar()
                    return@setPositiveButton
                }
                // A exclusao passa pelo store, que confere o caminho canonico
                // contra a pasta do protocolo: o nome da pagina vem do
                // lista.json, que pode ter chegado num pacote.
                store.excluirArquivo(atual.id, pag.arquivo)
                // O verso sai junto: sem isto o PDF dele ficaria na pasta do
                // protocolo sem nada apontando para ele, e viajaria na
                // exportacao como peso morto.
                if (pag.verso.isNotBlank()) store.excluirArquivo(atual.id, pag.verso)
                folhasPorArquivo.remove(pag.arquivo)
                desenharPaginas()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "prot_id"
        private const val ESTADO_ID = "estado_prot_id"
        private const val ESTADO_VERSO = "estado_frente_verso"

        fun abrir(origem: Activity,
                  launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
                  id: String? = null) {
            launcher.launch(Intent(origem, ProtocoloActivity::class.java).apply {
                if (id != null) putExtra(EXTRA_ID, id)
            })
        }
    }
}
