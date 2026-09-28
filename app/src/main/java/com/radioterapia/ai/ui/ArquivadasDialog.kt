package com.radioterapia.ai.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.radioterapia.ai.R
import com.radioterapia.ai.util.FotosArquivadas
import java.io.File

/**
 * Grade das fotos ARQUIVADAS do paciente, para devolvê-las ao carrossel.
 *
 * A foto arquivada saiu da ficha mas continua na pasta (ver [FotosArquivadas]).
 * Sem uma tela que a mostre, "arquivar" seria indistinguível de "apagar" para
 * quem usa o app — o arquivo existiria em disco e ninguém dentro da sala teria
 * como chegar nele.
 *
 * DEVOLVE A CADA TOQUE, sem seleção múltipla e sem botão de confirmar. O caso
 * comum é uma foto só — trocou o rosto, se arrependeu, quer a anterior de volta
 * — e um fluxo de marcar-e-confirmar custaria dois toques onde um basta. Quem
 * precisa de várias toca em várias: o diálogo continua aberto e a grade
 * atualiza.
 *
 * MINIATURA, e não nome de arquivo: o nome arquivado carrega carimbo de tempo e
 * não diz nada sobre o que a foto mostra. Quem escolhe reconhece a foto.
 */
object ArquivadasDialog {

    /**
     * @param bases pastas onde procurar. São duas porque a sessão em andamento
     *   guarda o arquivado na pasta de trabalho, e a simulação já gravada o
     *   guarda na pasta do paciente — quem edita uma simulação existente pode
     *   ter fotos nas duas.
     * @param aoRestaurar recebe a arquivada escolhida; devolver `true` mantém o
     *   diálogo aberto para a próxima.
     */
    fun mostrar(origem: Activity, bases: List<File>, aoRestaurar: (File) -> Boolean) {
        val fotos = bases.flatMap { FotosArquivadas.listar(it) }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }

        if (fotos.isEmpty()) {
            android.widget.Toast.makeText(origem, R.string.arq_vazio,
                android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val d = origem.resources.displayMetrics.density
        val restantes = fotos.toMutableList()

        val aviso = TextView(origem).apply {
            setText(R.string.arq_toque_para_voltar)
            textSize = 13f
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
        }
        val grade = GridView(origem).apply {
            numColumns = 3
            horizontalSpacing = (6 * d).toInt()
            verticalSpacing = (6 * d).toInt()
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (12 * d).toInt())
        }
        val caixa = LinearLayout(origem).apply {
            orientation = LinearLayout.VERTICAL
            addView(aviso); addView(grade)
        }

        val lado = (100 * d).toInt()
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = restantes.size
            override fun getItem(p: Int): Any = restantes[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, convert: View?, pai: android.view.ViewGroup): View {
                val img = (convert as? ImageView) ?: ImageView(origem).apply {
                    layoutParams = android.widget.AbsListView.LayoutParams(lado, lado)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                val arq = restantes[p]
                img.contentDescription = arq.name
                // Subamostrada: a grade pode ter dezenas de fotos e carregar
                // cada JPEG inteiro estoura a memoria do tablet.
                val bmp = try {
                    val op = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeFile(arq.absolutePath, op)
                } catch (_: Throwable) { null }
                // decodeFile devolve NULL para arquivo corrompido, sem lancar
                // nada: sem esta checagem a celula ficaria em branco e pareceria
                // foto vazia em vez de foto ilegivel.
                if (bmp != null) img.setImageBitmap(bmp)
                else img.setImageResource(R.drawable.bg_sem_foto)
                return img
            }
        }
        grade.adapter = adapter

        val dialog = AlertDialog.Builder(origem)
            .setTitle(R.string.arq_titulo)
            .setView(caixa)
            .setNegativeButton(R.string.close, null)
            .create()

        grade.setOnItemClickListener { _, _, pos, _ ->
            val arq = restantes.getOrNull(pos) ?: return@setOnItemClickListener
            if (aoRestaurar(arq)) {
                restantes.removeAt(pos)
                adapter.notifyDataSetChanged()
                if (restantes.isEmpty()) dialog.dismiss()
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
        dialog.window?.setGravity(Gravity.CENTER)
    }
}
