package com.radioterapia.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela inicial (Home) - item 1, 5, 7 da rodada UI v4.
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
     * continua na Main (footgun 5 — View tocada em Dispatchers.IO).
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

        findViewById<Button>(R.id.btnSimulation).setOnClickListener {
            startActivity(Intent(this, SimulationHomeActivity::class.java))
        }

        findViewById<Button>(R.id.btnTreatment).setOnClickListener {
            startActivity(Intent(this, com.radioterapia.ai.treatment.TreatmentActivity::class.java))
        }

        findViewById<TextView>(R.id.txtLearnMore).setOnClickListener {
            abrirSiteRadioterapia()
        }

        atualizarBaseDePacientes()
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
}
