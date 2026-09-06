package com.radioterapia.ai.wizard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.HomeActivity
import com.radioterapia.ai.R
import com.radioterapia.ai.branding.LogoManager
import com.radioterapia.ai.csv.CsvMapping
import com.radioterapia.ai.i18n.LocaleManager
import com.radioterapia.ai.security.CredentialStore

/**
 * Wizard de primeira execução. 7 passos navegando com Próximo/Anterior.
 *
 * 1. Boas-vindas
 * 2. Idioma (PT/EN/ES)
 * 3. Logo da clínica + nome
 * 4. SMB (host, share, usuário, senha)
 * 5. CSV (pasta + tem cabeçalho?)
 * 6. Mapeamento de colunas (nome, nasc, prontuário)
 * 7. Pronto — abre HomeActivity
 *
 * O wizard é disparado uma vez via flag em SharedPreferences.
 * Pode ser pulado a qualquer momento (botão "Pular tudo" → vai pra HomeActivity, marca como concluído).
 */
class WizardActivity : com.radioterapia.ai.BaseActivity() {

    /** Wizard tem header próprio - não usa toolbar da BaseActivity. */
    override fun mostrarToolbar(): Boolean = false

    private lateinit var config: AppConfig
    private lateinit var credentials: CredentialStore
    private lateinit var mapping: CsvMapping
    private lateinit var logoManager: LogoManager

    private lateinit var containerStep: LinearLayout
    private lateinit var btnAnterior: Button
    private lateinit var btnProximo: Button
    private lateinit var btnPular: Button
    private lateinit var txtPasso: TextView

    private var passoAtual: Int = 1
    private val totalPassos = 6

    // Estados temporários (consolidados ao final em onPasso7Concluir)
    private var idiomaSelecionado: String = "pt"
    private var nomeClinicaTemp: String = ""
    private var logoUriTemp: android.net.Uri? = null
    private var csvTemCabecalhoTemp: Boolean = true
    private var colNomeTemp: Int = 0
    private var colNascTemp: Int = 0
    private var colProntTemp: Int = 0

    private lateinit var pickLogoLauncher: ActivityResultLauncher<Intent>
    private lateinit var cropLogoLauncher: ActivityResultLauncher<Intent>
    private lateinit var pickPastaFotosLauncher: ActivityResultLauncher<Intent>
    private lateinit var pickPastaCsvLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        config = AppConfig(this)
        credentials = CredentialStore(this)
        mapping = CsvMapping(this)
        logoManager = LogoManager(this)

        containerStep = findViewById(R.id.wizardStepContainer)
        btnAnterior = findViewById(R.id.btnWizardAnterior)
        btnProximo = findViewById(R.id.btnWizardProximo)
        btnPular = findViewById(R.id.btnWizardPular)
        txtPasso = findViewById(R.id.txtWizardPasso)

        // Pré-popula com valores existentes (caso o wizard seja reaberto)
        idiomaSelecionado = LocaleManager.obterIdiomaConfigurado(this)
        nomeClinicaTemp = config.nomeClinica
        csvTemCabecalhoTemp = config.csvTemCabecalho
        colNomeTemp = mapping.colunaNome
        colNascTemp = mapping.colunaNascimento
        colProntTemp = mapping.colunaProntuario

        pickLogoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            // Copia para um arquivo temporário e abre o recorte (zoom/arrasto) com a
            // proporção da área do logo no PDF (~2.2:1), para encaixar direitinho.
            try {
                val tmp = java.io.File(cacheDir, "logo_crop_tmp.jpg")
                contentResolver.openInputStream(uri)?.use { inp ->
                    tmp.outputStream().use { inp.copyTo(it) }
                }
                val it = Intent(this, com.radioterapia.ai.crop.CropActivity::class.java)
                it.putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_PATH, tmp.absolutePath)
                it.putExtra(com.radioterapia.ai.crop.CropActivity.EXTRA_ASPECT, 2.2f)
                cropLogoLauncher.launch(it)
            } catch (_: Exception) {
                // fallback: usa a imagem sem recorte
                logoUriTemp = uri
                mostrarPasso(2)  // volta ao passo do logo p/ ver a miniatura
            }
        }

        cropLogoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val tmp = java.io.File(cacheDir, "logo_crop_tmp.jpg")
            if (tmp.exists()) {
                logoUriTemp = android.net.Uri.fromFile(tmp)
                mostrarPasso(2)  // volta ao passo do logo p/ ver a miniatura
            }
        }

        pickPastaFotosLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            persistirPasta(uri)
            val caminho = com.radioterapia.ai.util.StorageLocal.treeUriParaCaminho(uri)
            if (!caminho.isNullOrBlank()) {
                val base = if (caminho.endsWith("/PhotoID_RT")) caminho else "$caminho/PhotoID_RT"
                config.pastaBaseCustom = base
                com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
            }
            mostrarPasso(3)
        }
        pickPastaCsvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            persistirPasta(uri); config.pastaCsvUri = uri.toString()
            mostrarPasso(totalPassos)
        }

        btnAnterior.setOnClickListener {
            if (passoAtual > 1) {
                salvarPassoAtual()
                mostrarPasso(passoAtual - 1)
            }
        }

        btnProximo.setOnClickListener {
            if (validarPasso()) {
                salvarPassoAtual()
                if (passoAtual < totalPassos) mostrarPasso(passoAtual + 1)
                else concluir()
            }
        }

        btnPular.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.wiz_skip_title))
                .setMessage(getString(R.string.wiz_skip_message))
                .setPositiveButton(R.string.confirm) { _, _ ->
                    salvarPassoAtual()  // não perde o que já foi preenchido
                    marcarConcluido()
                    abrirHome()
                }
                .setNegativeButton(R.string.cancel, null).show()
        }

        mostrarPasso(1)
    }

    private fun mostrarPasso(numero: Int) {
        passoAtual = numero
        txtPasso.text = getString(R.string.wiz_step_counter, numero, totalPassos)
        btnAnterior.visibility = if (numero == 1) View.GONE else View.VISIBLE
        btnProximo.text = if (numero == totalPassos) getString(R.string.finish) else getString(R.string.next)

        containerStep.removeAllViews()
        when (numero) {
            1 -> mostrarPasso1()
            2 -> mostrarPasso3()
            3 -> mostrarPasso4()
            4 -> mostrarPassoImpressora()
            5 -> mostrarPassoPdfEtiqueta()
            6 -> mostrarPasso7()
        }
    }

    private fun mostrarPasso1() {
        val v = layoutInflater.inflate(R.layout.wizard_step_1, containerStep, true)
        // Versão / build / ID do dispositivo (mesmos dados da aba Sobre)
        try {
            val versao = com.radioterapia.ai.BuildConfig.VERSION_NAME
            val build = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            @Suppress("HardwareIds")
            val deviceId = (android.provider.Settings.Secure.getString(contentResolver,
                android.provider.Settings.Secure.ANDROID_ID) ?: "unknown").take(12)
            v.findViewById<TextView>(R.id.txtWizVersion)?.text = getString(R.string.about_version, versao)
            v.findViewById<TextView>(R.id.txtWizBuild)?.text = getString(R.string.about_build, build)
            v.findViewById<TextView>(R.id.txtWizDevice)?.text = getString(R.string.about_device_id, deviceId)
        } catch (_: Exception) {}
    }


    private fun mostrarPasso3() {
        val v = layoutInflater.inflate(R.layout.wizard_step_3, containerStep, true)
        val edt = v.findViewById<EditText>(R.id.edtWizClinica)
        val img = v.findViewById<ImageView>(R.id.imgWizLogo)
        val semLogo = v.findViewById<View>(R.id.txtWizSemLogo)
        val btnEscolher = v.findViewById<Button>(R.id.btnWizEscolherLogo)

        edt.setText(nomeClinicaTemp)
        // Equipe da clínica: médicos e equipamentos (replicados nas Configurações)
        v.findViewById<EditText>(R.id.edtWizMedicos).setText(config.timeoutMedicos)
        v.findViewById<EditText>(R.id.edtWizEquipamentos).setText(config.equipamentos)
        var temLogo = false
        val uriLogo = logoUriTemp        // cópia local: permite smart cast
        if (uriLogo != null) {
            try {
                val bm = android.graphics.BitmapFactory.decodeStream(
                    contentResolver.openInputStream(uriLogo))
                img.setImageBitmap(bm)
                temLogo = true
            } catch (_: Exception) {}
        } else if (logoManager.temLogo()) {
            img.setImageBitmap(logoManager.obterBitmap())
            temLogo = true
        }
        img.visibility = if (temLogo) View.VISIBLE else View.GONE
        semLogo.visibility = if (temLogo) View.GONE else View.VISIBLE
        btnEscolher.setOnClickListener {
            // Preserva o nome digitado (o crop recria este passo ao voltar)
            nomeClinicaTemp = v.findViewById<EditText>(R.id.edtWizClinica)
                .text.toString().trim()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickLogoLauncher.launch(intent)
        }
    }

    private fun mostrarPasso4() {
        val v = layoutInflater.inflate(R.layout.wizard_step_4, containerStep, true)
        v.findViewById<TextView>(R.id.txtWizPastaFotos).text =
            com.radioterapia.ai.util.StorageLocal.caminhoLegivel(this)
        // Manter padrão sugerido: usa PhotoID_RT na raiz (StorageLocal) e avança.
        v.findViewById<Button>(R.id.btnWizPastaPadrao).setOnClickListener {
            if (!com.radioterapia.ai.util.StorageLocal.temAcessoTotal()) {
                // Sem "Acesso a todos os arquivos" não dá para criar PhotoID_RT na raiz.
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
            config.pastaBaseCustom = ""  // vazio = padrão (raiz via StorageLocal)
            com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
            mostrarPasso(passoAtual + 1)
        }
        v.findViewById<Button>(R.id.btnWizEscolherPastaFotos).setOnClickListener {
            abrirSeletorPasta(pickPastaFotosLauncher)
        }
    }


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

    private fun persistirPasta(uri: android.net.Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    private fun nomeLegivelPasta(uriStr: String): String {
        if (uriStr.isBlank()) return getString(R.string.folder_not_set)
        return try {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(this, android.net.Uri.parse(uriStr))?.name
                ?: android.net.Uri.parse(uriStr).lastPathSegment ?: uriStr
        } catch (_: Exception) { uriStr }
    }

    private fun mostrarPassoImpressora() {
        val v = layoutInflater.inflate(R.layout.wizard_step_printer, containerStep, true)
        v.findViewById<EditText>(R.id.edtWizPrinterIp).setText(config.impressoraIp)
        v.findViewById<EditText>(R.id.edtWizPrinterName).setText(config.impressoraNome)
        when (config.printerDuplexMode) {
            "long" -> v.findViewById<android.widget.RadioButton>(R.id.rbWizDuplexLong).isChecked = true
            "short" -> v.findViewById<android.widget.RadioButton>(R.id.rbWizDuplexShort).isChecked = true
            else -> v.findViewById<android.widget.RadioButton>(R.id.rbWizDuplexOff).isChecked = true
        }
    }

    /** Passo novo: orientação do PDF + espaço da etiqueta física. */
    private fun mostrarPassoPdfEtiqueta() {
        val v = layoutInflater.inflate(R.layout.wizard_step_pdf, containerStep, true)
        if (config.pdfLandscape)
            v.findViewById<android.widget.RadioButton>(R.id.rbWizPdfLand).isChecked = true
        else
            v.findViewById<android.widget.RadioButton>(R.id.rbWizPdfPort).isChecked = true
        v.findViewById<android.widget.CheckBox>(R.id.chkWizUsarEtiqueta).isChecked = config.pdfUsarEtiqueta
        v.findViewById<EditText>(R.id.edtWizEtqLarg).setText(config.pdfEtiquetaLarguraMm.toString())
        v.findViewById<EditText>(R.id.edtWizEtqAlt).setText(config.pdfEtiquetaAlturaMm.toString())
        v.findViewById<android.widget.CheckBox>(R.id.chkWizTimeOut).isChecked = config.pdfIncluiTimeOut
        v.findViewById<android.widget.CheckBox>(R.id.chkWizRubricario).isChecked = config.rubricarioAtivo
    }


    private fun mostrarPasso7() {
        val v = layoutInflater.inflate(R.layout.wizard_step_7, containerStep, true)
        val resumo = v.findViewById<TextView>(R.id.txtWizResumo)
        val padrao = getString(R.string.suggested_default_short)
        val sb = StringBuilder()
        sb.append("Idioma: ${com.radioterapia.ai.i18n.LocaleManager.nomeDoIdioma(idiomaSelecionado)}\n")
        sb.append("Clínica: ${nomeClinicaTemp.ifBlank { config.nomeClinica.ifBlank { "(não definido)" } }}\n")
        sb.append("Pasta fotos: ${if (config.pastaFotosUri.isBlank()) padrao else nomeLegivelPasta(config.pastaFotosUri)}\n")
        sb.append("Impressora: ${config.impressoraIp.ifBlank { "(não definida)" }}\n")
        val dup = when (config.printerDuplexMode) {
            "long" -> getString(R.string.duplex_long)
            "short" -> getString(R.string.duplex_short)
            else -> getString(R.string.duplex_off)
        }
        sb.append("Impressão: $dup\n")
        sb.append("PDF: ${getString(if (config.pdfLandscape) R.string.pdf_landscape else R.string.pdf_portrait)}")
        if (config.pdfUsarEtiqueta)
            sb.append(" • Etiqueta ${config.pdfEtiquetaLarguraMm}×${config.pdfEtiquetaAlturaMm} mm")
        if (config.pdfIncluiTimeOut) sb.append(" • Time-Out incluído")
        resumo.text = sb.toString()
    }

    private fun salvarPassoAtual() {
        when (passoAtual) {
            2 -> {
                nomeClinicaTemp = containerStep.findViewById<EditText>(R.id.edtWizClinica)?.text?.toString()?.trim() ?: ""
                // Persiste imediatamente: robusto mesmo se o usuário usar "Pular tudo" depois.
                if (nomeClinicaTemp.isNotBlank()) config.nomeClinica = nomeClinicaTemp
                containerStep.findViewById<EditText>(R.id.edtWizMedicos)?.let {
                    config.timeoutMedicos = it.text.toString()
                }
                containerStep.findViewById<EditText>(R.id.edtWizEquipamentos)?.let {
                    config.equipamentos = it.text.toString()
                }
                logoUriTemp?.let { logoManager.salvarLogo(it) }
            }
            3 -> {
                // Mesmo sem interação nos botões: garante o acesso à pasta padrão
                if (config.pastaBaseCustom.isBlank() &&
                    !com.radioterapia.ai.util.StorageLocal.temAcessoTotal()) {
                    com.radioterapia.ai.util.StorageLocal.pedirAcessoTotal(this)
                } else {
                    com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
                }
                // Pasta das fotos é salva no momento da escolha (launcher). Nada aqui.
            }
            4 -> {
                // Impressora: persiste imediatamente (robusto a "Pular tudo")
                val ip = containerStep.findViewById<EditText>(R.id.edtWizPrinterIp)?.text?.toString()?.trim() ?: ""
                val nomeImp = containerStep.findViewById<EditText>(R.id.edtWizPrinterName)?.text?.toString()?.trim() ?: ""
                if (ip.isNotBlank()) config.impressoraIp = ip
                if (nomeImp.isNotBlank()) config.impressoraNome = nomeImp
                containerStep.findViewById<android.widget.RadioGroup>(R.id.rgWizDuplex)?.let { rg ->
                    config.printerDuplexMode = when (rg.checkedRadioButtonId) {
                        R.id.rbWizDuplexLong -> "long"
                        R.id.rbWizDuplexShort -> "short"
                        else -> "simplex"
                    }
                }
            }
            5 -> {
                // PDF & etiqueta: persiste imediatamente
                containerStep.findViewById<android.widget.RadioButton>(R.id.rbWizPdfLand)?.let {
                    config.pdfLandscape = it.isChecked
                }
                containerStep.findViewById<android.widget.CheckBox>(R.id.chkWizUsarEtiqueta)?.let {
                    config.pdfUsarEtiqueta = it.isChecked
                }
                containerStep.findViewById<EditText>(R.id.edtWizEtqLarg)?.text?.toString()
                    ?.toIntOrNull()?.let { config.pdfEtiquetaLarguraMm = it }
                containerStep.findViewById<EditText>(R.id.edtWizEtqAlt)?.text?.toString()
                    ?.toIntOrNull()?.let { config.pdfEtiquetaAlturaMm = it }
                containerStep.findViewById<android.widget.CheckBox>(R.id.chkWizRubricario)?.let {
                    config.rubricarioAtivo = it.isChecked
                }
                containerStep.findViewById<android.widget.CheckBox>(R.id.chkWizTimeOut)?.let {
                    config.pdfIncluiTimeOut = it.isChecked
                }
            }
        }
    }

    private fun validarPasso(): Boolean {
        // Todos os passos são opcionais (idioma tem default; pastas/CSV/mapeamento
        // podem ser definidos depois nas Configurações).
        return true
    }

    private fun concluir() {
        salvarPassoAtual()

        // Aplica idioma
        LocaleManager.definirIdiomaManual(this, idiomaSelecionado)

        // Aplica nome clínica
        config.nomeClinica = nomeClinicaTemp

        // Aplica logo
        logoUriTemp?.let { logoManager.salvarLogo(it) }

        // Pasta local das fotos já foi salva ao escolher. Envio/recebimento
        // à rede é responsabilidade do FolderSync. Cada tablet tem sua própria
        // base local (exportável/importável em Configurações).

        marcarConcluido()
        abrirHome()
    }

    private fun marcarConcluido() {
        getSharedPreferences("wizard", MODE_PRIVATE).edit().putBoolean("done", true).apply()
    }

    private fun abrirHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    companion object {
        fun jaConcluido(ctx: android.content.Context): Boolean {
            return ctx.getSharedPreferences("wizard", MODE_PRIVATE).getBoolean("done", false)
        }

        /** Marca o onboarding como não-concluído, para refazê-lo (via Configurações). */
        fun resetar(ctx: android.content.Context) {
            ctx.getSharedPreferences("wizard", MODE_PRIVATE).edit().putBoolean("done", false).apply()
        }
    }
}
