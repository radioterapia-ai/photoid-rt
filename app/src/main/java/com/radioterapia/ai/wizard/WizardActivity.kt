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
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.HomeActivity
import com.radioterapia.ai.R
import com.radioterapia.ai.branding.LogoManager
import com.radioterapia.ai.csv.CsvMapping
import com.radioterapia.ai.i18n.LocaleManager
import com.radioterapia.ai.security.CredentialStore
import kotlinx.coroutines.launch

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

    /**
     * SETE desde 14/09/2026 — o passo 2 passou a oferecer importar a
     * configuracao de outro tablet.
     *
     * O numero do passo e absoluto em quatro lugares independentes: aqui, no
     * `when` de [mostrarPasso], no `when` de [salvarPassoAtual] e nos
     * `mostrarPasso(n)` cravados dentro dos launchers. Os nomes das funcoes NAO
     * acompanham a ordem (`mostrarPasso3` desenha o passo 3 por coincidencia,
     * `mostrarPasso7` desenha o 7) porque um passo de idioma foi removido sem
     * renomear o resto. Entao o `when` de [mostrarPasso] e a UNICA fonte de
     * verdade da ordem — conferir por ali, nunca pelo nome da funcao.
     */
    private val totalPassos = 7

    // Estados temporários (consolidados ao final em onPasso7Concluir)
    private var idiomaSelecionado: String = "pt"
    private var nomeClinicaTemp: String = ""
    private var logoUriTemp: android.net.Uri? = null

    /** Resumo da ultima importacao, para o passo 2 nao esquecer o que fez. */
    private var ultimoImportado: String = ""
    private var csvTemCabecalhoTemp: Boolean = true
    private var colNomeTemp: Int = 0
    private var colNascTemp: Int = 0
    private var colProntTemp: Int = 0

    private lateinit var pickLogoLauncher: ActivityResultLauncher<Intent>
    private lateinit var cropLogoLauncher: ActivityResultLauncher<Intent>
    private lateinit var importarPacoteWizard: ActivityResultLauncher<Array<String>>
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
                mostrarPasso(3)  // volta ao passo do logo p/ ver a miniatura
            }
        }

        importarPacoteWizard = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            aplicarPacoteNoWizard(uri)
        }

        cropLogoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val tmp = java.io.File(cacheDir, "logo_crop_tmp.jpg")
            if (tmp.exists()) {
                logoUriTemp = android.net.Uri.fromFile(tmp)
                mostrarPasso(3)  // volta ao passo do logo p/ ver a miniatura
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
            mostrarPasso(4)
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
            2 -> mostrarPassoImportar()
            3 -> mostrarPasso3()
            4 -> mostrarPasso4()
            5 -> mostrarPassoImpressora()
            6 -> mostrarPassoPdfEtiqueta()
            7 -> mostrarPasso7()
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


    /**
     * PASSO 2 — trazer a configuracao de outro tablet.
     *
     * Fica no COMECO porque e aqui que ele poupa trabalho: quem esta preparando
     * o segundo tablet de um servico importa e pula os passos seguintes ja
     * preenchidos. Descobrir isso depois de digitar tudo a mao nao adianta nada.
     *
     * AS FOTOS FICAM DE FORA, e essa e a decisao que menos se adivinha lendo o
     * codigo. Neste ponto a pasta de armazenamento ainda nao existe: ela so e
     * escolhida no passo 4, e a permissao "Acesso a todos os arquivos" so e
     * pedida la. [StorageLocal.base] sem nenhuma das duas cai no diretorio
     * privado do app — que o Android APAGA na desinstalacao e que o proprio app
     * deixa de olhar assim que a pasta for definida. O acervo inteiro iria para
     * um lugar errado sem lancar excecao nenhuma. Quem quiser as fotos importa
     * de novo em Configuracoes, depois da pasta escolhida.
     *
     * NAO PERGUNTA SOMAR OU SUBSTITUIR. Num tablet que esta sendo configurado
     * agora nao ha nada com que fundir, entao a pergunta nao tem resposta errada
     * — e confirmacao que pode ser inferida e exatamente o que a regra de
     * produto proibe acrescentar.
     */
    private fun mostrarPassoImportar() {
        val v = layoutInflater.inflate(R.layout.wizard_step_import, containerStep, true)
        val txtResultado = v.findViewById<TextView>(R.id.txtWizImportResultado)
        txtResultado.text = ultimoImportado
        txtResultado.visibility = if (ultimoImportado.isBlank()) View.GONE else View.VISIBLE

        v.findViewById<Button>(R.id.btnWizImportar).setOnClickListener {
            try {
                importarPacoteWizard.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.prot_sem_seletor, Toast.LENGTH_SHORT).show()
            }
        }
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
        sb.append(" • Etiqueta ${config.pdfEtiquetaLarguraMm}×${config.pdfEtiquetaAlturaMm} mm")
        if (config.pdfIncluiTimeOut) sb.append(" • Time-Out incluído")
        resumo.text = sb.toString()
    }

    /**
     * Le o pacote e aplica, sem os dois dialogos que as Configuracoes mostram.
     *
     * TUDO MENOS AS FOTOS — o motivo esta no KDoc de [mostrarPassoImportar]. E
     * SEMPRE em modo SOMAR: substituir so faz diferenca quando ha algo do outro
     * lado, e aqui nao ha.
     *
     * Se o arquivo nao for um pacote valido, [PacoteConfig.inspecionar] devolve
     * nulo e a tela diz isso — sem tentar o formato JSON antigo de proposito.
     * Aquele caminho aplica o bloco de preferencias INTEIRO, inclusive os
     * caminhos de pasta do tablet de origem, e no passo 2 isso configuraria o
     * armazenamento com um caminho que nao existe aqui.
     */
    private fun aplicarPacoteNoWizard(uri: android.net.Uri) {
        val ctx = this
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val aplicado = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val resumo = contentResolver.openInputStream(uri)?.use {
                        com.radioterapia.ai.transfer.PacoteConfig.inspecionar(it)
                    } ?: return@withContext null
                    val selecao = resumo.itens.filterNot {
                        it == com.radioterapia.ai.transfer.PacoteConfig.Item.FOTOS
                    }.toSet()
                    if (selecao.isEmpty()) return@withContext null
                    contentResolver.openInputStream(uri)?.use {
                        com.radioterapia.ai.transfer.PacoteConfig.importar(
                            ctx, it, selecao,
                            com.radioterapia.ai.transfer.PacoteConfig.Modo.SOMAR)
                    }
                } catch (_: Exception) { null }
            }
            if (isFinishing || isDestroyed) return@launch
            if (aplicado == null) {
                Toast.makeText(ctx, R.string.tr_arquivo_invalido, Toast.LENGTH_LONG).show()
                return@launch
            }
            // Recarrega os temporarios A PARTIR do que entrou: o passo seguinte
            // le nomeClinicaTemp, nao o config, e sem isto o campo apareceria
            // vazio mesmo com o nome ja gravado.
            nomeClinicaTemp = config.nomeClinica
            // Reusa a mesma frase de resultado das Configuracoes: a operacao e a
            // mesma, e duas redacoes para o mesmo fato divergem na primeira vez
            // que so uma for revista.
            ultimoImportado = getString(R.string.tr_importado,
                aplicado.prefs, aplicado.arquivos, aplicado.pulados)
            mostrarPasso(passoAtual)
        }
    }

    private fun salvarPassoAtual() {
        when (passoAtual) {
            // O passo 2 (importar) nao tem campo a salvar: ele aplica na hora.
            3 -> {
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
            4 -> {
                // Mesmo sem interação nos botões: garante o acesso à pasta padrão
                if (config.pastaBaseCustom.isBlank() &&
                    !com.radioterapia.ai.util.StorageLocal.temAcessoTotal()) {
                    com.radioterapia.ai.util.StorageLocal.pedirAcessoTotal(this)
                } else {
                    com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
                }
                // Pasta das fotos é salva no momento da escolha (launcher). Nada aqui.
            }
            5 -> {
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
            6 -> {
                // PDF & etiqueta: persiste imediatamente
                containerStep.findViewById<android.widget.RadioButton>(R.id.rbWizPdfLand)?.let {
                    config.pdfLandscape = it.isChecked
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

        // Aplica nome clínica SE houver. A guarda nao e enfeite: com a
        // importacao no passo 2, o nome pode ter vindo do pacote e o campo da
        // tela seguinte nunca ter sido tocado — gravar o temporario vazio por
        // cima devolveria o nome da unidade a branco no ultimo passo do wizard.
        if (nomeClinicaTemp.isNotBlank()) config.nomeClinica = nomeClinicaTemp

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
