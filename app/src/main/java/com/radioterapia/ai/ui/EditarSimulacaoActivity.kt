package com.radioterapia.ai.ui

import android.os.Bundle
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.R
import com.radioterapia.ai.pdf.PdfBuilder
import com.radioterapia.ai.patient.PatientCache
import com.radioterapia.ai.treatment.TreatmentPhotoFetcher
import com.radioterapia.ai.util.Linhas
import com.radioterapia.ai.util.NomeArquivo
import com.radioterapia.ai.util.ObsStore
import com.radioterapia.ai.util.StorageLocal
import com.radioterapia.ai.util.TimeOutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Edita SOMENTE os dados da SIMULAÇÃO (Time-Out + observações): médico, máquina,
 * sítio, riscos, alergia e observações. Não toca no cadastro (nome/nascimento/
 * sexo/prontuário) — isso fica no fluxo de "Editar cadastro". Ao salvar, regrava
 * os stores e regenera o PDF da mesma simulação, mantendo a página de Time-Out.
 */
class EditarSimulacaoActivity : com.radioterapia.ai.BaseActivity() {

    private lateinit var config: AppConfig
    private lateinit var patientCache: PatientCache

    private var nomePaciente = ""
    private var prontuario = ""
    private var nomePastaServidor = ""
    private var numeroSimulacao = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editar_simulacao)
        config = AppConfig(this)
        patientCache = PatientCache(this)

        nomePaciente = intent.getStringExtra(EXTRA_NOME) ?: ""
        prontuario = intent.getStringExtra(EXTRA_PRONTUARIO) ?: ""
        nomePastaServidor = intent.getStringExtra(EXTRA_PASTA) ?: ""
        numeroSimulacao = intent.getIntExtra(EXTRA_NUM_SIMULACAO, 1)

        findViewById<TextView>(R.id.txtEsSubtitulo).text =
            getString(R.string.edit_sim_subtitle)

        val actMed = findViewById<AutoCompleteTextView>(R.id.actEsMedico)
        val actEq = findViewById<AutoCompleteTextView>(R.id.actEsEquip)
        val actSit = findViewById<AutoCompleteTextView>(R.id.actEsSitio)
        configurarSelecao(actMed, Linhas.deTexto(config.timeoutMedicos), true)
        configurarSelecao(actEq, Linhas.deTexto(config.equipamentos), false)
        configurarSelecao(actSit, Linhas.deTexto(config.sitiosLista), true)

        carregarValoresAtuais(actMed, actEq, actSit)

        // Mesmo limite da tela de finalizacao: ate 4 linhas digitadas, o que a
        // caixa de observacoes da ficha mostra no corpo cheio. A observacao
        // gravada chega ao campo depois (carregarValoresAtuais), e o vigia a
        // aceita inteira mesmo que tenha mais linhas. Ver CampoObservacao.
        val edtObs = findViewById<EditText>(R.id.edtEsObs)
        CampoObservacao.limitar(edtObs)

        findViewById<Button>(R.id.btnEsCancelar).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnEsSalvar).setOnClickListener { salvarERegerar() }
    }

    /**
     * Pasta DESTA simulação — ponto único, usado tanto para ler quanto para
     * gravar.
     *
     * Antes, a leitura resolvia a pasta pelo `nomePastaServidor` da Intent e a
     * gravação resolvia por outro caminho, via fetcher. Bastavam divergirem
     * para o técnico salvar o médico numa pasta, reabrir a tela e não
     * encontrá-lo — a edição parecia não ter sido gravada.
     *
     * Plano B igual ao do Resumo do carrossel: quando a pasta resolvida não tem
     * o registro, procura na pasta REAL das fotos. Sem ele, a tela abria com
     * TODOS os campos em branco, sem dizer que não localizou nada.
     */
    private suspend fun pastaDaSimulacao(): java.io.File? {
        // GUARDA: o resolvedor, sem a pasta exata, desempata por uma regra mais
        // fraca e pode devolver a pasta de uma homônima — onde o Time-Out seria
        // lido e depois gravado. Só vale a que a regra de escolha aceita.
        val resolvida = try {
            StorageLocal.resolverPastaSim(this, nomePaciente, numeroSimulacao,
                nomePastaServidor, prontuario).takeIf { pastaDeRegistrosServe(it) }
        } catch (_: Exception) { null }
        if (resolvida != null && TimeOutStore.ler(resolvida, numeroSimulacao) != null) return resolvida

        val daFoto = try {
            // Pasta E número, com o prontuário: só pelo número, a simulação
            // achada pode ser a de uma homônima, e o Time-Out seria lido — e
            // depois gravado — na pasta dela.
            val sims = TreatmentPhotoFetcher(this).buscarSimulacoes(nomePaciente, prontuario)
            val sim = TreatmentPhotoFetcher.simulacaoExata(sims, numeroSimulacao, nomePastaServidor)
            sim?.rosto?.arquivoLocal?.parentFile
                ?: sim?.posicionamentos?.firstOrNull()?.arquivoLocal?.parentFile
                ?: sim?.etiqueta?.arquivoLocal?.parentFile
        } catch (_: Exception) { null }
        return daFoto ?: resolvida
    }

    /**
     * A pasta devolvida pelo resolvedor é deste paciente? É, quando tem o nome
     * da pasta aberta, ou quando [TreatmentPhotoFetcher.pastaDeRegistrosServe]
     * a aceita. Pasta que ainda não existe passa: ler não acha nada. Só disco:
     * fora da thread principal.
     */
    private fun pastaDeRegistrosServe(pasta: java.io.File): Boolean {
        if (!pasta.exists()) return true
        val nomes = StorageLocal.photos(this).listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        return TreatmentPhotoFetcher.pastaDeRegistrosServe(pasta.name, nomePastaServidor, nomes,
            nomePaciente, prontuario, prontuario.isBlank() && patientCache.temHomonimos(nomePaciente))
    }

    /** Pré-preenche com o que já existe gravado para esta simulação. */
    private fun carregarValoresAtuais(
        actMed: AutoCompleteTextView, actEq: AutoCompleteTextView, actSit: AutoCompleteTextView
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val (reg, obs) = withContext(Dispatchers.IO) {
                val pasta = pastaDaSimulacao()
                val r = try { pasta?.let { TimeOutStore.ler(it, numeroSimulacao) } }
                        catch (_: Exception) { null }
                val o = try { pasta?.let { ObsStore.ler(it, numeroSimulacao) } ?: "" }
                        catch (_: Exception) { "" }
                r to o
            }
            if (reg != null) {
                if (reg.medico.isNotBlank()) actMed.setText(reg.medico, false)
                if (reg.equipamento.isNotBlank()) actEq.setText(reg.equipamento, false)
                if (reg.sitio.isNotBlank()) actSit.setText(reg.sitio, false)
                findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swEsRisco).isChecked = reg.riscoQueda
                findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swEsPrec).isChecked = reg.precaucaoContato
                findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swEsAlergia).isChecked = reg.alergia == "SIM"
                // O protocolo GRAVADO vem pre-marcado, ao contrario da tela de
                // confirmacao, onde nada vem marcado. A diferenca e proposital:
                // la nao ha escolha anterior, aqui ha — e nao mostra-la faria
                // parecer que a ficha nao tem protocolo nenhum.
                protocoloEscolhido = reg.protocoloId.orEmpty()
            }
            desenharProtocolos()
            ligarCoresDosAlertas()
            findViewById<EditText>(R.id.edtEsObs).setText(obs)
        }
    }

    private var protocoloEscolhido: String = ""

    /**
     * A faixa de protocolos, igual a da tela de confirmacao.
     *
     * Com um protocolo so o bloco nao aparece: nao ha escolha a fazer, e mostrar
     * uma opcao unica seria pedir um toque para confirmar o obvio.
     */
    private fun desenharProtocolos() {
        val bloco = findViewById<View>(R.id.esLayoutProtocolos) ?: return
        val faixa = findViewById<android.widget.LinearLayout>(R.id.esFaixaProtocolos) ?: return
        val store = com.radioterapia.ai.protocolo.ProtocoloStore(this)
        val todos = store.listar()
        if (todos.size <= 1) { bloco.visibility = View.GONE; return }

        bloco.visibility = View.VISIBLE
        faixa.removeAllViews()
        val d = resources.displayMetrics.density
        val lado = (110 * d).toInt()
        val marcas = mutableListOf<Pair<String, android.widget.CheckBox>>()

        todos.forEach { p ->
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            }
            val marca = android.widget.CheckBox(this).apply {
                text = p.nome
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@EditarSimulacaoActivity, R.color.text_primary))
                textSize = 11f
                isChecked = p.id == protocoloEscolhido
            }
            marcas.add(p.id to marca)
            marca.setOnCheckedChangeListener { _, marcado ->
                // Escolha unica: marcar um desmarca os outros. CheckBox e nao
                // RadioButton porque aqui DESMARCAR e uma acao valida — a ficha
                // pode sair sem paginas acrescentadas.
                if (marcado) {
                    protocoloEscolhido = p.id
                    marcas.forEach { (id, cb) -> if (id != p.id) cb.isChecked = false }
                } else if (protocoloEscolhido == p.id) {
                    protocoloEscolhido = ""
                }
            }
            val img = android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(lado, (lado * 0.72f).toInt())
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(store.bitmapMiniatura(p))
                if (drawable == null) setImageResource(R.drawable.bg_sem_foto)
                setOnClickListener { marca.isChecked = true }
            }
            col.addView(marca)
            col.addView(img)
            faixa.addView(col)
        }
    }

    /** As mesmas cores de alerta da tela de confirmacao. */
    private fun ligarCoresDosAlertas() {
        val M = com.radioterapia.ai.ui.anim.Movimento
        M.toggleAlerta(findViewById(R.id.esLinhaRisco), findViewById(R.id.swEsRisco),
                       0xFFFFE082.toInt())
        M.toggleAlerta(findViewById(R.id.esLinhaPrec), findViewById(R.id.swEsPrec),
                       0xFFFFB74D.toInt())
        M.toggleAlerta(findViewById(R.id.esLinhaAlergia), findViewById(R.id.swEsAlergia),
                       0xFFEF5350.toInt())
    }

    private fun salvarERegerar() {
        // Leituras de UI na thread principal.
        val med = findViewById<AutoCompleteTextView>(R.id.actEsMedico).text.toString().trim()
        val equip = findViewById<AutoCompleteTextView>(R.id.actEsEquip).text.toString().trim()
        val sitio = findViewById<AutoCompleteTextView>(R.id.actEsSitio).text.toString().trim()
        val risco = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swEsRisco).isChecked
        val prec = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swEsPrec).isChecked
        val alergia = if (findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swEsAlergia).isChecked) "SIM" else ""
        val protocoloUi = protocoloEscolhido
        val obs = findViewById<EditText>(R.id.edtEsObs).text.toString().trim()
        val prog = findViewById<TextView>(R.id.txtEsProgresso)
        val btn = findViewById<Button>(R.id.btnEsSalvar)
        btn.isEnabled = false
        prog.visibility = View.VISIBLE
        prog.setText(R.string.ap_finishing)

        CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val fetcher = TreatmentPhotoFetcher(this@EditarSimulacaoActivity)
                    val sims = fetcher.buscarSimulacoes(nomePaciente, prontuario)
                    // Sem a simulação EXATA, falha. Havia aqui um
                    // `?: sims.maxByOrNull { it.timestampPrincipal }`: quando a
                    // simulação pedida não era encontrada, o app gravava
                    // silenciosamente na pasta da simulação mais recente. O
                    // técnico salvava, reabria a tela — que lê a pasta certa — e
                    // a edição tinha sumido. Pior: o dado ficava numa simulação
                    // que não era a dele.
                    // EXATA é pasta E número: só pelo número, com uma homônima
                    // no tablet, a ficha regerada sairia com as fotos dela.
                    val sim = TreatmentPhotoFetcher.simulacaoExata(sims, numeroSimulacao, nomePastaServidor)
                        ?: return@withContext false
                    // Mesma resolução da leitura, para gravar onde se lê.
                    val pasta = pastaDaSimulacao() ?: return@withContext false

                    // LÊ ANTES DE GRAVAR, e não é ordem à toa.
                    //
                    // Registro tem NOVE campos; esta chamada passava SETE
                    // posicionais, então fracoesMax e protocoloId caíam nos
                    // defaults (0 e ""). A preservação existia logo abaixo —
                    // relia o registro para reaproveitar as frações —, mas lia
                    // DEPOIS desta gravação, ou seja, relia o zero que ela
                    // acabara de escrever. O efeito é o que o comentário de lá
                    // já descrevia e que continuou acontecendo: corrigir o nome
                    // do médico apagava o número de frações, e o realce da
                    // última fração sumia da folha sem ninguém ter pedido.
                    val regAnterior = try {
                        TimeOutStore.ler(pasta, numeroSimulacao)
                    } catch (_: Exception) { null }

                    // Grava Time-Out + observação desta simulação.
                    TimeOutStore.gravar(pasta, numeroSimulacao,
                        TimeOutStore.Registro(
                            true, med, sitio, risco, prec, equip, alergia,
                            fracoesMax = regAnterior?.fracoesMax ?: 0,
                            protocoloId = protocoloUi.ifBlank { regAnterior?.protocoloId ?: "" }
                        ))
                    ObsStore.gravar(pasta, numeroSimulacao, obs)
                    // Guarda equipamento/médico habituais no cadastro (comodidade).
                    // Sem prontuário e com homônimas no cadastro, não: a
                    // atualização cairia no registro que a busca escolhe entre
                    // elas, e o médico deste paciente iria para o da outra.
                    if (!(prontuario.isBlank() && patientCache.temHomonimos(nomePaciente))) {
                        equip.takeIf { it.isNotBlank() }?.let { patientCache.atualizarEquipamento(nomePaciente, it, prontuario) }
                        med.takeIf { it.isNotBlank() }?.let { patientCache.atualizarSexoMedico(nomePaciente, "", it, prontuario) }
                    }

                    // Monta a lista de fotos da simulação para regenerar o PDF.
                    val fotos = mutableListOf<File>()
                    val rotulos = mutableListOf<String>()
                    // VOCABULARIO CANONICO EM PORTUGUES, DE PROPOSITO — nao e literal esquecido.
                    // O PdfBuilder recebe estes rotulos em PT e os traduz no ponto de
                    // desenho, em traduzirRotulo(), com pdf_lbl_face/label/positioning/
                    // accessory. Trocar por getString aqui QUEBRA o mapa: "Rosto" deixa
                    // de casar e o rotulo sai sem traducao. Tambem quebra ehEtiqueta(),
                    // que compara a base contra "Etiqueta".
                    sim.rosto?.let { fotos.add(it.arquivoLocal); rotulos.add("Rosto") }
                    sim.etiqueta?.let { fotos.add(it.arquivoLocal); rotulos.add("Etiqueta") }
                    sim.posicionamentos.forEachIndexed { i, f ->
                        fotos.add(f.arquivoLocal); rotulos.add("Posicionamento." + (i + 1)) }
                    sim.acessoriosLista.forEachIndexed { i, f ->
                        fotos.add(f.arquivoLocal); rotulos.add("Acessório." + (i + 1)) }
                    if (fotos.isEmpty()) return@withContext false

                    // Só registro deste paciente: sem prontuário, a busca escolhe
                    // entre homônimas, e o nascimento e o sexo sairiam os da outra.
                    val dadosPac = patientCache.obterDadosPaciente(nomePaciente, prontuario)
                        ?.takeIf { d ->
                            TreatmentPhotoFetcher.cadastroServeAoPaciente(prontuario, d.prontuario,
                                prontuario.isBlank() && patientCache.temHomonimos(nomePaciente))
                        }
                    val dados = PdfBuilder.DadosCabecalho(
                        nomePaciente = nomePaciente,
                        nascimento = dadosPac?.nascimento ?: "",
                        prontuario = dadosPac?.prontuario ?: prontuario,
                        idsExtras = emptyList(),
                        dataSimulacao = java.util.Date(sim.timestampPrincipal),
                        numeroSimulacao = sim.numeroSimulacao,
                        nomeClinica = config.nomeClinica,
                        sexo = dadosPac?.sexo ?: "",
                        medicoAssistente = med
                    )
                    // As frações vêm de regAnterior, lido ANTES da gravação lá em
                    // cima. Antes havia aqui uma segunda leitura do disco com
                    // este mesmo propósito, só que ela rodava depois do gravar e
                    // por isso relia o zero que o gravar tinha acabado de
                    // escrever. Uma leitura só, no lugar certo.
                    val fracoesGravadas = regAnterior?.fracoesMax ?: 0
                    // O QUE ESTA NA TELA MANDA. Antes so o valor gravado era
                    // reaproveitado, porque nao havia como muda-lo; agora que ha,
                    // reaproveita-lo ignoraria a escolha que o usuario acabou de
                    // fazer — e ele nao teria como saber que foi ignorada.
                    val protocoloGravado = protocoloUi
                    val timeOut = if (config.pdfIncluiTimeOut)
                        PdfBuilder.DadosTimeOut(med, sitio, risco, prec,
                            sim.rosto?.arquivoLocal, equip, alergia,
                            fracoesGravadas) else null

                    // Remove os PDFs antigos DESTA simulação (traziam dados
                    // velhos). GUARDA: só desta. Todas as simulações dividem a pasta, e
                    // a regra de NomeArquivo separa pela marca no nome nos dois
                    // esquemas. Uma regra por ausência de marca ("sem _NOVASIM"
                    // = simulação 1) apagaria a ficha da reirradiação gravada
                    // no esquema novo, que leva NS<n>.
                    pasta.listFiles()?.filter { f ->
                        f.isFile && NomeArquivo.ehFichaDaSimulacao(f.name, numeroSimulacao)
                    }?.forEach { it.delete() }

                    // Gravada na pasta do paciente: nome com iniciais.
                    val saida = File(pasta, NomeArquivo.montar(nomePaciente,
                        NomeArquivo.Tipo.FICHA, numeroSimulacao,
                        System.currentTimeMillis(), 1, "pdf"))
                    PdfBuilder.gerarFolhaPosicionamento(
                        this@EditarSimulacaoActivity, dados, fotos, saida,
                        rotulos = rotulos, landscape = config.pdfLandscape,
                        etiquetaLarguraMm = config.pdfEtiquetaLarguraMm,
                        etiquetaAlturaMm = config.pdfEtiquetaAlturaMm,
                        observacoes = obs,
                        timeOut = timeOut,
                        margemImpressaoMm = config.pdfMargemMm,
                        protocoloId = protocoloGravado)
                    true
                } catch (_: Exception) { false }
            }
            prog.visibility = View.GONE
            btn.isEnabled = true
            Toast.makeText(this@EditarSimulacaoActivity,
                if (ok) R.string.edit_logged else R.string.pdf_open_error,
                Toast.LENGTH_SHORT).show()
            if (ok) { setResult(RESULT_OK); finish() }
        }
    }

    /** Dropdown-first idêntico ao da tela de Confirmar dados. */
    private fun configurarSelecao(act: AutoCompleteTextView,
                                  opcoes: List<String>, permiteDigitar: Boolean) {
        val itens = if (permiteDigitar) opcoes + getString(R.string.dd_type_other) else opcoes
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_dropdown_item_1line, itens) {
            override fun getFilter(): android.widget.Filter =
                object : android.widget.Filter() {
                    override fun performFiltering(cs: CharSequence?) =
                        FilterResults().apply { values = itens; count = itens.size }
                    override fun publishResults(cs: CharSequence?, r: FilterResults?) {
                        notifyDataSetChanged()
                    }
                }
        }
        // Sem opções configuradas → campo 100% livre para digitar
        // (não faz sentido "dropdown-first" com lista vazia).
        if (opcoes.isEmpty()) {
            act.setAdapter(null)
            act.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            return
        }
        act.setAdapter(adapter)
        act.threshold = 1
        if (!permiteDigitar && opcoes.size == 1 && act.text.isNullOrBlank())
            act.setText(opcoes[0], false)
        val teclado = act.keyListener
        var digitando = false
        fun modoSelecao() { digitando = false; act.keyListener = null; act.isCursorVisible = false }
        modoSelecao()
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        fun abrirLista() {
            imm.hideSoftInputFromWindow(act.windowToken, 0)
            act.requestFocus(); act.post { act.showDropDown() }
        }
        act.setOnClickListener { if (!digitando) abrirLista() }
        act.setOnFocusChangeListener { _, temFoco -> if (temFoco && !digitando) act.post { act.showDropDown() } }
        act.setOnItemClickListener { _, _, pos, _ ->
            if (permiteDigitar && pos == itens.size - 1) {
                digitando = true; act.setText("", false)
                act.keyListener = teclado; act.isCursorVisible = true
                act.requestFocus(); imm.showSoftInput(act, 0)
            } else { act.setText(itens[pos], false); modoSelecao() }
        }
    }

    companion object {
        const val EXTRA_NOME = "nome"
        const val EXTRA_PRONTUARIO = "prontuario"
        const val EXTRA_PASTA = "pasta"
        const val EXTRA_NUM_SIMULACAO = "num_simulacao"
    }
}
