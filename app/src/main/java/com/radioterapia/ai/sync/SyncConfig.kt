package com.radioterapia.ai.sync

import android.content.Context

/**
 * O interruptor mestre da sincronização e a cadência dela.
 *
 * **DESLIGADO POR PADRÃO, e isso não é conservadorismo.** Com a sincronização
 * desativada o app se comporta exatamente como sempre se comportou: nenhum
 * serviço em segundo plano, nenhuma tentativa de rede, nenhum dado saindo por
 * conta própria. Quem já usa FolderSync não é obrigado a migrar por ter
 * atualizado o app — e um serviço que atualizasse e descobrisse depois que o
 * tablet passou a enviar foto de paciente para algum lugar teria toda razão de
 * desinstalar.
 *
 * Mora no mesmo `SharedPreferences` do [com.radioterapia.ai.AppConfig] de
 * propósito: é assim que o pacote de transferência de configuração consegue
 * levar estas chaves junto com as demais. A senha de cada perfil NÃO vem junto
 * — ver [com.radioterapia.ai.security.CredentialStore].
 */
class SyncConfig(context: Context) {

    private val prefs = context.getSharedPreferences("config_radioterapia", Context.MODE_PRIVATE)

    /** O interruptor mestre. Falso = o app roda como antes do motor existir. */
    var ativo: Boolean
        get() = prefs.getBoolean(KEY_ATIVO, false)
        set(v) = prefs.edit().putBoolean(KEY_ATIVO, v).apply()

    /**
     * Intervalo da varredura periódica, em minutos.
     *
     * O piso é 15 porque é o mínimo que o `WorkManager` aceita para trabalho
     * periódico; pedir menos não dá erro, o Android simplesmente arredonda para
     * cima e a configuração passa a mentir sobre o que faz.
     */
    var intervaloMinutos: Int
        get() = prefs.getInt(KEY_INTERVALO, 60).coerceAtLeast(15)
        set(v) = prefs.edit().putInt(KEY_INTERVALO, v.coerceIn(15, 24 * 60)).apply()

    /** Enviar assim que uma foto é salva. */
    var gatilhoAoSalvarFoto: Boolean
        get() = prefs.getBoolean(KEY_GAT_FOTO, true)
        set(v) = prefs.edit().putBoolean(KEY_GAT_FOTO, v).apply()

    /** Enviar ao finalizar a simulação — o momento em que a ficha fica pronta. */
    var gatilhoAoFinalizar: Boolean
        get() = prefs.getBoolean(KEY_GAT_FIM, true)
        set(v) = prefs.edit().putBoolean(KEY_GAT_FIM, v).apply()

    /** Enviar ao abrir o app, para o tablet que passou a noite desligado. */
    var gatilhoAoAbrir: Boolean
        get() = prefs.getBoolean(KEY_GAT_ABRIR, true)
        set(v) = prefs.edit().putBoolean(KEY_GAT_ABRIR, v).apply()

    /**
     * Só sincronizar em rede não tarifada.
     *
     * Ligado por padrão: tablet de sala costuma ficar no Wi-Fi da clínica, e o
     * que sobe são fotos — acervo inteiro por dados móveis é conta que ninguém
     * esperava. Quem tem plano corporativo desliga.
     */
    var somenteRedeNaoTarifada: Boolean
        get() = prefs.getBoolean(KEY_SO_WIFI, true)
        set(v) = prefs.edit().putBoolean(KEY_SO_WIFI, v).apply()

    /** Última varredura concluída, para a tela dizer quando foi. */
    var ultimaVarredura: Long
        get() = prefs.getLong(KEY_ULTIMA, 0L)
        set(v) = prefs.edit().putLong(KEY_ULTIMA, v).apply()

    companion object {
        const val KEY_ATIVO = "sync_ativo"
        const val KEY_INTERVALO = "sync_intervalo_min"
        const val KEY_GAT_FOTO = "sync_gat_foto"
        const val KEY_GAT_FIM = "sync_gat_finalizar"
        const val KEY_GAT_ABRIR = "sync_gat_abrir"
        const val KEY_SO_WIFI = "sync_so_wifi"
        const val KEY_ULTIMA = "sync_ultima_varredura"

        /** As chaves que o pacote de transferência leva. Senha não está aqui. */
        val CHAVES_TRANSFERIVEIS = listOf(
            KEY_ATIVO, KEY_INTERVALO, KEY_GAT_FOTO, KEY_GAT_FIM,
            KEY_GAT_ABRIR, KEY_SO_WIFI
        )
    }
}
