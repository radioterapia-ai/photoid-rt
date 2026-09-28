package com.radioterapia.ai.stats

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.radioterapia.ai.R
import com.radioterapia.ai.audit.AuditLogger
import com.radioterapia.ai.patient.PatientCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dashboard simples de estatísticas com base no histórico local + auditoria.
 *
 * Métricas:
 *  - Total de pacientes registrados
 *  - Total de simulações finalizadas (do log)
 *  - Simulações nos últimos 7/30 dias
 *  - Reirradiações detectadas
 *  - Taxa de sucesso/falha de envios
 *  - Impressões realizadas
 */
class StatsActivity : com.radioterapia.ai.BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.stats_title)

    private lateinit var auditLogger: AuditLogger
    private lateinit var patientCache: PatientCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        supportActionBar?.title = getString(R.string.stats_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        auditLogger = AuditLogger(this)
        patientCache = PatientCache(this)

        renderizar()

        findViewById<Button>(R.id.btnExportStats).setOnClickListener { exportar() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun renderizar() {
        val txtTotal = findViewById<TextView>(R.id.txtStatsTotal)
        val txtUlt7 = findViewById<TextView>(R.id.txtStatsUlt7)
        val txtUlt30 = findViewById<TextView>(R.id.txtStatsUlt30)
        val txtReirr = findViewById<TextView>(R.id.txtStatsReirr)
        val txtUploads = findViewById<TextView>(R.id.txtStatsUploads)
        val txtImpr = findViewById<TextView>(R.id.txtStatsImpr)
        val txtErros = findViewById<TextView>(R.id.txtStatsErros)
        val txtDetalhe = findViewById<TextView>(R.id.txtStatsDetalhe)

        val finishs = auditLogger.listar(AuditLogger.Tipo.FINISH)
        val uploadOk = auditLogger.listar(AuditLogger.Tipo.UPLOAD).size
        val erros = auditLogger.listar(AuditLogger.Tipo.ERROR).size
        val impressoes = auditLogger.listar(AuditLogger.Tipo.PRINT).size

        val agora = System.currentTimeMillis()
        val inicio7 = agora - TimeUnit.DAYS.toMillis(7)
        val inicio30 = agora - TimeUnit.DAYS.toMillis(30)
        val ult7 = finishs.count { it.optLong("ts") > inicio7 }
        val ult30 = finishs.count { it.optLong("ts") > inicio30 }
        val reirr = patientCache.listarPacientes()
            .mapNotNull { patientCache.obterDadosPaciente(it) }
            .count { it.simulacoes >= 2 }

        txtTotal.text = "${patientCache.listarPacientes().size}"
        txtUlt7.text = "$ult7"
        txtUlt30.text = "$ult30"
        txtReirr.text = "$reirr"
        txtUploads.text = "$uploadOk"
        txtImpr.text = "$impressoes"
        txtErros.text = "$erros"

        val sb = StringBuilder()
        sb.append("Total de simulações finalizadas: ${finishs.size}\n")
        if (finishs.isNotEmpty()) {
            val maisAntiga = finishs.minOfOrNull { it.optLong("ts") } ?: 0L
            val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            sb.append("Primeira registrada: ${fmt.format(Date(maisAntiga))}\n")
        }
        if (uploadOk + erros > 0) {
            val taxaSucesso = (uploadOk * 100) / (uploadOk + erros)
            sb.append("Taxa de sucesso de envios: ~$taxaSucesso%\n")
        }
        txtDetalhe.text = sb.toString()
    }

    private fun exportar() {
        // Monta as linhas do relatório
        val linhas = mutableListOf<String>()
        linhas.add("RADIOTERAPIA.AI — RELATÓRIO DE ESTATÍSTICAS")
        linhas.add("Gerado em ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}")
        linhas.add("")

        val pacientes = patientCache.listarPacientes()
            .mapNotNull { patientCache.obterDadosPaciente(it) }
            .sortedByDescending { it.ultimaSimulacao }

        linhas.add("PACIENTES NO HISTÓRICO LOCAL: ${pacientes.size}")
        linhas.add("")
        linhas.add(String.format("%-32s %-12s %-14s %s", "NOME", "PRONTUÁRIO", "NASCIMENTO", "SIM."))
        linhas.add("─".repeat(72))
        val idioma = com.radioterapia.ai.i18n.LocaleManager.obterIdiomaAtual(this)
        val fmt = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        pacientes.forEach { p ->
            linhas.add(String.format("%-32s %-12s %-14s %d (%s)",
                p.nome.take(32),
                p.prontuario.take(12).ifBlank { "—" },
                com.radioterapia.ai.util.DateUtils.formatarNascimento(p.nascimento, idioma).take(14).ifBlank { "—" },
                p.simulacoes,
                if (p.ultimaSimulacao > 0) fmt.format(Date(p.ultimaSimulacao)) else "—"))
        }

        val nome = "estatisticas_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.pdf"
        try {
            // Gera o PDF (A4 retrato, ~72 linhas/página)
            val pdf = android.graphics.pdf.PdfDocument()
            val larguraPag = 595; val alturaPag = 842
            val margem = 36f; val lineH = 13f
            val paintTit = android.graphics.Paint().apply { textSize = 13f; isFakeBoldText = true; isAntiAlias = true }
            val paintMono = android.graphics.Paint().apply {
                textSize = 9f; isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }
            var i = 0; var pagina = 1
            while (i < linhas.size) {
                val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(larguraPag, alturaPag, pagina).create()
                val page = pdf.startPage(info)
                val canvas = page.canvas
                var y = margem + lineH
                while (i < linhas.size && y < alturaPag - margem) {
                    val txt = linhas[i]
                    canvas.drawText(txt, margem, y, if (pagina == 1 && i == 0) paintTit else paintMono)
                    y += lineH; i++
                }
                pdf.finishPage(page)
                pagina++
            }

            // Salva em Documents (coleção Files aceita PDF em Documents)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, com.radioterapia.ai.branding.Marca.subpastaDocumentos("Stats"))
                }
                val uri: Uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
                    ?: throw Exception("Falha criar arquivo")
                contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
            } else {
                @Suppress("DEPRECATION")
                val pasta = File(android.os.Environment.getExternalStorageDirectory(),
                    com.radioterapia.ai.branding.Marca.subpastaDocumentos("Stats"))
                if (!pasta.exists()) pasta.mkdirs()
                File(pasta, nome).outputStream().use { pdf.writeTo(it) }
            }
            pdf.close()
            Toast.makeText(this, getString(R.string.ok_exported, nome), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
