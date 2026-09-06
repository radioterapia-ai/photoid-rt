package com.radioterapia.ai.ui

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radioterapia.ai.R
import com.radioterapia.ai.audit.AuditLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsActivity : com.radioterapia.ai.BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.logs_title)

    private lateinit var auditLogger: AuditLogger

    private lateinit var recycler: RecyclerView
    private lateinit var spinnerFiltro: Spinner
    private lateinit var btnExportar: Button
    private lateinit var btnLimpar: Button
    private lateinit var txtVazio: TextView
    private lateinit var txtCount: TextView

    private var filtroAtual: AuditLogger.Tipo? = null

    private val filtros = listOf<Pair<String, AuditLogger.Tipo?>>(
        "Todos" to null,
        "Edições" to AuditLogger.Tipo.EDIT,
        "Sync CSV" to AuditLogger.Tipo.SYNC_CSV,
        "Divergências" to AuditLogger.Tipo.DIVERGENCE,
        "Uploads" to AuditLogger.Tipo.UPLOAD,
        "Impressões" to AuditLogger.Tipo.PRINT,
        "Finalizações" to AuditLogger.Tipo.FINISH,
        "Erros" to AuditLogger.Tipo.ERROR
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)
        supportActionBar?.title = getString(R.string.logs_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        auditLogger = AuditLogger(this)

        recycler = findViewById(R.id.recyclerLogs)
        spinnerFiltro = findViewById(R.id.spinnerFiltroLog)
        btnExportar = findViewById(R.id.btnLogExportar)
        btnLimpar = findViewById(R.id.btnLogLimpar)
        txtVazio = findViewById(R.id.txtLogVazio)
        txtCount = findViewById(R.id.txtLogCount)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            filtros.map { it.first })
        spinnerFiltro.adapter = adapter
        spinnerFiltro.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filtroAtual = filtros[position].second
                atualizar()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnExportar.setOnClickListener { exportar() }
        btnLimpar.setOnClickListener { confirmarLimpar() }

        atualizar()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun atualizar() {
        val itens = auditLogger.listar(filtroAtual, 1000)
        txtCount.text = getString(R.string.n_entries, itens.size)
        if (itens.isEmpty()) {
            recycler.visibility = View.GONE
            txtVazio.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            txtVazio.visibility = View.GONE
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = LogsAdapter(itens)
        }
    }

    private fun confirmarLimpar() {
        AlertDialog.Builder(this).setTitle(R.string.clear_old_logs)
            .setMessage(getString(R.string.hc_purge_logs_q))
            .setPositiveButton(R.string.confirm) { _, _ ->
                auditLogger.limparAntigo(90)
                atualizar()
                Toast.makeText(this, R.string.success, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun exportar() {
        val texto = auditLogger.exportarTexto()
        if (texto.isBlank()) {
            Toast.makeText(this, getString(R.string.hc_no_logs_export), Toast.LENGTH_SHORT).show()
            return
        }

        val nome = "auditoria_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, com.radioterapia.ai.branding.Marca.subpastaDocumentos("Logs"))
                }
                val uri: Uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
                    ?: throw Exception("Falha criar arquivo")
                contentResolver.openOutputStream(uri)?.use { it.write(texto.toByteArray()) }
                Toast.makeText(this, getString(R.string.ok_exported, nome), Toast.LENGTH_LONG).show()
            } else {
                @Suppress("DEPRECATION")
                val pasta = File(android.os.Environment.getExternalStorageDirectory(),
                    com.radioterapia.ai.branding.Marca.subpastaDocumentos("Logs"))
                if (!pasta.exists()) pasta.mkdirs()
                File(pasta, nome).writeText(texto)
                Toast.makeText(this, getString(R.string.ok_exported, nome), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.err_generic, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
