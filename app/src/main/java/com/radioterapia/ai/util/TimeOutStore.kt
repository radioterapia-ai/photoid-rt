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

    private const val PREFIXO = ".timeout_sim"
    private const val EXTENSAO = ".json"

    private fun arquivo(pastaPaciente: File, numSim: Int) =
        File(pastaPaciente, nomeOculto(PREFIXO, numSim, EXTENSAO))

    /**
     * Nome do arquivo oculto de uma simulação, com o número SEMPRE em dígitos
     * ASCII.
     *
     * GUARDA: `Int.toString()` não consulta o idioma. `"%d".format(n)` consulta: o app
     * troca o idioma padrão da JVM pelo da interface, e em árabe ou bengali o
     * número sai em algarismos daquela escrita. Um nome gravado assim deixa de
     * ser achado quando o idioma muda, e a ficha regerada sai sem o Time-Out,
     * sem erro nenhum.
     */
    internal fun nomeOculto(prefixo: String, numSim: Int, extensao: String): String =
        prefixo + numSim.toString() + extensao

    /**
     * O arquivo a LER: o de nome ASCII; se ele não existir, o mesmo arquivo
     * gravado com algarismos de outra escrita, que pode existir em tablets que
     * rodaram o app em árabe ou bengali.
     *
     * A comparação é pelo VALOR dos algarismos ([Character.digit] entende
     * qualquer escrita decimal do Unicode), e não por uma lista de idiomas: o
     * algarismo usado dependeu do idioma da época e da versão do Android, e
     * nenhuma das duas coisas se sabe hoje.
     */
    internal fun arquivoParaLer(pasta: File, prefixo: String, numSim: Int, extensao: String): File {
        val ascii = File(pasta, nomeOculto(prefixo, numSim, extensao))
        if (ascii.exists()) return ascii
        return variantesLocalizadas(pasta, prefixo, numSim, extensao).firstOrNull() ?: ascii
    }

    /** Arquivos desta simulação cujo número NÃO está em ASCII. */
    internal fun variantesLocalizadas(pasta: File, prefixo: String, numSim: Int,
                                      extensao: String): List<File> = try {
        pasta.listFiles { _, nome ->
            nome.startsWith(prefixo) && nome.endsWith(extensao) &&
                nome.length > prefixo.length + extensao.length &&
                nome.substring(prefixo.length, nome.length - extensao.length).let { meio ->
                    meio.any { it !in '0'..'9' } && valorDosAlgarismos(meio) == numSim
                }
        }?.filter { it.isFile }.orEmpty()
    } catch (_: Exception) { emptyList() }

    /** Valor de uma sequência de algarismos decimais de qualquer escrita; -1 se não for. */
    private fun valorDosAlgarismos(s: String): Int {
        if (s.isEmpty() || s.length > 4) return -1
        var v = 0
        for (c in s) {
            val d = Character.digit(c, 10)
            if (d < 0) return -1
            v = v * 10 + d
        }
        return v
    }

    fun ler(pastaPaciente: File, numSim: Int): Registro? = try {
        val f = arquivoParaLer(pastaPaciente, PREFIXO, numSim, EXTENSAO)
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
     *
     * Grava sempre no nome ASCII ([nomeOculto]) e, depois de gravar, apaga a
     * cópia com algarismos de outra escrita, se houver: ela ficaria para trás
     * com o conteúdo antigo, e a leitura só a consulta quando o nome ASCII falta.
     */
    fun gravar(pastaPaciente: File, numSim: Int, reg: Registro?): Boolean = try {
        val f = arquivo(pastaPaciente, numSim)
        if (reg == null || !reg.ativo) {
            f.delete()
            variantesLocalizadas(pastaPaciente, PREFIXO, numSim, EXTENSAO).forEach { it.delete() }
            true
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
            variantesLocalizadas(pastaPaciente, PREFIXO, numSim, EXTENSAO).forEach { it.delete() }
            true
        }
    } catch (_: Exception) { false }
}
