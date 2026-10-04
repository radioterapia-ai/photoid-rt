package com.radioterapia.ai.util

import android.content.Context
import android.content.SharedPreferences
import com.radioterapia.ai.session.SessionManager
import java.io.File

/**
 * Quarentena das fotos arquivadas que estão na pasta de mais de um paciente.
 *
 * COMO ISSO ACONTECE. A sessão de captura guarda em `ARQUIVADAS/` o rosto e a
 * etiqueta substituídos, e a finalização COPIA essa subpasta inteira para a
 * pasta do paciente. Uma `ARQUIVADAS/` que sobreviva à limpeza da sessão
 * (exclusão de pasta não vazia falha sem lançar nada) vai junto em cada
 * finalização seguinte: a mesma foto passa a existir em várias pastas, sobe ao
 * servidor e é oferecida para restaurar na ficha de outra pessoa.
 * `SessionManager.limparSessao` impede que isso se repita; esta varredura
 * trata o que já está em disco.
 *
 * POR QUE QUARENTENA, E NÃO SEPARAÇÃO. O nome de uma foto arquivada não diz de
 * quem ela é (`_rosto_ARQ<ms>.jpg`), e a mesma foto em duas pastas não tem dono
 * demonstrável. Toda cópia de um grupo presente em dois ou mais pacientes sai
 * da pasta de paciente — inclusive a do dono verdadeiro, que não há como
 * distinguir — e vai para [NOME_PASTA], uma subpasta por pasta de origem.
 *
 * NADA É APAGADO. Mover é renomear, ou copiar, conferir o tamanho e só então
 * remover a origem. O que não se deixa mover fica onde está, e a execução não
 * é dada como concluída: tenta de novo na próxima abertura. A cópia que ficou
 * sendo a única — a origem sumiu enquanto era copiada — nunca é apagada
 * ([mover]).
 *
 * O QUE JÁ ESTÁ NA QUARENTENA CONTINUA CONTANDO. A cópia movida numa execução
 * anterior é prova de que aquela foto esteve na pasta daquele paciente. Sem
 * ela, a cópia que ficou para trás — movimento que falhou, processo encerrado
 * no meio, cópia que chegou depois da listagem, foto trazida de volta por uma
 * importação — formaria um grupo de um paciente só e nunca mais sairia. O que
 * está na quarentena entra no agrupamento e nunca é movido de novo
 * ([evidenciasDaQuarentena]).
 *
 * O QUE CONTA COMO O MESMO PACIENTE. A pasta de reirradiação do formato
 * antigo (`... NOVA SIMULACAO n`) é do mesmo paciente da pasta principal:
 * identidade é nome mais prontuário, sem o sufixo ([identidadePaciente]).
 * Homônimos com prontuários diferentes são pacientes diferentes.
 *
 * A SESSÃO DE CAPTURA é examinada, e ANTES das pastas de paciente. O acúmulo
 * mora nela, e a finalização copia `ARQUIVADAS/` da sessão para a pasta do
 * paciente: o resíduo que sai da sessão logo no começo não tem como ser levado
 * por uma finalização feita durante a varredura longa para uma pasta que já foi
 * listada. Sem rascunho aberto, tudo é resíduo; com rascunho, é resíduo o que
 * foi arquivado antes de o rascunho começar e o que já aparece na pasta de
 * algum paciente ([classificarSessao]). O estado do rascunho é lido de novo
 * cada vez que se vai agir sobre a sessão, nunca uma vez só antes da varredura.
 *
 * O RESÍDUO SAI DA SESSÃO POR RENOMEAÇÃO, ANTES DE QUALQUER CÓPIA. A sessão
 * fica na memória interna e a quarentena na pasta base, em outro volume: entre
 * os dois, mover é copiar, e o acúmulo de muitas sessões leva minutos. Nesse
 * tempo a finalização copiaria para a pasta do paciente o resíduo ainda não
 * movido, a tela de captura o ofereceria para restaurar, e o descarte do
 * rascunho apagaria o que ainda estivesse lá. Por isso todo o resíduo listado
 * é primeiro renomeado para [PASTA_PREPARO], no mesmo volume da sessão — um
 * passo instantâneo por arquivo, para uma pasta que a finalização e a captura
 * não leem —, e só depois copiado de lá para a quarentena. O que uma execução
 * interrompida deixou no preparo segue na execução seguinte.
 *
 * QUANDO A BASE É DADA COMO EXAMINADA. Só depois de uma passada que não moveu
 * nada, não falhou e não deixou na sessão arquivo de ordem indecidível
 * ([executarAteLimpar]): a passada que move é seguida de outra, que confere. A
 * marca é por pasta base — a base muda quando o acesso a todos os arquivos é
 * concedido depois, ou quando o serviço escolhe outra pasta, e a base nova
 * ainda não foi examinada. Uma importação que traga fotos tira a marca antes
 * de gravar a primeira e impede que ela volte enquanto grava
 * ([abrirImportacao]). O motor de sincronia só envia `ARQUIVADAS/` de base
 * marcada ([baseConferida]).
 */
object QuarentenaArquivadas {

    const val NOME_PASTA = "_QUARENTENA_ARQUIVADAS"

    /** Subpasta da quarentena para o que estava na sessão de captura. */
    const val PASTA_SESSAO = "_SESSAO_DE_CAPTURA"

    /**
     * Pasta em `filesDir`, no mesmo volume da sessão de captura, para onde o
     * resíduo da sessão é renomeado antes de ser copiado à quarentena. Guarda
     * fotos de paciente: o que copia `filesDir` para fora deve recusá-la, como
     * recusa a própria sessão.
     */
    const val PASTA_PREPARO = "quarentena_pendente"

    /** Relatório em texto, só contagens e nomes de pasta. */
    const val NOME_RELATORIO = "RELATORIO.txt"

    private const val PREFS = "photoid_quarentena"

    /**
     * Bases já dadas como examinadas. A chave muda quando muda o que a marca
     * garante: marca gravada sob regra mais frouxa não dispensa a varredura
     * da regra atual.
     */
    private const val CHAVE_BASES = "bases_conferidas"

    /**
     * Passadas por execução: a que move, a que confere e uma de folga para o
     * que chegou durante a conferência. O que ainda mudar depois disso fica
     * para a próxima abertura.
     */
    internal const val MAX_PASSADAS = 3

    /** Carimbo que [FotosArquivadas.arquivar] grava no nome: `_ARQ<ms>`. */
    private val CARIMBO = Regex("_ARQ(\\d{1,19})")

    /** `_n` que [destinoLivre] acrescenta antes da extensão quando o nome já existe. */
    private val SUFIXO_COLISAO = Regex("^(.+)_\\d+$")

    /**
     * GUARDA: protege a marca de base examinada e o controle das importações.
     *
     * É segurada só para ler ou gravar a marca, nunca durante uma varredura.
     * Quem pergunta se a base foi examinada ([baseConferida]) — o motor de
     * sincronia, a conferência da tela de finalização, os botões de
     * sincronizar com prazo de 45 segundos — não pode esperar os minutos de
     * uma passada. Ordem: o monitor do objeto antes desta, nunca o contrário.
     */
    private val TRAVA_MARCA = Any()

    /** Importações de fotos em andamento. Lida e alterada só sob [TRAVA_MARCA]. */
    private val importacoes = Importacoes()

    /**
     * As importações de fotos abertas, e uma geração que muda cada vez que uma
     * abre ou fecha.
     *
     * Uma passada só grava a marca se nenhuma importação está aberta no fim E
     * nenhuma abriu ou fechou desde o começo dela: a importação que gravou
     * fotos depois da listagem — mesmo já encerrada — deixou nas pastas o que
     * a passada não viu. Sem trava própria: quem usa segura [TRAVA_MARCA].
     */
    internal class Importacoes {
        var abertas = 0
            private set
        var geracao = 0L
            private set

        fun abrir() {
            abertas++
            geracao++
        }

        fun fechar() {
            if (abertas > 0) abertas--
            geracao++
        }

        /** A passada que começou na geração [geracaoNoInicio] pode gravar a marca agora? */
        fun permitemMarcar(geracaoNoInicio: Long): Boolean =
            abertas == 0 && geracao == geracaoNoInicio
    }

    data class Relatorio(val examinados: Int, val movidos: Int, val pastas: Int)

    /**
     * Um arquivo de `ARQUIVADAS/` visto na varredura.
     *
     * @property paciente identidade do paciente ([identidadePaciente]).
     * @property pasta nome da pasta de paciente onde o arquivo está, ou de onde
     *   saiu, quando já está na quarentena.
     * @property evidencia arquivo que já está na quarentena: conta no
     *   agrupamento e nunca é selecionado para mover.
     */
    data class Entrada(
        val paciente: String,
        val pasta: String,
        val nome: String,
        val tamanho: Long,
        val evidencia: Boolean = false
    )

    /**
     * O rascunho de captura no instante em que foi lido.
     *
     * @property inicioMs quando o rascunho começou (`SessionManager.timestampInicio`);
     *   0 quando não se sabe.
     */
    data class EstadoSessao(val rascunhoAtivo: Boolean, val inicioMs: Long)

    /**
     * Os arquivos de `ARQUIVADAS/` da sessão, pelo que se faz com eles.
     *
     * @property residuo vai para a quarentena.
     * @property pendentes rascunho aberto e ordem indecidível: fica no lugar, e
     *   a base não é dada como examinada.
     */
    data class ClassificacaoSessao(
        val residuo: List<Pair<String, Long>>,
        val pendentes: List<Pair<String, Long>>
    )

    /** Resultado de uma passada. */
    internal data class Execucao(
        val relatorio: Relatorio,
        val falhas: Int,
        val pendentes: Int = 0,
        val pastasAfetadas: Set<String> = emptySet()
    ) {
        /** Não mexeu em nada e não deixou dúvida: o disco está como deveria. */
        val limpa: Boolean
            get() = relatorio.movidos == 0 && falhas == 0 && pendentes == 0
    }

    /** Resultado de uma execução inteira, somadas as passadas. */
    internal data class Resultado(val relatorio: Relatorio, val limpa: Boolean, val passadas: Int)

    /**
     * Quais arquivos vão para a quarentena.
     *
     * Agrupa por nome e tamanho. Grupo presente em dois ou mais pacientes sai
     * INTEIRO, em todas as pastas; grupo de um paciente só fica. Mesmo nome com
     * tamanho diferente é outro arquivo. Entrada de evidência conta para saber
     * quantos pacientes o grupo tem, mas não volta na lista: já está na
     * quarentena.
     */
    fun selecionar(entradas: List<Entrada>): List<Entrada> =
        entradas.groupBy { it.nome to it.tamanho }
            .values
            .filter { grupo -> grupo.map { it.paciente }.toSet().size >= 2 }
            .flatten()
            .filter { !it.evidencia }

    /**
     * O instante do arquivamento, lido do nome (`_rosto_ARQ<ms>.jpg`,
     * `_rosto_ARQ<ms>_ORIGINAL.jpg`), ou `null` quando não há carimbo legível.
     * Com mais de um carimbo vale o último, que é o do arquivamento mais
     * recente.
     */
    fun carimboDeArquivamento(nome: String): Long? =
        CARIMBO.findAll(nome).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Quais arquivos de `ARQUIVADAS/` da sessão são resíduo de sessões
     * anteriores.
     *
     * Sem rascunho aberto, todos: arquivo arquivado sem sessão em andamento não
     * pertence a captura nenhuma.
     *
     * Com rascunho, é resíduo:
     * - o que já está na pasta de algum paciente, ou na quarentena (mesmo nome e
     *   tamanho em [pacientes]);
     * - o que foi arquivado ANTES de o rascunho começar, pelo carimbo do nome.
     *   O rascunho só arquiva depois de começar, então o que é mais antigo que
     *   ele veio de outra captura — e a finalização o levaria para a pasta
     *   deste paciente.
     *
     * O que foi arquivado depois do início é do paciente que está sendo
     * fotografado, e segue com ele na finalização. Sem início conhecido ou sem
     * carimbo legível não há como decidir: o arquivo fica em [ClassificacaoSessao.pendentes].
     */
    fun classificarSessao(
        sessao: List<Pair<String, Long>>,
        pacientes: List<Entrada>,
        estado: EstadoSessao
    ): ClassificacaoSessao {
        if (!estado.rascunhoAtivo) return ClassificacaoSessao(sessao, emptyList())
        val conhecidos = pacientes.map { it.nome to it.tamanho }.toHashSet()
        val residuo = mutableListOf<Pair<String, Long>>()
        val pendentes = mutableListOf<Pair<String, Long>>()
        sessao.forEach { item ->
            val carimbo = carimboDeArquivamento(item.first)
            when {
                item in conhecidos -> residuo.add(item)
                estado.inicioMs <= 0L || carimbo == null -> pendentes.add(item)
                carimbo < estado.inicioMs -> residuo.add(item)
                else -> { /* arquivado durante o rascunho: segue com ele */ }
            }
        }
        return ClassificacaoSessao(residuo, pendentes)
    }

    /**
     * O estado que vale para os arquivos listados entre duas leituras.
     *
     * Rascunho em qualquer das duas conta como rascunho aberto. A tela regrava
     * os metadados da sessão a cada mudança, e a leitura que cai no meio dessa
     * gravação encontra o arquivo pela metade e responde «sem rascunho»;
     * acreditar nela mandaria para a quarentena o rosto arquivado do paciente
     * que está na sala. Entre duas leituras com rascunho, vale a mais recente.
     */
    internal fun combinar(antes: EstadoSessao, depois: EstadoSessao): EstadoSessao =
        when {
            depois.rascunhoAtivo -> depois
            antes.rascunhoAtivo -> antes
            else -> depois
        }

    /**
     * Identidade do paciente dono de uma pasta: nome mais prontuário, sem o
     * sufixo de reirradiação do formato antigo.
     */
    fun identidadePaciente(nomePasta: String): String {
        val nome = StorageLocal.chaveNome(StorageLocal.nomeDaPasta(nomePasta))
        val prontuario = StorageLocal.prontuarioDaPasta(nomePasta).trim().uppercase()
        return if (prontuario.isEmpty()) nome else "$nome - $prontuario"
    }

    /**
     * Examina a pasta base atual, se ainda não foi examinada, e move para a
     * quarentena o que tiver de ir.
     *
     * Chamar FORA da thread principal: varre todas as pastas de paciente e pode
     * copiar arquivos entre volumes.
     *
     * @return o relatório desta execução, ou `null` quando esta base já foi
     *   examinada.
     */
    @Synchronized
    fun executarSeNecessario(context: Context): Relatorio? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = StorageLocal.base(context)
        val chave = base.absolutePath
        // A geração das importações é lida junto com a marca, ANTES da
        // varredura: é contra ela que se decide, no fim, se a marca pode ser
        // gravada ([Importacoes]).
        val (marcada, geracao) = synchronized(TRAVA_MARCA) {
            (chave in marcadas(prefs)) to importacoes.geracao
        }
        if (marcada) return null

        val sessaoArquivadas = FotosArquivadas.pasta(SessionManager(context).pastaDeTrabalho())
        val preparo = File(context.filesDir, PASTA_PREPARO)
        val resultado = executarAteLimpar {
            executarEm(
                photos = File(base, "PHOTOS"),
                quarentena = File(base, NOME_PASTA),
                sessaoArquivadas = sessaoArquivadas,
                estadoSessao = { estadoDaSessao(context) },
                preparo = preparo)
        }
        if (resultado.limpa) {
            synchronized(TRAVA_MARCA) {
                if (importacoes.permitemMarcar(geracao)) {
                    prefs.edit().putStringSet(CHAVE_BASES, HashSet(marcadas(prefs) + chave)).apply()
                }
            }
        }
        return resultado.relatorio
    }

    /**
     * Devolve a pasta base atual ao estado de não examinada.
     *
     * Para quem grava fotos de paciente vindas de fora (importação de pacote):
     * a marca de base examinada vale para o disco como ele estava, e o que
     * chegou depois pode trazer a mesma foto arquivada em dois pacientes. A
     * próxima [executarSeNecessario] varre de novo. Sincronizado com ela: uma
     * varredura em andamento termina antes, e não grava a marca por cima.
     */
    @Synchronized
    fun reexaminar(context: Context) {
        synchronized(TRAVA_MARCA) { desmarcar(context) }
    }

    /**
     * Abre uma gravação de fotos de paciente vindas de fora (importação de
     * pacote). Chamar ANTES de gravar a primeira foto.
     *
     * Tira a marca da base atual e impede que ela volte enquanto a gravação
     * estiver aberta: uma passada disparada nesse meio-tempo — pela Home, pela
     * reabertura de uma simulação — examinaria o disco pela metade, e a marca
     * que ela gravasse liberaria para o motor de sincronia o que chegasse
     * depois. Não espera a varredura em curso: a geração nova já a impede de
     * marcar. Toda chamada pede um [fecharImportacao], num `finally`.
     */
    fun abrirImportacao(context: Context) {
        synchronized(TRAVA_MARCA) {
            importacoes.abrir()
            desmarcar(context)
        }
    }

    /**
     * Fecha o que [abrirImportacao] abriu. A marca não volta aqui: volta na
     * próxima passada limpa que começar depois disto.
     */
    fun fecharImportacao() {
        synchronized(TRAVA_MARCA) { importacoes.fechar() }
    }

    /**
     * A pasta base atual já passou por uma varredura limpa, e nenhuma
     * importação de fotos está aberta?
     *
     * É a pergunta do motor de sincronia antes de enviar `ARQUIVADAS/` das
     * pastas de paciente: enquanto a resposta for não, a mesma foto arquivada
     * pode estar na pasta de dois pacientes, e o que sobe ao destino nunca
     * mais é apagado de lá. Sincronizada com a gravação e a remoção da marca,
     * sem esperar a varredura em curso — que, enquanto roda, ainda não marcou
     * a base, e a resposta já é não. Lê preferências; chamar fora da thread
     * principal.
     */
    fun baseConferida(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val chave = StorageLocal.base(context).absolutePath
        return synchronized(TRAVA_MARCA) {
            importacoes.abertas == 0 && chave in marcadas(prefs)
        }
    }

    /** As bases marcadas. O conjunto é do `SharedPreferences`: não alterar. */
    private fun marcadas(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(CHAVE_BASES, null).orEmpty()

    /**
     * Tira a marca da base atual. Só sob [TRAVA_MARCA].
     *
     * `commit`, e não `apply`: quem tira a marca vai gravar fotos em seguida, e
     * a marca que ficasse só na memória voltaria do disco se o processo
     * morresse no meio — com as fotos gravadas e ninguém para examiná-las.
     */
    private fun desmarcar(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val chave = StorageLocal.base(context).absolutePath
        val feitas = marcadas(prefs)
        if (chave !in feitas) return
        prefs.edit().putStringSet(CHAVE_BASES, HashSet(feitas - chave)).commit()
    }

    /**
     * O rascunho como está em disco AGORA: uma instância nova lê os metadados
     * de novo. Sessão marcada com início conta como rascunho mesmo antes de ter
     * paciente ou foto, porque o que ela arquivar depois disso é dela.
     */
    private fun estadoDaSessao(context: Context): EstadoSessao {
        val s = SessionManager(context)
        val inicio = s.timestampInicio
        return EstadoSessao(rascunhoAtivo = s.temRascunho() || inicio > 0L, inicioMs = inicio)
    }

    /**
     * Repete [passada] até uma sair limpa, no máximo [maxPassadas] vezes.
     *
     * A passada que moveu algo é seguida de outra, que confere: com a prova do
     * que acabou de ir para a quarentena, ela encontra a cópia que chegou a uma
     * pasta já listada enquanto a anterior varria. Falha de movimento ou
     * arquivo pendente não se resolvem repetindo agora — ficam para a próxima
     * abertura.
     */
    internal fun executarAteLimpar(
        maxPassadas: Int = MAX_PASSADAS,
        passada: () -> Execucao
    ): Resultado {
        var examinados = 0
        var movidos = 0
        val pastas = HashSet<String>()
        var feitas = 0
        while (feitas < maxPassadas) {
            val ex = passada()
            feitas++
            examinados = maxOf(examinados, ex.relatorio.examinados)
            movidos += ex.relatorio.movidos
            pastas.addAll(ex.pastasAfetadas)
            if (ex.limpa) return Resultado(Relatorio(examinados, movidos, pastas.size), true, feitas)
            if (ex.falhas > 0 || ex.pendentes > 0) break
        }
        return Resultado(Relatorio(examinados, movidos, pastas.size), false, feitas)
    }

    /**
     * Uma passada sobre pastas já resolvidas, sem `Context`: é o que o teste
     * alcança com pastas temporárias.
     *
     * Ordem: (1) a sessão, com o que se decide sem as pastas de paciente;
     * (2) as pastas de paciente, com a quarentena como prova; (3) a sessão de
     * novo, com o que só se sabe resíduo comparando com as pastas. Em (1) e
     * (3) o estado do rascunho é lido logo antes e logo depois da listagem, e
     * é ele que decide — não um estado lido antes da varredura longa.
     *
     * @param preparo pasta no mesmo volume da sessão por onde o resíduo passa
     *   antes da cópia para a quarentena ([PASTA_PREPARO]); nula, o resíduo vai
     *   direto, copiado de dentro da sessão.
     */
    internal fun executarEm(
        photos: File,
        quarentena: File,
        sessaoArquivadas: File?,
        estadoSessao: () -> EstadoSessao,
        preparo: File? = null,
        agoraMs: Long = System.currentTimeMillis()
    ): Execucao {
        var falhas = 0
        var movidosSessao = 0
        val vistosNaSessao = HashSet<String>()
        val falharamNaSessao = HashSet<String>()
        val separados = HashSet<String>()
        val vistosNoPreparo = HashSet<String>()
        val falharamNoPreparo = HashSet<String>()
        val destinoSessao = File(quarentena, PASTA_SESSAO)

        // Resíduo da sessão para a quarentena. Primeiro TODO ele sai da sessão,
        // por renomeação ao preparo; só depois começa a cópia entre volumes,
        // a partir do preparo — inclusive o que uma execução interrompida
        // deixou lá. O que falhou não se tenta de novo em (3): contaria a
        // mesma falha duas vezes.
        fun tirarDaSessao(residuo: List<File>) {
            val semPreparo = mutableListOf<File>()
            residuo.forEach { f ->
                val separado = separar(f, preparo)
                if (separado != null) separados.add(separado.name) else semPreparo.add(f)
            }
            semPreparo.forEach { f ->
                if (mover(f, destinoSessao)) {
                    movidosSessao++
                } else {
                    falhas++
                    falharamNaSessao.add(f.name)
                }
            }
            if (preparo == null) return
            listarArquivos(preparo).forEach { f ->
                if (f.name in falharamNoPreparo) return@forEach
                vistosNoPreparo.add(f.name)
                if (mover(f, destinoSessao)) {
                    movidosSessao++
                } else {
                    falhas++
                    falharamNoPreparo.add(f.name)
                }
            }
        }

        // (1) Sessão, antes da varredura longa.
        val sessao1 = lerSessao(sessaoArquivadas, estadoSessao)
        val tamanhos1 = sessao1.arquivos.associateWith { it.length() }
        val residuo1 = classificarSessao(
            tamanhos1.map { (f, t) -> f.name to t }, emptyList(), sessao1.estado)
            .residuo.toHashSet()
        tamanhos1.keys.forEach { vistosNaSessao.add(it.name) }
        tirarDaSessao(tamanhos1.filter { (f, t) -> (f.name to t) in residuo1 }.keys.toList())

        // (2) Pastas de paciente, e o que já está na quarentena como prova.
        val entradas = mutableListOf<Entrada>()
        val arquivoDe = HashMap<Entrada, File>()
        photos.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.forEach { pasta ->
                listarArquivos(FotosArquivadas.pasta(pasta)).forEach { f ->
                    val e = Entrada(identidadePaciente(pasta.name), pasta.name, f.name, f.length())
                    entradas.add(e)
                    arquivoDe[e] = f
                }
            }
        val referencias = entradas + evidenciasDaQuarentena(quarentena)
        val movidosPorPasta = sortedMapOf<String, Int>()
        selecionar(referencias).forEach { e ->
            val f = arquivoDe[e] ?: return@forEach
            if (mover(f, File(quarentena, e.pasta))) {
                movidosPorPasta[e.pasta] = (movidosPorPasta[e.pasta] ?: 0) + 1
            } else {
                falhas++
            }
        }

        // (3) Sessão de novo, com o estado do rascunho lido agora.
        val sessao3 = lerSessao(sessaoArquivadas, estadoSessao)
        val tamanhos3 = sessao3.arquivos
            .filter { it.name !in falharamNaSessao }
            .associateWith { it.length() }
        val classificacao3 = classificarSessao(
            tamanhos3.map { (f, t) -> f.name to t }, referencias, sessao3.estado)
        val residuo3 = classificacao3.residuo.toHashSet()
        tamanhos3.keys.forEach { vistosNaSessao.add(it.name) }
        tirarDaSessao(tamanhos3.filter { (f, t) -> (f.name to t) in residuo3 }.keys.toList())
        val pendentes = classificacao3.pendentes.size

        val movidos = movidosPorPasta.values.sum() + movidosSessao
        val relatorio = Relatorio(
            // O que estava no preparo sem ter passado pela sessão nesta
            // passada sobrou de uma execução interrompida: também foi visto.
            examinados = entradas.size + vistosNaSessao.size + (vistosNoPreparo - separados).size,
            movidos = movidos,
            pastas = movidosPorPasta.size)
        if (movidos > 0 || falhas > 0) {
            escreverRelatorio(quarentena, relatorio, falhas, pendentes,
                movidosPorPasta, movidosSessao, agoraMs)
        }
        return Execucao(relatorio, falhas, pendentes, movidosPorPasta.keys.toSet())
    }

    /** Arquivos de `ARQUIVADAS/` da sessão, com o estado do rascunho que vale para eles. */
    private class Sessao(val arquivos: List<File>, val estado: EstadoSessao)

    /**
     * Lista a sessão entre duas leituras do rascunho ([combinar]).
     *
     * A leitura de depois é a que pega o rascunho aberto durante a listagem;
     * a de antes protege contra a leitura que cai no meio de uma gravação dos
     * metadados. Pasta vazia dispensa as duas: sem arquivo, não há o que
     * decidir.
     */
    private fun lerSessao(dir: File?, estadoSessao: () -> EstadoSessao): Sessao {
        if (dir == null || listarArquivos(dir).isEmpty()) {
            return Sessao(emptyList(), EstadoSessao(rascunhoAtivo = false, inicioMs = 0L))
        }
        val antes = estadoSessao()
        val arquivos = listarArquivos(dir)
        val depois = estadoSessao()
        return Sessao(arquivos, combinar(antes, depois))
    }

    private fun listarArquivos(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * O que já está na quarentena, como prova para o agrupamento.
     *
     * Uma subpasta por pasta de paciente de origem. A da sessão de captura fica
     * de fora: ela não diz de que paciente a foto veio, e contá-la tiraria da
     * pasta do dono a única cópia de uma foto que também tinha sobrado na
     * sessão.
     */
    internal fun evidenciasDaQuarentena(quarentena: File): List<Entrada> {
        val lista = mutableListOf<Entrada>()
        quarentena.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != PASTA_SESSAO }
            ?.sortedBy { it.name }
            ?.forEach { pasta ->
                val paciente = identidadePaciente(pasta.name)
                listarArquivos(pasta).forEach { f ->
                    val tamanho = f.length()
                    nomesDeOrigem(f.name).forEach { nome ->
                        lista.add(Entrada(paciente, pasta.name, nome, tamanho, evidencia = true))
                    }
                }
            }
        return lista
    }

    /**
     * O nome como está na quarentena e, quando houver, sem o `_n` que
     * [destinoLivre] acrescentou por colisão. O nome que [FotosArquivadas]
     * grava termina no carimbo (`_ARQ<ms>`) ou no sufixo do par, nunca em
     * `_<dígitos>`, então tirar esse final só desfaz a colisão.
     */
    internal fun nomesDeOrigem(nome: String): Set<String> {
        val ponto = nome.lastIndexOf('.')
        val radical = if (ponto > 0) nome.substring(0, ponto) else nome
        val extensao = if (ponto > 0) nome.substring(ponto) else ""
        val semSufixo = SUFIXO_COLISAO.matchEntire(radical)?.groupValues?.get(1)
        return if (semSufixo == null) setOf(nome) else setOf(nome, semSufixo + extensao)
    }

    /**
     * Tira um arquivo da sessão por renomeação para [preparo], no mesmo
     * volume: um passo só, sem cópia.
     *
     * @return o arquivo já no preparo, ou `null` sem preparo ou quando a
     *   renomeação falha — aí o arquivo continua na sessão e segue pelo
     *   caminho de [mover].
     */
    private fun separar(origem: File, preparo: File?): File? {
        if (preparo == null) return null
        if (!preparo.isDirectory && !preparo.mkdirs()) return null
        val destino = destinoLivre(preparo, origem.name)
        return if (origem.renameTo(destino)) destino else null
    }

    /**
     * Move sem apagar: renomeia; entre volumes, copia, confere o tamanho e só
     * então remove a origem.
     *
     * GUARDA: o tamanho de referência é lido ANTES da cópia. A sessão pode ser
     * limpa enquanto um arquivo dela é copiado (descarte do rascunho, fim da
     * finalização, validade vencida); a leitura termina pelo descritor já
     * aberto e o destino sai completo, mas a origem lida depois diria tamanho
     * zero. Destino só é apagado enquanto a origem ainda existe: sem ela, o
     * destino é o que restou da foto e fica, mesmo quando não confere — aí
     * conta como falha, para a base não ser dada como examinada. Nome que
     * passou a existir no destino antes da cópia é de outro arquivo, e não é
     * tocado.
     *
     * [renomear] e [copiar] existem para o teste simular a renomeação entre
     * volumes, que falha, e a origem removida no meio da cópia.
     */
    internal fun mover(
        origem: File,
        destinoDir: File,
        renomear: (File, File) -> Boolean = { o, d -> o.renameTo(d) },
        copiar: (File, File) -> Unit = { o, d -> o.copyTo(d, overwrite = false) }
    ): Boolean {
        if (!destinoDir.isDirectory && !destinoDir.mkdirs()) return false
        val destino = destinoLivre(destinoDir, origem.name)
        if (renomear(origem, destino)) return true
        val tamanho = origem.length()
        return try {
            copiar(origem, destino)
            val completo = destino.length() == tamanho
            when {
                completo && origem.delete() -> true
                !origem.exists() -> completo
                else -> {
                    destino.delete()
                    false
                }
            }
        } catch (_: kotlin.io.FileAlreadyExistsException) {
            false
        } catch (_: Exception) {
            if (destino.exists() && origem.exists()) destino.delete()
            false
        }
    }

    /** Nome que ainda não existe na pasta: o próprio, ou com `_1`, `_2`... antes da extensão. */
    private fun destinoLivre(dir: File, nome: String): File {
        val alvo = File(dir, nome)
        if (!alvo.exists()) return alvo
        val ponto = nome.lastIndexOf('.')
        val radical = if (ponto > 0) nome.substring(0, ponto) else nome
        val extensao = if (ponto > 0) nome.substring(ponto) else ""
        var n = 1
        var candidato = File(dir, "${radical}_$n$extensao")
        while (candidato.exists()) {
            n++
            candidato = File(dir, "${radical}_$n$extensao")
        }
        return candidato
    }

    /**
     * Relatório em texto simples, acrescentado a cada passada que moveu algo
     * ou falhou. Só contagens e nomes de pasta: nenhum conteúdo de foto.
     */
    private fun escreverRelatorio(
        quarentena: File,
        r: Relatorio,
        falhas: Int,
        pendentes: Int,
        movidosPorPasta: Map<String, Int>,
        movidosSessao: Int,
        agoraMs: Long
    ) {
        try {
            if (!quarentena.isDirectory && !quarentena.mkdirs()) return
            val quando = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(agoraMs))
            val sb = StringBuilder()
            sb.append("QUARENTENA DE FOTOS ARQUIVADAS - PhotoID RT\n")
            sb.append("Executada em: ").append(quando).append("\n\n")
            sb.append("Arquivos examinados: ").append(r.examinados).append('\n')
            sb.append("Arquivos movidos para a quarentena: ").append(r.movidos).append('\n')
            sb.append("Pastas de paciente afetadas: ").append(r.pastas).append('\n')
            if (falhas > 0) {
                sb.append("Arquivos que nao puderam ser movidos (ficaram no lugar): ")
                    .append(falhas).append('\n')
            }
            if (pendentes > 0) {
                sb.append("Arquivos da sessao de captura deixados no lugar ")
                    .append("(rascunho aberto, sem como saber se sao dele): ")
                    .append(pendentes).append('\n')
            }
            sb.append('\n')
            sb.append("POR QUE ESTES ARQUIVOS ESTAO AQUI\n")
            sb.append("  Cada foto arquivada abaixo estava na subpasta ARQUIVADAS de pastas de\n")
            sb.append("  pacientes diferentes, com o mesmo nome e o mesmo tamanho. Uma foto\n")
            sb.append("  arquivada pertence a um paciente so; presente em dois, nao ha como saber\n")
            sb.append("  de qual e. Nada foi apagado: todas as copias foram movidas para ca, cada\n")
            sb.append("  uma numa pasta com o nome da pasta de onde saiu. Fora da pasta do\n")
            sb.append("  paciente, elas nao entram na ficha e nao sao oferecidas para restaurar.\n")
            sb.append("  As da sessao de captura sao sobras de capturas anteriores, que a\n")
            sb.append("  proxima finalizacao levaria para a pasta de outro paciente.\n")
            sb.append('\n')
            sb.append("PASTAS DE ORIGEM (arquivos movidos)\n")
            movidosPorPasta.forEach { (pasta, n) ->
                sb.append("  ").append(pasta).append(": ").append(n).append('\n')
            }
            if (movidosSessao > 0) {
                sb.append("  ").append(PASTA_SESSAO).append(" (sessao de captura do tablet): ")
                    .append(movidosSessao).append('\n')
            }
            sb.append("\n----------------------------------------------------------------\n\n")
            File(quarentena, NOME_RELATORIO).appendText(sb.toString())
        } catch (_: Exception) {
        }
    }
}
