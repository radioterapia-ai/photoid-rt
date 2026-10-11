package com.radioterapia.ai.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.OneShotPreDrawListener
import com.radioterapia.ai.R
import com.radioterapia.ai.ui.anim.Movimento

/**
 * A abertura desenhada SOBRE o primeiro quadro da tela de destino.
 *
 * POR QUE NÃO UMA TELA PRÓPRIA. Com a assinatura numa Activity sua, a tela de
 * destino só começa a ser montada depois do desenho, e a assinatura fica parada
 * no último quadro enquanto ela monta — uma pausa depois do traço, que atrasa
 * a tela utilizável sem mostrar nada de novo. Sobreposta, a tela de destino
 * monta por baixo enquanto o traço se desenha: quando a assinatura sai, ela já
 * está pronta, e a abertura custa só o desenho.
 *
 * O GANCHO NA TELA DE DESTINO é uma linha no onCreate ([aplicar]) e não muda
 * nada dela: a camada é posta por cima do conteúdo e sai sozinha.
 *
 * A CAMADA VAI EM android.R.id.content, e não no decorView: o contêiner do
 * conteúdo já é a área útil — sem as barras do sistema, a barra de tarefas do
 * tablet e o recorte da tela —, então centralizar nele é centralizar no que se
 * vê. No decorView, a camada dependeria dos recuos que chegam a ele, e a
 * barra de tarefas do Android 14 não chega.
 *
 * SÓ EXISTE quando a SplashActivity pediu ([pedir]): voltar de outra tela,
 * retomar do segundo plano ou recriar por rotação não repetem.
 */
object AberturaSobreposta {

    private const val EXTRA = "com.radioterapia.ai.extra.ABERTURA"

    /** Marca o Intent que a SplashActivity manda à tela de destino. */
    fun pedir(intent: Intent): Intent = intent.putExtra(EXTRA, true)

    /**
     * Chamar no onCreate de cada tela que a SplashActivity pode abrir, depois
     * do último setContentView: a camada vai por cima do conteúdo que já está lá.
     */
    fun aplicar(activity: Activity, savedInstanceState: Bundle?) {
        val intent = activity.intent ?: return
        if (!intent.getBooleanExtra(EXTRA, false)) return
        // O pedido é consumido: a recriação desta tela reaproveita o Intent.
        intent.removeExtra(EXTRA)
        // Recriada, ou sem movimento no aparelho: a tela de destino já é o
        // estado final, e a rota é imediata. Nenhuma camada, nenhuma espera.
        if (savedInstanceState != null || !Movimento.ligado(activity)) return
        val raiz = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val camada = AssinaturaAberturaView(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.bg_main))
            // O toque não atravessa para a tela de baixo enquanto a assinatura
            // está no ar: um toque de passagem não aciona botão que não se via.
            isClickable = true
            contentDescription = activity.getString(R.string.app_name)
        }
        raiz.addView(camada, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // O tempo zero é o primeiro quadro desenhado, não o fim do onCreate.
        OneShotPreDrawListener.add(camada) {
            camada.iniciar {
                // Fora do despacho em curso: a saída pode vir de dentro de um
                // aviso de visibilidade, e tirar a View ali mexe na árvore que
                // está sendo percorrida.
                raiz.post {
                    raiz.removeView(camada)
                    // "Fully drawn" no logcat: a tela de destino utilizável, que
                    // é o que se mede na abertura.
                    try { activity.reportFullyDrawn() } catch (_: SecurityException) { }
                }
            }
        }
    }
}
