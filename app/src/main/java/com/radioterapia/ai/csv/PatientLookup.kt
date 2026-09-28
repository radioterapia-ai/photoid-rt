package com.radioterapia.ai.csv

import android.content.Context
import com.radioterapia.ai.data.AppDatabase
import com.radioterapia.ai.data.PatientEntity
import com.radioterapia.ai.patient.PatientCache

/**
 * Faz a busca de paciente combinando:
 *  - Lookup no CSV (Room) por prontuário
 *  - Histórico local do tablet
 *
 * Detecta divergências quando o paciente existe em ambos com dados diferentes:
 *  - Nome diferente
 *  - Data de nascimento diferente
 *  - Qualquer campo extra mudou
 */
class PatientLookup(private val context: Context) {

    private val cache = PatientCache(context)

    suspend fun buscar(prontuario: String): Resultado {
        if (prontuario.isBlank()) return Resultado.NaoEncontrado(emptyList())

        val dao = AppDatabase.get(context).patientDao()

        // 1. Busca exata no CSV
        val doCsv = dao.buscarPorProntuario(prontuario)
        if (doCsv == null) {
            // 2. Busca difusa por similaridade
            val similares = dao.buscarSimilarPorProntuario(prontuario)
            return Resultado.NaoEncontrado(similares)
        }

        // 3. Verifica divergência com histórico local
        val doLocal = cache.obterDadosPaciente(doCsv.nome)
        if (doLocal == null) return Resultado.Encontrado(doCsv, divergencia = null)

        val divergencias = mutableListOf<Divergencia>()
        if (!nomesIguais(doLocal.nome, doCsv.nome))
            divergencias.add(Divergencia("Nome", doCsv.nome, doLocal.nome))
        if (doLocal.nascimento.isNotBlank() && doCsv.nascimento.isNotBlank()
            && !datasIguais(doLocal.nascimento, doCsv.nascimento))
            divergencias.add(Divergencia("Nascimento", doCsv.nascimento, doLocal.nascimento))
        if (doLocal.prontuario.isNotBlank() && doLocal.prontuario != doCsv.prontuario)
            divergencias.add(Divergencia("Prontuário", doCsv.prontuario, doLocal.prontuario))

        return Resultado.Encontrado(
            doCsv,
            divergencia = if (divergencias.isEmpty()) null else divergencias
        )
    }

    private fun nomesIguais(a: String, b: String): Boolean {
        return CsvImporter.normalizarParaBusca(a) == CsvImporter.normalizarParaBusca(b)
    }

    private fun datasIguais(a: String, b: String): Boolean {
        // Compara depois de remover separadores
        return a.replace(Regex("[^0-9]"), "") == b.replace(Regex("[^0-9]"), "")
    }

    sealed class Resultado {
        /** Paciente encontrado no CSV. divergencia=null significa sem conflito. */
        data class Encontrado(
            val paciente: PatientEntity,
            val divergencia: List<Divergencia>?
        ) : Resultado()

        /** Não achou exato. Pode ter sugestões similares. */
        data class NaoEncontrado(val similares: List<PatientEntity>) : Resultado()
    }

    data class Divergencia(val campo: String, val valorCsv: String, val valorLocal: String)
}
