package com.radioterapia.ai.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.BuildConfig
import com.radioterapia.ai.patient.PatientCache
import com.radioterapia.ai.protocolo.ProtocoloStore
import com.radioterapia.ai.rubricario.RubricarioStore
import com.radioterapia.ai.treatment.TreatmentListManager
import com.radioterapia.ai.util.StorageLocal
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    /**
     * O formato de version.json que este app entende.
     *
     * GUARDA: só este formato é aceito, e o link do APK mora no campo `apkUrl`.
     * O campo antigo `apk` não é lido nem escrito. O leitor do formato anterior
     * exige `apk` e, sem ele, conclui que não há versão publicada — é isso que
     * tira o convite dos aparelhos com aquele leitor, cuja cópia antes de
     * atualizar levava o acervo de fotos inteiro, e os faz passar uma vez pelo
     * APK instalado à mão. Voltar a ler `apk` «por compatibilidade» não traria
     * nada e apagaria a razão de o campo ter mudado de nome.
     *
     * Subir este número faz o mesmo com ESTE leitor: todo aparelho com ele para
     * de ver versão nova. Campo novo e opcional não pede formato novo.
     */
    const val FORMATO_VERSAO = 2

    /**
     * Por quanto tempo o marcador gravado antes de instalar ainda descreve a
     * instalação. Três dias: cobre um fim de semana entre o «Concluído» do
     * instalador e a primeira abertura. Depois disso as contagens gravadas já
     * não são as do momento da troca — altas do tratamento e exclusões as
     * baixam no uso normal —, e compará-las acusaria perda que não houve.
     */
    const val VALIDADE_MARCADOR_MS = 3L * 24 * 60 * 60 * 1000

    /**
     * Por quanto tempo, depois de gravado o marcador, a versão de partida ainda
     * está entregando o APK ao instalador. A tela que gravou sai da frente logo
     * em seguida — é o instalador abrindo —, e isso não é a versão antiga
     * voltando a ser usada. Passado este tempo, vê-la rodando quer dizer que a
     * instalação ainda não aconteceu: foi cancelada ou ficou pendente.
     */
    const val CARENCIA_RECAPTURA_MS = 10L * 1000

    /** Preferências próprias do marcador: versão-alvo e hora da gravação. */
    private const val PREFS_MARCADOR = "photoid_atualizacao_marcador"
    private const val KEY_ALVO = "alvo"
    private const val KEY_GRAVADO_EM = "gravado_em"

    /** As chaves das contagens que a conferência pós-atualização compara. */
    const val CONT_PACIENTES = "pacientes"
    const val CONT_PROTOCOLOS = "protocolos"
    const val CONT_RUBRICARIO = "rubricario"
    const val CONT_TRATAMENTO = "tratamento"

    /**
     * O que a primeira abertura depois de uma atualização encontrou.
     *
     * [encolhidos] traz só os itens que diminuíram: chave → (antes, agora).
     * Vazio quer dizer que tudo chegou. [pastaBackup] já vem legível para a
     * tela.
     */
    data class PosAtualizacao(
        val versionName: String,
        val encolhidos: Map<String, Pair<Int, Int>>,
        val pastaBackup: String
    )

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

    /**
     * Lê o version.json. `null` se ele não for do [FORMATO_VERSAO] ou se faltar
     * qualquer um dos campos obrigatórios.
     *
     * Um version.json sem versionCode, versionName, apkUrl e um SHA-256 de 64
     * dígitos hexadecimais é version.json quebrado, e adivinhar o que falta
     * seria oferecer instalação de coisa não conferida.
     *
     * Throwable, e não Exception: JSON torto lança Exception, mas uma API
     * ausente lança Error, e nas duas situações a resposta certa é a mesma — o
     * app segue sem novidade.
     */
    fun interpretarVersao(txt: String): AtualizacaoRemota.Publicada? = try {
        val o = org.json.JSONObject(txt)
        val formato = o.optInt("formato", 1)
        val code = o.optInt("versionCode", 0)
        val nome = o.optString("versionName", "")
        val apk = o.optString("apkUrl", "")
        val sha = o.optString("sha256", "").lowercase()
        val shaValido = sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }
        if (formato != FORMATO_VERSAO || code <= 0 || nome.isBlank() || apk.isBlank() || !shaValido) null
        else AtualizacaoRemota.Publicada(
            versionCode = code,
            versionName = nome,
            urlApk = apk,
            sha256 = sha,
            minSdk = o.optInt("minSdk", 24),
            notas = o.optString("notas", "")
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * Os certificados do APK baixado são os da instalação?
     *
     * `null` quando um dos lados não pôde ser lido: não dá para saber, e quem
     * decide é o instalador do Android. Só `false` interrompe a atualização —
     * uma falha de leitura nunca vira bloqueio falso.
     */
    fun mesmaAssinatura(instalado: Set<String>, apk: Set<String>): Boolean? =
        if (instalado.isEmpty() || apk.isEmpty()) null else instalado == apk

    /**
     * As contagens em texto, no formato `pacientes=120;protocolos=3`.
     *
     * Texto simples, e não JSON, para caber numa preferência e para que a regra
     * seja testável sem Android. Chave vazia ou com `=` ou `;` não entra: ela
     * quebraria a leitura de todas as outras.
     */
    fun codificarContagens(contagens: Map<String, Int>): String =
        contagens.entries
            .filter { (k, _) -> k.isNotEmpty() && '=' !in k && ';' !in k }
            .joinToString(";") { (k, v) -> "$k=$v" }

    /**
     * O inverso de [codificarContagens]. Texto vazio ou malformado devolve mapa
     * vazio, sem exceção: um registro corrompido não pode impedir a abertura do
     * app, e sem base de comparação não há o que afirmar.
     */
    fun decodificarContagens(texto: String?): Map<String, Int> {
        if (texto.isNullOrBlank()) return emptyMap()
        val saida = LinkedHashMap<String, Int>()
        for (parte in texto.split(';')) {
            val i = parte.indexOf('=')
            if (i <= 0) return emptyMap()
            val valor = parte.substring(i + 1).toIntOrNull()
            if (valor == null || valor < 0) return emptyMap()
            saida[parte.substring(0, i)] = valor
        }
        return saida
    }

    /**
     * O que diminuiu de [antes] para [depois]: chave → (antes, agora).
     *
     * Só as chaves de [antes] contam, e a que falta em [depois] vale zero. A
     * assimetria é de propósito: uma leitura que falha DEPOIS da atualização é
     * exatamente o defeito que esta conferência existe para mostrar, e uma que
     * falhou ANTES não deixou base para comparar.
     */
    fun encolheu(antes: Map<String, Int>, depois: Map<String, Int>): Map<String, Pair<Int, Int>> {
        val saida = LinkedHashMap<String, Pair<Int, Int>>()
        for ((chave, n) in antes) {
            val agora = depois[chave] ?: 0
            if (agora < n) saida[chave] = n to agora
        }
        return saida
    }

    /**
     * O marcador gravado antes de instalar indica que a instalação aconteceu?
     *
     * Marcador igual à versão instalada é instalação cancelada ou pendente: o
     * marcador fica, a versão de partida segue em uso com as contagens dele
     * mantidas em dia ([deveRecapturar]), e a próxima tentativa pelo app o
     * sobrescreve. Se a versão seguinte chegar por fora do app — APK instalado
     * à mão, gerenciador do parque —, quem descarta o marcador velho é
     * [marcadorVale].
     */
    fun houveAtualizacao(marcadoDe: Int, instalado: Int): Boolean =
        marcadoDe > 0 && instalado > marcadoDe

    /**
     * O marcador ainda fala DESTA instalação?
     *
     * Um marcador deixado por instalação cancelada sobrevive até a próxima
     * versão chegar, por qualquer caminho, e [houveAtualizacao] então diz sim.
     * Comparar as contagens de semanas atrás com as de hoje acusaria perda de
     * dados que não houve, e mandaria restaurar uma cópia velha — importação
     * que só soma devolveria à lista de tratamento quem já teve alta.
     *
     * Vale só quando a versão instalada é a que o marcador esperava ([alvo]; 0
     * = não registrada, não decide) e o marcador tem menos de [validade]. Sem
     * hora de gravação, não vale. Relógio acertado para trás conta pela
     * distância, nos dois sentidos.
     */
    fun marcadorVale(alvo: Int, gravadoEm: Long, instalado: Int, agora: Long,
                     validade: Long = VALIDADE_MARCADOR_MS): Boolean {
        if (alvo > 0 && instalado != alvo) return false
        if (gravadoEm <= 0L) return false
        val idade = agora - gravadoEm
        return idade in -validade..validade
    }

    /**
     * A versão que gravou o marcador voltou a rodar depois da entrega ao
     * instalador: as contagens gravadas devem passar a ser as de agora?
     *
     * O instalador abre na tarefa dele. Quem o cancela, ou o deixa pendente nos
     * Recentes, volta ao app antigo, que continua funcionando: dá alta, apaga
     * cadastro. Se a versão-alvo for instalada depois, dentro da [validade], a
     * conferência compararia as contagens do momento da cópia com as de depois
     * dessas baixas e acusaria perda que não houve — e mandaria restaurar a
     * cópia, cuja importação que só soma devolveria à lista de tratamento quem
     * já teve alta. Recapturadas enquanto a versão de partida roda, as
     * contagens acompanham o uso; a perda que a conferência existe para mostrar
     * só acontece na versão nova, e continua aparecendo.
     *
     * Só a versão que gravou o marcador ([marcadoDe] igual a [instalado])
     * recaptura: a versão nova confere. Dentro da [carencia] a versão de partida
     * ainda está entregando o APK; fora da [validade] o marcador já não vale. A
     * hora da gravação NÃO muda com a recaptura: a validade continua contada da
     * cópia de segurança, que é a pasta que o aviso aponta. Relógio acertado
     * para trás conta pela distância, como em [marcadorVale].
     */
    fun deveRecapturar(marcadoDe: Int, instalado: Int, gravadoEm: Long, agora: Long,
                       carencia: Long = CARENCIA_RECAPTURA_MS,
                       validade: Long = VALIDADE_MARCADOR_MS): Boolean {
        if (marcadoDe <= 0 || instalado != marcadoDe) return false
        if (gravadoEm <= 0L) return false
        val distancia = kotlin.math.abs(agora - gravadoEm)
        return distancia in carencia..validade
    }

    /**
     * As contagens que substituem as gravadas numa recaptura.
     *
     * Item lido agora entra com o valor de agora. Item cuja leitura falhou agora
     * fica com o valor gravado: sem ele, a versão nova deixaria de comparar
     * aquele item, e é nela que a perda acontece.
     */
    fun contagensRecapturadas(gravadas: Map<String, Int>,
                              atuais: Map<String, Int>): Map<String, Int> =
        gravadas + atuais

    /**
     * O arquivo do cadastro em `filesDir` e a entrada de metadados dele.
     *
     * GUARDA: os mesmos nomes de [PatientCache]. Se divergirem, a recaptura não
     * acha o arquivo e o item `pacientes` fica com o valor gravado — a conferência
     * volta a comparar com a cópia, sem esconder perda.
     */
    internal const val ARQUIVO_CADASTRO = "pacientes_cache.json"
    internal const val CHAVE_META_CADASTRO = "__schema__"

    /**
     * Quantos pacientes há no texto do cadastro, lido direto do arquivo.
     *
     * `null` quando o texto não é um cadastro inteiro — vazio, cortado no meio
     * de uma gravação, torto —, e aí o item fica com o valor gravado
     * ([contagensRecapturadas]). Conta as mesmas chaves que
     * `PatientCache.totalPacientes`: todas, menos a de metadados.
     */
    internal fun pacientesNoTextoDoCadastro(texto: String?): Int? {
        if (texto.isNullOrBlank()) return null
        return try {
            val o = org.json.JSONObject(texto)
            o.keys().asSequence().count { it != CHAVE_META_CADASTRO }
        } catch (_: Exception) {
            null
        }
    }

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
     * `forcado` ignora o intervalo e o interruptor — é o caminho do botão das
     * Configurações, onde existe um humano esperando resposta agora.
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
     * `Error` e passa por baixo de `catch (Exception)`. Com minSdk 24, todo
     * erro NewApi do lint é um fechamento em potencial.
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
     * O APK baixado foi assinado com os mesmos certificados da instalação?
     *
     * Existe para o aparelho com build de outra chave — debug, ou uma chave
     * substituída. O Android recusa instalar por cima com uma mensagem genérica
     * de conflito, e o passo seguinte de quem a lê é desinstalar, o que apaga o
     * cadastro, as equipes e os protocolos. Perguntar antes deixa a tela dizer
     * o que está acontecendo e onde está a cópia de segurança.
     *
     * FALHA ABERTA: `null` quando não deu para ler um dos lados, e só `false`
     * para o fluxo. O instalador continua sendo a autoridade final.
     *
     * Versões guardadas por `SDK_INT`, não por try/catch: `signingInfo` nasceu na
     * API 28, e abaixo dela o caminho é `signatures`. O try/catch daqui cobre só
     * a leitura do pacote, que pode falhar em qualquer versão.
     */
    @Suppress("DEPRECATION")
    fun assinaturaCompativel(context: Context, apk: File): Boolean? {
        if (!apk.isFile || apk.length() <= 0L) return null
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        val doAparelho = try { pm.getPackageInfo(context.packageName, flags) }
                         catch (_: Exception) { null }
        val doArquivo = try { pm.getPackageArchiveInfo(apk.absolutePath, flags) }
                        catch (_: Exception) { null }
        val instalado = certificados(doAparelho)
        val resposta = mesmaAssinatura(instalado, certificados(doArquivo))
        // Chave rotacionada: o APK novo é assinado pela chave nova e carrega a
        // antiga no histórico, e o Android aceita a atualização. Os conjuntos
        // diferem, mas isso não é conflito — fica com o instalador decidir.
        if (resposta == false && historicoDeCertificados(doArquivo).containsAll(instalado)) {
            return null
        }
        return resposta
    }

    @Suppress("DEPRECATION")
    private fun certificados(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val assinaturas: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners
            else info.signatures
        return assinaturas.orEmpty().map { impressaoDigital(it) }.toSet()
    }

    private fun historicoDeCertificados(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo
            if (si == null || si.hasMultipleSigners()) emptySet()
            else si.signingCertificateHistory.orEmpty().map { impressaoDigital(it) }.toSet()
        } else emptySet()
    }

    /** SHA-256 do certificado, em hexadecimal minúsculo — o mesmo número que o
     *  `apksigner verify --print-certs` mostra. */
    private fun impressaoDigital(s: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Quantos registros há agora: pacientes, protocolos, pessoas do rubricário
     * e pacientes em tratamento.
     *
     * A cópia antes de atualizar e a conferência depois chamam ESTA função, e é
     * isso que torna as duas contagens comparáveis. Item cuja leitura falha fica
     * fora do mapa (ver [encolheu]). Lê disco: chamar fora da thread principal.
     *
     * [cadastroSemGravar] conta os pacientes pelo texto do arquivo, sem construir
     * [PatientCache]. É o caminho da recaptura, que roda quando o app sai da
     * frente — e pode haver uma gravação do cadastro em curso nesse instante.
     * Construir o [PatientCache] sobre um arquivo cortado no meio da gravação o
     * lê como vazio, e a migração dele grava esse vazio por cima do cadastro.
     * Lido direto, o arquivo cortado só deixa o item fora do mapa.
     */
    fun contagensAtuais(context: Context, cadastroSemGravar: Boolean = false): Map<String, Int> {
        val saida = LinkedHashMap<String, Int>()
        if (cadastroSemGravar) {
            val texto = try {
                File(context.filesDir, ARQUIVO_CADASTRO).takeIf { it.isFile }?.readText()
            } catch (_: Exception) {
                null
            }
            pacientesNoTextoDoCadastro(texto)?.let { saida[CONT_PACIENTES] = it }
        } else {
            contar(saida, CONT_PACIENTES) { PatientCache(context).totalPacientes() }
        }
        contar(saida, CONT_PROTOCOLOS) { ProtocoloStore(context).listar().size }
        contar(saida, CONT_RUBRICARIO) { RubricarioStore(context).listar().size }
        contar(saida, CONT_TRATAMENTO) { TreatmentListManager(context).total() }
        return saida
    }

    private fun contar(saida: MutableMap<String, Int>, chave: String, ler: () -> Int) {
        try {
            saida[chave] = ler()
        } catch (_: Exception) {
        }
    }

    /**
     * Grava, antes de entregar o APK ao instalador, de que versão se partiu,
     * quantos registros havia e onde ficou a cópia de segurança.
     *
     * Não lê disco: as contagens vêm da própria cópia, que as calculou antes de
     * qualquer passo que pudesse falhar. Grava de forma síncrona, porque o
     * processo morre logo depois da entrega ao instalador; quem chama faz isso
     * fora da thread principal. As chaves começam com `atualizacao_`, que as
     * deixa fora do pacote de Transferência e do preferencias.json — são estado
     * deste aparelho, nesta atualização.
     *
     * Grava também, num arquivo de preferências próprio, a versão que se espera
     * instalar e a hora: é o que [marcadorVale] confere na volta. A versão de
     * partida vai por último, porque é ela que liga o marcador — interrompido
     * antes, ele simplesmente não existe.
     *
     * Sincronizada com [recapturarSeInstalacaoPendente]: a recaptura de um
     * marcador anterior não grava contagens no meio desta gravação.
     *
     * @param versaoAlvo versionCode do APK entregue ao instalador; 0 usa o da
     *   última publicação encontrada (`AppConfig.atualizacaoCode`).
     */
    @Synchronized
    fun gravarMarcadorPreInstalacao(context: Context, backup: BackupPreAtualizacao.Resultado,
                                    versaoAlvo: Int = 0) {
        val cfg = AppConfig(context)
        val alvo = if (versaoAlvo > 0) versaoAlvo else cfg.atualizacaoCode
        context.getSharedPreferences(PREFS_MARCADOR, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ALVO, alvo.coerceAtLeast(0))
            .putLong(KEY_GRAVADO_EM, System.currentTimeMillis())
            .commit()
        cfg.atualizacaoContagens = codificarContagens(backup.contagens)
        cfg.atualizacaoBackup = backup.pasta?.absolutePath ?: ""
        cfg.atualizacaoDe = versaoInstalada()
    }

    /**
     * Na primeira abertura depois de uma atualização: os dados chegaram?
     *
     * `null` quando não houve atualização desde o marcador. Havendo, compara as
     * contagens, apaga o marcador e o APK que ficou em cacheDir, e devolve o que
     * encontrou. Quem dispara isto não é a troca do APK, que preserva
     * `filesDir`: é um defeito de migração na versão nova, e é esse que a
     * comparação pega.
     *
     * Marcador que não fala desta instalação ([marcadorVale]) é apagado em
     * silêncio, e a resposta é `null`: sem base confiável, não há o que afirmar.
     *
     * Lê disco: chamar fora da thread principal.
     */
    fun conferirPosAtualizacao(context: Context): PosAtualizacao? {
        val cfg = AppConfig(context)
        val instalado = versaoInstalada()
        if (!houveAtualizacao(cfg.atualizacaoDe, instalado)) return null

        val meta = context.getSharedPreferences(PREFS_MARCADOR, Context.MODE_PRIVATE)
        if (!marcadorVale(meta.getInt(KEY_ALVO, 0), meta.getLong(KEY_GRAVADO_EM, 0L),
                instalado, System.currentTimeMillis())) {
            apagarMarcador(context, cfg)
            return null
        }

        val antes = decodificarContagens(cfg.atualizacaoContagens)
        val depois = contagensAtuais(context)
        // Sem caminho gravado — a cópia falhou e o usuário seguiu assim mesmo —
        // a mensagem aponta a pasta das cópias, onde as anteriores continuam.
        val pasta = cfg.atualizacaoBackup.ifBlank {
            try { File(StorageLocal.base(context), BackupPreAtualizacao.NOME_PASTA).absolutePath }
            catch (_: Exception) { BackupPreAtualizacao.NOME_PASTA }
        }

        apagarMarcador(context, cfg)

        val legivel = try { StorageLocal.amigavel(pasta) } catch (_: Exception) { pasta }
        return PosAtualizacao(BuildConfig.VERSION_NAME, encolheu(antes, depois), legivel)
    }

    /**
     * Desliga o marcador e apaga o APK que ficou em cacheDir. A versão de
     * partida sai primeiro: interrompido no meio, o que sobra já não liga nada.
     */
    private fun apagarMarcador(context: Context, cfg: AppConfig) {
        cfg.atualizacaoDe = 0
        cfg.atualizacaoContagens = ""
        cfg.atualizacaoBackup = ""
        context.getSharedPreferences(PREFS_MARCADOR, Context.MODE_PRIVATE).edit()
            .clear()
            .commit()
        try {
            arquivoApk(context).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Mantém em dia as contagens do marcador enquanto a versão que o gravou
     * continua em uso ([deveRecapturar]). `true` quando recapturou.
     *
     * Só as contagens mudam: a versão-alvo, a hora e a pasta da cópia ficam, e
     * com elas a validade e a conferência da versão esperada. O APK de cacheDir
     * também fica — o instalador deixado pendente ainda pode precisar dele.
     *
     * Lê disco: chamar fora da thread principal. Sincronizada: duas recapturas
     * simultâneas poderiam terminar fora de ordem, e a que leu primeiro
     * gravaria por último contagens já vencidas.
     */
    @Synchronized
    fun recapturarSeInstalacaoPendente(context: Context): Boolean {
        val cfg = AppConfig(context)
        val meta = context.getSharedPreferences(PREFS_MARCADOR, Context.MODE_PRIVATE)
        if (!deveRecapturar(cfg.atualizacaoDe, versaoInstalada(),
                meta.getLong(KEY_GRAVADO_EM, 0L), System.currentTimeMillis())) return false
        val novas = contagensRecapturadas(decodificarContagens(cfg.atualizacaoContagens),
            contagensAtuais(context, cadastroSemGravar = true))
        cfg.atualizacaoContagens = codificarContagens(novas)
        return true
    }

    /** O observador de [vigiarInstalacaoPendente] já foi registrado neste processo. */
    private val vigiaRegistrada = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Recaptura as contagens do marcador toda vez que o app inteiro sai da
     * frente ([recapturarSeInstalacaoPendente]).
     *
     * É saindo do app que a pessoa vai aos Recentes tocar em «Instalar» no
     * instalador que ficou pendente. Recapturar só na volta à tela inicial
     * deixaria de fora a alta dada no Tratamento logo antes. Só quando a última
     * tela visível deixa de aparecer, e não a cada troca de tela: a contagem lê
     * o cadastro inteiro, e cada leitura a mais disputa o arquivo com quem está
     * gravando.
     * Girar o tablete interrompe e recria a tela, e isso não é saída. Sem
     * marcador, cada saída custa uma leitura de preferências em IO, e nada
     * mais.
     *
     * Uma vez por processo, registrado no Application: nenhuma Activity fica
     * presa ao observador (o conjunto das visíveis segura cada uma só por
     * referência fraca), e chamar de novo não registra outro. Tela que já
     * estava visível antes do registro não entra na conta, e a parada dela não
     * dispara nada. Os retornos de ciclo de vida chegam todos na thread
     * principal, que é a única a mexer no conjunto.
     */
    fun vigiarInstalacaoPendente(app: Application) {
        if (!vigiaRegistrada.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private val visiveis: MutableSet<Activity> =
                java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())

            override fun onActivityStarted(activity: Activity) {
                visiveis.add(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                if (!visiveis.remove(activity)) return
                if (visiveis.isNotEmpty() || activity.isChangingConfigurations) return
                val ctx = activity.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try { recapturarSeInstalacaoPendente(ctx) } catch (_: Exception) { }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Entrega o APK ao instalador do Android.
     *
     * O sistema mostra o diálogo DELE e o usuário confirma. Não existe caminho
     * silencioso para app que não é do sistema nem administrador do aparelho, e
     * fingir que existiria seria prometer o que a plataforma não dá.
     *
     * O QUE ACONTECE DEPOIS, e por que o app não se reabre sozinho. `true` aqui
     * quer dizer só que o instalador abriu. Quando o pacote é substituído, o
     * Android encerra o processo deste app, e nada do APK antigo continua
     * rodando. O instalador roda em processo próprio, e a última tela dele
     * sobrevive: é nela que aparece «Abrir», que inicia a versão nova a frio. Um
     * receptor de MY_PACKAGE_REPLACED até roda, mas a partir da API 29 o Android
     * bloqueia abrir Activity a partir do segundo plano; contornar isso pediria
     * a permissão de sobrepor outros apps ou a de notificação, e reabrir sozinho
     * não vale uma permissão nova. Por isso a confirmação e o aviso na entrega
     * dizem «toque em Instalar, depois em Abrir», e a primeira abertura da
     * versão nova confirma que a atualização aconteceu ([conferirPosAtualizacao]).
     * Quem tocar em «Concluído» abre o app pelo ícone, e a confirmação aparece do
     * mesmo jeito.
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
