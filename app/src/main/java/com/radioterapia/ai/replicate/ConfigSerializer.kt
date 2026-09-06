package com.radioterapia.ai.replicate

import android.content.Context
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.csv.CsvMapping
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializa/deserializa toda a configuração da clínica para sincronização entre tablets.
 *
 * IMPORTANTE: a senha SMB NÃO é incluída por questão de segurança.
 * O tablet importador precisa preencher a senha manualmente após importar.
 */
object ConfigSerializer {

    fun exportar(context: Context): String {
        val config = AppConfig(context)
        val mapping = CsvMapping(context)

        val obj = JSONObject()
        obj.put("v", 1)
        obj.put("nome_clinica", config.nomeClinica)

        // SMB
        val smb = JSONObject()
        smb.put("protocolo", config.smbProtocolo)
        smb.put("dominio", config.smbDominio)
        smb.put("usuario", config.smbUsuario)
        // SENHA NUNCA — é confidencial
        obj.put("smb", smb)

        // Destinos
        val destinos = JSONArray()
        for (i in 0..4) {
            val d = config.obterDestino(i)
            val o = JSONObject()
            o.put("ativo", d.ativo)
            o.put("host", d.host)
            o.put("porta", d.porta)
            o.put("unc", d.caminhoUNC)
            destinos.put(o)
        }
        obj.put("destinos", destinos)

        // CSV
        val csv = JSONObject()
        csv.put("pasta", config.csvPastaUnc)
        csv.put("tem_cabecalho", config.csvTemCabecalho)
        csv.put("col_nome", mapping.colunaNome)
        csv.put("col_nasc", mapping.colunaNascimento)
        csv.put("col_pront", mapping.colunaProntuario)
        val extras = JSONArray()
        for (i in 1..3) {
            val e = mapping.obterExtra(i)
            val eo = JSONObject()
            eo.put("idx", i)
            eo.put("titulo", e.titulo)
            eo.put("coluna", e.coluna)
            extras.put(eo)
        }
        csv.put("extras", extras)
        obj.put("csv", csv)

        // Impressora
        val impr = JSONObject()
        impr.put("ip", config.impressoraIp)
        impr.put("nome", config.impressoraNome)
        obj.put("impressora", impr)

        // Outras
        obj.put("pdf_servidor", config.pdfParaServidor)
        obj.put("pdf_landscape", config.pdfLandscape)
        obj.put("pasta_local", config.pastaBaseLocal)
        obj.put("camera_grid", config.cameraGrid)

        return obj.toString()
    }

    /**
     * @return mensagem de sucesso ou erro
     */
    fun importar(context: Context, json: String): Resultado {
        return try {
            val obj = JSONObject(json)
            if (obj.optInt("v") != 1) return Resultado(false, "Formato não suportado (versão diferente)")

            val config = AppConfig(context)
            val mapping = CsvMapping(context)

            config.nomeClinica = obj.optString("nome_clinica", "")

            obj.optJSONObject("smb")?.let { smb ->
                config.smbProtocolo = smb.optString("protocolo", "SMB2")
                config.smbDominio = smb.optString("dominio", "")
                config.smbUsuario = smb.optString("usuario", "")
                // senha não vem no payload — usuário precisa preencher
            }

            obj.optJSONArray("destinos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    config.salvarDestino(i, com.radioterapia.ai.DestinoSmb(
                        ativo = d.optBoolean("ativo"),
                        host = d.optString("host"),
                        porta = d.optInt("porta", 445),
                        caminhoUNC = d.optString("unc")
                    ))
                }
            }

            obj.optJSONObject("csv")?.let { csv ->
                config.csvPastaUnc = csv.optString("pasta", "")
                config.csvTemCabecalho = csv.optBoolean("tem_cabecalho", true)
                mapping.colunaNome = csv.optInt("col_nome", 0)
                mapping.colunaNascimento = csv.optInt("col_nasc", 0)
                mapping.colunaProntuario = csv.optInt("col_pront", 0)
                csv.optJSONArray("extras")?.let { extras ->
                    for (i in 0 until extras.length()) {
                        val e = extras.getJSONObject(i)
                        val idx = e.optInt("idx")
                        if (idx in 1..3) {
                            mapping.salvarExtra(idx, CsvMapping.IdExtra(
                                titulo = e.optString("titulo"),
                                coluna = e.optInt("coluna")
                            ))
                        }
                    }
                }
            }

            obj.optJSONObject("impressora")?.let { impr ->
                config.impressoraIp = impr.optString("ip", "")
                config.impressoraNome = impr.optString("nome", "")
            }

            config.pdfParaServidor = obj.optBoolean("pdf_servidor", true)
            config.pdfLandscape = obj.optBoolean("pdf_landscape", false)
            config.pastaBaseLocal = obj.optString("pasta_local", "Pictures").ifBlank { "Pictures" }
            config.cameraGrid = obj.optBoolean("camera_grid", true)

            Resultado(true, "Configuração importada. Preencha a senha SMB manualmente.")
        } catch (e: Exception) {
            Resultado(false, "Erro ao importar: ${e.message}")
        }
    }

    data class Resultado(val sucesso: Boolean, val mensagem: String)
}
