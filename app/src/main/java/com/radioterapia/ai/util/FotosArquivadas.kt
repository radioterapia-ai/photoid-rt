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
 * O sincronizador leva a subpasta junto, que é o ponto: o arquivo continua existindo
 * onde a clínica guarda o atendimento.
 */
object FotosArquivadas {

    const val PASTA = "ARQUIVADAS"

    fun pasta(base: File): File = File(base, PASTA)

    /**
     * Move a foto para `ARQUIVADAS/`, junto com o `_ORIGINAL` correspondente.
     *
     * O par anda junto de propósito: o original sozinho na pasta do paciente
     * seria um quadro cheio sem a foto que ele originou, e o sincronizador o levaria
     * ao servidor sem par — que é exatamente o que a exclusão de foto já evita.
     *
     * O nome ganha um carimbo de tempo porque categoria única grava sempre com o
     * MESMO nome na sessão (`_rosto.jpg`): sem o carimbo, arquivar a segunda
     * substituição sobrescreveria a primeira, e o arquivamento perderia
     * justamente o que existe para preservar.
     *
     * O par é localizado por [NomeArquivo.originalDe] ANTES de mover a foto: a
     * busca parte do nome da foto, e depois do movimento ela já não está ali.
     *
     * @return o arquivo já arquivado, ou `null` se nada foi movido.
     */
    fun arquivar(foto: File): File? {
        // Corpo em BLOCO, e nao expressao: o `?: return null` do parentFile nao
        // compila em corpo-expressao (o Kotlin nao aceita return ali).
        val pai = foto.parentFile
        if (!foto.exists() || foto.isDirectory || pai == null) return null
        return try {
            val orig = NomeArquivo.originalDe(foto)
            val destinoDir = pasta(pai).apply { mkdirs() }
            val base = foto.nameWithoutExtension
            val ext = foto.extension.ifBlank { "jpg" }
            val carimbo = System.currentTimeMillis()
            val destino = File(destinoDir, "${base}_ARQ$carimbo.$ext")
            val moveu = foto.renameTo(destino) || copiarEApagar(foto, destino)
            if (moveu) {
                // O par acompanha, com o MESMO carimbo e o PROPRIO sufixo, para
                // continuar reconhecivel dentro da pasta de arquivadas: o leitor
                // de originais aceita as grafias que existem em campo, e trocar
                // a grafia aqui criaria uma que nenhum leitor conhece.
                if (orig != null && orig.exists()) {
                    val sufixo = sufixoDoPar(orig.nameWithoutExtension, base)
                    val extOrig = orig.extension.ifBlank { "jpg" }
                    val destOrig = File(destinoDir, "${base}_ARQ$carimbo$sufixo.$extOrig")
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
     * escolha viraria adivinhação. A decisão de quem é par fica com
     * [NomeArquivo.ehOriginal], que ignora caixa no sufixo e na extensão:
     * converter só um dos lados para maiúsculas faz a comparação nunca casar, e
     * o original entra na grade como foto a escolher.
     */
    fun listar(base: File): List<File> = try {
        pasta(base).listFiles { _, nome ->
            (nome.endsWith(".jpg", true) || nome.endsWith(".jpeg", true)) &&
                !NomeArquivo.ehOriginal(nome)
        }?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
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
     * Leva para a pasta do paciente as arquivadas da sessão que quem chama
     * escolheu (a finalização passa só as do rascunho atual).
     *
     * Chamada na finalização: durante a captura as fotos vivem na pasta de
     * trabalho da sessão, que é temporária. Sem este passo, o que foi arquivado
     * enquanto o paciente estava na sala seria apagado junto com o rascunho — e
     * o arquivamento não teria servido para nada.
     *
     * GUARDA: os arquivos chegam com o nome interno da sessão
     * (`_rosto_ARQ<ms>.jpg`, `_rosto_ARQ<ms>_ORIGINAL.jpg`), e esse nome fica
     * para sempre na pasta do paciente e no servidor. É uma terceira família de
     * nomes, sem paciente e sem marca de simulação, que todo leitor de
     * ARQUIVADAS precisa continuar classificando (ver [tipoConfiavel]).
     *
     * @return quantos arquivos foram levados.
     */
    fun transferir(arquivos: List<File>, paraBase: File): Int = try {
        if (arquivos.isEmpty()) 0
        else {
            val destinoDir = pasta(paraBase).apply { mkdirs() }
            arquivos.count { arq ->
                try { arq.copyTo(File(destinoDir, arq.name), overwrite = true); true }
                catch (_: Exception) { false }
            }
        }
    } catch (_: Exception) { 0 }

    /**
     * Tipo da foto pelo nome do arquivo, SEM deixar o nome do paciente decidir.
     *
     * GUARDA: serve a quem MOVE arquivo por tipo (refazer rosto ou etiqueta
     * arquiva a anterior). O nome do esquema novo traz só as iniciais, e o tipo sai dele
     * sem ambiguidade. O nome legado começa pelo nome COMPLETO do paciente, e a
     * classificação legada procura palavras ("rosto", "face", "label") no nome
     * inteiro: para uma paciente chamada ANA FACELI, toda foto legada pareceria
     * rosto, e refazer o rosto arquivaria todos os posicionamentos da ficha.
     * Por isso o prefixo do paciente sai ANTES da classificação, nas duas
     * normalizações com que os nomes legados foram gravados, e o tipo é a
     * palavra que os gravadores legados punham logo depois do nome
     * (`_ROSTO_`, `_POSICIONAMENTO_TRATAMENTO_`, `_FOLHA_SIMULACAO_`...), e não
     * uma palavra encontrada em qualquer ponto.
     *
     * O nome interno da sessão (`_rosto_ARQ<ms>.jpg`) começa por `_` e não
     * carrega paciente nenhum: é classificado direto.
     *
     * @return o tipo, ou `null` quando não há certeza — nome legado cujo prefixo
     *   não confere com o paciente informado, ou cuja palavra depois do nome não
     *   é um tipo conhecido. Quem move arquivo não move na dúvida.
     */
    fun tipoConfiavel(nomeArquivo: String, nomePaciente: String?): NomeArquivo.Tipo? {
        val analise = NomeArquivo.analisar(nomeArquivo)
        if (analise.esquemaNovo || nomeArquivo.startsWith("_")) return analise.tipo
        val resto = restoAposPaciente(nomeArquivo, nomePaciente) ?: return null
        return tipoDaPalavraLegada(resto)
    }

    /**
     * Tipo pela palavra que abre o resto de um nome legado (`_ROSTO_...`).
     * Só as palavras que os gravadores legados escreveram; qualquer outra
     * devolve `null`.
     */
    private fun tipoDaPalavraLegada(resto: String): NomeArquivo.Tipo? {
        if (!resto.startsWith("_")) return null
        val r = resto.substring(1).uppercase(java.util.Locale.ROOT)
        if (r.startsWith("FOLHA_SIMULACAO") || r.startsWith("FOLHASIMULACAO")) {
            return NomeArquivo.Tipo.FICHA
        }
        return when (r.substringBefore('_').substringBefore('.')) {
            "ROSTO" -> NomeArquivo.Tipo.ROSTO
            "ETIQUETA" -> NomeArquivo.Tipo.ETIQUETA
            "POSICIONAMENTO" -> NomeArquivo.Tipo.POSICIONAMENTO
            "ACESSORIOS" -> NomeArquivo.Tipo.ACESSORIOS
            "DOC" -> NomeArquivo.Tipo.IMPRESSO
            else -> null
        }
    }

    /**
     * Como [tipoConfiavel], mas para quem só MOSTRA ou rotula: na dúvida cai na
     * classificação geral de [NomeArquivo.tipo] em vez de devolver `null`. Um
     * rótulo errado na tela se corrige à vista; um arquivo movido por engano,
     * não.
     */
    fun tipoProvavel(nomeArquivo: String, nomePaciente: String?): NomeArquivo.Tipo? =
        tipoConfiavel(nomeArquivo, nomePaciente) ?: NomeArquivo.tipo(nomeArquivo)

    /**
     * O que vem depois do nome do paciente num nome legado, a partir do `_`
     * separador (`MARIA_SILVA_ROSTO_...` -> `_ROSTO_...`), ou `null` se o
     * arquivo não começa pelo nome desse paciente.
     *
     * Duas grafias de prefixo existem em campo: a que preserva apóstrofo, hífen
     * e ponto (`MARIA_D'ARC`) e a que guarda só letras e dígitos
     * (`MARIA_DARC`). A mais longa é tentada primeiro, e o prefixo só vale se
     * for seguido de `_` — sem isso a paciente ANA reconheceria os arquivos de
     * ANABELA.
     */
    internal fun restoAposPaciente(nomeArquivo: String, nomePaciente: String?): String? {
        if (nomePaciente.isNullOrBlank()) return null
        val prefixos = listOf(prefixoComPontuacao(nomePaciente), prefixoSoAlfanumerico(nomePaciente))
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
        for (p in prefixos) {
            val comSeparador = p + "_"
            if (nomeArquivo.length > comSeparador.length &&
                nomeArquivo.regionMatches(0, comSeparador, 0, comSeparador.length, ignoreCase = true)) {
                return nomeArquivo.substring(p.length)
            }
        }
        return null
    }

    /** Prefixo como a finalização e a edição de simulação o gravavam. */
    private fun prefixoComPontuacao(nome: String): String =
        StorageLocal.removerAcentosMaiusculas(nome).trim().replace(Regex("\\s+"), "_")

    /** Prefixo como o Tratamento e a edição de cadastro o gravavam. */
    private fun prefixoSoAlfanumerico(nome: String): String =
        java.text.Normalizer.normalize(nome, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ").trim()
            .uppercase(java.util.Locale.ROOT)
            .replace(" ", "_")

    /**
     * Sufixo do par em relação à foto (`_ORIGINAL`, ou a grafia que estiver no
     * disco). Se o nome do par não começar pelo nome da foto, o que não deveria
     * acontecer, usa a grafia atual.
     */
    private fun sufixoDoPar(nomeParSemExtensao: String, nomeFotoSemExtensao: String): String {
        if (nomeParSemExtensao.length > nomeFotoSemExtensao.length &&
            nomeParSemExtensao.startsWith(nomeFotoSemExtensao)) {
            return nomeParSemExtensao.substring(nomeFotoSemExtensao.length)
        }
        return "_ORIGINAL"
    }

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
