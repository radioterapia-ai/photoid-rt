package com.radioterapia.ai

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.radioterapia.ai.i18n.LocaleManager

/**
 * Application principal. Faz duas coisas globais:
 *  1) Força modo escuro fixo (paleta foi pensada para fundo escuro).
 *  2) Aplica o locale escolhido pelo usuário antes de qualquer Activity ser criada.
 */
class RadioterapiaApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        criarEstruturaLocal()
    }

    /**
     * Garante a estrutura PhotoID_RT/{PHOTOS,DATABASE} (na raiz se houver permissão,
     * senão na pasta interna do app), para salvar funcionar mesmo pulando o onboarding.
     *
     * Fora da thread principal: rodava em `Application.onCreate`, ou seja, antes de
     * qualquer tela aparecer, e custa três `mkdirs` mais três construções de
     * `AppConfig` — cada uma um carregamento síncrono de SharedPreferences.
     *
     * Adiar é seguro porque `StorageLocal.photos()` e `database()` já fazem
     * `mkdirs()` por conta própria a cada chamada: isto aqui é só aquecimento,
     * nunca a única garantia de que a pasta existe.
     */
    private fun criarEstruturaLocal() {
        Thread {
            com.radioterapia.ai.util.StorageLocal.garantirEstrutura(this)
        }.apply { isDaemon = true; name = "storage-init" }.start()
    }
}
