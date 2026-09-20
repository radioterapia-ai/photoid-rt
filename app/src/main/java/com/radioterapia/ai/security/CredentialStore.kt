package com.radioterapia.ai.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * O único lugar onde senha de rede é guardada.
 *
 * `EncryptedSharedPreferences` com `MasterKey` no Android Keystore: a chave de
 * cifragem não é exportável e não sai do aparelho — nem por backup, nem por
 * `adb`, nem pelo pacote de transferência de configuração.
 *
 * **Senha nunca entra no pacote de transferência.** O `PacoteConfig` copia
 * chaves do `SharedPreferences` comum; estas ficam noutro arquivo e noutro
 * cofre, de propósito. Levar a configuração de um tablet para outro leva o
 * endereço do servidor e o nome de usuário, e para na senha — que é digitada
 * uma vez em cada aparelho. É um passo a mais, e é o passo certo: um `.zip` de
 * configuração viaja por e-mail e por pen-drive.
 */
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

    // ---- Senha do SMB da configuração antiga (base CSV e fotos do servidor).
    // Continua com chave própria: mexer nela renomearia o segredo de quem já
    // usa o app, e a senha some sem aviso porque ninguém lê a chave antiga.

    fun salvarSenha(senha: String) {
        prefs.edit().putString(KEY_SENHA, senha).apply()
    }

    fun obterSenha(): String = prefs.getString(KEY_SENHA, "") ?: ""

    fun limparSenha() {
        prefs.edit().remove(KEY_SENHA).apply()
    }

    // ---- Senhas dos perfis de sincronização, uma por perfil.

    /**
     * Guarda a senha de um perfil. Chave vazia é ignorada em silêncio: o perfil
     * ainda não tem id, e gravar sob chave vazia colocaria a senha de todos eles
     * no mesmo lugar.
     */
    fun salvarSenhaPerfil(idPerfil: String, senha: String) {
        if (idPerfil.isBlank()) return
        prefs.edit().putString(PREFIXO_PERFIL + idPerfil, senha).apply()
    }

    fun obterSenhaPerfil(idPerfil: String): String =
        if (idPerfil.isBlank()) "" else prefs.getString(PREFIXO_PERFIL + idPerfil, "") ?: ""

    fun limparSenhaPerfil(idPerfil: String) {
        if (idPerfil.isBlank()) return
        prefs.edit().remove(PREFIXO_PERFIL + idPerfil).apply()
    }

    /**
     * Remove as senhas de perfis que não existem mais.
     *
     * Apagar um perfil na tela apaga o registro dele, e sem esta varredura a
     * senha continuaria no cofre para sempre — invisível, sem dono e sem
     * caminho de remoção pela interface.
     */
    fun podarSenhasOrfas(idsVivos: Set<String>) {
        val mortas = prefs.all.keys
            .filter { it.startsWith(PREFIXO_PERFIL) }
            .filter { it.removePrefix(PREFIXO_PERFIL) !in idsVivos }
        if (mortas.isEmpty()) return
        prefs.edit().apply { mortas.forEach { remove(it) } }.apply()
    }

    companion object {
        private const val KEY_SENHA = "smb_senha"
        private const val PREFIXO_PERFIL = "sync_senha_"
    }
}
