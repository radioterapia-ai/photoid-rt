package com.radioterapia.ai.treatment

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.radioterapia.ai.R

class CarrosselAdapter(
    private val fotos: List<TreatmentPhotoFetcher.FotoInfo>,
    private val rotulos: List<String>? = null
) : RecyclerView.Adapter<CarrosselAdapter.FotoVH>() {

    class FotoVH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.imgCarrosselFoto)
        val tag: TextView = view.findViewById(R.id.txtCarrosselTag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FotoVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_carrossel_foto, parent, false)
        return FotoVH(view)
    }

    override fun onBindViewHolder(holder: FotoVH, position: Int) {
        val foto = fotos[position]
        try {
            val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
            holder.img.setImageBitmap(BitmapFactory.decodeFile(foto.arquivoLocal.absolutePath, opt))
        } catch (_: Exception) {
            holder.img.setImageResource(android.R.drawable.ic_menu_report_image)
        }
        // Tag flutuante (Posicionamento.N / Acessório.N)
        val rotulo = rotulos?.getOrNull(position) ?: when (foto.tipo) {
            TreatmentPhotoFetcher.TipoFoto.ACESSORIOS ->
                holder.itemView.context.getString(com.radioterapia.ai.R.string.cat_accessories)
            TreatmentPhotoFetcher.TipoFoto.POSICIONAMENTO ->
                holder.itemView.context.getString(com.radioterapia.ai.R.string.cat_positioning)
            TreatmentPhotoFetcher.TipoFoto.DOCUMENTO ->
                holder.itemView.context.getString(com.radioterapia.ai.R.string.docs_viewer_label)
            else -> ""
        }
        if (rotulo.isBlank()) holder.tag.visibility = View.GONE
        else { holder.tag.visibility = View.VISIBLE; holder.tag.text = rotulo }
    }

    override fun getItemCount(): Int = fotos.size
}
