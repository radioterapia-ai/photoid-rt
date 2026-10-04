package com.radioterapia.ai.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.radioterapia.ai.R
import com.radioterapia.ai.audit.AuditLogger
import com.radioterapia.ai.patient.PatientCache
import kotlinx.coroutines.launch

/**
 * Tela de edição de um paciente do histórico local.
 *
 * Lembrar:
 *  - Edição **não** afeta as fotos no servidor (filosofia: tablet só envia, jamais apaga/sobrescreve)
 *  - Próxima simulação criará pasta nova com o nome corrigido (pasta antiga vira "órfã")
 *  - Edição é registrada no log de auditoria com timestamp (sem identificar quem)
 */
class EditarPacienteActivity : com.radioterapia.ai.BaseActivity() {

    /** Devolve à tela anterior (Confirmar dados) o nome final, para que ela
     *  recarregue os dados/pasta corretos mesmo após renomear o paciente. */
    private fun concluirCom(nomeFinal: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_NOME_FINAL, nomeFinal))
        finish()
    }

    override fun tituloPadrao(): String = getString(R.string.edit_patient)

    private lateinit var patientCache: PatientCache
    private lateinit var auditLogger: AuditLogger

    private lateinit var nomeOriginal: String
    private lateinit var dadosOriginais: PatientCache.DadosPaciente

    private lateinit var edtNome: EditText
    private lateinit var edtNasc: EditText
    private val configData by lazy { com.radioterapia.ai.AppConfig(this) }
    private lateinit var edtPront: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editar_paciente)
        supportActionBar?.title = getString(R.string.edit_patient)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        patientCache = PatientCache(this)
        auditLogger = AuditLogger(this)

        nomeOriginal = intent.getStringExtra(EXTRA_NOME) ?: ""
        // Com o prontuário de quem abriu (o visualizador do paciente), o
        // registro é o deste paciente. Só pelo nome, a busca escolhe entre
        // homônimas o registro mais completo — e a edição, a migração da pasta
        // e a exclusão seguiriam o prontuário da outra.
        val prontuarioPedido = intent.getStringExtra(EXTRA_PRONTUARIO) ?: ""
        val dados = patientCache.obterDadosPaciente(nomeOriginal, prontuarioPedido)
        if (dados == null) {
            Toast.makeText(this, getString(R.string.hc_patient_not_found), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        dadosOriginais = dados

        edtNome = findViewById(R.id.edtEditNome)
        edtNasc = findViewById(R.id.edtEditNasc)
        edtPront = findViewById(R.id.edtEditPront)

        edtNome.setText(dados.nome)
        // O campo mostra no formato do servico; o cadastro guarda canonico.
        edtNasc.setText(com.radioterapia.ai.util.DateUtils.canonicoParaEntrada(
            dados.nascimento, configData.formatoData))
        edtPront.setText(dados.prontuario)
        com.radioterapia.ai.util.UiText.aplicarMascaraData(edtNasc, configData.formatoData)
        // Sexo/Médico (corrigíveis na edição do cadastro)
        when (dados.sexo) {
            "M" -> findViewById<android.widget.RadioButton>(R.id.rbEditSexoM).isChecked = true
            "F" -> findViewById<android.widget.RadioButton>(R.id.rbEditSexoF).isChecked = true
        }
        // Médico e equipamento NÃO são editados aqui (cadastro): pertencem à
        // simulação (tela "Editar simulação"). Preserva-se o que já existe.

        findViewById<Button>(R.id.btnSalvarEdicao).setOnClickListener { salvar() }
        findViewById<Button>(R.id.btnRemover).setOnClickListener { confirmarRemocao() }
        findViewById<Button>(R.id.btnCancelarEdicao).setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun salvar() {
        val novoNome = edtNome.text.toString().trim()
        // De volta ao canonico ANTES de qualquer validacao ou gravacao. String
        // vazia = data invalida no formato escolhido, e quem chama ja trata
        // nascimento vazio.
        val novoNasc = com.radioterapia.ai.util.DateUtils.entradaParaCanonico(
            edtNasc.text.toString().trim(), configData.formatoData)
        val novoPront = edtPront.text.toString().trim()

        if (novoNome.isBlank()) {
            Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show(); return
        }
        // Data real (não só o comprimento): 31/02 e datas futuras eram aceitas.
        if (novoNasc.isNotBlank() &&
            !com.radioterapia.ai.util.DateUtils.nascimentoValido(novoNasc)) {
            Toast.makeText(this, R.string.birth_invalid, Toast.LENGTH_LONG).show(); return
        }
        // CORREÇÃO: prontuário já usado por OUTRO paciente costuma ser erro de
        // digitação ou paciente duplicado — avisa antes de gravar.
        val donoPront = patientCache.prontuarioDeOutroPaciente(novoPront, novoNome)
        if (donoPront != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pront_dup_title)
                .setMessage(getString(R.string.pront_dup_msg, novoPront, donoPront))
                .setPositiveButton(R.string.confirm) { _, _ -> prosseguirSalvar(novoNome, novoNasc, novoPront) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        prosseguirSalvar(novoNome, novoNasc, novoPront)
    }

    private fun prosseguirSalvar(novoNome: String, novoNasc: String, novoPront: String) {

        // Detectar mudanças
        val mudancas = mutableListOf<String>()
        if (novoNome != dadosOriginais.nome) mudancas.add("nome: '${dadosOriginais.nome}' → '$novoNome'")
        if (novoNasc != dadosOriginais.nascimento) mudancas.add("nascimento: '${dadosOriginais.nascimento}' → '$novoNasc'")
        if (novoPront != dadosOriginais.prontuario) mudancas.add("prontuário: '${dadosOriginais.prontuario}' → '$novoPront'")

        sexoEditado = when {
            findViewById<android.widget.RadioButton>(R.id.rbEditSexoM).isChecked -> "M"
            findViewById<android.widget.RadioButton>(R.id.rbEditSexoF).isChecked -> "F"
            else -> ""
        }
        // Cadastro edita só o sexo entre os "extras"; médico/equip ficam como estavam.
        medicoEditado = dadosOriginais.medicoAssistente
        equipEditado = dadosOriginais.equipamento
        val extrasMudaram = sexoEditado != dadosOriginais.sexo

        if (mudancas.isEmpty()) {
            if (extrasMudaram) {
                patientCache.atualizarSexoMedico(dadosOriginais.nome, sexoEditado,
                    medicoEditado, dadosOriginais.prontuario)
                patientCache.atualizarEquipamento(dadosOriginais.nome, equipEditado,
                    dadosOriginais.prontuario)
                auditLogger.registrar(AuditLogger.Tipo.EDIT, "Sexo/médico atualizados",
                    mapOf("nome" to dadosOriginais.nome, "sexo" to sexoEditado,
                          "medico" to medicoEditado))
                Toast.makeText(this, R.string.edit_logged, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.hc_no_changes), Toast.LENGTH_SHORT).show()
            }
            concluirCom(dadosOriginais.nome); return
        }

        // Identificação (nome/prontuário) mudou: decidir o destino de fotos, PDF e
        // lista de tratamento junto com o usuário. Só nascimento: edição simples.
        val identMudou = novoNome != dadosOriginais.nome || novoPront != dadosOriginais.prontuario
        if (identMudou) {
            AlertDialog.Builder(this)
                .setTitle(R.string.edit_migrate_title)
                .setMessage(getString(R.string.edit_migrate_msg, dadosOriginais.nome, novoNome))
                .setPositiveButton(R.string.edit_opt_update_all) { _, _ ->
                    executarMigracao(novoNome, novoNasc, novoPront, mudancas)
                }
                .setNeutralButton(R.string.edit_opt_keep_both) { _, _ ->
                    manterAmbos(novoNome, novoNasc, novoPront, mudancas)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            aplicarEdicao(novoNome, novoNasc, novoPront, mudancas)
        }
    }

    private var sexoEditado = ""
    private var medicoEditado = ""
    private var equipEditado = ""

    /** ATUALIZAR TUDO: renomeia a pasta e os arquivos, migra o registro, transfere a
     *  lista de tratamento e regenera o PDF da simulação mais recente com o novo nome. */
    private fun executarMigracao(novoNome: String, novoNasc: String, novoPront: String,
                                 mudancas: List<String>) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val resultado = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                migrarArquivosERegistros(novoNome, novoNasc, novoPront)
            }
            when (resultado) {
                "conflito" -> AlertDialog.Builder(this@EditarPacienteActivity)
                    .setTitle(R.string.warning)
                    .setMessage(R.string.edit_folder_conflict)
                    .setPositiveButton(R.string.ok, null).show()
                "ok" -> {
                    auditLogger.registrar(
                        AuditLogger.Tipo.EDIT, "Paciente migrado (edição completa)",
                        mapOf("nome_antigo" to nomeOriginal, "nome_novo" to novoNome,
                              "mudancas" to mudancas.joinToString("; ")))
                    Toast.makeText(this@EditarPacienteActivity,
                        R.string.edit_done_migrated, Toast.LENGTH_LONG).show()
                    finish()
                }
                else -> Toast.makeText(this@EditarPacienteActivity,
                    "Erro na migração: " + resultado, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun migrarArquivosERegistros(novoNome: String, novoNasc: String,
                                                 novoPront: String): String {
        // Atualiza o cadastro (sexo/médico/equip) ANTES de regenerar o PDF, para
        // a etiqueta virtual e o cabeçalho saírem com o sexo novo.
        patientCache.atualizarSexoMedico(novoNome, sexoEditado, medicoEditado, novoPront)
        patientCache.atualizarEquipamento(novoNome, equipEditado, novoPront)
        return try {
            val photos = com.radioterapia.ai.util.StorageLocal.photos(this)
            val prontAntigo = dadosOriginais.prontuario

            // Pasta antiga pelo resolvedor oficial. O fallback anterior casava
            // SO POR NOME: com duas homonimas, editar o cadastro de uma
            // renomeava a pasta da outra, e as fotos passavam a morar sob o
            // nome errado sem nenhum aviso. resolverPastaSim aplica a regra de
            // divergencia de prontuario e, quando nao tem certeza, devolve o
            // caminho exato (que nao existe) — e o `exists()` abaixo trata.
            // GUARDA: sem a pasta exata, o resolvedor desempata por uma regra
            // mais fraca (sem prontuário, a primeira homônima de nome exato; com
            // ele, a pasta antiga mesmo ao lado de uma homônima de outro
            // prontuário). Só se renomeia a pasta que a regra de escolha aceita;
            // senão ela fica como está, que é o erro barato.
            val nomeExato = com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(nomeOriginal, prontAntigo)
            val dirResolvido = com.radioterapia.ai.util.StorageLocal.resolverPastaSim(
                this, nomeOriginal, 1, nomeExato, prontAntigo)
            val dirAntigo = if (!dirResolvido.exists() || pastaServeAEstePaciente(dirResolvido, nomeExato))
                dirResolvido else java.io.File(photos, nomeExato)
            val dirNovo = java.io.File(photos,
                com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(novoNome, novoPront))

            if (dirAntigo.exists()) {
                if (dirNovo.exists() && dirNovo.absolutePath != dirAntigo.absolutePath)
                    return "conflito"
                if (dirNovo.absolutePath != dirAntigo.absolutePath &&
                    !dirAntigo.renameTo(dirNovo)) return "falha ao renomear a pasta"
                // O nome do paciente nos ARQUIVOS, pela regra de cada esquema
                // (NomeArquivo.renomearParaPaciente):
                //  - esquema novo: troca so as iniciais; tipo, data, contador e
                //    os sufixos de arquivada e de original ficam, e o par foto +
                //    original continua par. Iniciais iguais = arquivo intocado.
                //  - legado: continua legado, so o prefixo com o nome completo
                //    muda, reconhecido nas duas grafias em que foi gravado (com
                //    e sem apostrofo/hifen). Nenhum arquivo e convertido para o
                //    esquema novo.
                // ARQUIVADAS entra junto (um nivel): o arquivado tambem e
                // registro do paciente e nao pode ficar com o nome antigo.
                // Alvo que ja existe nao e sobrescrito, e cada arquivo que nao
                // pode ser renomeado conta no log de auditoria.
                var falhas = 0
                val pastasComArquivos = listOf(dirNovo,
                    java.io.File(dirNovo, com.radioterapia.ai.util.FotosArquivadas.PASTA))
                for (p in pastasComArquivos) {
                    p.listFiles()?.forEach { f ->
                        if (!f.isFile || f.name.startsWith(".")) return@forEach
                        val novo = com.radioterapia.ai.util.NomeArquivo
                            .renomearParaPaciente(f.name, nomeOriginal, novoNome) ?: return@forEach
                        if (novo == f.name) return@forEach
                        val alvo = java.io.File(p, novo)
                        if (alvo.exists() || !f.renameTo(alvo)) falhas++
                    }
                }
                if (falhas > 0) {
                    auditLogger.registrar(AuditLogger.Tipo.ERROR,
                        "Arquivos nao renomeados na migracao",
                        mapOf("nome_novo" to novoNome, "arquivos" to falhas))
                }
            }

            patientCache.migrarRegistro(nomeOriginal, novoNome, novoPront, novoNasc)

            val trat = com.radioterapia.ai.treatment.TreatmentListManager(this)
            if (trat.estaEmTratamento(nomeOriginal)) {
                trat.remover(nomeOriginal); trat.alocar(novoNome)
            }

            try { regenerarPdfPosMigracao(novoNome, novoNasc, novoPront, dirNovo, sexoEditado) }
            catch (_: Exception) { /* pdf é best-effort; fotos/registro já migrados */ }
            "ok"
        } catch (e: Exception) { e.message ?: "erro" }
    }

    /** Regenera a folha da simulação MAIS RECENTE já com o novo nome (o PDF antigo
     *  trazia o nome anterior impresso no cabeçalho e nos arquivos). */
    /**
     * `suspend` em vez de `runBlocking`. Esta função já roda dentro de
     * `withContext(Dispatchers.IO)`, então o `runBlocking` anterior não travava
     * a interface — travava uma thread do pool de IO enquanto a varredura de
     * pastas acontecia, que é justamente o pool de onde a varredura precisa sair.
     */
    private suspend fun regenerarPdfPosMigracao(nome: String, nasc: String, pront: String,
                                                dir: java.io.File, sexo: String) {
        if (!dir.exists()) return
        // Só simulação da pasta migrada ('dir'), buscada com o prontuário: a
        // mais recente pelo nome pode ser a de uma homônima, e a ficha dela
        // seria gravada nesta pasta com o cabeçalho deste paciente.
        val fetcher = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this)
        val sim = fetcher.buscarSimulacoes(nome, pront)
            .filter { it.nomePastaCompleto == dir.name }
            .maxByOrNull { it.timestampPrincipal } ?: return

        val fotos = mutableListOf<java.io.File>()
        val rotulos = mutableListOf<String>()
        sim.rosto?.let { fotos.add(it.arquivoLocal); rotulos.add("Rosto") }
        sim.etiqueta?.let { fotos.add(it.arquivoLocal); rotulos.add("Etiqueta") }
        sim.posicionamentos.forEachIndexed { i, f ->
            fotos.add(f.arquivoLocal); rotulos.add("Posicionamento." + (i + 1)) }
        sim.acessoriosLista.forEachIndexed { i, f ->
            fotos.add(f.arquivoLocal); rotulos.add("Acessório." + (i + 1)) }
        if (fotos.isEmpty()) return

        // Remove os PDFs antigos DESTA simulação (traziam o nome anterior). Só
        // desta: as simulações dividem a pasta, e a regra de NomeArquivo separa
        // pela marca no nome, nos dois esquemas.
        dir.listFiles()?.filter { f ->
            f.isFile && com.radioterapia.ai.util.NomeArquivo
                .ehFichaDaSimulacao(f.name, sim.numeroSimulacao)
        }?.forEach { it.delete() }

        val config = com.radioterapia.ai.AppConfig(this)
        // Gravada na pasta do paciente: nome com as iniciais do nome NOVO.
        val saida = java.io.File(dir, com.radioterapia.ai.util.NomeArquivo.montar(
            nome, com.radioterapia.ai.util.NomeArquivo.Tipo.FICHA, sim.numeroSimulacao,
            System.currentTimeMillis(), 1, "pdf"))
        val dados = com.radioterapia.ai.pdf.PdfBuilder.DadosCabecalho(
            nomePaciente = nome,
            nascimento = nasc,
            prontuario = pront,
            idsExtras = emptyList(),
            dataSimulacao = java.util.Date(sim.timestampPrincipal),
            numeroSimulacao = sim.numeroSimulacao,
            nomeClinica = config.nomeClinica,
            sexo = sexo,
            medicoAssistente = patientCache.obterDadosPaciente(nome, pront)
                ?.takeIf { d ->
                    com.radioterapia.ai.treatment.TreatmentPhotoFetcher.cadastroServeAoPaciente(
                        pront, d.prontuario, pront.isBlank() && patientCache.temHomonimos(nome))
                }
                ?.medicoAssistente ?: ""
        )
        // Time-Out da pasta correta (após migração, os arquivos já estão em 'dir').
        val toReg = com.radioterapia.ai.util.TimeOutStore.ler(dir, sim.numeroSimulacao)
        val timeOut = if (toReg != null && toReg.ativo)
            com.radioterapia.ai.pdf.PdfBuilder.DadosTimeOut(
                toReg.medico, toReg.sitio, toReg.riscoQueda,
                toReg.precaucaoContato, sim.rosto?.arquivoLocal,
                toReg.equipamento, toReg.alergia, toReg.fracoesMax)
        else null
        com.radioterapia.ai.pdf.PdfBuilder.gerarFolhaPosicionamento(
            this, dados, fotos, saida, rotulos = rotulos, landscape = config.pdfLandscape,
            etiquetaLarguraMm = config.pdfEtiquetaLarguraMm,
            etiquetaAlturaMm = config.pdfEtiquetaAlturaMm,
            observacoes = com.radioterapia.ai.util.ObsStore.ler(dir, sim.numeroSimulacao),
            timeOut = timeOut,
            margemImpressaoMm = config.pdfMargemMm,
            protocoloId = toReg?.protocoloId.orEmpty())
    }

    /** CRIAR NOVO: mantém o cadastro antigo (com as fotos) e cria o novo registro.
     *  Se o antigo está em tratamento, o usuário decide como fica a lista. */
    private fun manterAmbos(novoNome: String, novoNasc: String, novoPront: String,
                            mudancas: List<String>) {
        patientCache.tocarUltimaSimulacao(novoNome, novoPront, novoNasc)
        patientCache.atualizarSexoMedico(novoNome, sexoEditado, medicoEditado, novoPront)
        patientCache.atualizarEquipamento(novoNome, equipEditado, novoPront)
        auditLogger.registrar(
            AuditLogger.Tipo.EDIT, "Paciente duplicado na edição (antigo mantido)",
            mapOf("nome_antigo" to nomeOriginal, "nome_novo" to novoNome,
                  "mudancas" to mudancas.joinToString("; ")))
        val trat = com.radioterapia.ai.treatment.TreatmentListManager(this)
        if (trat.estaEmTratamento(nomeOriginal)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.edit_treat_q_title)
                .setMessage(getString(R.string.edit_treat_q_msg, nomeOriginal))
                .setCancelable(false)
                .setPositiveButton(R.string.edit_treat_only_new) { _, _ ->
                    trat.remover(nomeOriginal); trat.alocar(novoNome)
                    Toast.makeText(this, R.string.edit_logged, Toast.LENGTH_SHORT).show(); concluirCom(novoNome)
                }
                .setNegativeButton(R.string.edit_treat_both) { _, _ ->
                    trat.alocar(novoNome)
                    Toast.makeText(this, R.string.edit_logged, Toast.LENGTH_SHORT).show(); concluirCom(novoNome)
                }
                .show()
        } else {
            Toast.makeText(this, R.string.edit_logged, Toast.LENGTH_SHORT).show(); concluirCom(novoNome)
        }
    }

    private fun aplicarEdicao(novoNome: String, novoNasc: String, novoPront: String, mudancas: List<String>) {
        // Prontuário ORIGINAL: o registro ainda está sob a chave antiga neste
        // ponto; quem faz a migração para a chave nova é editarPaciente(), logo
        // abaixo. Passar o novo aqui procuraria um cadastro que ainda não existe.
        patientCache.atualizarSexoMedico(dadosOriginais.nome, sexoEditado, medicoEditado,
            dadosOriginais.prontuario)
        try {
            patientCache.editarPaciente(
                nomeAntigo = nomeOriginal,
                novoNome = novoNome,
                novoNascimento = novoNasc,
                novoProntuario = novoPront
            )
            auditLogger.registrar(
                AuditLogger.Tipo.EDIT,
                "Paciente editado",
                mapOf(
                    "nome_antigo" to nomeOriginal,
                    "nome_novo" to novoNome,
                    "mudancas" to mudancas.joinToString("; ")
                )
            )
            Toast.makeText(this, R.string.edit_logged, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.err_save, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmarRemocao() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_from_history)
            .setMessage(getString(R.string.confirm_remove_patient_full, nomeOriginal))
            .setPositiveButton(R.string.remove_local_data) { _, _ -> removerTudoLocal() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Exclui TODOS os dados e fotos locais do paciente: a pasta em PHOTOS, o
     *  registro no cache e a lista de tratamento. O servidor externo NÃO é tocado
     *  (a próxima sincronização não deve retê-lo se ele foi apagado lá também). */
    private fun removerTudoLocal() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            // -1: nada foi excluído, porque sem prontuário não há como saber
            // quais pastas e registros são deste paciente e quais da homônima.
            val arquivos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // GUARDA: sem prontuário, pastasDoPaciente fica com a homônima de
                // nome exato, e remover() e removerPorNome() apagam toda chave do
                // nome. Na dúvida nada sai.
                if (semProntuarioComHomonimas()) return@withContext -1
                var removidos = 0
                try {
                    // A pasta sai do MESMO resolvedor que a leitura usa. Antes
                    // o caminho era montado aqui e, quando nao existia, havia um
                    // casamento so por nome: com duas homonimas, a exclusao
                    // apagava as fotos da paciente errada — e este e o caminho
                    // do app que nao tem volta. pastasDoPaciente devolve VAZIO
                    // na duvida, e vazio aqui significa "nao apaguei nada",
                    // que e o erro barato.
                    //
                    // Sao TODAS as pastas, nao uma. Hoje a reirradiacao divide
                    // a pasta do paciente, separada pela marca no nome do
                    // arquivo; mas layouts antigos guardavam cada reirradiacao
                    // numa pasta irma ("... NOVA SIMULACAO n"), que sobreviveria
                    // a uma exclusao que promete remover tudo.
                    val pastas = com.radioterapia.ai.util.StorageLocal.pastasDoPaciente(
                        this@EditarPacienteActivity, nomeOriginal, dadosOriginais.prontuario)
                    for (dir in pastas) {
                        if (!dir.exists()) continue
                        removidos += dir.walkBottomUp().count { it.isFile }
                        dir.deleteRecursively()
                    }
                } catch (_: Exception) {}
                patientCache.remover(nomeOriginal, dadosOriginais.prontuario)
                // Chaves legadas/órfãs. GUARDA: removerPorNome apaga TODA chave
                // do nome; com homônima de outro prontuário ainda no cadastro, o
                // registro dela iria junto (nascimento, sexo, médico).
                if (patientCache.prontuariosDoNome(nomeOriginal).isEmpty())
                    patientCache.removerPorNome(nomeOriginal)
                com.radioterapia.ai.treatment.TreatmentListManager(this@EditarPacienteActivity)
                    .remover(nomeOriginal)
                removidos
            }
            if (arquivos < 0) {
                AlertDialog.Builder(this@EditarPacienteActivity)
                    .setTitle(R.string.warning)
                    .setMessage(R.string.edit_remove_incerto)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            auditLogger.registrar(
                AuditLogger.Tipo.EDIT, "Paciente excluído (dados e fotos locais)",
                mapOf("nome" to nomeOriginal, "arquivos" to arquivos))
            AlertDialog.Builder(this@EditarPacienteActivity)
                .setTitle(R.string.patient_removed)
                .setMessage(R.string.remove_local_done_server_note)
                .setPositiveButton(R.string.ok) { _, _ -> finish() }
                .show()
        }
    }

    /**
     * Este paciente não tem prontuário, e há como ele ser outro: pastas do
     * nome que são de mais de um paciente, ou outro registro do nome com
     * prontuário. Sem prontuário nada desempata. Só disco e cadastro: fora da
     * thread principal.
     */
    private fun semProntuarioComHomonimas(): Boolean {
        if (dadosOriginais.prontuario.isNotBlank()) return false
        val nomes = com.radioterapia.ai.util.StorageLocal.photos(this)
            .listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        return com.radioterapia.ai.treatment.TreatmentPhotoFetcher.decidirPasta(
            nomes, nomeOriginal, "", "",
            patientCache.prontuariosDoNome(nomeOriginal).isNotEmpty()) is
            com.radioterapia.ai.treatment.TreatmentPhotoFetcher.DecisaoPasta.Incerta
    }

    /**
     * A pasta achada pelo resolvedor é a deste paciente? É, quando tem o nome
     * exato da pasta dele, ou quando a regra de escolha a aceita
     * ([com.radioterapia.ai.treatment.TreatmentPhotoFetcher.pastaDeRegistrosServe]).
     */
    private fun pastaServeAEstePaciente(pasta: java.io.File, nomeExato: String): Boolean {
        val pront = dadosOriginais.prontuario
        val nomes = com.radioterapia.ai.util.StorageLocal.photos(this)
            .listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        return com.radioterapia.ai.treatment.TreatmentPhotoFetcher.pastaDeRegistrosServe(
            pasta.name, nomeExato, nomes, nomeOriginal, pront,
            pront.isBlank() && patientCache.prontuariosDoNome(nomeOriginal).isNotEmpty())
    }

    companion object {
        const val EXTRA_NOME = "extra_nome"
        /** Prontuário do paciente aberto; vazio, o registro é procurado só pelo nome. */
        const val EXTRA_PRONTUARIO = "extra_prontuario"
        const val EXTRA_NOME_FINAL = "nome_final"
    }
}
