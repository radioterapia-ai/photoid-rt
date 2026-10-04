package com.radioterapia.ai.ui

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
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
import com.radioterapia.ai.sync.EstadoSyncFinalizacao
import com.radioterapia.ai.sync.MotorSync
import com.radioterapia.ai.sync.SyncWorker
import com.radioterapia.ai.treatment.TreatmentPhotoFetcher
import com.radioterapia.ai.util.FotosArquivadas
import com.radioterapia.ai.util.Linhas
import com.radioterapia.ai.util.NomeArquivo
import com.radioterapia.ai.util.StorageLocal
import com.radioterapia.ai.util.TimeOutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

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
    private lateinit var txtSyncInfo: TextView
    /** As duas linhas de estado (sincronia e impressora), acima do cartão de resumo. */
    private lateinit var layoutStatusFin: View
    /** Pílulas dos alertas ativos, dentro do cartão de resumo. */
    private lateinit var boxFinAlertas: LinearLayout

    /** Caminho do PDF gerado nesta finalização, p/ o "Visualizar PDF". */
    private var pdfGeradoAtual: File? = null

    /**
     * Nome das cópias da ficha que saem do tablet (pen-drive, pasta de
     * impressão, compartilhar), com o nome completo do paciente. A ficha
     * guardada e a de cache levam as iniciais. Vazio = usar o nome do arquivo.
     */
    private var nomeEntregaPdf: String = ""

    /** Nome do paciente cuja simulação foi finalizada nesta tela (para "Editar cadastro"). */
    private var nomePacienteFinalizado: String = ""
    private var numSimFinalizado: Int = 1
    /** Nome do paciente vigente (pode mudar após "Editar cadastro"). */
    private var nomePacienteAtual: String = ""
    /** Pasta local da simulação recém-finalizada (recomputada após rename). */
    private var nomePastaFinalizada: String = ""

    /**
     * Identificação com que a simulação foi finalizada, guardada antes de a
     * sessão ser limpa. "Salvar alterações" e "Adicionar mais fotos" usam ESTA,
     * e não uma busca no cadastro pelo nome: entre homônimas, a busca sem
     * prontuário devolve o registro mais completo, que pode ser o de outra
     * paciente — e o prontuário decide a pasta onde se grava e apaga.
     */
    private var prontuarioFinalizado: String = ""
    private var nascimentoFinalizado: String = ""
    private var idsExtrasFinalizados: List<Pair<String, String>> = emptyList()

    /**
     * Identificações + contagem de fotos capturadas ANTES de limpar a sessão,
     * já com os spans do cartão. CharSequence, e não String: uma String
     * perderia a hierarquia (cores, negrito, recuo) na reconstrução do resumo
     * depois de finalizar.
     */
    private var resumoBaseSnapshot: CharSequence = ""

    /**
     * Pasta da simulação finalizada, a mesma em que Time-Out e observação foram
     * gravados. A linha de sincronia confere nela, depois do envio, se o que a
     * finalização gravou chegou aos destinos.
     */
    private var pastaSimFinalizada: File? = null

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
        txtSyncInfo = findViewById(R.id.txtSyncInfo)
        layoutStatusFin = findViewById(R.id.layoutStatusFin)
        boxFinAlertas = findViewById(R.id.boxFinAlertas)
        edtObservacao = findViewById(R.id.edtObservacao)

        // Até 4 LINHAS digitadas, a mesma regra da edição da simulação e do
        // diálogo do Tratamento. A caixa de observações da ficha cresce com o
        // texto e mostra 4 linhas no corpo cheio; linha longa que quebra faz a
        // letra diminuir até caber, e só abaixo do piso o texto é cortado. O
        // limite aqui impede de digitar o que a ficha já não mostraria inteiro.
        CampoObservacao.limitar(edtObservacao)

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

        /*
            ESTES DOIS TAMBÉM TÊM DE SER CHAMADOS AQUI.

            Pelo mesmo motivo de desenharProtocolos(), escrito logo acima:
            chamados só em entrarModoEdicao(), eles não rodam na Fase 1.

            configurarFracoes() tinha um chamador só. Sem ele aqui, o Spinner
            de frações ficava SEM ADAPTER na Fase 1: o técnico via o rótulo
            "Número máximo de visitas / frações" ao lado de uma caixa que não
            abria nada, e fracoesSelecionadas() devolvia 0 — "não informado",
            sem erro. O realce da última fração na grade da ficha, que tem dez
            linhas de justificativa no PdfBuilder, era impossível de ligar pelo
            caminho normal.

            ligarCoresDosAlertas() tinha um chamador só. Sem ele aqui, acionar
            risco de queda, precaução de contato ou alergia na Fase 1 — que é o
            único momento em que o técnico os liga, com o paciente na mesa —
            não pintava coisa nenhuma. O espelho tela/papel só acendia depois
            de a ficha já ter sido impressa, que é quando ele não serve mais.
            O comentário do layout já afirmava o contrário: "quem liga o
            interruptor vê na hora a tarja que vai sair impressa".
         */
        configurarFracoes()
        ligarCoresDosAlertas()

        // ===== TIME-OUT: rotina da clínica (config). Sítio é OPCIONAL. =====
        findViewById<View>(R.id.layoutTimeOutCampos).visibility =
            if (config.pdfIncluiTimeOut) View.VISIBLE else View.GONE
        // Seleção "dropdown-first": o 1º toque abre a lista (a rotina); digitar um
        // valor fora dela é a exceção, pelo item "✏︎ Digitar outro…" do dropdown.
        val actSit = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutSitio)
        configurarSelecao(actSit, Linhas.deTexto(config.sitiosLista), true)
        val actMedTo = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutMedico)
        configurarSelecao(actMedTo, Linhas.deTexto(config.timeoutMedicos), true)
        cadastroDe(sessionManager.nomePaciente, sessionManager.prontuario)?.medicoAssistente
            ?.takeIf { it.isNotBlank() }?.let { actMedTo.setText(it, false) }
        val actEquip = findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutEquip)
        configurarSelecao(actEquip, Linhas.deTexto(config.equipamentos), false)
        cadastroDe(sessionManager.nomePaciente, sessionManager.prontuario)?.equipamento
            ?.takeIf { it.isNotBlank() }?.let { actEquip.setText(it, false) }

        // O protocolo marcado é campo desta classe, e não uma View: na recriação
        // da Activity ele não volta sozinho, e a ficha sairia sem as páginas do
        // serviço sem ninguém ter desmarcado nada.
        savedInstanceState?.getString(ESTADO_PROTOCOLO)?.let {
            protocoloEscolhido = it
            desenharProtocolos()
        }

        // Rodada de fotos adicionais da MESMA simulação: Time-Out e observação
        // já estão gravados, e a confirmação regrava os dois por inteiro. A
        // tela começa com o que está gravado; vazia, a regravação apagaria
        // alertas, sítio, frações e observação que ninguém desligou.
        // Na recriação com a carga já feita, os campos voltam pelo estado salvo
        // das Views, e a carga não se repete (PrefillEdicao.precisaCarregar).
        val cargaRestaurada = savedInstanceState?.getBoolean(ESTADO_CARGA_EDICAO, false) == true
        if (PrefillEdicao.precisaCarregar(sessionManager.editandoNomePasta.isNotBlank(), cargaRestaurada)) {
            carregarGravadoDaEdicao()
        } else {
            cargaEdicaoFeita = cargaRestaurada
        }

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

    /**
     * Preenche a primeira fase com o Time-Out e a observação gravados para a
     * simulação reaberta, lidos da MESMA pasta em que [executarFinalizacao] vai
     * gravar: o mesmo nome de pasta, o mesmo número, o mesmo prontuário.
     *
     * GUARDA: o botão de confirmar fica travado até a leitura voltar. Confirmar
     * antes regravaria a tela ainda vazia por cima do registro.
     *
     * Sem registro, a tela fica como a de uma simulação nova. O que o técnico
     * mexer enquanto a leitura corre não é sobrescrito ([PrefillEdicao.aplicar]).
     */
    private fun carregarGravadoDaEdicao() {
        // Tudo o que vem da sessão e da tela é lido aqui, na thread principal.
        val nome = sessionManager.nomePaciente
        val numSim = sessionManager.editandoNumSim
        val prontuario = sessionManager.prontuario
        val nomePastaPac = RegrasFinalizacao.pastaDaFinalizacao(
            sessionManager.editandoNomePasta, StorageLocal.removerAcentosMaiusculas(nome), prontuario)
        val antes = camposDaTela()
        btnConfirmar.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val (gravado, obsGravada) = withContext(Dispatchers.IO) {
                    val pasta = StorageLocal.resolverPastaSim(
                        this@FinalizarActivity, nome, numSim, nomePastaPac, prontuario)
                    com.radioterapia.ai.util.TimeOutStore.ler(pasta, numSim) to
                        com.radioterapia.ai.util.ObsStore.ler(pasta, numSim)
                }
                if (!isFinishing && !isDestroyed) {
                    val agora = camposDaTela()
                    mostrarCampos(PrefillEdicao.aplicar(antes, agora, gravado, obsGravada,
                        maxFracoes()), agora)
                    cargaEdicaoFeita = true
                }
            } catch (_: Exception) {
                // Sem leitura, a tela fica como está: a de uma simulação nova.
            } finally {
                btnConfirmar.isEnabled = true
            }
        }
    }

    /**
     * A carga da edição já pôs o gravado na tela. Vai para o estado salvo
     * junto com o protocolo marcado ([onSaveInstanceState]).
     */
    private var cargaEdicaoFeita = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ESTADO_CARGA_EDICAO, cargaEdicaoFeita)
        outState.putString(ESTADO_PROTOCOLO, protocoloEscolhido)
    }

    private companion object {
        const val ESTADO_CARGA_EDICAO = "fin_carga_edicao_feita"
        const val ESTADO_PROTOCOLO = "fin_protocolo_escolhido"
    }

    /** Os campos da primeira fase que a carga da edição pode preencher. */
    private fun camposDaTela(): PrefillEdicao.Campos {
        fun texto(id: Int) =
            findViewById<android.widget.AutoCompleteTextView>(id).text.toString().trim()
        fun ligado(id: Int) =
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(id).isChecked
        return PrefillEdicao.Campos(
            sitio = texto(R.id.actTimeoutSitio),
            medico = texto(R.id.actTimeoutMedico),
            equipamento = texto(R.id.actTimeoutEquip),
            riscoQueda = ligado(R.id.swRisco),
            precaucaoContato = ligado(R.id.swPrec),
            alergia = ligado(R.id.swAlergia),
            fracoes = fracoesSelecionadas(),
            protocolo = protocoloEscolhido,
            observacao = edtObservacao.text?.toString()?.trim() ?: "")
    }

    /** Última posição da lista de frações (a primeira é "não informar"). */
    private fun maxFracoes(): Int =
        ((findViewById<android.widget.Spinner>(R.id.spFracoes)?.adapter?.count ?: 1) - 1)
            .coerceAtLeast(0)

    /**
     * Põe na tela os campos carregados. Só toca no campo cujo valor muda, e
     * repinta as linhas de alerta com a cor que cada uma produz na ficha.
     */
    private fun mostrarCampos(novos: PrefillEdicao.Campos, agora: PrefillEdicao.Campos) {
        fun texto(id: Int, novo: String, atual: String) {
            if (novo != atual) findViewById<android.widget.AutoCompleteTextView>(id).setText(novo, false)
        }
        fun ligar(id: Int, novo: Boolean) {
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(id).isChecked = novo
        }
        texto(R.id.actTimeoutSitio, novos.sitio, agora.sitio)
        texto(R.id.actTimeoutMedico, novos.medico, agora.medico)
        texto(R.id.actTimeoutEquip, novos.equipamento, agora.equipamento)
        ligar(R.id.swRisco, novos.riscoQueda)
        ligar(R.id.swPrec, novos.precaucaoContato)
        ligar(R.id.swAlergia, novos.alergia)
        if (novos.fracoes != agora.fracoes) {
            findViewById<android.widget.Spinner>(R.id.spFracoes)?.setSelection(novos.fracoes)
        }
        if (novos.protocolo != agora.protocolo) {
            // A faixa confere se o protocolo gravado ainda existe e marca a
            // miniatura dele.
            protocoloEscolhido = novos.protocolo
            desenharProtocolos()
        }
        if (novos.observacao != agora.observacao) edtObservacao.setText(novos.observacao)
        ligarCoresDosAlertas()
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
        // O mesmo número que executarFinalizacao grava. Em modo edição a
        // simulação é a reaberta, e contagem + 1 anunciaria uma reirradiação
        // que não existe ("NOVA SIMULAÇÃO") ao só acrescentar fotos.
        val numSim = if (sessionManager.editandoNomePasta.isNotBlank()) sessionManager.editandoNumSim
                     else patientCache.obterContagemSimulacoes(
                         sessionManager.nomePaciente, sessionManager.prontuario) + 1
        val sb = SpannableStringBuilder()
        linhaDoModelo(sb, getString(R.string.fin_patient),
            listOf(sessionManager.nomePaciente to EstiloResumo.NOME))
        if (sessionManager.prontuario.isNotBlank())
            linhaDoModelo(sb, getString(R.string.fin_record),
                listOf(sessionManager.prontuario to EstiloResumo.VALOR))
        if (sessionManager.dataNascimento.isNotBlank())
            linhaDoModelo(sb, getString(R.string.fin_birth),
                listOf(sessionManager.dataNascimento to EstiloResumo.VALOR))
        linhaEmBranco(sb)
        linhaDoModelo(sb, getString(R.string.fin_total_photos),
            listOf(numero(sessionManager.quantidade()) to EstiloResumo.NEGRITO))
        val nDocs = sessionManager.quantidadeCategoria(Category.DOCUMENTS)
        if (nDocs > 0) linhaDeContagem(sb, R.string.fin_documents, nDocs)
        linhaDeContagem(sb, R.string.fin_face, sessionManager.quantidadeCategoria(Category.FACE))
        linhaDeContagem(sb, R.string.fin_label, sessionManager.quantidadeCategoria(Category.LABEL))
        linhaDeContagem(sb, R.string.fin_positioning,
            sessionManager.quantidadeCategoria(Category.POSITIONING))
        linhaDeContagem(sb, R.string.fin_accessories,
            sessionManager.quantidadeCategoria(Category.ACCESSORIES))
        if (numSim > 1) {
            // Reirradiação em azul de marca, a linha inteira. NÃO em laranja,
            // vermelho ou amarelo: essas cores são do risco do paciente, e nova
            // simulação não é tarja clínica.
            linhaEmBranco(sb)
            sb.append('\n')
            val ini = sb.length
            sb.append(getString(R.string.fin_new_sim, numSim - 1))
            estilizar(sb, ini, sb.length, EstiloResumo.CONTAGEM)
        }
        // Snapshot: mostrarResultado() limpa a sessão, então o resumo pós-finalização
        // não pode ser reconstruído a partir dela (ficaria vazio / 0 fotos).
        resumoBaseSnapshot = sb
        txtResumo.text = sb
    }

    // ============= CARTÃO DE RESUMO =============

    /** Papel de cada pedaço do resumo; cada um vira um conjunto de spans. */
    private enum class EstiloResumo {
        /** Nome do paciente: branco, negrito, 1,2× o corpo. */
        NOME,
        /** Valor comum: branco. */
        VALOR,
        /** Valor de destaque (total de fotos): branco e negrito. */
        NEGRITO,
        /** Contagem acima de zero: azul de marca, negrito. */
        CONTAGEM,
        /** Contagem zero: terciário — o zero se lê mais apagado pelo brilho, não por outra cor. */
        CONTAGEM_ZERO,
        /** Texto fixo do modelo e rótulo de dado: secundário. */
        ROTULO
    }

    private fun cor(res: Int): Int = ContextCompat.getColor(this, res)

    /**
     * Número com os algarismos do idioma da tela, como o getString com argumento
     * o formataria. O lugar do número no texto vem do modelo, não de uma busca
     * pelos algarismos.
     */
    private fun numero(n: Int): String =
        String.format(resources.configuration.locales.get(0), "%d", n)

    private fun estilizar(sb: SpannableStringBuilder, ini: Int, fim: Int, estilo: EstiloResumo) {
        if (fim <= ini) return
        fun span(o: Any) { sb.setSpan(o, ini, fim, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        when (estilo) {
            EstiloResumo.NOME -> {
                span(ForegroundColorSpan(cor(R.color.text_primary)))
                span(StyleSpan(android.graphics.Typeface.BOLD))
                span(RelativeSizeSpan(1.2f))
            }
            EstiloResumo.VALOR -> span(ForegroundColorSpan(cor(R.color.text_primary)))
            EstiloResumo.NEGRITO -> {
                span(ForegroundColorSpan(cor(R.color.text_primary)))
                span(StyleSpan(android.graphics.Typeface.BOLD))
            }
            EstiloResumo.CONTAGEM -> {
                span(ForegroundColorSpan(cor(R.color.brand_primary)))
                span(StyleSpan(android.graphics.Typeface.BOLD))
            }
            EstiloResumo.CONTAGEM_ZERO -> span(ForegroundColorSpan(cor(R.color.text_tertiary)))
            EstiloResumo.ROTULO -> span(ForegroundColorSpan(cor(R.color.text_secondary)))
        }
    }

    /** Linha vazia entre grupos do resumo. */
    private fun linhaEmBranco(sb: SpannableStringBuilder) {
        if (sb.isNotEmpty()) sb.append('\n')
    }

    /**
     * Acrescenta ao resumo uma linha montada pelo MODELO cru da string
     * (getString sem argumento devolve, por exemplo, "Paciente: %1$s").
     *
     * O texto fixo do modelo sai em cor secundária e cada valor no estilo
     * dele. Montar pelo modelo é o que põe o valor no lugar certo em todo
     * idioma — inclusive quando ele vem antes do rótulo — sem procurá-lo no
     * texto pronto. Ver [ResumoFormato].
     *
     * @param valores um par (texto, estilo) por lugar do modelo: o primeiro vai
     *   em `%1$`, o segundo em `%2$`.
     * @param recuoPx recuo da linha inteira (lista de contagens), 0 = nenhum.
     */
    private fun linhaDoModelo(sb: SpannableStringBuilder, modelo: String,
                              valores: List<Pair<String, EstiloResumo>>, recuoPx: Int = 0) {
        if (sb.isNotEmpty()) sb.append('\n')
        val inicioLinha = sb.length
        for (seg in ResumoFormato.segmentos(modelo)) {
            val ini = sb.length
            when (seg) {
                is ResumoFormato.Segmento.Literal -> {
                    sb.append(seg.texto)
                    estilizar(sb, ini, sb.length, EstiloResumo.ROTULO)
                }
                is ResumoFormato.Segmento.Valor -> {
                    val (texto, estilo) = valores.getOrNull(seg.indice - 1) ?: continue
                    sb.append(texto)
                    estilizar(sb, ini, sb.length, estilo)
                }
            }
        }
        if (recuoPx > 0 && sb.length > inicioLinha) {
            sb.setSpan(LeadingMarginSpan.Standard(recuoPx), inicioLinha, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Linha da lista de contagens de fotos, recuada sob o total.
     *
     * O recuo é do span, não do texto: o aapt descarta o espaço do começo das
     * strings sem aspas, e o modelo chega ao app sem ele. O modelo de impressos
     * não traz o «•» das outras linhas; recuado também a largura do marcador,
     * o texto dele alinha com o das outras.
     */
    private fun linhaDeContagem(sb: SpannableStringBuilder, modeloRes: Int, n: Int) {
        val modelo = getString(modeloRes).trimStart()
        val d = resources.displayMetrics.density
        var recuo = (12 * d + 0.5f).toInt()
        if (!ResumoFormato.temMarcador(modelo)) {
            recuo += kotlin.math.ceil(txtResumo.paint.measureText("• ")).toInt()
        }
        val estilo = if (n > 0) EstiloResumo.CONTAGEM else EstiloResumo.CONTAGEM_ZERO
        linhaDoModelo(sb, modelo, listOf(numero(n) to estilo), recuo)
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
        // Com duas pastas o defeito é silencioso. As fotos e o PDF iam para `nomePastaPaciente()`
        // ("NOME - PRONTUÁRIO"), e o Time-Out ia para uma string montada aqui
        // ("NOME POSICIONAMENTO..."), que ninguém criava. Como TimeOutStore não
        // fazia mkdirs e engolia a exceção, o sítio de tratamento, o risco de
        // queda e a precaução de contato eram perdidos em silêncio na PRIMEIRA
        // finalização — e só "apareciam" se o usuário editasse depois, porque o
        // caminho de regeneração já usava resolverPastaSim.
        //
        // Em modo edição a pasta é a que foi REABERTA (editandoNomePasta), e não
        // uma recalculada do nome e do prontuário da sessão: a regravação apaga
        // e grava nela, e um prontuário divergente levaria as duas coisas para a
        // pasta de uma homônima. Ver RegrasFinalizacao.pastaDaFinalizacao.
        val nomePastaPac = RegrasFinalizacao.pastaDaFinalizacao(
            sessionManager.editandoNomePasta, nomeNorm, sessionManager.prontuario)
        // Lido aqui, na thread principal, junto com o resto da sessão.
        val reconstruidos = if (editando) sessionManager.reconstruidos() else emptySet()

        // A categoria anda junto com o arquivo até o nome ser montado. Uma lista
        // de tipos paralela à de fotos se desalinha em silêncio no primeiro item
        // que uma das duas acrescentar ou pular.
        val fotosPdfCat = sessionManager.fotosOrdenadasParaPdf()
        val fotosPdf = fotosPdfCat.map { it.arquivo }
        // Impressos escaneados: NÃO entram no PDF, mas SÃO gravados na pasta do paciente.
        val docsCat = sessionManager.fotos
            .filter { it.categoria == Category.DOCUMENTS }
            .sortedBy { it.timestampMs }
        val fotosGravar = fotosPdfCat + docsCat
        // Um instante só para a ficha: dele saem o nome guardado na pasta (que é
        // o mesmo da cópia em cache) e o nome das cópias de entrega.
        val instantePdf = System.currentTimeMillis()
        val nomeEntrega = NomeArquivo.montarEntrega(
            nome, NomeArquivo.Tipo.FICHA, numSim, instantePdf, 1, "pdf")
        // Acessórios agora fazem parte de fotosPdf (todas as fotos). Não salvamos
        // mais um acessório único em separado.
        val fotoAcessorios: File? = null

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Gera PDFs
                txtProgresso.text = getString(R.string.hc_generating_pdf)
                val pdfFolha = withContext(Dispatchers.IO) {
                    gerarFolhaPosicionamento(nome, fotosPdf, numSim, instantePdf, timeOutSelecionado)
                }
                // Acessórios agora entram na própria Folha de Posicionamento
                // (item: todas as fotos no PDF). Não geramos mais ficha separada.
                val pdfAcessorios: File? = null

                // 2. Salva localmente (fotos + PDF na pasta escolhida)
                txtProgresso.text = getString(R.string.saving_locally)
                val errosLocais = withContext(Dispatchers.IO) {
                    salvarTodasLocalmente(nome, fotosGravar, pdfFolha, nomePastaPac, numSim,
                        editando, reconstruidos)
                }

                // Persiste as escolhas do Time-Out (regenerações futuras) e o
                // equipamento habitual no cadastro do paciente.
                val protocoloUi = protocoloEscolhido
                val toTela = timeOutSelecionado?.let { t ->
                    com.radioterapia.ai.util.TimeOutStore.Registro(
                        true, t.medicoResponsavel, t.sitioTratamento,
                        t.riscoQueda, t.precaucaoContato,
                        t.equipamento, t.alergia, t.fracoesMax, protocoloUi)
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
                pastaSimFinalizada = pastaSim
                val okTimeOut = withContext(Dispatchers.IO) {
                    // Só a edição com a página desligada consulta o gravado: é
                    // o único caso em que a tela não diz o que vale. Ver
                    // RegrasFinalizacao.acaoTimeOut.
                    val gravado = if (editando && toTela == null)
                        com.radioterapia.ai.util.TimeOutStore.ler(pastaSim, numSim) else null
                    when (val acao = RegrasFinalizacao.acaoTimeOut(toTela, gravado, editando, protocoloUi)) {
                        is RegrasFinalizacao.AcaoTimeOut.Gravar ->
                            com.radioterapia.ai.util.TimeOutStore.gravar(pastaSim, numSim, acao.registro)
                        RegrasFinalizacao.AcaoTimeOut.Apagar ->
                            com.radioterapia.ai.util.TimeOutStore.gravar(pastaSim, numSim, null)
                        RegrasFinalizacao.AcaoTimeOut.Manter -> true
                    }
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
                // (ex.: um app de sincronização de pastas). O app apenas salva localmente.
                val resultadosEnvio = emptyList<ResultadoDestino>()

                // 3. Mostra resultado
                txtProgresso.visibility = View.GONE
                mostrarResultado(resultadosEnvio, errosLocais + errosClinicos,
                    pdfFolha, nome, numSim, nomePastaPac, nomeEntrega)
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
        numSim: Int,
        nomePastaPac: String,
        nomeEntrega: String
    ) {
        // Registrar histórico e limpar sessão IMEDIATAMENTE (senão a Home
        // continua mostrando rascunho ativo) quando a finalização processa. As ações nos
        // 4 botões abaixo são OPCIONAIS - simulação já foi salva.
        pdfGeradoAtual = pdfFolha
        nomeEntregaPdf = nomeEntrega
        // GATILHO DE FINALIZACAO. E o instante em que a ficha existe: fotos
        // gravadas, PDF montado, pasta do paciente completa. Esperar o periodo
        // configurado deixaria a simulacao inteira so no tablet justamente no
        // intervalo em que ela ainda nao foi conferida por ninguem. A linha de
        // sincronia acompanha ESTE envio (ver iniciarAcompanhamentoSync).
        iniciarAcompanhamentoSync()
        nomePacienteFinalizado = nomePaciente
        numSimFinalizado = numSim
        mostrarFaseResultado()
        // Captura dados antes de limpar a sessão (usados pelo botão "Adicionar mais fotos").
        //
        // A PASTA É A REAL, a mesma em que salvarTodasLocalmente gravou. Ela vira
        // `editandoNomePasta` e é usada como caminho: a captura procura ali as
        // arquivadas do paciente, e a reconstrução casa a simulação por ela. Um
        // nome de pasta que não existe em disco faria as duas coisas falharem
        // sem erro nenhum.
        prontuarioFinalizado = sessionManager.prontuario
        nascimentoFinalizado = sessionManager.dataNascimento
        idsExtrasFinalizados = sessionManager.obterIdsExtras()
        nomePacienteAtual = nomePaciente
        nomePastaFinalizada = nomePastaPac
        registrarHistoricoCompleto(nomePaciente)
        sessionManager.limparSessao()

        // Resumo: mostrar APENAS o que deu certo
        tituloAtualRes = R.string.fin_done_title
        title = getString(R.string.fin_done_title)
        findViewById<TextView>(R.id.txtFinTitulo).setText(R.string.fin_done_title)
        atualizarResumoFinal()
        /*
            A CAIXA DE RESULTADO DIZ SÓ O QUE ACONTECEU NO TABLET: "salva".

            Neste instante nada foi enviado — o envio é um trabalho em segundo
            plano que começa segundos depois, e o interruptor mestre da
            sincronia nasce desligado. Dizer "enviada" aqui afirmaria um envio
            que pode nunca acontecer. Quem fala do envio é a linha de sincronia,
            e ela só diz "realizada" depois de conferir a pasta contra os
            destinos.
         */
        txtResultado.text = if (errosLocais.isEmpty()) getString(R.string.fin_ok_salva)
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
        // O botão SEMPRE tem ação e NUNCA apaga. A ficha existe neste ponto
        // (gerada antes de qualquer gravação na pasta), e o seletor de modo
        // (rede / sistema / pen-drive OTG / pasta), herdado da BaseActivity, é
        // o mesmo caminho do carrossel: sem impressora de rede, só a opção
        // "impressora da rede" fica esmaecida dentro dele. Erro de gravação na
        // pasta, de arquivadas, do Time-Out ou da observação aparece na caixa
        // de resultado e não tira a ficha da mão do técnico. As cópias para
        // pen-drive e pasta saem com o nome completo.
        btnImprimir.isEnabled = true
        btnImprimir.alpha = 1f
        btnImprimir.setOnClickListener {
            escolherModoImpressao(pdfFolha, nomeEntregaPdf.ifBlank { null })
        }
        when (RegrasFinalizacao.linhaImpressora(config.temImpressora())) {
            // "Ping" na impressora: diz se ela responde na rede atual (o
            // tablet pode ter trocado de Wi-Fi no meio do dia).
            RegrasFinalizacao.LinhaImpressora.TESTAR_REDE -> verificarStatusImpressora()
            RegrasFinalizacao.LinhaImpressora.SEM_IMPRESSORA_DE_REDE -> {
                txtImprInfo.text = "ℹ ${getString(R.string.printer_offline)}"
                txtImprInfo.visibility = View.VISIBLE
            }
        }

        // Cadastro (nome/nascimento/sexo/prontuário) NÃO é editável no fluxo de
        // simulação: renomear a pasta/sessão no meio quebrava o PDF em andamento.
        // Editar cadastro fica só no carrossel (⋮) e nas Configurações.

        // ===== Botão 4: Adicionar mais fotos à simulação recém-criada =====
        // Volta ao MODO DE FOTOS completo (MainActivity) reconstruindo a sessão a
        // partir da pasta do paciente, permitindo refazer rosto/etiqueta e adicionar
        // posicionamentos/acessórios. Ao finalizar de novo, regenera o PDF da MESMA
        // simulação (sem incrementar a contagem).
        btnAddFotos.setOnClickListener {
            travarAcoesDaEdicao(true)
            // Capturados aqui, na thread principal: o bloco de IO não lê campo
            // desta tela.
            val nomeFin = nomePacienteAtual
            val pastaFin = nomePastaFinalizada
            val numFin = numSimFinalizado
            val prontFin = prontuarioFinalizado
            val nascFin = nascimentoFinalizado
            val idsFin = idsExtrasFinalizados
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // A simulação vem da PASTA REAL, por pasta e número, e não há
                    // alternativa. O que for carregado aqui é o que a finalização
                    // seguinte grava nesta pasta e o que ela tira da pasta antes
                    // de gravar; outra simulação, ou a de uma homônima, passaria a
                    // ser este paciente. Ver lerSimulacaoDaPasta.
                    val sim = withContext(Dispatchers.IO) {
                        lerSimulacaoDaPasta(nomeFin, pastaFin, numFin)
                    }
                    if (sim == null) {
                        travarAcoesDaEdicao(false)
                        android.widget.Toast.makeText(this@FinalizarActivity,
                            getString(R.string.err_reopen, getString(R.string.sim_not_found)),
                            android.widget.Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val entrou = withContext(Dispatchers.IO) {
                        sessionManager.limparSessao()
                        // GUARDA: a captura passa a oferecer para restaurar as
                        // arquivadas da PASTA DO PACIENTE (editandoNomePasta,
                        // abaixo). Arquivada que também está na pasta de outro
                        // paciente não pode chegar a essa lista; a quarentena
                        // tira essas antes. Já feita nesta base, não custa nada.
                        try {
                            com.radioterapia.ai.util.QuarentenaArquivadas
                                .executarSeNecessario(this@FinalizarActivity)
                        } catch (_: Exception) {}
                        sessionManager.nomePaciente = nomeFin
                        // O prontuário é o da finalização, mesmo vazio. Ele
                        // decide a pasta e o cadastro que a regravação toca.
                        sessionManager.prontuario = prontFin
                        sessionManager.dataNascimento = nascFin.ifBlank {
                            cadastroDe(nomeFin, prontFin)?.nascimento.orEmpty()
                        }
                        if (idsFin.isNotEmpty()) sessionManager.salvarIdsExtras(idsFin)
                        // GUARDA: a edição é marcada ANTES das cópias. É por ela
                        // que a sessão reconhece o arquivo que veio da pasta
                        // reaberta e o registra em reconstruidos() — a lista do
                        // que a regravação pode tirar da pasta.
                        sessionManager.editandoNomePasta = pastaFin
                        sessionManager.editandoNumSim = numFin
                        sim.rosto?.let { sessionManager.adicionarDaPasta(it.arquivoLocal, Category.FACE) }
                        sim.etiqueta?.let { sessionManager.adicionarDaPasta(it.arquivoLocal, Category.LABEL) }
                        sim.posicionamentos.forEach { sessionManager.adicionarDaPasta(it.arquivoLocal, Category.POSITIONING) }
                        sim.acessoriosLista.forEach { sessionManager.adicionarDaPasta(it.arquivoLocal, Category.ACCESSORIES) }
                        sim.documentos.forEach { sessionManager.adicionarDaPasta(it.arquivoLocal, Category.DOCUMENTS) }
                        // Nenhuma foto entrou: sem edição. Uma sessão vazia em modo
                        // edição faria a câmera tentar reconstruir por conta própria.
                        if (sessionManager.quantidade() == 0) {
                            sessionManager.limparSessao()
                            false
                        } else {
                            sessionManager.marcarInicio()
                            true
                        }
                    }
                    if (!entrou) {
                        travarAcoesDaEdicao(false)
                        android.widget.Toast.makeText(this@FinalizarActivity,
                            getString(R.string.err_reopen, getString(R.string.sim_not_found)),
                            android.widget.Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val intent = Intent(this@FinalizarActivity, com.radioterapia.ai.MainActivity::class.java)
                    intent.putExtra(com.radioterapia.ai.MainActivity.EXTRA_CONTINUAR_SIMULACAO, true)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                } catch (e: Exception) {
                    travarAcoesDaEdicao(false)
                    android.widget.Toast.makeText(this@FinalizarActivity, getString(R.string.err_reopen, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        // ===== Botão final: encerrar e voltar pra Home =====
        btnEncerrar.setOnClickListener { voltarHome() }

    }

    /**
     * A simulação recém-finalizada, lida da PASTA REAL dela pelo número.
     *
     * Não passa pela busca do Tratamento, que procura por NOME: com homônimas
     * ("MARIA SILVA - 123" e "MARIA SILVA - 456"), ou com uma pasta antiga só
     * com o nome, ela devolve a pasta que o disco listar primeiro, e consulta
     * o servidor antes do tablet. Quem usa o resultado daqui grava na pasta
     * deste paciente — a ficha de "Salvar alterações" e a reconstrução que a
     * finalização seguinte regrava —, então só vale a pasta exata, sem
     * alternativa por número ou pela mais recente.
     *
     * Na pasta escolhida por SAF, as fotos da simulação são copiadas para o
     * cache, porque o resto do fluxo trabalha com File.
     *
     * Roda em IO.
     *
     * @return `null` quando a pasta ou fotos desta simulação não estão lá.
     */
    private fun lerSimulacaoDaPasta(nome: String, nomePasta: String,
                                    numSim: Int): TreatmentPhotoFetcher.Simulacao? {
        if (nomePasta.isBlank()) return null
        val uriSaf = config.pastaFotosUri
        if (uriSaf.isBlank()) {
            val pasta = File(StorageLocal.photos(this), nomePasta)
            if (!pasta.isDirectory) return null
            val arquivos = pasta.listFiles()?.filter { it.isFile }
                ?.map { it to it.lastModified() }.orEmpty()
            return RegrasFinalizacao.simulacaoDaPasta(nome, nomePasta, numSim, arquivos)
        }
        return try {
            val base = androidx.documentfile.provider.DocumentFile
                .fromTreeUri(this, Uri.parse(uriSaf)) ?: return null
            val pasta = base.findFile("PhotoID_RT")?.findFile("PHOTOS")?.findFile(nomePasta)
                ?.takeIf { it.isDirectory } ?: return null
            // Pasta fixa e esvaziada a cada leitura: os dois botões que leem
            // travam juntos enquanto uma leitura não termina (travarAcoesDaEdicao).
            val destino = File(cacheDir, "simulacao_da_pasta").apply {
                deleteRecursively(); mkdirs()
            }
            val arquivos = mutableListOf<Pair<File, Long>>()
            pasta.listFiles().forEach { doc ->
                val n = doc.name ?: return@forEach
                if (!doc.isFile || n.contains('/') || n.contains('\\')) return@forEach
                if (!RegrasFinalizacao.ehFotoDaSimulacao(n, numSim)) return@forEach
                val copia = File(destino, n)
                try {
                    contentResolver.openInputStream(doc.uri)?.use { entrada ->
                        copia.outputStream().use { entrada.copyTo(it) }
                    }
                } catch (_: Exception) {
                    copia.delete()
                }
                if (copia.isFile && copia.length() > 0L) arquivos.add(copia to doc.lastModified())
            }
            RegrasFinalizacao.simulacaoDaPasta(nome, nomePasta, numSim, arquivos)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cadastro deste paciente, e só dele.
     *
     * Com prontuário, a chave do cadastro já é dele. Sem prontuário, a busca
     * escolhe entre homônimas o registro mais completo, que pode ser o de
     * outra paciente; esse só serve se também não tiver prontuário. Ver
     * [RegrasFinalizacao.cadastroServe].
     */
    private fun cadastroDe(nome: String, prontuario: String): PatientCache.DadosPaciente? =
        patientCache.obterDadosPaciente(nome, prontuario)
            ?.takeIf { RegrasFinalizacao.cadastroServe(prontuario, it.prontuario) }

    /**
     * "Salvar alterações" e "Adicionar mais fotos" travam e destravam JUNTOS.
     * Os dois leem a simulação da pasta, e na pasta SAF a leitura esvazia a
     * cópia em cache da leitura anterior: um segundo toque no meio do primeiro
     * apagaria as fotos de que a ficha em montagem depende.
     */
    private fun travarAcoesDaEdicao(travar: Boolean) {
        findViewById<View>(R.id.btnAtualizarObs)?.isEnabled = !travar
        btnAddFotos.isEnabled = !travar
    }

    /**
     * Abre o PDF no app padrão do sistema via FileProvider.
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
     * "Ping" na impressora (conexão TCP nas portas 9100/631, mais fiel ao ato de
     * imprimir do que ICMP). Mostra um aviso pequeno de online/offline; o botão
     * Imprimir continua habilitado o tempo todo. Tocar no aviso re-testa.
     */
    private fun verificarStatusImpressora() {
        // O botão NÃO é mais desabilitado: o seletor de modo oferece pen-drive
        // (OTG) e pasta de impressão, que funcionam sem impressora de rede.
        // O aviso de texto continua informando o estado da impressora IP.
        // As cores são as da linha de sincronia logo acima (secundário, verde
        // de primeiro plano, vermelho de primeiro plano): as duas linhas formam
        // um bloco só e dizem "certo" e "errado" com os mesmos tons.
        txtImprInfo.setTextColor(cor(R.color.text_secondary))
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
                txtImprInfo.setTextColor(cor(R.color.confirm_green))
            } else {
                txtImprInfo.text = getString(R.string.printer_status_offline)
                txtImprInfo.setTextColor(cor(R.color.error_red_fg))
            }
            txtImprInfo.visibility = View.VISIBLE
            // Re-testa ao tocar (ex.: depois de voltar para o Wi-Fi certo)
            txtImprInfo.setOnClickListener { verificarStatusImpressora() }
        }
    }

    // ============= LINHA DE SINCRONIA =============
    //
    // A tela OBSERVA o trabalho que a própria finalização enfileira; não roda
    // sincronização nenhuma. Rodar o motor daqui seria uma segunda varredura
    // concorrente com a do trabalho, e esta tela não ganha código de rede.

    /** Estado do trabalho desta finalização, observado pelo id. */
    private var syncObservado: androidx.lifecycle.LiveData<WorkInfo>? = null
    /** Relógio monotônico no instante em que o trabalho foi enfileirado. */
    private var syncInicioMs = 0L
    /** Último estado não nulo recebido do trabalho. */
    private var syncUltimoInfo: WorkInfo? = null
    /** Conferência local da pasta, feita uma vez depois do sucesso. */
    private var syncConferencia: MotorSync.Conferencia? = null
    private var syncConferindo = false
    /**
     * Cada acompanhamento novo invalida os retornos do anterior: observador,
     * conferência e reavaliação de um envio antigo não pintam a linha do novo.
     */
    private var syncGeracao = 0
    /** O técnico foi às Configurações pela linha de falha: ao voltar, tenta de novo. */
    private var syncReconferirAoVoltar = false
    private val syncReavaliar = Runnable { pintarSync(linhaSyncAtual()) }

    /**
     * Dispara o envio da simulação finalizada e passa a mostrar o andamento
     * dele na linha de sincronia.
     *
     * O GATILHO SAI SEMPRE, apareça a linha ou não: é o envio que a
     * finalização já fazia. O que depende da configuração é só o
     * acompanhamento — com o interruptor mestre desligado, sem destino
     * utilizável ou com «enviar ao finalizar» desligado, a linha fica oculta.
     *
     * Observa PELO ID: a fila nomeada pode estar segurando um trabalho
     * anterior (de foto salva), e só o id diz se ESTE envio rodou.
     */
    private fun iniciarAcompanhamentoSync() {
        pararAcompanhamentoSync()
        pintarSync(EstadoSyncFinalizacao.Linha.OCULTA)
        val geracao = syncGeracao
        val id = try { SyncWorker.aoFinalizar(this) } catch (_: Exception) { null }
        val inicio = SystemClock.elapsedRealtime()
        if (id == null) return
        val ctx = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            // Lê o arquivo de perfis: fora da thread principal.
            val acompanhar = withContext(Dispatchers.IO) {
                try { SyncWorker.acompanhavelAoFinalizar(ctx) } catch (_: Exception) { false }
            }
            if (geracao != syncGeracao || isFinishing || isDestroyed || !acompanhar) return@launch
            syncInicioMs = inicio
            val ld = SyncWorker.observar(this@FinalizarActivity, id)
            syncObservado = ld
            // WorkInfo? e não WorkInfo: a API do WorkManager é Java, e o valor
            // chega nulo quando o trabalho deixa de existir.
            ld.observe(this@FinalizarActivity, androidx.lifecycle.Observer<WorkInfo?> { info ->
                aoMudarTrabalhoSync(geracao, info)
            })
            // Trabalho parado na fila (sem rede permitida, bateria baixa) não
            // muda de estado sozinho: esta reavaliação é o que tira a linha de
            // «em curso» quando o limite de espera passa.
            txtSyncInfo.postDelayed(syncReavaliar, EstadoSyncFinalizacao.LIMITE_ESPERA_MS + 100L)
            pintarSync(linhaSyncAtual())
        }
    }

    /** Desliga o acompanhamento em curso: observador, reavaliação e conferência. */
    private fun pararAcompanhamentoSync() {
        syncGeracao++
        txtSyncInfo.removeCallbacks(syncReavaliar)
        syncObservado?.removeObservers(this)
        syncObservado = null
        syncUltimoInfo = null
        syncConferencia = null
        syncConferindo = false
    }

    private fun aoMudarTrabalhoSync(geracao: Int, info: WorkInfo?) {
        if (geracao != syncGeracao) return
        if (info != null) syncUltimoInfo = info
        // Sucesso do trabalho não basta: a linha só diz «realizada» depois de
        // conferir, na pasta da simulação, que o que foi gravado está nos
        // destinos. A conferência lê disco: em IO, uma vez por envio.
        if (info?.state == WorkInfo.State.SUCCEEDED && syncConferencia == null && !syncConferindo) {
            syncConferindo = true
            val pasta = pastaSimFinalizada
            val ctx = applicationContext
            CoroutineScope(Dispatchers.Main).launch {
                val conf = withContext(Dispatchers.IO) {
                    try {
                        if (pasta == null) null else MotorSync(ctx).conferirPasta(pasta)
                    } catch (_: Exception) { null }
                }
                if (geracao != syncGeracao) return@launch
                syncConferindo = false
                // Sem pasta, ou sem conseguir ler: a rodada deu certo, mas não
                // há como afirmar que ESTE prontuário subiu (total zero).
                syncConferencia = conf ?: MotorSync.Conferencia(0, 0)
                pintarSync(linhaSyncAtual())
            }
        }
        pintarSync(linhaSyncAtual())
    }

    /** O que a linha mostra agora, pela tabela de [EstadoSyncFinalizacao]. */
    private fun linhaSyncAtual(): EstadoSyncFinalizacao.Linha {
        if (syncObservado == null) return EstadoSyncFinalizacao.Linha.OCULTA
        val info = syncUltimoInfo
        // Antes do primeiro estado o trabalho está sendo posto na fila.
        val fase = if (info == null) EstadoSyncFinalizacao.Fase.AGUARDANDO else faseSync(info.state)
        val conf = syncConferencia
        return EstadoSyncFinalizacao.calcular(fase, info?.runAttemptCount ?: 0,
            SystemClock.elapsedRealtime() - syncInicioMs, conf?.total, conf?.pendentes)
    }

    private fun faseSync(estado: WorkInfo.State): EstadoSyncFinalizacao.Fase = when (estado) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> EstadoSyncFinalizacao.Fase.AGUARDANDO
        WorkInfo.State.RUNNING -> EstadoSyncFinalizacao.Fase.RODANDO
        WorkInfo.State.SUCCEEDED -> EstadoSyncFinalizacao.Fase.SUCESSO
        WorkInfo.State.FAILED -> EstadoSyncFinalizacao.Fase.FALHA
        WorkInfo.State.CANCELLED -> EstadoSyncFinalizacao.Fase.CANCELADO
    }

    /**
     * Pinta a linha de sincronia. Sem emoji: é mensagem de estado.
     *
     * «Não realizada» é a única tocável, e leva às Configurações, onde estão
     * os destinos; ao voltar, a tela tenta o envio de novo (ver [onResume]).
     */
    private fun pintarSync(linha: EstadoSyncFinalizacao.Linha) {
        val (texto, corRes) = when (linha) {
            EstadoSyncFinalizacao.Linha.OCULTA -> {
                txtSyncInfo.setOnClickListener(null)
                txtSyncInfo.isClickable = false
                txtSyncInfo.visibility = View.GONE
                return
            }
            EstadoSyncFinalizacao.Linha.EM_CURSO -> R.string.fin_sync_em_curso to R.color.text_secondary
            EstadoSyncFinalizacao.Linha.OK -> R.string.fin_sync_ok to R.color.confirm_green
            EstadoSyncFinalizacao.Linha.OK_SEM_CONFERENCIA -> R.string.fin_sync_ok_geral to R.color.confirm_green
            EstadoSyncFinalizacao.Linha.FALHOU -> R.string.fin_sync_falhou to R.color.error_red_fg
        }
        txtSyncInfo.setText(texto)
        txtSyncInfo.setTextColor(cor(corRes))
        if (linha == EstadoSyncFinalizacao.Linha.FALHOU) {
            txtSyncInfo.setOnClickListener { abrirConfiguracoesDaSync() }
        } else {
            txtSyncInfo.setOnClickListener(null)
            txtSyncInfo.isClickable = false
        }
        txtSyncInfo.visibility = View.VISIBLE
    }

    private fun abrirConfiguracoesDaSync() {
        syncReconferirAoVoltar = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (syncReconferirAoVoltar) {
            syncReconferirAoVoltar = false
            if (simulacaoFinalizada) iniciarAcompanhamentoSync()
        }
    }

    override fun onDestroy() {
        if (::txtSyncInfo.isInitialized) txtSyncInfo.removeCallbacks(syncReavaliar)
        super.onDestroy()
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
     *
     * Na rodada de fotos adicionais da mesma simulação a escolha já existe: o
     * protocolo gravado volta marcado ([carregarGravadoDaEdicao]).
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
        // A escolha já feita nesta tela SOBREVIVE ao redesenho. "Editar dados"
        // redesenha a faixa, e zerar aqui faria "Salvar alterações" regravar a
        // ficha sem as páginas do protocolo sem ninguém ter desmarcado nada. Na
        // primeira passagem ela ainda está vazia, e nada vem marcado.
        protocoloEscolhido = protocoloEscolhido.takeIf { id -> todos.any { it.id == id } }.orEmpty()
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
                // "Escolhido" AFIRMA a selecao. `confirm` e "Sim, esta correto",
                // a frase de um dialogo de confirmacao — dizia a coisa errada
                // para o estado de um item marcado.
                textOn = getString(R.string.prot_escolhido)
                textOff = getString(R.string.prot_marcar)
                isChecked = false
                textSize = 12f
                isAllCaps = false
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@FinalizarActivity, R.color.text_on_dark))
                minHeight = 0
                minimumHeight = 0
                setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
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
            // A COLUNA INTEIRA e o alvo do realce, nao so a borda da imagem:
            // miniatura, nome e marcador formam um cartao so, e e o cartao que
            // o olho procura de longe.
            col.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            col.isClickable = true
            col.setOnClickListener { marca.performClick() }
            col.addView(img); col.addView(nome); col.addView(marca)
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
            val img = col.getChildAt(0) as? android.widget.ImageView ?: continue
            val marca = col.getChildAt(2) as? android.widget.ToggleButton ?: continue
            val p = todos.getOrNull(i) ?: continue
            val sel = p.id == protocoloEscolhido
            marca.isChecked = sel

            /*
                O CARTAO INTEIRO MUDA, nao quatro pixels em volta da imagem.

                bg_bloco_selecionado e bg_bloco_normal sao os mesmos drawables
                que as outras telas usam para dizer "este esta escolhido" — a
                escolha passa a parecer a mesma coisa em todo o app.

                O marcador tambem muda de cor: cinza do Material nos dois
                estados fazia o botao do escolhido ficar identico ao dos outros,
                e o estado do item ficava so no texto.
             */
            col.setBackgroundResource(
                if (sel) R.drawable.bg_bloco_selecionado else R.drawable.bg_bloco_normal)
            img.setBackgroundColor(0x00000000)
            marca.backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this,
                    if (sel) R.color.brand_primary_dark else R.color.action_neutral))
        }
    }

    private fun configurarFracoes() {
        val sp = findViewById<android.widget.Spinner>(R.id.spFracoes) ?: return
        if (sp.adapter != null) return
        val itens = mutableListOf(getString(R.string.fin_fracoes_nenhuma))
        for (n in 1..40) itens.add(getString(R.string.fin_fracoes_n, n))
        // Fechado, o Spinner mostra o valor como os outros três campos de
        // seleção; aberto, a lista usa a mesma linha de opção que eles.
        sp.adapter = android.widget.ArrayAdapter(this, R.layout.item_seletor_spinner, itens).apply {
            setDropDownViewResource(R.layout.item_seletor_opcao)
        }
        sp.setSelection(0)
    }

    private var timeOutSelecionado: com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut? = null

    private var obsEmEdicao = false

    /**
     * Resumo pós-finalização: acrescenta ao snapshot do topo médico,
     * equipamento, sítio, número máximo de visitas e observação, e mostra os
     * alertas ativos como pílulas.
     *
     * Os rótulos são as strings dos próprios campos, no idioma da tela, pelo
     * modelo "%1$s: %2$s" (rótulo em secundário, valor em branco).
     *
     * ALERTA É PÍLULA, NÃO LINHA DE TEXTO: só os ativos aparecem, na ordem do
     * resumo do Tratamento (contato, alergia, queda), na forma e nas cores da
     * tarja da ficha de Time-Out. Sem alerta ligado não há pílula — como no
     * papel, que também só imprime a tarja ativa.
     *
     * Roda na thread principal (lê os campos da tela). É chamado de novo
     * depois de "Salvar alterações", por isso as pílulas são refeitas do zero.
     */
    private fun atualizarResumoFinal() {
        val sb = SpannableStringBuilder(resumoBaseSnapshot)
        val modelo = getString(R.string.wiz_resumo_linha)
        fun dado(rotuloRes: Int, valor: String) {
            if (valor.isBlank()) return
            linhaDoModelo(sb, modelo,
                listOf(getString(rotuloRes) to EstiloResumo.ROTULO, valor to EstiloResumo.VALOR))
        }
        dado(R.string.team_medico_label,
            findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutMedico).text.toString().trim())
        dado(R.string.timeout_equip_label,
            findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutEquip).text.toString().trim())
        dado(R.string.sitio_topografia_label,
            findViewById<android.widget.AutoCompleteTextView>(R.id.actTimeoutSitio).text.toString().trim())
        // Logo depois do sítio, na mesma ordem dos campos da tela.
        val fracoes = fracoesSelecionadas()
        if (fracoes > 0) dado(R.string.fin_resumo_visitas, getString(R.string.fin_fracoes_n, fracoes))
        dado(R.string.fin_observation_label,
            findViewById<android.widget.EditText>(R.id.edtObservacao).text.toString().trim())
        txtResumo.text = sb

        boxFinAlertas.removeAllViews()
        fun ligado(id: Int) =
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(id).isChecked
        if (ligado(R.id.swPrec)) boxFinAlertas.addView(PilulaAlerta.criar(this,
            getString(R.string.resumo_alerta_contato), R.drawable.pill_alert_orange, 0xFFFFFFFF.toInt()))
        if (ligado(R.id.swAlergia)) boxFinAlertas.addView(PilulaAlerta.criar(this,
            getString(R.string.resumo_alerta_alergia), R.drawable.pill_alert_red, 0xFFFFFFFF.toInt()))
        if (ligado(R.id.swRisco)) boxFinAlertas.addView(PilulaAlerta.criar(this,
            getString(R.string.resumo_alerta_queda), R.drawable.pill_alert_yellow, 0xFF1A1A1A.toInt()))
        // A última pílula fica sem a margem de baixo: o respiro até a borda do
        // cartão é o padding dele, o mesmo de cima.
        if (boxFinAlertas.childCount > 0) {
            val ultima = boxFinAlertas.getChildAt(boxFinAlertas.childCount - 1)
            (ultima.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = 0
                ultima.layoutParams = lp
            }
        }
        boxFinAlertas.visibility = if (boxFinAlertas.childCount > 0) View.VISIBLE else View.GONE
    }

    /**
     * Compartilha o PDF gerado como anexo (WhatsApp, Gmail, Drive, etc.), numa
     * cópia com o nome completo do paciente: o anexo sai do tablet sem a pasta
     * que identifica a ficha guardada.
     */
    private fun compartilharPdf(pdf: File) {
        compartilharComoEntrega(pdf, nomeEntregaPdf.ifBlank { null })
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
            this, R.layout.item_seletor_opcao, itens) {
            override fun getFilter(): android.widget.Filter =
                object : android.widget.Filter() {
                    override fun performFiltering(cs: CharSequence?) =
                        FilterResults().apply { values = itens; count = itens.size }
                    override fun publishResults(cs: CharSequence?, r: FilterResults?) {
                        notifyDataSetChanged()
                    }
                }

            // "Digitar outro…" é uma AÇÃO, não um valor da lista: sai em azul
            // de marca. A cor é reposta nas outras linhas porque a View é
            // reciclada entre posições.
            override fun getView(position: Int, convertView: View?,
                                 parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val acao = permiteDigitar && position == itens.size - 1
                (v as? TextView)?.setTextColor(
                    cor(if (acao) R.color.brand_primary else R.color.text_primary))
                return v
            }
        }
        // Sem opções configuradas → campo 100% livre para digitar
        // (não faz sentido "dropdown-first" com lista vazia).
        //
        // A seta vai pelo par RELATIVO (início/fim): no absoluto ela ficaria à
        // direita também em árabe, do lado em que o texto começa.
        if (opcoes.isEmpty()) {
            act.setAdapter(null)
            act.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            return
        }
        act.setAdapter(adapter)
        act.threshold = 1
        // Lista de 1 item sem digitação livre (equipamento): pré-seleciona.
        if (!permiteDigitar && opcoes.size == 1 && act.text.isNullOrBlank())
            act.setText(opcoes[0], false)
        act.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_seletor_chevron, 0)
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
        // As linhas de estado moram acima do cartão, fora de layoutResultado,
        // e por isso seguem a fase aqui.
        layoutStatusFin.visibility = View.VISIBLE
    }

    /** FASE 1-b — "Editar dados": devolve os campos editáveis com os valores
     *  atuais e troca as ações por Salvar alterações / Editar cadastro / Fotos. */
    private fun entrarModoEdicao() {
        obsEmEdicao = true
        tituloAtualRes = R.string.fin_edit_title
        title = getString(R.string.fin_edit_title)
        findViewById<TextView>(R.id.txtFinTitulo).setText(R.string.fin_edit_title)
        layoutResultado.visibility = View.GONE
        layoutStatusFin.visibility = View.GONE
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
        // O mesmo campo escuro da primeira passagem (o layout já o declara);
        // reposto aqui para a edição nunca voltar com outro visual.
        edt.setBackgroundResource(R.drawable.bg_seletor)
        edt.setTextColor(cor(R.color.text_primary))
        edt.setHintTextColor(cor(R.color.text_tertiary))
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
        val fracoesUi = fracoesSelecionadas()
        val protocoloUi = protocoloEscolhido
        // A simulação e a identidade são as DESTA finalização, capturadas antes
        // de a sessão ser limpa: pasta real, número, prontuário, nascimento e
        // identificações extras saem como saíram na primeira ficha.
        val pastaFin = nomePastaFinalizada
        val numFin = numSimFinalizado
        val prontFin = prontuarioFinalizado
        val nascFin = com.radioterapia.ai.util.DateUtils.formatarNascimento(
            nascimentoFinalizado,
            com.radioterapia.ai.i18n.LocaleManager.obterIdiomaAtual(this))
        val idsFin = idsExtrasFinalizados.map { PdfBuilder.IdExtra(it.first, it.second) }
        val incluiTimeOut = config.pdfIncluiTimeOut
        travarAcoesDaEdicao(true)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resultado = withContext(Dispatchers.IO) {
                    // Pasta + número, sem alternativa: a ficha regenerada aqui
                    // SUBSTITUI a guardada na pasta deste paciente. Ver
                    // lerSimulacaoDaPasta.
                    val sim = lerSimulacaoDaPasta(nome, pastaFin, numFin)
                        ?: return@withContext Regravacao.SEM_SIMULACAO
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
                    if (fotos.isEmpty()) return@withContext Regravacao.SEM_SIMULACAO
                    val cfg = com.radioterapia.ai.AppConfig(this@FinalizarActivity)
                    val cadastro = cadastroDe(nome, prontFin)
                    val dados = com.radioterapia.ai.pdf.PdfBuilder.DadosCabecalho(
                        nomePaciente = nome,
                        nascimento = nascFin,
                        prontuario = prontFin,
                        idsExtras = idsFin,
                        dataSimulacao = java.util.Date(sim.timestampPrincipal),
                        numeroSimulacao = numFin,
                        nomeClinica = cfg.nomeClinica,
                        sexo = cadastro?.sexo ?: "",
                        medicoAssistente = cadastro?.medicoAssistente ?: ""
                    )
                    // A pasta é a MESMA em que a finalização gravou Time-Out e
                    // observação (mesmo nome sugerido, mesmo prontuário).
                    val pastaReal = com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
                        this@FinalizarActivity, nome, numFin, pastaFin, prontFin)
                    // A OBSERVAÇÃO É GRAVADA SEMPRE, com ou sem Time-Out: o campo
                    // é editável nos dois casos, e as regenerações seguintes (fotos
                    // do Tratamento) leem daqui. Gravada só com Time-Out, a ficha
                    // nova sairia com o texto novo e a próxima regeneração a
                    // trocaria pelo antigo, sem aviso.
                    //
                    // Falha de gravação PARA antes de regenerar: a ficha da pasta
                    // não pode dizer uma coisa e o registro da simulação outra.
                    if (!com.radioterapia.ai.util.ObsStore.gravar(pastaReal, numFin, obs)) {
                        return@withContext Regravacao.OBS_FALHOU
                    }
                    val to = if (incluiTimeOut) {
                        val novoReg = com.radioterapia.ai.util.TimeOutStore.Registro(
                            true, medUi, sitioUi, riscoUi, precUi, equipUi, alergiaUi,
                            fracoesUi, protocoloUi)
                        if (!com.radioterapia.ai.util.TimeOutStore.gravar(pastaReal, numFin, novoReg)) {
                            return@withContext Regravacao.TIMEOUT_FALHOU
                        }
                        novoReg.equipamento.takeIf { it.isNotBlank() }?.let {
                            patientCache.atualizarEquipamento(nome, it, prontFin)
                        }
                        novoReg.medico.takeIf { it.isNotBlank() }?.let {
                            patientCache.atualizarSexoMedico(nome, "", it, prontFin)
                        }
                        // NÃO tocar em View aqui: este bloco roda em
                        // Dispatchers.IO. O resumo é atualizado na thread
                        // principal, junto com mostrarFaseResultado().
                        com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut(
                            novoReg.medico, novoReg.sitio, novoReg.riscoQueda,
                            novoReg.precaucaoContato, sim.rosto?.arquivoLocal,
                            novoReg.equipamento, novoReg.alergia, novoReg.fracoesMax)
                    } else null
                    // O protocolo é o marcado na tela. Sem Time-Out ele não fica
                    // gravado em lugar nenhum, e ler do registro devolveria vazio:
                    // a ficha sairia sem as páginas do serviço.
                    com.radioterapia.ai.pdf.PdfBuilder.gerarFolhaPosicionamento(
                        this@FinalizarActivity, dados, fotos, pdf,
                        rotulos = rotulos, landscape = cfg.pdfLandscape,
                        etiquetaLarguraMm = cfg.pdfEtiquetaLarguraMm,
                        etiquetaAlturaMm = cfg.pdfEtiquetaAlturaMm,
                        observacoes = obs,
                        timeOut = to,
                        margemImpressaoMm = cfg.pdfMargemMm,
                        protocoloId = protocoloUi)
                    // A ficha regenerada acima é a de CACHE, a que Visualizar e
                    // Imprimir usam. A da pasta do paciente — a que a sincronia
                    // leva e que o carrossel e o Histórico reimprimem — ficaria
                    // com os dados de antes, com o mesmo nome da nova.
                    if (atualizarFichaNaPasta(pdf, numFin, pastaFin)) Regravacao.OK
                    else Regravacao.PASTA_FALHOU
                }
                travarAcoesDaEdicao(false)
                // Falha ao gravar observação ou Time-Out deixa a tela em edição,
                // com o que foi digitado, para tentar de novo.
                val ok = resultado == Regravacao.OK || resultado == Regravacao.PASTA_FALHOU
                if (ok) { atualizarResumoFinal(); mostrarFaseResultado() }
                // Ficha nova na pasta do paciente: um envio novo, e a linha de
                // sincronia passa a acompanhá-lo. Sem a ficha na pasta
                // (PASTA_FALHOU) nada novo há para enviar, e a linha ficaria
                // dizendo "realizada" sobre a ficha antiga.
                if (resultado == Regravacao.OK) iniciarAcompanhamentoSync()
                android.widget.Toast.makeText(this@FinalizarActivity,
                    when (resultado) {
                        Regravacao.OK -> getString(R.string.obs_updated)
                        Regravacao.PASTA_FALHOU -> getString(R.string.err_save, pdf.name)
                        Regravacao.SEM_SIMULACAO -> getString(R.string.sim_not_found)
                        Regravacao.OBS_FALHOU -> getString(R.string.err_obs_save)
                        Regravacao.TIMEOUT_FALHOU -> getString(R.string.err_timeout_save)
                    },
                    android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                travarAcoesDaEdicao(false)
                android.widget.Toast.makeText(this@FinalizarActivity,
                    getString(R.string.err_unexpected, e.message ?: ""),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Desfecho de "Salvar alterações". */
    private enum class Regravacao { OK, PASTA_FALHOU, SEM_SIMULACAO, OBS_FALHOU, TIMEOUT_FALHOU }

    /**
     * Põe a ficha regenerada na pasta do paciente, no lugar das fichas desta
     * simulação, pelo mesmo caminho que a finalização usou (SAF ou arquivo).
     *
     * A nova é gravada ANTES de as outras saírem: falhar no meio deixa a pasta
     * com uma ficha a mais, nunca sem ficha. Sai só a ficha que a nova
     * substitui ([RegrasFinalizacao.fichaSubstituivel]): desta simulação, no
     * esquema de nomes atual. Ficha de outra simulação, ou de nome legado com o
     * mesmo número, fica.
     *
     * A pasta precisa existir — é dela que a simulação acabou de ser lida.
     * Criá-la aqui deixaria uma ficha numa pasta que ninguém procura.
     *
     * Roda em IO.
     *
     * @return `true` quando a ficha nova ficou gravada na pasta.
     */
    private fun atualizarFichaNaPasta(pdf: File, numSim: Int, nomePasta: String): Boolean {
        if (!pdf.isFile || nomePasta.isBlank()) return false
        val uriSaf = config.pastaFotosUri
        if (uriSaf.isNotBlank()) {
            return try {
                val base = androidx.documentfile.provider.DocumentFile
                    .fromTreeUri(this, Uri.parse(uriSaf)) ?: return false
                val pastaPac = base.findFile("PhotoID_RT")?.findFile("PHOTOS")?.findFile(nomePasta)
                    ?.takeIf { it.isDirectory } ?: return false
                gravarSaf(pastaPac, pdf.name, "application/pdf", pdf)
                pastaPac.listFiles().forEach { doc ->
                    val n = doc.name ?: return@forEach
                    if (doc.isFile && n != pdf.name && RegrasFinalizacao.fichaSubstituivel(n, numSim)) {
                        doc.delete()
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        val pasta = File(StorageLocal.photos(this), nomePasta)
        return try {
            if (!pasta.isDirectory) {
                false
            } else {
                pdf.copyTo(File(pasta, pdf.name), overwrite = true)
                pasta.listFiles()?.forEach { arq ->
                    if (arq.isFile && arq.name != pdf.name &&
                        RegrasFinalizacao.fichaSubstituivel(arq.name, numSim)) arq.delete()
                }
                true
            }
        } catch (_: Exception) {
            false
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
                // Com o prontuário: sem ele, entre homônimas o cadastro escolhido
                // seria o mais completo, e o histórico mostraria este rosto no
                // cartão de outra paciente.
                patientCache.salvarCaminhoFotoRosto(nomePaciente, destino.absolutePath,
                    sessionManager.prontuario)
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

    /**
     * Gera a ficha em cache, já com o nome que ela terá na pasta do paciente.
     *
     * Cache e pasta com o MESMO nome: é este arquivo que Visualizar, Imprimir e
     * a regravação de "Salvar alterações" usam, e um nome só para os dois é o
     * que permite reconhecer, na pasta, qual ficha ele substitui.
     */
    private fun gerarFolhaPosicionamento(nome: String, fotos: List<File>, numSim: Int, instantePdf: Long,
                                         timeOut: com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut? = null): File {
        val pdfFile = File(cacheDir,
            NomeArquivo.montar(nome, NomeArquivo.Tipo.FICHA, numSim, instantePdf, 1, "pdf"))
        val extrasSession = sessionManager.obterIdsExtras().map {
            PdfBuilder.IdExtra(it.first, it.second)
        }
        val cadastro = cadastroDe(nome, sessionManager.prontuario)
        val dados = PdfBuilder.DadosCabecalho(
            nomePaciente = nome,
            nascimento = com.radioterapia.ai.util.DateUtils.formatarNascimento(
                sessionManager.dataNascimento,
                com.radioterapia.ai.i18n.LocaleManager.obterIdiomaAtual(this)),
            prontuario = sessionManager.prontuario,
            idsExtras = extrasSession,
            dataSimulacao = Date(instantePdf),
            numeroSimulacao = numSim,
            nomeClinica = config.nomeClinica,
            sexo = cadastro?.sexo ?: "",
            medicoAssistente = cadastro?.medicoAssistente ?: ""
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


    // ============= BACKUP LOCAL =============

    /**
     * @param nomePastaPac nome da pasta do paciente ("NOME - PRONTUÁRIO"), calculado
     *   por quem chama e usado TAMBÉM para gravar Time-Out e observações. Antes esta
     *   função recebia um `nomePasta` que era ignorado no corpo — a pasta era
     *   recalculada aqui dentro — enquanto o chamador usava aquele valor morto para
     *   escrever o Time-Out num diretório que nunca existiu.
     */
    private fun salvarTodasLocalmente(
        nomePaciente: String,
        fotos: List<SessionManager.FotoCategorizada>,
        pdfFolha: File,
        nomePastaPac: String,
        numSim: Int,
        editando: Boolean,
        reconstruidos: Set<String>
    ): List<String> {
        val erros = mutableListOf<String>()

        // Padrão dos arquivos (NomeArquivo):
        //   <INICIAIS>_<TIPO>[_NS<n>]_<DD>_<MMM>_<AAAA>_<HH>_<MM>_<SS>_<CONTAGEM>.jpg
        // O instante de cada foto é o da captura (data do arquivo da sessão). A
        // contagem é por tipo, dentro do lote: neste fluxo ela coincide com o
        // "Posicionamento.N" / "Acessório.N" da ficha, porque simulação nova não
        // tem arquivo com o seu número, e no modo edição os anteriores que a
        // sessão trouxe saem depois que o conjunto novo está gravado (caminho de
        // arquivo, abaixo). O instante distingue os nomes novos dos antigos: a
        // cópia para a sessão leva a data da reconstrução.
        val nomes = NomeArquivo.nomearLote(
            nomePaciente,
            fotos.map { f ->
                NomeArquivo.tipoDe(f.categoria) to
                    (f.arquivo.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis())
            },
            numSim,
            NomeArquivo.Contexto.SIMULACAO)
        val fotosNomeadas = nomes.zip(fotos.map { it.arquivo })
        // A ficha guardada leva o nome da cópia em cache (ver gerarFolhaPosicionamento).
        val nomePdf = pdfFolha.name

        // ORIGINAIS: mesmo nome da foto processada, com "_ORIGINAL" antes da
        // extensão. Vão para a MESMA pasta do paciente, sem qualquer edição —
        // só renomeados — para o sincronizador levar também o quadro cheio.
        // Cada par fica lado a lado e é óbvio qual original pertence a qual foto.
        val originaisNomeados = fotosNomeadas.mapNotNull { (nome, arq) ->
            sessionManager.originalDe(arq)?.let { orig ->
                NomeArquivo.nomeOriginal(nome) to orig
            }
        }
        // Tudo o que esta finalização grava na pasta: nada disto sai na limpeza
        // do modo edição, mesmo que o nome coincida com um antigo.
        val gravadosAgora = (fotosNomeadas.map { it.first } +
            originaisNomeados.map { it.first } + nomePdf).toSet()

        // ARQUIVADAS da sessão, contadas ANTES de qualquer cópia. O que não
        // chegar à pasta do paciente é retido, nunca apagado (ver
        // reterArquivadasQueFaltaram, no fim).
        //
        // Só as DESTE rascunho: uma sobra de sessão anterior que ainda esteja em
        // ARQUIVADAS é foto de outro paciente, e copiá-la para cá a poria na
        // pasta errada e no servidor dele.
        val inicioRascunho = sessionManager.timestampInicio
        val sessaoArquivadas = FotosArquivadas.pasta(sessionManager.pastaDeTrabalho())
            .listFiles()?.filter { it.isFile }.orEmpty()
            .filter { RegrasFinalizacao.arquivadaDoRascunho(it.name, inicioRascunho) }
        var arquivadasCopiadas = 0

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
                // ARQUIVADAS: o mesmo passo do caminho de arquivo, abaixo. A
                // sessão é esvaziada logo depois da finalização; o que foi
                // substituído na sala só sobrevive se for levado agora. Cada
                // cópia que deu certo é contada; pasta que não se cria conta
                // como nenhuma.
                if (sessaoArquivadas.isNotEmpty()) {
                    try {
                        val dirArq = obterOuCriarPasta(pastaPac, FotosArquivadas.PASTA)
                        if (dirArq != null) sessaoArquivadas.forEach { a ->
                            try {
                                gravarSaf(dirArq, a.name, "image/jpeg", a)
                                arquivadasCopiadas++
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
                fotosNomeadas.forEach { (nome, origem) ->
                    try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) salvarImgMediaStore(origem, nome, "Pictures/PhotoID_RT") } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                erros.add("Pasta local: ${e.message}")
            }
            reterArquivadasQueFaltaram(sessaoArquivadas.size, arquivadasCopiadas, nomePastaPac, erros)
            return erros
        }

        // ===== Caminho padrão: PhotoID_RT/PHOTOS/<PACIENTE> via File I/O.
        // StorageLocal grava na RAIZ do armazenamento (/storage/emulated/0/PhotoID_RT)
        // quando há "Acesso a todos os arquivos"; senão, na pasta interna do app. =====
        try {
            com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
            val photos = File(com.radioterapia.ai.util.StorageLocal.photos(this), nomePastaPac).apply { mkdirs() }
            val errosAntesDeGravar = erros.size
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

            // Modo edição: tira da pasta os arquivos ANTIGOS que a sessão trouxe
            // e que acabaram de ser gravados de novo, com nome novo — senão a
            // simulação ficaria em dobro no carrossel e na ficha regenerada.
            //
            // DEPOIS de gravar, e só se tudo foi gravado: com uma cópia falha,
            // o antigo é a única versão daquela foto, e a sessão é limpa logo
            // em seguida. Ficar em dobro se corrige; perder, não.
            //
            // Sai SÓ o que a reconstrução trouxe ([SessionManager.reconstruidos])
            // e a ficha que a nova substitui. Foto que não entrou na sessão,
            // quadro cheio que não veio, arquivo sem tipo, legado que a
            // classificação por nome não alcança e DICOM ficam: não seriam
            // gravados de novo, e apagá-los seria perdê-los.
            if (editando && erros.size == errosAntesDeGravar) {
                apagarSubstituidosNaPasta(photos, reconstruidos, numSim, gravadosAgora)
            }

            // ARQUIVADAS: o que foi substituido enquanto o paciente estava na
            // sala mora na pasta de trabalho da sessao, que e temporaria. Sem
            // este passo, o arquivamento seria apagado junto com o rascunho e
            // nao teria servido para nada. O total copiado é conferido no fim.
            if (sessaoArquivadas.isNotEmpty()) {
                arquivadasCopiadas = try {
                    FotosArquivadas.transferir(sessaoArquivadas, photos)
                } catch (_: Exception) { 0 }
            }

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
        reterArquivadasQueFaltaram(sessaoArquivadas.size, arquivadasCopiadas, nomePastaPac, erros)
        return erros
    }

    /**
     * Limpeza do modo edição na pasta do paciente. Ver o comentário no ponto
     * de chamada, em [salvarTodasLocalmente], e [RegrasFinalizacao.aApagarNaRegravacao].
     *
     * Roda em IO.
     */
    private fun apagarSubstituidosNaPasta(pasta: File, reconstruidos: Set<String>,
                                          numSim: Int, gravadosAgora: Set<String>) {
        val daPasta = RegrasFinalizacao.nomesNaPasta(reconstruidos, pasta.absolutePath)
        val nomes = pasta.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        RegrasFinalizacao.aApagarNaRegravacao(nomes, daPasta, numSim, gravadosAgora).forEach { n ->
            try { File(pasta, n).delete() } catch (_: Exception) {}
        }
    }

    /**
     * Confere a cópia das arquivadas da sessão para a pasta do paciente.
     *
     * Faltou alguma: `ARQUIVADAS/` inteira sai da sessão para a pasta de
     * retidas ([SessionManager.reterArquivadas]) ANTES de a sessão ser limpa, e
     * a falta vira linha de erro no resultado e no log de auditoria. A limpeza
     * da sessão apaga `ARQUIVADAS/` de propósito, e sem este passo levaria a
     * única cópia da foto substituída na sala.
     *
     * Roda em IO.
     */
    private fun reterArquivadasQueFaltaram(total: Int, copiadas: Int, nomePastaPac: String,
                                           erros: MutableList<String>) {
        val faltaram = total - copiadas
        if (faltaram <= 0) return
        val retida = try { sessionManager.reterArquivadas(nomePastaPac) } catch (_: Exception) { null }
        erros.add(
            if (retida != null) getString(R.string.err_arquivadas_retidas, faltaram, total)
            else getString(R.string.err_arquivadas_perdidas, faltaram, total))
        try {
            auditLogger.registrar(
                AuditLogger.Tipo.ERROR,
                "Arquivadas nao copiadas para a pasta do paciente",
                mapOf("faltaram" to faltaram, "total" to total, "retidas" to (retida != null)))
        } catch (_: Exception) {}
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
}

/**
 * Regras da finalização que não dependem de Android, fora da Activity para o
 * teste de JVM alcançá-las: de que pasta e de que simulação a tela reaberta
 * lê, o que a regravação em modo edição tira da pasta e qual cadastro vale
 * para o paciente.
 */
internal object RegrasFinalizacao {

    /**
     * A foto de `ARQUIVADAS/` da sessão foi arquivada NESTE rascunho?
     *
     * [com.radioterapia.ai.util.FotosArquivadas.arquivar] sempre grava o
     * carimbo `_ARQ<ms>`, e o início do rascunho é marcado antes da primeira
     * foto. Carimbo anterior ao início, ou ausente, é sobra de outra sessão.
     * Sem início conhecido não há com o que comparar, e vale o comportamento
     * de sempre: copiar.
     */
    fun arquivadaDoRascunho(nomeArquivo: String, inicioRascunhoMs: Long): Boolean {
        if (inicioRascunhoMs <= 0L) return true
        val carimbo = com.radioterapia.ai.util.QuarentenaArquivadas
            .carimboDeArquivamento(nomeArquivo) ?: return false
        return carimbo >= inicioRascunhoMs
    }

    /**
     * A foto entra na simulação lida da pasta? JPG desta simulação, sem o
     * quadro cheio (`_ORIGINAL` é par de outra foto, e entraria em dobro na
     * ficha) e sem arquivo oculto. O número vem do nome, como em toda leitura
     * da pasta do paciente (ver NomeArquivo).
     */
    fun ehFotoDaSimulacao(nome: String, numSim: Int): Boolean {
        if (nome.startsWith(".")) return false
        if (!nome.endsWith(".jpg", ignoreCase = true) &&
            !nome.endsWith(".jpeg", ignoreCase = true)) return false
        return !NomeArquivo.ehOriginal(nome) && NomeArquivo.numeroSimulacao(nome) == numSim
    }

    /**
     * A simulação [numSim] montada com os arquivos de UMA pasta, já listados.
     *
     * A classificação por tipo é a mesma da busca do Tratamento (NomeArquivo),
     * para a ficha regenerada aqui sair igual à que o carrossel regenera.
     *
     * @param arquivos cada arquivo com o seu instante (data de modificação na
     *   pasta de origem).
     * @return `null` quando a pasta não tem foto desta simulação.
     */
    fun simulacaoDaPasta(nomePaciente: String, nomePasta: String, numSim: Int,
                         arquivos: List<Pair<File, Long>>): TreatmentPhotoFetcher.Simulacao? {
        val fotos = arquivos
            .filter { (f, _) -> ehFotoDaSimulacao(f.name, numSim) }
            .map { (f, instante) ->
                TreatmentPhotoFetcher.FotoInfo(
                    arquivoLocal = f, nomeOriginal = f.name,
                    tipo = tipoFoto(NomeArquivo.tipo(f.name)), timestamp = instante)
            }
        if (fotos.isEmpty()) return null
        return TreatmentPhotoFetcher.Simulacao(
            nomePaciente = nomePaciente,
            nomePastaCompleto = nomePasta,
            numeroSimulacao = numSim,
            timestampPrincipal = fotos.maxOf { it.timestamp },
            fotos = fotos,
            origem = TreatmentPhotoFetcher.Origem.LOCAL_TABLET)
    }

    /**
     * Ficha que uma ficha nova desta simulação substitui: PDF do esquema de
     * nomes atual, com o número desta simulação.
     *
     * Ficha de nome legado fica, mesmo com o mesmo número. A simulação reaberta
     * nesta tela foi gravada no esquema atual; uma legada com o mesmo número é
     * de outra simulação, que só divide o número quando a contagem do cadastro
     * ficou para trás da pasta (reinstalação com as fotos preservadas).
     */
    fun fichaSubstituivel(nome: String, numSim: Int): Boolean {
        if (nome.startsWith(".")) return false
        val a = NomeArquivo.analisar(nome)
        return a.esquemaNovo && a.tipo == NomeArquivo.Tipo.FICHA &&
            a.numeroSimulacao == numSim && !a.original && !a.arquivada
    }

    /**
     * Nomes dos arquivos registrados como reconstruídos que estão DIRETO em
     * [caminhoPasta]. A comparação é pelo caminho inteiro: arquivo de mesmo
     * nome em outra pasta não é este.
     */
    fun nomesNaPasta(reconstruidos: Set<String>, caminhoPasta: String): Set<String> {
        val pasta = File(caminhoPasta).path
        return reconstruidos.map { File(it) }
            .filter { it.parentFile?.path == pasta }
            .map { it.name }
            .toSet()
    }

    /**
     * O que a regravação em modo edição tira da pasta, depois de gravado o
     * conjunto novo.
     *
     * Sai: o arquivo que a reconstrução trouxe desta pasta para a sessão (foto
     * ou quadro cheio, e só desta simulação) e a ficha que a nova substitui
     * ([fichaSubstituivel]). Nunca sai: o que acabou de ser gravado
     * ([gravadosAgora]), arquivo oculto (Time-Out e observação) e todo o resto
     * — foto que não entrou na sessão, quadro cheio que não veio, arquivo sem
     * tipo, legado não reconstruído, DICOM, outra simulação.
     *
     * @param nomesNaPasta arquivos (não subpastas) do primeiro nível da pasta.
     * @param reconstruidos nomes, nesta pasta, que a reconstrução trouxe.
     */
    fun aApagarNaRegravacao(nomesNaPasta: Collection<String>, reconstruidos: Set<String>,
                            numSim: Int, gravadosAgora: Set<String>): List<String> =
        nomesNaPasta.filter { n ->
            n !in gravadosAgora && !n.startsWith(".") &&
                ((n in reconstruidos && NomeArquivo.pertenceASimulacao(n, numSim)) ||
                    fichaSubstituivel(n, numSim))
        }

    /**
     * Pasta onde a finalização grava.
     *
     * Em modo edição, a pasta reaberta — desde que seja deste paciente. Pasta
     * que não casa com o nome (a chave do layout antigo, "NOME POSICIONAMENTO",
     * guardada por rascunhos de versões anteriores) não existe em disco, e
     * gravar nela criaria uma pasta nova; nesse caso, e fora do modo edição,
     * vale a pasta do nome com o prontuário da sessão.
     */
    fun pastaDaFinalizacao(editandoNomePasta: String, nomeNorm: String, prontuario: String): String =
        if (editandoNomePasta.isNotBlank() &&
            StorageLocal.pastaCasaPaciente(editandoNomePasta, nomeNorm)) editandoNomePasta
        else StorageLocal.nomePastaPaciente(nomeNorm, prontuario)

    /**
     * O cadastro devolvido pela busca serve para este paciente?
     *
     * Com prontuário pedido, sim: a chave do cadastro já é nome + prontuário.
     * Sem prontuário, a busca escolhe entre homônimas o registro mais
     * completo, e ele só serve se também não tiver prontuário — registro com
     * prontuário é de outra pessoa de mesmo nome.
     */
    fun cadastroServe(prontuarioPedido: String, prontuarioDoCadastro: String): Boolean =
        prontuarioPedido.isNotBlank() || prontuarioDoCadastro.isBlank()

    /** O que a finalização faz com o registro do Time-Out da simulação. */
    sealed class AcaoTimeOut {
        /** Grava este registro por cima do que houver. */
        data class Gravar(val registro: TimeOutStore.Registro) : AcaoTimeOut()
        /** Página desligada: sem registro, nenhuma regeneração põe Time-Out na ficha. */
        object Apagar : AcaoTimeOut()
        /** O que está em disco fica como está: nada é gravado nem apagado. */
        object Manter : AcaoTimeOut()
    }

    /**
     * O que a finalização grava no registro do Time-Out.
     *
     * Com a página ligada, vale a tela ([daTela]); na edição, ela já veio
     * preenchida com o que estava gravado.
     *
     * Com a página desligada a tela não tem registro, e gravar nenhum APAGA o
     * que existe. Na simulação nova é o que se quer. Na edição — rodada de
     * fotos adicionais da mesma simulação — o registro [gravado] fica: alergia,
     * risco de queda, precaução, sítio e frações não estavam na tela para
     * alguém ter desligado. Só o protocolo vem da tela, porque a faixa de
     * protocolos aparece com ou sem Time-Out e a ficha acabou de sair com o
     * marcado. Sem registro legível na edição, nada é tocado.
     *
     * @param protocolo o protocolo marcado na tela, que entrou na ficha.
     */
    fun acaoTimeOut(daTela: TimeOutStore.Registro?, gravado: TimeOutStore.Registro?,
                    editando: Boolean, protocolo: String): AcaoTimeOut = when {
        daTela != null -> AcaoTimeOut.Gravar(daTela)
        !editando -> AcaoTimeOut.Apagar
        gravado != null && gravado.ativo -> AcaoTimeOut.Gravar(gravado.copy(protocoloId = protocolo))
        else -> AcaoTimeOut.Manter
    }

    /** A linha de estado da impressora no fim da finalização. */
    enum class LinhaImpressora {
        /** Há IP de impressora: testa se ela responde na rede atual. */
        TESTAR_REDE,
        /** Sem IP: avisa que não há impressora de rede. */
        SEM_IMPRESSORA_DE_REDE
    }

    /**
     * A linha da impressora depende só de haver IP configurado.
     *
     * GUARDA: não depende de erro da finalização. Falha ao copiar arquivadas
     * ou ao gravar Time-Out e observação não muda a impressora; fazer a linha
     * ou o botão Imprimir depender disso dizia "impressora indisponível" com a
     * impressora respondendo, e deixava a ficha gravada sem como imprimir. O
     * botão Imprimir tem ação nos dois casos.
     */
    fun linhaImpressora(temImpressora: Boolean): LinhaImpressora =
        if (temImpressora) LinhaImpressora.TESTAR_REDE else LinhaImpressora.SEM_IMPRESSORA_DE_REDE

    /**
     * Linhas DIGITADAS que a observação da simulação aceita, na finalização e
     * na edição da simulação. É o que a caixa de observações da ficha mostra
     * no corpo cheio; linha longa que quebra faz a letra da ficha diminuir.
     */
    const val MAX_LINHAS_OBS = 4

    /**
     * A mudança no campo de observação fica?
     *
     * Recusa só a mudança que AUMENTA as quebras de linha acima do teto. Um
     * texto já gravado com mais linhas que o teto continua editável e pode ser
     * encurtado, em vez de travar o campo.
     *
     * GUARDA: texto que chega a um campo VAZIO fica, com qualquer número de
     * linhas. É assim que a observação gravada entra no campo da edição da
     * simulação, por setText depois de o vigia já estar ligado; recusá-la
     * deixaria o campo vazio, e salvar apagaria a observação.
     */
    fun observacaoAceita(novo: String, anterior: String): Boolean {
        val quebras = novo.count { it == '\n' }
        return quebras < MAX_LINHAS_OBS || anterior.isEmpty() ||
            quebras <= anterior.count { it == '\n' }
    }

    private fun tipoFoto(t: NomeArquivo.Tipo?): TreatmentPhotoFetcher.TipoFoto = when (t) {
        NomeArquivo.Tipo.ROSTO -> TreatmentPhotoFetcher.TipoFoto.ROSTO
        NomeArquivo.Tipo.ETIQUETA -> TreatmentPhotoFetcher.TipoFoto.ETIQUETA
        NomeArquivo.Tipo.POSICIONAMENTO -> TreatmentPhotoFetcher.TipoFoto.POSICIONAMENTO
        NomeArquivo.Tipo.ACESSORIOS -> TreatmentPhotoFetcher.TipoFoto.ACESSORIOS
        NomeArquivo.Tipo.IMPRESSO -> TreatmentPhotoFetcher.TipoFoto.DOCUMENTO
        NomeArquivo.Tipo.FICHA, null -> TreatmentPhotoFetcher.TipoFoto.DESCONHECIDO
    }
}

/**
 * O preenchimento da tela de confirmação numa rodada de fotos adicionais da
 * mesma simulação, sem Android, para o teste de JVM alcançá-lo.
 */
internal object PrefillEdicao {

    /** Os campos da primeira fase que vêm do que está gravado. */
    data class Campos(
        val sitio: String,
        val medico: String,
        val equipamento: String,
        val riscoQueda: Boolean,
        val precaucaoContato: Boolean,
        val alergia: Boolean,
        /** Posição na lista de frações: 0 = não informado. */
        val fracoes: Int,
        /** Vazio = nenhum protocolo marcado. */
        val protocolo: String,
        val observacao: String
    )

    /**
     * A tela lê o gravado? Só na rodada de fotos adicionais ([editando]) e só
     * se a carga ainda não foi feita nesta tela.
     *
     * GUARDA: com a carga restaurada pelo estado salvo da Activity, os campos
     * já voltam com ela e com o que o técnico mudou depois. Uma carga nova
     * compararia com a tela de antes da restauração e devolveria o valor
     * gravado ao campo que ele limpou ou ao alerta que ele desligou.
     */
    fun precisaCarregar(editando: Boolean, cargaRestaurada: Boolean): Boolean =
        editando && !cargaRestaurada

    /**
     * Os campos depois da leitura do Time-Out e da observação gravados.
     *
     * GUARDA: campo que mudou enquanto a leitura corria ([antes] diferente de
     * [agora]) fica com o que está na tela. Foi o técnico que mexeu, e a carga
     * não passa por cima dele.
     *
     * Sem registro do Time-Out ([gravado] nulo), os campos dele ficam como
     * estão: médico e equipamento do cadastro, o resto vazio — a tela de uma
     * simulação nova. Com registro, interruptores, frações e protocolo vêm
     * dele, mesmo desligados ou vazios: é o que foi decidido na rodada
     * anterior, e é o que a regravação mantém. Texto vazio no registro não
     * apaga o que o cadastro trouxe; observação gravada vazia, idem.
     *
     * Alergia ligada é só o valor "SIM", como no PdfBuilder, que só imprime a
     * tarja com ele.
     *
     * @param maxFracoes a última posição da lista de frações; valor gravado
     *   fora dela é trazido para dentro.
     */
    fun aplicar(antes: Campos, agora: Campos, gravado: TimeOutStore.Registro?,
                obsGravada: String, maxFracoes: Int): Campos {
        fun texto(a: String, b: String, g: String?): String =
            if (b != a || g == null || g.isBlank()) b else g
        fun <T> valor(a: T, b: T, g: T?): T =
            if (b != a || g == null) b else g
        return Campos(
            sitio = texto(antes.sitio, agora.sitio, gravado?.sitio),
            medico = texto(antes.medico, agora.medico, gravado?.medico),
            equipamento = texto(antes.equipamento, agora.equipamento, gravado?.equipamento),
            riscoQueda = valor(antes.riscoQueda, agora.riscoQueda, gravado?.riscoQueda),
            precaucaoContato = valor(antes.precaucaoContato, agora.precaucaoContato,
                gravado?.precaucaoContato),
            alergia = valor(antes.alergia, agora.alergia, gravado?.let { it.alergia == "SIM" }),
            fracoes = valor(antes.fracoes, agora.fracoes,
                gravado?.fracoesMax?.coerceIn(0, maxFracoes.coerceAtLeast(0))),
            protocolo = valor(antes.protocolo, agora.protocolo, gravado?.protocoloId),
            observacao = texto(antes.observacao, agora.observacao, obsGravada.trim()))
    }
}

/**
 * O limite de linhas da observação aplicado a um campo de texto: altura de no
 * máximo [RegrasFinalizacao.MAX_LINHAS_OBS] linhas e o vigia que recusa a
 * quebra a mais. Um só lugar para a finalização e a edição da simulação, que
 * aplicam a mesma regra.
 */
internal object CampoObservacao {

    fun limitar(campo: EditText) {
        campo.maxLines = RegrasFinalizacao.MAX_LINHAS_OBS
        campo.addTextChangedListener(object : android.text.TextWatcher {
            private var anterior = ""
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                anterior = s?.toString() ?: ""
            }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (RegrasFinalizacao.observacaoAceita(s?.toString() ?: "", anterior)) return
                // Copiado antes do setText: a chamada reentrante do vigia troca
                // `anterior` pelo texto recusado. Ela não entra em laço, porque
                // o texto reposto tem menos quebras que o recusado.
                val aceito = anterior
                campo.setText(aceito)
                campo.setSelection(aceito.length.coerceAtMost(campo.text.length))
            }
        })
    }
}
