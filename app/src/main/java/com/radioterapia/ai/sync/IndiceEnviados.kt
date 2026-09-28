package com.radioterapia.ai.sync

import android.content.Context
import java.io.File

/**
 * O que já subiu, por perfil.
 *
 * POR QUE PRECISA EXISTIR
 * Sem índice, cada varredura mandaria o acervo inteiro de novo. Um tablet com
 * seis meses de uso tem alguns milhares de fotos; reenviar tudo a cada hora
 * satura o Wi-Fi da clínica e não acrescenta um byte de informação. Perguntar
 * ao servidor "isto já existe aí?" antes de cada arquivo resolveria — e é o que
 * o motor faz quando o índice não sabe —, mas uma ida à rede por arquivo é
 * lenta, e em muitos servidores a conta do tablet nem tem leitura para perguntar.
 *
 * O QUE ENTRA NA IDENTIDADE: caminho relativo, tamanho e data de modificação.
 * Hash seria mais seguro e custaria ler o arquivo inteiro; para foto e PDF, que
 * são escritos uma vez e não mudam depois, tamanho e mtime bastam. Foto
 * reenquadrada muda os dois e sobe de novo, que é o comportamento desejado.
 *
 * POR PERFIL, e não global: dois destinos podem ter sido configurados em
 * momentos diferentes, e o que já foi para um não foi para o outro.
 *
 * ARQUIVO-TEXTO, uma linha por arquivo. Room daria consulta e índice, e aqui a
 * consulta é "está no conjunto?" — que é um `HashSet` em memória. A gravação é
 * incremental (`append`): a varredura que morre no meio não perde o que já
 * enviou, e não há transação para reabrir.
 */
class IndiceEnviados(private val context: Context, private val idPerfil: String) {

    private val arquivo: File by lazy {
        File(File(context.filesDir, "sync").apply { mkdirs() }, "enviados_$idPerfil.txt")
    }

    private val conjunto: MutableSet<String> by lazy {
        val s = HashSet<String>()
        if (arquivo.exists()) {
            try {
                arquivo.forEachLine { l -> if (l.isNotBlank()) s.add(l.trim()) }
            } catch (_: Exception) {
                // Índice ilegível é índice ausente: reenviar é caro, perder
                // arquivo não é opção. O conjunto vazio faz o motor subir tudo
                // de novo, e o servidor sobrescreve o que já estava lá.
            }
        }
        s
    }

    private fun chave(caminhoRelativo: String, local: File): String =
        "$caminhoRelativo|${local.length()}|${local.lastModified()}"

    fun jaEnviado(caminhoRelativo: String, local: File): Boolean =
        chave(caminhoRelativo, local) in conjunto

    fun marcar(caminhoRelativo: String, local: File) {
        val k = chave(caminhoRelativo, local)
        if (!conjunto.add(k)) return
        try {
            arquivo.appendText(k + "\n")
        } catch (_: Exception) {
            // Falhou gravar: o arquivo foi enviado do mesmo jeito, e na próxima
            // varredura ele sobe de novo. Desperdício de banda é melhor que a
            // varredura inteira abortar por causa do caderno de anotações.
        }
    }

    /** Quantos arquivos o índice conhece. Para a tela dizer o tamanho do acervo. */
    fun quantidade(): Int = conjunto.size

    /**
     * Esquece tudo. Usado quando o destino do perfil muda — pasta remota nova é
     * um destino vazio, e o índice do destino anterior diria que já está lá.
     */
    fun limpar() {
        conjunto.clear()
        try { arquivo.delete() } catch (_: Exception) { }
    }

    companion object {
        /** Apaga os índices de perfis que não existem mais. */
        fun podar(context: Context, idsVivos: Set<String>) {
            val pasta = File(context.filesDir, "sync")
            if (!pasta.isDirectory) return
            pasta.listFiles()?.forEach { f ->
                val id = f.name.removePrefix("enviados_").removeSuffix(".txt")
                if (f.name.startsWith("enviados_") && id !in idsVivos) {
                    try { f.delete() } catch (_: Exception) { }
                }
            }
        }
    }
}
