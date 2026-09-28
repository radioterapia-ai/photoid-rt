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

    /** Marca que o indicador já foi posicionado uma vez. */
    private val TAG_IND_POSTO = com.radioterapia.ai.R.id.tag_indicador_posto

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
     * O visto de conferência do OCR: confirmado ou pendente.
     *
     * CONFIRMAR É O MOMENTO QUE PRECISA DE RESPOSTA. O técnico está conferindo
     * nome, nascimento, prontuário e sexo lidos de uma etiqueta — quatro toques
     * seguidos, cada um deles uma afirmação clínica. Trocar só a cor dava um
     * sinal fraco demais para o peso do ato, e foi a reclamação que trouxe esta
     * mudança.
     *
     * O TRAÇO SE DESENHA em vez de aparecer pronto: o visto surge como quem o
     * escreve, o que faz a confirmação parecer consequência do toque. Quando o
     * movimento está desligado no aparelho, o mesmo visto aparece inteiro e
     * verde na mesma hora — o estado nunca depende da animação.
     *
     * DESFAZER volta ao ícone apagado sem animar. Animar a saída faria o desfazer
     * parecer tão comemorativo quanto o confirmar.
     */
    fun vistoConfirmado(botao: android.widget.ImageView?, confirmado: Boolean) {
        val b = botao ?: return
        if (!confirmado) {
            b.setImageResource(com.radioterapia.ai.R.drawable.ic_check_confirm)
            b.setColorFilter(androidx.core.content.ContextCompat.getColor(
                b.context, com.radioterapia.ai.R.color.confirm_pendente))
            b.alpha = 0.5f
            return
        }
        b.alpha = 1f
        if (!ligado(b.context)) {
            b.setImageResource(com.radioterapia.ai.R.drawable.ic_check_confirm)
            b.setColorFilter(androidx.core.content.ContextCompat.getColor(
                b.context, com.radioterapia.ai.R.color.confirm_green))
            return
        }
        // O desenho animado já traz a cor certa; o filtro de cor do estado
        // pendente precisa sair, senão ele pinta o traço por cima.
        b.clearColorFilter()
        b.setImageResource(com.radioterapia.ai.R.drawable.anim_check_sucesso)
        (b.drawable as? android.graphics.drawable.Animatable)?.let {
            try { it.stop(); it.start() } catch (_: Throwable) { }
        }
    }

    /**
     * O campo reprovado treme, e volta sozinho ao normal.
     *
     * POR QUE TREMER, E NAO SO PINTAR DE VERMELHO. O erro de validação aqui
     * aparece num toast, que sai da tela em segundos e não diz QUAL campo. Num
     * formulário com seis campos, o técnico lê "preencha o prontuário" e ainda
     * precisa procurar onde. O tremor aponta.
     *
     * AMPLITUDE DECRESCENTE (14 → 10 → 5 → 0): é como um objeto físico perde
     * energia. Tremor de amplitude constante parece falha de renderização, não
     * recusa.
     *
     * NÃO PINTA NADA. A cor de erro é responsabilidade de quem chama, porque
     * alguns campos têm fundo próprio e um tint aqui os deixaria com duas
     * camadas de vermelho. Esta função só chama atenção para o lugar.
     *
     * Com movimento desligado, o campo recebe foco em vez de tremer — o mesmo
     * trabalho de apontar, pelo caminho que continua disponível.
     */
    fun sacudirErro(campo: View?) {
        val v = campo ?: return
        if (!ligado(v.context)) {
            v.requestFocus()
            return
        }
        val d = v.resources.displayMetrics.density
        android.animation.ObjectAnimator.ofFloat(
            v, "translationX",
            0f, 14f * d, -14f * d, 10f * d, -10f * d, 5f * d, -5f * d, 0f
        ).apply {
            duration = 420
            interpolator = android.view.animation.DecelerateInterpolator()
            start()
        }
    }

    /**
     * Desliza o indicador até a aba escolhida.
     *
     * SÓ O INDICADOR SE MOVE, e o conteúdo troca sem deslizar. O que está
     * embaixo das abas é o visor da câmera: arrastá-lo lateralmente faria o
     * `PreviewView` piscar e custaria quadros na hora exata em que o técnico
     * está mirando o paciente. O indicador entrega a leitura espacial — de onde
     * vim, para onde fui — sem tocar no caminho da imagem.
     *
     * A LARGURA É CALCULADA, não fixada: divide a barra pelo número de abas que
     * existirem. No dia em que uma categoria entrar ou sair, o indicador
     * acompanha sem ninguém lembrar de ajustar um `dimen`.
     *
     * Na primeira passada não anima — posiciona. Deslizar da borda esquerda até
     * a aba certa quando a tela abre pareceria carregamento, não navegação.
     */
    fun deslizarIndicador(indicador: View?, barra: ViewGroup?, indice: Int) {
        val ind = indicador ?: return
        val b = barra ?: return
        val abas = b.childCount
        if (abas <= 0 || indice < 0) return
        b.post {
            val largura = b.width / abas
            if (largura <= 0) return@post
            if (ind.layoutParams.width != largura) {
                ind.layoutParams = ind.layoutParams.apply { width = largura }
                ind.requestLayout()
            }
            val destino = (largura * indice).toFloat()
            val primeiraVez = ind.getTag(TAG_IND_POSTO) == null
            ind.setTag(TAG_IND_POSTO, true)
            if (primeiraVez || !ligado(ind.context)) {
                ind.translationX = destino
            } else {
                ind.animate().translationX(destino).setDuration(PADRAO)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    /**
     * Liga um interruptor de alerta à cor que ele produz na ficha impressa.
     *
     * A LINHA INTEIRA ACENDE, não só o interruptor. As três cores são as mesmas
     * que o `PdfBuilder` usa nas tarjas do cabeçalho — amarelo para risco de
     * queda, laranja para precaução de contato, vermelho para alergia — e a
     * correspondência é o ponto: quem liga o interruptor vê na hora a tarja que
     * vai sair impressa, em vez de descobrir depois de imprimir.
     *
     * A COR ENTRA COM TRANSPARÊNCIA sobre o fundo escuro da tela. Em cheio, o
     * amarelo da tarja impressa apagaria o texto branco da linha; a ficha é
     * papel branco, esta tela não é, e a mesma cor não se comporta igual nos
     * dois lugares.
     */
    fun toggleAlerta(
        linha: View?,
        interruptor: android.widget.CompoundButton?,
        corAlerta: Int,
    ) {
        val l = linha ?: return
        val sw = interruptor ?: return
        fun pintar(ligado: Boolean) {
            val cor = if (ligado) (corAlerta and 0x00FFFFFF) or 0x40000000 else 0
            if (!ligado(l.context)) { l.setBackgroundColor(cor); return }
            val de = (l.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0
            android.animation.ValueAnimator.ofArgb(de, cor).apply {
                duration = PADRAO
                addUpdateListener { l.setBackgroundColor(it.animatedValue as Int) }
                start()
            }
        }
        pintar(sw.isChecked)
        sw.setOnCheckedChangeListener { _, marcado -> pintar(marcado) }
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
