package com.radioterapia.ai.update

import android.content.Context
import com.radioterapia.ai.BuildConfig
import com.radioterapia.ai.transfer.PacoteConfig
import com.radioterapia.ai.util.StorageLocal
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A cópia de segurança que roda ANTES de instalar versão nova.
 *
 * O QUE ELA PROTEGE. Atualizar um APK por cima de outro com a MESMA assinatura
 * não apaga dado do app: `filesDir` e as preferências sobrevivem. O que uma
 * instalação interrompida, ou uma desinstalação feita para «resolver», levaria
 * embora é o que mora DENTRO do app: o cadastro dos pacientes, a lista de
 * tratamento, as equipes e o rubricário, os protocolos com seus PDFs e a
 * configuração do serviço. Isso vem para cá.
 *
 * FOTO NÃO ENTRA, NUNCA. As fotos, as fichas, o Time-Out e as observações moram
 * em PhotoID_RT/PHOTOS, no armazenamento externo, que nenhuma instalação
 * alcança. Copiá-los aqui duplicaria o acervo inteiro a cada atualização —
 * gigabytes, atrás de um diálogo sem progresso, em [QUANTAS_MANTER] cópias —
 * para proteger o que não está em risco. Por isso [ITENS_EXPORTADOS] é uma lista
 * explícita, e a cópia crua de `filesDir` recusa as pastas e as extensões de
 * foto ([deveCopiarInterno]).
 *
 * TRÊS SAÍDAS, cada uma com o seu caminho de volta:
 *  - `configuracao.zip`: o pacote de Transferência, conferido depois de gravado.
 *    Restaura-se em Configurações > Transferência > Importar, tudo marcado.
 *  - `preferencias.json`: as preferências do serviço no formato antigo de
 *    exportação, que a mesma importação reconhece. Completa o que o pacote não
 *    leva, e por isso vai DEPOIS dele.
 *  - `interno/`: cópia crua de `filesDir`, para suporte técnico. O Android não
 *    deixa devolver isto à mão a um app instalado, e o LEIA-ME diz isso.
 *
 * SEM SENHA. O pacote não leva senha de destino de sincronia, as senhas moram em
 * preferências cifradas fora de `filesDir`, e [chavesParaCopia] recusa qualquer
 * chave com nome de segredo. Gravar senha em texto no armazenamento
 * compartilhado para economizar uma digitação seria trocar um risco real por
 * uma comodidade.
 */
object BackupPreAtualizacao {

    /** Quantas cópias ficam. As antigas saem: a mais nova é a que interessa, e
     *  acumular cópia sem limite enche o armazenamento com arquivo que ninguém
     *  abriu. */
    private const val QUANTAS_MANTER = 3

    const val NOME_PASTA = "BACKUP_ATUALIZACAO"

    const val ARQ_ZIP = "configuracao.zip"
    const val ARQ_PREFERENCIAS = "preferencias.json"
    const val PASTA_INTERNO = "interno"

    private const val PREFS_CONFIG = "config_radioterapia"

    /**
     * O que vai para o `configuracao.zip`: todo item do pacote de Transferência,
     * menos FOTOS.
     *
     * GUARDA: lista explícita, e não `Item.values()`. FOTOS está no enum, então
     * «todos os itens» quer dizer «o acervo de fotos inteiro». Item novo entra
     * na cópia por padrão; foto, não.
     */
    val ITENS_EXPORTADOS: Set<PacoteConfig.Item> =
        PacoteConfig.Item.values().filterNot { it == PacoteConfig.Item.FOTOS }.toSet()

    /**
     * Chaves de `config_radioterapia` que o preferencias.json não leva.
     *
     * As três URIs do seletor de pastas: a autorização do Android vale só para a
     * instalação que a recebeu, e depois de reinstalar elas apontariam para
     * nada. E o estado da procura de atualização e o marcador pré-instalação,
     * que são deste aparelho nesta atualização — restaurá-los faria a versão
     * nova se achar desatualizada, ou conferir contra contagens de outro dia.
     *
     * `atualizacao_procurar` NÃO está aqui de propósito: é a escolha do serviço
     * de não consultar o repositório, e restaurar a cópia não pode religar em
     * silêncio uma conexão que a clínica desligou.
     */
    private val CHAVES_FORA = setOf(
        "pasta_fotos_uri", "pasta_csv_uri", "backup_uri",
        "atualizacao_code", "atualizacao_nome", "atualizacao_dispensada",
        "atualizacao_checagem",
        "atualizacao_de", "atualizacao_contagens", "atualizacao_backup",
    )

    /** Trecho de nome que denuncia segredo. Nenhuma chave de hoje o tem; a
     *  regra existe para a chave que alguém acrescentar amanhã. */
    private val NOMES_DE_SEGREDO = listOf("senha", "password", "passwd", "token", "secret")

    /**
     * Pastas de primeiro nível de `filesDir` que a cópia crua não leva.
     *
     * `sessao_atual`: o rascunho da simulação em andamento, com as fotos do
     * paciente. `historico_thumbs`: a foto de rosto de cada paciente, copiada
     * para o histórico. `treatment_cache`: fotos baixadas do servidor.
     * `PhotoID_RT`: a pasta base inteira, quando o aparelho não tem
     * armazenamento externo e ela cai aqui dentro — junto com esta própria cópia.
     */
    private val PASTAS_FORA_DO_INTERNO =
        setOf("sessao_atual", "historico_thumbs", "treatment_cache", "PhotoID_RT", "arquivadas_retidas",
            com.radioterapia.ai.util.QuarentenaArquivadas.PASTA_PREPARO)

    /** `csv_baixado.csv`: cópia da base de pacientes do servidor, que a próxima
     *  leitura refaz. */
    private val ARQUIVOS_FORA_DO_INTERNO = setOf("csv_baixado.csv")

    /** Extensões de foto e de imagem médica, recusadas em qualquer pasta: é a
     *  rede para a pasta de fotos que alguém criar amanhã sem passar por aqui. */
    private val EXTENSOES_FORA_DO_INTERNO = setOf("jpg", "jpeg", "dcm")

    data class Resultado(
        val pasta: File?,
        val arquivos: Int,
        val bytes: Long,
        /** Contagens no momento da cópia ([GerenciadorAtualizacao.contagensAtuais]),
         *  base da conferência na primeira abertura da versão nova. */
        val contagens: Map<String, Int> = emptyMap(),
        /** A cópia está na pasta privada do app, que a desinstalação apaga. */
        val privada: Boolean = false,
        /** O `configuracao.zip` foi relido e reconhecido como pacote. */
        val zipVerificado: Boolean = false,
    ) {
        /** A parte que se restaura pela tela existe e foi conferida. */
        val ok: Boolean get() = pasta != null && zipVerificado
    }

    // ==================== as regras, sem Context ====================

    /**
     * As preferências que entram no preferencias.json, já filtradas.
     *
     * Fora: valor nulo, as chaves de [CHAVES_FORA], toda chave com nome de
     * segredo e tipo que a importação não sabe gravar.
     *
     * Long que cabe em Int também fica fora. JSON não guarda o tipo do número, e
     * a importação grava como Int o que couber em Int; um `getLong` sobre o que
     * voltou como Int lança ClassCastException e fecha a tela que o lê. Os Long
     * desta configuração são marcas de tempo, e zero ou ausente querem dizer o
     * mesmo: nunca.
     */
    fun chavesParaCopia(todas: Map<String, Any?>): Map<String, Any> {
        val saida = LinkedHashMap<String, Any>()
        for ((chave, valor) in todas) {
            if (valor == null || chave in CHAVES_FORA) continue
            val minuscula = chave.lowercase()
            if (NOMES_DE_SEGREDO.any { it in minuscula }) continue
            when (valor) {
                is String, is Boolean, is Int, is Float -> saida[chave] = valor
                is Long ->
                    if (valor < Int.MIN_VALUE.toLong() || valor > Int.MAX_VALUE.toLong()) {
                        saida[chave] = valor
                    }
                is Set<*> -> if (valor.all { it is String }) saida[chave] = valor
                else -> { }
            }
        }
        return saida
    }

    /**
     * Este caminho de `filesDir`, relativo e com `/`, entra na cópia crua?
     *
     * Pasta se pergunta com `/` no fim (`"sessao_atual/"`), e é assim que a
     * varredura evita até descer nas pastas recusadas.
     */
    fun deveCopiarInterno(relativo: String): Boolean {
        val r = relativo.replace('\\', '/').trimStart('/')
        if (r.isEmpty()) return false
        val dentroDePasta = '/' in r
        if (dentroDePasta && r.substringBefore('/') in PASTAS_FORA_DO_INTERNO) return false
        if (!dentroDePasta && r in ARQUIVOS_FORA_DO_INTERNO) return false
        val extensao = r.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return extensao !in EXTENSOES_FORA_DO_INTERNO
    }

    // ==================== com Context ====================

    /**
     * Grava a cópia e devolve o que foi gravado.
     *
     * Nunca lança: cada saída tem o próprio try, e uma que falha não leva as
     * outras junto. Quem chama decide, por [Resultado.ok], se pergunta antes de
     * seguir. Lê e grava disco: chamar fora da thread principal.
     */
    fun executar(context: Context): Resultado {
        // As contagens vêm PRIMEIRO, antes de qualquer passo que possa falhar:
        // são a base da conferência pós-atualização e valem mesmo quando a
        // pasta não pôde ser criada.
        val contagens = GerenciadorAtualizacao.contagensAtuais(context)
        val privada = StorageLocal.emPastaPrivada(context)
        val falha = Resultado(null, 0, 0L, contagens, privada, zipVerificado = false)

        val raiz = try { File(StorageLocal.base(context), NOME_PASTA) }
                   catch (_: Throwable) { null } ?: return falha
        val pasta = File(raiz, SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()))
        try {
            pasta.mkdirs()
        } catch (_: Throwable) {
        }
        if (!pasta.isDirectory) return falha

        var arquivos = 0
        var bytes = 0L

        // 1) O pacote de Transferência, sem FOTOS. CONFERIDO RELENDO, e não pelo
        //    tamanho: um zip truncado tem tamanho, e é justamente o que não se
        //    descobre no dia de restaurar.
        val zip = File(pasta, ARQ_ZIP)
        var zipVerificado = false
        try {
            zip.outputStream().use { saida ->
                PacoteConfig.exportar(context, ITENS_EXPORTADOS, saida)
            }
            zipVerificado = zip.length() > 0 &&
                zip.inputStream().use { PacoteConfig.inspecionar(it) } != null
        } catch (_: Throwable) {
        }
        if (zipVerificado) {
            arquivos++
            bytes += zip.length()
        } else {
            zip.delete()
        }

        // 2) As preferências do serviço, inclusive as que o pacote não leva.
        try {
            val f = File(pasta, ARQ_PREFERENCIAS)
            f.writeText(jsonPreferencias(context))
            arquivos++
            bytes += f.length()
        } catch (_: Throwable) {
        }

        // 3) A cópia crua de filesDir, sem foto.
        try {
            val (n, b) = copiarInterno(context.filesDir, File(pasta, PASTA_INTERNO), raiz)
            arquivos += n
            bytes += b
        } catch (_: Throwable) {
        }

        gravarLeiaMe(pasta, privada)
        limparAntigas(raiz)
        return Resultado(pasta, arquivos, bytes, contagens, privada, zipVerificado)
    }

    /**
     * `config_radioterapia` no formato de AppConfig.exportarJson, que a
     * importação antiga das Configurações aplica.
     *
     * Só esse arquivo de preferências: a importação grava toda chave que lê em
     * `config_radioterapia`, e chaves de outro arquivo cairiam ali misturadas.
     */
    private fun jsonPreferencias(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
        val valores = org.json.JSONObject()
        for ((chave, valor) in chavesParaCopia(prefs.all)) {
            when (valor) {
                is Float -> valores.put(chave, valor.toDouble())
                is Set<*> -> valores.put(chave, org.json.JSONArray(valor.toList()))
                else -> valores.put(chave, valor)
            }
        }
        return org.json.JSONObject()
            .put("_versao", 1)
            .put("_app", "PhotoID RT")
            .put("_data", System.currentTimeMillis())
            .put("valores", valores)
            .toString(2)
    }

    /**
     * Copia [origem] para [destino] mantendo os caminhos relativos, filtrada por
     * [deveCopiarInterno]. Devolve (arquivos, bytes).
     *
     * Um arquivo que falha não interrompe os outros.
     */
    private fun copiarInterno(origem: File, destino: File, raizCopias: File): Pair<Int, Long> {
        var n = 0
        var b = 0L
        // Se a pasta das cópias estiver dentro de filesDir por qualquer caminho,
        // descer nela copiaria a cópia para dentro dela mesma, sem fim.
        val excluida = canonico(raizCopias)
        origem.walkTopDown()
            .onEnter { d ->
                d == origem ||
                    (deveCopiarInterno(d.relativeTo(origem).invariantSeparatorsPath + "/") &&
                        canonico(d) != excluida)
            }
            .filter { it.isFile }
            .forEach { f ->
                val rel = f.relativeTo(origem).invariantSeparatorsPath
                if (deveCopiarInterno(rel)) {
                    try {
                        val alvo = File(destino, rel)
                        f.copyTo(alvo, overwrite = true)
                        n++
                        b += alvo.length()
                    } catch (_: Throwable) {
                    }
                }
            }
        return n to b
    }

    private fun canonico(f: File): String =
        try { f.canonicalPath } catch (_: Exception) { f.absolutePath }

    /**
     * O bilhete que explica a pasta a quem a encontrar.
     *
     * Existe porque cópia de segurança sem instrução de restauração é cópia que
     * ninguém restaura. E porque a ausência das fotos precisa estar escrita, ou
     * alguém vai contar com elas no dia errado. ASCII de propósito: é lido em
     * qualquer computador, com qualquer codificação.
     */
    private fun gravarLeiaMe(pasta: File, privada: Boolean) {
        try {
            val data = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
            val avisoPrivada = if (!privada) "" else "\n\n" + """
                ATENCAO: ESTA PASTA ESTA DENTRO DO APLICATIVO
                  O aparelho nao deu ao PhotoID RT acesso a todos os arquivos, e
                  por isso esta copia ficou em Android/data. Desinstalar o
                  aplicativo APAGA esta pasta junto. Copie-a para fora (outra
                  pasta, computador ou pen-drive) ANTES de desinstalar.
                """.trimIndent()
            File(pasta, "LEIA-ME.txt").writeText(
                """
                COPIA DE SEGURANCA ANTES DE ATUALIZAR - PhotoID RT
                Gerada em: $data
                Versao instalada quando a copia foi feita: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})

                O QUE TEM AQUI
                  configuracao.zip   as configuracoes do servico, o cadastro dos
                                     pacientes, a lista de pacientes em
                                     tratamento, as equipes e o rubricario, e
                                     os protocolos com seus PDFs.
                  preferencias.json  todas as preferencias do aplicativo,
                                     inclusive as que o pacote acima nao leva.
                  interno/           copia bruta da pasta interna do
                                     aplicativo, para suporte tecnico. Nao se
                                     restaura a mao: o Android nao deixa.

                COMO RESTAURAR
                  So e preciso se o aplicativo tiver sido desinstalado e
                  instalado de novo. Uma atualizacao normal preserva tudo, e
                  nesse caso nao ha nada a restaurar.

                  1. Configuracoes > Transferencia > Importar: escolha
                     configuracao.zip e deixe todos os itens marcados.
                  2. Depois, pelo mesmo caminho, importe preferencias.json.
                     Ele completa o que o pacote nao leva. A ordem importa:
                     ele substitui cada preferencia que traz.

                O QUE NAO TEM AQUI, E POR QUE NAO PRECISA
                  As FOTOS, as fichas em PDF, o Time-Out e as observacoes nao
                  estao nesta copia. Eles ficam em PhotoID_RT/PHOTOS, no
                  armazenamento do aparelho, e instalar versao nova do
                  aplicativo nao os toca.

                  As SENHAS de rede e dos destinos de sincronia nao estao aqui,
                  de proposito. Depois de restaurar, digite-as uma vez em
                  Configuracoes.

                  As pastas escolhidas pelo seletor do Android precisam ser
                  escolhidas de novo: a autorizacao do Android vale so para a
                  instalacao que a recebeu.

                ESTA PASTA TEM DADO DE PACIENTE
                  O cadastro dos pacientes esta aqui. A sincronizacao do proprio
                  aplicativo nao envia esta pasta; guarde-a com o mesmo cuidado
                  do aparelho.
                """.trimIndent() + avisoPrivada + "\n"
            )
        } catch (_: Throwable) {
        }
    }

    /** Mantém as [QUANTAS_MANTER] mais recentes, pelo nome — que é a data em
     *  ordem ano-mês-dia, e por isso ordena sozinho. */
    private fun limparAntigas(raiz: File) {
        try {
            val pastas = raiz.listFiles()?.filter { it.isDirectory }
                ?.sortedBy { it.name } ?: return
            if (pastas.size > QUANTAS_MANTER) {
                pastas.take(pastas.size - QUANTAS_MANTER).forEach { it.deleteRecursively() }
            }
        } catch (_: Throwable) {
        }
    }
}
