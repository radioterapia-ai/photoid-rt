package com.radioterapia.ai.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radioterapia.ai.R
import com.radioterapia.ai.session.SessionManager.Category
import com.radioterapia.ai.session.SessionManager.FotoCategorizada

class ThumbAdapter(
    private val itens: MutableList<FotoCategorizada>,
    private val onClick: (FotoCategorizada) -> Unit,
    private val onRemover: (FotoCategorizada) -> Unit
) : RecyclerView.Adapter<ThumbAdapter.ThumbVH>() {

    inner class ThumbVH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.thumbImage)
        val cat: TextView = view.findViewById(R.id.thumbCategory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false)
        return ThumbVH(v)
    }

    override fun onBindViewHolder(holder: ThumbVH, position: Int) {
        val item = itens[position]
        try {
            val opt = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bm = BitmapFactory.decodeFile(item.arquivo.absolutePath, opt)
            holder.img.setImageBitmap(bm)
        } catch (_: Exception) {
            holder.img.setImageResource(android.R.drawable.ic_menu_report_image)
        }

        holder.cat.text = when (item.categoria) {
            Category.FACE -> "R"
            Category.LABEL -> "E"
            Category.POSITIONING -> "P"
            Category.ACCESSORIES -> "A"
            Category.DOCUMENTS -> "D"
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onRemover(item); true }
    }

    override fun getItemCount(): Int = itens.size

    fun atualizar(novos: List<FotoCategorizada>) {
        itens.clear()
        itens.addAll(novos)
        notifyDataSetChanged()
    }
}
