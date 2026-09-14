package com.radioterapia.ai

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.radioterapia.ai.i18n.LocaleManager
import com.radioterapia.ai.ui.SettingsActivity
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Base de todas as Activities visuais do app.
 *
 * O que ela faz:
 *  - Aplica o locale (idioma) escolhido pelo usuário (via attachBaseContext + LocaleManager)
 *  - Intercepta setContentView e injeta uma TOOLBAR CUSTOM padrão (sem usar a ActionBar
 *    do framework) com botão "voltar" (seta) à esquerda + título centralizado + opcional
 *    botão de engrenagem à direita (apenas na Home, item 13).
 *
 * Subclasses NÃO chamam mais setSupportActionBar nem mexem com supportActionBar.
 * Para esconder a toolbar (Splash, Main da câmera, Wizard, Scan): override mostrarToolbar() = false.
 * Para mostrar a engrenagem (Home): override mostrarEngrenagemAoInvesDeVoltar() = true.
 * Para setar o título: override tituloPadrao(): String.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    protected open fun mostrarToolbar(): Boolean = true

    /** Seta "voltar" da toolbar custom (para a tela esconder/redirecionar). */
    protected var btnToolbarVoltar: android.widget.ImageButton? = null

    /** Ação da seta "voltar" — sobrescrevível (ex.: pós-finalização segura). */
    protected open fun aoTocarVoltarToolbar() { finish() }

    /** Ícone de ação à direita na toolbar (0 = nenhum). */
    protected open fun acaoToolbar(): Int = 0
    /** Toque no ícone de ação da toolbar. */
    protected open fun aoTocarAcaoToolbar() {}
    protected var btnToolbarAcao: android.widget.ImageButton? = null
    protected open fun mostrarEngrenagemAoInvesDeVoltar(): Boolean = false
    protected open fun tituloPadrao(): String? = null

    private var tituloView: TextView? = null

    // -------- diálogos: cor por significado --------

    /**
     * Pinta os botões de um diálogo pelo que eles FAZEM, não pela posição.
     *
     * O diálogo padrão do Android pinta os três botões da mesma cor, e o
     * positivo — o mais à direita, o que a mão alcança primeiro e o que o olho
     * lê como "o botão" — nem sempre é a ação segura. No "Rascunho em
     * andamento", por exemplo, o positivo era **Descartar**: um toque apressado
     * com o paciente na mesa apagava a sessão de fotos inteira.
     *
     * Trocar a ordem quebraria a memória muscular de quem já usa o app todo dia.
     * A cor resolve sem mexer na posição: destrutivo em vermelho, seguro em
     * verde. Quem age no automático continua acertando; quem olha, vê o aviso.
     *
     * Chamar DEPOIS de `show()` — antes disso os botões ainda não existem.
     *
     * @param destrutivo qual botão apaga algo (`DialogInterface.BUTTON_*`), 0 = nenhum
     * @param seguro     qual botão preserva o trabalho, 0 = nenhum
     */
    private fun pintarBotao(b: Button?, corRes: Int) {
        b?.setTextColor(androidx.core.content.ContextCompat.getColor(this, corRes))
    }

    protected fun pintarBotoesDialog(d: AlertDialog, destrutivo: Int = 0, seguro: Int = 0) {
        try {
            if (destrutivo != 0) pintarBotao(d.getButton(destrutivo), R.color.error_red)
            if (seguro != 0) pintarBotao(d.getButton(seguro), R.color.success_green)
        } catch (_: Exception) { /* cor é reforço, nunca requisito */ }
    }

    /**
     * Mesma coisa para `android.app.AlertDialog`. O app usa os dois tipos —
     * algumas telas importam o do framework, outras o do AppCompat — e eles não
     * compartilham supertipo com `getButton`. Uma sobrecarga custa menos que
     * trocar o import de telas já validadas em campo, o que mudaria a aparência
     * dos diálogos sem ninguém ter pedido.
     */
    protected fun pintarBotoesDialog(d: android.app.AlertDialog,
                                     destrutivo: Int = 0, seguro: Int = 0) {
        try {
            if (destrutivo != 0) pintarBotao(d.getButton(destrutivo), R.color.error_red)
            if (seguro != 0) pintarBotao(d.getButton(seguro), R.color.success_green)
        } catch (_: Exception) { /* cor é reforço, nunca requisito */ }
    }

    /** Atalho: mostra o diálogo e já pinta os botões. */
    protected fun mostrarDialogPintado(b: AlertDialog.Builder,
                                       destrutivo: Int = 0, seguro: Int = 0): AlertDialog {
        val d = b.create()
        d.show()
        pintarBotoesDialog(d, destrutivo, seguro)
        return d
    }

    protected fun mostrarDialogPintado(b: android.app.AlertDialog.Builder,
                                       destrutivo: Int = 0,
                                       seguro: Int = 0): android.app.AlertDialog {
        val d = b.create()
        d.show()
        pintarBotoesDialog(d, destrutivo, seguro)
        return d
    }

    // -------- setContentView interceptado --------

    override fun setContentView(layoutResID: Int) {
        if (!mostrarToolbar()) {
            super.setContentView(layoutResID)
            return
        }
        val wrapper = construirWrapper()
        layoutInflater.inflate(layoutResID, wrapper.findViewById(R.id.baseContentContainer), true)
        super.setContentView(wrapper)
        aplicarTitulo()
    }

    override fun setContentView(view: View) {
        if (!mostrarToolbar()) {
            super.setContentView(view)
            return
        }
        val wrapper = construirWrapper()
        val container = wrapper.findViewById<FrameLayout>(R.id.baseContentContainer)
        container.addView(view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        super.setContentView(wrapper)
        aplicarTitulo()
    }

    private fun aplicarTitulo() {
        tituloPadrao()?.let { setToolbarTitle(it) }
    }

    private fun construirWrapper(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#00030E"))
        }

        // Toolbar custom
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0E1839"))
            setPadding(dp(4), 0, dp(4), 0)
        }

        // Botão esquerda: voltar OU vazio (Home não tem voltar)
        if (!mostrarEngrenagemAoInvesDeVoltar()) {
            val btnBack = ImageButton(this).apply {
                setImageResource(R.drawable.ic_arrow_back)
                background = null
                contentDescription = getString(R.string.back)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { aoTocarVoltarToolbar() }
            }
            btnToolbarVoltar = btnBack
            toolbar.addView(btnBack)
        } else {
            // Spacer (mesma largura do botão para o título ficar realmente centrado)
            toolbar.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            })
        }

        // Título (centralizado, ocupa o resto)
        val titulo = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        tituloView = titulo
        toolbar.addView(titulo)

        // Botão direita: engrenagem na Home, vazio nas outras
        if (mostrarEngrenagemAoInvesDeVoltar()) {
            val btnGear = ImageButton(this).apply {
                setImageResource(R.drawable.ic_settings_gear)
                background = null
                contentDescription = getString(R.string.settings)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    startActivity(android.content.Intent(this@BaseActivity,
                        SettingsActivity::class.java))
                }
            }
            toolbar.addView(btnGear)
        } else if (acaoToolbar() != 0) {
            // Slot de ação da tela (ex.: ⋮ do carrossel) na PRÓPRIA barra de
            // título, em vez de flutuar sobre o conteúdo.
            val btnAcao = ImageButton(this).apply {
                setImageResource(acaoToolbar())
                background = null
                contentDescription = getString(R.string.patient_actions)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { aoTocarAcaoToolbar() }
            }
            btnToolbarAcao = btnAcao
            toolbar.addView(btnAcao)
        } else {
            toolbar.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            })
        }

        root.addView(toolbar)

        // Container do conteúdo
        val container = FrameLayout(this).apply {
            id = R.id.baseContentContainer
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f)
        }
        root.addView(container)

        return root
    }

    // ---------- Pen-drive por cabo OTG ----------
    private val penDrive by lazy { com.radioterapia.ai.usb.PenDriveHelper(this) }
    private var dlgPenDrive: AlertDialog? = null
    private var viewPenDrive: View? = null
    private var pdfsParaPenDrive: List<java.io.File> = emptyList()
    /** Nomes dos pacientes, para o lote individual sair legível na impressora. */
    private var nomesParaPenDrive: List<String> = emptyList()
    /** Dados de origem do lote agrupado — o PDF é REMONTADO a partir deles,
     *  preservando texto e vetores em vez de colar imagens. */
    private var itensParaPenDrive: List<com.radioterapia.ai.pdf.PdfBuilder.ItemLote> = emptyList()
    /** Como gravar: ficha avulsa, um arquivo por paciente, ou tudo num só. */
    private var modoPenDrive: ModoPenDrive = ModoPenDrive.PACIENTE_ATUAL

    protected enum class ModoPenDrive { PACIENTE_ATUAL, LOTE }
    private var receiverPenDrive: android.content.BroadcastReceiver? = null

    /** Seletor de pasta do pen-drive (SAF). Registrado uma vez, como manda o AndroidX. */
    private val escolherPastaPenDrive = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) {
            penDrive.salvarPasta(uri)
        }
        atualizarEstadoPenDrive()
    }

    /**
     * ITEM 10 — seletor de MODO de impressão. O botão nunca fica apagado: o que
     * varia é a disponibilidade de cada modo.
     *
     *  • Impressora da rede (IP): a rotina JetDirect/IPP já existente. Fica
     *    esmaecida quando não responde, com o motivo escrito.
     *  • Outra impressora (sistema): abre o serviço de impressão do Android, que
     *    é quem enxerga USB (OTG), Bluetooth e Wi-Fi Direct através dos plugins
     *    do fabricante — inclusive "Salvar como PDF".
     *  • Copiar para pasta: leva o PDF para uma pasta simples, para pendrive ou
     *    para a impressora ler direto.
     */
    /**
     * Deixa escolher QUAIS páginas imprimir e segue para o modo de impressão.
     *
     * A ficha completa pode ter Time-Out, várias páginas de fotos, rubricário e
     * as páginas do protocolo. Repor uma folha que rasgou obrigava a reimprimir
     * tudo — papel e tempo de acelerador gastos por falta de alternativa.
     *
     * Um PDF de UMA página não pergunta nada: escolher entre "tudo" e "a
     * página 1" é a mesma coisa, e a pergunta só custaria um toque.
     *
     * A extração RASTERIZA a página (ver PdfPaginas). Por isso ela não é o
     * caminho padrão: quem imprime a ficha inteira continua imprimindo o
     * vetor original, sem passar por aqui.
     */
    protected fun escolherPaginasEImprimir(pdf: java.io.File) {
        val total = com.radioterapia.ai.pdf.PdfPaginas.contar(pdf)
        if (total <= 1) { escolherModoImpressao(pdf); return }

        val rotulos = Array(total) { getString(R.string.print_page_n, it + 1) }
        val marcadas = BooleanArray(total)
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.print_pages_title, total))
            .setMultiChoiceItems(rotulos, marcadas) { _, i, v -> marcadas[i] = v }
            .setPositiveButton(R.string.print_pages_ok) { _, _ ->
                val escolhidas = (1..total).filter { marcadas[it - 1] }
                if (escolhidas.isEmpty()) {
                    android.widget.Toast.makeText(this, R.string.print_pages_none,
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CoroutineScope(Dispatchers.Main).launch {
                    val parcial = withContext(Dispatchers.IO) {
                        val saida = java.io.File(cacheDir,
                            "paginas_" + System.currentTimeMillis() + ".pdf")
                        com.radioterapia.ai.pdf.PdfPaginas.extrair(pdf, escolhidas, saida)
                    }
                    if (isFinishing || isDestroyed) return@launch
                    if (parcial == null) {
                        android.widget.Toast.makeText(this@BaseActivity,
                            R.string.print_pages_fail,
                            android.widget.Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    escolherModoImpressao(parcial)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
    }

    protected fun escolherModoImpressao(pdf: java.io.File) {
        val cfg = com.radioterapia.ai.AppConfig(this)
        val opcoes = arrayOf(
            getString(R.string.print_mode_ip),
            getString(R.string.print_mode_pendrive),
            getString(R.string.print_mode_folder))
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.print_mode_title)
            .setItems(opcoes) { _, i ->
                when (i) {
                    0 -> imprimirPorIp(pdf, cfg)
                    1 -> abrirDialogoPenDrive(listOf(pdf))
                    2 -> copiarParaPastaImpressao(pdf)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        // Testa a impressora de rede em background e esmaece a opção se offline.
        if (!cfg.temImpressora()) {
            dlg.listView?.getChildAt(0)?.let { it.isEnabled = false; it.alpha = 0.4f }
        } else {
            CoroutineScope(Dispatchers.Main).launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        com.radioterapia.ai.print.PrinterClient(cfg.impressoraIp)
                            .testarConexao(3_000).sucesso
                    } catch (_: Exception) { false }
                }
                if (!ok) dlg.listView?.getChildAt(0)?.let { it.isEnabled = false; it.alpha = 0.4f }
            }
        }
    }

    /**
     * Diálogo do PEN-DRIVE (cabo OTG). Mostra a animação em loop dos três
     * quadros — cabo solto, encostando, gravando — e acompanha ao vivo o estado
     * da conexão: o botão de gravar só habilita quando o Android confirma um
     * volume removível montado. Plugar ou remover o pen-drive com o diálogo
     * aberto atualiza a tela na hora, sem o usuário precisar reabrir nada.
     */
    protected fun abrirDialogoPenDrive(
        pdfs: List<java.io.File>,
        modo: ModoPenDrive = ModoPenDrive.PACIENTE_ATUAL,
        nomes: List<String> = emptyList(),
        itens: List<com.radioterapia.ai.pdf.PdfBuilder.ItemLote> = emptyList()
    ) {
        pdfsParaPenDrive = pdfs
        nomesParaPenDrive = nomes
        itensParaPenDrive = itens
        modoPenDrive = modo
        val view = layoutInflater.inflate(R.layout.dialog_pendrive, null)
        viewPenDrive = view

        // Animação em loop: precisa ser iniciada depois que a view é anexada.
        val img = view.findViewById<android.widget.ImageView>(R.id.imgOtgAnim)
        img.post {
            (img.drawable as? android.graphics.drawable.AnimationDrawable)?.start()
        }

        view.findViewById<Button>(R.id.btnOtgEscolherPasta).setOnClickListener {
            try { escolherPastaPenDrive.launch(penDrive.intentEscolherPasta()) }
            catch (e: Exception) {
                android.widget.Toast.makeText(this, R.string.pd_escolher_pasta,
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<Button>(R.id.btnOtgGravar).setOnClickListener { gravarNoPenDrive() }

        dlgPenDrive = AlertDialog.Builder(this)
            .setTitle(R.string.pd_titulo)
            .setView(view)
            .setNegativeButton(R.string.cancel) { _, _ -> fecharDialogoPenDrive() }
            .setOnDismissListener { fecharDialogoPenDrive() }
            .create()
        dlgPenDrive?.show()

        // Observa plugar/desplugar enquanto o diálogo estiver aberto.
        receiverPenDrive = penDrive.registrarObservador { runOnUiThread { atualizarEstadoPenDrive() } }
        atualizarEstadoPenDrive()
    }

    private fun fecharDialogoPenDrive() {
        penDrive.desregistrar(receiverPenDrive)
        receiverPenDrive = null
        (viewPenDrive?.findViewById<android.widget.ImageView>(R.id.imgOtgAnim)
            ?.drawable as? android.graphics.drawable.AnimationDrawable)?.stop()
        viewPenDrive = null
        dlgPenDrive = null
    }

    /** Reflete o estado real da conexão nos textos e botões do diálogo. */
    private fun atualizarEstadoPenDrive() {
        val view = viewPenDrive ?: return
        val txtEstado = view.findViewById<TextView>(R.id.txtOtgEstado)
        val txtDica = view.findViewById<TextView>(R.id.txtOtgDica)
        val txtPasta = view.findViewById<TextView>(R.id.txtOtgPasta)
        val btnPasta = view.findViewById<Button>(R.id.btnOtgEscolherPasta)
        val btnGravar = view.findViewById<Button>(R.id.btnOtgGravar)

        when (penDrive.estado()) {
            com.radioterapia.ai.usb.PenDriveHelper.Estado.SEM_CABO -> {
                txtEstado.setText(R.string.pd_sem_cabo)
                txtDica.setText(R.string.pd_sem_cabo_dica)
                btnPasta.visibility = View.GONE
                txtPasta.visibility = View.GONE
                btnGravar.isEnabled = false; btnGravar.alpha = 0.4f
            }
            com.radioterapia.ai.usb.PenDriveHelper.Estado.MONTANDO -> {
                txtEstado.setText(R.string.pd_montando)
                txtDica.setText(R.string.pd_montando_dica)
                btnPasta.visibility = View.GONE
                txtPasta.visibility = View.GONE
                btnGravar.isEnabled = false; btnGravar.alpha = 0.4f
            }
            com.radioterapia.ai.usb.PenDriveHelper.Estado.PASTA_PENDENTE -> {
                txtEstado.setText(R.string.pd_pasta_pendente)
                txtDica.setText(R.string.pd_pasta_pendente_dica)
                btnPasta.visibility = View.VISIBLE
                btnPasta.setText(R.string.pd_escolher_pasta)
                txtPasta.visibility = View.GONE
                btnGravar.isEnabled = false; btnGravar.alpha = 0.4f
            }
            com.radioterapia.ai.usb.PenDriveHelper.Estado.PRONTO -> {
                txtEstado.setText(R.string.pd_pronto)
                txtDica.setText(R.string.pd_pronto_dica_sessao)
                btnPasta.visibility = View.VISIBLE
                btnPasta.setText(R.string.pd_trocar_pasta)
                txtPasta.visibility = View.VISIBLE
                txtPasta.text = getString(R.string.pd_pasta_atual,
                    com.radioterapia.ai.usb.PenDriveHelper.SUBPASTA + "/" +
                    when (modoPenDrive) {
                        ModoPenDrive.PACIENTE_ATUAL ->
                            com.radioterapia.ai.usb.PenDriveHelper.Pasta.PACIENTE_ATUAL.dir
                        ModoPenDrive.LOTE ->
                            com.radioterapia.ai.usb.PenDriveHelper.Pasta.LOTE_INDIVIDUAIS.dir +
                            " + " + com.radioterapia.ai.usb.PenDriveHelper.Pasta.LOTE_AGRUPADO.dir
                    })
                btnGravar.isEnabled = true; btnGravar.alpha = 1f
            }
        }
    }

    /**
     * Grava direto, sem perguntar nada. A organização em pastas é automática:
     * ao escrever numa pasta da sessão, o que estava nela vai para 9_ANTIGOS
     * com carimbo. O operador nunca perde o histórico e nunca responde diálogo.
     */
    private fun gravarNoPenDrive() {
        val pdfs = pdfsParaPenDrive
        val itens = itensParaPenDrive
        if (pdfs.isEmpty() && itens.isEmpty()) return
        val btn = viewPenDrive?.findViewById<Button>(R.id.btnOtgGravar)
        btn?.isEnabled = false
        btn?.setText(R.string.pd_gravando)
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) {
                when (modoPenDrive) {
                    ModoPenDrive.PACIENTE_ATUAL ->
                        penDrive.gravarPacienteAtual(pdfs.first())
                    // No lote, os DOIS formatos são gravados de uma vez: quem
                    // opera escolhe na impressora se abre um arquivo só ou
                    // paciente a paciente, sem precisar voltar ao tablet.
                    ModoPenDrive.LOTE -> penDrive.gravarLote(
                        pdfs, nomesParaPenDrive, itens)
                }
            }
            btn?.setText(R.string.pd_gravar)
            btn?.isEnabled = true
            android.widget.Toast.makeText(this@BaseActivity, res.mensagem,
                android.widget.Toast.LENGTH_LONG).show()
            if (res.sucesso) dlgPenDrive?.dismiss()
        }
    }

    /**
     * Seletor de modo para o LOTE. Mesmas quatro opções da ficha avulsa, com
     * uma diferença: para rede, serviço do Android e pasta, o lote é remontado
     * num único PDF e vai como UM trabalho de impressão — as páginas físicas
     * saem iguais e o operador não espera fila. Só no pen-drive a separação em
     * pastas (individuais + agrupado) faz sentido, e lá os dois são gravados.
     */
    protected fun escolherModoImpressaoLote(
        pdfs: List<java.io.File>,
        nomes: List<String>,
        itens: List<com.radioterapia.ai.pdf.PdfBuilder.ItemLote>
    ) {
        val cfg = com.radioterapia.ai.AppConfig(this)
        val opcoes = arrayOf(
            getString(R.string.print_mode_ip),
            getString(R.string.print_mode_pendrive),
            getString(R.string.print_mode_folder))
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.print_mode_title)
            .setItems(opcoes) { _, i ->
                when (i) {
                    1 -> abrirDialogoPenDrive(pdfs, ModoPenDrive.LOTE, nomes, itens)
                    else -> comLoteAgrupado(itens, pdfs) { unico ->
                        if (i == 0) imprimirPorIp(unico, cfg)
                        else copiarParaPastaImpressao(unico)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        if (!cfg.temImpressora()) {
            dlg.listView?.getChildAt(0)?.let { it.isEnabled = false; it.alpha = 0.4f }
        } else {
            CoroutineScope(Dispatchers.Main).launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        com.radioterapia.ai.print.PrinterClient(cfg.impressoraIp)
                            .testarConexao(3_000).sucesso
                    } catch (_: Exception) { false }
                }
                if (!ok) dlg.listView?.getChildAt(0)?.let { it.isEnabled = false; it.alpha = 0.4f }
            }
        }
    }

    /**
     * Monta o PDF único do lote em cache e entrega ao destino. Se a remontagem
     * falhar (dados incompletos), cai para o primeiro PDF disponível em vez de
     * deixar o usuário sem nada.
     */
    private fun comLoteAgrupado(
        itens: List<com.radioterapia.ai.pdf.PdfBuilder.ItemLote>,
        alternativa: List<java.io.File>,
        destino: (java.io.File) -> Unit
    ) {
        if (itens.isEmpty()) {
            alternativa.firstOrNull()?.let(destino)
            return
        }
        val prog = AlertDialog.Builder(this)
            .setMessage(getString(R.string.lote_preparando, itens.size))
            .setCancelable(false).create()
        prog.show()
        CoroutineScope(Dispatchers.Main).launch {
            val unico = withContext(Dispatchers.IO) {
                try {
                    val carimbo = java.text.SimpleDateFormat("yyyyMMdd_HHmm",
                        java.util.Locale.US).format(java.util.Date())
                    val alvo = java.io.File(cacheDir, "LOTE_$carimbo.pdf")
                    com.radioterapia.ai.pdf.PdfBuilder.gerarLoteAgrupado(
                        this@BaseActivity, itens, alvo)
                } catch (_: Exception) { null }
            }
            prog.dismiss()
            val arquivo = unico ?: alternativa.firstOrNull()
            if (arquivo == null) {
                android.widget.Toast.makeText(this@BaseActivity, R.string.lote_sem_pdf,
                    android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            destino(arquivo)
        }
    }

    private fun imprimirPorIp(pdf: java.io.File, cfg: com.radioterapia.ai.AppConfig) {
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    // FICHA COM VERSO MANDA NO MODO. Se o protocolo trouxe uma
                    // folha frente-e-verso, imprimir em simplex separaria as duas
                    // faces em folhas diferentes — e o impresso que a clinica
                    // desenhou para ser virado deixaria de funcionar. Nos demais
                    // casos vale a configuracao da impressora, como sempre.
                    val modo =
                        if (com.radioterapia.ai.pdf.PdfBuilder.temFrenteVerso(pdf)) "long"
                        else cfg.printerDuplexMode
                    com.radioterapia.ai.print.PrinterClient(cfg.impressoraIp)
                        .imprimirPdf(pdf, modo)
                } catch (e: Exception) { null }
            }
            android.widget.Toast.makeText(this@BaseActivity,
                if (res?.sucesso == true) getString(R.string.print_sent)
                else (res?.mensagem ?: getString(R.string.printer_offline)),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Copia o PDF para uma pasta simples (pendrive / leitura direta). */
    private fun copiarParaPastaImpressao(pdf: java.io.File) {
        CoroutineScope(Dispatchers.Main).launch {
            val destino = withContext(Dispatchers.IO) {
                try {
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOCUMENTS), "PhotoID_Imprimir")
                    // Limpa a pasta a cada novo pedido: a impressora encontra
                    // apenas o documento atual no teclado dela.
                    if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()
                    val alvo = java.io.File(dir, pdf.name)
                    pdf.copyTo(alvo, overwrite = true)
                    alvo
                } catch (e: Exception) { null }
            }
            android.widget.Toast.makeText(this@BaseActivity,
                if (destino != null) getString(R.string.print_mode_folder_ok, destino.parent)
                else getString(R.string.err_save, ""),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    protected fun setToolbarTitle(texto: String) {
        tituloView?.text = texto
    }

    /**
     * Rotação atual do display, para alimentar o targetRotation do CameraX.
     *
     * Existe aqui porque `Activity.getDisplay()` só chegou na API 30 e o app
     * declara minSdk 24: em tablet Android 7–10 a chamada direta lança
     * NoSuchMethodError — que é Error, NÃO Exception, então um
     * `try { } catch (_: Exception) { }` em volta não protege nada e o app
     * fecha na hora do disparo. Já aconteceu nas duas telas de câmera.
     *
     * Ponto único: quem precisa de rotação chama esta função, nunca `display`.
     */
    protected fun rotacaoAtualDoDisplay(): Int = try {
        if (android.os.Build.VERSION.SDK_INT >= 30)
            display?.rotation ?: android.view.Surface.ROTATION_0
        else
            @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
    } catch (_: Throwable) {
        android.view.Surface.ROTATION_0
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
