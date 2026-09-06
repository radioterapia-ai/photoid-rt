package com.radioterapia.ai.treatment

import android.content.Context
import com.radioterapia.ai.util.StorageLocal

/**
 * Lista de "Pacientes em Tratamento" — LOCAL e por tablet (nunca sincronizada).
 * Cada tablet cuida da agenda de um aparelho, então essa lista não vai para
 * backup nem servidor. Guarda apenas as CHAVES normalizadas (nome) dos pacientes.
 *
 * Um paciente entra automaticamente ao ser simulado (Finalizar) e sai ao receber
 * "Alta". A lista é um subconjunto do histórico (que mantém todos os pacientes).
 */
class TreatmentListManager(context: Context) {

    private val prefs = context.getSharedPreferences("treatment_list", Context.MODE_PRIVATE)
    private val KEY = "em_tratamento"

    private fun ler(): MutableSet<String> =
        // Cópia mutável: o set retornado por getStringSet não deve ser modificado direto.
        HashSet(prefs.getStringSet(KEY, emptySet()) ?: emptySet())

    private fun gravar(set: Set<String>) {
        // commit() para refletir imediatamente ao voltar de telas.
        prefs.edit().putStringSet(KEY, set).commit()
    }

    /** Aloca o paciente para a lista de tratamento. */
    fun alocar(nomePaciente: String) {
        val set = ler(); set.add(StorageLocal.chaveNome(nomePaciente)); gravar(set)
    }

    /** Dá alta — remove o paciente da lista de tratamento (continua no histórico). */
    fun darAlta(nomePaciente: String) {
        val set = ler(); set.remove(StorageLocal.chaveNome(nomePaciente)); gravar(set)
    }

    /** Retira o paciente da lista local (migração de cadastro): mesmo efeito da alta. */
    fun remover(nomePaciente: String) = darAlta(nomePaciente)

    fun estaEmTratamento(nomePaciente: String): Boolean =
        ler().contains(StorageLocal.chaveNome(nomePaciente))

    /** Conjunto de chaves (nomes normalizados) em tratamento. */
    fun chavesEmTratamento(): Set<String> = ler()

    fun total(): Int = ler().size
}
