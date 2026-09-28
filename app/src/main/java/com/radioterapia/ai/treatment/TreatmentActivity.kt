package com.radioterapia.ai.treatment

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import com.radioterapia.ai.R
import com.radioterapia.ai.ui.HistoricoActivity

/**
 * Tela de entrada do módulo Tratamento.
 *
 * Dois caminhos:
 *  - "Pacientes em Tratamento" (principal, azul): lista LOCAL de pacientes do
 *    aparelho deste tablet. Lá dentro: escanear etiqueta / ler código + busca + Alta.
 *  - "Histórico de Pacientes" (secundário, contorno): todos os pacientes; lá é
 *    possível buscar e ALOCAR um paciente para tratamento.
 *
 * A identificação por scan e a abertura do visualizador acontecem dentro da lista
 * (HistoricoActivity nos modos correspondentes).
 */
class TreatmentActivity : com.radioterapia.ai.BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.treatment)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_treatment)
        supportActionBar?.title = getString(R.string.treatment)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<Button>(R.id.btnEmTratamento).setOnClickListener {
            val intent = Intent(this, HistoricoActivity::class.java)
            intent.putExtra(HistoricoActivity.EXTRA_MODO_TRATAMENTO, true)
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnHistoricoTreat).setOnClickListener {
            val intent = Intent(this, HistoricoActivity::class.java)
            intent.putExtra(HistoricoActivity.EXTRA_MODO_HISTORICO_TRAT, true)
            startActivity(intent)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        // Usadas pelo HistoricoActivity (modo seleção) e SimulationHomeActivity.
        const val EXTRA_MODO_SELECAO = "modo_selecao"
        const val EXTRA_NOME_SELECIONADO = "nome_selecionado"
        const val EXTRA_PRONT_SELECIONADO = "prontuario_selecionado"
    }
}
