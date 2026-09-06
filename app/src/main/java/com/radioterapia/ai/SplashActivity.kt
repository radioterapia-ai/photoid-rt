package com.radioterapia.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.radioterapia.ai.consent.ConsentActivity
import com.radioterapia.ai.i18n.LocaleManager
import com.radioterapia.ai.wizard.WizardActivity

/**
 * Roteador de entrada. Decide para onde ir e sai do caminho:
 *   1) Se o usuário ainda NÃO aceitou os termos → ConsentActivity (item 11)
 *   2) Se aceitou mas o wizard ainda não rodou  → WizardActivity
 *   3) Caso contrário                            → HomeActivity
 *
 * NÃO espera e NÃO infla layout. Havia aqui um `postDelayed` de 1500 ms com o
 * logo em tela cheia: 1,5 s de espera artificial em TODA abertura do app, numa
 * rotina em que o técnico abre e fecha o app com o paciente já na mesa. O
 * `setContentView` foi junto, porque com o roteamento imediato a tela nunca
 * chega a ser desenhada — inflar o layout e decodificar o PNG de 1,9 MB seria
 * trabalho que ninguém vê. O fundo do tema (bg_main) cobre a transição.
 *
 * Não usa BaseActivity (precisa de tela cheia sem toolbar e tema próprio).
 */
class SplashActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val proxima = when {
            !ConsentActivity.jaAceitou(this) -> ConsentActivity::class.java
            !WizardActivity.jaConcluido(this) -> WizardActivity::class.java
            else -> HomeActivity::class.java
        }
        startActivity(Intent(this, proxima))
        overridePendingTransition(0, 0)
        finish()
    }
}
