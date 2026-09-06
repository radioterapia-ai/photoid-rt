package com.radioterapia.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.radioterapia.ai.session.SessionManager
import com.radioterapia.ai.treatment.PatientViewerActivity
import com.radioterapia.ai.treatment.TreatmentActivity
import com.radioterapia.ai.ui.HistoricoActivity
import java.util.concurrent.TimeUnit

class SimulationHomeActivity : BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.simulation)

    private lateinit var sessionManager: SessionManager
    private lateinit var cardDraft: LinearLayout
    private lateinit var txtDraftInfo: TextView
    private lateinit var txtWifiStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simulation_home)
        com.radioterapia.ai.util.UiText.uniformizar(
            findViewById(R.id.btnContinueDraft), findViewById(R.id.btnDiscardDraft))

        sessionManager = SessionManager(this)
        cardDraft = findViewById(R.id.cardDraft)
        txtDraftInfo = findViewById(R.id.txtDraftInfo)
        txtWifiStatus = findViewById(R.id.txtWifiStatus)

        findViewById<Button>(R.id.btnStartPatient).setOnClickListener {
            iniciarNovoPaciente()
        }

        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            // Histórico UNIFICADO: mesma tela e fluxo do modo Check.
            // Abre a lista (miniaturas) em modo seleção; ao tocar, abre o
            // PatientViewerActivity (fotos + carrossel + cabeçalho + botões
            // "Adicionar fotos" / "Resimular paciente"). Edição só nas Configurações.
            val intent = Intent(this, HistoricoActivity::class.java)
            intent.putExtra(TreatmentActivity.EXTRA_MODO_SELECAO, true)
            abrirHistorico.launch(intent)
        }

        findViewById<Button>(R.id.btnContinueDraft).setOnClickListener {
            continuarRascunho()
        }

        findViewById<Button>(R.id.btnDiscardDraft).setOnClickListener {
            confirmarDescartarRascunho()
        }
    }

    override fun onResume() {
        super.onResume()
        // Recarrega do disco: o rascunho pode ter sido criado/alterado na tela de
        // captura enquanto esta Activity estava no back stack.
        sessionManager.recarregar()
        atualizarBannerDeRascunho()
        atualizarStatusWifi()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun atualizarBannerDeRascunho() {
        if (sessionManager.temRascunho()) {
            val nome = sessionManager.nomePaciente.ifBlank { "(sem nome)" }
            val qtd = sessionManager.quantidade()
            val inicio = sessionManager.timestampInicio
            val dias = if (inicio > 0) TimeUnit.MILLISECONDS.toDays(
                System.currentTimeMillis() - inicio
            ).toInt() else 0
            txtDraftInfo.text = getString(R.string.draft_info, nome, qtd, dias)
            cardDraft.visibility = View.VISIBLE
        } else {
            cardDraft.visibility = View.GONE
        }
    }

    /**
     * Status de Wi-Fi sem falso alarme.
     *
     * A versão anterior lia `WifiManager.connectionInfo` e mostrava
     * "Sem Wi-Fi" quando o SSID vinha vazio ou como "<unknown ssid>". A partir
     * do Android 8.1 o SSID só é revelado a quem tem ACCESS_FINE_LOCATION, e o
     * app não pede essa permissão (nem deveria: não precisa saber onde o tablet
     * está, só se há rede). O resultado é que `networkId` voltava -1 e o SSID
     * vinha "unknown" **mesmo conectado** — a tela de Simulação exibia
     * permanentemente "⚠ Sem Wi-Fi" nos tablets do campo, bem ao lado do botão
     * que depende da rede para imprimir.
     *
     * A conectividade em si não exige permissão nenhuma além de
     * ACCESS_NETWORK_STATE, que já está no manifest. O nome da rede é um extra:
     * quando o sistema o entrega, mostramos; quando não, dizemos apenas que há
     * Wi-Fi. Melhor um rótulo genérico verdadeiro que um alarme falso.
     */
    private fun atualizarStatusWifi() {
        val cm = applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val temWifi = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (_: Exception) { false }

        if (!temWifi) {
            txtWifiStatus.text = getString(R.string.wifi_disconnected)
            return
        }
        val ssid = nomeDaRede()
        txtWifiStatus.text = if (ssid.isBlank()) getString(R.string.wifi_connected_generic)
                             else getString(R.string.wifi_connected, ssid)
    }

    /** SSID quando o sistema permite lê-lo; "" quando não. Nunca é motivo para
     *  dizer que não há Wi-Fi. */
    private fun nomeDaRede(): String = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val s = wm.connectionInfo?.ssid?.removeSurrounding("\"").orEmpty()
        if (s.isBlank() || s.contains("unknown", true) || s == "02:00:00:00:00:00") "" else s
    } catch (_: Exception) { "" }

    private fun iniciarNovoPaciente() {
        // Em E1 abrimos a MainActivity (stub). Em E2 vai pra MainActivity completa.
        if (sessionManager.temRascunho()) {
            // Descartar (destrutivo) em vermelho, Continuar (seguro) em verde.
            mostrarDialogPintado(
                AlertDialog.Builder(this)
                    .setTitle(R.string.draft_in_progress)
                    .setMessage(R.string.confirm_discard_draft)
                    .setPositiveButton(R.string.discard_draft) { _, _ ->
                        sessionManager.limparSessao()
                        abrirCaptura(novaSimulacao = true)
                    }
                    .setNegativeButton(R.string.continue_draft) { _, _ ->
                        abrirCaptura(novaSimulacao = false)
                    },
                destrutivo = android.content.DialogInterface.BUTTON_POSITIVE,
                seguro = android.content.DialogInterface.BUTTON_NEGATIVE)
            return
        }
        abrirCaptura(novaSimulacao = true)
    }

    private fun continuarRascunho() {
        abrirCaptura(novaSimulacao = false)
    }

    private fun abrirCaptura(novaSimulacao: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_NOVA_SIMULACAO, novaSimulacao)
        }
        startActivity(intent)
    }

    private fun confirmarDescartarRascunho() {
        mostrarDialogPintado(
            AlertDialog.Builder(this)
                .setTitle(R.string.discard_draft)
                .setMessage(R.string.confirm_discard_draft)
                .setPositiveButton(R.string.discard) { _, _ ->
                    sessionManager.limparSessao()
                    atualizarBannerDeRascunho()
                    Toast.makeText(this, R.string.success, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null),
            destrutivo = android.content.DialogInterface.BUTTON_POSITIVE,
            seguro = android.content.DialogInterface.BUTTON_NEGATIVE)
    }

    /**
     * Resultado da seleção no Histórico (modo Sim). Abre o PatientViewerActivity
     * do paciente escolhido — mesma tela do modo Check, mostrando fotos, carrossel,
     * cabeçalho com identificação e os botões "Adicionar fotos" / "Resimular".
     *
     * Usa `registerForActivityResult` em vez do par
     * `startActivityForResult`/`onActivityResult`, obsoleto desde a AndroidX
     * Activity 1.2. Além de tirar o `requestCode` mágico do caminho, o contrato
     * registrado sobrevive à recriação da Activity — com a API antiga, um
     * resultado que chegasse depois de o sistema recriar a tela caía num
     * `onActivityResult` de uma instância que já não existia.
     */
    private val abrirHistorico = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult
        val nome = data.getStringExtra(TreatmentActivity.EXTRA_NOME_SELECIONADO)
            ?: return@registerForActivityResult
        val pront = data.getStringExtra(TreatmentActivity.EXTRA_PRONT_SELECIONADO) ?: ""
        val intent = Intent(this, PatientViewerActivity::class.java)
        intent.putExtra(PatientViewerActivity.EXTRA_NOME, nome)
        intent.putExtra(PatientViewerActivity.EXTRA_PRONTUARIO, pront)
        startActivity(intent)
    }

    companion object {
        const val EXTRA_NOVA_SIMULACAO = "extra_nova_simulacao"
    }
}
