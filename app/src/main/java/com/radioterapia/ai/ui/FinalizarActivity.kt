package com.radioterapia.ai.ui

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.R
import com.radioterapia.ai.audit.AuditLogger
import com.radioterapia.ai.patient.PatientCache
import com.radioterapia.ai.pdf.PdfBuilder
import com.radioterapia.ai.print.PrinterClient
import com.radioterapia.ai.security.CredentialStore
import com.radioterapia.ai.session.SessionManager
import com.radioterapia.ai.session.SessionManager.Category
import com.radioterapia.ai.smb.SmbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela de finalização da simulação.
 *
 * Sequência:
 *  1. Mostra resumo
 *  2. Usuário confirma → executa em background:
 *     a. Gera Folha de Posicionamento (PDF)
 *     b. Gera Ficha de Acessórios (PDF separado, se houver foto)
 *     c. Salva localmente (fotos + PDFs)
 *     d. Envia para destino primário (fotos + PDFs)
 *     e. Envia para destinos backup (sequencial)
 *     f. Mostra resultado
 *  3. Se há impressora: pergunta "Imprimir folha?"
 *  4. Registra no histórico, limpa sessão, volta para Home
 */
class FinalizarActivity : com.radioterapia.ai.BaseActivity() {

    private var tituloAtualRes: Int = R.string.fin_confirm_title
    override fun tituloPadrao(): String = getString(tituloAtualRes)

    private lateinit var config: AppConfig
    private lateinit var credentials: CredentialStore
    private lateinit var sessionManager: SessionManager
    private lateinit var patientCache: PatientCache
    private lateinit var auditLogger: AuditLogger

    private lateinit var txtResumo: TextView
    private lateinit var txtProgresso: TextView
    private lateinit var btnConfirmar: Button
    private lateinit var btnCancelar: Button
    private lateinit var layoutAcoes: LinearLayout
    private lateinit var layoutResultado: LinearLayout
    private lateinit var txtResultado: TextView
    private lateinit var btnImprimir: Button
    private lateinit var btnEncerrar: Button
    private lateinit var btnVerPdf: Button
    private lateinit var btnAddFotos: Button
    private lateinit var edtObservacao: EditText
    private var observacoesTexto = ""
    private lateinit var txtImprInfo: TextView

    /** Caminho do PDF gerado nesta finalização, p/ o "Visualizar PDF". */
    private var pdfGeradoAtual: File? = null

    /** Nome do paciente cuja simulação foi finalizada nesta tela (para "Editar cadastro"). */
    private var nomePacienteFinalizado: String = ""
    private var numSimFinalizado: Int = 1
    /** Nome do paciente vigente (pode mudar após "Editar cadastro"). */
    private var nomePacienteAtual: String = ""
    /** Pasta local da simulação recém-finalizada (recomputada após rename). */
    private var nomePastaFinalizada: String = ""

    /** Identificações + contagem de fotos capturadas ANTES de limpar a sessão. */
    private var resumoBaseSnapshot: String = ""

    /** Após finalizar, a sessão foi limpa: voltar para a câmera corromperia a
     *  simulação (miniaturas cinzas, registros duplicados). A seta some e
     *  qualquer "voltar" leva ao MENU. */
    /** Ao virar true, o "voltar" passa a ir para a Home (ver [voltarPosFinalizacao]). */
    private var simulacaoFinalizada = false
        set(v) { field = v; voltarPosFinalizacao.isEnabled = v }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finalizar)

        onBackPressedDispatcher.addCallback(this, voltarPosFinalizacao)

        config = AppConfig(this)
        credentials = CredentialStore(this)
        sessionManager = SessionManager(this)
        nomePacienteAtual = sessionManager.nomePaciente
        patientCache = PatientCache(this)
        auditLogger = AuditLogger(this)

        txtResumo = findViewById(R.id.txtResumo)
        txtProgresso = findViewById(R.id.txtProgresso)
        btnConfirmar = findViewById(R.id.btnConfirmar)
        btnCancelar = findViewById(R.id.btnCancelar)
        layoutAcoes = findViewById(R.id.layoutAcoes)
        layoutResultado = findViewById(R.id.layoutResultado)
        txtResultado = findViewById(R.id.txtResultado)
        btnImprimir = findViewById(R.id.btnImprimir)
        btnEncerrar = findViewById(R.id.btnEncerrar)
        btnVerPdf = findViewById(R.id.btnVerPdf)
        btnAddFotos = findViewById(R.id.btnAddFotos)
        txtImprInfo = findViewById(R.id.txtImprInfo)
        edtObservacao = findViewById(R.id.edtObservacao)

        // Limita a 2 LINHAS: o box da ficha comporta duas desde que a
        // terceira foi cedida ao espaco de seguranca do cabecalho. Bloquear
        // aqui evita o usuario digitar o que a ficha depois cortaria calada.
        edtObservacao.addTextChangedListener(object : android.text.TextWatcher {
            private var anterior = ""
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { anterior = s?.toString() ?: "" }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val txt = s?.toString() ?: ""
                if (txt.count { it == '\n' } > 1) {
                    edtObservacao.setText(anterior)
                    edtObservacao.setSelection(anterior.length.coerceAtMost(edtObservacao.text.length))
                }
            }
        })

        title = getString(R.string.fin_confirm_title)
        findViewById<TextView>(R.id.txtFinTitulo).setText(R.string.fin_confirm_title)
        construirResumo()
        /*
            A FAIXA DE PROTOCOLOS TEM DE SER DESENHADA AQUI, na primeira passagem.

            Ela estava sendo montada num lugar so — dentro de entrarModoEdicao(),
            que e o botao "Editar dados". Resultado: quem preenchia e salvava
            nunca via a escolha; ela so aparecia depois, quando a tela voltava
            para o modo de edicao, e de fora isso parecia a tela "voltando
            sozinha e so entao mostrando os protocolos".

            Pior que nao aparecer: protocoloEscolhido ficava "" e a ficha saia
            SEM as paginas do servico, sem ninguem ter decidido isso.
         */
        desenharProtocolos()
        // ===== TIME-OUT: rotina da clínica (config). Sítio é OPCIONAL. =====
        findViewById<View>(R.id.layoutTimeOutCampos).visibility =
            if (config.pdfIncluiTimeOut) View.VISIBLE else View.GONE
        // Seleção "dropdown-first": o 1º toque abre a lista (a rotina); digitar um
        // valor fora dela é a exceção, pelo item "✏︎ Digitar outro…" do dropdown.
        val actSit = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutSitio)
        configurarSelecao(actSit,
            config.sitiosLista.split("\n").map { it.trim() }.filter { it.isNotBlank() }, true)
        val actMedTo = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutMedico)
        configurarSelecao(actMedTo,
            config.timeoutMedicos.split("\n").map { it.trim() }.filter { it.isNotBlank() }, true)
        patientCache.obterDadosPaciente(sessionManager.nomePaciente, sessionManager.prontuario)?.medicoAssistente
            ?.takeIf { it.isNotBlank() }?.let { actMedTo.setText(it, false) }
        val actEquip = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutEquip)
        configurarSelecao(actEquip,
            config.equipamentos.split("\n").map { it.trim() }.filter { it.isNotBlank() }, false)
        patientCache.obterDadosPaciente(sessionManager.nomePaciente, sessionManager.prontuario)?.equipamento
            ?.takeIf { it.isNotBlank() }?.let { actEquip.setText(it, false) }

        com.radioterapia.ai.util.UiText.uniformizar(
            findViewById(R.id.btnCancelar), findViewById(R.id.btnConfirmar))

        com.radioterapia.ai.util.UiText.uniformizar(
            findViewById(R.id.btnVerPdf), findViewById(R.id.btnImprimir),
            findViewById(R.id.btnCompartilharPdf), findViewById(R.id.btnEditarDados))

        // "Editar dados" (fase 2) devolve a tela editável; "Salvar alterações" regrava.
        findViewById<Button>(R.id.btnEditarDados).setOnClickListener { entrarModoEdicao() }
        findViewById<Button>(R.id.btnAtualizarObs).setOnClickListener { atualizarObservacaoNoPdf() }

        // Cancelar volta para a CÂMERA (retoma a simulação em vez de descartar a tela).
        btnCancelar.setOnClickListener { finish() }
        btnConfirmar.setOnClickListener {
            timeOutSelecionado = montarTimeOut()
            observacoesTexto = edtObservacao.text?.toString()?.trim() ?: ""
            confirmarCamposEmBranco { executarFinalizacao() }
        }
    }

    /**
     * Avisa quais campos do Time-Out ficaram em branco e deixa seguir mesmo assim.
     *
     * NÃO TORNA NADA OBRIGATÓRIO. Médico, equipamento e sítio continuam
     * opcionais — a decisão registrada no projeto é que a resposta a campo
     * vazio é instrução e treinamento, não obrigatoriedade, porque clique
     * custa e o técnico às vezes de fato não tem o dado na hora.
     *
     * O que existia era outra coisa: os campos saíam vazios na ficha impressa e
     * na página de Time-Out sem que ninguém percebesse até a folha estar na
     * mão, com o paciente já fora da sala. O aviso é o lembrete no único
     * momento em que ainda dá para preencher — e o botão que segue em frente
     * fica ali do lado.
     *
     * Só aparece quando há algo em branco: quem preencheu tudo não paga clique
     * nenhum, que é a metade da regra que costuma ser esquecida.
     */
    private fun confirmarCamposEmBranco(aoSeguir: () -> Unit) {
        val t = timeOutSelecionado
        if (t == null) { aoSeguir(); return }

        val faltando = buildList {
            if (t.medicoResponsavel.isBlank()) add(getString(R.string.fin_faltando_medico))
            if (t.equipamento.isBlank()) add(getString(R.string.fin_faltando_equip))
            if (t.sitioTratamento.isBlank()) add(getString(R.string.fin_faltando_sitio))
        }
        if (faltando.isEmpty()) { aoSeguir(); return }

        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.fin_faltando_titulo)
            .setMessage(getString(R.string.fin_faltando_msg,
                faltando.joinToString("\n") + "\n"))
            .setPositiveButton(R.string.fin_faltando_seguir) { _, _ -> aoSeguir() }
            .setNegativeButton(R.string.fin_faltando_voltar, null)
            .create()
        // SEM COR nos botões, ao contrário de descartar/excluir. Vermelho em
        // "Continuar assim" leria como repreensão por não ter preenchido um
        // campo que é opcional de propósito — e o aviso passaria de lembrete a
        // cobrança. O texto já diz o que falta; a decisão é do técnico.
        dlg.show()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (simulacaoFinalizada) voltarHome() else finish(); return true
    }

    override fun aoTocarVoltarToolbar() {
        if (simulacaoFinalizada) voltarHome() else finish()
    }

    /**
     * "Voltar" pelo OnBackPressedDispatcher (a API antiga está obsoleta e não
     * participa do gesto de voltar previsto do Android 13+).
     *
     * O callback só fica HABILITADO depois de finalizar. Antes disso não há o
     * que interceptar, e deixá-lo desabilitado devolve o comportamento padrão ao
     * sistema em vez de reimplementá-lo — que era o papel do `super` na versão
     * anterior. Depois de finalizada a simulação, voltar tem que ir para a Home:
     * a sessão já foi limpa, e reabrir a tela anterior mostraria uma captura
     * vazia como se as fotos tivessem sumido.
     */
    private val voltarPosFinalizacao =
        object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { voltarHome() }
        }

    private fun construirResumo() {
        val numSim = patientCache.obterContagemSimulacoes(
            sessionManager.nomePaciente, sessionManager.prontuario) + 1
        val sb = StringBuilder()
        sb.append(getString(R.string.fin_patient, sessionManager.nomePaciente)).append("\n")
        if (sessionManager.prontuario.isNotBlank())
            sb.append(getString(R.string.fin_record, sessionManager.prontuario)).append("\n")
        if (sessionManager.dataNascimento.isNotBlank())
            sb.append(getString(R.string.fin_birth, sessionManager.dataNascimento)).append("\n")
        sb.append("\n")
        sb.append(getString(R.string.fin_total_photos, sessionManager.quantidade())).append("\n")
        val nDocs = sessionManager.quantidadeCategoria(Category.DOCUMENTS)
        if (nDocs > 0) sb.append(getString(R.string.fin_documents, nDocs)).append("\n")
        sb.append(getString(R.string.fin_face, sessionManager.quantidadeCategoria(Category.FACE))).append("\n")
        sb.append(getString(R.string.fin_label, sessionManager.quantidadeCategoria(Category.LABEL))).append("\n")
        sb.append(getString(R.string.fin_positioning, sessionManager.quantidadeCategoria(Category.POSITIONING))).append("\n")
        sb.append(getString(R.string.fin_accessories, sessionManager.quantidadeCategoria(Category.ACCESSORIES))).append("\n")
        sb.append("\n")
        if (numSim > 1) sb.append(getString(R.string.fin_new_sim, numSim - 1)).append("\n")
        // Snapshot: mostrarResultado() limpa a sessão, então o resumo pós-finalização
        // não pode ser reconstruído a partir dela (ficaria vazio / 0 fotos).
        resumoBaseSnapshot = sb.toString().trimEnd()
        txtResumo.text = resumoBaseSnapshot
    }

    // ============= EXECUÇÃO =============

    private fun executarFinalizacao() {
        // Espaço em disco: sem folga, a cópia das fotos e o PDF falhariam no meio
        // (simulação corrompida). Melhor avisar antes de começar.
        if (!com.radioterapia.ai.util.StorageLocal.temEspacoMinimo(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.disk_full_title)
                .setMessage(getString(R.string.disk_full_msg,
                    com.radioterapia.ai.util.StorageLocal.espacoLivreMb(this)))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        layoutAcoes.visibility = View.GONE
        txtProgresso.visibility = View.VISIBLE

        val nome = sessionManager.nomePaciente
        val nomeNorm = com.radioterapia.ai.util.StorageLocal.removerAcentosMaiusculas(nome)
        // Modo edição ("adicionar mais fotos"): mantém a MESMA simulação e não incrementa.
        val editando = sessionManager.editandoNomePasta.isNotBlank()
        // O PRONTUÁRIO desempata homônimos. Sem ele, resolverChave() escolhe entre
        // pacientes de mesmo nome "o registro mais completo" — que não é
        // necessariamente quem está na mesa. Como este número vira o sufixo
        // "NOVA SIMULACAO n", errar aqui é numerar a simulação de uma paciente
        // como reirradiação de outra.
        val numSim = if (editando) sessionManager.editandoNumSim
                     else patientCache.obterContagemSimulacoes(nome, sessionManager.prontuario) + 1

        // PASTA ÚNICA da simulação: a MESMA para fotos, PDF, Time-Out e observações.
        //
        // Antes havia duas. As fotos e o PDF iam para `nomePastaPaciente()`
        // ("NOME - PRONTUÁRIO"), e o Time-Out ia para uma string montada aqui
        // ("NOME POSICIONAMENTO..."), que ninguém criava. Como TimeOutStore não
        // fazia mkdirs e engolia a exceção, o sítio de tratamento, o risco de
        // queda e a precaução de contato eram perdidos em silêncio na PRIMEIRA
        // finalização — e só "apareciam" se o usuário editasse depois, porque o
        // caminho de regeneração já usava resolverPastaSim.
        val nomePastaPac = com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(
            nomeNorm, sessionManager.prontuario)

        val fotosPdf = sessionManager.fotosOrdenadasParaPdf().map { it.arquivo }
        val tiposPdf = sessionManager.tiposParaPdf()
        // Impressos escaneados: NÃO entram no PDF, mas SÃO gravados na pasta do paciente.
        val docsArquivos = sessionManager.fotos
            .filter { it.categoria == Category.DOCUMENTS }
            .sortedBy { it.timestampMs }.map { it.arquivo }
        val fotosGravar = fotosPdf + docsArquivos
        val tiposGravar = tiposPdf + List(docsArquivos.size) { "DOC" }
        // Acessórios agora fazem parte de fotosPdf (todas as fotos). Não salvamos
        // mais um acessório único em separado.
        val fotoAcessorios: File? = null

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Gera PDFs
                txtProgresso.text = getString(R.string.hc_generating_pdf)
                val pdfFolha = withContext(Dispatchers.IO) {
                    gerarFolhaPosicionamento(nome, fotosPdf, numSim, nomeNorm, timeOutSelecionado)
                }
                // Acessórios agora entram na própria Folha de Posicionamento
                // (item: todas as fotos no PDF). Não geramos mais ficha separada.
                val pdfAcessorios: File? = null

                // 2. Salva localmente (fotos + PDF na pasta escolhida)
                txtProgresso.text = getString(R.string.saving_locally)
                val errosLocais = withContext(Dispatchers.IO) {
                    salvarTodasLocalmente(fotosGravar, tiposGravar, pdfFolha,
                        nomeNorm, nomePastaPac, numSim)
                }

                // Persiste as escolhas do Time-Out (regenerações futuras) e o
                // equipamento habitual no cadastro do paciente.
                val toReg = timeOutSelecionado?.let { t ->
                    com.radioterapia.ai.util.TimeOutStore.Registro(
                        true, t.medicoResponsavel, t.sitioTratamento,
                        t.riscoQueda, t.precaucaoContato,
                        t.equipamento, t.alergia, t.fracoesMax, protocoloEscolhido)
                }
                // A pasta já foi criada por salvarTodasLocalmente; resolverPastaSim
                // devolve a EXATA quando ela existe, e desempata por prontuário
                // quando não. É o mesmo caminho que a regeneração já usava.
                val obsTexto = findViewById<android.widget.EditText>(R.id.edtObservacao)
                    .text.toString().trim()
                val pastaSim = withContext(Dispatchers.IO) {
                    com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
                        this@FinalizarActivity, nome, numSim, nomePastaPac,
                        sessionManager.prontuario)
                }
                val okTimeOut = withContext(Dispatchers.IO) {
                    com.radioterapia.ai.util.TimeOutStore.gravar(pastaSim, numSim, toReg)
                }
                // Observação da simulação: persistida junto (as rodadas de fotos
                // adicionais pré-carregam e reimprimem a partir daqui).
                val okObs = withContext(Dispatchers.IO) {
                    com.radioterapia.ai.util.ObsStore.gravar(pastaSim, numSim, obsTexto)
                }
                // Falha de gravação clínica NÃO é silenciosa: entra na lista de
                // erros que o resultado mostra e vira linha no log de auditoria.
                val errosClinicos = mutableListOf<String>()
                if (!okTimeOut) errosClinicos.add(getString(R.string.err_timeout_save))
                if (!okObs) errosClinicos.add(getString(R.string.err_obs_save))
                if (errosClinicos.isNotEmpty()) withContext(Dispatchers.IO) {
                    com.radioterapia.ai.audit.AuditLogger(this@FinalizarActivity).registrar(
                        com.radioterapia.ai.audit.AuditLogger.Tipo.ERROR,
                        "Falha ao gravar dados da simulacao",
                        mapOf("pasta" to pastaSim.absolutePath,
                              "timeout_ok" to okTimeOut, "obs_ok" to okObs))
                }
                timeOutSelecionado?.equipamento?.takeIf { it.isNotBlank() }?.let {
                    patientCache.atualizarEquipamento(nome, it, sessionManager.prontuario)
                }
                timeOutSelecionado?.medicoResponsavel?.takeIf { it.isNotBlank() }?.let {
                    patientCache.atualizarSexoMedico(nome, "", it, sessionManager.prontuario)
                }

                // Envio à rede agora é responsabilidade do app de sincronização
                // (ex.: FolderSync). O app apenas salva localmente.
                val resultadosEnvio = emptyList<ResultadoDestino>()

                // 3. Mostra resultado
                txtProgresso.visibility = View.GONE
                mostrarResultado(resultadosEnvio, errosLocais + errosClinicos,
                    pdfFolha, nome, numSim)
            } catch (e: Exception) {
                txtProgresso.visibility = View.GONE
                AlertDialog.Builder(this@FinalizarActivity)
                    .setTitle(R.string.error)
                    .setMessage(getString(R.string.err_unexpected, e.message ?: ""))
                    .setPositiveButton(R.string.ok) { _, _ -> finish() }
                    .show()
            }
        }
    }

    private data class ResultadoDestino(
        val rotulo: String,
        val host: String,
        val resultado: SmbClient.TentativaResultado
    )

    private fun mostrarResultado(
        resultados: List<ResultadoDestino>,
        errosLocais: List<String>,
        pdfFolha: File,
        nomePaciente: String,
        numSim: Int
    ) {
        // Item 8: resolver o bug "Home ainda mostra rascunho ativo" - registrar histórico
        // e limpar sessão IMEDIATAMENTE quando a finalização processa. As ações nos
        // 4 botões abaixo são OPCIONAIS - simulação já foi salva.
        pdfGeradoAtual = pdfFolha
        // GATILHO DE FINALIZACAO. E o instante em que a ficha existe: fotos
        // gravadas, PDF montado, pasta do paciente completa. Esperar o periodo
        // configurado deixaria a simulacao inteira so no tablet justamente no
        // intervalo em que ela ainda nao foi conferida por ninguem.
        com.radioterapia.ai.sync.SyncWorker.aoFinalizar(this)
        nomePacienteFinalizado = nomePaciente
        numSimFinalizado = numSim
        mostrarFaseResultado()
        // Captura dados antes de limpar a sessão (usados pelo botão "Adicionar mais fotos").
        // O nome da pasta usa a MESMA fórmula de executarFinalizacao().
        val prontuarioFin = sessionManager.prontuario
        val nomeNormFin = normalizarNome(nomePaciente)
        val sufixoFin = if (numSim > 1) " NOVA SIMULACAO ${numSim - 1}" else ""
        val nomePastaFin = "$nomeNormFin POSICIONAMENTO$sufixoFin"
        nomePacienteAtual = nomePaciente
        nomePastaFinalizada = nomePastaFin
        registrarHistoricoCompleto(nomePaciente)
        sessionManager.limparSessao()

        // Sucesso agora = salvou localmente (sem erros). Não há mais envio SMB.
        val primarioOk = errosLocais.isEmpty()

        // Resumo: mostrar APENAS o que deu certo (item 8 e 12)
        tituloAtualRes = R.string.fin_done_title
        title = getString(R.string.fin_done_title)
        findViewById<TextView>(R.id.txtFinTitulo).setText(R.string.fin_done_title)
        atualizarResumoFinal()
        txtResultado.text = if (errosLocais.isEmpty())
            getString(R.string.fin_ok_short)
        else getString(R.string.fin_fail_short) + "\n" + errosLocais.joinToString("\n")
        layoutResultado.visibility = View.VISIBLE

        // ===== Botão 1: Visualizar PDF =====
        btnVerPdf.setOnClickListener { abrirPdfNoSistema(pdfFolha) }
        findViewById<Button>(R.id.btnCompartilharPdf).setOnClickListener {
            val pdf = pdfFolha
            if (pdf != null && pdf.exists()) compartilharPdf(pdf)
            else android.widget.Toast.makeText(this, R.string.pdf_not_found,
                android.widget.Toast.LENGTH_SHORT).show()
        }

        // ===== Botão 2: Enviar para impressora =====
        if (config.temImpressora() && primarioOk) {
            // Abre o seletor de modo (rede / sistema / pen-drive OTG / pasta),
            // herdado da BaseActivity — mesmo caminho usado no carrossel.
            btnImprimir.setOnClickListener { escolherModoImpressao(pdfFolha) }
            // "Ping" na impressora: só habilita o botão se ela responder na rede atual
            // (evita o caso clássico de o tablet ter trocado de Wi-Fi no meio do dia).
            verificarStatusImpressora()
        } else {
            // O botão NUNCA apaga: sem impressora de rede ainda dá para gravar
            // no pen-drive (OTG) ou copiar para a pasta de impressão. Quem fica
            // esmaecida é APENAS a opção "impressora da rede" dentro do seletor.
            btnImprimir.isEnabled = true
            btnImprimir.alpha = 1f
            txtImprInfo.text = "ℹ ${getString(R.string.printer_offline)}"
            txtImprInfo.visibility = View.VISIBLE
        }

        // Cadastro (nome/nascimento/sexo/prontuário) NÃO é editável no fluxo de
        // simulação: renomear a pasta/sessão no meio quebrava o PDF em andamento.
        // Editar cadastro fica só no carrossel (⋮) e nas Configurações.

        // ===== Botão 4: Adicionar mais fotos à simulação recém-criada =====
        // Volta ao MODO DE FOTOS completo (MainActivity) reconstruindo a sessão a
        // partir da pasta do paciente, permitindo refazer rosto/etiqueta e adicionar
        // posicionamentos/acessórios. Ao finalizar de novo, regenera o PDF da MESMA
        // simulação (sem incrementar a contagem).
        val nascimentoFin = sessionManager.dataNascimento
        btnAddFotos.setOnClickListener {
            btnAddFotos.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val fetcher = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this@FinalizarActivity)
                    val sims = withContext(Dispatchers.IO) { fetcher.buscarSimulacoes(nomePacienteAtual) }
                    // Pasta + numero: ver comentario em regerarPdf.
                    val sim = sims.find {
                        it.nomePastaCompleto == nomePastaFinalizada &&
                            it.numeroSimulacao == numSimFinalizado
                    } ?: sims.find { it.numeroSimulacao == numSimFinalizado }
                        ?: sims.maxByOrNull { it.timestampPrincipal }
                    withContext(Dispatchers.IO) {
                        sessionManager.limparSessao()
                        sessionManager.nomePaciente = nomePacienteAtual
                        val dCache = patientCache.obterDadosPaciente(nomePacienteAtual)
                        (dCache?.prontuario?.takeIf { it.isNotBlank() } ?: prontuarioFin.takeIf { it.isNotBlank() })
                            ?.let { sessionManager.prontuario = it }
                        (dCache?.nascimento?.takeIf { it.isNotBlank() } ?: nascimentoFin.takeIf { it.isNotBlank() })
                            ?.let { sessionManager.dataNascimento = it }
                        sim?.rosto?.let { sessionManager.adicionarCopiando(it.arquivoLocal, Category.FACE) }
                        sim?.etiqueta?.let { sessionManager.adicionarCopiando(it.arquivoLocal, Category.LABEL) }
                        sim?.posicionamentos?.forEach { sessionManager.adicionarCopiando(it.arquivoLocal, Category.POSITIONING) }
                        sim?.acessoriosLista?.forEach { sessionManager.adicionarCopiando(it.arquivoLocal, Category.ACCESSORIES) }
                        sim?.documentos?.forEach { sessionManager.adicionarCopiando(it.arquivoLocal, Category.DOCUMENTS) }
                        sessionManager.editandoNomePasta = nomePastaFinalizada
                        sessionManager.editandoNumSim = numSimFinalizado
                        sessionManager.marcarInicio()
                    }
                    val intent = Intent(this@FinalizarActivity, com.radioterapia.ai.MainActivity::class.java)
                    intent.putExtra(com.radioterapia.ai.MainActivity.EXTRA_CONTINUAR_SIMULACAO, true)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                } catch (e: Exception) {
                    btnAddFotos.isEnabled = true
                    android.widget.Toast.makeText(this@FinalizarActivity, getString(R.string.err_reopen, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        // ===== Botão final: encerrar e voltar pra Home =====
        btnEncerrar.setOnClickListener { voltarHome() }

    }

    /**
     * Abre o PDF no app padrão do sistema via FileProvider (item 8 - Visualizar PDF).
     * Se não houver app instalado, mostra Toast informativo (sem warning laranja).
     */
    private fun abrirPdfNoSistema(pdf: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", pdf)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                         Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this,
                getString(R.string.no_pdf_viewer),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Imprime sem chamar registrarHistorico de novo (já foi chamado em mostrarResultado).
     */
    /**
     * "Ping" na impressora (conexão TCP nas portas 9100/631, mais fiel ao ato de
     * imprimir do que ICMP). Enquanto verifica, o botão fica desabilitado; depois
     * mostra um aviso pequeno de online/offline. Tocar no aviso re-testa.
     */
    private fun verificarStatusImpressora() {
        // O botão NÃO é mais desabilitado: o seletor de modo oferece pen-drive
        // (OTG) e pasta de impressão, que funcionam sem impressora de rede.
        // O aviso de texto continua informando o estado da impressora IP.
        txtImprInfo.setTextColor(android.graphics.Color.parseColor("#B0BEC5"))
        txtImprInfo.text = getString(R.string.printer_checking)
        txtImprInfo.visibility = View.VISIBLE
        txtImprInfo.setOnClickListener(null)

        CoroutineScope(Dispatchers.Main).launch {
            val online = withContext(Dispatchers.IO) {
                try { PrinterClient(config.impressoraIp).testarConexao(3_000).sucesso }
                catch (_: Exception) { false }
            }
            if (online) {
                txtImprInfo.text = getString(R.string.printer_status_online, config.impressoraIp)
                txtImprInfo.setTextColor(android.graphics.Color.parseColor("#66BB6A"))
            } else {
                txtImprInfo.text = getString(R.string.printer_status_offline)
                txtImprInfo.setTextColor(android.graphics.Color.parseColor("#EF5350"))
            }
            txtImprInfo.visibility = View.VISIBLE
            // Re-testa ao tocar (ex.: depois de voltar para o Wi-Fi certo)
            txtImprInfo.setOnClickListener { verificarStatusImpressora() }
        }
    }


    /** Regenera o PDF da simulação recém-finalizada com a observação atual do box.
     *  O arquivo é o MESMO (sobrescrito): Visualizar/Imprimir já usam a versão nova. */
    /** Time-Out pela CONFIGURAÇÃO da clínica (null = página desativada).
     *  Médico vem do cadastro do paciente; sítio é opcional. */
    private fun montarTimeOut(): com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut? {
        if (!config.pdfIncluiTimeOut) return null
        val med = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutMedico)
            .text.toString().trim()
        val sit = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutSitio)
            .text.toString().trim()
        val equip = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutEquip)
            .text.toString().trim()
        val alergia =
            if (findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swAlergia).isChecked) "SIM" else ""
        val rosto = sessionManager.fotos
            .firstOrNull { it.categoria == Category.FACE }?.arquivo
        return com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut(
            medicoResponsavel = med,
            sitioTratamento = sit,
            riscoQueda = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swRisco).isChecked,
            precaucaoContato = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swPrec).isChecked,
            fotoRosto = rosto,
            equipamento = equip,
            alergia = alergia,
            fracoesMax = fracoesSelecionadas())
    }

    /**
     * Última fração prevista, do dropdown. 0 quando não informado.
     *
     * A primeira posição da lista é "não informar", e é onde ela abre: o número
     * de frações nem sempre está definido na hora da simulação, e obrigar uma
     * escolha transformaria um campo opcional em obrigatório — que é
     * exatamente o que o produto não faz.
     */
    private fun fracoesSelecionadas(): Int {
        val sp = findViewById<android.widget.Spinner>(R.id.spFracoes) ?: return 0
        return sp.selectedItemPosition.coerceAtLeast(0)
    }

    /** Protocolo escolhido para esta simulação. Vazio = nenhum marcado. */
    private var protocoloEscolhido: String = ""

    /**
     * Miniaturas dos protocolos, com um marcador acima de cada uma.
     *
     * COM UM SÓ PROTOCOLO A FAIXA NÃO APARECE, e ele é o escolhido — não há
     * decisão a tomar, e mostrar uma escolha única seria pedir um toque para
     * confirmar o óbvio.
     *
     * COM MAIS DE UM, NENHUM VEM MARCADO. Pré-selecionar o padrão faria a
     * escolha passar despercebida: quem não olhasse a faixa imprimiria o padrão
     * achando que tinha escolhido. Sem marcação, a ficha sai sem páginas
     * acrescentadas — que é o mesmo resultado do padrão, mas por decisão
     * explícita de quem não escolheu.
     */
    private fun desenharProtocolos() {
        val bloco = findViewById<View>(R.id.layoutProtocolos) ?: return
        val faixa = findViewById<android.widget.LinearLayout>(R.id.faixaProtocolos) ?: return
        val store = com.radioterapia.ai.protocolo.ProtocoloStore(this)
        val todos = store.listar()

        if (todos.size <= 1) {
            bloco.visibility = View.GONE
            protocoloEscolhido = todos.firstOrNull()?.id.orEmpty()
            return
        }
        bloco.visibility = View.VISIBLE
        protocoloEscolhido = ""
        faixa.removeAllViews()

        val d = resources.displayMetrics.density
        val lado = (120 * d).toInt()
        todos.forEach { p ->
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (10 * d).toInt() }
            }
            val marca = android.widget.ToggleButton(this).apply {
                textOn = getString(R.string.confirm)
                textOff = getString(R.string.prot_marcar)
                isChecked = false
                textSize = 11f
            }
            val img = android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(lado, (lado * 0.72f).toInt())
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                contentDescription = p.nome
                setPadding(4, 4, 4, 4)
            }
            val bmp = store.bitmapMiniatura(p)
            if (bmp != null) img.setImageBitmap(bmp) else img.setImageResource(R.drawable.bg_sem_foto)

            val nome = TextView(this).apply {
                text = p.nome
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@FinalizarActivity, R.color.text_primary))
                textSize = 12f
                maxWidth = lado
                maxLines = 2
            }
            col.addView(marca); col.addView(img); col.addView(nome)
            faixa.addView(col)

            marca.setOnClickListener {
                // Um só por vez: desmarca os outros. O contorno em volta da
                // miniatura é o que se vê de longe; o marcador diz o estado.
                protocoloEscolhido = if (marca.isChecked) p.id else ""
                atualizarRealceProtocolos(faixa, todos)
            }
        }
        atualizarRealceProtocolos(faixa, todos)
    }

    private fun atualizarRealceProtocolos(
        faixa: android.widget.LinearLayout,
        todos: List<com.radioterapia.ai.protocolo.ProtocoloStore.Protocolo>
    ) {
        for (i in 0 until faixa.childCount) {
            val col = faixa.getChildAt(i) as? android.widget.LinearLayout ?: continue
            val marca = col.getChildAt(0) as? android.widget.ToggleButton ?: continue
            val img = col.getChildAt(1) as? android.widget.ImageView ?: continue
            val p = todos.getOrNull(i) ?: continue
            val sel = p.id == protocoloEscolhido
            marca.isChecked = sel
            img.setBackgroundColor(if (sel) 0xFF119EE0.toInt() else 0x22FFFFFF)
        }
    }

    private fun configurarFracoes() {
        val sp = findViewById<android.widget.Spinner>(R.id.spFracoes) ?: return
        if (sp.adapter != null) return
        val itens = mutableListOf(getString(R.string.fin_fracoes_nenhuma))
        for (n in 1..40) itens.add(getString(R.string.fin_fracoes_n, n))
        sp.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, itens)
        sp.setSelection(0)
    }

    private var timeOutSelecionado: com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut? = null

    private var obsEmEdicao = false

    /** Pós-finalização: box ESCURO, texto claro, não-editável; botão vira "Editar". */
    /** Resumo pós-finalização: acrescenta médico, sítio, riscos, alergia e
     *  observações às identificações do box do topo. */
    private fun atualizarResumoFinal() {
        val sb = StringBuilder(resumoBaseSnapshot)
        fun linha(rotulo: String, valor: String) {
            if (valor.isNotBlank()) sb.append("\n").append(rotulo).append(": ").append(valor)
        }
        linha("MÉDICO", findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutMedico)
            .text.toString().trim())
        linha("EQUIPAMENTO", findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutEquip)
            .text.toString().trim())
        linha("SÍTIO / TOPOGRAFIA", findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutSitio)
            .text.toString().trim())
        val riscos = mutableListOf<String>()
        if (findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swRisco).isChecked) riscos.add("RISCO DE QUEDA")
        if (findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swPrec).isChecked) riscos.add("PRECAUÇÃO DE CONTATO")
        linha("RISCOS", if (riscos.isEmpty()) "—" else riscos.joinToString(" • "))
        linha("ALERGIA",
            if (findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swAlergia).isChecked) "SIM"
            else "NÃO / NÃO INFORMADO")
        linha("OBSERVAÇÕES", findViewById<android.widget.EditText>(R.id.edtObservacao)
            .text.toString().trim())
        txtResumo.text = sb.toString()
    }

    /** Compartilha o PDF gerado como anexo (WhatsApp, Gmail, Drive, etc.). */
    private fun compartilharPdf(pdf: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", pdf)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_pdf)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this,
                getString(R.string.share_failed, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Seleção "dropdown-first": 1º toque abre a lista; digitar é a exceção
    // (item "✏︎ Digitar outro…"). Detalhes que evitam o popup "piscar":
    //  • o campo permanece FOCÁVEL (ACTV não-focável fecha o dropdown no
    //    mesmo frame) — a digitação é bloqueada por keyListener = null;
    //  • adapter SEM filtro: com valor pré-preenchido, o filtro padrão
    //    esvaziava a lista e o popup fechava sozinho.
    private fun configurarSelecao(act: android.widget.AutoCompleteTextView,
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
        // ITEM 8: sem opções configuradas → campo 100% livre para digitar
        // (não faz sentido "dropdown-first" com lista vazia).
        if (opcoes.isEmpty()) {
            act.setAdapter(null)
            act.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            return
        }
        act.setAdapter(adapter)
        act.threshold = 1
        // Lista de 1 item sem digitação livre (equipamento): pré-seleciona.
        if (!permiteDigitar && opcoes.size == 1 && act.text.isNullOrBlank())
            act.setText(opcoes[0], false)
        act.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_dropdown_arrow, 0)
        val teclado = act.keyListener   // guardado p/ o modo digitação
        var digitando = false
        fun modoSelecao() {
            digitando = false
            act.keyListener = null
            act.isCursorVisible = false
        }
        modoSelecao()
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        fun abrirLista() {
            imm.hideSoftInputFromWindow(act.windowToken, 0)
            act.requestFocus()
            act.post { act.showDropDown() }
        }
        act.setOnClickListener { if (!digitando) abrirLista() }
        act.setOnFocusChangeListener { _, temFoco ->
            if (temFoco && !digitando) act.post { act.showDropDown() }
        }
        act.setOnItemClickListener { _, _, pos, _ ->
            if (permiteDigitar && pos == itens.size - 1) {
                digitando = true
                act.setText("", false)
                act.keyListener = teclado
                act.isCursorVisible = true
                act.requestFocus()
                imm.showSoftInput(act, 0)
            } else {
                act.setText(itens[pos], false)
                modoSelecao()
            }
        }
    }

    /** Repopula o adapter do dropdown de médicos (corpo clínico + digitação). */

    /** FASE 2 — dados prontos: some com os campos editáveis (o box azul já traz
     *  tudo) e mostra o alerta + Visualizar PDF / Imprimir / Compartilhar /
     *  Editar dados / Voltar ao Menu. */
    private fun mostrarFaseResultado() {
        simulacaoFinalizada = true
        btnToolbarVoltar?.visibility = View.INVISIBLE
        obsEmEdicao = false
        tituloAtualRes = R.string.fin_done_title
        title = getString(R.string.fin_done_title)
        findViewById<TextView>(R.id.txtFinTitulo).setText(R.string.fin_done_title)
        findViewById<View>(R.id.layoutTimeOutCampos).visibility = View.GONE
        findViewById<View>(R.id.layoutObservacao).visibility = View.GONE
        findViewById<View>(R.id.layoutFracoes).visibility = View.GONE
        findViewById<View>(R.id.layoutProtocolos).visibility = View.GONE
        layoutAcoes.visibility = View.GONE
        findViewById<View>(R.id.layoutEdicao).visibility = View.GONE
        txtProgresso.visibility = View.GONE
        layoutResultado.visibility = View.VISIBLE
    }

    /** FASE 1-b — "Editar dados": devolve os campos editáveis com os valores
     *  atuais e troca as ações por Salvar alterações / Editar cadastro / Fotos. */
    private fun entrarModoEdicao() {
        obsEmEdicao = true
        tituloAtualRes = R.string.fin_edit_title
        title = getString(R.string.fin_edit_title)
        findViewById<TextView>(R.id.txtFinTitulo).setText(R.string.fin_edit_title)
        layoutResultado.visibility = View.GONE
        layoutAcoes.visibility = View.GONE
        findViewById<View>(R.id.layoutTimeOutCampos).visibility =
            if (config.pdfIncluiTimeOut) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutObservacao).visibility = View.VISIBLE
        findViewById<View>(R.id.layoutFracoes).visibility = View.VISIBLE
        configurarFracoes()
        desenharProtocolos()
        findViewById<View>(R.id.layoutEdicao).visibility = View.VISIBLE
        setDetalhesTimeOutHabilitados(true)
        ligarCoresDosAlertas()
        animarBlocosDaFinalizacao()
        val edt = findViewById<android.widget.EditText>(R.id.edtObservacao)
        edt.isEnabled = true
        edt.setBackgroundResource(R.drawable.bg_input_white)
        edt.setTextColor(0xFF212121.toInt())
        edt.setHintTextColor(0xFF9E9E9E.toInt())
    }

    /**
     * Acende cada linha de alerta com a cor que ela produz na ficha.
     *
     * Os valores sao os MESMOS do PdfBuilder (`ativos.add(Tag(...))`): mudar um
     * lado sem o outro quebraria a correspondencia que faz a tela valer.
     */
    /**
     * Faz o bloco do Time-Out crescer e encolher com altura animada.
     *
     * POR QUE NAO UM ACCORDION QUE FECHA. A tentacao era transformar a secao num
     * painel dobravel com cabecalho clicavel. Medido contra o uso: esta tela e
     * preenchida UMA vez por paciente, com ele deitado na mesa esperando — e o
     * bloco so aparece quando o servico usa Time-Out. Fechar por padrao
     * acrescentaria um toque obrigatorio no caminho critico, e "cliques custam"
     * e restricao escrita deste produto.
     *
     * O que o accordion tem de util aqui e o MOVIMENTO: o bloco aparece e some
     * conforme a configuracao, e o protocolo entra e sai conforme o numero de
     * protocolos cadastrados. Sem transicao, essas mudancas sao saltos — a tela
     * pisca e o resto do formulario pula de lugar. Com ela, o conteudo empurra
     * o que esta embaixo, e o olho acompanha.
     */
    private fun animarBlocosDaFinalizacao() {
        val M = com.radioterapia.ai.ui.anim.Movimento
        M.animarMudancasDeLayout(findViewById(R.id.layoutTimeOutCampos))
        M.animarMudancasDeLayout(
            findViewById<View>(R.id.layoutTimeOutCampos)?.parent as? android.view.ViewGroup)
    }

    private fun ligarCoresDosAlertas() {
        val M = com.radioterapia.ai.ui.anim.Movimento
        M.toggleAlerta(findViewById(R.id.linhaRisco), findViewById(R.id.swRisco),
                       0xFFFFE082.toInt())   // risco de queda — amarelo
        M.toggleAlerta(findViewById(R.id.linhaPrec), findViewById(R.id.swPrec),
                       0xFFFFB74D.toInt())   // precaucao de contato — laranja
        M.toggleAlerta(findViewById(R.id.linhaAlergia), findViewById(R.id.swAlergia),
                       0xFFEF5350.toInt())   // alergia — vermelho
    }

    /** Habilita/trava os campos do Time-Out. */
    private fun setDetalhesTimeOutHabilitados(hab: Boolean) {
        listOf(R.id.actTimeoutMedico, R.id.actTimeoutEquip, R.id.actTimeoutSitio,
               R.id.swRisco, R.id.swPrec, R.id.swAlergia).forEach {
            findViewById<View>(it).isEnabled = hab
        }
    }

    private fun atualizarObservacaoNoPdf() {
        val pdf = pdfGeradoAtual ?: return
        val nome = nomePacienteFinalizado
        // Todas as leituras de UI acontecem AQUI (thread principal). Ler Views
        // dentro de Dispatchers.IO lançava "Only the original thread that created
        // a view hierarchy can touch its views".
        val obs = findViewById<android.widget.EditText>(R.id.edtObservacao).text.toString().trim()
        val medUi = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutMedico).text.toString().trim()
        val sitioUi = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutSitio).text.toString().trim()
        val equipUi = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutEquip).text.toString().trim()
        val riscoUi = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swRisco).isChecked
        val precUi = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swPrec).isChecked
        val alergiaUi = if (findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swAlergia).isChecked) "SIM" else ""
        val btn = findViewById<android.widget.Button>(R.id.btnAtualizarObs)
        btn.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    val sims = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this@FinalizarActivity)
                        .buscarSimulacoes(nome)
                    val sim = sims.find { it.numeroSimulacao == numSimFinalizado }
                        ?: sims.maxByOrNull { it.timestampPrincipal } ?: return@withContext false
                    val fotos = mutableListOf<File>()
                    val rotulos = mutableListOf<String>()
                    sim.rosto?.let { fotos.add(it.arquivoLocal); rotulos.add("Rosto") }
                    sim.etiqueta?.let { fotos.add(it.arquivoLocal); rotulos.add("Etiqueta") }
                    sim.posicionamentos.forEachIndexed { i, f ->
                        fotos.add(f.arquivoLocal); rotulos.add("Posicionamento." + (i + 1)) }
                    sim.acessoriosLista.forEachIndexed { i, f ->
                        fotos.add(f.arquivoLocal); rotulos.add("Acessório." + (i + 1)) }
                    if (fotos.isEmpty()) return@withContext false
                    val cfg = com.radioterapia.ai.AppConfig(this@FinalizarActivity)
                    val dados = com.radioterapia.ai.pdf.PdfBuilder.DadosCabecalho(
                        nomePaciente = nome,
                        nascimento = patientCache.obterDadosPaciente(nome)?.nascimento ?: "",
                        prontuario = patientCache.obterDadosPaciente(nome)?.prontuario ?: "",
                        idsExtras = emptyList(),
                        dataSimulacao = java.util.Date(sim.timestampPrincipal),
                        numeroSimulacao = sim.numeroSimulacao,
                        nomeClinica = cfg.nomeClinica,
                        sexo = patientCache.obterDadosPaciente(nome)?.sexo ?: "",
                        medicoAssistente = patientCache.obterDadosPaciente(nome)?.medicoAssistente ?: ""
                    )
                    // "Editar detalhes": regrava as escolhas atuais antes de regenerar
                    val pastaReal = com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
                        this@FinalizarActivity, nome, sim.numeroSimulacao, sim.nomePastaCompleto,
                        sessionManager.prontuario)
                    if (config.pdfIncluiTimeOut) {
                        val novoReg = com.radioterapia.ai.util.TimeOutStore.Registro(
                            true, medUi, sitioUi, riscoUi, precUi, equipUi, alergiaUi,
                            fracoesSelecionadas(), protocoloEscolhido)
                        com.radioterapia.ai.util.TimeOutStore.gravar(
                            pastaReal, sim.numeroSimulacao, novoReg)
                        com.radioterapia.ai.util.ObsStore.gravar(
                            pastaReal, sim.numeroSimulacao, obs)
                        novoReg.equipamento.takeIf { it.isNotBlank() }?.let {
                            patientCache.atualizarEquipamento(nome, it, sessionManager.prontuario)
                        }
                        novoReg.medico.takeIf { it.isNotBlank() }?.let {
                            patientCache.atualizarSexoMedico(nome, "", it, sessionManager.prontuario)
                        }
                        // NÃO tocar em View aqui: este bloco roda em
                        // Dispatchers.IO. O resumo é atualizado na thread
                        // principal, junto com mostrarFaseResultado().
                    }
                    val toReg = com.radioterapia.ai.util.TimeOutStore.ler(
                        com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
                        this@FinalizarActivity, nome, sim.numeroSimulacao, sim.nomePastaCompleto,
                        sessionManager.prontuario), sim.numeroSimulacao)
                    val to = if (toReg != null && toReg.ativo)
                        com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut(
                            toReg.medico, toReg.sitio, toReg.riscoQueda,
                            toReg.precaucaoContato, sim.rosto?.arquivoLocal,
                            toReg.equipamento, toReg.alergia, toReg.fracoesMax)
                    else null
                    com.radioterapia.ai.pdf.PdfBuilder.gerarFolhaPosicionamento(
                        this@FinalizarActivity, dados, fotos, pdf,
                        rotulos = rotulos, landscape = cfg.pdfLandscape,
                        etiquetaLarguraMm = cfg.pdfEtiquetaLarguraMm,
                        etiquetaAlturaMm = cfg.pdfEtiquetaAlturaMm,
                        observacoes = obs,
                        timeOut = to,
                        margemImpressaoMm = cfg.pdfMargemMm,
                        protocoloId = toReg?.protocoloId.orEmpty())
                    true
                }
                btn.isEnabled = true
                if (ok) { atualizarResumoFinal(); mostrarFaseResultado() }
                android.widget.Toast.makeText(this@FinalizarActivity,
                    if (ok) getString(R.string.obs_updated) else getString(R.string.sim_not_found),
                    android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                btn.isEnabled = true
                android.widget.Toast.makeText(this@FinalizarActivity,
                    "Erro: " + e.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun registrarHistorico(nomePaciente: String) {
        if (sessionManager.editandoNomePasta.isNotBlank()) {
            // Modo edição: apenas atualiza a data e mantém tudo; NÃO incrementa a contagem.
            patientCache.tocarUltimaSimulacao(nomePaciente,
                sessionManager.prontuario, sessionManager.dataNascimento)
        } else {
            patientCache.registrarSimulacao(nomePaciente,
                sessionManager.prontuario,
                sessionManager.dataNascimento)
        }
        // Paciente simulado entra automaticamente na lista local de "em tratamento".
        com.radioterapia.ai.treatment.TreatmentListManager(this).alocar(nomePaciente)
        auditLogger.registrar(
            AuditLogger.Tipo.FINISH,
            "Simulação finalizada",
            mapOf(
                "paciente" to nomePaciente,
                "prontuario" to sessionManager.prontuario,
                "fotos" to sessionManager.quantidade()
            )
        )
    }

    /**
     * Versão completa: registra no histórico + salva caminho da foto-rosto para
     * thumbnail no histórico. A foto-rosto é copiada para filesDir/historico_thumbs/
     * para sobreviver a limpezas de cache.
     */
    private fun registrarHistoricoCompleto(nomePaciente: String) {
        registrarHistorico(nomePaciente)

        val fotoRosto = sessionManager.fotosOrdenadasParaPdf().firstOrNull { fo ->
            fo.categoria == Category.FACE
        }?.arquivo
        if (fotoRosto != null && fotoRosto.exists()) {
            try {
                val thumbsDir = File(filesDir, "historico_thumbs").apply { mkdirs() }
                val nomeArqSeguro = nomePaciente.replace(Regex("[^A-Za-z0-9]"), "_").take(40)
                val destino = File(thumbsDir,
                    "${nomeArqSeguro}_${System.currentTimeMillis()}.jpg")
                fotoRosto.copyTo(destino, overwrite = true)
                patientCache.salvarCaminhoFotoRosto(nomePaciente, destino.absolutePath)
            } catch (_: Exception) { /* silencioso - thumbnail é nice-to-have */ }
        }
    }

    private fun voltarHome() {
        // Volta direto pra HomeActivity (limpa stack)
        val intent = Intent(this, com.radioterapia.ai.HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }


    // ============= GERAÇÃO PDF =============

    private fun gerarFolhaPosicionamento(nome: String, fotos: List<File>, numSim: Int, nomeNorm: String,
                                         timeOut: com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut? = null): File {
        val data = SimpleDateFormat("dd_MMM_yyyy__HH_mm_ss", Locale("pt", "BR")).format(Date())
        val pdfFile = File(cacheDir, "pdf_folha_${nomeNorm.replace(" ", "_")}_${data}.pdf")
        val extrasSession = sessionManager.obterIdsExtras().map {
            PdfBuilder.IdExtra(it.first, it.second)
        }
        val dados = PdfBuilder.DadosCabecalho(
            nomePaciente = nome,
            nascimento = com.radioterapia.ai.util.DateUtils.formatarNascimento(
                sessionManager.dataNascimento,
                com.radioterapia.ai.i18n.LocaleManager.obterIdiomaAtual(this)),
            prontuario = sessionManager.prontuario,
            idsExtras = extrasSession,
            dataSimulacao = Date(),
            numeroSimulacao = numSim,
            nomeClinica = config.nomeClinica,
            sexo = patientCache.obterDadosPaciente(nome)?.sexo ?: "",
            medicoAssistente = patientCache.obterDadosPaciente(nome)?.medicoAssistente ?: ""
        )
        return PdfBuilder.gerarFolhaPosicionamento(this, dados, fotos, pdfFile,
            rotulos = sessionManager.rotulosParaPdf(), landscape = config.pdfLandscape,
            etiquetaLarguraMm = config.pdfEtiquetaLarguraMm,
            etiquetaAlturaMm = config.pdfEtiquetaAlturaMm,
            observacoes = observacoesTexto,
            timeOut = timeOut,
            margemImpressaoMm = config.pdfMargemMm,
            protocoloId = protocoloEscolhido)
    }


    // ============= ENVIO SMB =============


    /** Identifica o tipo da foto pelo índice na lista ordenada (rosto, etiqueta, posicionamento). */
    private fun identificarTipo(idx: Int, total: Int): String {
        return when (idx) {
            0 -> "Rosto"
            1 -> "Etiqueta"
            else -> "Posicionamento"
        }
    }

    // ============= BACKUP LOCAL =============

    /**
     * @param nomePastaPac nome da pasta do paciente ("NOME - PRONTUÁRIO"), calculado
     *   por quem chama e usado TAMBÉM para gravar Time-Out e observações. Antes esta
     *   função recebia um `nomePasta` que era ignorado no corpo — a pasta era
     *   recalculada aqui dentro — enquanto o chamador usava aquele valor morto para
     *   escrever o Time-Out num diretório que nunca existiu.
     */
    private fun salvarTodasLocalmente(
        fotos: List<File>,
        tipos: List<String>,
        pdfFolha: File,
        nomeNorm: String,
        nomePastaPac: String,
        numSim: Int
    ): List<String> {
        val erros = mutableListOf<String>()
        val tagSim = if (numSim == 1) "" else "_NOVASIM${numSim - 1}"
        val prefArq = nomeNorm.trim().uppercase().replace(Regex("\\s+"), " ").replace(" ", "_")

        // Padrão dos arquivos: NOME_TIPO[_NOVASIMx]_dd-MMM-aaaa_HH-mm-ss_n.jpg
        val fotosNomeadas = fotos.mapIndexed { idx, arq ->
            val data = SimpleDateFormat("dd-MMM-yyyy_HH-mm-ss", Locale("pt", "BR")).format(arq.lastModified()).uppercase()
            val tipo = (tipos.getOrNull(idx) ?: identificarTipo(idx, fotos.size)).uppercase()
            "${prefArq}_${tipo}${tagSim}_${data}_${idx + 1}.jpg" to arq
        }
        val dataPdf = SimpleDateFormat("dd-MMM-yyyy_HH-mm-ss", Locale("pt", "BR")).format(Date()).uppercase()
        val nomePdf = "${prefArq}_FOLHA_SIMULACAO${tagSim}_$dataPdf.pdf"

        // ORIGINAIS: mesmo nome da foto processada, com "_ORIGINAL" antes da
        // extensão. Vão para a MESMA pasta do paciente, sem qualquer edição —
        // só renomeados — para o FileSync levar também o quadro cheio.
        // Cada par fica lado a lado e é óbvio qual original pertence a qual foto.
        val originaisNomeados = fotosNomeadas.mapNotNull { (nome, arq) ->
            sessionManager.originalDe(arq)?.let { orig ->
                nome.removeSuffix(".jpg") + "_ORIGINAL.jpg" to orig
            }
        }

        // ===== Caminho SAF (somente se o usuário escolheu uma pasta pública específica) =====
        val uriSaf = config.pastaFotosUri
        if (uriSaf.isNotBlank()) {
            try {
                val base = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(uriSaf))
                    ?: throw Exception("Pasta inválida")
                val photos = obterOuCriarPasta(obterOuCriarPasta(base, "PhotoID_RT"), "PHOTOS")
                    ?: throw Exception("Não foi possível criar PhotoID_RT/PHOTOS")
                val pastaPac = obterOuCriarPasta(photos, nomePastaPac)
                    ?: throw Exception("Não foi possível criar a pasta do paciente")
                fotosNomeadas.forEach { (nome, origem) ->
                    try { gravarSaf(pastaPac, nome, "image/jpeg", origem) }
                    catch (e: Exception) { erros.add("$nome: ${e.message}") }
                }
                // Originais sem edição, ao lado das processadas.
                originaisNomeados.forEach { (nome, origem) ->
                    try { gravarSaf(pastaPac, nome, "image/jpeg", origem) }
                    catch (e: Exception) { erros.add("$nome: ${e.message}") }
                }
                try { gravarSaf(pastaPac, nomePdf, "application/pdf", pdfFolha) }
                catch (e: Exception) { erros.add("$nomePdf (PDF): ${e.message}") }
                fotosNomeadas.forEach { (nome, origem) ->
                    try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) salvarImgMediaStore(origem, nome, "Pictures/PhotoID_RT") } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                erros.add("Pasta local: ${e.message}")
            }
            return erros
        }

        // ===== Caminho padrão: PhotoID_RT/PHOTOS/<PACIENTE> via File I/O.
        // StorageLocal grava na RAIZ do armazenamento (/storage/emulated/0/PhotoID_RT)
        // quando há "Acesso a todos os arquivos"; senão, na pasta interna do app. =====
        try {
            com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
            val photos = File(com.radioterapia.ai.util.StorageLocal.photos(this), nomePastaPac).apply { mkdirs() }
            // Modo edição: remove os arquivos ANTIGOS desta MESMA simulação (mesmo tag)
            // para não duplicar ao regravar o conjunto completo (antigas + novas).
            if (sessionManager.editandoNomePasta.isNotBlank()) {
                photos.listFiles()?.forEach { arq ->
                    // Subpasta fica de fora: ARQUIVADAS/ guarda o que foi
                    // substituido e nao pode ser varrida junto com os arquivos
                    // da simulacao que esta sendo regravada.
                    if (arq.isDirectory) return@forEach
                    val n = arq.name
                    val ehDestaSim = if (numSim == 1) !n.contains("_NOVASIM") else n.contains("_NOVASIM${numSim - 1}")
                    if (ehDestaSim) arq.delete()
                }
            }
            fotosNomeadas.forEach { (nome, origem) ->
                try { origem.copyTo(File(photos, nome), overwrite = true) }
                catch (e: Exception) { erros.add("$nome: ${e.message}") }
            }
            // Originais sem edição, ao lado das processadas.
            originaisNomeados.forEach { (nome, origem) ->
                try { origem.copyTo(File(photos, nome), overwrite = true) }
                catch (e: Exception) { erros.add("$nome: ${e.message}") }
            }

            try { pdfFolha.copyTo(File(photos, nomePdf), overwrite = true) }
            catch (e: Exception) { erros.add("$nomePdf (PDF): ${e.message}") }

            // ARQUIVADAS: o que foi substituido enquanto o paciente estava na
            // sala mora na pasta de trabalho da sessao, que e temporaria. Sem
            // este passo, o arquivamento seria apagado junto com o rascunho e
            // nao teria servido para nada.
            try {
                com.radioterapia.ai.util.FotosArquivadas.transferir(
                    sessionManager.pastaDeTrabalho(), photos)
            } catch (_: Exception) {}

            // Cópia das FOTOS no rolo da câmera (renomeadas). O rolo não aceita PDF.
            fotosNomeadas.forEach { (nome, origem) ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        salvarImgMediaStore(origem, nome, "Pictures/PhotoID_RT")
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            erros.add("Pasta local: ${e.message}")
        }
        return erros
    }

    /** Cria a subpasta (ou reutiliza se já existir) dentro de um DocumentFile. */
    private fun obterOuCriarPasta(pai: androidx.documentfile.provider.DocumentFile?,
                                  nome: String): androidx.documentfile.provider.DocumentFile? {
        if (pai == null) return null
        return pai.findFile(nome)?.takeIf { it.isDirectory } ?: pai.createDirectory(nome)
    }

    /** Grava um arquivo dentro de uma pasta SAF (DocumentFile), sobrescrevendo se já existir. */
    private fun gravarSaf(pasta: androidx.documentfile.provider.DocumentFile, nome: String,
                          mime: String, origem: File) {
        pasta.findFile(nome)?.delete()
        val doc = pasta.createFile(mime, nome) ?: throw Exception("Falha ao criar $nome")
        contentResolver.openOutputStream(doc.uri)?.use { saida ->
            origem.inputStream().use { it.copyTo(saida) }
        } ?: throw Exception("Falha ao abrir $nome")
    }

    private fun salvarImgMediaStore(origem: File, nome: String, caminho: String) {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, caminho)
        }
        val uri: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw Exception("Falha criar imagem")
        contentResolver.openOutputStream(uri)?.use { saida ->
            origem.inputStream().use { it.copyTo(saida) }
        }
    }




    /**
     * Normalização de nome — DELEGA para StorageLocal.
     *
     * Havia aqui uma segunda implementação, própria desta tela. Duas
     * normalizações diferentes para a mesma coisa é como pastas divergem: basta
     * uma tratar um caractere de um jeito e o mesmo paciente ganha duas pastas.
     * A fonte única é StorageLocal, que é quem resolve pasta.
     */
    private fun normalizarNome(nome: String): String =
        com.radioterapia.ai.util.StorageLocal.removerAcentosMaiusculas(nome)
}
