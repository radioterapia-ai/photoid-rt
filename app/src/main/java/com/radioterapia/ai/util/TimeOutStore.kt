package com.radioterapia.ai.util

import org.json.JSONObject
import java.io.File

/**
 * Persistência da página TIME-OUT por simulação (arquivo oculto
 * .timeout_simN.json na pasta do paciente). Garante que regenerações do PDF
 * (editar observações, adicionar fotos no tratamento, migração de cadastro)
 * mantenham a página Time-Out com as mesmas escolhas.
 */
object TimeOutStore {

    data class Registro(
        val ativo: Boolean,
        val medico: String,
        val sitio: String,
        val riscoQueda: Boolean,
        val precaucaoContato: Boolean,
        val equipamento: String = "",
        val alergia: String = "",  // "SIM" | "NAO" | "" (não informado)
        /** Última fração prevista (1..40). 0 = não informado. */
        val fracoesMax: Int = 0,
        /** Protocolo escolhido para ESTA simulação. Vazio = ficha simples. */
        val protocoloId: String = ""
    )

    private fun arquivo(pastaPaciente: File, numSim: Int) =
        File(pastaPaciente, ".timeout_sim%d.json".format(numSim))

    fun ler(pastaPaciente: File, numSim: Int): Registro? = try {
        val f = arquivo(pastaPaciente, numSim)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            Registro(
                ativo = o.optBoolean("ativo", false),
                medico = o.optString("medico", ""),
                sitio = o.optString("sitio", ""),
                riscoQueda = o.optBoolean("risco", false),
                precaucaoContato = o.optBoolean("precaucao", false),
                equipamento = o.optString("equipamento", ""),
                alergia = o.optString("alergia", ""),
                fracoesMax = o.optInt("fracoes_max", 0),
                protocoloId = o.optString("protocolo", "")
            )
        }
    } catch (_: Exception) { null }

    /**
     * Grava o Time-Out. Devolve `true` se gravou (ou apagou, quando inativo).
     *
     * DUAS MUDANÇAS que nasceram de perda de dado clínico em campo:
     *
     * 1. `mkdirs()`. A pasta não era criada aqui. Quando a chamada vinha com uma
     *    pasta inexistente — era o caso da PRIMEIRA finalização, que montava o
     *    caminho por conta própria em vez de usar `resolverPastaSim` — o
     *    `writeText` estourava `FileNotFoundException`.
     *
     * 2. Retorno em vez de `catch` mudo. O `catch (_: Exception) {}` engolia
     *    exatamente essa exceção, então o sítio de tratamento, o risco de queda
     *    e a precaução de contato simplesmente não chegavam ao disco, sem uma
     *    linha de aviso. Quem chama agora sabe que falhou e avisa o técnico.
     */
    fun gravar(pastaPaciente: File, numSim: Int, reg: Registro?): Boolean = try {
        val f = arquivo(pastaPaciente, numSim)
        if (reg == null || !reg.ativo) {
            f.delete(); true
        } else {
            pastaPaciente.mkdirs()
            val o = JSONObject()
            o.put("ativo", true)
            o.put("medico", reg.medico)
            o.put("sitio", reg.sitio)
            o.put("risco", reg.riscoQueda)
            o.put("precaucao", reg.precaucaoContato)
            o.put("equipamento", reg.equipamento)
            o.put("alergia", reg.alergia)
            // 0 = nao informado. E campo OPCIONAL: o tecnico que nao
            // sabe o numero de fracoes na hora da simulacao deixa em
            // branco, e a folha sai como sempre saiu.
            o.put("fracoes_max", reg.fracoesMax)
            o.put("protocolo", reg.protocoloId)
            f.writeText(o.toString())
            true
        }
    } catch (_: Exception) { false }
}
