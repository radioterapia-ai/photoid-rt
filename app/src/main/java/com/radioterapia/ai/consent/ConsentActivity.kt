package com.radioterapia.ai.consent

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.radioterapia.ai.HomeActivity
import com.radioterapia.ai.R
import com.radioterapia.ai.i18n.LocaleManager

/**
 * Tela de consentimento da LGPD (item 11).
 *
 * Aparece UMA ÚNICA VEZ no primeiro acesso ao app. Após aceitar:
 *  - Persiste em SharedPreferences "consent_prefs" os campos: accepted=true,
 *    accepted_at=timestamp, accepted_lang=idioma escolhido
 *  - Bota o aplicativo no fluxo normal (SplashActivity → HomeActivity)
 *
 * Tem 4 partes verticais:
 *  1) Spinner de idiomas (pt/en/es) no TOPO. Trocar o idioma reinicia a Activity para
 *     mostrar todos os textos no idioma escolhido — crítico pra usuário consentir num
 *     idioma que ele realmente entende.
 *  2) Título "Termos e Privacidade" + texto introdutório (consent_intro).
 *  3) Dois botões "Abrir Termos" e "Abrir Privacidade" que abrem AlertDialogs com o
 *     texto completo (terms_body / privacy_body, traduzidos em pt/en/es).
 *  4) Checkbox + botão "Aceitar e continuar" (desabilitado até marcar o checkbox).
 *
 * Toda a UI é construída programaticamente para não depender de XML/inflate em meio
 * a uma troca de locale (o que poderia mostrar string em idioma desencontrado).
 */
class ConsentActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    private lateinit var prefs: SharedPreferences

    companion object {
        /** Passos da primeira inicializacao: idioma, depois termos. */
        const val PASSO_IDIOMA = 1
        const val PASSO_TERMOS = 2
        const val EXTRA_PASSO = "consent_passo"

        const val PREFS = "consent_prefs"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_ACCEPTED_AT = "accepted_at"
        const val KEY_ACCEPTED_LANG = "accepted_lang"
        const val KEY_ACCEPTED_VERSION = "accepted_version"

        /**
         * Versão dos Termos e da Política. INCREMENTAR a cada mudança material
         * no que o usuário aceita — não a cada correção de vírgula.
         *
         * 1 → texto original.
         * 2 → 12/08/2026. Licença passou a Apache-2.0 (o texto anterior dizia
         *     "é vedada a reprodução, modificação e redistribuição", o oposto
         *     do que a licença concede); entrou a declaração de ferramenta de
         *     apoio não validada para uso clínico; entrou a autoria de
         *     terceiro do reconhecimento; a Política deixou de afirmar "100%
         *     offline", que não era exato, e passou a descrever o uso real de
         *     rede.
         */
        const val VERSAO_TERMOS = 2

        /**
         * O consentimento vigente cobre a versão ATUAL dos Termos?
         *
         * Antes bastava `accepted=true`, e quem já tinha aceitado nunca mais
         * veria os Termos — nem quando eles mudassem no essencial. Os próprios
         * Termos prometem, na cláusula de modificações, que alteração material
         * é comunicada no aplicativo; sem esta checagem a promessa era falsa.
         *
         * Registro anterior sem `accepted_version` é tratado como versão 1.
         */
        fun jaAceitou(ctx: Context): Boolean {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.getBoolean(KEY_ACCEPTED, false)) return false
            return p.getInt(KEY_ACCEPTED_VERSION, 1) >= VERSAO_TERMOS
        }
    }

    /**
     * Em qual das duas telas estamos.
     *
     * DUAS TELAS, E NAO UMA. Antes o seletor de idioma e o aceite dos termos
     * dividiam a mesma tela, e o efeito era que o texto legal aparecia em
     * portugues no instante em que o app abria — o usuario lia (ou pulava) a
     * declaracao clinica e a politica de privacidade antes de ter escolhido o
     * idioma que entende. Consentir e o unico ato juridico do app; ler primeiro
     * no idioma certo nao e detalhe de apresentacao.
     */
    private var passo = PASSO_IDIOMA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Trocar de idioma reinicia a Activity, e a volta cai sempre no passo 1.
        // O passo nao e persistido de proposito: reiniciar por troca de idioma
        // significa que o usuario ainda esta escolhendo.
        passo = intent.getIntExtra(EXTRA_PASSO, PASSO_IDIOMA)
        render()
    }

    /**
     * Voltar do passo 2 devolve a escolha de idioma, em vez de sair do app.
     *
     * Sem isto, quem chega aos termos e percebe que escolheu o idioma errado
     * so tem o botao do sistema, que fecha tudo.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (passo == PASSO_TERMOS) {
            passo = PASSO_IDIOMA
            render()
        } else {
            super.onBackPressed()
        }
    }

    private fun render() {
        setContentView(if (passo == PASSO_IDIOMA) construirUiIdioma() else construirUiTermos())
    }

    private fun molduraRolavel(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#00030E"))
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return scroll to root
    }

    /**
     * PASSO 1: so a marca e a escolha do idioma.
     *
     * Nada mais entra aqui. Qualquer texto a mais nesta tela estaria no idioma
     * errado para quem ainda vai escolher o dele — que e justamente o problema
     * que a divisao em duas telas resolve.
     */
    private fun construirUiIdioma(): View {
        val (scroll, root) = molduraRolavel()
        root.gravity = android.view.Gravity.CENTER_HORIZONTAL

        root.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo_radioterapia)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.app_name)
        }, LinearLayout.LayoutParams(dp(240), dp(96)).apply { topMargin = dp(48) })

        // Rotulo TRILINGUE: quem abre pela primeira vez ainda ve a tela no
        // idioma padrao, e precisa reconhecer o que fazer sem ler portugues.
        root.addView(TextView(this).apply {
            text = getString(R.string.select_language_trilingue)
            setTextColor(Color.parseColor("#B8C5D6"))
            textSize = 15f
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(48) })

        val codigos = LocaleManager.supportedLanguages
        val nomes = codigos.map { LocaleManager.nomeDoIdioma(it) }
        val atual = LocaleManager.obterIdiomaAtual(this)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ConsentActivity,
                android.R.layout.simple_spinner_dropdown_item, nomes)
            setSelection(codigos.indexOf(atual).coerceAtLeast(0))
        }
        var ignorarPrimeiraSelecao = true
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?,
                                         v: View?, pos: Int, id: Long) {
                if (ignorarPrimeiraSelecao) { ignorarPrimeiraSelecao = false; return }
                val novoLang = codigos[pos]
                if (novoLang != LocaleManager.obterIdiomaAtual(this@ConsentActivity)) {
                    LocaleManager.definirIdioma(this@ConsentActivity, novoLang)
                    // REINICIA a Activity: o idioma e aplicado em
                    // attachBaseContext, entao redesenhar sem reiniciar traria
                    // os textos do idioma anterior.
                    val it = Intent(this@ConsentActivity, ConsentActivity::class.java)
                    it.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    finish()
                    startActivity(it)
                    overridePendingTransition(0, 0)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        root.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        root.addView(Button(this).apply {
            text = getString(R.string.next)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            textSize = 17f
            setOnClickListener { passo = PASSO_TERMOS; render() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(68)).apply { topMargin = dp(40) })

        scroll.addView(root)
        return scroll
    }

    /** PASSO 2: os termos, ja no idioma escolhido no passo 1. */
    private fun construirUiTermos(): View {
        val (scroll, root) = molduraRolavel()

        // 2) Título + intro
        root.addView(TextView(this).apply {
            text = getString(R.string.terms_and_privacy)
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.consent_intro)
            setTextColor(Color.parseColor("#B8C5D6"))
            textSize = 16f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        // 2b) DECLARAÇÃO CLÍNICA, visível na própria tela.
        //
        // Não basta estar dentro do diálogo dos Termos: quem consente às pressas
        // não abre o diálogo, e este é o único ponto do app em que o usuário
        // declara ter entendido o que está usando. O que precisa ser lido fica
        // à vista; o texto completo continua a um toque.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#5A1F1F"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@ConsentActivity).apply {
                text = getString(R.string.aviso_clinico_titulo)
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@ConsentActivity).apply {
                text = getString(R.string.aviso_clinico_corpo)
                setTextColor(Color.parseColor("#E8D6D6"))
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(this@ConsentActivity).apply {
                text = getString(R.string.creditos_intro)
                setTextColor(Color.parseColor("#E8D6D6"))
                textSize = 13f
                setPadding(0, dp(10), 0, 0)
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        // 3) Botões pra abrir Termos / Privacidade
        val linhaBotoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnTermos = Button(this).apply {
            text = getString(R.string.open_terms)
            setOnClickListener { mostrarDialogo(getString(R.string.terms_title),
                                                 getString(R.string.terms_body)) }
        }
        val btnPriv = Button(this).apply {
            text = getString(R.string.open_privacy)
            setOnClickListener { mostrarDialogo(getString(R.string.privacy_title),
                                                 getString(R.string.privacy_body)) }
        }
        linhaBotoes.addView(btnTermos, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        linhaBotoes.addView(btnPriv, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(linhaBotoes, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        // 4) Checkbox + botão final
        val checkAceitar = CheckBox(this).apply {
            text = getString(R.string.i_agree)
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        root.addView(checkAceitar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })

        val btnAceitar = Button(this).apply {
            text = getString(R.string.accept_and_continue)
            isEnabled = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            textSize = 17f
        }
        checkAceitar.setOnCheckedChangeListener { _, marcado ->
            btnAceitar.isEnabled = marcado
            btnAceitar.alpha = if (marcado) 1f else 0.4f
        }
        btnAceitar.alpha = 0.4f
        btnAceitar.setOnClickListener {
            persistirAceite()
            // Primeiro aceite → inicia o onboarding; se o wizard já rodou, vai à home.
            val proximo = if (!com.radioterapia.ai.wizard.WizardActivity.jaConcluido(this))
                com.radioterapia.ai.wizard.WizardActivity::class.java
            else HomeActivity::class.java
            startActivity(Intent(this, proximo))
            finish()
        }
        root.addView(btnAceitar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(68)).apply {
            topMargin = dp(16)
        })

        scroll.addView(root)
        return scroll
    }

    private fun persistirAceite() {
        prefs.edit()
            .putBoolean(KEY_ACCEPTED, true)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .putString(KEY_ACCEPTED_LANG, LocaleManager.obterIdiomaAtual(this))
            // Guarda QUAL versão foi aceita. Sem isto não há como saber, num
            // aparelho já em uso, se o consentimento cobre o texto vigente —
            // e é essa informação que responde a uma auditoria.
            .putInt(KEY_ACCEPTED_VERSION, VERSAO_TERMOS)
            .apply()
    }

    private fun mostrarDialogo(titulo: String, corpo: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(corpo)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

}
