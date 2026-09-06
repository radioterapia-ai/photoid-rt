package com.radioterapia.ai.audit

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger de auditoria estruturado.
 *
 * Eventos registrados:
 *  - EDIT (edição de paciente)
 *  - SYNC_CSV (sucesso/falha)
 *  - DIVERGENCE (divergência detectada)
 *  - UPLOAD (envio SMB)
 *  - PRINT (impressão)
 *  - FINISH (simulação finalizada)
 *  - ERROR (erros gerais)
 *
 * Persistência: JSON Lines em filesDir/audit_log.jsonl
 * Cada linha um JSONObject com: timestamp, tipo, detalhes...
 */
class AuditLogger(private val context: Context) {

    private val arquivo: File = File(context.filesDir, "audit_log.jsonl")

    enum class Tipo { EDIT, SYNC_CSV, DIVERGENCE, UPLOAD, PRINT, FINISH, ERROR, INFO }

    fun registrar(tipo: Tipo, mensagem: String, detalhes: Map<String, Any?> = emptyMap()) {
        try {
            val obj = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("ts_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                put("tipo", tipo.name)
                put("msg", mensagem)
                detalhes.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
            }
            arquivo.appendText(obj.toString() + "\n")
        } catch (_: Exception) { /* fail silently — log não pode quebrar o app */ }
    }

    fun listar(filtro: Tipo? = null, limite: Int = 1000): List<JSONObject> {
        if (!arquivo.exists()) return emptyList()
        val resultado = mutableListOf<JSONObject>()
        try {
            arquivo.bufferedReader().useLines { linhas ->
                linhas.forEach { l ->
                    if (l.isBlank()) return@forEach
                    try {
                        val obj = JSONObject(l)
                        if (filtro == null || obj.optString("tipo") == filtro.name) {
                            resultado.add(obj)
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return resultado.takeLast(limite).reversed() // mais recente primeiro
    }

    fun exportarTexto(): String {
        if (!arquivo.exists()) return ""
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        listar().forEach { obj ->
            sb.append(fmt.format(Date(obj.optLong("ts"))))
            sb.append("  [").append(obj.optString("tipo", "?")).append("]  ")
            sb.append(obj.optString("msg"))
            // anexa demais campos como detalhes
            val keys = obj.keys()
            keys.forEach { k ->
                if (k !in setOf("ts", "ts_iso", "tipo", "msg")) {
                    sb.append("  ").append(k).append("=").append(obj.optString(k))
                }
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    fun limpar() {
        if (arquivo.exists()) arquivo.delete()
    }

    fun limparAntigo(diasMaximos: Int) {
        if (!arquivo.exists()) return
        val limite = System.currentTimeMillis() - diasMaximos * 24L * 3600 * 1000
        val temp = File(arquivo.parentFile, "audit_log.tmp")
        try {
            arquivo.bufferedReader().use { reader ->
                temp.bufferedWriter().use { writer ->
                    reader.lineSequence().forEach { l ->
                        try {
                            val obj = JSONObject(l)
                            if (obj.optLong("ts") >= limite) {
                                writer.appendLine(l)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            temp.copyTo(arquivo, overwrite = true)
            temp.delete()
        } catch (_: Exception) {
            if (temp.exists()) temp.delete()
        }
    }
}
