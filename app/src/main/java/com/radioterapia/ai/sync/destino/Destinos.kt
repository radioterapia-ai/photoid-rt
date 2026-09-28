package com.radioterapia.ai.sync.destino

import android.content.Context
import com.radioterapia.ai.sync.DestinoSync
import com.radioterapia.ai.sync.PerfilSync

/**
 * De um perfil para o adaptador que sabe falar com ele.
 *
 * O único lugar do app que conhece os cinco tipos ao mesmo tempo. Tudo o mais —
 * o motor, a tela, o teste de conexão — trabalha contra a interface
 * [DestinoSync] e não sabe qual protocolo está do outro lado. É o que faz um
 * protocolo novo ser um arquivo novo mais uma linha aqui.
 */
object Destinos {

    fun criar(context: Context, perfil: PerfilSync, senha: String): DestinoSync =
        when (perfil.tipo) {
            PerfilSync.Tipo.SMB -> DestinoSmb(perfil, senha)
            PerfilSync.Tipo.WEBDAV -> DestinoWebDav(perfil, senha)
            PerfilSync.Tipo.FTP -> DestinoFtp(perfil, senha)
            PerfilSync.Tipo.SFTP -> DestinoSftp(perfil, senha)
            PerfilSync.Tipo.SAF -> DestinoSaf(context, perfil)
        }
}
