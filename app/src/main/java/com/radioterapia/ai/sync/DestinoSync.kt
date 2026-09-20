package com.radioterapia.ai.sync

import java.io.File

/**
 * O contrato de um destino, qualquer que seja o protocolo.
 *
 * Quatro métodos, e nenhum deles apaga nada. Isso não é omissão: é o contrato.
 * Ver [DestinoSync] em `MotorSync` — a sincronização é de **uma via**, e o
 * adaptador nem tem como remover ou renomear no remoto, por mais que um dia
 * alguém ache que seria útil.
 *
 * POR QUE NÃO UMA CLASSE POR PROTOCOLO COM HERANÇA
 * Os quatro protocolos não compartilham nada além destes quatro métodos: o SMB
 * autentica por sessão, o WebDAV por cabeçalho em cada requisição, o SFTP por
 * canal, o SAF nem autentica. Uma base comum acabaria sendo um lugar onde só
 * cabe o que é trivial, com cada filha sobrescrevendo o resto.
 */
interface DestinoSync {

    /**
     * Abre a conexão e confirma que dá para escrever no destino, narrando tudo
     * no [log]. Devolve falso sem lançar: quem chama quer o log, não a exceção.
     */
    fun testar(log: LogConexao): Boolean

    /**
     * Envia um arquivo para `caminhoRelativo/nomeRemoto`, criando o que
     * precisar no caminho.
     *
     * O [log] é opcional porque a sincronização de rotina roda milhares de
     * vezes e não deve construir texto que ninguém vai ler; o teste de conexão
     * e o envio manual passam um log, a rotina não.
     */
    fun enviar(local: File, caminhoRelativo: String, nomeRemoto: String,
               log: LogConexao? = null): Resultado

    /** Tamanho do arquivo remoto, ou -1 se não existe ou não dá para saber. */
    fun tamanhoRemoto(caminhoRelativo: String, nomeRemoto: String): Long

    /** Encerra sessões e sockets. Chamado sempre, inclusive depois de falha. */
    fun fechar()

    /**
     * O que aconteceu com um envio.
     *
     * [permanente] separa "não adianta tentar de novo" de "tente mais tarde", e
     * essa distinção é o que impede a fila de girar para sempre: credencial
     * recusada e caminho inexistente não melhoram com repetição, enquanto
     * tablet fora da rede melhora sozinho quando ele volta para o Wi-Fi.
     */
    data class Resultado(
        val sucesso: Boolean,
        val erro: String = "",
        val permanente: Boolean = false,
    ) {
        companion object {
            val OK = Resultado(true)
            fun falha(msg: String) = Resultado(false, msg, permanente = false)
            fun recusado(msg: String) = Resultado(false, msg, permanente = true)
        }
    }
}
