package com.radioterapia.ai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PatientDao {

    @Query("SELECT * FROM csv_patients WHERE prontuario = :prontuario LIMIT 1")
    suspend fun buscarPorProntuario(prontuario: String): PatientEntity?

    /**
     * Busca difusa: aceita match parcial no prontuário (typos comuns).
     * Limita a 10 sugestões.
     */
    @Query("SELECT * FROM csv_patients WHERE prontuario LIKE '%' || :fragmento || '%' LIMIT 10")
    suspend fun buscarSimilarPorProntuario(fragmento: String): List<PatientEntity>

    /**
     * Busca por nome (substring, case-insensitive via nomeSearch).
     */
    @Query("SELECT * FROM csv_patients WHERE nomeSearch LIKE '%' || :busca || '%' LIMIT 50")
    suspend fun buscarPorNome(busca: String): List<PatientEntity>

    @Query("SELECT COUNT(*) FROM csv_patients")
    suspend fun contar(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserir(pacientes: List<PatientEntity>)

    @Query("DELETE FROM csv_patients")
    suspend fun apagarTudo()

    @Query("SELECT * FROM csv_patients ORDER BY nome LIMIT :limite OFFSET :offset")
    suspend fun listar(limite: Int, offset: Int): List<PatientEntity>
}
