package com.radioterapia.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.radioterapia.ai.consent.ConsentActivity
import com.radioterapia.ai.i18n.LocaleManager
import com.radioterapia.ai.ui.AberturaSobreposta
import com.radioterapia.ai.wizard.WizardActivity

/**
 * Roteador de entrada. Decide para onde ir e sai do caminho:
 *   1) Se o usuário ainda NÃO aceitou os termos → ConsentActivity
 *   2) Se aceitou mas o wizard ainda não rodou  → WizardActivity
 *   3) Caso contrário                            → HomeActivity
 *
 * NÃO espera e NÃO desenha. A assinatura da abertura é desenhada pela própria
 * tela de destino, por cima do primeiro quadro dela ([AberturaSobreposta]):
 * assim a tela de destino monta enquanto a assinatura se desenha, em vez de
 * depois dela. Esta Activity só pede a abertura no Intent.
 *
 * Um atraso fixo com o logo em tela cheia aqui custaria a espera inteira em
 * TODA abertura do app, numa rotina em que o técnico abre e fecha o app com o
 * paciente já na mesa. O fundo do tema (bg_main) cobre a transição.
 *
 * Não usa BaseActivity (precisa de tela sem toolbar e tema próprio).
 */
class SplashActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // O APP JÁ ESTÁ ABERTO POR BAIXO: o técnico saiu pelo botão Início e
        // voltou pelo ícone. O lançador cria esta Activity de novo sobre a
        // tarefa existente; rotear dali empilharia uma segunda tela inicial
        // sobre a primeira e repetiria a abertura. Sair já devolve a tela em
        // que ele estava.
        // Só com FLAG_ACTIVITY_NEW_TASK, que o lançador sempre põe: outro app
        // que abra esta Activity na tarefa DELE também a deixa fora da raiz, e
        // ali ela precisa rotear, senão o app não abre.
        if (!isTaskRoot && intent?.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            finish()
            return
        }

        val proxima = when {
            !ConsentActivity.jaAceitou(this) -> ConsentActivity::class.java
            !WizardActivity.jaConcluido(this) -> WizardActivity::class.java
            else -> HomeActivity::class.java
        }
        startActivity(AberturaSobreposta.pedir(Intent(this, proxima)))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }
}
