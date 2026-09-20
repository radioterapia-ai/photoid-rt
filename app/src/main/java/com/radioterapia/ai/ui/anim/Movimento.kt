package com.radioterapia.ai.ui.anim

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * As regras de movimento do app, num lugar só.
 *
 * ANIMAÇÃO DESLIGADA NO APARELHO VALE PARA NÓS. O Android deixa o usuário zerar
 * a escala de animação — em Opções do desenvolvedor, e também quando o sistema
 * entra em economia de bateria ou quando alguém ativa "reduzir movimento" por
 * sensibilidade vestibular. Ignorar isso é decidir pelo usuário numa tela que
 * ele usa com um paciente ao lado.
 *
 * Quando o movimento está desligado, nada some: o estado final aparece
 * imediatamente. Uma animação suprimida nunca pode custar informação — o visto
 * confirmado continua verde, o aviso continua aparecendo, o campo com erro
 * continua sinalizado. O que se perde é só o caminho até lá.
 */
object Movimento {

    /** Chave da tag onde o desaparecimento pendente fica guardado. */
    private val TAG_SAIDA = com.radioterapia.ai.R.id.tag_aviso_saida

    /** Durações, em milissegundos. Uma tabela só, para a casa inteira soar igual. */
    const val RAPIDO = 140L
    const val PADRAO = 240L
    const val CALMO = 420L

    /** O aviso da câmera: dois ciclos da pinça, depois desaparece. */
    const val AVISO_VISIVEL = 2900L
    const val AVISO_SAIDA = 420L

    /**
     * O aparelho aceita animação agora?
     *
     * Lê `ANIMATOR_DURATION_SCALE`, que é o mesmo interruptor que o próprio
     * Android consulta. Falha fechada para `true`: se a leitura der erro, é
     * melhor animar do que deixar a tela parecer travada.
     */
    fun ligado(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Throwable) { true }

    /**
     * Mostra o aviso, roda a animação e apaga sozinho.
     *
     * NÃO EMPILHA. Chamar de novo enquanto um aviso está no ar reinicia o tempo
     * em vez de agendar um segundo desaparecimento — sem isso, abrir e fechar a
     * revisão da foto deixaria dois temporizadores correndo e o aviso sumiria no
     * meio da segunda exibição.
     */
    fun mostrarAviso(aviso: View?, iconeAnimado: ImageView? = null) {
        val v = aviso ?: return
        v.animate().cancel()
        // Remove SÓ o desaparecimento anterior, guardado na tag. `removeCallbacks(null)`
        // varreria a fila da View inteira, inclusive callbacks de quem não é nosso.
        (v.getTag(TAG_SAIDA) as? Runnable)?.let { v.removeCallbacks(it) }
        v.alpha = 1f
        v.visibility = View.VISIBLE

        val anim = iconeAnimado?.drawable
        if (ligado(v.context) && anim is android.graphics.drawable.Animatable) {
            try { anim.stop(); anim.start() } catch (_: Throwable) { }
        }

        val semMovimento = !ligado(v.context)
        val sair = Runnable {
            if (semMovimento) {
                // Sem movimento: o aviso ainda aparece e ainda sai sozinho — só
                // não esmaece. Deixá-lo fixo seria trocar uma animação por um
                // estorvo permanente.
                v.visibility = View.GONE
            } else {
                v.animate().alpha(0f).setDuration(AVISO_SAIDA)
                    .withEndAction { v.visibility = View.GONE }
                    .start()
            }
        }
        v.setTag(TAG_SAIDA, sair)
        v.postDelayed(sair, AVISO_VISIVEL)
    }

    /** Esconde na hora, sem esperar o tempo do aviso. */
    fun esconderAviso(aviso: View?) {
        val v = aviso ?: return
        v.animate().cancel()
        (v.getTag(TAG_SAIDA) as? Runnable)?.let { v.removeCallbacks(it) }
        v.visibility = View.GONE
    }

    /**
     * Prepara um contêiner para animar mudanças de altura sozinho.
     *
     * Usado pelo accordion e pelos toggles. O `LayoutTransition` do Android faz
     * o trabalho sem nenhum animator escrito à mão, e respeita a escala do
     * sistema por conta própria.
     */
    fun animarMudancasDeLayout(grupo: ViewGroup?) {
        val g = grupo ?: return
        if (!ligado(g.context)) { g.layoutTransition = null; return }
        g.layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.CHANGING)
            setDuration(PADRAO)
        }
    }
}
