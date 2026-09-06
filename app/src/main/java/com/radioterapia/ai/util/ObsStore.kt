package com.radioterapia.ai.util

import java.io.File

/**
 * Observações da simulação persistidas na PASTA do paciente (arquivo oculto
 * .obs_simN.txt). Permite reeditar a observação depois — no Finalizar e no
 * fluxo de adicionar fotos do tratamento — sempre partindo da última versão.
 */
object ObsStore {
    private fun arquivo(pastaPaciente: File, numSim: Int) =
        File(pastaPaciente, ".obs_sim%d.txt".format(numSim))

    fun ler(pastaPaciente: File, numSim: Int): String = try {
        arquivo(pastaPaciente, numSim).takeIf { it.exists() }?.readText()?.trim() ?: ""
    } catch (_: Exception) { "" }

    /**
     * Grava a observação. Devolve `true` se gravou (ou apagou, quando vazia).
     *
     * Mesmo conserto do [TimeOutStore.gravar]: cria a pasta antes de escrever e
     * devolve o resultado em vez de engolir a exceção. A observação da simulação
     * era perdida em silêncio junto com o Time-Out, pela mesma causa.
     */
    fun gravar(pastaPaciente: File, numSim: Int, texto: String): Boolean = try {
        val f = arquivo(pastaPaciente, numSim)
        if (texto.isBlank()) {
            f.delete()
        } else {
            pastaPaciente.mkdirs()
            f.writeText(texto.trim())
        }
        true
    } catch (_: Exception) { false }
}
