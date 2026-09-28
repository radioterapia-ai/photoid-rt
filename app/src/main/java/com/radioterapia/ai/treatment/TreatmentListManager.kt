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

    /**
     * Aplica um conjunto vindo de um pacote de transferência.
     *
     * A LISTA CONTINUA SENDO DESTE APARELHO — ela não sincroniza sozinha, e este
     * método só roda quando alguém marca o item na tela de importação. O que
     * mudou foi o reconhecimento de que "transferir" cobre dois casos que a
     * decisão original tratava como um: mandar a configuração para OUTRO tablet,
     * onde a agenda de fato não deve ir, e RESTAURAR o mesmo tablet depois de
     * reinstalar, onde ela é justamente o que se quer de volta.
     *
     * NUNCA ESVAZIA. Conjunto vindo vazio não apaga a agenda local nem em
     * substituir: um pacote exportado de um tablet ocioso apagaria, em silêncio,
     * a lista de quem está em tratamento no aparelho de destino — e ninguém
     * confere uma lista para ver se ela sumiu.
     *
     * @return quantas chaves entraram que ainda não estavam aqui.
     */
    fun importarChaves(chaves: Set<String>, substituir: Boolean): Int {
        val limpas = chaves.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (limpas.isEmpty()) return 0
        val atual = ler()
        val novas = limpas.count { it !in atual }
        gravar(if (substituir) limpas else atual + limpas)
        return novas
    }
}
