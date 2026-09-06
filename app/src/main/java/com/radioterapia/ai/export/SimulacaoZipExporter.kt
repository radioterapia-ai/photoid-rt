package com.radioterapia.ai.export

import android.content.Context
import com.radioterapia.ai.util.ObsStore
import com.radioterapia.ai.util.StorageLocal
import com.radioterapia.ai.util.TimeOutStore
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Empacota UMA simulação inteira num único `.zip`: fotos, originais, PDF da
 * folha, Time-Out e observações.
 *
 * Existe porque levar uma simulação para fora do tablet hoje é copiar arquivo a
 * arquivo, e os dados clínicos do Time-Out nem aparecem — ficam em arquivos
 * ocultos (`.timeout_simN.json`, `.obs_simN.txt`) que ninguém pensa em copiar.
 * Um caso levado a outro serviço, ou a uma junta médica, chegava incompleto sem
 * ninguém notar.
 *
 * O Time-Out e a observação entram TAMBÉM como `RESUMO.txt` legível, porque do
 * outro lado do zip pode não haver ninguém com o app instalado — e um JSON
 * oculto não é resposta para quem precisa ler o sítio de tratamento.
 */
object SimulacaoZipExporter {

    class Resultado(val ok: Boolean, val arquivo: File?, val qtdArquivos: Int)

    /**
     * Monta o zip em cache e devolve o arquivo. Não escreve na pasta do
     * paciente: o zip é material de saída, não parte do prontuário — gravá-lo
     * ali faria o FileSync sincronizar uma cópia inteira e redundante da
     * simulação a cada exportação.
     */
    fun exportar(context: Context, nomePaciente: String, prontuario: String,
                 nomePastaSim: String, numSim: Int): Resultado {
        val pasta = StorageLocal.resolverPastaSim(
            context, nomePaciente, numSim, nomePastaSim, prontuario)
        if (!pasta.exists() || !pasta.isDirectory) return Resultado(false, null, 0)

        val tag = if (numSim > 1) "_NOVASIM${numSim - 1}" else ""
        val base = StorageLocal.removerAcentosMaiusculas(nomePaciente).replace(" ", "_")
        val saida = File(context.cacheDir, "${base}${tag}_SIMULACAO.zip")
        if (saida.exists()) saida.delete()

        // Só os arquivos DESTA simulação: uma pasta de paciente pode conter
        // várias (reirradiação), e exportar tudo entregaria material de outra
        // simulação sem quem exporta perceber.
        val doArquivo = { n: String ->
            if (numSim == 1) !n.contains("_NOVASIM") else n.contains("_NOVASIM${numSim - 1}")
        }
        val arquivos = pasta.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && doArquivo(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (arquivos.isEmpty()) return Resultado(false, null, 0)

        return try {
            var n = 0
            ZipOutputStream(saida.outputStream().buffered()).use { zip ->
                arquivos.forEach { arq ->
                    try {
                        zip.putNextEntry(ZipEntry(arq.name))
                        arq.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        n++
                    } catch (_: Exception) { /* um arquivo ilegível não perde o lote */ }
                }
                val reg = TimeOutStore.ler(pasta, numSim)
                val obs = ObsStore.ler(pasta, numSim)
                zip.putNextEntry(ZipEntry("RESUMO.txt"))
                zip.write(resumoLegivel(nomePaciente, prontuario, numSim, reg, obs)
                    .toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("simulacao.json"))
                zip.write(comoJson(nomePaciente, prontuario, numSim, reg, obs)
                    .toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                n += 2
            }
            Resultado(true, saida, n)
        } catch (_: Exception) {
            saida.delete()
            Resultado(false, null, 0)
        }
    }

    private fun resumoLegivel(nome: String, pront: String, numSim: Int,
                              reg: TimeOutStore.Registro?, obs: String): String {
        val sb = StringBuilder()
        sb.append("PACIENTE: ").append(nome).append("\n")
        if (pront.isNotBlank()) sb.append("PRONTUARIO: ").append(pront).append("\n")
        sb.append("SIMULACAO: ")
            .append(if (numSim > 1) "NOVA SIMULACAO ${numSim - 1}" else "ORIGINAL").append("\n\n")
        if (reg != null && reg.ativo) {
            sb.append("TIME-OUT\n")
            sb.append("  Medico:      ").append(reg.medico).append("\n")
            sb.append("  Sitio:       ").append(reg.sitio).append("\n")
            sb.append("  Equipamento: ").append(reg.equipamento).append("\n")
            sb.append("  Risco queda: ").append(if (reg.riscoQueda) "SIM" else "NAO").append("\n")
            sb.append("  Precaucao:   ").append(if (reg.precaucaoContato) "SIM" else "NAO").append("\n")
            sb.append("  Alergia:     ").append(reg.alergia.ifBlank { "NAO INFORMADO" }).append("\n\n")
        }
        if (obs.isNotBlank()) sb.append("OBSERVACOES\n").append(obs).append("\n")
        return sb.toString()
    }

    private fun comoJson(nome: String, pront: String, numSim: Int,
                         reg: TimeOutStore.Registro?, obs: String): String {
        val o = JSONObject()
        o.put("paciente", nome)
        o.put("prontuario", pront)
        o.put("numero_simulacao", numSim)
        o.put("observacoes", obs)
        if (reg != null && reg.ativo) {
            o.put("time_out", JSONObject().apply {
                put("medico", reg.medico)
                put("sitio", reg.sitio)
                put("equipamento", reg.equipamento)
                put("risco_queda", reg.riscoQueda)
                put("precaucao_contato", reg.precaucaoContato)
                put("alergia", reg.alergia)
            })
        }
        return o.toString(2)
    }
}
