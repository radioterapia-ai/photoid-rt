package com.radioterapia.ai.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O relato do que a conexão tentou, passo a passo.
 *
 * POR QUE ISTO EXISTE
 * Quando o teste de conexão falha numa clínica, quem está com o tablet na mão é
 * o técnico de radioterapia, e quem pode resolver é o setor de TI do hospital —
 * que não está na sala e não vai ver a tela. "Falha ao conectar" não atravessa
 * essa distância. O que atravessa é um texto que o técnico copia e cola no
 * chamado, com a porta que foi tentada, o formato de usuário que o servidor
 * recusou e a mensagem que o servidor devolveu, palavra por palavra.
 *
 * REGRA DE OURO: SENHA NUNCA ENTRA AQUI. Este texto é feito para ser colado em
 * chamado, e-mail e grupo de mensagens. O que se registra é o comprimento dela
 * — que responde "digitei certo?" e "o campo está vazio?" sem revelar nada.
 */
class LogConexao {

    private val linhas = StringBuilder()
    private val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val inicio = System.currentTimeMillis()

    fun passo(texto: String) = registrar("·", texto)
    fun ok(texto: String) = registrar("OK", texto)
    fun falha(texto: String) = registrar("ERRO", texto)

    /**
     * Registra uma exceção com o **tipo** dela, e não só a mensagem.
     *
     * `SMBApiException` com "STATUS_LOGON_FAILURE" e `SocketTimeoutException`
     * com mensagem nula são problemas completamente diferentes — credencial
     * recusada contra pacote bloqueado no caminho —, e a mensagem sozinha não
     * distingue os dois. A de timeout costuma ser nula, aliás, e o log sairia
     * com "erro: null".
     */
    fun excecao(contexto: String, e: Throwable) {
        registrar("ERRO", "$contexto — ${e.javaClass.simpleName}: ${e.message ?: "(sem mensagem)"}")
        var causa = e.cause
        var nivel = 1
        while (causa != null && nivel <= 3) {
            registrar("  ↳", "causa ${nivel}: ${causa.javaClass.simpleName}: " +
                    (causa.message ?: "(sem mensagem)"))
            causa = causa.cause
            nivel++
        }
    }

    /** Anota o comprimento da senha. O valor nunca. */
    fun senha(valor: String) {
        registrar("·", if (valor.isEmpty()) "senha: VAZIA"
                       else "senha: ${valor.length} caracteres (não registrada)")
    }

    private fun registrar(marca: String, texto: String) {
        val ms = System.currentTimeMillis() - inicio
        linhas.append(String.format(Locale.US, "%s  [+%5d ms] %-4s %s%n",
            hora.format(Date()), ms, marca, texto))
    }

    fun texto(): String = linhas.toString()

    fun vazio(): Boolean = linhas.isEmpty()
}
