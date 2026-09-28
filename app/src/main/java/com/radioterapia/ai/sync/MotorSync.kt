package com.radioterapia.ai.sync

import android.content.Context
import com.radioterapia.ai.sync.destino.Destinos
import com.radioterapia.ai.util.StorageLocal
import java.io.File

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
 */
class MotorSync(private val context: Context) {

    /**
     * O resultado de uma varredura, por perfil.
     *
     * [pendentes] é o que ficou para a próxima rodada — por falha temporária ou
     * porque o tempo acabou. É o número que a tela mostra, e é ele que responde
     * "já subiu tudo?" sem obrigar ninguém a contar arquivo.
     */
    data class Resumo(
        val perfil: String,
        val enviados: Int = 0,
        val jaEstavam: Int = 0,
        val pendentes: Int = 0,
        val erro: String = "",
    ) {
        val houveFalha: Boolean get() = erro.isNotEmpty() || pendentes > 0
    }

    /**
     * Roda todos os perfis ativos.
     *
     * @param limiteMs quanto tempo a varredura pode durar. O `WorkManager` mata
     *   o trabalho aos 10 minutos, e trabalho morto não grava o índice do que
     *   acabou de enviar — a rodada seguinte reenviaria tudo. Parar sozinho
     *   antes disso, com o índice em dia, é o que torna a fila incremental de
     *   verdade.
     */
    fun sincronizarTudo(limiteMs: Long = 8 * 60 * 1000L): List<Resumo> {
        val cfg = SyncConfig(context)
        if (!cfg.ativo) return emptyList()

        val store = PerfilStore(context)
        val perfis = store.listar().filter { it.ativo && it.utilizavel() }
        if (perfis.isEmpty()) return emptyList()

        val arquivos = listarLocais()
        val fim = System.currentTimeMillis() + limiteMs
        val resumos = perfis.map { p -> sincronizar(p, store.senha(p.id), arquivos, fim) }

        cfg.ultimaVarredura = System.currentTimeMillis()
        // O estado de cada perfil volta para o disco numa gravação só: o
        // PerfilStore poda senhas e índices a cada `salvarTodos`, e chamá-lo por
        // perfil faria a poda rodar sobre um estado intermediário.
        val atualizados = store.listar().map { p ->
            val r = resumos.firstOrNull { it.perfil == p.id } ?: return@map p
            p.copy(
                ultimaSincronizacao = if (r.erro.isEmpty()) System.currentTimeMillis()
                                      else p.ultimaSincronizacao,
                ultimoErro = r.erro
            )
        }
        store.salvarTodos(atualizados)
        return resumos
    }

    /** Um perfil só, para o botão "sincronizar agora" da tela. */
    fun sincronizarUm(perfil: PerfilSync, limiteMs: Long = 8 * 60 * 1000L): Resumo {
        val store = PerfilStore(context)
        return sincronizar(perfil, store.senha(perfil.id), listarLocais(),
            System.currentTimeMillis() + limiteMs)
    }

    private fun sincronizar(perfil: PerfilSync, senha: String,
                            arquivos: List<Local>, fim: Long): Resumo {
        val indice = IndiceEnviados(context, perfil.id)
        val destino = Destinos.criar(context, perfil, senha)
        var enviados = 0
        var jaEstavam = 0
        var pendentes = 0
        var erro = ""

        try {
            for (a in arquivos) {
                if (System.currentTimeMillis() >= fim) {
                    // Não é erro: é a rodada acabando. O que sobrou continua
                    // pendente e sobe na próxima, sem reenviar o que já subiu.
                    pendentes += 1
                    continue
                }
                if (indice.jaEnviado(a.relativo, a.arquivo)) { jaEstavam++; continue }

                val r = destino.enviar(a.arquivo, a.pasta, a.nome)
                when {
                    r.sucesso -> { indice.marcar(a.relativo, a.arquivo); enviados++ }
                    r.permanente -> {
                        // Recusa que não melhora com repetição para a varredura
                        // inteira deste perfil: insistir arquivo por arquivo
                        // contra uma credencial recusada só enche o log do
                        // servidor e gasta a bateria do tablet.
                        erro = r.erro
                        pendentes += 1
                        break
                    }
                    else -> { erro = r.erro; pendentes++ }
                }
            }
        } catch (e: Exception) {
            erro = "${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}"
        } finally {
            destino.fechar()
        }

        return Resumo(perfil.id, enviados, jaEstavam, pendentes, erro)
    }

    /**
     * Tira o `PHOTOS/` da frente do caminho relativo.
     *
     * A PASTA DO PACIENTE VAI NA PASTA QUE O SERVIÇO ESCOLHEU, e não numa
     * subpasta dentro dela. Antes, o caminho era calculado da raiz
     * `PhotoID_RT/` — que tem `PHOTOS/` e `DATABASE/` dentro —, então uma foto
     * em `PhotoID_RT/PHOTOS/MARIA - 123/rosto.jpg` chegava ao destino como
     * `<destino>/PHOTOS/MARIA - 123/rosto.jpg`. O `PHOTOS` era estrutura
     * INTERNA do tablet vazando para o servidor: quem escolheu a pasta de
     * destino ja disse onde quer as coisas, e encontrava os pacientes um nivel
     * abaixo do que pediu.
     *
     * `DATABASE/` e o que mais houver continuam onde estao. So o nivel do
     * `PHOTOS` some, porque so ele e redundante com a escolha do destino.
     *
     * CONSEQUENCIA QUE VALE SABER: o indice de enviados e chaveado por este
     * caminho, entao o que ja subiu sob `PHOTOS/` sera enviado UMA VEZ no
     * layout novo. E, como a sincronizacao e de uma via e nunca apaga, a pasta
     * `PHOTOS/` antiga permanece no destino ate alguem remove-la a mao — o
     * motor nao apaga nada, nem o que ele mesmo criou.
     */
    private fun semPrefixoPhotos(relativo: String): String =
        if (relativo.startsWith("PHOTOS/")) relativo.removePrefix("PHOTOS/") else relativo

    /** Um arquivo local e onde ele fica em relação à raiz. */
    private data class Local(val arquivo: File, val relativo: String) {
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
     */
    private fun listarLocais(): List<Local> {
        val raiz = StorageLocal.base(context)
        if (!raiz.isDirectory) return emptyList()
        val agora = System.currentTimeMillis()
        val achados = mutableListOf<Local>()
        val prefixo = raiz.absolutePath.length + 1

        raiz.walkTopDown()
            .onEnter { d -> d.name != "_tmp" && !d.name.startsWith(".") }
            .filter { it.isFile }
            .forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                if (f.name.endsWith(".tmp", true)) return@forEach
                if (f.length() <= 0L) return@forEach
                // ARQUIVO RECÉM-ESCRITO FICA PARA A PRÓXIMA. Uma foto salva há
                // meio segundo pode ainda estar sendo gravada; enviá-la truncada
                // marcaria no índice um arquivo incompleto, e ele nunca mais
                // subiria inteiro — o tamanho no índice é o tamanho truncado.
                if (agora - f.lastModified() < 3_000L) return@forEach
                val rel = f.absolutePath.substring(prefixo).replace('\\', '/')
                achados.add(Local(f, semPrefixoPhotos(rel)))
            }

        return achados.sortedBy { it.arquivo.lastModified() }
    }
}
