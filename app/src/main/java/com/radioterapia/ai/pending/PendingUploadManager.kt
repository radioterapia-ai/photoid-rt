package com.radioterapia.ai.pending

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Gerencia lista de simulações com falha de envio.
 *
 * Cada item contém:
 *  - id único
 *  - paciente
 *  - timestamp da tentativa original
 *  - lista de arquivos pendentes (caminhos locais ainda existentes)
 *  - configuração de destino que falhou (host, share, subpasta, nome da pasta)
 *  - número de tentativas
 *  - última mensagem de erro
 *
 * Persistência: JSON em filesDir/pending_uploads.json
 *
 * Quando o usuário toca em "Reenviar", a tela de pendências processa cada item
 * e move pra fila de sucesso (remove daqui) ou atualiza tentativas+erro.
 */
class PendingUploadManager(private val context: Context) {

    private val arquivo: File = File(context.filesDir, "pending_uploads.json")

    data class Item(
        val id: String,
        val paciente: String,
        val pasta: String,
        val timestampOriginal: Long,
        val arquivos: List<String>,        // caminhos locais (em filesDir/cacheDir)
        val destinos: List<String>,        // hosts dos destinos que falharam
        val tentativas: Int,
        val ultimoErro: String
    )

    fun listar(): List<Item> {
        if (!arquivo.exists()) return emptyList()
        return try {
            val arr = JSONArray(arquivo.readText())
            (0 until arr.length()).map { idx -> parsearItem(arr.getJSONObject(idx)) }
        } catch (_: Exception) { emptyList() }
    }

    fun contar(): Int = listar().size

    fun adicionar(item: Item) {
        val atual = listar().toMutableList()
        atual.add(item)
        salvar(atual)
    }

    fun remover(id: String) {
        val atual = listar().toMutableList()
        atual.removeAll { it.id == id }
        salvar(atual)
    }

    fun atualizar(id: String, novoTentativas: Int, novoErro: String) {
        val atual = listar().toMutableList()
        val idx = atual.indexOfFirst { it.id == id }
        if (idx < 0) return
        val antigo = atual[idx]
        atual[idx] = antigo.copy(tentativas = novoTentativas, ultimoErro = novoErro)
        salvar(atual)
    }

    private fun salvar(items: List<Item>) {
        val arr = JSONArray()
        items.forEach { i ->
            val o = JSONObject()
            o.put("id", i.id)
            o.put("paciente", i.paciente)
            o.put("pasta", i.pasta)
            o.put("ts", i.timestampOriginal)
            o.put("arquivos", JSONArray(i.arquivos))
            o.put("destinos", JSONArray(i.destinos))
            o.put("tentativas", i.tentativas)
            o.put("ultimo_erro", i.ultimoErro)
            arr.put(o)
        }
        try { arquivo.writeText(arr.toString()) } catch (_: Exception) {}
    }

    private fun parsearItem(obj: JSONObject): Item {
        val arquivos = mutableListOf<String>()
        val destinos = mutableListOf<String>()
        obj.optJSONArray("arquivos")?.let { for (i in 0 until it.length()) arquivos.add(it.getString(i)) }
        obj.optJSONArray("destinos")?.let { for (i in 0 until it.length()) destinos.add(it.getString(i)) }
        return Item(
            id = obj.optString("id"),
            paciente = obj.optString("paciente"),
            pasta = obj.optString("pasta"),
            timestampOriginal = obj.optLong("ts"),
            arquivos = arquivos,
            destinos = destinos,
            tentativas = obj.optInt("tentativas", 1),
            ultimoErro = obj.optString("ultimo_erro")
        )
    }
}
