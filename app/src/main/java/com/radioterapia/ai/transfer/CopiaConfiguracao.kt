package com.radioterapia.ai.transfer

import android.content.Context
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.util.NomeArquivo
import com.radioterapia.ai.util.StorageLocal
import java.io.File
import java.util.TimeZone

/**
 * A cópia datada da configuração que a sincronia leva ao destino.
 *
 * O QUE É. Um pacote [PacoteConfig] comum — o mesmo ZIP do «Exportar tudo» —
 * sem os itens com dado de paciente ([ITENS]). Restaura-se pelo caminho que já
 * existe: Configurações, exportar e importar configurações, importar de um
 * arquivo. Um tablet perdido, roubado ou reinstalado volta a ter equipe,
 * rubricas, protocolos, impressora e destinos a partir da cópia mais recente
 * que está no servidor da instituição.
 *
 * O QUE NUNCA ENTRA. Senha: as preferências entram só por lista de chaves, e
 * as senhas dos destinos moram no cofre cifrado que o [PacoteConfig] não lê.
 * Paciente: cadastro, fotos e agenda de tratamento ficam de fora, e o estado
 * dos perfis de sincronia — cujo último erro pode citar a pasta de um paciente
 * — sai limpo ([com.radioterapia.ai.sync.PerfilSync.semEstado]).
 *
 * ONDE FICA. `<base>/_CONFIG_PHOTOID_RT/`, irmã de `PHOTOS/` e de `DATABASE/`.
 * Nunca dentro de `PHOTOS/`, onde seria tomada por pasta de paciente pelo
 * Histórico, pela exclusão e pelo item de fotos do pacote. O motor tira só o
 * `PHOTOS/` da frente dos caminhos, então ela chega ao destino como
 * `<destino>/_CONFIG_PHOTOID_RT/`.
 *
 * SÓ NASCE QUANDO A CONFIGURAÇÃO MUDA. A comparação é pela impressão digital
 * ([PacoteConfig.impressaoDigital]), cujos 8 primeiros dígitos vão no nome do
 * arquivo, e é feita contra a cópia MAIS RECENTE — não contra qualquer uma.
 * Assim, configuração A, depois B, depois A de novo gera um terceiro arquivo, e
 * o mais novo no destino sempre reflete a configuração em uso. O sufixo também
 * torna o nome único por conteúdo: dois tablets da mesma unidade, no mesmo
 * minuto e com configurações diferentes, não se sobrescrevem no destino.
 *
 * RETENÇÃO SÓ LOCAL. Ficam as [QUANTAS_MANTER] mais recentes na pasta do
 * tablet; o destino guarda todas, porque o motor nunca apaga.
 *
 * Sem rede: este arquivo só grava em disco local. Quem envia é o motor, pelos
 * adaptadores de destino.
 */
object CopiaConfiguracao {

    /** A pasta, sob [StorageLocal.base]. O motor a manda primeiro e sem esperar a janela de frescor. */
    const val NOME_PASTA = "_CONFIG_PHOTOID_RT"

    private const val PREFIXO = "CONFIG_"

    /** O nome da unidade quando a clínica não tem nome em letra latina. */
    private const val UNIDADE_PADRAO = "PHOTOID_RT"

    /** Cópias mantidas na pasta local; a mais recente nunca sai. */
    const val QUANTAS_MANTER = 10

    /**
     * Os itens que entram: todos os que não levam dado de paciente.
     *
     * Um teste fixa o conjunto exato. Um item novo no [PacoteConfig.Item]
     * quebra o teste e obriga alguém a decidir se ele vai ao destino — uma
     * lista só negativa deixaria passar em silêncio um item futuro com dado de
     * paciente mal classificado.
     */
    val ITENS: Set<PacoteConfig.Item> =
        PacoteConfig.Item.values().filterNot { it.ehDadoDePaciente }.toSet()

    private val NOME_COPIA = Regex("^CONFIG_.+_([0-9A-F]{8})\\.zip$")

    /**
     * GUARDA: uma geração por vez. O motor já serializa as varreduras, mas a
     * cópia apaga temporários e poda arquivos da pasta, e isso não pode correr
     * em paralelo com outra geração vinda de outro chamador.
     */
    private val TRAVA = Any()

    /**
     * Garante que a pasta tem uma cópia da configuração atual, criando-a se a
     * configuração mudou desde a mais recente. Devolve a cópia em vigor, ou
     * nulo se não foi possível — e nulo nunca impede a sincronia das fotos.
     *
     * Roda na thread do motor, nunca na principal.
     *
     * Usa sempre o contexto da aplicação. O nome do protocolo padrão entra no
     * pacote traduzido; o worker chega com o contexto da aplicação e o botão
     * de sincronizar com o da tela, e dois contextos em idiomas diferentes
     * dariam duas impressões digitais alternando — uma cópia nova a cada vez
     * que o chamador trocasse.
     */
    fun garantir(context: Context): File? {
        val app = context.applicationContext ?: context
        return synchronized(TRAVA) {
            try {
                gerarSeMudou(app)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun gerarSeMudou(context: Context): File? {
        // Espaço para foto vem antes: com protocolos em PDF a cópia pode ter
        // alguns megabytes.
        if (!StorageLocal.temEspacoMinimo(context)) return null
        val pasta = File(StorageLocal.base(context), NOME_PASTA)
        pasta.mkdirs()
        if (!pasta.isDirectory) return null
        apagarTemporarios(pasta)

        val hash = PacoteConfig.impressaoDigital(context, ITENS).take(8)
        val copias = listarCopias(pasta)
        val maisRecente = copias.maxWithOrNull(
            compareBy<File>({ it.lastModified() }, { it.name }))
        if (!precisaNova(hash, maisRecente?.name)) return maisRecente
        val maiorDataAnterior = copias.maxOfOrNull { it.lastModified() } ?: 0L

        val agora = System.currentTimeMillis()
        val nome = nomeArquivo(unidadeAscii(AppConfig(context).nomeClinica), carimbo(agora), hash)
        // Ponto na frente e .tmp no fim: o motor pula os dois, então o arquivo
        // pela metade nunca é enviado.
        val tmp = File(pasta, ".$nome.${System.nanoTime()}.tmp")
        try {
            tmp.outputStream().use { PacoteConfig.exportar(context, ITENS, it, agora) }
            // Relido antes de ganhar o nome final: o motor envia a cópia sem
            // esperar a janela de frescor, porque ela nasce completa. Ler o ZIP
            // até o manifesto, que é a última entrada, confere isso.
            val legivel = tmp.inputStream().use { PacoteConfig.inspecionar(it) } != null
            if (!legivel) return null
            val copia = File(pasta, nome)
            if (!tmp.renameTo(copia)) return null
            // A mais recente é escolhida pela data de modificação. Uma cópia
            // antiga com data no futuro (relógio do tablet corrigido para trás)
            // continuaria «a mais recente» para sempre, e cada rodada geraria
            // uma cópia nova. A nova passa a valer depois de todas as outras.
            if (maiorDataAnterior >= copia.lastModified()) {
                copia.setLastModified(maiorDataAnterior + 2_000L)
            }
            // Há armazenamento que recusa mudar a data em silêncio. Aí as
            // cópias datadas no futuro saem da pasta local — são configurações
            // já substituídas pela que acabou de nascer —, para que a
            // recém-criada seja de fato a mais recente na próxima rodada.
            val dataNova = copia.lastModified()
            if (maiorDataAnterior >= dataNova) {
                listarCopias(pasta)
                    .filter { it.name != copia.name && it.lastModified() >= dataNova }
                    .forEach { it.delete() }
            }
            podar(pasta, copia.name)
            return copia
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** As cópias da pasta: só os arquivos com nome de cópia. */
    private fun listarCopias(pasta: File): List<File> =
        pasta.listFiles()?.filter { it.isFile && hashDoNome(it.name) != null }.orEmpty()

    /**
     * Temporário que sobrou na pasta é de uma geração que morreu no meio: a
     * [TRAVA] garante que nenhuma outra está gravando agora.
     */
    private fun apagarTemporarios(pasta: File) {
        pasta.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(".") && f.name.endsWith(".tmp")) f.delete()
        }
    }

    private fun podar(pasta: File, preservar: String) {
        val copias = listarCopias(pasta)
        excedentes(copias.map { it.name to it.lastModified() }, QUANTAS_MANTER)
            .filter { it != preservar }
            .forEach { File(pasta, it).delete() }
    }

    // ============================================================ funções puras

    /**
     * O nome da unidade em ASCII, para o nome do arquivo.
     *
     * Mesma normalização dos nomes de paciente nos arquivos
     * ([NomeArquivo.nomeCompletoAscii]): sem acento, letras latinas que não se
     * decompõem (Ł, Ø) trocadas pela base, palavras separadas por `_`. Quando
     * nada sobra — nome vazio, ou só em escrita não latina —, o normalizador
     * devolve `X`, e aqui vale [UNIDADE_PADRAO]: um nome que diga de que app é
     * o arquivo serve mais a quem o encontra no servidor que uma letra solta.
     * O manifesto continua levando o nome original da clínica.
     */
    fun unidadeAscii(nome: String?): String {
        val ascii = NomeArquivo.nomeCompletoAscii(nome)
        return if (ascii == "X") UNIDADE_PADRAO else ascii
    }

    /**
     * `DD_MMM_AAAA_HH_MM` pela tabela fixa de meses de [NomeArquivo], a fonte
     * única de data em nome de arquivo — independente do idioma do aparelho.
     */
    fun carimbo(instante: Long, fuso: TimeZone = TimeZone.getDefault()): String =
        NomeArquivo.campoData(instante, fuso, comSegundos = false)

    /** `CONFIG_<UNIDADE>_<DD_MMM_AAAA_HH_MM>_<HASH8>.zip`. */
    fun nomeArquivo(unidade: String, carimbo: String, hash8: String): String =
        "$PREFIXO${unidade}_${carimbo}_${hash8}.zip"

    /** Os 8 dígitos da impressão digital no nome, ou nulo se não é nome de cópia. */
    fun hashDoNome(nome: String?): String? =
        NOME_COPIA.find(nome ?: "")?.groupValues?.get(1)

    /** Cópia nova é necessária quando a mais recente não tem a impressão atual. */
    fun precisaNova(hash8: String, nomeMaisRecente: String?): Boolean =
        hashDoNome(nomeMaisRecente) != hash8

    /**
     * Os nomes que saem da pasta local: tudo depois dos [manter] mais recentes,
     * por data de modificação decrescente e, no empate, por nome decrescente.
     */
    fun excedentes(arquivos: List<Pair<String, Long>>, manter: Int): List<String> =
        arquivos
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }
                .thenByDescending { it.first })
            .drop(manter.coerceAtLeast(1))
            .map { it.first }
}
