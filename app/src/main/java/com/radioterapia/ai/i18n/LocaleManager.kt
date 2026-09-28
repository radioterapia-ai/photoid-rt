package com.radioterapia.ai.i18n

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/**
 * Gerencia o idioma do app.
 *
 * Doze idiomas: pt, en, es, fr, it, de, pl, zh, ja, ko, ar, bn — cada um com o
 * seu `res/values-XX/strings.xml`. Para estender, some o código a
 * [supportedLanguages] E crie a pasta: sem ela o build para no lint.
 *
 * O idioma escolhido é persistido em SharedPreferences e aplicado em todas as
 * Activities via BaseActivity.attachBaseContext + RadioterapiaApp.attachBaseContext.
 *
 * PADRÃO E INICIAL SÃO COISAS DIFERENTES, e confundi-los já custou caro em
 * outro projeto da casa. *Padrão* é o idioma em que o app está escrito;
 * *inicial* é o idioma em que a tela abre na primeira vez. Aqui o inicial é
 * resolvido em [resolverIdiomaInicial], no código — mover pasta de recurso não
 * mudaria nada, porque esta classe decide antes de o Android escolher.
 *
 * ÁRABE é escrito da direita para a esquerda. Quem faz a inversão do layout é o
 * Android, a partir de `supportsRtl` no manifesto — os layouts do app já usam
 * Start/End em vez de Left/Right (97 atributos, nenhum fixo), então a inversão
 * sai correta sem alterar layout nenhum. O PDF é exceção conhecida: ele é
 * desenhado com coordenadas absolutas e continua com a estrutura da esquerda
 * para a direita, ainda que o texto árabe seja moldado corretamente.
 */
object LocaleManager {

    const val LANG_PT = "pt"
    const val LANG_EN = "en"
    const val LANG_ES = "es"
    const val LANG_FR = "fr"
    const val LANG_DE = "de"
    const val LANG_IT = "it"
    const val LANG_PL = "pl"
    const val LANG_JA = "ja"
    const val LANG_ZH = "zh"
    const val LANG_KO = "ko"
    const val LANG_HI = "hi"
    const val LANG_AR = "ar"
    const val LANG_BN = "bn"

    /**
     * Os 12 idiomas oferecidos na seleção, na ordem em que aparecem na lista.
     *
     * A ORDEM NÃO É ALFABÉTICA de propósito: português vem primeiro por ser o
     * padrão, depois inglês e espanhol (os primeiros a existir), e em seguida os
     * demais agrupados por família — românicas, germânica, eslava, asiáticas,
     * árabe, bengali. Quem procura o próprio idioma varre uma lista de doze
     * itens, e agrupar por família encurta essa varredura.
     *
     * ESTA LISTA É O CONTRATO. O lint trata `MissingTranslation` como erro, então
     * um código aqui sem o `values-XX/strings.xml` correspondente quebra o build
     * — o que é desejado: idioma oferecido e não traduzido cairia em português
     * no meio da tela, sem aviso nenhum.
     *
     * OS DOZE ESTÃO COMPLETOS desde 09/2026 — francês, alemão, coreano e
     * polonês foram os últimos a fechar. Idioma só entra nesta lista quando o
     * `values-XX/strings.xml` existe: código listado sem tradução mostraria
     * português no meio de uma tela em coreano, e o lint (`MissingTranslation`
     * = erro) nem deixa compilar.
     *
     * `LANG_HI` (híndi) tem constante, locale e nome prontos, e **não** está na
     * lista: falta a pasta, e o site anuncia doze.
     */
    val supportedLanguages = listOf(
        LANG_PT, LANG_EN, LANG_ES,
        LANG_FR, LANG_IT, LANG_DE, LANG_PL,
        LANG_ZH, LANG_JA, LANG_KO,
        LANG_AR, LANG_BN,
    )

    /**
     * Código do idioma -> Locale.
     *
     * PÚBLICA porque [com.radioterapia.ai.util.DateUtils] precisa do mesmo
     * mapeamento para formatar a data de nascimento. Duas tabelas de idioma no
     * projeto divergiriam na primeira vez que só uma fosse atualizada — e a
     * divergência apareceria numa data impressa em outro idioma, longe daqui.
     */
    fun localeDe(lang: String): Locale = when (lang) {
        LANG_EN -> Locale.ENGLISH
        LANG_ES -> Locale("es", "ES")
        LANG_FR -> Locale.FRENCH
        LANG_DE -> Locale.GERMAN
        LANG_IT -> Locale.ITALIAN
        LANG_PL -> Locale("pl", "PL")
        LANG_JA -> Locale.JAPANESE
        LANG_ZH -> Locale.SIMPLIFIED_CHINESE
        LANG_KO -> Locale.KOREAN
        LANG_HI -> Locale("hi", "IN")
        LANG_AR -> Locale("ar")
        LANG_BN -> Locale("bn", "BD")
        else -> Locale("pt", "BR")
    }

    private const val PREFS = "locale_prefs"
    private const val KEY_LANG = "lang"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Aplica o idioma persistido (ou pt-BR padrão) ao context. Chamado por todas as
     * Activities via attachBaseContext.
     */
    /**
     * O idioma em que o app abre quando o usuário ainda não escolheu.
     *
     * REGRA: o do aparelho, se estiver entre os doze; senão, INGLÊS. Um tablet
     * configurado em alemão não deve abrir em português só porque o português é
     * a língua em que o app foi escrito.
     *
     * SALVAGUARDA PARA QUEM JÁ USA: instalação que já tem configuração gravada
     * continua em português. Ninguém deve ver a tela trocar de idioma depois de
     * uma atualização — a mudança seria silenciosa, e quem estranhasse não teria
     * como saber que foi o app que mudou de critério, não o tablet.
     */
    private fun resolverIdiomaInicial(context: Context): String {
        val jaEmUso = try {
            context.getSharedPreferences("config_radioterapia", Context.MODE_PRIVATE)
                .all.isNotEmpty()
        } catch (_: Exception) { false }
        if (jaEmUso) return LANG_PT

        val doSistema = try {
            val cfg = android.content.res.Resources.getSystem().configuration
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                cfg.locales[0]
            else @Suppress("DEPRECATION") cfg.locale
        } catch (_: Exception) { null }

        val codigo = doSistema?.language?.lowercase(Locale.US).orEmpty()
        return if (codigo in supportedLanguages) codigo else LANG_EN
    }

    /**
     * O idioma efetivo, resolvendo e GRAVANDO na primeira vez.
     *
     * Grava para a escolha ficar estável: sem isso, trocar o idioma do Android
     * trocaria o do app pelas costas de quem já tinha se acostumado com ele. A
     * partir daqui a fonte única é a preferência, e a tela de Configurações é
     * quem a muda.
     */
    fun idiomaEfetivo(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_LANG, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val novo = resolverIdiomaInicial(context)
        try { p.edit().putString(KEY_LANG, novo).commit() } catch (_: Exception) { }
        return novo
    }

    fun applyLocale(context: Context): Context {
        val lang = idiomaEfetivo(context)
        val locale = localeDe(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun obterIdiomaAtual(context: Context): String = idiomaEfetivo(context)

    /** Define o idioma manualmente. Após chamar, recomenda-se reiniciar a Activity. */
    /** Define o idioma manualmente. Usa commit() (síncrono) porque o app é
     *  reiniciado logo após salvar — apply() assíncrono poderia se perder no exit(0). */
    fun definirIdioma(context: Context, lang: String) {
        val finalLang = if (lang in supportedLanguages) lang else LANG_PT
        prefs(context).edit().putString(KEY_LANG, finalLang).commit()
    }

    fun nomeDoIdioma(lang: String): String = when (lang) {
        LANG_EN -> "🇺🇸 English"
        LANG_ES -> "🇪🇸 Español"
        LANG_FR -> "🇫🇷 Français"
        LANG_DE -> "🇩🇪 Deutsch"
        LANG_IT -> "🇮🇹 Italiano"
        LANG_PL -> "🇵🇱 Polski"
        LANG_JA -> "🇯🇵 日本語"
        LANG_ZH -> "🇨🇳 中文"
        LANG_KO -> "🇰🇷 한국어"
        LANG_HI -> "🇮🇳 हिन्दी"
        LANG_AR -> "🇸🇦 العربية"
        LANG_BN -> "🇧🇩 বাংলা"
        else -> "🇧🇷 Português"
    }

    // ===== Compatibilidade com chamadas antigas =====
    fun isAuto(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false
    fun obterIdiomaConfigurado(context: Context): String = obterIdiomaAtual(context)
    @Suppress("UNUSED_PARAMETER")
    fun definirAuto(context: Context, auto: Boolean) { /* sem efeito - removido o modo automático */ }
    fun definirIdiomaManual(context: Context, lang: String) = definirIdioma(context, lang)
    /**
     * Só a bandeira, extraída de [nomeDoIdioma].
     *
     * DERIVADA, e não uma segunda tabela: a versão anterior tinha um `when`
     * próprio que conhecia três idiomas e devolvia a bandeira do Brasil para
     * todos os outros. Com doze idiomas, duas tabelas divergem — esta sempre
     * concorda com o nome mostrado ao lado.
     */
    fun bandeiraDoIdioma(lang: String): String =
        nomeDoIdioma(lang).substringBefore(' ')
}
