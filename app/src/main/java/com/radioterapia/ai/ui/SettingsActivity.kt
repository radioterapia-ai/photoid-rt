package com.radioterapia.ai.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.DestinoSmb
import com.radioterapia.ai.R
import com.radioterapia.ai.branding.LogoManager
import com.radioterapia.ai.i18n.LocaleManager
import com.radioterapia.ai.patient.PatientCache
import com.radioterapia.ai.print.PrinterClient
import com.radioterapia.ai.protocolo.ProtocoloStore
import com.radioterapia.ai.security.CredentialStore
import com.radioterapia.ai.session.SessionManager
import com.radioterapia.ai.update.AtualizacaoRemota
import com.radioterapia.ai.update.BackupPreAtualizacao
import com.radioterapia.ai.update.GerenciadorAtualizacao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela de configurações organizada em 16 grupos colapsáveis (accordion).
 *
 * Persistência:
 *  - Credenciais e config principal: AppConfig (SharedPreferences)
 *  - Senha: CredentialStore (EncryptedSharedPreferences)
 *  - Idioma: LocaleManager (SharedPreferences próprio)
 *  - Logo: LogoManager (arquivo em filesDir)
 *
 * Botão SALVAR no rodapé persiste tudo de uma vez.
 */
class SettingsActivity : com.radioterapia.ai.BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.settings)

    private lateinit var config: AppConfig
    private lateinit var credentials: CredentialStore
    private lateinit var sessionManager: SessionManager
    private lateinit var patientCache: PatientCache
    private lateinit var logoManager: LogoManager
    // csvMapping saiu com o bind dos extra: esta tela não configura mais o CSV.
    // CsvMapping segue vivo e é lido por CsvSyncManager. Ver salvarTudo().

    private lateinit var groupsContainer: LinearLayout

    // Bind dos campos por grupo
    // Identidade da clínica
    private var imgLogoPreview: ImageView? = null
    private var edtCompanyName: EditText? = null

    // SMB: os sete campos do grupo de rede foram removidos junto com o bind.
    // Ver a nota em salvarTudo().
    private var txtSmbTestResult: TextView? = null

    // Backups
    private val backupBindings = mutableListOf<BackupBinding>()

    // CSV: os seis campos dos identificadores extra saíram junto com o bind.
    // Ver a nota em salvarTudo().
    private var txtCsvSyncStatus: TextView? = null
    private var txtCsvLastSync: TextView? = null

    // Impressora
    private var edtPrinterIp: EditText? = null
    private var edtPrinterName: EditText? = null
    private var txtPrinterTestResult: TextView? = null

    // PDF
    private var rgPdfOrientation: android.widget.RadioGroup? = null
    private var edtEtiquetaLargura: EditText? = null
    private var edtEtiquetaAltura: EditText? = null
    private var edtPdfMargem: EditText? = null
    private var txtMargemHint: TextView? = null
    private var layoutEtiquetaTamanho: android.view.View? = null

    // Idioma
    private var chkLangAuto: CheckBox? = null
    private var spinnerLanguage: Spinner? = null
    private var spinnerFormatoData: Spinner? = null

    // Pasta local (edtLocalFolder saiu junto com o bind; ver salvarTudo())
    private var txtPastaFotos: TextView? = null
    private var txtPastaCsv: TextView? = null
    private var btnEscolherPastaFotos: Button? = null
    private var btnEscolherPastaCsv: Button? = null

    // Câmera (chkShowGrid saiu junto com o bind; ver salvarTudo())

    // Status conexão
    private var txtConnWifi: TextView? = null
    private var txtConnLastSync: TextView? = null
    private var txtConnPending: TextView? = null

    // Cache
    private var txtCachePatients: TextView? = null
    private var txtDraftStatus: TextView? = null
    private var protTotal: TextView? = null
    private var rubSpBloco: Spinner? = null

    /**
     * Equipe selecionada. TUDO no grupo do rubricário se refere a ela: a lista
     * de pessoas, o botão de adicionar, o PDF avulso e a exportação do bloco.
     */
    private var rubBlocoAtual: String = com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO
    private var protLista: android.widget.LinearLayout? = null

    private val idiomasMap = com.radioterapia.ai.i18n.LocaleManager.supportedLanguages
        .map { it to com.radioterapia.ai.i18n.LocaleManager.nomeDoIdioma(it) }

    private lateinit var seletorImagemLauncher: ActivityResultLauncher<Intent>
    private lateinit var cropLogoLauncher: ActivityResultLauncher<Intent>
    private lateinit var seletorPastaFotosLauncher: ActivityResultLauncher<Intent>
    private lateinit var seletorPastaCsvLauncher: ActivityResultLauncher<Intent>
    private var txtDbStatus: android.widget.TextView? = null
    private var txtBackupFolder: android.widget.TextView? = null
    private var txtBackupAviso: android.widget.TextView? = null
    private lateinit var seletorPastaBaseLauncher: ActivityResultLauncher<Intent>
    private var rgRemovePeriodo: android.widget.RadioGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = getString(R.string.settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        config = AppConfig(this)
        credentials = CredentialStore(this)
        sessionManager = SessionManager(this)
        patientCache = PatientCache(this)
        logoManager = LogoManager(this)

        groupsContainer = findViewById(R.id.groupsContainer)

        configurarSeletorImagem()
        configurarSeletoresPasta()
        montarGrupos()
        carregarTudo()

        // O convite vem DEPOIS de carregarTudo, senao o dialogo apareceria sobre
        // uma tela ainda em branco. Ele so aparece se a checagem de fundo achou
        // versao nova; sem internet nao ha nada gravado e nada e mostrado.
        oferecerAtualizacaoSeHouver()

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            salvarTudo()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            if (idiomaMudou) {
                // Relança o app pra aplicar o novo idioma em todas as Activities
                val it = packageManager.getLaunchIntentForPackage(packageName)
                it?.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(it)
                Runtime.getRuntime().exit(0)
            } else {
                finish()
            }
        }
    }

    /** Sinaliza se o usuário trocou o idioma; força relançamento ao salvar. */
    private var idiomaMudou = false

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ============= MONTAGEM DOS GRUPOS =============

    private fun montarGrupos() {
        // Aba única: identidade + equipe/tratamentos (eram duas).
        // Aba única: identidade + equipe/tratamentos (eram duas abas).
        adicionarGrupo("🏥", R.string.group_clinic_identity, R.layout.group_identidade_completa) {

            imgLogoPreview = it.findViewById(R.id.imgLogoPreview)
            edtCompanyName = it.findViewById(R.id.edtCompanyName)
            it.findViewById<Button>(R.id.btnSelectLogo).setOnClickListener { abrirSeletorImagem() }
            it.findViewById<Button>(R.id.btnRemoveLogo).setOnClickListener { confirmarRemoverLogo() }


            val edtSit = it.findViewById<android.widget.EditText>(R.id.edtSitiosLista)
            edtSit.setText(config.sitiosLista)
            edtSit.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    config.sitiosLista = s?.toString() ?: ""
                }
            })
            val edtEq = it.findViewById<android.widget.EditText>(R.id.edtEquipamentos)
            edtEq.setText(config.equipamentos)
            edtEq.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    config.equipamentos = s?.toString() ?: ""
                }
            })
            val edt = it.findViewById<android.widget.EditText>(R.id.edtTimeoutMedicos)
            edt.setText(config.timeoutMedicos)
            edt.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    config.timeoutMedicos = s?.toString() ?: ""
                }
            })
        }

        adicionarGrupo("🗄️", R.string.group_database, R.layout.group_database) {
            txtDbStatus = it.findViewById(R.id.txtDbStatus)
            txtBackupFolder = it.findViewById(R.id.txtBackupFolder)
            txtBackupAviso = it.findViewById(R.id.txtBackupAviso)
            rgRemovePeriodo = it.findViewById(R.id.rgRemovePeriodo)
            it.findViewById<Button>(R.id.btnChooseBackupFolder).setOnClickListener {
                abrirSeletorPasta(seletorPastaBaseLauncher)
            }
            it.findViewById<Button>(R.id.btnResetBackupFolder).setOnClickListener {
                if (!com.radioterapia.ai.util.StorageLocal.temAcessoTotal()) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.storage_folder_label)
                        .setMessage(R.string.storage_perm_msg)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            com.radioterapia.ai.util.StorageLocal.pedirAcessoTotal(this)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return@setOnClickListener
                }
                config.pastaBaseCustom = ""
                com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
                atualizarLabelArmazenamento()
                Toast.makeText(this, R.string.storage_reset_ok, Toast.LENGTH_SHORT).show()
            }
            com.radioterapia.ai.util.UiText.uniformizar(
                it.findViewById(R.id.btnChooseBackupFolder),
                it.findViewById(R.id.btnResetBackupFolder))
            it.findViewById<Button>(R.id.btnCleanCache).setOnClickListener { limparCacheAntigo() }
            txtDraftStatus = it.findViewById(R.id.txtDraftStatus)
            it.findViewById<Button>(R.id.btnOpenHistory).setOnClickListener {
                val intent = Intent(this, HistoricoActivity::class.java)
                intent.putExtra(HistoricoActivity.EXTRA_MODO_EDICAO, true)
                startActivity(intent)
            }
            atualizarStatusBase()
            atualizarLabelArmazenamento()
            atualizarCache()
        }

        adicionarGrupo("🖨️", R.string.group_printer, R.layout.group_printer) {
            edtPrinterIp = it.findViewById(R.id.edtPrinterIp)
            edtPrinterName = it.findViewById(R.id.edtPrinterName)
            txtPrinterTestResult = it.findViewById(R.id.txtPrinterTestResult)
            // Modo de impressão (1 por folha / frente-verso longa / curta)
            val rgDup = it.findViewById<android.widget.RadioGroup>(R.id.rgDuplex)
            when (config.printerDuplexMode) {
                "long" -> it.findViewById<android.widget.RadioButton>(R.id.rbDuplexLong).isChecked = true
                "short" -> it.findViewById<android.widget.RadioButton>(R.id.rbDuplexShort).isChecked = true
                else -> it.findViewById<android.widget.RadioButton>(R.id.rbDuplexOff).isChecked = true
            }
            rgDup.setOnCheckedChangeListener { _, checkedId ->
                config.printerDuplexMode = when (checkedId) {
                    R.id.rbDuplexLong -> "long"
                    R.id.rbDuplexShort -> "short"
                    else -> "simplex"
                }
            }
            it.findViewById<Button>(R.id.btnTestPrinter).setOnClickListener { testarImpressora() }
        }

        adicionarGrupo("🔄", R.string.group_sync, R.layout.group_sync) {
            swSyncMestre = it.findViewById(R.id.swSyncMestre)
            blocoSyncDetalhe = it.findViewById(R.id.blocoSyncDetalhe)
            edtSyncIntervalo = it.findViewById(R.id.edtSyncIntervalo)
            chkSyncGatFoto = it.findViewById(R.id.chkSyncGatFoto)
            chkSyncGatFim = it.findViewById(R.id.chkSyncGatFim)
            chkSyncGatAbrir = it.findViewById(R.id.chkSyncGatAbrir)
            chkSyncSoWifi = it.findViewById(R.id.chkSyncSoWifi)
            listaSyncPerfis = it.findViewById(R.id.listaSyncPerfis)
            txtSyncEstado = it.findViewById(R.id.txtSyncEstado)
            txtSyncOrigem = it.findViewById(R.id.txtSyncOrigem)
            txtSyncCopiaConfig = it.findViewById(R.id.txtSyncCopiaConfig)

            val cfg = com.radioterapia.ai.sync.SyncConfig(this)
            swSyncMestre?.isChecked = cfg.ativo
            edtSyncIntervalo?.setText(cfg.intervaloMinutos.toString())
            chkSyncGatFoto?.isChecked = cfg.gatilhoAoSalvarFoto
            chkSyncGatFim?.isChecked = cfg.gatilhoAoFinalizar
            chkSyncGatAbrir?.isChecked = cfg.gatilhoAoAbrir
            chkSyncSoWifi?.isChecked = cfg.somenteRedeNaoTarifada

            swSyncMestre?.setOnCheckedChangeListener { _, ligado ->
                // GRAVA NA HORA, e não no salvarTudo. Ligar a sincronização é o
                // ato que faz o app começar a usar a rede por conta própria;
                // deixar isso pendurado até alguém sair da tela criaria um
                // intervalo em que a tela diz uma coisa e o app faz outra.
                com.radioterapia.ai.sync.SyncConfig(this).ativo = ligado
                com.radioterapia.ai.sync.SyncWorker.reprogramar(this)
                atualizarEstadoSync()
            }
            it.findViewById<Button>(R.id.btnSyncAdicionar).setOnClickListener {
                startActivity(Intent(this, SyncPerfilActivity::class.java))
            }
            it.findViewById<Button>(R.id.btnSyncAgora).setOnClickListener { sincronizarAgora() }

            desenharPerfisSync()
            atualizarEstadoSync()
        }

        adicionarGrupo("📄", R.string.group_pdf, R.layout.group_pdf) {
            rgPdfOrientation = it.findViewById(R.id.rgPdfOrientation)
            rgPdfOrientation?.setOnCheckedChangeListener { _, checked ->
                txtMargemHint?.setText(
                    if (checked == R.id.rbPdfLandscape) R.string.pdf_margin_hint_landscape
                    else R.string.pdf_margin_hint_portrait)
            }
            it.findViewById<android.widget.CheckBox>(R.id.chkPdfTimeOut).apply {
                isChecked = config.pdfIncluiTimeOut
                setOnCheckedChangeListener { _, v -> config.pdfIncluiTimeOut = v }
            }
            edtEtiquetaLargura = it.findViewById(R.id.edtEtiquetaLargura)
            edtEtiquetaAltura = it.findViewById(R.id.edtEtiquetaAltura)
            edtPdfMargem = it.findViewById(R.id.edtPdfMargem)
            txtMargemHint = it.findViewById(R.id.txtMargemHint)
            layoutEtiquetaTamanho = it.findViewById(R.id.layoutEtiquetaTamanho)
        }

        adicionarGrupo("📑", R.string.group_protocolo, R.layout.group_protocolo) {
            protTotal = it.findViewById(R.id.txtProtTotal)
            protLista = it.findViewById(R.id.listaProtocolos)
            it.findViewById<android.widget.Button>(R.id.btnProtAdicionar).setOnClickListener {
                com.radioterapia.ai.protocolo.ProtocoloActivity.abrir(this, editarProtocolo)
            }
            desenharProtocolos()
        }

        adicionarGrupo("✍️", R.string.group_rubricario, R.layout.group_rubricario) {
            val chk = it.findViewById<android.widget.CheckBox>(R.id.chkRubAtivo)
            chk.isChecked = config.rubricarioAtivo
            chk.setOnCheckedChangeListener { _, v -> config.rubricarioAtivo = v }

            val rg = it.findViewById<android.widget.RadioGroup>(R.id.rgRubOrientacao)
            rg.check(if (config.rubricarioRetrato) R.id.rbRubRetrato else R.id.rbRubPaisagem)
            rg.setOnCheckedChangeListener { _, id ->
                config.rubricarioRetrato = (id == R.id.rbRubRetrato)
            }

            val edtCargos = it.findViewById<android.widget.EditText>(R.id.edtRubCargos)
            edtCargos.setText(config.rubricarioCargos)
            // Grava ao SAIR do campo, nao a cada tecla: salvar por tecla
            // reescreveria a lista de cargos a cada letra digitada.
            edtCargos.setOnFocusChangeListener { _, temFoco ->
                if (!temFoco) config.rubricarioCargos = edtCargos.text.toString()
            }

            rubLista = it.findViewById(R.id.listaRubEquipe)
            rubTotal = it.findViewById(R.id.txtRubTotal)
            rubSpBloco = it.findViewById(R.id.spRubBloco)
            it.findViewById<Button>(R.id.btnRubBlocoNovo)
                .setOnClickListener { novoBlocoRubricario() }
            it.findViewById<Button>(R.id.btnRubBlocoRenomear)
                .setOnClickListener { renomearBlocoRubricario() }
            it.findViewById<Button>(R.id.btnRubBlocoExcluir)
                .setOnClickListener { excluirBlocoRubricario() }
            it.findViewById<Button>(R.id.btnRubBlocoExportar)
                .setOnClickListener { exportarBlocoRubricario() }
            it.findViewById<Button>(R.id.btnRubBlocoImportar).setOnClickListener {
                try { importarBlocoLauncher.launch(arrayOf("application/json", "text/*")) }
                catch (_: Exception) {
                    Toast.makeText(this, R.string.prot_sem_seletor, Toast.LENGTH_SHORT).show()
                }
            }
            it.findViewById<android.widget.Button>(R.id.btnRubAdicionar).setOnClickListener {
                com.radioterapia.ai.rubricario.RubricarioPessoaActivity
                    .abrir(this, editarRubrica, null, rubBlocoAtual)
            }
            it.findViewById<android.widget.Button>(R.id.btnRubGerarPdf).setOnClickListener {
                gerarRubricarioAvulso(apenasVer = false)
            }
            it.findViewById<android.widget.Button>(R.id.btnRubVerPdf).setOnClickListener {
                gerarRubricarioAvulso(apenasVer = true)
            }
            desenharBlocosRubricario()
            desenharEquipeRubricario()
        }

        adicionarGrupo("📦", R.string.group_transferencia, R.layout.group_transferencia) {
            montarTransferencia(it)
        }

        adicionarGrupo("🌍", R.string.group_language, R.layout.group_language) {
            chkLangAuto = it.findViewById(R.id.chkLangAuto)
            spinnerLanguage = it.findViewById(R.id.spinnerLanguage)
            spinnerLanguage?.adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_dropdown_item,
                idiomasMap.map { p -> p.second })
            chkLangAuto?.setOnCheckedChangeListener { _, checked ->
                spinnerLanguage?.isEnabled = !checked
            }
            // O formato aparece como o usuario o le — "DD/MM/AAAA" —, e nao
            // como o codigo o guarda ("dd/MM/yyyy". As duas listas andam
            // juntas, pelo indice.
            spinnerFormatoData = it.findViewById(R.id.spinnerFormatoData)
            spinnerFormatoData?.adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_dropdown_item,
                com.radioterapia.ai.util.DateUtils.FORMATOS_ENTRADA.map { f -> rotuloFormato(f) })
        }



        adicionarGrupo("📊", R.string.group_stats_logs, R.layout.group_stats_logs) {
            com.radioterapia.ai.util.UiText.uniformizar(
                it.findViewById(R.id.btnAbrirStats), it.findViewById(R.id.btnAbrirLogs))
            it.findViewById<Button>(R.id.btnAbrirStats).setOnClickListener {
                startActivity(Intent(this, com.radioterapia.ai.stats.StatsActivity::class.java))
            }
            it.findViewById<Button>(R.id.btnAbrirLogs).setOnClickListener {
                startActivity(Intent(this, com.radioterapia.ai.ui.LogsActivity::class.java))
            }
        }

        adicionarGrupo("⚠️", R.string.group_restore_defaults, R.layout.group_restore_defaults) {
            com.radioterapia.ai.util.UiText.uniformizar(
                it.findViewById(R.id.btnRedoOnboarding), it.findViewById(R.id.btnRestoreAll))
            it.findViewById<Button>(R.id.btnRedoOnboarding).setOnClickListener { confirmarRefazerOnboarding() }
            it.findViewById<Button>(R.id.btnRestoreAll).setOnClickListener { confirmarRestaurarPadroes() }
        }

        adicionarGrupo("⬆️", R.string.group_update, R.layout.group_update) {
            montarAtualizacao(it)
        }

        adicionarGrupo("ℹ️", R.string.group_about, R.layout.group_about) {
            it.findViewById<Button>(R.id.btnOpenAbout).setOnClickListener {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        }

        // Termos e Privacidade - acessível também depois do consentimento
        adicionarGrupo("📜", R.string.terms_and_privacy, R.layout.group_terms_privacy) {
            it.findViewById<Button>(R.id.btnOpenTerms).setOnClickListener {
                mostrarTextoLongo(getString(R.string.terms_title), getString(R.string.terms_body))
            }
            it.findViewById<Button>(R.id.btnOpenPrivacy).setOnClickListener {
                mostrarTextoLongo(getString(R.string.privacy_title), getString(R.string.privacy_body))
            }
        }
    }

    /** Diálogo rolável para textos longos (termos / política de privacidade). */
    private fun mostrarTextoLongo(titulo: String, corpo: String) {
        val scroll = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            text = corpo
            setTextColor(android.graphics.Color.parseColor("#D5DDE9"))
            textSize = 13f
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * Adiciona um grupo colapsável ao container.
     * @param onContentInflated executado depois que o conteúdo é inflado, para o grupo bindar suas views.
     */
    /** Pares (conteúdo, seta) de todos os grupos, para o acordeão exclusivo. */
    private val gruposAcordeao = mutableListOf<Pair<LinearLayout, TextView>>()

    // ---- Sincronização de prontuários
    private var swSyncMestre: com.google.android.material.switchmaterial.SwitchMaterial? = null
    private var blocoSyncDetalhe: View? = null
    private var edtSyncIntervalo: EditText? = null
    private var chkSyncGatFoto: CheckBox? = null
    private var chkSyncGatFim: CheckBox? = null
    private var chkSyncGatAbrir: CheckBox? = null
    private var chkSyncSoWifi: CheckBox? = null
    private var listaSyncPerfis: LinearLayout? = null
    private var txtSyncEstado: TextView? = null
    private var txtSyncOrigem: TextView? = null
    private var txtSyncCopiaConfig: TextView? = null

    /**
     * Desenha um cartão por destino.
     *
     * Redesenhado no `onResume` porque a edição acontece em outra tela: sem
     * isso, voltar de lá mostraria o nome antigo e o erro antigo — e a pessoa
     * concluiria que a alteração não foi salva.
     */
    /**
     * O destino chegou pelo pacote de configuracao e ainda nao tem senha.
     *
     * SAF nao entra: aquele destino fala com o provedor de documentos do
     * Android, com a conta que a clinica ja autorizou no aparelho, e nao tem
     * senha propria para faltar.
     */
    private fun precisaSenha(p: com.radioterapia.ai.sync.PerfilSync): Boolean =
        p.tipo != com.radioterapia.ai.sync.PerfilSync.Tipo.SAF &&
        com.radioterapia.ai.sync.PerfilStore(this).senha(p.id).isBlank()

    /**
     * O formato como o usuario o le.
     *
     * "dd/MM/yyyy" e a forma que o codigo entende; "DD/MM/AAAA" e a que a
     * pessoa reconhece. Mostrar o padrao do Java numa tela de configuracao
     * seria pedir que ela traduza convencao de programador.
     */
    private fun rotuloFormato(f: String): String = when (f) {
        "MM/dd/yyyy" -> "MM/DD/AAAA"
        "yyyy-MM-dd" -> "AAAA-MM-DD"
        else          -> "DD/MM/AAAA"
    }

    // ==================== ATUALIZAÇÃO DO APLICATIVO ====================

    private var txtUpdEstado: TextView? = null
    private var btnUpdAtualizar: Button? = null
    private var btnUpdPagina: Button? = null

    /**
     * Há um fluxo de atualização em curso. Enquanto for `true`, o botão fica
     * desabilitado: dois toques seguidos abririam dois downloads do mesmo APK
     * no mesmo arquivo.
     */
    private var fluxoAtualizacaoAtivo = false

    /** A versão que esperava a liberação de «instalar apps desconhecidos». */
    private var pubAguardandoPermissao: AtualizacaoRemota.Publicada? = null

    /**
     * Volta da tela do sistema que libera a instalação por esta via.
     *
     * Liberada, o fluxo segue sozinho para a cópia de segurança e o download:
     * pedir que a pessoa toque de novo em «Atualizar agora» seria um clique
     * que a tela consegue inferir. A tela do sistema não devolve resultado
     * útil, então quem decide é podeInstalar, relido na volta. Se esta
     * Activity foi recriada enquanto a tela do sistema estava na frente, o
     * fluxo não sobreviveu, e a volta só destrava o botão e a orientação.
     *
     * Inicializador de propriedade, como os demais launchers desta tela: o
     * registro precisa acontecer antes de STARTED.
     */
    private val liberarInstalacaoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pub = pubAguardandoPermissao
        pubAguardandoPermissao = null
        if (pub != null && fluxoAtualizacaoAtivo && !isFinishing && !isDestroyed &&
            GerenciadorAtualizacao.podeInstalar(this)) {
            executarAtualizacao(pub)
        } else {
            encerrarFluxo()
        }
    }

    /**
     * Monta o grupo. Não pergunta nada à rede ao abrir: mostra o que a última
     * checagem já gravou, e só sai para a rede quando alguém toca em
     * «Atualizar agora». Abrir Configurações não é pedido de atualização.
     */
    private fun montarAtualizacao(raiz: View) {
        val g = GerenciadorAtualizacao

        raiz.findViewById<TextView>(R.id.txtUpdateInstalada).text = getString(
            R.string.update_installed_fmt,
            com.radioterapia.ai.BuildConfig.VERSION_NAME,
            com.radioterapia.ai.BuildConfig.VERSION_CODE)

        val chk = raiz.findViewById<CheckBox>(R.id.chkUpdateAuto)
        chk.isChecked = config.procurarAtualizacao
        chk.setOnCheckedChangeListener { _, marcado ->
            config.procurarAtualizacao = marcado
        }

        txtUpdEstado = raiz.findViewById(R.id.txtUpdateEstado)
        btnUpdAtualizar = raiz.findViewById(R.id.btnUpdateAtualizar)
        btnUpdPagina = raiz.findViewById(R.id.btnUpdatePagina)

        // Se a checagem de fundo já achou versão nova, o grupo abre dizendo, e
        // a página da versão fica à mão como saída manual.
        if (g.avisoPendente(this)) {
            mostrarEstadoAtualizacao(getString(R.string.update_found_fmt, config.atualizacaoNome))
            btnUpdPagina?.visibility = View.VISIBLE
        } else {
            mostrarEstadoAtualizacao("")
        }

        btnUpdAtualizar?.setOnClickListener { atualizarPeloBotao() }
        btnUpdPagina?.setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(AtualizacaoRemota.URL_RELEASES)))
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * A linha de estado some quando não tem o que dizer: vazia, ela ainda
     * reservaria uma faixa em branco abaixo do botão.
     */
    private fun mostrarEstadoAtualizacao(texto: String) {
        txtUpdEstado?.text = texto
        txtUpdEstado?.visibility = if (texto.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * «Atualizar agora», o único botão do grupo: checar → (há versão nova?) →
     * confirmar → liberar a instalação → cópia de segurança → download com
     * SHA-256 → assinatura → instalador do sistema.
     *
     * Aqui a falha APARECE. Em todo o resto do app, não achar versão nova e
     * não conseguir perguntar são a mesma coisa — silêncio. Quem tocou num
     * botão está esperando resposta, e silêncio faria o botão parecer
     * quebrado.
     *
     * [jaConfirmado] vem do convite que aparece ao abrir esta tela: aquele
     * diálogo já foi a confirmação, e repeti-lo pediria o mesmo sim duas
     * vezes. Como o grupo pode estar recolhido nesse caso, o resultado da
     * checagem também sai em aviso curto.
     *
     * A ORIENTAÇÃO FICA TRAVADA enquanto o fluxo corre. Esta Activity é
     * recriada ao girar; girar o tablet no meio do download destruiria a dona
     * dos diálogos, e um `show()` numa Activity destruída derruba o app.
     * Travar evita a recriação sem acrescentar dependência de ciclo de vida.
     * Todo passo que volta de IO ainda confere `isFinishing || isDestroyed`
     * antes de tocar na tela.
     */
    private fun atualizarPeloBotao(jaConfirmado: Boolean = false) {
        if (fluxoAtualizacaoAtivo) return
        fluxoAtualizacaoAtivo = true
        btnUpdAtualizar?.isEnabled = false
        btnUpdPagina?.visibility = View.GONE
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        mostrarEstadoAtualizacao(getString(R.string.update_checking))

        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val pub = withContext(Dispatchers.IO) {
                try { GerenciadorAtualizacao.checarEGravar(app, forcado = true) }
                catch (_: Exception) { null }
            }
            if (isFinishing || isDestroyed) return@launch
            val g = GerenciadorAtualizacao
            when {
                pub == null -> {
                    mostrarEstadoAtualizacao(getString(R.string.update_offline))
                    if (jaConfirmado) Toast.makeText(this@SettingsActivity,
                        R.string.update_offline, Toast.LENGTH_LONG).show()
                    encerrarFluxo()
                }
                !g.cabeAtualizar(g.versaoInstalada(), pub.versionCode, pub.minSdk,
                    android.os.Build.VERSION.SDK_INT) -> {
                    mostrarEstadoAtualizacao(getString(R.string.update_uptodate))
                    if (jaConfirmado) Toast.makeText(this@SettingsActivity,
                        R.string.update_uptodate, Toast.LENGTH_LONG).show()
                    encerrarFluxo()
                }
                else -> {
                    mostrarEstadoAtualizacao(getString(R.string.update_found_fmt, pub.versionName))
                    if (jaConfirmado) prosseguirComPermissao(pub) else confirmarAtualizacao(pub)
                }
            }
        }
    }

    /**
     * A confirmação, com a versão no texto e as notas publicadas, quando há.
     *
     * «Depois» dispensa ESTA versão — grava o versionCode, como o convite — e
     * a página da versão aparece como caminho manual. Voltar ou tocar fora só
     * encerra, sem dispensar nada: não é resposta.
     */
    private fun confirmarAtualizacao(pub: AtualizacaoRemota.Publicada) {
        val msg = StringBuilder(getString(R.string.update_dialog_msg_fmt, pub.versionName))
        if (pub.notas.isNotBlank()) {
            msg.append("\n\n").append(getString(R.string.update_notes_title))
                .append('\n').append(pub.notas.trim())
        }
        var respondeu = false
        AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(msg.toString())
            .setNegativeButton(R.string.update_later) { _, _ ->
                respondeu = true
                config.atualizacaoDispensada = pub.versionCode
                btnUpdPagina?.visibility = View.VISIBLE
                encerrarFluxo()
            }
            .setPositiveButton(R.string.update_now_btn) { _, _ ->
                respondeu = true
                prosseguirComPermissao(pub)
            }
            .setOnDismissListener { if (!respondeu) encerrarFluxo() }
            .show()
    }

    /**
     * A liberação do sistema é pedida ANTES da cópia e do download: descobrir
     * que falta permissão depois de baixar dezenas de MB gasta a rede da
     * clínica para nada. O diálogo diz por que uma tela do sistema vai abrir;
     * sem ele, ela pareceria ter tomado o lugar do app sem pedir.
     */
    private fun prosseguirComPermissao(pub: AtualizacaoRemota.Publicada) {
        val g = GerenciadorAtualizacao
        val liberar = if (g.podeInstalar(this)) null else g.intentLiberarInstalacao(this)
        if (liberar == null) {
            executarAtualizacao(pub)
            return
        }
        var respondeu = false
        AlertDialog.Builder(this)
            .setTitle(R.string.group_update)
            .setMessage(R.string.update_perm_needed)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                respondeu = true
                pubAguardandoPermissao = pub
                try {
                    liberarInstalacaoLauncher.launch(liberar)
                } catch (_: Exception) {
                    // Aparelho sem essa tela do sistema: resta a página da versão.
                    pubAguardandoPermissao = null
                    btnUpdPagina?.visibility = View.VISIBLE
                    Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_LONG).show()
                    encerrarFluxo()
                }
            }
            .setOnDismissListener { if (!respondeu) encerrarFluxo() }
            .show()
    }

    /**
     * Cópia de segurança, depois o download.
     *
     * A cópia vem ANTES do download porque é o passo que protege o dado, e
     * porque é o único que funciona sem rede. Se o download falhar depois, a
     * cópia já está gravada e não custou nada. Uma cópia em andamento não é
     * interrompida pelo Cancelar: o pedido é atendido assim que ela termina.
     */
    private fun executarAtualizacao(pub: AtualizacaoRemota.Publicada) {
        val app = applicationContext
        val cancelado = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.group_update)
            .setMessage(R.string.update_backup_running)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelado.set(true) }
            .create()
        dlg.show()

        CoroutineScope(Dispatchers.Main).launch {
            val backup = withContext(Dispatchers.IO) {
                val r = try { BackupPreAtualizacao.executar(app) }
                        catch (_: Exception) { BackupPreAtualizacao.Resultado(null, 0, 0L) }
                if (r.ok) {
                    try {
                        com.radioterapia.ai.audit.AuditLogger(app).registrar(
                            com.radioterapia.ai.audit.AuditLogger.Tipo.EDIT,
                            "Copia de seguranca antes de atualizar",
                            mapOf("pasta" to (r.pasta?.name ?: ""),
                                  "arquivos" to r.arquivos))
                    } catch (_: Exception) {
                    }
                }
                r
            }
            fecharDialogo(dlg)
            if (isFinishing || isDestroyed) return@launch
            if (cancelado.get()) {
                mostrarEstadoAtualizacao("")
                encerrarFluxo()
                return@launch
            }
            if (backup.ok) {
                baixarEInstalar(pub, backup)
                return@launch
            }
            // A cópia falhou. É uma escolha de risco, que só aparece neste caso
            // e que nada permite inferir: por isso, e só por isso, pergunta.
            var respondeu = false
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.group_update)
                .setMessage(R.string.update_backup_failed_continue)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.update_now_btn) { _, _ ->
                    respondeu = true
                    baixarEInstalar(pub, backup)
                }
                .setOnDismissListener { if (!respondeu) encerrarFluxo() }
                .show()
        }
    }

    /**
     * Download com SHA-256, conferência da assinatura e entrega ao instalador.
     *
     * Cada desfecho do download tem a sua frase: sem rede e arquivo que não
     * confere pedem ações diferentes, e uma mensagem só para os dois mandaria
     * a pessoa procurar o defeito errado. Cancelar não é erro e não diz nada.
     *
     * Assinatura diferente da instalada para aqui, com o caminho da cópia: o
     * instalador recusaria com uma mensagem genérica, e o passo seguinte de
     * quem a lê é desinstalar, o que apaga os dados internos. Não deu para
     * conferir (`null`) segue, e o instalador continua sendo a autoridade.
     */
    private fun baixarEInstalar(pub: AtualizacaoRemota.Publicada,
                                backup: BackupPreAtualizacao.Resultado) {
        val app = applicationContext
        val g = GerenciadorAtualizacao
        val cancelado = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.group_update)
            .setMessage(getString(R.string.update_downloading_fmt, 0))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelado.set(true) }
            .create()
        dlg.show()

        CoroutineScope(Dispatchers.Main).launch {
            var ultimoPct = -1
            val apk = withContext(Dispatchers.IO) { g.arquivoApk(app) }
            val resultado = withContext(Dispatchers.IO) {
                AtualizacaoRemota.baixarApk(
                    pub.urlApk, apk, pub.sha256, cancelado = { cancelado.get() }
                ) { lidos, total ->
                    val pct = if (total > 0) (lidos * 100 / total).toInt() else -1
                    if (pct >= 0 && pct != ultimoPct) {
                        ultimoPct = pct
                        // A View é tocada na thread principal, sempre: o texto
                        // é postado, e não escrito daqui, que é IO.
                        dlg.window?.decorView?.post {
                            if (dlg.isShowing) {
                                dlg.setMessage(getString(R.string.update_downloading_fmt, pct))
                            }
                        }
                    }
                }
            }
            fecharDialogo(dlg)
            if (isFinishing || isDestroyed) return@launch
            when (resultado) {
                AtualizacaoRemota.Download.OK -> Unit
                AtualizacaoRemota.Download.CANCELADO -> {
                    mostrarEstadoAtualizacao("")
                    encerrarFluxo()
                    return@launch
                }
                AtualizacaoRemota.Download.FALHA -> {
                    btnUpdPagina?.visibility = View.VISIBLE
                    avisarAtualizacao(getString(R.string.update_download_fail))
                    encerrarFluxo()
                    return@launch
                }
                AtualizacaoRemota.Download.HASH_DIFERENTE -> {
                    avisarAtualizacao(getString(R.string.update_hash_fail))
                    encerrarFluxo()
                    return@launch
                }
            }

            val (compativel, pastaCopia) = withContext(Dispatchers.IO) {
                val c = try { g.assinaturaCompativel(app, apk) } catch (_: Exception) { null }
                if (c == false) apk.delete()
                c to caminhoDaCopia(app, backup)
            }
            if (isFinishing || isDestroyed) return@launch
            if (compativel == false) {
                avisarAtualizacao(getString(R.string.update_signature_mismatch_fmt, pastaCopia))
                encerrarFluxo()
                return@launch
            }

            // O marcador vai ANTES do instalador, gravado de forma síncrona:
            // com a troca do pacote o processo morre, e o que não estiver no
            // disco se perde. É ele que deixa a versão nova confirmar, na
            // primeira abertura, que os dados chegaram. Vai junto o versionCode
            // deste APK: um marcador de instalação cancelada não pode valer para
            // outra versão instalada depois por outro caminho.
            withContext(Dispatchers.IO) {
                try { g.gravarMarcadorPreInstalacao(app, backup, pub.versionCode) }
                catch (_: Exception) {}
            }
            if (isFinishing || isDestroyed) return@launch
            if (backup.ok) {
                Toast.makeText(this@SettingsActivity,
                    getString(if (backup.privada) R.string.update_backup_private_fmt
                              else R.string.update_backup_ok_fmt, pastaCopia),
                    Toast.LENGTH_LONG).show()
            }
            if (g.instalar(app, apk)) {
                Toast.makeText(this@SettingsActivity,
                    R.string.update_installer_hint, Toast.LENGTH_LONG).show()
            } else {
                // Nada foi entregue ao instalador: sem desfazer o marcador, a
                // próxima versão instalada leria contagens de uma tentativa
                // que não aconteceu.
                withContext(Dispatchers.IO) { config.atualizacaoDe = 0 }
                Toast.makeText(app, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
            encerrarFluxo()
        }
    }

    /**
     * Onde está a cópia, no caminho que a pessoa reconhece. Sem cópia gravada —
     * ela falhou e a pessoa seguiu assim mesmo — aponta a pasta das cópias,
     * onde as anteriores continuam. Resolve a pasta base: chamar fora da
     * thread principal.
     */
    private fun caminhoDaCopia(ctx: android.content.Context,
                               backup: BackupPreAtualizacao.Resultado): String {
        val pasta = backup.pasta ?: try {
            java.io.File(com.radioterapia.ai.util.StorageLocal.base(ctx),
                BackupPreAtualizacao.NOME_PASTA)
        } catch (_: Exception) { null }
        return pasta?.let { com.radioterapia.ai.util.StorageLocal.amigavel(it.absolutePath) }
            ?: BackupPreAtualizacao.NOME_PASTA
    }

    /** Desfecho que a pessoa precisa ler: diálogo de uma frase, só com OK. */
    private fun avisarAtualizacao(texto: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.group_update)
            .setMessage(texto)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Fecha um diálogo de progresso que pode já ter sido fechado pelo Cancelar. */
    private fun fecharDialogo(dlg: android.app.Dialog) {
        try {
            if (dlg.isShowing) dlg.dismiss()
        } catch (_: Exception) {
        }
    }

    /** Fim do fluxo, por qualquer caminho: destrava o botão e a orientação. */
    private fun encerrarFluxo() {
        fluxoAtualizacaoAtivo = false
        pubAguardandoPermissao = null
        btnUpdAtualizar?.isEnabled = true
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /**
     * O convite que aparece ao ABRIR as Configurações, quando há versão nova.
     *
     * «Depois» grava o versionCode dispensado, não um booleano: adiar silencia
     * ESTA versão e não a função. Sem isso, um «depois» apertado por reflexo
     * desligaria o aviso para sempre.
     */
    private fun oferecerAtualizacaoSeHouver() {
        val g = GerenciadorAtualizacao
        if (!g.avisoPendente(this)) return
        val nome = config.atualizacaoNome
        if (nome.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(getString(R.string.update_dialog_msg_fmt, nome))
            .setNegativeButton(R.string.update_later) { _, _ ->
                config.atualizacaoDispensada = config.atualizacaoCode
            }
            .setPositiveButton(R.string.update_now_btn) { _, _ ->
                // O convite já foi a confirmação. A checagem forçada traz o
                // hash e o endereço publicados agora, e o fluxo segue direto
                // para a liberação, a cópia e o download.
                atualizarPeloBotao(jaConfirmado = true)
            }
            .show()
    }

    private fun desenharPerfisSync() {
        val lista = listaSyncPerfis ?: return
        lista.removeAllViews()
        val perfis = com.radioterapia.ai.sync.PerfilStore(this).listar()
        if (perfis.isEmpty()) {
            lista.addView(TextView(this).apply {
                text = getString(R.string.sync_no_profiles)
                textSize = 13f
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@SettingsActivity, R.color.text_secondary))
                setPadding(0, 8, 0, 8)
            })
            return
        }
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        val d = resources.displayMetrics.density
        perfis.forEach { p ->
            val cartao = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                setBackgroundColor(0x11000000)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * d).toInt() }
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SettingsActivity, SyncPerfilActivity::class.java)
                        .putExtra(SyncPerfilActivity.EXTRA_ID, p.id))
                }
            }
            cartao.addView(TextView(this).apply {
                text = "${p.nome}  ·  ${p.tipo.name}"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@SettingsActivity, R.color.text_primary))
            })
            // ORIGEM À ESQUERDA, DESTINO À DIREITA, na mesma linha: é o desenho
            // que responde "de onde para onde" sem obrigar a ler duas linhas.
            cartao.addView(TextView(this).apply {
                text = destinoLegivel(p)
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@SettingsActivity, R.color.text_secondary))
            })
            cartao.addView(TextView(this).apply {
                text = when {
                    // AGUARDANDO SENHA vem ANTES de "inativo", porque explica o
                    // porque dele. O pacote de configuracao leva o destino —
                    // endereco, usuario, caminho — e NAO leva a senha, que fica
                    // cifrada no Keystore e nao viaja por e-mail nem pen-drive.
                    // O destino chega desligado de proposito; sem esta linha,
                    // quem importa conclui que nada foi importado.
                    precisaSenha(p) -> getString(R.string.sync_falta_senha)
                    !p.ativo -> getString(R.string.sync_profile_inactive)
                    p.ultimoErro.isNotBlank() -> getString(R.string.sync_profile_error, p.ultimoErro)
                    p.ultimaSincronizacao > 0 -> getString(R.string.sync_profile_last,
                        fmt.format(java.util.Date(p.ultimaSincronizacao)))
                    else -> getString(R.string.sync_profile_never)
                }
                textSize = 11f
                setTextColor(
                    if (precisaSenha(p)) ContextCompat.getColor(
                        this@SettingsActivity, R.color.error_red_fg)
                    else if (p.ultimoErro.isNotBlank() && p.ativo) 0xFFC62828.toInt()
                    else androidx.core.content.ContextCompat.getColor(
                        this@SettingsActivity, R.color.text_secondary))
            })
            lista.addView(cartao)
        }
    }

    /** O destino em uma linha, no formato que a pessoa digitou. */
    private fun destinoLegivel(p: com.radioterapia.ai.sync.PerfilSync): String = when (p.tipo) {
        com.radioterapia.ai.sync.PerfilSync.Tipo.SMB ->
            "\\\\${p.host}\\${p.share}\\${p.caminhoRemoto}"
        com.radioterapia.ai.sync.PerfilSync.Tipo.WEBDAV ->
            listOf(p.urlBase.trimEnd('/'), p.caminhoRemoto).filter { it.isNotBlank() }.joinToString("/")
        com.radioterapia.ai.sync.PerfilSync.Tipo.FTP,
        com.radioterapia.ai.sync.PerfilSync.Tipo.SFTP ->
            "${p.tipo.name.lowercase()}://${p.host}/${p.caminhoRemoto}"
        com.radioterapia.ai.sync.PerfilSync.Tipo.SAF ->
            (try { com.radioterapia.ai.util.StorageLocal.treeUriParaCaminho(
                android.net.Uri.parse(p.safUri)) } catch (_: Exception) { null }) ?: p.safUri
    }

    private fun atualizarEstadoSync() {
        val cfg = com.radioterapia.ai.sync.SyncConfig(this)
        blocoSyncDetalhe?.alpha = if (cfg.ativo) 1f else 0.4f
        txtSyncOrigem?.text = getString(R.string.sync_origin,
            com.radioterapia.ai.util.StorageLocal.caminhoLegivel(this))
        // O nome da pasta vai literal, igual ao que aparece no destino; o
        // grupo de Transferência é citado pelo próprio título, para a frase
        // apontar o lugar exato onde se importa a cópia.
        txtSyncCopiaConfig?.text = getString(R.string.sync_config_copy_hint,
            com.radioterapia.ai.transfer.CopiaConfiguracao.NOME_PASTA,
            getString(R.string.group_transferencia))
        txtSyncEstado?.text =
            if (cfg.ultimaVarredura > 0)
                getString(R.string.sync_last, java.text.SimpleDateFormat(
                    "dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(cfg.ultimaVarredura)))
            else getString(R.string.sync_never)
    }

    /** Varre agora, na mão, e conta o que aconteceu. */
    private fun sincronizarAgora() {
        salvarTudo()
        val cfg = com.radioterapia.ai.sync.SyncConfig(this)
        if (!cfg.ativo) {
            Toast.makeText(this, R.string.sync_master_hint, Toast.LENGTH_LONG).show(); return
        }
        txtSyncEstado?.text = getString(R.string.sync_running)
        CoroutineScope(Dispatchers.Main).launch {
            // ORÇAMENTO CURTO AQUI, e não os 8 minutos do trabalho em segundo
            // plano: esta varredura está presa a uma tela aberta, sem botão de
            // cancelar. O que não couber em 45 segundos continua pela fila do
            // WorkManager, que é onde uma tarefa longa deve morar — e o número
            // de pendentes no fim da linha diz exatamente quanto sobrou.
            val app = applicationContext
            val resumos = withContext(Dispatchers.IO) {
                try {
                    com.radioterapia.ai.sync.MotorSync(app).sincronizarTudo(limiteMs = 45_000L)
                } catch (_: Exception) { emptyList<com.radioterapia.ai.sync.MotorSync.Resumo>() }
            }
            // Outra varredura ficou com a vez durante todo o prazo: nada foi
            // enviado por este toque, e isso não é falha. Mas a que está
            // rodando fez a lista dela ao começar e não vê o que foi gravado
            // depois. O trabalho posto na fila aqui espera a vez, lista de
            // novo e leva o que chegou depois logo em seguida. Enfileira antes
            // de conferir a tela: quem saiu dela também tem arquivos novos.
            val emAndamento = resumos.any { it.emAndamento }
            if (emAndamento) com.radioterapia.ai.sync.SyncWorker.agora(app)
            if (isFinishing || isDestroyed) return@launch
            if (emAndamento) {
                txtSyncEstado?.text = getString(R.string.sync_em_andamento)
                desenharPerfisSync()
                return@launch
            }
            val env = resumos.sumOf { it.enviados }
            val ja = resumos.sumOf { it.jaEstavam }
            val pend = resumos.sumOf { it.pendentes }
            txtSyncEstado?.text = getString(R.string.sync_result, env, ja, pend)
            // O QUE SOBROU VAI PARA A FILA. Sem isto, o técnico veria
            // "pendentes: 812" e não teria o que fazer a respeito senão tocar o
            // botão de novo, quarenta e cinco segundos por vez.
            if (pend > 0) com.radioterapia.ai.sync.SyncWorker.agora(this@SettingsActivity)
            desenharPerfisSync()
        }
    }

    private fun adicionarGrupo(
        icone: String,
        @androidx.annotation.StringRes tituloRes: Int,
        @androidx.annotation.LayoutRes contentLayout: Int,
        expandido: Boolean = false,
        onContentInflated: (View) -> Unit
    ) {
        val groupView = LayoutInflater.from(this).inflate(R.layout.settings_group, groupsContainer, false)
        groupView.findViewById<TextView>(R.id.groupIcon).text = icone
        groupView.findViewById<TextView>(R.id.groupTitle).text = getString(tituloRes)

        val expander = groupView.findViewById<TextView>(R.id.groupExpander)
        val content = groupView.findViewById<LinearLayout>(R.id.groupContent)
        val header = groupView.findViewById<LinearLayout>(R.id.groupHeader)

        // Infla o conteúdo do grupo dentro de groupContent
        val contentView = LayoutInflater.from(this).inflate(contentLayout, content, false)
        content.addView(contentView)
        onContentInflated(contentView)

        if (expandido) { content.visibility = View.VISIBLE; expander.text = "▼" }

        gruposAcordeao.add(content to expander)
        header.setOnClickListener {
            val expandirAgora = content.visibility != View.VISIBLE
            // Acordeão: só um bloco expandido por vez — recolhe todos os demais.
            gruposAcordeao.forEach { (ct, ex) ->
                ct.visibility = View.GONE; ex.text = "▶"
            }
            if (expandirAgora) { content.visibility = View.VISIBLE; expander.text = "▼" }
        }

        groupsContainer.addView(groupView)
    }

    // ============= CARREGAR =============

    private fun carregarTudo() {
        // Logo
        atualizarLogoPreview()

        // Identidade
        edtCompanyName?.setText(config.nomeClinica)

        // SMB: bind removido. Ver a nota em salvarTudo().

        // Backups (grupo pode não estar presente — guardar contra lista vazia)
        if (backupBindings.size >= 4) for (i in 1..4) {
            val d = config.obterDestino(i)
            val b = backupBindings[i - 1]
            b.checkBox.isChecked = d.ativo
            b.host.setText(d.host)
            b.porta.setText(d.porta.toString())
            b.unc.setText(d.caminhoUNC)
            b.grupo.visibility = if (d.ativo) View.VISIBLE else View.GONE
        }

        // CSV: bind removido, incluindo os três extra. Ver a nota em salvarTudo().

        atualizarStatusCsv()

        // Impressora
        edtPrinterIp?.setText(config.impressoraIp)
        edtPrinterName?.setText(config.impressoraNome)

        // PDF
        rgPdfOrientation?.check(
            if (config.pdfLandscape) R.id.rbPdfLandscape else R.id.rbPdfPortrait
        )
        edtEtiquetaLargura?.setText(config.pdfEtiquetaLarguraMm.toString())
        edtEtiquetaAltura?.setText(config.pdfEtiquetaAlturaMm.toString())
        edtPdfMargem?.setText(config.pdfMargemMm.toString())
        txtMargemHint?.setText(
            if (config.pdfLandscape) R.string.pdf_margin_hint_landscape
            else R.string.pdf_margin_hint_portrait)

        // Idioma
        val auto = LocaleManager.isAuto(this)
        chkLangAuto?.isChecked = auto
        spinnerLanguage?.isEnabled = !auto
        val langAtual = LocaleManager.obterIdiomaConfigurado(this)
        val idx = idiomasMap.indexOfFirst { it.first == langAtual }
        if (idx >= 0) spinnerLanguage?.setSelection(idx)

        val idxFmt = com.radioterapia.ai.util.DateUtils.FORMATOS_ENTRADA
            .indexOf(config.formatoData)
        if (idxFmt >= 0) spinnerFormatoData?.setSelection(idxFmt)

        // Pasta local e grade da câmera: bind removido. Ver a nota em salvarTudo().

        // Status conexão
        atualizarStatusConexao()

        // Cache
        atualizarCache()
    }

    // ============= RUBRICARIO =============

    private var rubLista: android.widget.LinearLayout? = null
    private var rubTotal: android.widget.TextView? = null

    /** Volta da tela de cadastro: redesenha a tabela com quem foi salvo. */
    private val editarRubrica = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { desenharEquipeRubricario() }

    private val editarProtocolo = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { desenharProtocolos() }

    /**
     * Cada desenho da lista de protocolos ganha um número. A contagem de
     * páginas que volta do IO só preenche a lista que a pediu, e não uma
     * redesenhada depois por uma seta ou pela volta do editor.
     */
    private var protGeracao = 0

    /** Protocolos sendo duplicados agora: o Duplicar deles nasce desabilitado
     *  mesmo que a lista seja redesenhada no meio da cópia. */
    private val protDuplicando = mutableSetOf<String>()

    /**
     * Lista os protocolos: setas, nome, total de páginas, Editar, Duplicar e
     * Excluir.
     *
     * O PADRÃO mostra só o nome — e o total de páginas, se alguém lhe
     * acrescentou alguma —, fica fixo em primeiro, sem setas, e não tem
     * Excluir. A proibição de verdade está no ProtocoloStore; esconder os
     * botões é o que evita o usuário tentar. No lugar das setas e do Excluir
     * dele ficam vagas INVISIBLE, e não GONE: sem elas, o nome e os botões do
     * Padrão sairiam deslocados das colunas das outras linhas. Pelo mesmo
     * motivo, a seta que não se aplica a uma linha fica invisível em vez de
     * sumir.
     *
     * O total de páginas abre cada PDF, então é contado em IO depois que as
     * linhas já estão na tela. Até lá o subtítulo fica vazio com a altura
     * reservada, e a linha não pula quando o número chega.
     */
    private fun desenharProtocolos() {
        val lista = protLista ?: return
        val store = ProtocoloStore(this)
        val todos = store.listar()
        val geracao = ++protGeracao
        protTotal?.text = getString(R.string.prot_total, todos.size)
        lista.removeAllViews()

        val subtitulos = HashMap<String, TextView>()
        todos.forEachIndexed { i, p ->
            val linha = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dpSet(6), 0, dpSet(6))
            }

            // O Padrão não se move, e nenhum protocolo passa à frente dele.
            // Mover grava só a ordem do lista.json, que é pequeno.
            val sobe = !p.padrao && i > 0 && !todos[i - 1].padrao
            val desce = !p.padrao && i < todos.lastIndex && !todos[i + 1].padrao
            linha.addView(botaoSeta(R.string.seta_cima, R.string.prot_mover_cima, sobe) {
                if (store.mover(p.id, -1)) desenharProtocolos()
            })
            linha.addView(botaoSeta(R.string.seta_baixo, R.string.prot_mover_baixo, desce) {
                if (store.mover(p.id, +1)) desenharProtocolos()
            })

            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = p.nome
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@SettingsActivity, R.color.text_primary))
                textSize = 14f
            })
            if (!p.padrao || p.paginas.isNotEmpty()) {
                val sub = TextView(this).apply {
                    text = ""
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        this@SettingsActivity, R.color.text_secondary))
                    textSize = 11f
                }
                col.addView(sub)
                subtitulos[p.id] = sub
            }
            linha.addView(col)

            linha.addView(android.widget.Button(this).apply {
                setText(R.string.edit_name)
                textSize = 12f
                setOnClickListener {
                    com.radioterapia.ai.protocolo.ProtocoloActivity
                        .abrir(this@SettingsActivity, editarProtocolo, p.id)
                }
            })
            linha.addView(android.widget.Button(this).apply {
                setText(R.string.prot_duplicar)
                textSize = 12f
                isEnabled = p.id !in protDuplicando
                setOnClickListener { v -> duplicarProtocolo(p, v) }
            })
            linha.addView(android.widget.Button(this).apply {
                setText(R.string.rub_excluir)
                textSize = 12f
                if (p.padrao) {
                    visibility = View.INVISIBLE
                } else {
                    setOnClickListener {
                        android.app.AlertDialog.Builder(this@SettingsActivity)
                            .setTitle(getString(R.string.prot_excluir_q, p.nome))
                            .setMessage(R.string.prot_excluir_msg)
                            .setPositiveButton(R.string.rub_excluir) { _, _ ->
                                store.excluir(p.id); desenharProtocolos()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
            })
            lista.addView(linha)
        }

        if (subtitulos.isEmpty()) return
        val aContar = todos.filter { it.id in subtitulos }
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val totais = withContext(Dispatchers.IO) {
                try {
                    val s = ProtocoloStore(app)
                    aContar.associate { it.id to s.contarPaginas(it) }
                } catch (_: Exception) { emptyMap<String, Int>() }
            }
            if (isFinishing || isDestroyed || geracao != protGeracao) return@launch
            totais.forEach { (id, n) ->
                subtitulos[id]?.text = getString(R.string.prot_qtd_paginas, n)
            }
        }
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
    private fun botaoSeta(@androidx.annotation.StringRes glifo: Int,
                          @androidx.annotation.StringRes descricao: Int,
                          ativa: Boolean,
                          aoTocar: () -> Unit): Button =
        android.widget.Button(this).apply {
            setText(glifo)
            contentDescription = getString(descricao)
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                dpSet(48), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            visibility = if (ativa) View.VISIBLE else View.INVISIBLE
            if (ativa) setOnClickListener { aoTocar() }
        }

    /**
     * Duplica e abre a cópia no editor. Duplica-se para editar, então abrir o
     * editor direto poupa um clique e não é passo de confirmação; cancelar o
     * editor mantém a cópia, que já está gravada. A cópia dos PDFs roda em IO,
     * e o botão fica desabilitado enquanto ela corre, para dois toques não
     * fazerem duas cópias.
     */
    private fun duplicarProtocolo(p: ProtocoloStore.Protocolo, botao: View) {
        if (!protDuplicando.add(p.id)) return
        botao.isEnabled = false
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val copia = withContext(Dispatchers.IO) {
                try { ProtocoloStore(app).duplicar(p.id) } catch (_: Exception) { null }
            }
            protDuplicando.remove(p.id)
            if (isFinishing || isDestroyed) return@launch
            desenharProtocolos()
            if (copia == null) {
                Toast.makeText(this@SettingsActivity, R.string.prot_duplicar_falhou,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            com.radioterapia.ai.protocolo.ProtocoloActivity
                .abrir(this@SettingsActivity, editarProtocolo, copia.id)
        }
    }

    /**
     * Tabela da equipe: uma linha por pessoa, com editar e excluir.
     *
     * Montada em codigo e nao num RecyclerView porque vive DENTRO de um grupo
     * das Configuracoes, que ja e um ScrollView — RecyclerView aninhado em
     * scroll perde a reciclagem e ainda briga pelo gesto de rolagem.
     */
    private fun desenharEquipeRubricario() {
        val lista = rubLista ?: return
        val store = com.radioterapia.ai.rubricario.RubricarioStore(this)
        // SO A EQUIPE DO BLOCO SELECIONADO. Listar todas juntas devolveria a
        // tela unica que os blocos vieram desfazer, e o total no rodape passaria
        // a contar gente de outra clinica.
        val pessoas = store.listar(rubBlocoAtual)
        rubTotal?.text = if (pessoas.isEmpty()) getString(R.string.rub_vazio)
                         else getString(R.string.rub_total, pessoas.size)
        lista.removeAllViews()

        val ordem = config.rubricarioCargos.split("\n").map { it.trim() }
            .filter { it.isNotBlank() }
            .map { com.radioterapia.ai.rubricario.RubricarioStore.separarCargo(it).first }

        store.porCargo(ordem, rubBlocoAtual).forEach { (cargo, doCargo) ->
            lista.addView(android.widget.TextView(this).apply {
                text = cargo
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@SettingsActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, dpSet(10), 0, dpSet(2))
            })
            doCargo.forEach { p ->
                val linha = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dpSet(4), 0, dpSet(4))
                }
                linha.addView(android.widget.TextView(this).apply {
                    text = if (p.registro.isBlank()) p.nome else "${p.nome}  ·  ${p.registro}"
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        this@SettingsActivity, R.color.text_primary))
                    textSize = 14f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                // Miniatura da rubrica: confirma de relance que a assinatura
                // guardada e a da pessoa certa, sem abrir o cadastro.
                store.arquivoAssinatura(p)?.let { arq ->
                    linha.addView(android.widget.ImageView(this).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            dpSet(52), dpSet(26)).apply { marginEnd = dpSet(6) }
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        try {
                            setImageBitmap(android.graphics.BitmapFactory.decodeFile(arq.absolutePath))
                        } catch (_: Exception) {}
                    })
                }
                linha.addView(android.widget.Button(this).apply {
                    text = getString(R.string.rub_editar)
                    textSize = 11f
                    setOnClickListener {
                        com.radioterapia.ai.rubricario.RubricarioPessoaActivity
                            .abrir(this@SettingsActivity, editarRubrica, p.id, p.blocoId)
                    }
                })
                linha.addView(android.widget.Button(this).apply {
                    text = getString(R.string.rub_excluir)
                    textSize = 11f
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        this@SettingsActivity, R.color.error_red))
                    setOnClickListener {
                        mostrarDialogPintado(
                            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                                .setTitle(R.string.rub_excluir)
                                .setMessage(getString(R.string.rub_excluir_q, p.nome))
                                .setPositiveButton(R.string.rub_excluir) { _, _ ->
                                    store.excluir(p.id); desenharEquipeRubricario()
                                }
                                .setNegativeButton(R.string.cancel, null),
                            destrutivo = android.content.DialogInterface.BUTTON_POSITIVE,
                            seguro = android.content.DialogInterface.BUTTON_NEGATIVE)
                    }
                })
                lista.addView(linha)
            }
        }
    }

    /**
     * Gera o rubricario SOZINHO e oferece imprimir ou salvar.
     *
     * Reaproveita escolherModoImpressao da BaseActivity — o mesmo dialogo da
     * ficha do paciente, com impressora de rede e pen-drive. Nao ha motivo para
     * esta folha ter um caminho de impressao proprio.
     */
    private fun gerarRubricarioAvulso(apenasVer: Boolean = false) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val pdf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val saida = java.io.File(cacheDir, "RUBRICARIO.pdf")
                    com.radioterapia.ai.pdf.PdfBuilder.gerarRubricarioAvulso(
                        this@SettingsActivity, saida, rubBlocoAtual)
                } catch (_: Exception) { null }
            }
            if (isFinishing || isDestroyed) return@launch
            if (pdf == null) {
                android.widget.Toast.makeText(this@SettingsActivity,
                    R.string.rub_sem_equipe, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            // VER abre no leitor de PDF; GERAR vai direto para a impressao.
            //
            // Sao dois usos diferentes: conferir como a folha vai sair — margem
            // de furacao, tamanho das rubricas, quebra em duas colunas — nao
            // deveria custar uma folha de papel a cada tentativa.
            if (apenasVer) abrirPdfExterno(pdf) else escolherModoImpressao(pdf)
        }
    }

    /** Abre um PDF no leitor padrão do sistema (via FileProvider). */
    private fun abrirPdfExterno(pdf: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", pdf)
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                          android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.pdf_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ================= EXPORTAR / IMPORTAR CONFIGURACOES =================

    private val itensTransfer = com.radioterapia.ai.transfer.PacoteConfig.Item.values()
    private val marcados = mutableSetOf<com.radioterapia.ai.transfer.PacoteConfig.Item>()
    private var avisoPaciente: android.widget.TextView? = null

    /**
     * Monta a lista de itens a partir do enum, e nao de caixas escritas no XML.
     *
     * O enum e a unica lista: item novo aparece aqui e na importacao de uma vez.
     * Com caixas no layout, acrescentar uma categoria exigiria lembrar de tres
     * lugares, e o esquecido seria a importacao — que ninguem testa no dia em
     * que acrescenta.
     */
    private fun montarTransferencia(v: View) {
        val lista = v.findViewById<android.widget.LinearLayout>(R.id.listaTransferItens)
        val chkTudo = v.findViewById<android.widget.CheckBox>(R.id.chkTransferTudo)
        avisoPaciente = v.findViewById(R.id.txtTransferAviso)
        lista.removeAllViews()
        marcados.clear()

        val caixas = mutableListOf<android.widget.CheckBox>()
        var ajustando = false

        itensTransfer.forEach { item ->
            val bloco = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, dpSet(6), 0, dpSet(6))
            }
            val chk = android.widget.CheckBox(this).apply {
                text = getString(item.rotulo)
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@SettingsActivity, R.color.text_primary))
                textSize = 14f
                setOnCheckedChangeListener { _, marcado ->
                    if (marcado) marcados.add(item) else marcados.remove(item)
                    atualizarAvisoPaciente()
                    if (!ajustando) {
                        ajustando = true
                        chkTudo.isChecked = marcados.size == itensTransfer.size
                        ajustando = false
                    }
                }
            }
            caixas.add(chk)
            bloco.addView(chk)
            bloco.addView(android.widget.TextView(this).apply {
                text = getString(item.descricao)
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@SettingsActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(dpSet(32), 0, 0, 0)
            })
            lista.addView(bloco)
        }

        chkTudo.setOnCheckedChangeListener { _, marcado ->
            if (ajustando) return@setOnCheckedChangeListener
            ajustando = true
            caixas.forEach { it.isChecked = marcado }
            ajustando = false
            atualizarAvisoPaciente()
        }

        v.findViewById<Button>(R.id.btnTransferExportar).setOnClickListener {
            if (marcados.isEmpty()) {
                Toast.makeText(this, R.string.tr_nada_marcado, Toast.LENGTH_SHORT).show()
            } else {
                val carimbo = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                    .format(java.util.Date())
                exportarPacoteLauncher.launch("PhotoIDRT_config_$carimbo.zip")
            }
        }
        // A TRANSFERENCIA POR PASTA, no mesmo grupo. Os IDs
        // agora pertencem a group_transferencia.xml, que e o layout declarado
        // por este adicionarGrupo — a regra de R.id por grupo continua valendo.
        v.findViewById<Button>(R.id.btnExportarBase).setOnClickListener {
            modoTransferencia = true
            abrirSeletorPasta(seletorTransferenciaLauncher)
        }
        v.findViewById<Button>(R.id.btnImportarBase).setOnClickListener {
            modoTransferencia = false
            abrirSeletorPasta(seletorTransferenciaLauncher)
        }

        v.findViewById<Button>(R.id.btnTransferImportar).setOnClickListener {
            importarPacoteLauncher.launch(arrayOf(
                com.radioterapia.ai.transfer.PacoteConfig.MIME, "*/*"))
        }
    }

    /**
     * O aviso so aparece quando a selecao leva dado de paciente.
     *
     * Um aviso fixo seria lido como decoracao. Aparecendo no instante em que a
     * caixa e marcada, ele diz respeito ao que a pessoa acabou de escolher.
     */
    private fun atualizarAvisoPaciente() {
        avisoPaciente?.visibility =
            if (marcados.any { it.ehDadoDePaciente }) View.VISIBLE else View.GONE
    }

    private val exportarPacoteLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(com.radioterapia.ai.transfer.PacoteConfig.MIME)
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val selecao = marcados.toSet()
        Toast.makeText(this, R.string.tr_exportando, Toast.LENGTH_SHORT).show()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { saida ->
                        com.radioterapia.ai.transfer.PacoteConfig.exportar(
                            this@SettingsActivity, selecao, saida)
                    } ?: -1
                } catch (e: Throwable) { -1 }
            }
            if (isFinishing || isDestroyed) return@launch
            if (r < 0) Toast.makeText(this@SettingsActivity,
                getString(R.string.tr_erro, ""), Toast.LENGTH_LONG).show()
            else Toast.makeText(this@SettingsActivity,
                getString(R.string.tr_exportado, r), Toast.LENGTH_LONG).show()
        }
    }

    private val importarPacoteLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val resumo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use {
                        com.radioterapia.ai.transfer.PacoteConfig.inspecionar(it)
                    }
                } catch (_: Throwable) { null }
            }
            if (isFinishing || isDestroyed) return@launch
            if (resumo == null) {
                // NAO E UM ZIP: pode ser o JSON que a versao anterior gerava.
                // Recusar seria transformar em lixo os arquivos que as clinicas
                // ja tinham feito — e quem guardou um backup nao tem como saber
                // que o formato mudou.
                if (importarJsonAntigo(uri)) return@launch
                Toast.makeText(this@SettingsActivity, R.string.tr_arquivo_invalido,
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            escolherOQueImportar(uri, resumo)
        }
    }

    /**
     * Mostra o que o arquivo traz ANTES de aplicar, e deixa desmarcar.
     *
     * Importar as cegas e como a configuracao de uma unidade acaba dentro de
     * outra sem ninguem perceber. So os itens que o pacote realmente contem
     * aparecem — oferecer o que nao esta la seria mentir sobre o arquivo.
     */
    private fun escolherOQueImportar(uri: android.net.Uri,
                                     resumo: com.radioterapia.ai.transfer.PacoteConfig.Resumo) {
        val itens = resumo.itens
        if (itens.isEmpty()) {
            Toast.makeText(this, R.string.tr_arquivo_invalido, Toast.LENGTH_LONG).show()
            return
        }
        val rotulos = itens.map { item ->
            val n = resumo.contagens[item] ?: 0
            if (n > 0) "${getString(item.rotulo)}  ($n)" else getString(item.rotulo)
        }.toTypedArray()
        val escolhido = BooleanArray(itens.size) { true }

        val cabecalho = android.widget.TextView(this).apply {
            text = getString(R.string.tr_de_unidade,
                resumo.origem.ifBlank { "—" })
            setPadding(dpSet(20), dpSet(14), dpSet(20), 0)
            textSize = 13f
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tr_conteudo_titulo)
            .setCustomTitle(cabecalho)
            .setMultiChoiceItems(rotulos, escolhido) { _, i, marcado -> escolhido[i] = marcado }
            .setPositiveButton(R.string.next) { _, _ ->
                val selecao = itens.filterIndexed { i, _ -> escolhido[i] }.toSet()
                if (selecao.isEmpty()) {
                    Toast.makeText(this, R.string.tr_nada_marcado, Toast.LENGTH_SHORT).show()
                } else {
                    escolherModoImportacao(uri, selecao)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Somar ou sobrescrever. Somar e o padrao, e a razao esta em PacoteConfig.Modo. */
    private fun escolherModoImportacao(
        uri: android.net.Uri,
        selecao: Set<com.radioterapia.ai.transfer.PacoteConfig.Item>
    ) {
        val opcoes = arrayOf(
            getString(R.string.tr_modo_somar),
            getString(R.string.tr_modo_sobrescrever))
        var escolha = 0
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tr_modo_titulo)
            .setSingleChoiceItems(opcoes, 0) { _, i -> escolha = i }
            .setMessage(R.string.tr_modo_explica)
            .setPositiveButton(R.string.confirm) { _, _ ->
                aplicarPacote(uri, selecao,
                    if (escolha == 0) com.radioterapia.ai.transfer.PacoteConfig.Modo.SOMAR
                    else com.radioterapia.ai.transfer.PacoteConfig.Modo.SOBRESCREVER)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun aplicarPacote(
        uri: android.net.Uri,
        selecao: Set<com.radioterapia.ai.transfer.PacoteConfig.Item>,
        modo: com.radioterapia.ai.transfer.PacoteConfig.Modo
    ) {
        Toast.makeText(this, R.string.tr_importando, Toast.LENGTH_SHORT).show()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use {
                        com.radioterapia.ai.transfer.PacoteConfig.importar(
                            this@SettingsActivity, it, selecao, modo)
                    }
                } catch (_: Throwable) { null }
            }
            if (isFinishing || isDestroyed) return@launch
            if (r == null) {
                Toast.makeText(this@SettingsActivity, getString(R.string.tr_erro, ""),
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(this@SettingsActivity,
                getString(R.string.tr_importado, r.prefs, r.arquivos, r.pulados),
                Toast.LENGTH_LONG).show()

            /*
                DESTINO SEM SENHA MERECE UMA FRASE, nao silencio.

                O pacote leva os destinos de sincronizacao, mas nao a senha —
                ela fica cifrada sob o Keystore e este .zip viaja por e-mail e
                pen-drive. O destino chega desligado, de proposito. Sem dizer
                isso, quem importa abre a lista, ve tudo apagado e conclui que
                a exportacao nao levou nada.
             */
            val semSenha = try {
                com.radioterapia.ai.sync.PerfilStore(this@SettingsActivity)
                    .listar().count { precisaSenha(it) }
            } catch (_: Exception) { 0 }
            if (semSenha > 0) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.sync_profile_title)
                    .setMessage(R.string.sync_importados_sem_senha)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            // recreate() para os campos da tela mostrarem o que acabou de entrar.
            recreate()
        }
    }

    /**
     * Le um arquivo no formato da exportacao ANTERIOR (JSON de preferencias).
     *
     * Devolve `true` se era mesmo um desses e foi aplicado. Nao pergunta somar
     * ou sobrescrever: o formato antigo nao separa itens, entao a unica leitura
     * honesta dele e "aplique tudo", que e o que ele sempre fez.
     */
    private fun importarJsonAntigo(uri: android.net.Uri): Boolean = try {
        val texto = contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        val raiz = org.json.JSONObject(texto)
        if (!raiz.has("valores")) false
        else {
            limparSenhaSeDestinoSmbMudar(raiz)
            val n = config.importarJson(texto)
            val nRub = try {
                raiz.optJSONArray("rubricario")?.let { arr ->
                    com.radioterapia.ai.rubricario.RubricarioStore(this).importarJson(arr)
                } ?: 0
            } catch (_: Exception) { 0 }
            Toast.makeText(this, getString(R.string.tr_importado, n, nRub, 0),
                Toast.LENGTH_LONG).show()
            recreate()
            true
        }
    } catch (_: Throwable) { false }

    /**
     * Tira a senha SMB guardada quando o JSON de preferências traz outro
     * endereço, conta ou pasta SMB — a mesma regra do pacote
     * ([com.radioterapia.ai.transfer.PacoteConfig.mudaDestinoSmb]). Chamar
     * ANTES de aplicar as preferências: entre as duas gravações, uma busca de
     * fotos levaria a senha deste aparelho ao endereço que chegou no arquivo.
     */
    private fun limparSenhaSeDestinoSmbMudar(raiz: org.json.JSONObject) {
        val vindos = raiz.optJSONObject("valores") ?: return
        val locais = getSharedPreferences("config_radioterapia", MODE_PRIVATE).all
        val caduca = vindos.keys().asSequence().any { chave ->
            com.radioterapia.ai.transfer.PacoteConfig
                .mudaDestinoSmb(chave, locais[chave], vindos.opt(chave))
        }
        if (caduca) credentials.limparSenha()
    }

    // ==================== BLOCOS DO RUBRICARIO ====================

    /**
     * Preenche o seletor de equipes e preserva a selecao ao redesenhar.
     *
     * O SELETOR APARECE MESMO COM UMA EQUIPE SO, em vez de se esconder ate a
     * segunda existir: funcao que so aparece depois de ja ter sido usada nao e
     * descoberta por ninguem, e quem tem uma clinica hoje e quem ganha a
     * segunda amanha. Com um bloco, a lista tem uma linha e a rotina e a de
     * sempre — nenhum clique a mais.
     */
    private fun desenharBlocosRubricario() {
        val sp = rubSpBloco ?: return
        val store = com.radioterapia.ai.rubricario.RubricarioStore(this)
        val blocos = store.listarBlocos()
        if (blocos.none { it.id == rubBlocoAtual }) rubBlocoAtual = com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO

        sp.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, blocos.map { it.nome })
        sp.setSelection(blocos.indexOfFirst { it.id == rubBlocoAtual }.coerceAtLeast(0))
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(pai: android.widget.AdapterView<*>?, v: View?,
                                        pos: Int, id: Long) {
                val novo = blocos.getOrNull(pos)?.id ?: return
                if (novo == rubBlocoAtual) return
                rubBlocoAtual = novo
                desenharEquipeRubricario()
            }
            override fun onNothingSelected(pai: android.widget.AdapterView<*>?) {}
        }
    }

    private fun novoBlocoRubricario() {
        pedirNomeDeBloco(getString(R.string.rub_bloco_novo), "") { nome ->
            val store = com.radioterapia.ai.rubricario.RubricarioStore(this)
            val b = com.radioterapia.ai.rubricario.RubricarioStore.Bloco(store.novoIdBloco(), nome, false)
            if (store.salvarBloco(b)) {
                // Ja entra na equipe recem-criada: quem acabou de nomeá-la vai
                // cadastrar gente nela agora, nao daqui a tres telas.
                rubBlocoAtual = b.id
                desenharBlocosRubricario(); desenharEquipeRubricario()
            } else Toast.makeText(this, R.string.export_zip_fail, Toast.LENGTH_LONG).show()
        }
    }

    private fun renomearBlocoRubricario() {
        val store = com.radioterapia.ai.rubricario.RubricarioStore(this)
        val b = store.obterBloco(rubBlocoAtual) ?: return
        pedirNomeDeBloco(getString(R.string.rub_bloco_renomear), b.nome) { nome ->
            if (store.salvarBloco(b.copy(nome = nome))) {
                desenharBlocosRubricario(); desenharEquipeRubricario()
            }
        }
    }

    private fun pedirNomeDeBloco(titulo: String, atual: String, aoConfirmar: (String) -> Unit) {
        val edt = EditText(this).apply {
            setText(atual)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dpSet(16), dpSet(12), dpSet(16), dpSet(12))
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@SettingsActivity, R.color.text_primary))
        }
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(edt)
            .setPositiveButton(R.string.save) { _, _ ->
                val nome = edt.text.toString().trim()
                if (nome.isBlank())
                    Toast.makeText(this, R.string.rub_bloco_falta_nome, Toast.LENGTH_SHORT).show()
                else aoConfirmar(nome)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Exclui a equipe E as pessoas dela, dizendo QUANTAS antes de perguntar.
     *
     * O numero no aviso nao e enfeite: e a diferenca entre apagar uma equipe
     * vazia criada por engano e apagar quinze rubricas colhidas uma a uma, que
     * so se refazem reunindo quinze pessoas de novo.
     */
    private fun excluirBlocoRubricario() {
        val store = com.radioterapia.ai.rubricario.RubricarioStore(this)
        val b = store.obterBloco(rubBlocoAtual) ?: return
        if (b.padrao) {
            Toast.makeText(this, R.string.rub_bloco_padrao_fixo, Toast.LENGTH_LONG).show()
            return
        }
        val n = store.contarNoBloco(b.id)
        mostrarDialogPintado(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.rub_bloco_excluir_q, b.nome))
                .setMessage(getString(R.string.rub_bloco_excluir_msg, n))
                .setPositiveButton(R.string.rub_excluir) { _, _ ->
                    if (store.excluirBloco(b.id)) {
                        rubBlocoAtual = com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO
                        desenharBlocosRubricario(); desenharEquipeRubricario()
                    }
                }
                .setNegativeButton(R.string.cancel, null),
            destrutivo = android.content.DialogInterface.BUTTON_POSITIVE,
            seguro = android.content.DialogInterface.BUTTON_NEGATIVE)
    }

    // ---- transferencia de UMA equipe, em arquivo proprio ----
    //
    // Existe alem da exportacao completa porque as duas respondem a perguntas
    // diferentes: aquela clona o tablet inteiro, esta manda "a equipe da Clinica
    // B" para o tablet da Clinica B sem levar junto impressora, logotipo e a
    // equipe das outras unidades.

    private fun exportarBlocoRubricario() {
        val store = com.radioterapia.ai.rubricario.RubricarioStore(this)
        val b = store.obterBloco(rubBlocoAtual) ?: return
        val carimbo = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val limpo = b.nome.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "equipe" }
        try { exportarBlocoLauncher.launch("rubricario_${limpo}_$carimbo.json") }
        catch (_: Exception) {
            Toast.makeText(this, R.string.prot_sem_seletor, Toast.LENGTH_SHORT).show()
        }
    }

    private val exportarBlocoLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            // CONTEM DADO PESSOAL DA EQUIPE — nome, conselho e a rubrica em
            // imagem. E documento da instituicao, e a descricao ao lado do
            // botao diz isso a quem clica.
            val texto = com.radioterapia.ai.rubricario.RubricarioStore(this).exportarBloco(rubBlocoAtual).toString(2)
            contentResolver.openOutputStream(uri)?.use {
                it.write(texto.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.rub_bloco_exportado, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.err_save, e.message ?: ""),
                Toast.LENGTH_LONG).show()
        }
    }

    private val importarBlocoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val texto = contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val res = com.radioterapia.ai.rubricario.RubricarioStore(this).importarBloco(texto)
            if (res == null) {
                Toast.makeText(this, R.string.rub_bloco_invalido, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            Toast.makeText(this, getString(R.string.rub_bloco_importado, res.first, res.second),
                Toast.LENGTH_LONG).show()
            desenharBlocosRubricario(); desenharEquipeRubricario()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.rub_bloco_invalido, Toast.LENGTH_LONG).show()
        }
    }

    private fun dpSet(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun atualizarStatusConexao() {
        atualizarStatusPendencias()
    }

    private fun atualizarStatusPendencias() {
        val n = com.radioterapia.ai.pending.PendingUploadManager(this).contar()
        txtConnPending?.text = if (n == 0) getString(R.string.no_pending)
            else getString(R.string.pending_count, n)
    }

    private fun atualizarCache() {
        val n = patientCache.listarPacientes().size
        txtCachePatients?.text = getString(R.string.patients_cached, n)
        txtDraftStatus?.text = if (sessionManager.temRascunho()) {
            getString(R.string.draft_active,
                sessionManager.nomePaciente.ifBlank { "(sem nome)" },
                sessionManager.quantidade())
        } else getString(R.string.no_draft)
    }

    private fun atualizarLogoPreview() {
        val alvoLogo = imgLogoPreview ?: return   // view ainda não inflada
        if (logoManager.temLogo()) {
            val arq = logoManager.obterArquivo()
            // Cache-busting: ao substituir o logo no MESMO caminho, o Glide reusaria
            // a imagem em cache. A assinatura por data de modificação + sem cache
            // força recarregar a nova imagem.
            Glide.with(this)
                .load(arq)
                .signature(com.bumptech.glide.signature.ObjectKey(arq?.lastModified() ?: 0L))
                .skipMemoryCache(true)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .into(alvoLogo)
        } else {
            alvoLogo.setImageResource(R.drawable.logo_radioterapia)
        }
    }

    // ============= SALVAR =============

    private fun salvarTudo() {
        // Identidade
        config.nomeClinica = edtCompanyName?.text?.toString()?.trim() ?: ""

        // SMB: bind REMOVIDO, pela MESMA razão que o CSV logo abaixo — era
        // destrutivo, e de um jeito pior, porque apagava credencial.
        //
        // spinnerSmbProtocol, edtSmbDomain, edtSmbUsername, edtSmbPassword,
        // edtSmbHost, edtSmbPort e edtSmbUnc eram declarados e NUNCA
        // atribuídos: viviam no grupo de rede das Configurações, que deixou de
        // existir. Nenhum deles tem `@+id` em layout nenhum, e nunca houve um
        // `findViewById` para eles em commit nenhum deste repositório. Com
        // todos em null, este bloco rodava como
        //
        //     credentials.salvarSenha(null?.text?.toString() ?: "")
        //     config.salvarDestino(0, DestinoSmb(ativo = true, host = "", …))
        //
        // ou seja: APAGAVA a senha cifrada e gravava no destino 0 um servidor
        // ATIVO e vazio, toda vez que alguém salvasse. E não era só o botão
        // Salvar — `testarImpressora()` e `sincronizarAgora()` chamam
        // salvarTudo(), então TESTAR A IMPRESSORA apagava a configuração de
        // rede. A correção de 16/08 tratou o bloco do CSV e parou antes deste.
        //
        // O que sobra: config.smbUsuario, smbProtocolo, smbDominio e
        // credentials.obterSenha() continuam vivos e são lidos por
        // CsvSyncManager e AddPhotoInTreatmentActivity. Quem os grava hoje é a
        // importação de pacote (PacoteConfig.Item.REDE). Não há tela; está em
        // junto com o lado CSV.

        // Backups (grupo pode não estar presente)
        if (backupBindings.size >= 4) for (i in 1..4) {
            val b = backupBindings[i - 1]
            config.salvarDestino(i, DestinoSmb(
                ativo = b.checkBox.isChecked,
                host = b.host.text.toString().trim(),
                porta = b.porta.text.toString().toIntOrNull() ?: 445,
                caminhoUNC = b.unc.text.toString().trim()
            ))
        }

        // CSV: bind REMOVIDO, e não é limpeza cosmética — era destrutivo.
        //
        // Os campos edtCsvFolder, rgCsvHeader, edtColNome, edtColNasc e
        // edtColPront eram declarados e NUNCA atribuídos: viviam no layout
        // group_csv_database.xml, que nenhum `adicionarGrupo` jamais inflou. Com
        // todos em null, esta linha rodava como
        //
        //     config.csvPastaUnc = null?.text?...?.trim() ?: ""
        //
        // ou seja, APAGAVA a pasta do CSV toda vez que alguém salvasse as
        // Configurações — e o mesmo valia para o mapeamento de colunas, zerado
        // a cada salvamento.
        //
        // O que sobra: CsvSyncManager, AppConfig.csvPastaUnc e CsvMapping
        // continuam vivos e são lidos na abertura do app (HomeActivity), mas
        // HOJE NÃO HÁ TELA que permita configurá-los. Enquanto não houver, a
        // importação da base de pacientes não roda: HomeActivity sai cedo
        // quando csvPastaUnc está vazio.
        // Os TRÊS IDENTIFICADORES EXTRA do CSV ficaram de fora daquela correção,
        // e estavam aqui embaixo do comentário que a explica: edtExtraNTitulo e
        // edtExtraNCol são órfãos como os demais, e as chamadas zeravam
        // csvMapping.salvarExtra(N, IdExtra("", 0)) a cada salvamento. Removidas
        // pela mesma razão. CsvMapping segue vivo e lido; só não há quem o
        // configure enquanto a tela não voltar.

        // Impressora
        config.impressoraIp = edtPrinterIp?.text?.toString()?.trim() ?: ""
        config.impressoraNome = edtPrinterName?.text?.toString()?.trim() ?: ""

        // Sincronização (o interruptor mestre já foi gravado no próprio toque)
        com.radioterapia.ai.sync.SyncConfig(this).apply {
            edtSyncIntervalo?.text?.toString()?.toIntOrNull()?.let { intervaloMinutos = it }
            chkSyncGatFoto?.let { gatilhoAoSalvarFoto = it.isChecked }
            chkSyncGatFim?.let { gatilhoAoFinalizar = it.isChecked }
            chkSyncGatAbrir?.let { gatilhoAoAbrir = it.isChecked }
            chkSyncSoWifi?.let { somenteRedeNaoTarifada = it.isChecked }
        }
        if (swSyncMestre != null) com.radioterapia.ai.sync.SyncWorker.reprogramar(this)

        // PDF
        config.pdfParaServidor = true  // sempre salvamos o PDF junto das fotos
        config.pdfLandscape = (rgPdfOrientation?.checkedRadioButtonId == R.id.rbPdfLandscape)
        edtEtiquetaLargura?.text?.toString()?.toIntOrNull()?.let { config.pdfEtiquetaLarguraMm = it }
        edtPdfMargem?.text?.toString()?.toIntOrNull()?.let { config.pdfMargemMm = it }
        edtEtiquetaAltura?.text?.toString()?.toIntOrNull()?.let { config.pdfEtiquetaAlturaMm = it }

        // Formato de data (entrada). O armazenamento segue canonico.
        spinnerFormatoData?.selectedItemPosition?.let { i ->
            com.radioterapia.ai.util.DateUtils.FORMATOS_ENTRADA.getOrNull(i)
                ?.let { config.formatoData = it }
        }

        // Idioma (sem modo auto - sempre manual pelo spinner)
        val idx = spinnerLanguage?.selectedItemPosition ?: 0
        val langSelecionado = idiomasMap.getOrNull(idx)?.first ?: LocaleManager.LANG_PT
        val langAtual = LocaleManager.obterIdiomaAtual(this)
        if (langSelecionado != langAtual) {
            LocaleManager.definirIdioma(this, langSelecionado)
            // Marca que o idioma mudou - será relançado o app na próxima ação
            idiomaMudou = true
        }

        // Pasta local e grade da câmera: bind REMOVIDO, mesma família.
        //
        // edtLocalFolder e chkShowGrid também são órfãos, e também gravavam o
        // default por cima do valor real: pastaBaseLocal voltava para
        // "Pictures" e cameraGrid para `true` a cada salvamento. Ao contrário
        // do SMB, estes dois NÃO viajam no pacote de configuração — a perda era
        // local ao aparelho. config.pastaBaseLocal é lido por
        // TreatmentPhotoFetcher, e config.cameraGrid por MainActivity e
        // AddPhotoInTreatmentActivity; ambos seguem vivos com o valor que já
        // tinham.
    }

    // ============= AÇÕES =============

    /** true = exportar; false = importar (define o que o seletor de pasta fará). */
    private var modoTransferencia = true
    private lateinit var seletorTransferenciaLauncher: ActivityResultLauncher<Intent>

    /** Grava o JSON de configurações onde o usuário escolher (SAF). */
    private val exportarConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            // O RUBRICARIO viaja junto (ver RubricarioStore.exportarJson). Ele
            // nao mora nas preferencias — e arquivo em filesDir — entao precisa
            // ser enxertado aqui; e reunir a equipe para assinar de novo num
            // tablet novo e a parte mais cara de refazer a mao.
            val raiz = org.json.JSONObject(config.exportarJson())
            try {
                raiz.put("rubricario",
                    com.radioterapia.ai.rubricario.RubricarioStore(this).exportarJson())
            } catch (_: Exception) { /* configuracao sai mesmo sem o rubricario */ }
            contentResolver.openOutputStream(uri)?.use { saida ->
                saida.write(raiz.toString(2).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, getString(R.string.cfg_exportado,
                uri.lastPathSegment ?: ""), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.err_save, e.message ?: ""),
                Toast.LENGTH_LONG).show()
        }
    }

    /** Lê um JSON de configurações e aplica. */
    private val importarConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val texto = contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            try {
                limparSenhaSeDestinoSmbMudar(org.json.JSONObject(texto))
            } catch (_: org.json.JSONException) { /* importarJson recusa o mesmo texto */ }
            val n = config.importarJson(texto)
            // Rubricario, quando o arquivo trouxer. ACRESCENTA a equipe que ja
            // existe no tablet — importar a configuracao de outra unidade nao
            // pode apagar quem esta cadastrado aqui.
            val nRub = try {
                org.json.JSONObject(texto).optJSONArray("rubricario")?.let { arr ->
                    com.radioterapia.ai.rubricario.RubricarioStore(this).importarJson(arr)
                } ?: 0
            } catch (_: Exception) { 0 }
            if (n == 0 && nRub == 0) {
                Toast.makeText(this, R.string.cfg_import_falha, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.cfg_importado, n),
                    Toast.LENGTH_LONG).show()
                if (nRub > 0) {
                    Toast.makeText(this, getString(R.string.rub_importadas, nRub),
                        Toast.LENGTH_LONG).show()
                }
                recreate()   // recarrega os campos já com os valores novos
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cfg_import_falha, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportarConfiguracoes() {
        val carimbo = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        exportarConfigLauncher.launch("PhotoIDRT_config_$carimbo.json")
    }

    private fun configurarSeletoresPasta() {
        // Transferência de base entre tablets: uma pasta escolhida pelo usuário
        // recebe (ou fornece) fotos + PDFs + cadastro CSV.
        seletorTransferenciaLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri = result.data?.data ?: return@registerForActivityResult
            persistirPasta(uri)
            executarTransferenciaBase(uri, modoTransferencia)
        }

        seletorPastaFotosLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri = result.data?.data ?: return@registerForActivityResult
            persistirPasta(uri)
            config.pastaFotosUri = uri.toString()
            atualizarLabelsPasta()
            Toast.makeText(this, R.string.folder_chosen_ok, Toast.LENGTH_SHORT).show()
        }
        seletorPastaCsvLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri = result.data?.data ?: return@registerForActivityResult
            persistirPasta(uri)
            config.pastaCsvUri = uri.toString()
            atualizarLabelsPasta()
            Toast.makeText(this, R.string.folder_chosen_ok, Toast.LENGTH_SHORT).show()
        }

        seletorPastaBaseLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri = result.data?.data ?: return@registerForActivityResult
            persistirPasta(uri)
            val caminho = com.radioterapia.ai.util.StorageLocal.treeUriParaCaminho(uri)
            if (caminho.isNullOrBlank()) {
                Toast.makeText(this, R.string.storage_path_error, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            // Guarda a subpasta PhotoID_RT dentro da pasta escolhida (mantém a estrutura).
            val base = if (caminho.endsWith("/PhotoID_RT")) caminho else "$caminho/PhotoID_RT"
            config.pastaBaseCustom = base
            com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
            atualizarLabelArmazenamento()
            atualizarStatusBase()
            Toast.makeText(this, R.string.storage_set_ok, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Exporta ou importa a base COMPLETA (fotos + PDFs + cadastro) para/de uma
     * pasta escolhida — é o caminho para migrar de tablet. Roda em IO com
     * diálogo de progresso; a sincronia de rotina é a do próprio app.
     */
    private fun executarTransferenciaBase(pasta: Uri, exportar: Boolean) {
        val backup = com.radioterapia.ai.backup.BackupManager(this)
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (exportar) R.string.db_export_title else R.string.db_import_title)
            .setMessage(getString(R.string.db_working, 0, 0))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> backup.cancelado = true }
            .create()
        dlg.show()
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val onProg: (com.radioterapia.ai.backup.BackupManager.Progresso) -> Unit = { p ->
                        runOnUiThread {
                            dlg.setMessage(getString(R.string.db_working, p.atual, p.total))
                        }
                    }
                    // meses = 0 → base inteira (migração de tablet leva tudo).
                    if (exportar) backup.exportar(pasta, 0, onProg)
                    else backup.importar(pasta, 0, onProg)
                } catch (e: Exception) {
                    com.radioterapia.ai.backup.BackupManager.Resultado(
                        false, 0, 0, e.message ?: "erro")
                }
            }
            dlg.dismiss()
            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(if (exportar) R.string.db_export_title else R.string.db_import_title)
                .setMessage(
                    if (res.sucesso) getString(R.string.db_transfer_done, res.pacientes, res.arquivos)
                    else res.mensagem)
                .setPositiveButton(R.string.ok, null)
                .show()
            atualizarStatusBase()
        }
    }

    /** Abre o seletor de pastas do sistema (explorador + criar pasta). */
    private fun abrirSeletorPasta(launcher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                     Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            // Abre o seletor já na RAIZ do armazenamento interno.
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"))
        }
        launcher.launch(intent)
    }

    // ===== Base de dados: pasta de armazenamento única (raiz) + ações por período =====

    private fun atualizarStatusBase() {
        val total = try { com.radioterapia.ai.patient.PatientCache(this).totalPacientes() } catch (_: Exception) { 0 }
        txtDbStatus?.text = getString(R.string.db_status, total)
    }

    override fun onResume() {
        super.onResume()
        // Ao voltar da tela de permissão do sistema, reflete a pasta/status atuais.
        if (txtBackupFolder != null) { atualizarLabelArmazenamento(); atualizarStatusBase() }
        // A edição de um destino acontece em outra tela: sem redesenhar, voltar
        // de lá mostraria o nome e o erro antigos, e a pessoa concluiria que a
        // alteração não foi salva.
        if (listaSyncPerfis != null) { desenharPerfisSync(); atualizarEstadoSync() }
    }

    /** Mostra a pasta de armazenamento (raiz PhotoID_RT) automaticamente. */
    private fun atualizarLabelArmazenamento() {
        txtBackupFolder?.text = com.radioterapia.ai.util.StorageLocal.caminhoLegivel(this)
        txtBackupAviso?.visibility =
            if (com.radioterapia.ai.util.StorageLocal.emPastaPrivada(this)) View.VISIBLE
            else View.GONE
    }

    /** Período (meses) do seletor de remoção; 0 = todos. */
    private fun mesesDoGrupo(rg: android.widget.RadioGroup?): Int = when (rg?.checkedRadioButtonId) {
        R.id.rbRm1 -> 1
        R.id.rbRm6 -> 6
        R.id.rbRm12 -> 12
        else -> 0
    }

    /** Remove casos: mais antigos que N meses, ou TODOS se período = "todos". */
    private fun limparCacheAntigo() {
        val meses = mesesDoGrupo(rgRemovePeriodo)
        val backup = com.radioterapia.ai.backup.BackupManager(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val previa = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backup.previaLimpeza(meses) }
            if (previa.pacientes == 0) {
                Toast.makeText(this@SettingsActivity, R.string.cache_nothing, Toast.LENGTH_LONG).show()
                return@launch
            }
            val mb = previa.bytes / (1024.0 * 1024.0)
            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle("⚠ " + getString(R.string.remove_old_button))
                .setMessage(getString(R.string.cache_confirm, previa.pacientes, previa.arquivos, mb, textoPeriodo(meses))
                    + "\n\n" + getString(R.string.cache_confirm_warning))
                .setCancelable(false)
                .setPositiveButton(R.string.cache_confirm_delete) { _, _ ->
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backup.limparAntigos(meses) }
                        atualizarStatusBase()
                        Toast.makeText(this@SettingsActivity,
                            getString(R.string.cache_done, res.pacientes, res.arquivos), Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun textoPeriodo(meses: Int): String =
        if (meses <= 0) getString(R.string.period_all) else getString(R.string.period_n_months, meses)

    /** Mantém a permissão de leitura/escrita na pasta entre reinícios do app. */
    private fun persistirPasta(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* algumas pastas não permitem persistir; segue */ }
    }

    /** Mostra um caminho legível da pasta escolhida (ou aviso de padrão). */
    private fun atualizarLabelsPasta() {
        txtPastaFotos?.text = nomeLegivelPasta(config.pastaFotosUri)
        txtPastaCsv?.text = nomeLegivelPasta(config.pastaCsvUri)
    }

    private fun nomeLegivelPasta(uriStr: String): String {
        if (uriStr.isBlank()) return getString(R.string.folder_not_set)
        return try {
            val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(uriStr))
            doc?.name ?: Uri.parse(uriStr).lastPathSegment ?: uriStr
        } catch (_: Exception) { uriStr }
    }

    private fun configurarSeletorImagem() {
        seletorImagemLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri = result.data?.data ?: return@registerForActivityResult
            // Copia para arquivo temporário e abre o editor (zoom/arrastar/girar/cortar),
            // com a proporção da área do logo no PDF (~2.2:1), igual ao onboarding.
            try {
                val tmp = java.io.File(cacheDir, "logo_crop_settings.jpg")
                contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                val it2 = Intent(this, com.radioterapia.ai.crop.CropActivity::class.java)
                it2.putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_PATH, tmp.absolutePath)
                it2.putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_ASPECT, 2.2f)
                cropLogoLauncher.launch(it2)
            } catch (_: Exception) {
                // fallback: salva direto se não der para editar
                if (logoManager.salvarLogo(uri)) { atualizarLogoPreview(); Toast.makeText(this, R.string.logo_saved, Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, R.string.error, Toast.LENGTH_LONG).show()
            }
        }

        cropLogoLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val tmp = java.io.File(cacheDir, "logo_crop_settings.jpg")
            if (tmp.exists() && logoManager.salvarLogo(android.net.Uri.fromFile(tmp))) {
                atualizarLogoPreview()
                Toast.makeText(this, R.string.logo_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun abrirSeletorImagem() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        seletorImagemLauncher.launch(intent)
    }

    private fun confirmarRemoverLogo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_logo)
            .setMessage(R.string.confirm_remove_logo)
            .setPositiveButton(R.string.remove_logo) { _, _ ->
                logoManager.removerLogo()
                atualizarLogoPreview()
                Toast.makeText(this, R.string.logo_removed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }


    private fun confirmarRefazerOnboarding() {
        AlertDialog.Builder(this)
            .setTitle(R.string.redo_onboarding)
            .setMessage(R.string.redo_onboarding_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                com.radioterapia.ai.wizard.WizardActivity.resetar(this)
                val intent = Intent(this, com.radioterapia.ai.wizard.WizardActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmarRestaurarPadroes() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_all)
            .setMessage(R.string.confirm_restore)
            .setPositiveButton(R.string.restore_all) { _, _ ->
                config.limparTudo()
                credentials.salvarSenha("")
                logoManager.removerLogo()
                LocaleManager.definirAuto(this, true)
                Toast.makeText(this, R.string.success, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }



    private fun testarImpressora() {
        salvarTudo()
        if (config.impressoraIp.isBlank()) {
            Toast.makeText(this, R.string.printer_ip, Toast.LENGTH_SHORT).show()
            return
        }
        txtPrinterTestResult?.visibility = View.VISIBLE
        txtPrinterTestResult?.text = getString(R.string.testing)
        /*
            RESULTADO DO TESTE: fundo da escada tonal, texto de primeiro plano.

            Antes o fundo era pastel (#FFFDE7 / #C8E6C9 / #FFCDD2) e o texto
            herdava text_primary (#FFFFFF): 1,07:1 no amarelo, 1,34:1 no verde,
            1,41:1 no vermelho. Nao era contraste baixo — era texto invisivel, e
            justamente a frase que o KDoc desta tela chama de centro dela. De
            quebra, amarelo e vermelho pastel como estado de sistema e o que a
            Regra do Alerta Clinico proibe.
         */
        txtPrinterTestResult?.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bg_elevated))
        txtPrinterTestResult?.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))

        CoroutineScope(Dispatchers.Main).launch {
            val resultado = withContext(Dispatchers.IO) {
                PrinterClient(config.impressoraIp).testarConexao()
            }
            if (resultado.sucesso) {
                txtPrinterTestResult?.text = getString(R.string.printer_ok, resultado.protocoloUsado)
                txtPrinterTestResult?.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bg_elevated))
                txtPrinterTestResult?.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.confirm_green))
            } else {
                txtPrinterTestResult?.text = getString(R.string.printer_fail, resultado.mensagem)
                txtPrinterTestResult?.setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bg_elevated))
                txtPrinterTestResult?.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.error_red_fg))
            }
        }
    }

    private fun atualizarStatusCsv() {
        val ts = config.ultimoSyncCsv
        if (ts > 0) {
            val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
            txtCsvLastSync?.text =
                getString(R.string.cfg_last_sync, fmt.format(java.util.Date(ts)))
        } else {
            txtCsvLastSync?.text = getString(R.string.hc_never_synced)
        }
    }



    private data class BackupBinding(
        val checkBox: CheckBox,
        val grupo: LinearLayout,
        val host: EditText,
        val porta: EditText,
        val unc: EditText
    )
}
