package com.radioterapia.ai.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "radioterapia_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun salvarSenha(senha: String) {
        prefs.edit().putString(KEY_SENHA, senha).apply()
    }

    fun obterSenha(): String = prefs.getString(KEY_SENHA, "") ?: ""

    fun limparSenha() {
        prefs.edit().remove(KEY_SENHA).apply()
    }

    companion object {
        private const val KEY_SENHA = "smb_senha"
    }
}

