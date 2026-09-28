package com.radioterapia.ai.csv

import android.content.Context

/**
 * Configuração do mapeamento de colunas do CSV.
 *
 * Cada coluna é identificada pelo seu **número** (1-indexado) — confirmado nas decisões.
 * Nome / nascimento / prontuário são obrigatórios. Os 3 IDs extras são opcionais e cada
 * um pode ter título customizável (ex: "Convênio", "CPF").
 */
class CsvMapping(context: Context) {

    private val prefs = context.getSharedPreferences("csv_mapping", Context.MODE_PRIVATE)

    /** Número da coluna do nome (1-indexado). 0 = não configurado. */
    var colunaNome: Int
        get() = prefs.getInt(KEY_COL_NOME, 0)
        set(value) = prefs.edit().putInt(KEY_COL_NOME, value).apply()

    var colunaNascimento: Int
        get() = prefs.getInt(KEY_COL_NASC, 0)
        set(value) = prefs.edit().putInt(KEY_COL_NASC, value).apply()

    var colunaProntuario: Int
        get() = prefs.getInt(KEY_COL_PRONT, 0)
        set(value) = prefs.edit().putInt(KEY_COL_PRONT, value).apply()

    fun obterExtra(idx: Int): IdExtra {
        return IdExtra(
            titulo = prefs.getString("$KEY_EXTRA_TITULO$idx", "") ?: "",
            coluna = prefs.getInt("$KEY_EXTRA_COL$idx", 0)
        )
    }

    fun salvarExtra(idx: Int, extra: IdExtra) {
        prefs.edit()
            .putString("$KEY_EXTRA_TITULO$idx", extra.titulo)
            .putInt("$KEY_EXTRA_COL$idx", extra.coluna)
            .apply()
    }

    fun extrasConfigurados(): List<Pair<Int, IdExtra>> {
        val result = mutableListOf<Pair<Int, IdExtra>>()
        for (i in 1..3) {
            val e = obterExtra(i)
            if (e.coluna > 0 && e.titulo.isNotBlank()) result.add(i to e)
        }
        return result
    }

    fun valido(): Boolean = colunaNome > 0 && colunaNascimento > 0 && colunaProntuario > 0

    fun limpar() { prefs.edit().clear().apply() }

    data class IdExtra(val titulo: String, val coluna: Int)

    companion object {
        private const val KEY_COL_NOME = "col_nome"
        private const val KEY_COL_NASC = "col_nasc"
        private const val KEY_COL_PRONT = "col_prontuario"
        private const val KEY_EXTRA_TITULO = "extra_titulo_"
        private const val KEY_EXTRA_COL = "extra_col_"
    }
}
