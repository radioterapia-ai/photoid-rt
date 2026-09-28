package com.radioterapia.ai.update

import android.content.Context
import android.content.Intent
import android.os.Build
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.BuildConfig
import java.io.File

/**
 * Quem DECIDE sobre atualização. Não tem rede, e é por isso que existe separado
 * do [AtualizacaoRemota]: as regras abaixo são o que erra na prática — comparar
 * versão por nome, avisar para sempre sobre a versão que já foi adiada, oferecer
 * instalação que o aparelho vai recusar — e regra que se testa é regra que não
 * volta.
 */
object GerenciadorAtualizacao {

    /** De quanto em quanto tempo vale perguntar ao repositório. Seis horas: o
     *  bastante para um tablete ligado o dia inteiro perguntar duas ou três
     *  vezes, e pouco o bastante para não perguntar a cada tela aberta. */
    const val INTERVALO_CHECAGEM_MS = 6L * 60 * 60 * 1000

    // ==================== as decisões, sem Context ====================

    /**
     * Cabe instalar a versão publicada neste aparelho?
     *
     * O `minSdk` da publicação entra na conta de propósito. Se uma versão futura
     * subir o mínimo, o tablete antigo NÃO pode receber o convite: o download
     * terminaria num erro do instalador que o técnico não tem como resolver, e
     * ele passaria a desconfiar do aviso. Melhor nunca oferecer.
     *
     * A comparação é por versionCode, número, nunca por nome. "4.10" < "4.9" em
     * ordem de texto, e é assim que uma checagem de versão para de funcionar
     * exatamente quando o projeto passa da nona versão menor.
     */
    fun cabeAtualizar(instalado: Int, publicado: Int, minSdkPublicado: Int,
                      sdkDoAparelho: Int): Boolean =
        publicado > instalado && minSdkPublicado <= sdkDoAparelho

    /**
     * Cabe AVISAR — bolinha vermelha e convite ao abrir as Configurações?
     *
     * Só avisa sobre versão mais nova do que a instalada E mais nova do que a
     * que o usuário mandou esperar. Guardar «dispensou» como número, e não como
     * sim/não, é o que faz «depois» silenciar uma versão em vez de silenciar a
     * função.
     */
    fun deveAvisar(instalado: Int, publicado: Int, dispensado: Int): Boolean =
        publicado > instalado && publicado > dispensado

    /** Já passou tempo bastante desde a última pergunta? */
    fun horaDeChecar(agora: Long, ultima: Long,
                     intervalo: Long = INTERVALO_CHECAGEM_MS): Boolean =
        ultima <= 0L || agora - ultima >= intervalo || agora < ultima

    // ==================== com Context ====================

    /** O versionCode desta instalação. */
    fun versaoInstalada(): Int = BuildConfig.VERSION_CODE

    /** Há aviso pendente para pintar a bolinha e abrir o convite? */
    fun avisoPendente(context: Context): Boolean {
        val cfg = AppConfig(context)
        return deveAvisar(versaoInstalada(), cfg.atualizacaoCode, cfg.atualizacaoDispensada)
    }

    /**
     * Pergunta ao repositório e GRAVA o que achou. Devolve o que achou, ou null.
     *
     * Chamar isto na thread principal travaria a tela: quem chama põe numa
     * corrotina de IO. A gravação é em preferências, então voltar para a UI só
     * é necessário para pintar a bolinha.
     *
     * `forcado` ignora o intervalo — é o caminho do botão «Checar», onde existe
     * um humano esperando resposta agora.
     */
    fun checarEGravar(context: Context, forcado: Boolean = false): AtualizacaoRemota.Publicada? {
        val cfg = AppConfig(context)
        if (!forcado && !cfg.procurarAtualizacao) return null
        val agora = System.currentTimeMillis()
        if (!forcado && !horaDeChecar(agora, cfg.atualizacaoUltimaChecagem)) return null

        val pub = AtualizacaoRemota.checar()
        // A marca de tempo é gravada mesmo quando a pergunta falha. Sem isso,
        // um tablete sem internet tentaria a cada abertura de tela — o que não
        // quebra nada, mas gasta bateria para nada numa intranete onde a
        // resposta vai continuar sendo a mesma.
        cfg.atualizacaoUltimaChecagem = agora
        if (pub == null) return null

        if (cabeAtualizar(versaoInstalada(), pub.versionCode, pub.minSdk, Build.VERSION.SDK_INT)) {
            cfg.atualizacaoCode = pub.versionCode
            cfg.atualizacaoNome = pub.versionName
        } else {
            // Publicação igual ou anterior à instalada: limpa o que houvesse
            // gravado, senão uma bolinha vermelha sobrevive à própria
            // atualização que a resolveu.
            cfg.atualizacaoCode = 0
            cfg.atualizacaoNome = ""
        }
        return pub
    }

    /**
     * O aparelho permite que ESTE app instale pacote?
     *
     * Guardado por `SDK_INT`, não por try/catch: `canRequestPackageInstalls`
     * nasceu na API 26, e chamá-la na 24 lança `NoSuchMethodError`, que é
     * `Error` e passa por baixo de `catch (Exception)`. Isto já derrubou a
     * captura de foto em tablet API 24–29 neste app: com minSdk 24, todo erro
     * NewApi do lint é um fechamento em potencial.
     */
    fun podeInstalar(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.packageManager.canRequestPackageInstalls()
        else true

    /** A tela do sistema onde se libera «instalar apps desconhecidos». */
    fun intentLiberarInstalacao(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}"))
        else null

    /** Onde o APK baixado espera. Em cacheDir: se a instalação não acontecer, o
     *  sistema recolhe o espaço sozinho. */
    fun arquivoApk(context: Context): File =
        File(File(context.cacheDir, "atualizacao").apply { mkdirs() }, "PhotoID_RT.apk")

    /**
     * Entrega o APK ao instalador do Android.
     *
     * O sistema mostra o diálogo DELE e o usuário confirma. Não existe caminho
     * silencioso para app que não é do sistema nem administrador do aparelho, e
     * fingir que existiria seria prometer o que a plataforma não dá.
     */
    fun instalar(context: Context, apk: File): Boolean = try {
        if (!apk.exists() || apk.length() <= 0L) false
        else {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }
    } catch (_: Throwable) {
        false
    }
}
