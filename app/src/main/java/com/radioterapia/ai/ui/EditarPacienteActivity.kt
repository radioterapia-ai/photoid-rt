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
    private lateinit var edtPront: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editar_paciente)
        supportActionBar?.title = getString(R.string.edit_patient)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        patientCache = PatientCache(this)
        auditLogger = AuditLogger(this)

        nomeOriginal = intent.getStringExtra(EXTRA_NOME) ?: ""
        val dados = patientCache.obterDadosPaciente(nomeOriginal)
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
        edtNasc.setText(dados.nascimento)
        edtPront.setText(dados.prontuario)
        com.radioterapia.ai.util.UiText.aplicarMascaraData(edtNasc)
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
        val novoNasc = edtNasc.text.toString().trim()
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

    private fun normalizarNome(nome: String): String {
        val s = java.text.Normalizer.normalize(nome, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return s.replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ").trim().uppercase(java.util.Locale.getDefault())
    }

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
            val prefA = normalizarNome(nomeOriginal).replace(" ", "_")
            val prefN = normalizarNome(novoNome).replace(" ", "_")

            // Pasta antiga: pelo padrão "NOME - PRONT"; fallback: varredura por chave.
            var dirAntigo = java.io.File(photos,
                com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(nomeOriginal, prontAntigo))
            if (!dirAntigo.exists()) {
                dirAntigo = photos.listFiles()?.firstOrNull { d ->
                    d.isDirectory && com.radioterapia.ai.util.StorageLocal.chaveNome(
                        d.name.substringBeforeLast(" - ")) ==
                        com.radioterapia.ai.util.StorageLocal.chaveNome(nomeOriginal)
                } ?: dirAntigo
            }
            val dirNovo = java.io.File(photos,
                com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(novoNome, novoPront))

            if (dirAntigo.exists()) {
                if (dirNovo.exists() && dirNovo.absolutePath != dirAntigo.absolutePath)
                    return "conflito"
                if (dirNovo.absolutePath != dirAntigo.absolutePath &&
                    !dirAntigo.renameTo(dirNovo)) return "falha ao renomear a pasta"
                // Prefixo do nome nos arquivos: PREF_ANTIGO_* -> PREF_NOVO_*
                dirNovo.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith(prefA + "_"))
                        f.renameTo(java.io.File(dirNovo, prefN + f.name.substring(prefA.length)))
                }
            }

            patientCache.migrarRegistro(nomeOriginal, novoNome, novoPront, novoNasc)

            val trat = com.radioterapia.ai.treatment.TreatmentListManager(this)
            if (trat.estaEmTratamento(nomeOriginal)) {
                trat.remover(nomeOriginal); trat.alocar(novoNome)
            }

            try { regenerarPdfPosMigracao(novoNome, novoNasc, novoPront, dirNovo, prefN, sexoEditado) }
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
                                                dir: java.io.File, prefN: String, sexo: String) {
        if (!dir.exists()) return
        val fetcher = com.radioterapia.ai.treatment.TreatmentPhotoFetcher(this)
        val sim = fetcher.buscarSimulacoes(nome)
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

        val tagSim = if (sim.numeroSimulacao > 1) "_NOVASIM" + (sim.numeroSimulacao - 1) else ""
        // Remove os PDFs antigos DESTA simulação (traziam o nome anterior)
        dir.listFiles()?.filter { f ->
            f.isFile && f.name.endsWith(".pdf", true) &&
                (if (sim.numeroSimulacao == 1) !f.name.contains("_NOVASIM")
                 else f.name.contains("_NOVASIM" + (sim.numeroSimulacao - 1)))
        }?.forEach { it.delete() }

        val config = com.radioterapia.ai.AppConfig(this)
        val ts = java.text.SimpleDateFormat("dd-MMM-yyyy_HH-mm-ss",
            java.util.Locale("pt", "BR")).format(java.util.Date()).uppercase()
        val saida = java.io.File(dir, prefN + "_FOLHA_SIMULACAO" + tagSim + "_" + ts + ".pdf")
        val dados = com.radioterapia.ai.pdf.PdfBuilder.DadosCabecalho(
            nomePaciente = nome,
            nascimento = nasc,
            prontuario = pront,
            idsExtras = emptyList(),
            dataSimulacao = java.util.Date(sim.timestampPrincipal),
            numeroSimulacao = sim.numeroSimulacao,
            nomeClinica = config.nomeClinica,
            sexo = sexo,
            medicoAssistente = patientCache.obterDadosPaciente(nome)?.medicoAssistente ?: ""
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
            usarEtiqueta = config.pdfUsarEtiqueta,
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
            val arquivos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var removidos = 0
                try {
                    val photos = com.radioterapia.ai.util.StorageLocal.photos(this@EditarPacienteActivity)
                    val prontAntigo = dadosOriginais.prontuario
                    var dir = java.io.File(photos,
                        com.radioterapia.ai.util.StorageLocal.nomePastaPaciente(nomeOriginal, prontAntigo))
                    if (!dir.exists()) {
                        dir = photos.listFiles()?.firstOrNull { d ->
                            d.isDirectory && com.radioterapia.ai.util.StorageLocal.chaveNome(
                                d.name.substringBeforeLast(" - ")) ==
                                com.radioterapia.ai.util.StorageLocal.chaveNome(nomeOriginal)
                        } ?: dir
                    }
                    if (dir.exists()) { removidos = dir.walkBottomUp().count { it.isFile }; dir.deleteRecursively() }
                } catch (_: Exception) {}
                patientCache.remover(nomeOriginal, dadosOriginais.prontuario)
                patientCache.removerPorNome(nomeOriginal)  // chaves legadas/órfãs
                com.radioterapia.ai.treatment.TreatmentListManager(this@EditarPacienteActivity)
                    .remover(nomeOriginal)
                removidos
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

    companion object {
        const val EXTRA_NOME = "extra_nome"
        const val EXTRA_NOME_FINAL = "nome_final"
    }
}
