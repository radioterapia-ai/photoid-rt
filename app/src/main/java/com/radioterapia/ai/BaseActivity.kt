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
import android.widget.Toast
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
 *    botão de engrenagem à direita (apenas na Home).
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
    /** O ponto vermelho sobre a engrenagem. Existe so nas telas que mostram a
     *  engrenagem, ou seja, na Home. */
    private var pontoNovidade: View? = null

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
            // Spacer da largura dos DOIS botoes da direita (sincronizar +
            // engrenagem), senao o titulo sai do meio: ele ocupa o que sobra, e
            // o que sobra deixou de ser simetrico quando o segundo botao entrou.
            toolbar.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56 + 56), dp(48))
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
            toolbar.addView(montarBotaoSincronizar())

            // A engrenagem passa a morar dentro de um FrameLayout para que o
            // ponto de novidade possa ficar SOBRE ela. Antes era um ImageButton
            // solto num LinearLayout, que nao empilha filhos.
            val molduraGear = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            val btnGear = ImageButton(this).apply {
                setImageResource(R.drawable.ic_settings_gear)
                background = null
                contentDescription = getString(R.string.settings)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    startActivity(android.content.Intent(this@BaseActivity,
                        SettingsActivity::class.java))
                }
            }
            molduraGear.addView(btnGear)

            // O ponto NAO e clicavel e nao rouba o toque da engrenagem: quem
            // recebe o clique e o botao inteiro, inclusive sob o ponto.
            pontoNovidade = View(this).apply {
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@BaseActivity, R.drawable.dot_novidade)
                layoutParams = FrameLayout.LayoutParams(dp(10), dp(10)).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    topMargin = dp(9)
                    marginEnd = dp(9)
                }
                isClickable = false
                visibility = View.GONE
                contentDescription = getString(R.string.update_dialog_title)
            }
            molduraGear.addView(pontoNovidade)
            toolbar.addView(molduraGear)
            pintarPontoNovidade()
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

    // ===================== SINCRONIZAR, na barra de titulo =====================

    private var btnSync: ImageButton? = null
    private var pontoFalhaSync: View? = null
    private var sincronizando = false

    /**
     * O botao de sincronizar da Home, ao lado da engrenagem.
     *
     * TRES ESTADOS, E NENHUM DELES DEPENDE DE ANIMACAO PARA EXISTIR:
     *
     *  - parado: icone branco, como a engrenagem ao lado;
     *  - sincronizando: icone em brand_primary E girando;
     *  - falhou: ponto vermelho no canto superior direito, que so sai na
     *    proxima sincronizacao bem-sucedida.
     *
     * A COR MUDA ALEM DA ROTACAO de proposito. Este projeto exige que estado
     * nunca dependa de animacao, porque a escala de animacao do sistema pode
     * estar em zero — e ai um icone que so girasse ficaria parado, sem dizer
     * nada. Com a cor, o estado sobrevive; a rotacao e o reforco.
     */
    private fun montarBotaoSincronizar(): View {
        val caixa = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }

        val btn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_sync)
            background = null
            contentDescription = getString(R.string.sync_now)
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { sincronizarPelaBarra() }
        }
        btnSync = btn
        caixa.addView(btn)

        // O ponto de falha: canto superior direito, sobre o icone.
        val ponto = View(this).apply {
            setBackgroundResource(R.drawable.badge_dot)
            layoutParams = FrameLayout.LayoutParams(dp(11), dp(11)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(9)
                marginEnd = dp(9)
            }
            visibility = View.GONE
        }
        pontoFalhaSync = ponto
        caixa.addView(ponto)

        return caixa
    }

    private fun pintarEstadoSync() {
        val b = btnSync ?: return
        val cor = if (sincronizando)
            androidx.core.content.ContextCompat.getColor(this, R.color.brand_primary)
        else Color.WHITE
        b.setColorFilter(cor)

        if (sincronizando && com.radioterapia.ai.ui.anim.Movimento.ligado(this)) {
            if (b.animation == null) {
                // ANTI-HORARIO, seguindo as setas do proprio icone. Girando
                // para o outro lado, o desenho e o movimento contam historias
                // opostas e o olho le como defeito, nao como progresso.
                b.startAnimation(android.view.animation.RotateAnimation(
                    0f, -360f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 1000L
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                })
            }
        } else {
            b.clearAnimation()
        }
    }

    /**
     * Toque no botao: sincroniza e gira ate terminar.
     *
     * NAO EMPILHA. Tocar de novo enquanto roda nao dispara uma segunda
     * varredura — duas varreduras concorrentes disputariam o indice de
     * enviados, e o que uma marcasse a outra poderia reenviar.
     *
     * O orcamento de 45 segundos e o mesmo do "Sincronizar agora" das
     * Configuracoes, e pela mesma razao: esta varredura esta presa a uma tela
     * aberta e sem botao de cancelar. O que nao couber continua pela fila do
     * WorkManager.
     */
    private fun sincronizarPelaBarra() {
        if (sincronizando) return

        val cfg = com.radioterapia.ai.sync.SyncConfig(this)
        if (!cfg.ativo) {
            Toast.makeText(this, R.string.sync_master_hint, Toast.LENGTH_LONG).show()
            return
        }

        sincronizando = true
        pontoFalhaSync?.visibility = View.GONE
        pintarEstadoSync()

        CoroutineScope(Dispatchers.Main).launch {
            val resumos = try {
                withContext(Dispatchers.IO) {
                    com.radioterapia.ai.sync.MotorSync(this@BaseActivity)
                        .sincronizarTudo(limiteMs = 45_000L)
                }
            } catch (_: Exception) { emptyList() }

            // Outra varredura ficou com a vez durante todo o prazo: nada foi
            // enviado por este toque, e isso não é falha. Mas a que está
            // rodando fez a lista dela ao começar e não vê o que foi gravado
            // depois. O trabalho posto na fila aqui espera a vez, lista de
            // novo e leva o que chegou depois logo em seguida. Enfileira antes
            // de conferir a tela: quem saiu dela também tem fotos novas.
            val emAndamento = resumos.any { it.emAndamento }
            if (emAndamento) com.radioterapia.ai.sync.SyncWorker.agora(applicationContext)

            sincronizando = false
            pintarEstadoSync()
            if (isFinishing || isDestroyed) return@launch

            if (emAndamento) {
                Toast.makeText(this@BaseActivity, R.string.sync_em_andamento,
                    Toast.LENGTH_LONG).show()
                return@launch
            }

            val env = resumos.sumOf { it.enviados }
            val ja = resumos.sumOf { it.jaEstavam }
            val pend = resumos.sumOf { it.pendentes }
            val falhou = resumos.isEmpty() || resumos.any { it.houveFalha }

            pontoFalhaSync?.visibility = if (falhou) View.VISIBLE else View.GONE
            Toast.makeText(this@BaseActivity,
                getString(R.string.sync_result, env, ja, pend),
                Toast.LENGTH_LONG).show()

            // O QUE SOBROU VAI PARA A FILA, como no botao das Configuracoes.
            if (pend > 0) com.radioterapia.ai.sync.SyncWorker.agora(this@BaseActivity)

            // CARONA NA SINCRONIA. Uma varredura que nao falhou e a prova de que
            // existe rede agora — e e a hora mais barata de perguntar por versao
            // nova, porque nao acrescenta tentativa nenhuma num tablet que
            // vive em intranete. Se falhou, nao pergunta: nao ha rede.
            if (!falhou) {
                withContext(Dispatchers.IO) {
                    com.radioterapia.ai.update.GerenciadorAtualizacao
                        .checarEGravar(this@BaseActivity)
                }
                pintarPontoNovidade()
            }
        }
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
    /** Nome da cópia da ficha avulsa no pen-drive (ver [nomeDeEntrega]); nulo = nome do arquivo. */
    private var nomeEntregaPenDrive: String? = null
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
     * Seletor de MODO de impressão. O botão nunca fica apagado: o que
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
     *
     * @param nomeEntrega nome das cópias que saem para o pen-drive e para a
     *   pasta de impressão (ver [nomeDeEntrega]). As páginas avulsas levam o
     *   nome da ficha de onde saíram: quem as pega precisa saber de quem são.
     */
    protected fun escolherPaginasEImprimir(pdf: java.io.File, nomeEntrega: String? = null) {
        val total = com.radioterapia.ai.pdf.PdfPaginas.contar(pdf)
        if (total <= 1) { escolherModoImpressao(pdf, nomeEntrega); return }

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
                    escolherModoImpressao(parcial, nomeEntrega)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
    }

    /**
     * @param nomeEntrega nome das cópias que saem para o pen-drive e para a
     *   pasta de impressão (ver [nomeDeEntrega]); nulo, saem com o nome do
     *   arquivo.
     */
    protected fun escolherModoImpressao(pdf: java.io.File, nomeEntrega: String? = null) {
        val cfg = com.radioterapia.ai.AppConfig(this)
        val opcoes = arrayOf(
            getString(R.string.print_mode_ip),
            getString(R.string.print_mode_pendrive),
            getString(R.string.print_mode_folder))
        /*
            QUEM DECIDE O CLIQUE E O ADAPTER, NAO A VIEW DA LINHA.

            Antes, a opcao de impressora offline era esmaecida com
            `dlg.listView?.getChildAt(0)?.let { it.isEnabled = false; it.alpha = 0.4f }`.
            Duas coisas erradas ali, e nenhuma delas aparece como erro:

            1. `AbsListView` despacha o clique pelo `adapter.isEnabled(position)`.
               O `isEnabled` da View filha nao participa da decisao, entao a
               linha ficava cinza E CONTINUAVA disparando imprimirPorIp: o app
               mostrava barra de progresso e "imprimia" mesmo com a
               impressora offline.
            2. `getChildAt(0)` e posicao VISUAL, nao posicao do adapter — e o
               ping de 3s podia voltar depois de o dialogo ter sido fechado,
               mexendo numa lista que nao esta mais na tela.

            Com um adapter proprio, `isEnabled(0)` gaveta o clique de verdade, o
            esmaecimento sai do mesmo lugar que a decisao (nao ha como um mudar
            sem o outro), e `isShowing` fecha a corrida.
         */
        val ipIndisponivel = java.util.concurrent.atomic.AtomicBoolean(!cfg.temImpressora())
        val adaptador = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, opcoes) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) =
                position != 0 || !ipIndisponivel.get()
            override fun getView(position: Int, convertView: View?,
                                 parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.alpha = if (isEnabled(position)) 1f else 0.4f
                return v
            }
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.print_mode_title)
            .setAdapter(adaptador) { _, i ->
                when (i) {
                    0 -> imprimirPorIp(pdf, cfg)
                    1 -> abrirDialogoPenDrive(listOf(pdf), nomeEntrega = nomeEntrega)
                    2 -> copiarParaPastaImpressao(pdf, nomeEntrega)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()

        if (cfg.temImpressora()) {
            CoroutineScope(Dispatchers.Main).launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        com.radioterapia.ai.print.PrinterClient(cfg.impressoraIp)
                            .testarConexao(3_000).sucesso
                    } catch (_: Exception) { false }
                }
                if (!ok && dlg.isShowing) {
                    ipIndisponivel.set(true)
                    adaptador.notifyDataSetChanged()
                }
            }
        }
    }

    /**
     * Diálogo do PEN-DRIVE (cabo OTG). Mostra a animação em loop dos três
     * quadros — cabo solto, encostando, gravando — e acompanha ao vivo o estado
     * da conexão: o botão de gravar só habilita quando o Android confirma um
     * volume removível montado. Plugar ou remover o pen-drive com o diálogo
     * aberto atualiza a tela na hora, sem o usuário precisar reabrir nada.
     *
     * @param nomeEntrega nome da cópia da ficha avulsa no pen-drive (ver
     *   [nomeDeEntrega]). No lote, o nome de cada ficha sai de [nomes].
     */
    protected fun abrirDialogoPenDrive(
        pdfs: List<java.io.File>,
        modo: ModoPenDrive = ModoPenDrive.PACIENTE_ATUAL,
        nomes: List<String> = emptyList(),
        itens: List<com.radioterapia.ai.pdf.PdfBuilder.ItemLote> = emptyList(),
        nomeEntrega: String? = null
    ) {
        pdfsParaPenDrive = pdfs
        nomesParaPenDrive = nomes
        itensParaPenDrive = itens
        nomeEntregaPenDrive = nomeEntrega
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
        val nomes = nomesParaPenDrive
        val nomeAvulsa = nomeEntregaPenDrive
        val modo = modoPenDrive
        if (pdfs.isEmpty() && itens.isEmpty()) return
        val btn = viewPenDrive?.findViewById<Button>(R.id.btnOtgGravar)
        btn?.isEnabled = false
        btn?.setText(R.string.pd_gravando)
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) {
                when (modo) {
                    ModoPenDrive.PACIENTE_ATUAL ->
                        penDrive.gravarPacienteAtual(pdfs.first(), nomeAvulsa)
                    // No lote, os DOIS formatos são gravados de uma vez: quem
                    // opera escolhe na impressora se abre um arquivo só ou
                    // paciente a paciente, sem precisar voltar ao tablet.
                    ModoPenDrive.LOTE -> penDrive.gravarLote(
                        pdfs, nomes, itens,
                        pdfs.mapIndexed { i, f ->
                            nomeDeEntregaDoGuardado(f.name, nomes.getOrNull(i))
                        })
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
        // GUARDA: o clique é decidido pelo adapter.isEnabled, como na ficha
        // avulsa. Esmaecer a View da linha (getChildAt) deixa a linha cinza e
        // ainda despachando o clique, e mandaria o lote a uma impressora que
        // não respondeu ao teste.
        val ipIndisponivel = java.util.concurrent.atomic.AtomicBoolean(!cfg.temImpressora())
        val adaptador = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, opcoes) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) =
                position != 0 || !ipIndisponivel.get()
            override fun getView(position: Int, convertView: View?,
                                 parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.alpha = if (isEnabled(position)) 1f else 0.4f
                return v
            }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.print_mode_title)
            .setAdapter(adaptador) { _, i ->
                when (i) {
                    1 -> abrirDialogoPenDrive(pdfs, ModoPenDrive.LOTE, nomes, itens)
                    0 -> comLoteAgrupado(itens, pdfs,
                        agrupado = { unico -> imprimirPorIp(unico, cfg) },
                        avulsas = { fichas -> imprimirVariasPorIp(fichas, cfg) })
                    // As fichas avulsas vão para a pasta com o nome de entrega,
                    // como no pen-drive: ali não há pasta de paciente em volta,
                    // e as iniciais sozinhas se repetem entre pacientes.
                    else -> comLoteAgrupado(itens, pdfs,
                        agrupado = { unico -> copiarParaPastaImpressao(unico) },
                        avulsas = { fichas ->
                            copiarFichasParaPastaImpressao(fichas.map { f ->
                                f to nomeDeEntrega(f, nomes.getOrNull(pdfs.indexOf(f)))
                            })
                        })
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        if (cfg.temImpressora()) {
            CoroutineScope(Dispatchers.Main).launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        com.radioterapia.ai.print.PrinterClient(cfg.impressoraIp)
                            .testarConexao(3_000).sucesso
                    } catch (_: Exception) { false }
                }
                if (!ok && dlg.isShowing) {
                    ipIndisponivel.set(true)
                    adaptador.notifyDataSetChanged()
                }
            }
        }
    }

    /**
     * Monta o PDF único do lote em cache e entrega a [agrupado]. Sem dados para
     * remontar, ou se a remontagem falhar (dados incompletos, memória), entrega
     * a [avulsas] as fichas guardadas de TODOS os pacientes do lote, na ordem.
     *
     * GUARDA: nunca só a primeira ficha. Mandar uma quando o lote tinha várias
     * deixava os outros pacientes de fora sem aviso, e quem opera a impressora
     * não tem como perceber que faltou alguém.
     */
    private fun comLoteAgrupado(
        itens: List<com.radioterapia.ai.pdf.PdfBuilder.ItemLote>,
        fichas: List<java.io.File>,
        agrupado: (java.io.File) -> Unit,
        avulsas: (List<java.io.File>) -> Unit
    ) {
        if (itens.isEmpty()) {
            entregarAvulsas(fichas, avulsas)
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
            if (unico != null) agrupado(unico) else entregarAvulsas(fichas, avulsas)
        }
    }

    private fun entregarAvulsas(fichas: List<java.io.File>,
                                avulsas: (List<java.io.File>) -> Unit) {
        if (fichas.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.lote_sem_pdf,
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        avulsas(fichas)
    }

    private fun imprimirPorIp(pdf: java.io.File, cfg: com.radioterapia.ai.AppConfig) {
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) { enviarPorIp(pdf, cfg) }
            android.widget.Toast.makeText(this@BaseActivity,
                if (res?.sucesso == true) getString(R.string.print_sent)
                else (res?.mensagem ?: getString(R.string.printer_offline)),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Fichas avulsas do lote pela impressora de rede: um trabalho por paciente,
     * um depois do outro, na ordem do lote. Para no primeiro que falhar e mostra
     * o motivo — seguir mandando para uma impressora que não responde só
     * somaria esperas, e o aviso diz a quem opera que o lote não saiu inteiro.
     */
    private fun imprimirVariasPorIp(pdfs: List<java.io.File>, cfg: com.radioterapia.ai.AppConfig) {
        if (pdfs.size == 1) { imprimirPorIp(pdfs[0], cfg); return }
        val semResposta = getString(R.string.printer_offline)
        CoroutineScope(Dispatchers.Main).launch {
            val falha = withContext(Dispatchers.IO) {
                var motivo: String? = null
                for (pdf in pdfs) {
                    val res = enviarPorIp(pdf, cfg)
                    if (res?.sucesso != true) {
                        motivo = res?.mensagem ?: semResposta
                        break
                    }
                }
                motivo
            }
            android.widget.Toast.makeText(this@BaseActivity,
                falha ?: getString(R.string.print_sent),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Manda um PDF à impressora de rede. Bloqueia: chamar em Dispatchers.IO. */
    private fun enviarPorIp(pdf: java.io.File, cfg: com.radioterapia.ai.AppConfig) = try {
        // FICHA COM VERSO MANDA NO MODO. Se o protocolo trouxe uma
        // folha frente-e-verso, imprimir em simplex separaria as duas
        // faces em folhas diferentes — e o impresso que a clinica
        // desenhou para ser virado deixaria de funcionar. Nos demais
        // casos vale a configuracao da impressora, como sempre.
        val modo =
            if (com.radioterapia.ai.pdf.PdfBuilder.temFrenteVerso(pdf, this@BaseActivity)) "long"
            else cfg.printerDuplexMode
        com.radioterapia.ai.print.PrinterClient(cfg.impressoraIp)
            .imprimirPdf(pdf, modo)
    } catch (e: Exception) { null }

    /**
     * Copia o PDF para uma pasta simples (pendrive / leitura direta).
     *
     * @param nomeEntrega nome da cópia (ver [nomeDeEntrega]); nulo, sai com o
     *   nome do arquivo.
     */
    private fun copiarParaPastaImpressao(pdf: java.io.File, nomeEntrega: String? = null) =
        copiarFichasParaPastaImpressao(
            listOf(pdf to (nomeEntrega?.takeIf { it.isNotBlank() } ?: pdf.name)))

    /**
     * Copia cada PDF para a pasta de impressão com o nome dado ao lado dele.
     *
     * A pasta é esvaziada uma vez, antes da primeira cópia: a impressora
     * encontra só o pedido atual no teclado dela, e um lote fica inteiro. Dois
     * nomes iguais no mesmo pedido ganham `_2`, `_3` antes da extensão, sem
     * diferenciar maiúsculas — a pasta pode estar num sistema de arquivos que
     * não diferencia, e a segunda cópia apagaria a primeira.
     */
    private fun copiarFichasParaPastaImpressao(copias: List<Pair<java.io.File, String>>) {
        CoroutineScope(Dispatchers.Main).launch {
            val pasta = withContext(Dispatchers.IO) {
                try {
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOCUMENTS), "PhotoID_Imprimir")
                    if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()
                    val usados = HashSet<String>()
                    for ((pdf, nome) in copias) {
                        pdf.copyTo(java.io.File(dir, nomeSemRepetir(nome, usados)), overwrite = true)
                    }
                    dir
                } catch (e: Exception) { null }
            }
            android.widget.Toast.makeText(this@BaseActivity,
                if (pasta != null) getString(R.string.print_mode_folder_ok, pasta.path)
                else getString(R.string.err_save, ""),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun nomeSemRepetir(nome: String, usados: MutableSet<String>): String {
        val base = nome.substringBeforeLast('.')
        val ext = nome.substringAfterLast('.', "")
        var candidato = nome
        var n = 2
        while (!usados.add(candidato.lowercase(java.util.Locale.ROOT))) {
            candidato = if (ext.isEmpty()) "${base}_$n" else "${base}_$n.$ext"
            n++
        }
        return candidato
    }

    // ---------- Cópias de entrega ----------

    /**
     * Nome da cópia que sai da pasta do paciente para alguém pegar: pen-drive,
     * pasta de impressão, compartilhar.
     *
     * Na pasta do paciente o arquivo leva só as iniciais, porque a pasta já diz
     * de quem é. A cópia entregue anda sozinha — na bandeja da impressora, no
     * pen-drive, no anexo — e leva o nome completo. Devolve o nome do próprio
     * arquivo quando a troca não se aplica (ver [nomeDeEntregaDoGuardado]).
     */
    protected fun nomeDeEntrega(arquivo: java.io.File, nomePaciente: String?): String =
        nomeDeEntregaDoGuardado(arquivo.name, nomePaciente) ?: arquivo.name

    /**
     * Abre o "compartilhar" do Android com uma cópia de entrega do arquivo.
     *
     * A cópia vai para `cache/entrega/` com o nome de entrega, porque o app que
     * recebe o anexo usa o nome do arquivo compartilhado. Cópias com mais de
     * uma hora são apagadas a cada chamada: o compartilhamento anterior já foi
     * lido, e cópia com nome de paciente não fica esquecida no cache. Se a cópia
     * falhar, compartilha o arquivo original — o anexo sai com as iniciais, mas
     * sai.
     *
     * @param nomeEntrega nome da cópia; nulo ou igual ao do arquivo, compartilha
     *   o próprio arquivo, sem cópia.
     */
    protected fun compartilharComoEntrega(arquivo: java.io.File, nomeEntrega: String?,
                                          mime: String = "application/pdf") {
        if (!arquivo.exists()) {
            Toast.makeText(this, R.string.pdf_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val enviar = withContext(Dispatchers.IO) {
                if (nomeEntrega.isNullOrBlank() || nomeEntrega == arquivo.name) arquivo
                else try {
                    val dir = java.io.File(cacheDir, "entrega").apply { mkdirs() }
                    val limite = System.currentTimeMillis() - 60 * 60 * 1000L
                    dir.listFiles()?.forEach { if (it.lastModified() < limite) it.delete() }
                    val copia = java.io.File(dir, nomeEntrega)
                    arquivo.copyTo(copia, overwrite = true)
                    copia
                } catch (_: Exception) { arquivo }
            }
            if (isFinishing || isDestroyed) return@launch
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@BaseActivity, "$packageName.fileprovider", enviar)
                val envio = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(android.content.Intent.createChooser(envio, getString(R.string.share_pdf)))
            } catch (e: Exception) {
                Toast.makeText(this@BaseActivity,
                    getString(R.string.share_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
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
    /**
     * Acende ou apaga o ponto conforme haja versao nova pendente.
     *
     * Chamado na montagem da barra E no onResume, porque a checagem roda em
     * segundo plano: quando ela termina, a barra ja esta desenhada, e sem o
     * onResume o ponto so apareceria na proxima vez que o app abrisse.
     */
    protected fun pintarPontoNovidade() {
        val v = pontoNovidade ?: return
        val mostrar = try {
            com.radioterapia.ai.update.GerenciadorAtualizacao.avisoPendente(this)
        } catch (_: Throwable) {
            false
        }
        v.visibility = if (mostrar) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        pintarPontoNovidade()
    }

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

    companion object {
        /**
         * Nome de entrega de um arquivo guardado na pasta do paciente: as
         * iniciais dão lugar ao nome completo, e todo o resto fica. A regra, e
         * os casos em que ela devolve `null` para quem chama manter o nome do
         * arquivo, moram em `NomeArquivo.entregaDoGuardado`, testável na JVM.
         */
        fun nomeDeEntregaDoGuardado(nomeArquivo: String, nomePaciente: String?): String? =
            com.radioterapia.ai.util.NomeArquivo.entregaDoGuardado(nomeArquivo, nomePaciente)
    }
}
