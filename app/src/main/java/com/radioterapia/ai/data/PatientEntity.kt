package com.radioterapia.ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache local do CSV de pacientes.
 * Atualizado a cada sincronização — sempre limpa antes de inserir as novas linhas.
 *
 * Campo `prontuario` é a chave única (configurada como obrigatória pela clínica).
 */
@Entity(tableName = "csv_patients")
data class PatientEntity(
    @PrimaryKey val prontuario: String,
    val nome: String,
    val nomeSearch: String,                  // nome normalizado (sem acento, minúsculo) para busca difusa
    val nascimento: String,
    val campoExtra1Titulo: String? = null,
    val campoExtra1Valor: String? = null,
    val campoExtra2Titulo: String? = null,
    val campoExtra2Valor: String? = null,
    val campoExtra3Titulo: String? = null,
    val campoExtra3Valor: String? = null,
    val syncTimestamp: Long
)
