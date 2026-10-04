package com.radioterapia.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela inicial (Home).
 *
 * - mostrarEngrenagemAoInvesDeVoltar() = true: a toolbar da BaseActivity coloca a
 *   engrenagem (maior, 28dp) à direita em vez do botão voltar.
 * - tituloPadrao() = null: o título não vai na toolbar; o destaque visual é o
 *   PhotoIdHeaderView grande no corpo da tela.
 */
class HomeActivity : BaseActivity() {

    override fun mostrarEngrenagemAoInvesDeVoltar(): Boolean = true
    override fun tituloPadrao(): String? = null

    private var syncJaRodou = false

    /**
     * MELHORIA: aviso PREVENTIVO de armazenamento. O bloqueio no finalizar já
     * existe, mas avisar só ali é tarde — o técnico já está com o paciente na
     * mesa. Aqui o alerta aparece na abertura, com folga para liberar espaço.
     *
     * A leitura de espaço livre é I/O e saiu da thread principal; o diálogo
     * continua na Main: View tocada em Dispatchers.IO lanca.
     */
    private fun avisarEspacoBaixo() {
        CoroutineScope(Dispatchers.Main).launch {
            val livre = withContext(Dispatchers.IO) {
                try { com.radioterapia.ai.util.StorageLocal.espacoLivreMb(this@HomeActivity) }
                catch (_: Exception) { Long.MAX_VALUE }
            }
            if (livre >= 500 || isFinishing || isDestroyed) return@launch
            try {
                android.app.AlertDialog.Builder(this@HomeActivity)
                    .setTitle(R.string.disk_low_title)
                    .setMessage(getString(R.string.disk_low_msg, livre))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            } catch (_: Exception) { }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        avisarEspacoBaixo()
        // GATILHO DE ABERTURA. O tablet da sala passa a noite desligado; sem
        // isto, a primeira varredura do dia esperaria o periodo configurado.
        // `aoAbrir` tambem reprograma o trabalho periodico, que e o que faz uma
        // mudanca de intervalo nas Configuracoes valer sem reinstalar o app.
        // Com o interruptor mestre desligado, nao faz absolutamente nada.
        com.radioterapia.ai.sync.SyncWorker.aoAbrir(this)
        separarArquivadasRepetidas()

        findViewById<Button>(R.id.btnSimulation).setOnClickListener {
            startActivity(Intent(this, SimulationHomeActivity::class.java))
        }

        findViewById<Button>(R.id.btnTreatment).setOnClickListener {
            startActivity(Intent(this, com.radioterapia.ai.treatment.TreatmentActivity::class.java))
        }

        // O id agora e o CONTAINER das duas linhas, nao um TextView.
        findViewById<View>(R.id.txtLearnMore).setOnClickListener {
            abrirSiteRadioterapia()
        }

        atualizarBaseDePacientes()
        conferirPosAtualizacao()
        // Instalador cancelado ou deixado pendente: enquanto esta versão segue
        // em uso, as contagens do marcador acompanham altas e exclusões, e a
        // versão nova não acusa perda que não houve. Registra uma vez por
        // processo; sem marcador, cada saída do app só lê preferências.
        com.radioterapia.ai.update.GerenciadorAtualizacao.vigiarInstalacaoPendente(application)
        procurarVersaoNova()
    }

    /**
     * Separa as fotos arquivadas que aparecem em mais de uma pasta de paciente.
     *
     * Repete a cada abertura até uma passada limpa, quando a própria rotina
     * guarda a marca, e volta a rodar depois de uma importação que trouxe fotos.
     * Só em IO: ela percorre o acervo inteiro, e na thread principal a Home congelaria na
     * primeira abertura. Nada é apagado — as fotos vão para a quarentena — e o
     * resultado vai só para o registro de auditoria, sem diálogo: não há decisão
     * a pedir a quem está abrindo o app com um paciente esperando.
     */
    private fun separarArquivadasRepetidas() {
        // Uma vez por processo: girar o tablet recria a Home, e duas varreduras
        // do acervo ao mesmo tempo disputariam os mesmos arquivos.
        if (!quarentenaDisparada.compareAndSet(false, true)) return
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = com.radioterapia.ai.util.QuarentenaArquivadas.executarSeNecessario(app)
                if (r != null && r.movidos > 0) {
                    com.radioterapia.ai.audit.AuditLogger(app).registrar(
                        com.radioterapia.ai.audit.AuditLogger.Tipo.INFO,
                        "Fotos arquivadas repetidas entre pacientes movidas para quarentena",
                        mapOf(
                            "examinados" to r.examinados,
                            "movidos" to r.movidos,
                            "pastas" to r.pastas
                        )
                    )
                }
            } catch (_: Exception) { /* silencioso: nunca atrapalha a abertura */ }
        }
    }

    /**
     * Na primeira abertura depois de uma atualização, confirma a versão nova e
     * que os dados chegaram.
     *
     * Tudo chegou: um aviso curto, que não pede toque. Algo diminuiu: um diálogo
     * que diz o quê, quanto, e onde está a cópia gravada antes de atualizar. Ele
     * só informa — restaurar por cima do que existe é decisão de quem conhece o
     * aparelho, e não do app.
     *
     * A leitura é em IO; o aviso, na thread principal, e só numa Home que esteja
     * na frente: View tocada em Dispatchers.IO lança, e diálogo sobre Activity
     * destruída também.
     */
    private fun conferirPosAtualizacao() {
        // Uma vez por processo: duas leituras ao mesmo tempo veriam o mesmo
        // marcador antes de qualquer uma apagá-lo, e a confirmação sairia dupla.
        if (!conferenciaDisparada.compareAndSet(false, true)) return
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val r = withContext(Dispatchers.IO) {
                try { com.radioterapia.ai.update.GerenciadorAtualizacao.conferirPosAtualizacao(app) }
                catch (_: Exception) { null }
            } ?: return@launch
            // A leitura já apagou o marcador, então o resultado não pode morrer
            // com esta instância: se a Home foi recriada (o tablet girou) enquanto
            // a leitura corria, quem mostra é a que estiver na frente agora, ou a
            // próxima que voltar à frente. Tudo na thread principal, sem disputa.
            posAtualizacaoPendente = r
            homeNaFrente?.get()?.mostrarPosAtualizacaoPendente()
        }
    }

    override fun onResume() {
        super.onResume()
        homeNaFrente = java.lang.ref.WeakReference(this)
        mostrarPosAtualizacaoPendente()
    }

    override fun onPause() {
        if (homeNaFrente?.get() === this) homeNaFrente = null
        super.onPause()
    }

    private fun mostrarPosAtualizacaoPendente() {
        val r = posAtualizacaoPendente ?: return
        if (isFinishing || isDestroyed) return
        posAtualizacaoPendente = null

        if (r.encolhidos.isEmpty()) {
            android.widget.Toast.makeText(this,
                getString(R.string.update_done_fmt, r.versionName),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val linhas = r.encolhidos.entries.joinToString("\n") { (chave, par) ->
            getString(R.string.update_transplant_item_fmt,
                rotuloDaContagem(chave), par.first, par.second)
        }
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.group_update)
                .setMessage(getString(R.string.update_transplant_warn_fmt, linhas, r.pastaBackup))
                .setPositiveButton(R.string.ok, null)
                .show()
        } catch (_: Exception) { }
    }

    /** O nome que a tela de Transferência já usa para cada item contado. */
    private fun rotuloDaContagem(chave: String): String = when (chave) {
        com.radioterapia.ai.update.GerenciadorAtualizacao.CONT_PACIENTES ->
            getString(R.string.tr_pacientes)
        com.radioterapia.ai.update.GerenciadorAtualizacao.CONT_PROTOCOLOS ->
            getString(R.string.tr_protocolos)
        com.radioterapia.ai.update.GerenciadorAtualizacao.CONT_RUBRICARIO ->
            getString(R.string.tr_rubricario)
        com.radioterapia.ai.update.GerenciadorAtualizacao.CONT_TRATAMENTO ->
            getString(R.string.tr_tratamento)
        else -> chave
    }

    /**
     * A procura de versao nova, no arranque.
     *
     * TUDO AQUI E CALADO. Sem internet nao ha aviso, nao ha toast e nao ha log
     * na tela: o tablet da sala roda a maior parte do tempo em intranete, e nao
     * ter saida para a internet e o estado NORMAL, nao uma falha. Avisar sobre
     * isso treinaria a equipe a ignorar avisos, que e o pior resultado possivel
     * para uma funcao de seguranca.
     *
     * O respeito ao interruptor e ao intervalo mora em checarEGravar, e nao
     * aqui: assim a Home nao precisa saber a regra, e a regra nao se repete em
     * cada chamador.
     */
    private fun procurarVersaoNova() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.radioterapia.ai.update.GerenciadorAtualizacao.checarEGravar(this@HomeActivity)
            }
            // De volta na thread principal: a barra ja esta desenhada, entao e
            // aqui que o ponto acende.
            pintarPontoNovidade()
        }
    }

    /**
     * Atualiza a base de pacientes a partir do CSV da pasta de rede, quando a
     * clínica configurou uma. Sem pasta configurada, não faz nada.
     *
     * É a ÚNICA coisa que escreve na base consultada pelo `PatientLookup` — o
     * que preenche nome, nascimento e prontuário sozinho quando o técnico lê o
     * código de barras ou a etiqueta na identificação. Sem isto, a leitura
     * encontra a base vazia e o técnico digita tudo à mão.
     *
     * Tudo, inclusive a leitura da configuração, roda fora da thread principal:
     * antes o `AppConfig` e o `CsvMapping` eram construídos no `onCreate`, e
     * cada um é um carregamento síncrono de SharedPreferences no caminho de
     * abertura da tela.
     */
    private fun atualizarBaseDePacientes() {
        if (syncJaRodou) return
        syncJaRodou = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = AppConfig(this@HomeActivity)
                val mapping = com.radioterapia.ai.csv.CsvMapping(this@HomeActivity)
                if (config.csvPastaUnc.isBlank() || !mapping.valido()) return@launch

                val res = com.radioterapia.ai.csv.CsvSyncManager(this@HomeActivity).sincronizar()
                com.radioterapia.ai.audit.AuditLogger(this@HomeActivity).registrar(
                    if (res.sucesso) com.radioterapia.ai.audit.AuditLogger.Tipo.SYNC_CSV
                    else com.radioterapia.ai.audit.AuditLogger.Tipo.ERROR,
                    "Atualização da base ao abrir o app",
                    mapOf(
                        "sucesso" to res.sucesso,
                        "carregados" to res.pacientesCarregados,
                        "msg" to (res.mensagem ?: "")
                    )
                )
            } catch (_: Exception) { /* silencioso: nunca atrapalha a abertura */ }
        }
    }

    private fun abrirSiteRadioterapia() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(com.radioterapia.ai.branding.Marca.SITE))
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, getString(R.string.hc_no_browser),
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** A separação das arquivadas já foi disparada neste processo. */
        val quarentenaDisparada = java.util.concurrent.atomic.AtomicBoolean(false)

        /** A conferência pós-atualização já foi disparada neste processo. */
        val conferenciaDisparada = java.util.concurrent.atomic.AtomicBoolean(false)

        /** O que a conferência achou e ainda não foi mostrado. Só a thread
         *  principal lê e grava. */
        var posAtualizacaoPendente: com.radioterapia.ai.update.GerenciadorAtualizacao.PosAtualizacao? = null

        /** A Home que está na frente, sem segurá-la viva depois de destruída. */
        var homeNaFrente: java.lang.ref.WeakReference<HomeActivity>? = null
    }
}
