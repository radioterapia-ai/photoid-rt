package com.radioterapia.ai.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radioterapia.ai.R
import com.radioterapia.ai.patient.PatientCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.View

/**
 * Adapter da lista do histórico de pacientes.
 *
 * Cada item mostra:
 *  - Thumbnail da foto-rosto (item 12/13) - se houver caminho salvo no PatientCache
 *  - Nome do paciente em destaque
 *  - Prontuário, data de nascimento e data da última simulação
 *  - Contagem de simulações como badge
 *
 * O thumbnail é decodificado com inSampleSize para economizar memória já que cada
 * item é pequeno (64dp). Se a foto não existir ou der erro, usa fallback icon_circle.
 */
class HistoricoAdapter(
    pacientes: List<PatientCache.DadosPaciente>,
    private val patientCache: PatientCache,
    private val onPdfClick: ((PatientCache.DadosPaciente, ImageView) -> Unit)? = null,
    private val onPrintClick: ((PatientCache.DadosPaciente) -> Unit)? = null,
    private val tratLabel: String? = null,
    private val tratCorRes: Int = R.color.brand_primary,
    private val onTratClick: ((PatientCache.DadosPaciente) -> Unit)? = null,
    private val tratLabelProvider: ((PatientCache.DadosPaciente) -> Pair<String, Int>)? = null,
    private val onClick: (PatientCache.DadosPaciente) -> Unit
) : RecyclerView.Adapter<HistoricoAdapter.PacienteVH>() {

    // Lista de trabalho (filtrável). A original fica guardada para refiltrar.
    private val todos = pacientes.toList()
    private val itens = pacientes.toMutableList()

    // ---------- Modo LOTE (seleção múltipla para impressão) ----------
    /** Em modo lote a coluna de ações vira checkbox e o toque marca/desmarca. */
    private var modoLote = false
    /** Chave estável do paciente (nome+prontuário) — sobrevive a refiltragem. */
    private val selecionados = linkedSetOf<String>()
    /** Avisa a tela quantos estão marcados, para atualizar o cabeçalho. */
    var aoMudarSelecao: ((Int) -> Unit)? = null

    private fun chaveDe(p: PatientCache.DadosPaciente) = "${p.nome}|${p.prontuario}"

    fun entrarModoLote() {
        if (modoLote) return
        modoLote = true; selecionados.clear()
        notifyDataSetChanged(); aoMudarSelecao?.invoke(0)
    }

    fun sairModoLote() {
        if (!modoLote) return
        modoLote = false; selecionados.clear()
        notifyDataSetChanged(); aoMudarSelecao?.invoke(0)
    }

    fun estaEmModoLote() = modoLote

    /** Marca ou desmarca TODOS os itens visíveis (respeita o filtro em uso). */
    fun alternarTodos() {
        val visiveis = itens.map { chaveDe(it) }
        if (selecionados.containsAll(visiveis)) selecionados.removeAll(visiveis.toSet())
        else selecionados.addAll(visiveis)
        notifyDataSetChanged(); aoMudarSelecao?.invoke(selecionados.size)
    }

    /** Pacientes marcados, na ordem em que aparecem na lista. */
    fun selecionados(): List<PatientCache.DadosPaciente> =
        todos.filter { chaveDe(it) in selecionados }

    /** Filtra por trecho do nome OU prontuário (case/acento-insensível). */
    fun filtrar(query: String) {
        val q = com.radioterapia.ai.util.StorageLocal.chaveNome(query)
        itens.clear()
        if (q.isBlank()) itens.addAll(todos)
        else itens.addAll(todos.filter {
            com.radioterapia.ai.util.StorageLocal.chaveNome(it.nome).contains(q) ||
            com.radioterapia.ai.util.StorageLocal.chaveNome(it.prontuario).contains(q)
        })
        notifyDataSetChanged()
    }

    /** Itens atualmente exibidos (após filtro) — útil para "se sobrar 1, abrir direto". */
    fun itensVisiveis(): List<PatientCache.DadosPaciente> = itens.toList()

    inner class PacienteVH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val imgThumb: ImageView = view.findViewById(R.id.imgPacienteThumb)
        val txtNome: TextView = view.findViewById(R.id.txtNomePaciente)
        val txtRecord: TextView = view.findViewById(R.id.txtRecord)
        val txtBirth: TextView = view.findViewById(R.id.txtBirth)
        val txtLast: TextView = view.findViewById(R.id.txtLast)
        val txtCount: TextView = view.findViewById(R.id.txtCount)
        val btnPdf: android.widget.ImageButton = view.findViewById(R.id.btnHistPdf)
        val btnPrint: android.widget.ImageButton = view.findViewById(R.id.btnHistPrint)
        val btnTrat: android.widget.Button = view.findViewById(R.id.btnHistTrat)
        val chkSelecao: android.widget.CheckBox = view.findViewById(R.id.chkHistSelecao)
        val colAcoes: android.view.View = view.findViewById(R.id.colHistAcoes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PacienteVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_historico, parent, false)
        return PacienteVH(v)
    }

    override fun onBindViewHolder(holder: PacienteVH, position: Int) {
        val p = itens[position]
        val ctx = holder.itemView.context

        holder.txtNome.text = p.nome
        holder.txtRecord.text = ctx.getString(R.string.patient_record,
            p.prontuario.ifBlank { "—" })
        holder.txtBirth.text = ctx.getString(R.string.patient_birth,
            com.radioterapia.ai.util.DateUtils.formatarNascimento(
                p.nascimento, com.radioterapia.ai.i18n.LocaleManager.obterIdiomaAtual(ctx)
            ).ifBlank { "—" })
        holder.txtCount.text = ctx.resources.getQuantityString(
            R.plurals.simulations_count_plural, p.simulacoes, p.simulacoes)

        // Modo LOTE: a coluna de ações (contagem, PDF, imprimir, tag) some e o
        // checkbox assume; o toque na linha passa a marcar em vez de abrir.
        holder.colAcoes.visibility = if (modoLote) View.GONE else View.VISIBLE
        holder.chkSelecao.visibility = if (modoLote) View.VISIBLE else View.GONE
        holder.chkSelecao.isChecked = chaveDe(p) in selecionados

        holder.txtLast.text = if (p.ultimaSimulacao > 0) {
            val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            ctx.getString(R.string.last_simulation, fmt.format(Date(p.ultimaSimulacao)))
        } else ""

        carregarThumbnail(holder.imgThumb, p.nome, p.prontuario)
        holder.itemView.setOnClickListener {
            if (modoLote) {
                val k = chaveDe(p)
                if (k in selecionados) selecionados.remove(k) else selecionados.add(k)
                holder.chkSelecao.isChecked = k in selecionados
                aoMudarSelecao?.invoke(selecionados.size)
            } else onClick(p)
        }

        // Ícone de PDF: aparece quando há um manipulador (modo seleção do tratamento).
        if (onPdfClick != null) {
            holder.btnPdf.visibility = android.view.View.VISIBLE
            holder.btnPdf.setOnClickListener { onPdfClick.invoke(p, holder.imgThumb) }
        } else {
            holder.btnPdf.visibility = android.view.View.GONE
            holder.btnPdf.setOnClickListener(null)
        }

        if (onPrintClick != null) {
            holder.btnPrint.visibility = android.view.View.VISIBLE
            holder.btnPrint.setOnClickListener { onPrintClick.invoke(p) }
        } else {
            holder.btnPrint.visibility = android.view.View.GONE
            holder.btnPrint.setOnClickListener(null)
        }

        // Botão Alta (no tratamento) ou Tratar/Em tratamento (no histórico)
        val prov = tratLabelProvider?.invoke(p)
        val label = prov?.first ?: tratLabel
        val cor = prov?.second ?: tratCorRes
        if (onTratClick != null && label != null) {
            holder.btnTrat.visibility = android.view.View.VISIBLE
            holder.btnTrat.text = label
            holder.btnTrat.backgroundTintList =
                android.content.res.ColorStateList.valueOf(ctx.getColor(cor))
            holder.btnTrat.setOnClickListener { onTratClick.invoke(p) }
        } else {
            holder.btnTrat.visibility = android.view.View.GONE
            holder.btnTrat.setOnClickListener(null)
        }
    }

    /**
     * Carrega thumbnail subsampleado da foto-rosto. Fallback: ícone do app.
     */
    private fun carregarThumbnail(imgView: ImageView, nomePaciente: String,
                                  prontuario: String) {
        // Prontuario desempata homonimas: sem ele, duas pacientes de mesmo nome
        // dividiam a mesma miniatura.
        val caminho = patientCache.obterOuEncontrarFotoRosto(
            imgView.context, nomePaciente, prontuario)
        if (caminho.isNullOrBlank() || !File(caminho).exists()) {
            imgView.setImageResource(R.drawable.icon_circle)
            return
        }
        try {
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(caminho, probe)
            var sample = 1
            while (probe.outWidth / sample > 200 && probe.outHeight / sample > 200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bm = BitmapFactory.decodeFile(caminho, opts)
            if (bm != null) imgView.setImageBitmap(bm)
            else imgView.setImageResource(R.drawable.icon_circle)
        } catch (_: Exception) {
            imgView.setImageResource(R.drawable.icon_circle)
        }
    }

    override fun getItemCount(): Int = itens.size
}
