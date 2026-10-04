package com.radioterapia.ai.sync

import android.content.Context
import com.radioterapia.ai.sync.destino.Destinos
import com.radioterapia.ai.transfer.CopiaConfiguracao
import com.radioterapia.ai.update.BackupPreAtualizacao
import com.radioterapia.ai.util.FotosArquivadas
import com.radioterapia.ai.util.QuarentenaArquivadas
import com.radioterapia.ai.util.StorageLocal
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A varredura: o que está no tablet e ainda não está no destino.
 *
 * UMA VIA, E SÓ. O motor lê a pasta local e escreve no remoto. Nunca apaga,
 * nunca renomeia, nunca traz nada de volta. Não é limitação que um dia se tira:
 * é o que separa "cópia de segurança" de "espelhamento", e espelhamento com
 * dado de paciente significa que uma exclusão acidental no tablet apaga o que
 * está no servidor da instituição — que é a cópia boa.
 *
 * A ORIGEM É FIXA. É a pasta de armazenamento do app
 * ([StorageLocal.base]), com `PHOTOS` e `DATABASE` dentro. Deixar o usuário
 * escolher a origem pareceria flexibilidade e seria uma armadilha: apontar para
 * a raiz do cartão faria o tablet subir o acervo de fotos pessoais de quem o
 * usa, e ninguém descobriria até a fatura ou a auditoria.
 *
 * UMA VARREDURA POR VEZ NO PROCESSO. O trabalho periódico e o imediato do
 * WorkManager têm nomes únicos DIFERENTES, e os botões de sincronizar da tela
 * chamam o motor direto, sem passar pelo WorkManager. Sem a [TRAVA], duas
 * varreduras rodariam juntas: cada uma com o próprio índice em memória,
 * reenviando o que a outra acabou de enviar, e no destino SAF uma apagando o
 * arquivo que a outra está gravando. Quem chega com outra varredura em curso
 * ESPERA, dentro do próprio prazo — e, como o índice em disco já registrou o
 * que subiu, encontra pouco a fazer. Se o prazo acaba na espera, sai sem
 * enviar nada e devolve a contagem do índice.
 *
 * A CONFIGURAÇÃO PODE MUDAR NO MEIO DA VARREDURA. Ela dura minutos e começa
 * com a lista de perfis daquele instante; nesse tempo o serviço pode desligar
 * a sincronização, desativar um destino ou corrigir o caminho remoto. Antes de
 * cada envio a varredura confere o interruptor mestre, o pedido de parada do
 * trabalho e o perfil gravado, e desligado quer dizer nenhuma conexão nova
 * dali em diante — ver [sincronizar].
 */
class MotorSync(private val context: Context) {

    /**
     * O resultado de uma varredura, por perfil.
     *
     * [pendentes] é o que ficou para a próxima rodada — por falha temporária,
     * porque o tempo acabou ou porque a varredura foi interrompida. É o número
     * que a tela mostra, e é ele que responde "já subiu tudo?" sem obrigar
     * ninguém a contar arquivo.
     *
     * [interrompido]: a varredura deste perfil parou antes do fim porque a
     * configuração mudou enquanto ela rodava — interruptor mestre desligado,
     * trabalho parado pelo sistema, perfil desativado, removido ou com o
     * destino trocado. O estado gravado do perfil (última sincronização, último
     * erro) não é tocado: a rodada não valeu para a configuração de agora.
     *
     * [emAndamento]: outra varredura ficou com a vez durante todo o prazo desta.
     * Nada foi enviado por esta chamada; [jaEstavam] e [pendentes] vêm do
     * índice, sem rede. É o caso de a tela dizer que já há uma sincronização em
     * andamento, e não que falhou.
     */
    data class Resumo(
        val perfil: String,
        val enviados: Int = 0,
        val jaEstavam: Int = 0,
        val pendentes: Int = 0,
        val erro: String = "",
        val interrompido: Boolean = false,
        val emAndamento: Boolean = false,
    ) {
        val houveFalha: Boolean get() = erro.isNotEmpty() || pendentes > 0
    }

    /**
     * O que a varredura encontra ao reler o perfil gravado no meio da rodada.
     * Ver [vigencia].
     */
    internal enum class Vigencia {
        /** Mesmo destino e ainda ativo: segue enviando. */
        VALE,
        /** Mesmo destino, desativado durante a rodada: para; o que chegou é marcado. */
        DESATIVADO,
        /** Perfil apagado: para e não marca — o índice dele foi podado. */
        REMOVIDO,
        /** Destino trocado: para sem marcar — o índice agora é do destino novo. */
        DESTINO_TROCADO,
    }

    /**
     * O que [conferirPasta] encontrou.
     *
     * [total] é quantos arquivos da pasta o motor enviaria; [pendentes], quantos
     * desses ainda faltam em pelo menos um destino ativo.
     */
    data class Conferencia(val total: Int, val pendentes: Int)

    /**
     * Roda todos os perfis ativos.
     *
     * @param limiteMs quanto tempo a chamada pode durar, CONTADO DA ENTRADA. O
     *   `WorkManager` para o trabalho aos 10 minutos, mas não interrompe a
     *   thread: ela seguiria enviando sem a trava de energia do trabalho,
     *   segurando a [TRAVA], enquanto o sistema agenda outra rodada que fica
     *   na fila atrás dela. Parar sozinho antes disso, com o índice em dia, é o
     *   que mantém uma rodada por vez e a fila incremental.
     *
     *   GUARDA: o prazo nasce ANTES da trava. Os 10 minutos do `WorkManager`
     *   correm desde o início do `doWork`, inclusive enquanto ele espera a vez
     *   de outra varredura; um prazo que só começasse depois da espera somaria
     *   os dois tempos e passaria do limite. Pela mesma conta, os botões da
     *   tela, que passam 45 segundos, não giram minutos atrás de um trabalho
     *   em segundo plano: esgotado o prazo na espera, a chamada devolve a
     *   contagem do índice com [Resumo.emAndamento] e sai.
     * @param continuar consultado antes de cada envio e durante a espera, junto
     *   com o interruptor mestre. O trabalho do `WorkManager` passa
     *   `{ !isStopped }`: parado pelo sistema, ele para de abrir conexão em vez
     *   de seguir até o fim do prazo. O que sobra continua pendente.
     */
    fun sincronizarTudo(limiteMs: Long = LIMITE_PADRAO_MS,
                        continuar: () -> Boolean = { true }): List<Resumo> {
        val fim = prazoFinal(System.currentTimeMillis(), limiteMs)
        val cfg = SyncConfig(context)
        val seguir = { cfg.ativo && continuar() }
        if (!cfg.ativo) return emptyList()

        if (!pegarTrava(TRAVA, fim, seguir)) {
            return if (cfg.ativo) contagemSemRede(perfisAtivos(PerfilStore(context)), emAndamento = true)
                   else emptyList()
        }
        try {
            // Desligada durante a espera: sai como se nunca tivesse entrado.
            if (!cfg.ativo) return emptyList()

            val store = PerfilStore(context)
            val perfis = perfisAtivos(store)
            if (perfis.isEmpty()) return emptyList()

            // A cópia da configuração nasce ANTES da lista, para entrar nesta
            // mesma rodada. Depois das saídas acima: com a sincronia desligada,
            // ou sem destino, nada é criado. Sem prazo restante ela fica para a
            // rodada seguinte, que terá tempo de enviá-la. Falha dela devolve
            // nulo e a rodada segue — ela nunca segura o envio clínico.
            if (seguir() && System.currentTimeMillis() < fim) CopiaConfiguracao.garantir(context)

            val arquivos = listarLocais()
            val resumos = perfis.map { p -> sincronizar(p, store, arquivos, fim, seguir) }

            cfg.ultimaVarredura = System.currentTimeMillis()
            // O estado de cada perfil volta para o disco numa gravação só: o
            // PerfilStore poda senhas e índices a cada `salvarTodos`, e chamá-lo por
            // perfil faria a poda rodar sobre um estado intermediário.
            //
            // Ler e regravar acontece sob a TRAVA_PERFIS: sem ela, um perfil que
            // a tela gravasse entre a leitura e a gravação voltaria à versão
            // antiga. Perfil interrompido, ou cujo destino mudou depois de
            // varrido, fica como está: a rodada não falou com o destino de agora.
            synchronized(TRAVA_PERFIS) {
                val atualizados = store.listar().map { p ->
                    val r = resumos.firstOrNull { it.perfil == p.id } ?: return@map p
                    val varrido = perfis.firstOrNull { it.id == p.id } ?: return@map p
                    if (r.interrompido || !mesmoDestino(varrido, p)) return@map p
                    p.copy(
                        ultimaSincronizacao = if (r.erro.isEmpty()) System.currentTimeMillis()
                                              else p.ultimaSincronizacao,
                        ultimoErro = r.erro
                    )
                }
                store.salvarTodos(atualizados)
            }
            return resumos
        } finally {
            TRAVA.unlock()
        }
    }

    /**
     * Um perfil só, para o botão "sincronizar agora" da tela.
     *
     * Mesmo prazo, mesma espera e mesmas paradas de [sincronizarTudo]. Com o
     * interruptor mestre desligado não abre conexão: devolve só a contagem do
     * índice. O perfil tem de estar gravado: a varredura o relê antes de cada
     * envio, e perfil que não está no disco conta como removido.
     */
    fun sincronizarUm(perfil: PerfilSync, limiteMs: Long = LIMITE_PADRAO_MS,
                      continuar: () -> Boolean = { true }): Resumo {
        val fim = prazoFinal(System.currentTimeMillis(), limiteMs)
        val cfg = SyncConfig(context)
        val seguir = { cfg.ativo && continuar() }
        if (!cfg.ativo) return contagemSemRede(listOf(perfil), emAndamento = false).first()

        if (!pegarTrava(TRAVA, fim, seguir)) {
            return contagemSemRede(listOf(perfil), emAndamento = cfg.ativo).first()
        }
        try {
            // A cópia da configuração segue o interruptor mestre e o prazo, como
            // em sincronizarTudo: desligado, nada novo nasce na pasta.
            if (seguir() && System.currentTimeMillis() < fim) CopiaConfiguracao.garantir(context)
            return sincronizar(perfil, PerfilStore(context), listarLocais(), fim, seguir)
        } finally {
            TRAVA.unlock()
        }
    }

    /**
     * Confere, sem rede, se a pasta de uma simulação já está em todos os
     * destinos ativos.
     *
     * Usa o MESMO filtro de pasta e de arquivo e a MESMA chave relativa da
     * varredura ([pastaEntraNaVarredura], [arquivoElegivel], [chaveRelativa]),
     * para que a conferência e o envio nunca discordem sobre o que conta. Só a
     * janela de frescor fica de fora: arquivo recém-escrito, por definição, não
     * foi enviado, e tem de contar como pendente.
     *
     * Sem destino ativo, todo arquivo conta como pendente — nada pode ter
     * subido. Arquivo fora da origem do motor (pasta escolhida por SAF) não
     * conta: o motor nunca o enviaria, e a pasta sai com total zero. A
     * `ARQUIVADAS/` da simulação segue a mesma regra da varredura: com a base
     * ainda não examinada pela quarentena, ela não conta, porque não sobe.
     *
     * Lê disco; chamar fora da thread principal. Não pega a [TRAVA]: é leitura,
     * e esperar uma varredura de minutos travaria a resposta da tela.
     */
    fun conferirPasta(pasta: File): Conferencia {
        if (!pasta.isDirectory) return Conferencia(0, 0)
        val raizAbs = StorageLocal.base(context).absolutePath
        val indices = PerfilStore(context).listar()
            .filter { it.ativo && it.utilizavel() }
            .map { IndiceEnviados(context, it.id) }
        val liberadas = arquivadasLiberadas()
        var total = 0
        var pendentes = 0

        pasta.walkTopDown()
            .onEnter { d -> pastaEntraNaVarredura(d.name, liberadas) }
            .filter { it.isFile }
            .forEach { f ->
                if (!arquivoElegivel(f.name, f.length())) return@forEach
                val rel = chaveRelativa(raizAbs, f.absolutePath) ?: return@forEach
                total++
                if (indices.isEmpty() || indices.any { !it.jaEnviado(rel, f) }) pendentes++
            }

        return Conferencia(total, pendentes)
    }

    /**
     * Um perfil, arquivo por arquivo.
     *
     * O ÍNDICE VEM ANTES DO RELÓGIO. Arquivo que já subiu conta como "já
     * estava" mesmo depois de o prazo acabar, e só o que falta de fato conta
     * como pendente; senão, toda rodada encerrada pelo relógio anunciaria como
     * pendente o acervo que restava na lista, inclusive o que já está no
     * destino.
     *
     * GUARDA: o perfil gravado é relido sob a [TRAVA_PERFIS] antes de cada
     * envio e de novo antes de marcar o arquivo no índice ([vigencia]) — a
     * mesma trava sob a qual a tela troca o destino e zera o índice
     * ([salvarPerfil]). Sem a releitura, a varredura que começou com o destino
     * antigo continuaria mandando o lote para lá, e cada envio recriaria o
     * índice que a tela acabou de apagar, com chaves de arquivos que nunca
     * chegaram ao destino novo: a rodada seguinte os pularia para sempre.
     * Destino trocado para sem marcar nada, e todo o acervo conta como
     * pendente para o destino novo. Perfil desativado ou removido para de
     * enviar; o que já chegou ao destino que o perfil gravado ainda aponta é
     * marcado.
     *
     * Paradas sem relação com o servidor (prazo, interruptor mestre, trabalho
     * parado, perfil mudado) e a recusa permanente desligam a rede deste
     * perfil até o fim da lista: o resto só é contado, pelo índice.
     */
    private fun sincronizar(perfil: PerfilSync, store: PerfilStore,
                            arquivos: List<Local>, fim: Long, seguir: () -> Boolean): Resumo {
        val indice = IndiceEnviados(context, perfil.id)
        val destino = Destinos.criar(context, perfil, store.senha(perfil.id))
        var enviados = 0
        var jaEstavam = 0
        var pendentes = 0
        var erro = ""
        var interrompido = false
        var semRede = false

        fun vigenciaAtual(): Vigencia =
            synchronized(TRAVA_PERFIS) { vigencia(perfil, store.obter(perfil.id)) }

        try {
            for (a in arquivos) {
                if (indice.jaEnviado(a.relativo, a.arquivo)) { jaEstavam++; continue }

                // Não é erro: é a rodada acabando. O que sobrou continua
                // pendente e sobe na próxima, sem reenviar o que já subiu.
                if (!semRede && System.currentTimeMillis() >= fim) semRede = true

                if (!semRede && !seguir()) {
                    // Interruptor mestre desligado ou trabalho parado pelo
                    // sistema: desligado quer dizer nenhuma conexão nova.
                    semRede = true
                    interrompido = true
                }

                if (!semRede) {
                    when (vigenciaAtual()) {
                        Vigencia.VALE -> {}
                        Vigencia.DESTINO_TROCADO -> return destinoTrocado(perfil, arquivos)
                        Vigencia.DESATIVADO, Vigencia.REMOVIDO -> {
                            semRede = true
                            interrompido = true
                        }
                    }
                }

                if (semRede) { pendentes++; continue }

                // GUARDA: `ARQUIVADAS/` sobe só com a base examinada AGORA, e
                // não só quando a lista foi feita. Uma importação de fotos
                // aberta depois da listagem pode ter regravado o arquivo
                // listado com a foto de outro paciente.
                if (dentroDeArquivadas(a.relativo) && !arquivadasLiberadas()) {
                    pendentes++
                    continue
                }

                val r = destino.enviar(a.arquivo, a.pasta, a.nome)
                when {
                    r.sucesso -> {
                        // Conferir e marcar num passo só, sob a trava da tela:
                        // entre a conferência e a anotação ela não consegue
                        // zerar o índice nem gravar o destino novo.
                        val v = synchronized(TRAVA_PERFIS) {
                            val atual = vigencia(perfil, store.obter(perfil.id))
                            if (podeMarcar(atual)) indice.marcar(a.relativo, a.arquivo)
                            atual
                        }
                        when (v) {
                            Vigencia.VALE -> enviados++
                            Vigencia.DESTINO_TROCADO -> return destinoTrocado(perfil, arquivos)
                            Vigencia.DESATIVADO, Vigencia.REMOVIDO -> {
                                enviados++
                                semRede = true
                                interrompido = true
                            }
                        }
                    }
                    r.permanente -> {
                        // Recusa que não melhora com repetição para a varredura
                        // inteira deste perfil: insistir arquivo por arquivo
                        // contra uma credencial recusada só enche o log do
                        // servidor e gasta a bateria do tablet.
                        erro = r.erro
                        pendentes++
                        semRede = true
                    }
                    else -> { erro = r.erro; pendentes++ }
                }
            }
        } catch (e: Exception) {
            erro = "${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}"
        } finally {
            destino.fechar()
        }

        // O laço só relê o perfil quando vai enviar. Com a lista inteira
        // presente no índice em memória, carregado antes de a tela trocar o
        // destino, ele termina sem ter conferido nada, e o resumo diria
        // "tudo enviado" ao destino novo, cujo índice foi zerado.
        if (vigenciaAtual() == Vigencia.DESTINO_TROCADO) return destinoTrocado(perfil, arquivos)

        return Resumo(perfil.id, enviados, jaEstavam, pendentes, erro, interrompido)
    }

    /**
     * O resumo de um perfil cujo destino mudou no meio da rodada.
     *
     * O índice que a tela zerou agora é do destino novo, e nele nada subiu:
     * todo o acervo conta como pendente, e o que esta rodada mandou ao destino
     * antigo não entra na conta.
     */
    private fun destinoTrocado(perfil: PerfilSync, arquivos: List<Local>): Resumo =
        Resumo(perfil.id, pendentes = arquivos.size, interrompido = true)

    /**
     * O que falta, pelo índice de cada perfil, sem abrir conexão.
     *
     * Para quando esta chamada não pode enviar: outra varredura ficou com a
     * vez durante todo o prazo, ou o interruptor mestre está desligado. Lê o
     * índice sem a [TRAVA], como [conferirPasta]: uma linha que a varredura em
     * curso estiver anotando só faz o arquivo contar como pendente.
     */
    private fun contagemSemRede(perfis: List<PerfilSync>, emAndamento: Boolean): List<Resumo> {
        if (perfis.isEmpty()) return emptyList()
        val arquivos = listarLocais()
        return perfis.map { p ->
            val indice = IndiceEnviados(context, p.id)
            val ja = arquivos.count { indice.jaEnviado(it.relativo, it.arquivo) }
            Resumo(p.id, jaEstavam = ja, pendentes = arquivos.size - ja, emAndamento = emAndamento)
        }
    }

    private fun perfisAtivos(store: PerfilStore): List<PerfilSync> =
        store.listar().filter { it.ativo && it.utilizavel() }

    /**
     * Um arquivo local, onde ele fica em relação à raiz, e a data de
     * modificação lida UMA vez.
     *
     * A data fica guardada porque é a chave da ordenação: relida a cada
     * comparação, um arquivo regravado no meio da ordenação faria o comparador
     * se contradizer, e a ordenação do Java lança exceção quando isso acontece.
     */
    private data class Local(val arquivo: File, val relativo: String, val mtime: Long) {
        val pasta: String get() = relativo.substringBeforeLast('/', "")
        val nome: String get() = relativo.substringAfterLast('/')
    }

    /**
     * Tudo o que existe sob a pasta do app, em ordem estável.
     *
     * ORDEM ESTÁVEL importa mais do que parece: a varredura pode ser
     * interrompida pelo limite de tempo, e com ordem aleatória a rodada
     * seguinte começaria por outro lugar — o acervo antigo e o novo se
     * alternariam, e o que chegou hoje demoraria dias para subir. Ordenado por
     * data de modificação CRESCENTE, o mais antigo sai primeiro e a fila
     * termina; o que acabou de ser fotografado entra pelos gatilhos, que rodam
     * na hora.
     *
     * A CÓPIA DA CONFIGURAÇÃO VAI NA FRENTE de tudo ([prioridade]). São
     * kilobytes, e é o que se precisa para reinstalar um tablet; numa primeira
     * sincronia com acervo grande e limite de 45 s, ela ficaria presa atrás de
     * milhares de fotos.
     *
     * `ARQUIVADAS/` SÓ ENTRA COM A BASE EXAMINADA ([pastaEntraNaVarredura]). A
     * marca da quarentena é lida antes e de novo depois da listagem: uma
     * importação de fotos tira a marca antes de gravar a primeira, então marca
     * presente no fim quer dizer que nada do que foi listado veio dela. Marca
     * que caiu durante a listagem tira da lista tudo o que está em
     * `ARQUIVADAS/` ([entraNaLista]).
     */
    private fun listarLocais(): List<Local> {
        val raiz = StorageLocal.base(context)
        if (!raiz.isDirectory) return emptyList()
        val agora = System.currentTimeMillis()
        val achados = mutableListOf<Local>()
        val raizAbs = raiz.absolutePath
        val liberadasAntes = arquivadasLiberadas()

        raiz.walkTopDown()
            .onEnter { d -> pastaEntraNaVarredura(d.name, liberadasAntes) }
            .filter { it.isFile }
            .forEach { f ->
                if (!arquivoElegivel(f.name, f.length())) return@forEach
                val rel = chaveRelativa(raizAbs, f.absolutePath) ?: return@forEach
                val mtime = f.lastModified()
                // ARQUIVO RECÉM-ESCRITO FICA PARA A PRÓXIMA. Uma foto salva há
                // meio segundo pode ainda estar sendo gravada; enviá-la truncada
                // marcaria no índice um arquivo incompleto, e ele nunca mais
                // subiria inteiro — o tamanho no índice é o tamanho truncado.
                // A cópia da configuração é a exceção: ela ganha o nome final
                // por rename, já completa, e o temporário dela é pulado pelo
                // filtro de arquivo. Isenta, ela sobe na mesma rodada.
                if (!ehCopiaConfiguracao(rel) && agora - mtime < JANELA_FRESCOR_MS) return@forEach
                achados.add(Local(f, rel, mtime))
            }

        val liberadas = liberadasAntes && arquivadasLiberadas()
        return achados
            .filter { entraNaLista(it.relativo, liberadas) }
            .sortedWith(compareBy<Local>({ prioridade(it.relativo) }, { it.mtime }))
    }

    /**
     * A quarentena já deu a base como examinada
     * ([QuarentenaArquivadas.baseConferida])? Falha de leitura conta como não:
     * o custo é `ARQUIVADAS/` esperar a rodada seguinte.
     */
    private fun arquivadasLiberadas(): Boolean =
        try { QuarentenaArquivadas.baseConferida(context) } catch (_: Exception) { false }

    companion object {

        /**
         * GUARDA: uma varredura por vez no processo — ver o KDoc da classe.
         * O WorkManager roda no processo do app, então este objeto cobre os
         * três chamadores (worker, botão da Home, botão das Configurações).
         *
         * `ReentrantLock`, e não `synchronized`, porque a espera tem prazo
         * ([pegarTrava]): `synchronized` esperaria o tempo que a varredura da
         * frente levasse.
         */
        private val TRAVA = ReentrantLock()

        /**
         * GUARDA: serializa quem grava perfis com a conferência e a marcação
         * de cada envio. A tela pega esta trava para trocar o destino e zerar o
         * índice ([salvarPerfil]) ou apagar um perfil ([removerPerfil]); a
         * varredura, para reler o perfil e anotar uma linha no índice, e para
         * regravar o estado dos perfis no fim. Ninguém a segura durante um
         * envio, então a tela espera pouco.
         *
         * Ordem: [TRAVA] antes desta, nunca o contrário. Quem segura esta não
         * pede a [TRAVA] nem a trava da cópia da configuração.
         */
        private val TRAVA_PERFIS = Any()

        /**
         * O prazo do trabalho em segundo plano. GUARDA: abaixo dos 10 minutos
         * do `WorkManager`, com folga para o envio que estiver em curso quando
         * o prazo vencer — ele termina antes de a varredura parar.
         */
        internal const val LIMITE_PADRAO_MS = 8 * 60 * 1000L

        /**
         * De quanto em quanto tempo quem espera a vez confere se ainda deve
         * rodar. É também o maior atraso entre desligar a sincronização e uma
         * varredura em espera desistir.
         */
        internal const val FATIA_ESPERA_MS = 1_000L

        /**
         * O instante em que a chamada para de enviar: a entrada mais o limite,
         * sem estourar o `Long`. Limite negativo vale zero.
         */
        internal fun prazoFinal(inicio: Long, limiteMs: Long): Long {
            val l = limiteMs.coerceAtLeast(0L)
            return if (inicio > Long.MAX_VALUE - l) Long.MAX_VALUE else inicio + l
        }

        /** Quanto esperar pela trava nesta fatia: o que resta do prazo, até uma fatia. */
        internal fun esperaDaFatia(fim: Long, agora: Long): Long =
            if (agora >= fim) 0L else (fim - agora).coerceAtMost(FATIA_ESPERA_MS)

        /**
         * Espera a vez de varrer até [fim], uma fatia por vez.
         *
         * Entre uma fatia e outra consulta [seguir]: desligar a sincronização
         * ou parar o trabalho libera quem está na fila em até uma fatia, em vez
         * de deixá-lo esperando a varredura da frente só para então descobrir
         * que não devia rodar. Com o prazo vencido ainda tenta uma vez, sem
         * esperar: trava livre é pega mesmo sem tempo, e a varredura conta o
         * que falta sem enviar.
         *
         * Interrupção da thread é pedido de parada: a marca é restaurada para
         * quem estiver acima na pilha, e a chamada sai sem a trava.
         *
         * @return verdadeiro com a trava PEGA — quem chamou tem de soltá-la.
         */
        internal fun pegarTrava(trava: ReentrantLock, fim: Long, seguir: () -> Boolean): Boolean {
            try {
                var agora = System.currentTimeMillis()
                while (!trava.tryLock(esperaDaFatia(fim, agora), TimeUnit.MILLISECONDS)) {
                    if (agora >= fim || !seguir()) return false
                    agora = System.currentTimeMillis()
                }
                return true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        /**
         * Os dois perfis apontam para o mesmo lugar?
         *
         * Só os campos que dizem ONDE o arquivo cai. Nome, usuário, porta,
         * protocolo SMB e o próprio interruptor do perfil não mudam a pasta de
         * destino, e trocá-los não faz o acervo subir de novo.
         */
        internal fun mesmoDestino(a: PerfilSync, b: PerfilSync): Boolean =
            a.tipo == b.tipo && a.host == b.host && a.share == b.share &&
                a.urlBase == b.urlBase && a.safUri == b.safUri &&
                a.caminhoRemoto == b.caminhoRemoto

        /**
         * O perfil gravado ([atual]) ainda vale para a varredura que começou
         * com [inicial]?
         *
         * Destino trocado vence desativação: o índice já foi zerado pela tela,
         * e marcar nele seria anotar como enviado ao destino novo o que foi
         * para o antigo. Desativação só conta quando o perfil começou ativo: o
         * que já começou desativado só é varrido a pedido, pelo botão de um
         * perfil só ([sincronizarUm]), e segue até o fim.
         */
        internal fun vigencia(inicial: PerfilSync, atual: PerfilSync?): Vigencia = when {
            atual == null -> Vigencia.REMOVIDO
            !mesmoDestino(inicial, atual) -> Vigencia.DESTINO_TROCADO
            inicial.ativo && !atual.ativo -> Vigencia.DESATIVADO
            else -> Vigencia.VALE
        }

        /**
         * Marcar no índice só vale se o perfil gravado ainda aponta para onde o
         * arquivo foi. Perfil removido não marca: o índice dele foi podado, e
         * anotar nele recriaria um arquivo órfão.
         */
        internal fun podeMarcar(v: Vigencia): Boolean =
            v == Vigencia.VALE || v == Vigencia.DESATIVADO

        /**
         * Grava um perfil editado na tela; destino trocado zera o índice dele.
         *
         * Pasta remota nova é destino vazio: o índice do destino anterior diria
         * que já está tudo lá, e nada subiria. Zerar e gravar acontecem sob a
         * [TRAVA_PERFIS], a mesma que a varredura pega para conferir o perfil e
         * marcar cada envio: uma varredura em curso não anota nada entre o
         * índice zerado e o destino novo gravado, e na conferência seguinte
         * encontra o destino trocado e para.
         *
         * Lê e grava disco. A espera pela trava é curta: a varredura só a
         * segura para ler o perfil e anotar uma linha, nunca durante um envio.
         */
        fun salvarPerfil(context: Context, perfil: PerfilSync) {
            synchronized(TRAVA_PERFIS) {
                val store = PerfilStore(context)
                val anterior = store.obter(perfil.id)
                if (anterior != null && !mesmoDestino(anterior, perfil)) {
                    IndiceEnviados(context, perfil.id).limpar()
                }
                store.salvar(perfil)
            }
        }

        /**
         * Apaga um perfil sob a mesma trava de [salvarPerfil]: a varredura em
         * curso deixa de enviar para ele antes do próximo arquivo.
         */
        fun removerPerfil(context: Context, id: String) {
            synchronized(TRAVA_PERFIS) { PerfilStore(context).remover(id) }
        }

        /**
         * A janela de frescor: arquivo modificado há menos que isto fica para a
         * rodada seguinte.
         *
         * O gatilho de finalização depende deste número
         * ([SyncWorker.ATRASO_FINALIZAR_S] precisa ficar acima dele).
         */
        const val JANELA_FRESCOR_MS = 3_000L

        /**
         * A varredura entra nesta pasta?
         *
         * Fora ficam:
         * - `_tmp` e pastas com ponto na frente, que são trabalho em andamento;
         * - [BackupPreAtualizacao.NOME_PASTA]: a cópia antes de atualizar é
         *   recuperação DESTE aparelho, com cadastro, rubricas e auditoria, e
         *   os Termos e a Política dizem que ela não sai do aparelho. Como o
         *   motor nunca apaga, cada atualização deixaria no servidor mais uma
         *   cópia, para sempre;
         * - [QuarentenaArquivadas.NOME_PASTA]: fotos arquivadas que apareceram
         *   em mais de uma pasta de paciente e não podem ser atribuídas a
         *   ninguém. Enviá-las seria levar ao servidor da instituição foto
         *   possivelmente de outra pessoa dentro do prontuário errado;
         * - [FotosArquivadas.PASTA], enquanto [arquivadasLiberadas] for falso:
         *   a quarentena ainda não deu a base como examinada
         *   ([QuarentenaArquivadas.baseConferida]). Até lá a mesma foto
         *   arquivada pode estar na pasta de dois pacientes — base recém-
         *   atualizada, importação de fotos em andamento, movimento da
         *   quarentena que falhou —, e o que sobe nunca é apagado do destino.
         *   O resto da pasta do paciente sobe normalmente.
         *
         * Vale em qualquer profundidade. A pasta de paciente é o nome da pessoa
         * seguido do prontuário, e não coincide com nenhum destes nomes.
         */
        internal fun pastaEntraNaVarredura(nome: String, arquivadasLiberadas: Boolean): Boolean =
            nome != "_tmp" &&
                !nome.startsWith(".") &&
                nome != BackupPreAtualizacao.NOME_PASTA &&
                nome != QuarentenaArquivadas.NOME_PASTA &&
                (arquivadasLiberadas || nome != FotosArquivadas.PASTA)

        /**
         * O caminho relativo ([chaveRelativa]) passa por uma `ARQUIVADAS/`?
         * Mesmo critério de [pastaEntraNaVarredura], em qualquer profundidade;
         * o último trecho é o nome do arquivo e não conta.
         */
        internal fun dentroDeArquivadas(relativo: String): Boolean =
            relativo.split('/').dropLast(1).any { it == FotosArquivadas.PASTA }

        /**
         * O arquivo já listado continua na lista? Tudo, menos o que está em
         * `ARQUIVADAS/` quando a base não está examinada.
         */
        internal fun entraNaLista(relativo: String, arquivadasLiberadas: Boolean): Boolean =
            arquivadasLiberadas || !dentroDeArquivadas(relativo)

        /**
         * O arquivo pode ser enviado? Oculto (Time-Out e observações da
         * simulação, gravação em andamento), temporário e vazio, não.
         *
         * Não inclui a janela de frescor de propósito: a conferência da pasta
         * usa este mesmo filtro e precisa contar o arquivo recém-escrito.
         */
        internal fun arquivoElegivel(nome: String, tamanho: Long): Boolean =
            !nome.startsWith(".") &&
                !nome.endsWith(".tmp", ignoreCase = true) &&
                tamanho > 0L

        /**
         * Tira o `PHOTOS/` da frente do caminho relativo.
         *
         * A PASTA DO PACIENTE VAI NA PASTA QUE O SERVIÇO ESCOLHEU, e não numa
         * subpasta dentro dela. A raiz `PhotoID_RT/` tem `PHOTOS/` e
         * `DATABASE/` dentro; sem este corte, uma foto em
         * `PhotoID_RT/PHOTOS/MARIA - 123/rosto.jpg` chegaria ao destino como
         * `<destino>/PHOTOS/MARIA - 123/rosto.jpg`, com estrutura INTERNA do
         * tablet vazando para o servidor e os pacientes um nível abaixo da pasta
         * que o serviço escolheu.
         *
         * `DATABASE/` e o que mais houver continuam onde estão. Só o nível do
         * `PHOTOS` some, porque só ele é redundante com a escolha do destino.
         *
         * GUARDA: o índice de enviados é chaveado por este caminho. Mudar esta
         * regra faz o acervo inteiro subir de novo no layout novo, e, como a
         * sincronização é de uma via e nunca apaga, a árvore antiga fica no
         * destino até alguém removê-la à mão.
         */
        internal fun semPrefixoPhotos(relativo: String): String =
            if (relativo.startsWith("PHOTOS/")) relativo.removePrefix("PHOTOS/") else relativo

        /**
         * A chave de um arquivo no índice de enviados e o caminho dele no
         * destino: relativo à raiz, com `/` e sem o `PHOTOS/` da frente.
         *
         * É a ÚNICA conta dessa chave. A varredura e a conferência da pasta
         * passam por aqui; uma chave calculada de dois jeitos faria a
         * conferência procurar no índice um caminho que a varredura nunca
         * gravou, e todo prontuário pareceria pendente.
         *
         * @return nulo se [arquivo] não está sob [raiz].
         */
        internal fun chaveRelativa(raiz: String, arquivo: String): String? {
            val r = raiz.replace('\\', '/').trimEnd('/')
            val a = arquivo.replace('\\', '/')
            if (r.isEmpty() || !a.startsWith("$r/")) return null
            val rel = a.substring(r.length + 1)
            if (rel.isEmpty()) return null
            return semPrefixoPhotos(rel)
        }

        /** O caminho relativo é da cópia da configuração? */
        internal fun ehCopiaConfiguracao(relativo: String): Boolean =
            relativo.startsWith(CopiaConfiguracao.NOME_PASTA + "/")

        /** Ordem de envio: a cópia da configuração (0) antes do resto (1). */
        internal fun prioridade(relativo: String): Int =
            if (ehCopiaConfiguracao(relativo)) 0 else 1
    }
}
