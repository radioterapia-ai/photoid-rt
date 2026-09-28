package com.radioterapia.ai.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radioterapia.ai.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsAdapter(private val itens: List<JSONObject>) : RecyclerView.Adapter<LogsAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtTipo: TextView = view.findViewById(R.id.txtLogTipo)
        val txtTimestamp: TextView = view.findViewById(R.id.txtLogTs)
        val txtMsg: TextView = view.findViewById(R.id.txtLogMsg)
        val txtDetalhes: TextView = view.findViewById(R.id.txtLogDetalhes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val obj = itens[position]
        val tipo = obj.optString("tipo", "?")
        val ts = obj.optLong("ts")
        val msg = obj.optString("msg", "")

        holder.txtTipo.text = tipo
        holder.txtTipo.setBackgroundColor(corDoTipo(tipo))
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        holder.txtTimestamp.text = fmt.format(Date(ts))
        holder.txtMsg.text = msg

        val detalhes = StringBuilder()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in setOf("ts", "ts_iso", "tipo", "msg")) continue
            detalhes.append("$k: ${obj.optString(k)}  ")
        }
        if (detalhes.isNotBlank()) {
            holder.txtDetalhes.text = detalhes.toString().trim()
            holder.txtDetalhes.visibility = View.VISIBLE
        } else holder.txtDetalhes.visibility = View.GONE
    }

    private fun corDoTipo(tipo: String): Int = when (tipo) {
        "EDIT" -> Color.parseColor("#7B1FA2")        // roxo
        "SYNC_CSV" -> Color.parseColor("#1565C0")    // azul
        "DIVERGENCE" -> Color.parseColor("#F57C00")  // laranja
        "UPLOAD" -> Color.parseColor("#2E7D32")      // verde
        "PRINT" -> Color.parseColor("#1565C0")       // azul
        "FINISH" -> Color.parseColor("#2E7D32")      // verde escuro
        "ERROR" -> Color.parseColor("#C62828")       // vermelho
        else -> Color.parseColor("#757575")
    }

    override fun getItemCount(): Int = itens.size
}
