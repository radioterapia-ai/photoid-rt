package com.radioterapia.ai.sync

import org.json.JSONObject

/**
 * UM DESTINO de sincronização, com tudo o que ele precisa para existir.
 *
 * POR QUE N PERFIS, E NÃO UM
 * Serviço com dois prédios manda a mesma pasta para dois servidores. Serviço que
 * está migrando de infraestrutura manda para o antigo e para o novo ao mesmo
 * tempo, até confiar no novo. Um destino só obrigaria a escolher — e a escolha
 * seria feita no dia da migração, que é o pior dia para ela.
 *
 * POR QUE A SENHA NÃO ESTÁ AQUI
 * Ela mora no [com.radioterapia.ai.security.CredentialStore], indexada por
 * [id]. Este objeto é serializado em JSON no `filesDir` e é o que o pacote de
 * transferência leva de um tablet para outro; senha dentro dele viajaria por
 * e-mail e por pen-drive junto com o resto da configuração.
 *
 * POR QUE OS CAMPOS DE TODOS OS TIPOS NUM OBJETO SÓ
 * Herança daria uma classe por protocolo e obrigaria a tela a saber qual
 * instanciar antes de o usuário escolher o tipo — e a trocar de instância, e
 * perder o que já foi digitado, a cada troca no seletor. Campo que o tipo não
 * usa fica vazio e não é lido. É a estrutura que sobrevive a trocar SMB por
 * WebDAV sem redigitar o host.
 */
data class PerfilSync(
    val id: String,
    val nome: String,
    val tipo: Tipo = Tipo.SMB,
    val ativo: Boolean = true,

    // ---- comum a SMB / WebDAV / FTP / SFTP
    val host: String = "",
    /** 0 = porta padrão do protocolo. Ver [portaEfetiva]. */
    val porta: Int = 0,
    val usuario: String = "",

    // ---- SMB
    /** "SMB1" (legado), "SMB2" ou "SMB3" (cifrado). */
    val protocolo: String = "SMB2",
    /** Nome do share, isolado do caminho. Aceita `$` (share administrativo). */
    val share: String = "",
    /** Domínio NTLM, em campo próprio: o usuário não digita `DOMINIO\usuario`. */
    val dominio: String = "",
    /**
     * Pular a verificação de existência das pastas superiores antes de gravar.
     *
     * Em hospital é comum a conta do tablet ter permissão de escrita na pasta
     * final e NENHUMA leitura nas pastas acima dela. Listar a raiz para
     * "conferir se existe" falha com acesso negado numa pasta que não é a de
     * destino — e o erro aponta a pasta errada.
     */
    val injecaoDireta: Boolean = true,

    // ---- WebDAV
    /** URL completa da pasta de destino, com esquema. Ex.: `https://nas/dav/rt/`. */
    val urlBase: String = "",

    // ---- SAF (qualquer nuvem já instalada no tablet)
    /** `content://` da árvore escolhida pelo seletor do sistema. */
    val safUri: String = "",

    // ---- destino, comum a todos
    /**
     * Pasta remota onde a estrutura local é reproduzida. Sempre relativa ao
     * share (SMB), à raiz do login (FTP/SFTP), à [urlBase] (WebDAV) ou à árvore
     * escolhida (SAF).
     */
    val caminhoRemoto: String = "",

    // ---- estado, não é configuração
    val ultimaSincronizacao: Long = 0L,
    val ultimoErro: String = "",
) {

    enum class Tipo { SMB, WEBDAV, FTP, SFTP, SAF }

    /** Porta padrão quando [porta] é 0. SAF e WebDAV não usam porta própria. */
    fun portaEfetiva(): Int = if (porta > 0) porta else when (tipo) {
        Tipo.SMB -> 445
        Tipo.FTP -> 21
        Tipo.SFTP -> 22
        Tipo.WEBDAV, Tipo.SAF -> 0
    }

    /**
     * O perfil tem o mínimo para tentar uma conexão.
     *
     * NÃO É VALIDAÇÃO DE FORMULÁRIO. Só responde se vale a pena gastar uma
     * tentativa de rede; o que está errado de verdade quem diz é o servidor, no
     * teste de conexão, com a mensagem dele. Adivinhar aqui o que o servidor
     * aceita produziria recusa de configuração válida.
     */
    fun utilizavel(): Boolean = when (tipo) {
        Tipo.SMB -> host.isNotBlank() && share.isNotBlank()
        Tipo.WEBDAV -> urlBase.isNotBlank()
        Tipo.FTP, Tipo.SFTP -> host.isNotBlank()
        Tipo.SAF -> safUri.isNotBlank()
    }

    fun paraJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("nome", nome)
        put("tipo", tipo.name)
        put("ativo", ativo)
        put("host", host)
        put("porta", porta)
        put("usuario", usuario)
        put("protocolo", protocolo)
        put("share", share)
        put("dominio", dominio)
        put("injecaoDireta", injecaoDireta)
        put("urlBase", urlBase)
        put("safUri", safUri)
        put("caminhoRemoto", caminhoRemoto)
        put("ultimaSincronizacao", ultimaSincronizacao)
        put("ultimoErro", ultimoErro)
    }

    companion object {
        fun deJson(o: JSONObject): PerfilSync = PerfilSync(
            id = o.optString("id"),
            nome = o.optString("nome"),
            tipo = try { Tipo.valueOf(o.optString("tipo", "SMB")) } catch (_: Exception) { Tipo.SMB },
            ativo = o.optBoolean("ativo", true),
            host = o.optString("host"),
            porta = o.optInt("porta", 0),
            usuario = o.optString("usuario"),
            protocolo = o.optString("protocolo", "SMB2"),
            share = o.optString("share"),
            dominio = o.optString("dominio"),
            injecaoDireta = o.optBoolean("injecaoDireta", true),
            urlBase = o.optString("urlBase"),
            safUri = o.optString("safUri"),
            caminhoRemoto = o.optString("caminhoRemoto"),
            ultimaSincronizacao = o.optLong("ultimaSincronizacao", 0L),
            ultimoErro = o.optString("ultimoErro"),
        )
    }
}
