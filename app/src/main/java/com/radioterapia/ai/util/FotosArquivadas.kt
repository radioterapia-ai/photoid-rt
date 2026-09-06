package com.radioterapia.ai.util

import java.io.File

/**
 * Fotos que saíram da ficha mas **não** foram jogadas fora.
 *
 * POR QUE EXISTE: as categorias únicas — rosto, etiqueta — guardam uma foto só.
 * Ao mandar outra para a mesma categoria, a anterior era APAGADA, em silêncio e
 * sem volta. O técnico que trocava a foto do rosto por uma da galeria via a
 * anterior simplesmente sumir, e se a nova estivesse pior não havia como
 * desfazer: o paciente já tinha ido embora.
 *
 * Arquivar não é o mesmo que descartar. A foto que o técnico DESCARTA na tela de
 * captura — borrada, de teste, do enquadramento errado — continua sendo apagada,
 * porque guardá-la só encheria o disco. O que passa por aqui é a foto que foi
 * considerada boa o bastante para ser gravada e depois foi substituída: essa é
 * registro do atendimento, e some da ficha sem sumir do disco.
 *
 * ONDE FICA: subpasta `ARQUIVADAS/` dentro da pasta que a continha. Subpasta, e
 * não sufixo no nome, porque tudo que lista fotos no app usa `listFiles` com
 * filtro de extensão e **não desce um nível** — então a foto arquivada sai do
 * carrossel e do PDF sem que uma única listagem precise ser alterada para
 * excluí-la. Uma regra por nome exigiria acertar todas elas, e a que ficasse
 * para trás traria a foto de volta à ficha impressa.
 *
 * O FileSync leva a subpasta junto, que é o ponto: o arquivo continua existindo
 * onde a clínica guarda o atendimento.
 */
object FotosArquivadas {

    const val PASTA = "ARQUIVADAS"

    fun pasta(base: File): File = File(base, PASTA)

    /**
     * Move a foto para `ARQUIVADAS/`, junto com o `_ORIGINAL` correspondente.
     *
     * O par anda junto de propósito: o original sozinho na pasta do paciente
     * seria um quadro cheio sem a foto que ele originou, e o FileSync o levaria
     * ao servidor sem par — que é exatamente o que a exclusão de foto já evita.
     *
     * O nome ganha um carimbo de tempo porque categoria única grava sempre com o
     * MESMO nome (`ROSTO.jpg`): sem o carimbo, arquivar a segunda substituição
     * sobrescreveria a primeira, e o arquivamento perderia justamente o que
     * existe para preservar.
     *
     * @return o arquivo já arquivado, ou `null` se nada foi movido.
     */
    fun arquivar(foto: File): File? {
        // Corpo em BLOCO, e nao expressao: o `?: return null` do parentFile nao
        // compila em corpo-expressao (o Kotlin nao aceita return ali).
        val pai = foto.parentFile
        if (!foto.exists() || foto.isDirectory || pai == null) return null
        return try {
            val destinoDir = pasta(pai).apply { mkdirs() }
            val base = foto.nameWithoutExtension
            val ext = foto.extension.ifBlank { "jpg" }
            val carimbo = System.currentTimeMillis()
            val destino = File(destinoDir, "${base}_ARQ$carimbo.$ext")
            val moveu = foto.renameTo(destino) || copiarEApagar(foto, destino)
            if (moveu) {
                // O "_ORIGINAL" acompanha, com o MESMO carimbo, para o par
                // continuar reconhecível dentro da pasta de arquivadas.
                val orig = File(foto.parentFile, "$base${SUFIXO_ORIGINAL}")
                if (orig.exists()) {
                    val destOrig = File(destinoDir, "${base}_ARQ$carimbo$SUFIXO_ORIGINAL")
                    if (!orig.renameTo(destOrig)) copiarEApagar(orig, destOrig)
                }
                destino
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * Fotos arquivadas de uma pasta, mais recentes primeiro.
     *
     * O `_ORIGINAL` fica de fora da listagem: ele é o par da foto ao lado, não
     * uma foto a mais para escolher — apareceriam duas entradas quase iguais e a
     * escolha viraria adivinhação.
     */
    fun listar(base: File): List<File> = try {
        pasta(base).listFiles { _, nome ->
            nome.endsWith(".jpg", true) && !nome.uppercase().endsWith(SUFIXO_ORIGINAL)
        }?.sortedByDescending { it.lastModified() }.orEmpty()
    } catch (_: Exception) { emptyList() }

    /** Há alguma foto arquivada nesta pasta? */
    fun temAlguma(base: File): Boolean = listar(base).isNotEmpty()

    /**
     * Copia a arquivada para um destino temporário, para voltar ao carrossel.
     *
     * COPIA, não move: a foto continua arquivada até que a nova sessão seja de
     * fato gravada. Se o técnico desistir no meio, nada foi perdido — mover
     * primeiro deixaria a foto num temporário que a saída da tela apaga.
     */
    fun copiarParaTemp(arquivada: File, destino: File): Boolean = try {
        arquivada.copyTo(destino, overwrite = true)
        true
    } catch (_: Exception) { false }

    /**
     * Leva a subpasta de arquivadas da sessão para a pasta do paciente.
     *
     * Chamada na finalização: durante a captura as fotos vivem na pasta de
     * trabalho da sessão, que é temporária. Sem este passo, o que foi arquivado
     * enquanto o paciente estava na sala seria apagado junto com o rascunho — e
     * o arquivamento não teria servido para nada.
     *
     * @return quantos arquivos foram levados.
     */
    fun transferir(deBase: File, paraBase: File): Int = try {
        val origem = pasta(deBase)
        val arquivos = origem.listFiles()?.filter { it.isFile }.orEmpty()
        if (arquivos.isEmpty()) 0
        else {
            val destinoDir = pasta(paraBase).apply { mkdirs() }
            arquivos.count { arq ->
                try { arq.copyTo(File(destinoDir, arq.name), overwrite = true); true }
                catch (_: Exception) { false }
            }
        }
    } catch (_: Exception) { 0 }

    private const val SUFIXO_ORIGINAL = "_ORIGINAL.jpg"

    /**
     * `renameTo` falha entre volumes diferentes (memória interna e cartão) e
     * devolve `false` sem lançar nada — a foto ficaria onde estava, e quem
     * chamou acharia que arquivou.
     */
    private fun copiarEApagar(origem: File, destino: File): Boolean = try {
        origem.copyTo(destino, overwrite = true)
        origem.delete()
        true
    } catch (_: Exception) { false }
}
