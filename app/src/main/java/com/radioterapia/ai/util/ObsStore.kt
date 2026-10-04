package com.radioterapia.ai.util

import java.io.File

/**
 * Observações da simulação persistidas na PASTA do paciente (arquivo oculto
 * .obs_simN.txt). Permite reeditar a observação depois — no Finalizar e no
 * fluxo de adicionar fotos do tratamento — sempre partindo da última versão.
 */
object ObsStore {
    private const val PREFIXO = ".obs_sim"
    private const val EXTENSAO = ".txt"

    /**
     * Número em dígitos ASCII, pela mesma regra do Time-Out
     * ([TimeOutStore.nomeOculto]): o nome não pode depender do idioma da
     * interface, senão a observação some quando o idioma muda.
     */
    private fun arquivo(pastaPaciente: File, numSim: Int) =
        File(pastaPaciente, TimeOutStore.nomeOculto(PREFIXO, numSim, EXTENSAO))

    fun ler(pastaPaciente: File, numSim: Int): String = try {
        TimeOutStore.arquivoParaLer(pastaPaciente, PREFIXO, numSim, EXTENSAO)
            .takeIf { it.exists() }?.readText()?.trim() ?: ""
    } catch (_: Exception) { "" }

    /**
     * Grava a observação. Devolve `true` se gravou (ou apagou, quando vazia).
     *
     * Mesmo conserto do [TimeOutStore.gravar]: cria a pasta antes de escrever e
     * devolve o resultado em vez de engolir a exceção. A observação da simulação
     * era perdida em silêncio junto com o Time-Out, pela mesma causa. E, como
     * lá, a cópia com algarismos de outra escrita sai depois de gravar.
     */
    fun gravar(pastaPaciente: File, numSim: Int, texto: String): Boolean = try {
        val f = arquivo(pastaPaciente, numSim)
        if (texto.isBlank()) {
            f.delete()
        } else {
            pastaPaciente.mkdirs()
            f.writeText(texto.trim())
        }
        TimeOutStore.variantesLocalizadas(pastaPaciente, PREFIXO, numSim, EXTENSAO)
            .forEach { it.delete() }
        true
    } catch (_: Exception) { false }
}
