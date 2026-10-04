package com.radioterapia.ai.protocolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Protocolos: as páginas finais que cada serviço acrescenta à ficha.
 *
 * O QUE É UM PROTOCOLO. Um conjunto de PDFs que o próprio serviço já usa —
 * termo de consentimento, orientações ao paciente, checklist interno — e que
 * passa a sair impresso junto da ficha de posicionamento. O app **não edita**
 * esses PDFs: ele os reproduz página a página e sobrepõe, no máximo, a etiqueta
 * de identificação e o logotipo, nas posições que o usuário calibrar.
 *
 * O PROTOCOLO PADRÃO é a ficha como ela sempre foi: Time-Out, fotos e
 * rubricário, sem nenhuma página acrescentada. Ele existe como item da lista
 * para que a tela de seleção tenha sentido quando há mais de um — e não pode
 * ser excluído, porque é o estado ao qual sempre se pode voltar.
 *
 * ONDE MORA: `filesDir/protocolos/`, com um `lista.json` e uma subpasta por
 * protocolo guardando os PDFs e a miniatura. Subpasta por protocolo, e não
 * todos os arquivos juntos com prefixo: excluir um protocolo passa a ser apagar
 * uma pasta, e não varrer nomes — que é onde se esquece um arquivo e ele fica
 * ocupando espaço para sempre.
 *
 * ID E NOMES DE ARQUIVO SÃO DADO NÃO CONFIÁVEL. Eles vêm do `lista.json`, que
 * pode ter chegado num pacote de outro tablet, e viram caminho em disco. Por
 * isso a leitura descarta id fora de [idValido] e nome fora de [nomeSimples], e
 * toda exclusão passa por [resolverDentro], que confere o caminho canônico
 * contra a pasta `protocolos/`. Sem isso, um protocolo de id `..` levaria o
 * `filesDir` inteiro ao ser excluído.
 *
 * As posições da etiqueta e do logotipo são guardadas em MILÍMETROS, não em
 * pixels nem em pontos. O PDF do usuário pode ter qualquer tamanho de página, e
 * milímetro é a única unidade que significa a mesma coisa em todos eles — foi
 * assim que a etiqueta física de 100x50 mm entrou na configuração.
 *
 * UM SÓ SERIALIZADOR. O mesmo objeto JSON de página e de protocolo serve ao
 * `lista.json` e ao pacote de exportação ([protocoloParaJson]). Com duas cópias
 * da rotina, um campo novo entra numa e é esquecido na outra: fica salvo no
 * tablet e some, em silêncio, de toda configuração exportada.
 */
class ProtocoloStore(private val context: Context) {

    private val dir = File(context.filesDir, "protocolos").apply { mkdirs() }
    private val arquivo = File(dir, "lista.json")

    /**
     * Pasta de um protocolo, criada se faltar. Os PDFs e a miniatura moram aqui.
     *
     * Só aceita id que passe em [idValido]; com outro, LANÇA em vez de devolver
     * um caminho que poderia sair de `protocolos/`. Todo id legítimo vem de
     * [listar], que já descarta os inválidos, ou de [novoId].
     */
    fun pastaDe(id: String): File {
        require(idValido(id)) { "id de protocolo inválido" }
        return File(dir, id).apply { mkdirs() }
    }

    /**
     * Onde a etiqueta e o logotipo caem numa página do PDF.
     *
     * Mesmas unidades e mesmas âncoras dos campos planos de [Pagina]: etiqueta
     * medida da borda esquerda e do topo, logotipo da borda DIREITA e do topo,
     * tudo em mm. Os valores padrão são os mesmos de [Pagina], e é isso que faz
     * uma chave ausente no JSON cair no mesmo número de antes.
     *
     * @param rodapeAtivo se o rodapé do app (nome da clínica e número da
     *   página) sai nesta página. Ligado por padrão: desligar serve à página
     *   cujo impresso já tem texto centrado na margem de baixo, que o rodapé
     *   cobriria. Fica no FIM para que quem constrói por posição continue
     *   preenchendo os mesmos campos.
     */
    data class Posicao(
        val etqAtiva: Boolean = true,
        val etqXmm: Float = 10f,
        val etqYmm: Float = 10f,
        val etqWmm: Float = 60f,
        val etqHmm: Float = 30f,
        val logoAtivo: Boolean = false,
        val logoXmm: Float = MARGEM_PADRAO_MM,
        val logoYmm: Float = MARGEM_PADRAO_MM,
        val rodapeAtivo: Boolean = true
    )

    /**
     * Posição própria de UMA página de um PDF de várias páginas.
     *
     * @param indice base 0 dentro do PDF da frente. Sempre maior que 0: a
     *   página 0 é a base, guardada nos campos planos de [Pagina].
     */
    data class AjustePagina(val indice: Int, val posicao: Posicao)

    /**
     * Um PDF do protocolo, que pode ter uma ou várias páginas.
     *
     * POSIÇÃO POR PÁGINA, COM HERANÇA. Os campos planos (`etq*`, `logo*`,
     * `rodapeAtivo`) são a posição da PRIMEIRA página do PDF e, ao mesmo tempo,
     * a de toda página que não tem ajuste próprio em [ajustes]. Um formulário de
     * duas páginas com o mesmo cabeçalho não pede nada além do que sempre
     * pediu; só a página que tem o espaço em outro lugar ganha um [AjustePagina].
     *
     * Os campos planos continuam sendo a página 0, e não uma lista com todas as
     * páginas, por compatibilidade: protocolo salvo e pacote exportado antes
     * da posição por página não têm `pp`, então [ajustes] vem vazio e toda
     * página usa a base — exatamente a ficha que já saía. E uma versão antiga
     * do app que receba um pacote novo ignora `pp` e imprime todas as páginas
     * na posição da primeira: pior, mas não quebrado.
     *
     * Ajuste com índice além do número de páginas do PDF é inerte: quem
     * desenha só pergunta pelas páginas que o arquivo tem.
     *
     * @param arquivo nome do PDF dentro da pasta do protocolo.
     * @param etqAtiva se a etiqueta de identificação é sobreposta nesta página.
     * @param etqXmm distância da BORDA ESQUERDA da página, em mm.
     * @param etqYmm distância do TOPO da página, em mm.
     * @param etqWmm largura da etiqueta, em mm. Limitada a 100 (ver [ETQ_MAX_W]).
     * @param etqHmm altura da etiqueta, em mm. Limitada a 50 (ver [ETQ_MAX_H]).
     * @param logoAtivo se o logotipo do serviço é sobreposto nesta página.
     * @param logoXmm distância da BORDA DIREITA, em mm — igual às demais
     *   páginas, o logo é ancorado à direita.
     * @param logoYmm distância do TOPO, em mm.
     * @param nome rótulo do PDF na lista do editor. Vazio = sem rótulo, e a
     *   linha mostra só a contagem de páginas.
     * @param ajustes posições próprias das páginas 2 em diante; ver acima.
     * @param rodapeAtivo se o rodapé do app sai na página 0 — e, como os demais
     *   campos planos, nas páginas sem ajuste próprio. Ver [Posicao.rodapeAtivo].
     */
    data class Pagina(
        val arquivo: String,
        /**
         * PDF do VERSO desta folha, dentro da mesma pasta. Vazio = só frente.
         *
         * Sem este campo, um impresso frente-e-verso teria de ser carregado
         * como duas páginas separadas — o que sai em duas folhas, e faz a
         * etiqueta de identificação aparecer duas vezes.
         *
         * O VERSO NÃO RECEBE ETIQUETA NEM LOGOTIPO. Eles já estão na frente da
         * mesma folha: repetir seria gastar duas etiquetas por paciente e sujar
         * um impresso que a clínica desenhou com o espaço contado.
         */
        val verso: String = "",
        val etqAtiva: Boolean = true,
        val etqXmm: Float = 10f,
        val etqYmm: Float = 10f,
        val etqWmm: Float = 60f,
        val etqHmm: Float = 30f,
        val logoAtivo: Boolean = false,
        val logoXmm: Float = MARGEM_PADRAO_MM,
        val logoYmm: Float = MARGEM_PADRAO_MM,
        // nome, ajustes e rodapeAtivo ficam no FIM: quem constrói Pagina por
        // posição continua preenchendo exatamente os mesmos campos.
        val nome: String = "",
        val ajustes: List<AjustePagina> = emptyList(),
        val rodapeAtivo: Boolean = true
    ) {
        /** A posição da página 0, que é também a herdada pelas demais. */
        val posicaoBase: Posicao
            get() = Posicao(etqAtiva, etqXmm, etqYmm, etqWmm, etqHmm,
                logoAtivo, logoXmm, logoYmm, rodapeAtivo)

        /** Posição da página [indice] (base 0): o ajuste dela, ou a base. */
        fun posicaoDa(indice: Int): Posicao =
            if (indice <= 0) posicaoBase
            else ajustes.firstOrNull { it.indice == indice }?.posicao ?: posicaoBase

        /** A página [indice] tem posição própria? A página 0 nunca tem: ela É a base. */
        fun temAjuste(indice: Int): Boolean =
            indice > 0 && ajustes.any { it.indice == indice }

        /**
         * Cópia com a página [indice] em [pos].
         *
         * Página 0 grava nos campos planos — e as páginas sem ajuste próprio
         * passam a herdar a nova base, que é o que se espera de "seguir a
         * página 1". Demais páginas trocam ou acrescentam o ajuste. Arquivo,
         * verso e nome passam intactos: é cópia, não reconstrução.
         */
        fun comPosicao(indice: Int, pos: Posicao): Pagina =
            if (indice <= 0) copy(
                etqAtiva = pos.etqAtiva,
                etqXmm = pos.etqXmm, etqYmm = pos.etqYmm,
                etqWmm = pos.etqWmm, etqHmm = pos.etqHmm,
                logoAtivo = pos.logoAtivo,
                logoXmm = pos.logoXmm, logoYmm = pos.logoYmm,
                rodapeAtivo = pos.rodapeAtivo)
            else copy(ajustes = (ajustes.filter { it.indice != indice } +
                AjustePagina(indice, pos)).sortedBy { it.indice })

        /** Cópia em que a página [indice] volta a seguir a página 0. */
        fun semAjuste(indice: Int): Pagina =
            if (!temAjuste(indice)) this
            else copy(ajustes = ajustes.filter { it.indice != indice })
    }

    data class Protocolo(
        val id: String,
        val nome: String,
        val padrao: Boolean,
        /** Nome do PNG da miniatura dentro da pasta. Vazio = sem miniatura. */
        val miniatura: String = "",
        /**
         * Qual equipe entra na página de rubricário desta ficha.
         *
         * É a PRIMEIRA página do protocolo, e a primeira coisa que se escolhe
         * ao editá-lo. Um tablet que atende duas clínicas tem um protocolo por
         * clínica, e é este campo que impede a folha de sair com a equipe da
         * outra — que é o defeito que os blocos vieram corrigir.
         */
        val rubricarioId: String =
            com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO,
        val paginas: List<Pagina> = emptyList()
    )

    companion object {
        const val ID_PADRAO = "padrao"

        /**
         * Distância da margem que o logotipo já usa nas demais páginas.
         *
         * MARGIN do PdfBuilder são 28 pt (~9,9 mm), e o logo é desenhado a
         * partir dela. Este é o valor com que o editor abre — a calibração fina
         * fica com o usuário, porque o PDF dele pode ter o próprio cabeçalho
         * ocupando exatamente esse canto.
         */
        const val MARGEM_PADRAO_MM = 10f

        /** Teto da etiqueta, em mm. É o maior formato que a configuração aceita. */
        const val ETQ_MAX_W = 100f
        const val ETQ_MAX_H = 50f

        /** Teto do nome de um PDF, em caracteres. O campo de edição usa o mesmo. */
        const val NOME_PAGINA_MAX = 80

        /**
         * Trava de toda leitura-alteração-gravação do `lista.json`.
         *
         * Cada tela cria o próprio [ProtocoloStore], então a trava é do
         * processo, não da instância. Ela existe porque [duplicar] roda fora da
         * thread principal: sem ela, uma seta tocada durante a cópia gravaria a
         * nova ordem e a duplicação, ao terminar, regravaria a lista que leu
         * antes — e a ordem escolhida sumiria sem aviso.
         */
        private val TRAVA = Any()

        private val SUFIXO_NUMERO = Regex("""\s*\(\d+\)$""")
        private val EXTENSAO_PDF = Regex("""\.pdf$""", RegexOption.IGNORE_CASE)
        private val CONTROLE = Regex("""[\x00-\x1F\x7F]""")

        /**
         * Formato aceito de id: letras e dígitos ASCII, `_` e `-`, até 64
         * caracteres. Lista do que PODE, e não do que não pode: sem ponto, um
         * id nunca é `.`, `..` nem o nome do `lista.json` ou do temporário dele,
         * que moram na mesma pasta. Todo id que o app gera (`padrao`, `p` mais
         * um número) cabe nela com folga.
         */
        private val ID_SEGURO = Regex("""[A-Za-z0-9_-]{1,64}""")

        // ------------------------------------------------------------ regras puras

        /**
         * Nome da cópia de um protocolo: "Mama" vira "Mama (1)", e duplicar
         * "Mama (1)" com "Mama (1)" já existente dá "Mama (2)".
         *
         * O número final do original sai antes de contar, senão a cópia da
         * cópia viraria "Mama (1) (1)". Se tirar o número deixar o nome vazio
         * — um protocolo chamado só "(3)" — o nome inteiro é a base. A
         * comparação ignora espaço nas pontas e maiúsculas, porque "mama (1)" e
         * "Mama (1)" lado a lado na lista são, para quem lê, o mesmo nome. O
         * menor número livre é usado, então um buraco deixado por exclusão é
         * preenchido.
         */
        fun nomeDuplicado(nome: String, existentes: Collection<String>): String {
            val limpo = nome.trim()
            val base = limpo.replace(SUFIXO_NUMERO, "").trim().ifEmpty { limpo }
            val ocupados = existentes.map { it.trim().lowercase() }.toHashSet()
            fun rotulo(n: Int) = if (base.isEmpty()) "($n)" else "$base ($n)"
            var n = 1
            while (rotulo(n).lowercase() in ocupados) n++
            return rotulo(n)
        }

        /**
         * A lista com o item [de] deslocado [delta] posições.
         *
         * Destino fora da lista devolve a PRÓPRIA lista recebida (mesma
         * referência), e é assim que quem chama sabe que não há o que gravar.
         */
        fun <T> moverItem(lista: List<T>, de: Int, delta: Int): List<T> {
            val para = de + delta
            if (delta == 0 || de !in lista.indices || para !in lista.indices) return lista
            val m = lista.toMutableList()
            m.add(para, m.removeAt(de))
            return m
        }

        /**
         * A lista de protocolos com [id] deslocado [delta] posições, e o PADRÃO
         * FIXO NO TOPO: ele não se move, e nenhum outro sobe para cima dele.
         *
         * O piso é a posição logo abaixo do padrão, e não o índice 1 escrito à
         * mão, para que uma lista sem padrão (que [ordenarComPadrao] nunca
         * produz) continue se movendo inteira. Sem movimento possível — padrão,
         * id ausente, ponta da lista ou destino acima do piso — devolve a
         * PRÓPRIA lista recebida, como [moverItem].
         */
        internal fun moverProtocolo(lista: List<Protocolo>, id: String, delta: Int): List<Protocolo> {
            if (id == ID_PADRAO) return lista
            val piso = lista.indexOfFirst { it.id == ID_PADRAO } + 1
            val de = lista.indexOfFirst { it.id == id }
            if (de < piso || de + delta < piso) return lista
            return moverItem(lista, de, delta)
        }

        /**
         * A ordem da lista é a ordem do arquivo, com o PADRÃO SEMPRE NO TOPO.
         *
         * O padrão é a ficha sem página acrescentada, o estado ao qual sempre se
         * pode voltar; ele abre a lista e não sai de lá ([moverProtocolo]).
         * Presente no arquivo, entra a versão gravada — com o nome, a miniatura
         * e as páginas que o serviço lhe deu —, venha de que posição vier.
         * Ausente — `lista.json` corrompido, apagado ou vindo de importação
         * incompleta —, entra [sintetico], e no pior caso o serviço volta a ter
         * a ficha como ela sempre foi, em vez de uma tela de seleção vazia. Id
         * repetido fica com a primeira ocorrência.
         */
        fun ordenarComPadrao(lidos: List<Protocolo>, sintetico: Protocolo): List<Protocolo> {
            val unicos = lidos.distinctBy { it.id }
            val padrao = unicos.firstOrNull { it.id == ID_PADRAO } ?: sintetico
            return listOf(padrao) + unicos.filter { it.id != ID_PADRAO }
        }

        /**
         * Páginas que o protocolo acrescenta à ficha: as de cada PDF da frente
         * mais as do verso, que também são impressas.
         *
         * A folha em branco que a impressão frente-e-verso intercala NÃO entra:
         * ela depende do paciente, não do protocolo. Arquivo ilegível conta 0.
         */
        fun totalPaginas(paginas: List<Pagina>, contar: (String) -> Int): Int =
            paginas.sumOf { pg ->
                contar(pg.arquivo).coerceAtLeast(0) +
                    (if (pg.verso.isNotBlank()) contar(pg.verso).coerceAtLeast(0) else 0)
            }

        /**
         * Rótulo sugerido para um PDF a partir do nome que o provedor de
         * documentos informa: sem a extensão `.pdf`, sem espaço nas pontas, com
         * no máximo [NOME_PAGINA_MAX] caracteres. Nome ausente vira rótulo
         * vazio, que é o mesmo que não ter rótulo.
         */
        internal fun rotuloDeArquivo(displayName: String?): String {
            if (displayName == null) return ""
            val limpo = displayName.replace(CONTROLE, " ").trim()
                .replace(EXTENSAO_PDF, "").trim()
            return cortar(limpo, NOME_PAGINA_MAX).trim()
        }

        /**
         * O nome que o usuário digitou para um PDF, como vai ser gravado:
         * caractere de controle vira espaço, sem espaço nas pontas, com no
         * máximo [NOME_PAGINA_MAX] caracteres. Ao contrário de
         * [rotuloDeArquivo], não tira `.pdf`: o que foi digitado é o rótulo
         * que a pessoa quer ver.
         *
         * GUARDA: o rótulo é de uma linha só, e texto colado de outro app
         * pode trazer quebra de linha mesmo num campo de linha única. Vazio é
         * válido — é o PDF sem rótulo, e o campo nunca é obrigatório.
         */
        internal fun nomeDePagina(texto: String): String =
            cortar(texto.replace(CONTROLE, " ").trim(), NOME_PAGINA_MAX).trim()

        /** Corta sem partir um par substituto (emoji) ao meio. */
        private fun cortar(s: String, max: Int): String {
            if (s.length <= max) return s
            var fim = max
            if (Character.isHighSurrogate(s[fim - 1])) fim--
            return s.substring(0, fim)
        }

        /**
         * Primeiro id livre a partir de `p<base>`, subindo de um em um.
         *
         * Duas duplicações no mesmo milissegundo teriam o mesmo carimbo, e a
         * segunda gravaria os PDFs na pasta da primeira.
         */
        internal fun idLivre(base: Long, ocupado: (String) -> Boolean): String {
            var n = base
            while (ocupado("p$n")) n++
            return "p$n"
        }

        /**
         * Nome que pode ser resolvido DENTRO da pasta do protocolo. O nome vem
         * do `lista.json`, que pode ter chegado num pacote: com barra ou `..`
         * ele apontaria para fora da pasta. Caractere de controle também sai —
         * o NUL corta o caminho em algumas camadas do sistema e não em outras.
         */
        internal fun nomeSimples(nome: String): Boolean =
            nome.isNotBlank() && nome != "." && nome != ".." &&
                '/' !in nome && '\\' !in nome && !CONTROLE.containsMatchIn(nome)

        /** O id pode virar nome de pasta sob `protocolos/`? Ver [ID_SEGURO]. */
        internal fun idValido(id: String): Boolean = ID_SEGURO.matches(id)

        /**
         * Caminho de um binário de protocolo dentro do pacote, já sem o prefixo
         * `protocolos/`: exatamente `<id>/<arquivo>`, com o id em [idValido] e o
         * arquivo em [nomeSimples].
         *
         * Arquivo solto na raiz — `lista.json`, por exemplo — não é binário de
         * protocolo: aceitá-lo deixaria o pacote trocar a lista inteira por fora
         * de [importarJson], que é onde ids e nomes são conferidos.
         */
        internal fun entradaDoPacoteValida(rel: String): Boolean {
            val partes = rel.split('/')
            return partes.size == 2 && idValido(partes[0]) && nomeSimples(partes[1])
        }

        /**
         * [partes] resolvidas sob [raiz], só se o resultado ficar DENTRO dela e
         * exatamente onde os nomes dizem; `null` em qualquer outro caso.
         *
         * Cada parte passa por [nomeSimples], e o caminho CANÔNICO do resultado
         * tem de ser igual à simples junção da raiz canônica com as partes.
         * Comparar o canônico, e não o texto, pega o que um filtro de texto
         * deixa passar: uma ligação simbólica no caminho, ou uma grafia que o
         * sistema de arquivos ainda resolveria para outro lugar. Na dúvida a
         * resposta é não, porque o uso é apagar, e apagar o arquivo errado não
         * se desfaz.
         */
        internal fun resolverDentro(raiz: File, vararg partes: String): File? {
            if (partes.isEmpty() || partes.any { !nomeSimples(it) }) return null
            return try {
                val esperado = partes.fold(raiz.canonicalFile) { pai, nome -> File(pai, nome) }
                if (esperado.canonicalFile.path == esperado.path) esperado else null
            } catch (_: Exception) { null }
        }

        /**
         * Apaga a pasta do protocolo [id] sob [raiz], e só ela.
         *
         * O id tem de passar em [idValido] e a pasta, em [resolverDentro]. Sem
         * essas duas conferências, o id `..` faria a exclusão levar o
         * `filesDir` inteiro: cadastro de pacientes, assinaturas, perfis de
         * sincronia. `true` também quando a pasta já não existia.
         */
        internal fun apagarPasta(raiz: File, id: String): Boolean {
            if (!idValido(id)) return false
            val pasta = resolverDentro(raiz, id) ?: return false
            return !pasta.exists() || apagarArvore(pasta)
        }

        /**
         * Apaga UM arquivo da pasta do protocolo [id] sob [raiz] — o PDF de uma
         * página, o verso, a miniatura. Mesmas conferências de [apagarPasta];
         * nome que aponte para fora, ou para uma ligação, não apaga nada.
         */
        internal fun apagarArquivo(raiz: File, id: String, nome: String): Boolean {
            if (!idValido(id)) return false
            val alvo = resolverDentro(raiz, id, nome) ?: return false
            return alvo.isFile && alvo.delete()
        }

        /**
         * `deleteRecursively` sem atravessar ligação simbólica.
         *
         * O `deleteRecursively` da biblioteca desce por `isDirectory`, que segue
         * a ligação: uma ligação dentro da pasta do protocolo apontando para o
         * `filesDir` o faria apagar o destino. Aqui só se desce por filho cujo
         * caminho canônico é o próprio caminho — pasta real, não ligação —, e a
         * ligação é apagada como entrada, deixando o destino intacto.
         * [pasta] já chega canônica, de [resolverDentro].
         */
        private fun apagarArvore(pasta: File): Boolean {
            pasta.listFiles()?.forEach { filho ->
                val real = try {
                    filho.canonicalFile.path == filho.path
                } catch (_: Exception) { false }
                if (real && filho.isDirectory) apagarArvore(filho) else filho.delete()
            }
            return pasta.delete() || !pasta.exists()
        }

        /**
         * Troca, NO MESMO LUGAR, a página de mesmo arquivo; acrescenta no fim se
         * ela não estiver lá. A posição na lista é a ordem de impressão.
         */
        internal fun substituirPagina(paginas: List<Pagina>, nova: Pagina): List<Pagina> {
            val i = paginas.indexOfFirst { it.arquivo == nova.arquivo }
            return if (i >= 0) paginas.toMutableList().also { it[i] = nova } else paginas + nova
        }

        // ------------------------------------------------------------ json

        /**
         * As chaves curtas de uma posição.
         *
         * `rd` (rodapé) é a exceção: só é escrita quando difere de
         * [rodapeReferencia], que é o valor que a leitura usaria na falta dela.
         * Na base da página a referência é `true`, o padrão — e assim arquivo e
         * pacote antigos, que não têm a chave, leem "rodapé ligado", e página
         * com o rodapé ligado continua gerando os mesmos bytes de antes. Numa
         * entrada de `pp` a referência é o rodapé da base da própria página,
         * porque é nele que a leitura cai quando a chave falta.
         */
        private fun escreverPosicao(o: JSONObject, pos: Posicao, rodapeReferencia: Boolean = true) {
            o.put("etq", pos.etqAtiva)
            o.put("ex", pos.etqXmm.toDouble()); o.put("ey", pos.etqYmm.toDouble())
            o.put("ew", pos.etqWmm.toDouble()); o.put("eh", pos.etqHmm.toDouble())
            o.put("lg", pos.logoAtivo)
            o.put("lx", pos.logoXmm.toDouble()); o.put("ly", pos.logoYmm.toDouble())
            if (pos.rodapeAtivo != rodapeReferencia) o.put("rd", pos.rodapeAtivo)
        }

        /** Chave ausente cai no valor de [base], uma a uma. */
        private fun lerPosicao(o: JSONObject, base: Posicao): Posicao = Posicao(
            etqAtiva = o.optBoolean("etq", base.etqAtiva),
            etqXmm = o.optDouble("ex", base.etqXmm.toDouble()).toFloat(),
            etqYmm = o.optDouble("ey", base.etqYmm.toDouble()).toFloat(),
            etqWmm = o.optDouble("ew", base.etqWmm.toDouble()).toFloat(),
            etqHmm = o.optDouble("eh", base.etqHmm.toDouble()).toFloat(),
            logoAtivo = o.optBoolean("lg", base.logoAtivo),
            logoXmm = o.optDouble("lx", base.logoXmm.toDouble()).toFloat(),
            logoYmm = o.optDouble("ly", base.logoYmm.toDouble()).toFloat(),
            rodapeAtivo = o.optBoolean("rd", base.rodapeAtivo))

        /**
         * Uma página em JSON — a mesma para o `lista.json` e para o pacote.
         *
         * `nome`, `pp` e `rd` só são escritos quando têm conteúdo. Protocolo que
         * ninguém tocou continua gerando exatamente os mesmos bytes, o que
         * mantém estável qualquer comparação feita sobre o arquivo.
         */
        internal fun paginaParaJson(pg: Pagina): JSONObject = JSONObject().apply {
            put("arquivo", pg.arquivo)
            put("verso", pg.verso)
            escreverPosicao(this, pg.posicaoBase)
            if (pg.nome.isNotEmpty()) put("nome", pg.nome)
            if (pg.ajustes.isNotEmpty()) {
                put("pp", JSONArray().apply {
                    pg.ajustes.forEach { a ->
                        put(JSONObject().apply {
                            put("i", a.indice)
                            escreverPosicao(this, a.posicao, rodapeReferencia = pg.rodapeAtivo)
                        })
                    }
                })
            }
        }

        /**
         * Um protocolo em JSON.
         *
         * @param rubricarioNome só o pacote de exportação leva o nome da equipe
         *   (ver [exportarJson]); `null` omite a chave, como no `lista.json`.
         */
        internal fun protocoloParaJson(p: Protocolo, rubricarioNome: String? = null): JSONObject =
            JSONObject().apply {
                put("id", p.id); put("nome", p.nome); put("miniatura", p.miniatura)
                put("rubricario", p.rubricarioId)
                if (rubricarioNome != null) put("rubricario_nome", rubricarioNome)
                put("paginas", JSONArray().apply {
                    p.paginas.forEach { put(paginaParaJson(it)) }
                })
            }

        /**
         * Lê um protocolo do `lista.json` ou de um pacote. `null` se o id faltar
         * ou não passar em [idValido] — sem id seguro não há pasta onde procurar
         * os PDFs, nem pasta que se possa apagar depois.
         *
         * A MESMA REGRA VALE PARA OS NOMES DE ARQUIVO, porque é por eles que a
         * página é aberta e apagada. Página cujo PDF, ou cujo verso declarado,
         * falha em [nomeSimples] é descartada inteira; miniatura que falha vira
         * "sem miniatura". Todo nome que o app gera passa, então o que cai aqui
         * só pode ter vindo de um pacote montado à mão.
         *
         * `pp` é lido com cada chave caindo na base da própria página; entrada
         * com índice 0 ou negativo é descartada (a página 0 é a base), e índice
         * repetido fica com a primeira ocorrência.
         */
        internal fun deJson(o: JSONObject?): Protocolo? {
            if (o == null) return null
            val id = o.optString("id")
            if (!idValido(id)) return null
            val arrP = o.optJSONArray("paginas")
            val paginas = if (arrP == null) emptyList() else
                (0 until arrP.length()).mapNotNull { i ->
                    val po = arrP.optJSONObject(i) ?: return@mapNotNull null
                    val arq = po.optString("arquivo")
                    val verso = po.optString("verso")
                    if (!nomeSimples(arq) || (verso.isNotBlank() && !nomeSimples(verso))) null
                    else {
                        val base = lerPosicao(po, Posicao())
                        Pagina(
                            arquivo = arq,
                            verso = verso,
                            etqAtiva = base.etqAtiva,
                            etqXmm = base.etqXmm, etqYmm = base.etqYmm,
                            etqWmm = base.etqWmm, etqHmm = base.etqHmm,
                            logoAtivo = base.logoAtivo,
                            logoXmm = base.logoXmm, logoYmm = base.logoYmm,
                            nome = po.optString("nome"),
                            ajustes = lerAjustes(po.optJSONArray("pp"), base),
                            rodapeAtivo = base.rodapeAtivo)
                    }
                }
            val miniatura = o.optString("miniatura")
            return Protocolo(
                id = id,
                nome = o.optString("nome"),
                padrao = id == ID_PADRAO,
                miniatura = if (miniatura.isBlank() || nomeSimples(miniatura)) miniatura else "",
                rubricarioId = o.optString("rubricario",
                    com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO)
                    .ifBlank { com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO },
                paginas = paginas)
        }

        private fun lerAjustes(arr: JSONArray?, base: Posicao): List<AjustePagina> {
            if (arr == null) return emptyList()
            val vistos = HashSet<Int>()
            return (0 until arr.length()).mapNotNull { j ->
                val ao = arr.optJSONObject(j) ?: return@mapNotNull null
                val indice = ao.optInt("i", 0)
                if (indice <= 0 || !vistos.add(indice)) null
                else AjustePagina(indice, lerPosicao(ao, base))
            }.sortedBy { it.indice }
        }

        /** O conteúdo do `lista.json`, na ordem da lista. */
        internal fun listaParaTexto(lista: List<Protocolo>): String =
            JSONArray().apply { lista.forEach { put(protocoloParaJson(it)) } }.toString()

        /** Lê o `lista.json`. Lança se o texto não for um array JSON. */
        internal fun listaDeTexto(texto: String): List<Protocolo> {
            val arr = JSONArray(texto)
            return (0 until arr.length()).mapNotNull { i -> deJson(arr.optJSONObject(i)) }
        }

        /**
         * Grava [texto] em [destino] sem nunca deixar um arquivo pela metade.
         *
         * Escreve num `.tmp` ao lado e troca pelo nome definitivo com
         * `renameTo`, que no Android substitui o destino de uma vez. Um app
         * derrubado no meio da escrita deixa o arquivo anterior inteiro — e
         * não um JSON truncado que [listar] leria como lista vazia, com todos
         * os protocolos sumindo da tela.
         *
         * Só a falha da TROCA cai na gravação direta. Se o próprio `.tmp` não
         * pôde ser escrito (armazenamento cheio, por exemplo), gravar direto
         * truncaria o arquivo bom e falharia do mesmo jeito; por isso o erro é
         * repassado a quem chamou, e o `lista.json` anterior fica intacto.
         */
        internal fun gravarAtomico(destino: File, texto: String) {
            val tmp = File(destino.parentFile, destino.name + ".tmp")
            val bytes = texto.toByteArray(Charsets.UTF_8)
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.flush()
                    try { out.fd.sync() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                try { tmp.delete() } catch (_: Exception) {}
                throw e
            }
            if (tmp.renameTo(destino)) return
            try { tmp.delete() } catch (_: Exception) {}
            destino.writeBytes(bytes)
        }
    }

    // ---------------------------------------------------------------- leitura

    /**
     * Todos os protocolos, na ordem em que o usuário os deixou, com o padrão
     * SEMPRE presente e SEMPRE primeiro.
     *
     * A ordem é a posição no `lista.json` — sem campo próprio, e por isso já
     * entendida por pacotes e versões anteriores. O padrão não se move
     * ([moverProtocolo]); ver [ordenarComPadrao] para o arquivo que o traz em
     * outra posição ou não o traz. Entrada de id ou nome inseguro não aparece
     * ([deJson]).
     */
    fun listar(): List<Protocolo> {
        val lidos = try {
            if (!arquivo.exists()) emptyList() else listaDeTexto(arquivo.readText())
        } catch (_: Exception) { emptyList() }
        return ordenarComPadrao(lidos, Protocolo(ID_PADRAO, nomePadrao(), true))
    }

    fun obter(id: String): Protocolo? = listar().firstOrNull { it.id == id }

    /** Há mais de um protocolo? A tela de seleção só aparece quando sim. */
    fun temEscolha(): Boolean = listar().size > 1

    /**
     * Arquivo [nome] da pasta do protocolo [id], para LER. `null` quando o id
     * ou o nome não passam nas regras ([idValido], [nomeSimples]). Não cria a
     * pasta, ao contrário de [pastaDe]: ler não deve deixar rastro em disco.
     */
    private fun arquivoLido(id: String, nome: String): File? =
        if (idValido(id) && nomeSimples(nome)) File(File(dir, id), nome) else null

    fun arquivoMiniatura(p: Protocolo): File? =
        arquivoLido(p.id, p.miniatura)?.takeIf { it.exists() }

    /**
     * A miniatura, JÁ NA ORIENTAÇÃO CERTA.
     *
     * `BitmapFactory.decodeFile` ignora a etiqueta de orientação do EXIF: a foto
     * tirada com o tablet em pé volta deitada, e era isso que fazia as
     * miniaturas dos protocolos aparecerem giradas. O app já resolve isso em
     * quatro lugares (`ImagemUtils`, `ExifWatermark`, `PdfBuilder`,
     * `VisibleWatermark`) — a miniatura foi a que ficou de fora.
     */
    fun bitmapMiniatura(p: Protocolo): Bitmap? = try {
        arquivoMiniatura(p)?.let {
            com.radioterapia.ai.util.ImagemUtils.decodificarComExif(it, 640)
        }
    } catch (_: Throwable) { null }

    /**
     * Gira a miniatura 90° à direita e grava já girada.
     *
     * GRAVA EM VEZ DE GUARDAR O ÂNGULO. Um campo de rotação obrigaria todo mundo
     * que lê a miniatura a lembrar de aplicá-lo — e quem esquecesse mostraria a
     * imagem torta de novo, que é o defeito que isto conserta. Gravada girada,
     * não há o que lembrar.
     *
     * A perda de qualidade é aceitável: é uma miniatura de identificação, não a
     * página que vai impressa.
     */
    fun girarMiniatura(p: Protocolo): Boolean = try {
        val arq = arquivoMiniatura(p)
        val bm = arq?.let { com.radioterapia.ai.util.ImagemUtils.decodificarComExif(it, 1280) }
        if (arq == null || bm == null) false
        else {
            val m = android.graphics.Matrix().apply { postRotate(90f) }
            val girado = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
            arq.outputStream().use {
                girado.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (girado != bm) bm.recycle()
            girado.recycle()
            true
        }
    } catch (_: Throwable) { false }

    /** PDF de uma página, se ainda existir em disco. */
    fun arquivoPagina(p: Protocolo, pag: Pagina): File? =
        arquivoLido(p.id, pag.arquivo)?.takeIf { it.exists() && it.length() > 0 }

    /** O PDF do verso, se esta página tiver um. Verso vazio falha em [nomeSimples]. */
    fun arquivoVerso(p: Protocolo, pag: Pagina): File? =
        arquivoLido(p.id, pag.verso)?.takeIf { it.exists() && it.length() > 0 }

    /**
     * Quantas páginas o protocolo acrescenta à ficha impressa (ver
     * [totalPaginas]).
     *
     * ABRE CADA PDF. Numa lista com vários protocolos isso não pode rodar na
     * thread principal. Não cria a pasta do protocolo, ao contrário de
     * [pastaDe]: contar não deve deixar rastro em disco.
     */
    fun contarPaginas(p: Protocolo): Int = totalPaginas(p.paginas) { nome ->
        arquivoLido(p.id, nome)?.let { com.radioterapia.ai.pdf.PdfPaginas.contar(it) } ?: 0
    }

    // ---------------------------------------------------------------- escrita

    /**
     * Cria ou atualiza. O protocolo padrão pode ser editado como qualquer outro.
     *
     * Id fora de [idValido] é recusado aqui também, e não só na leitura: o que
     * nunca entra no `lista.json` não precisa ser filtrado depois.
     */
    fun salvar(p: Protocolo): Boolean {
        if (!idValido(p.id)) return false
        return try {
            synchronized(TRAVA) {
                val atuais = listar().toMutableList()
                val i = atuais.indexOfFirst { it.id == p.id }
                if (i >= 0) atuais[i] = p else atuais.add(p)
                gravar(atuais)
            }
            true
        } catch (_: Exception) { false }
    }

    /**
     * Grava UMA página do protocolo, relida do disco na hora de gravar.
     *
     * A página é trocada no lugar, pelo nome do arquivo, dentro da versão
     * ATUAL do protocolo — e não de uma cópia guardada quando a tela abriu,
     * que perderia o que outra tela mudou nesse meio-tempo. Quem chama deve
     * partir da página guardada (`copy`), nunca de um `Pagina(...)` novo:
     * construir do zero zera o verso, o nome e os ajustes por página, que a
     * tela de calibração não mostra.
     */
    fun atualizarPagina(idProtocolo: String, pagina: Pagina): Boolean = try {
        synchronized(TRAVA) {
            val todos = listar()
            val i = todos.indexOfFirst { it.id == idProtocolo }
            if (i < 0) false
            else {
                val lista = todos.toMutableList()
                lista[i] = todos[i].copy(paginas = substituirPagina(todos[i].paginas, pagina))
                gravar(lista)
                true
            }
        }
    } catch (_: Exception) { false }

    /**
     * Desloca um protocolo [delta] posições na lista, sempre abaixo do padrão
     * ([moverProtocolo]). `false` quando o id é o do padrão, quando o destino
     * ficaria acima dele ou fora da lista, quando o id não existe ou quando a
     * gravação falha.
     */
    fun mover(id: String, delta: Int): Boolean = try {
        synchronized(TRAVA) {
            val todos = listar()
            val nova = moverProtocolo(todos, id, delta)
            if (nova === todos) false
            else {
                gravar(nova)
                true
            }
        }
    } catch (_: Exception) { false }

    /**
     * Exclui um protocolo e a pasta dele.
     *
     * O PADRÃO NÃO SAI. Ele é o estado ao qual sempre se pode voltar, e sem ele
     * um serviço que apagasse tudo ficaria sem nenhuma opção na tela de
     * seleção — sem forma de imprimir a ficha simples.
     *
     * A pasta sai por [apagarPasta], que só apaga o que estiver de fato sob
     * `protocolos/`; id que não passa em [idValido] não apaga nada.
     */
    fun excluir(id: String): Boolean {
        if (id == ID_PADRAO || !idValido(id)) return false
        return try {
            synchronized(TRAVA) {
                val atuais = listar().filter { it.id != id }
                gravar(atuais)
            }
            apagarPasta(dir, id)
            true
        } catch (_: Exception) { false }
    }

    /**
     * Apaga UM arquivo da pasta do protocolo — o PDF de uma página, o verso.
     * Só a tela de edição chama, ao remover uma página ou um verso. Nome ou
     * id que apontem para fora de `protocolos/<id>/` não apagam nada
     * ([apagarArquivo]).
     */
    fun excluirArquivo(idProtocolo: String, nome: String): Boolean =
        try { apagarArquivo(dir, idProtocolo, nome) } catch (_: Exception) { false }

    fun novoId(): String = "p" + System.currentTimeMillis()

    /**
     * Cópia de um protocolo, logo abaixo do original na lista. `null` se o
     * original não existir ou se a cópia falhar.
     *
     * A cópia tem id e pasta próprios e leva os PDFs, os versos e a miniatura
     * que o original REFERENCIA — arquivo órfão na pasta não vai junto. Os
     * nomes de arquivo se repetem entre as duas pastas sem conflito: o pacote
     * de exportação grava cada arquivo sob a pasta do seu protocolo. Páginas,
     * nomes dos PDFs, posições por página, equipe do rubricário e miniatura
     * passam iguais; o nome ganha o número de [nomeDuplicado]. Copiar o padrão
     * dá um protocolo comum, que pode ser excluído.
     *
     * Os arquivos são copiados FORA da [TRAVA]; só a leitura e a gravação da
     * lista ficam dentro, para que uma seta tocada durante a cópia não espere
     * os PDFs. A pasta nova é criada já ao escolher o id, o que a reserva
     * contra outra duplicação simultânea. Qualquer falha apaga a pasta nova:
     * não sobra cópia pela metade.
     *
     * Lê e grava arquivos — não chamar na thread principal.
     */
    fun duplicar(id: String): Protocolo? {
        val orig = obter(id) ?: return null
        var pastaNova: File? = null
        return try {
            val novo = synchronized(TRAVA) {
                val ids = listar().map { it.id }.toHashSet()
                val livre = idLivre(System.currentTimeMillis()) {
                    it in ids || File(dir, it).exists()
                }
                File(dir, livre).mkdirs()
                livre
            }
            val destino = File(dir, novo)
            pastaNova = destino
            if (!destino.isDirectory) throw IOException("pasta do protocolo não criada")

            val origem = File(dir, orig.id)
            val referenciados = (orig.paginas.flatMap { listOf(it.arquivo, it.verso) } +
                orig.miniatura).filter { nomeSimples(it) }.distinct()
            referenciados.forEach { nome ->
                val f = File(origem, nome)
                if (f.isFile) f.copyTo(File(destino, nome), overwrite = true)
            }

            synchronized(TRAVA) {
                val todos = listar()
                val copia = orig.copy(
                    id = novo,
                    padrao = false,
                    nome = nomeDuplicado(orig.nome, todos.map { it.nome }))
                val lista = todos.toMutableList()
                val pos = todos.indexOfFirst { it.id == orig.id }
                if (pos >= 0) lista.add(pos + 1, copia) else lista.add(copia)
                gravar(lista)
                copia
            }
        } catch (_: Exception) {
            try { pastaNova?.let { apagarPasta(dir, it.name) } } catch (_: Exception) {}
            null
        }
    }

    private fun nomePadrao(): String =
        try { context.getString(com.radioterapia.ai.R.string.prot_padrao_nome) }
        catch (_: Exception) { "Padrão" }

    // ---------------------------------------------------------------- transferência

    /**
     * A lista de protocolos, SEM os binários.
     *
     * Os PDFs e as miniaturas viajam como arquivos próprios dentro do pacote,
     * não em Base64 aqui. Um termo de consentimento de três páginas passa
     * facilmente de um megabyte, e embutir isso num JSON produziria um arquivo
     * que o tablet não consegue nem abrir para ler o resto da configuração.
     *
     * A ordem do array é a ordem da lista, e a importação acrescenta na ordem
     * do pacote: um tablet novo recebe os protocolos na mesma sequência.
     */
    fun exportarJson(): JSONArray {
        val arr = JSONArray()
        // Para dizer QUAL EQUIPE por nome, e não pelo id: id de bloco é carimbo
        // de tempo do tablet de origem, e a mesma "Clínica A" nasce com outro id
        // no destino. Guardar só o id faria o protocolo importado cair no bloco
        // padrão — a ficha sairia assinada pela equipe errada, em silêncio.
        val nomesBloco = com.radioterapia.ai.rubricario.RubricarioStore(context).listarBlocos().associate { it.id to it.nome }
        listar().forEach { p ->
            arr.put(protocoloParaJson(p, rubricarioNome =
                if (p.rubricarioId == com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO) ""
                else nomesBloco[p.rubricarioId].orEmpty()))
        }
        return arr
    }

    /**
     * Aplica uma lista vinda de outro tablet. Devolve quantos entraram.
     *
     * SOMAR não toca no que já existe: um protocolo cujo id já está aqui é
     * pulado inteiro, porque substituí-lo em silêncio trocaria os PDFs que o
     * serviço local carregou pelos de outra unidade. O PADRÃO é sempre pulado
     * no modo somar — ele existe em todo tablet, e importá-lo apagaria a
     * calibração local.
     *
     * Em SOBRESCREVER, protocolo de id já existente é trocado NO LUGAR: ele
     * mantém a posição local, e não a do pacote. Os novos entram no fim, na
     * ordem do pacote.
     *
     * O pacote é dado não confiável: protocolo de id inseguro é pulado, e
     * página de nome inseguro sai do protocolo, já na leitura ([deJson]).
     */
    fun importarJson(arr: JSONArray, sobrescrever: Boolean): Int {
        var n = 0
        val atuais = listar().associateBy { it.id }
        val rub = com.radioterapia.ai.rubricario.RubricarioStore(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            // A equipe é reencontrada (ou recriada) PELO NOME. Nome vazio é o
            // padrão — inclusive em pacote antigo, que não tinha este campo e
            // vinha de um tablet sem blocos.
            val p = deJson(o)?.let {
                it.copy(rubricarioId = rub.garantirBloco(
                    o?.optString("rubricario_nome").orEmpty().trim()))
            } ?: continue
            val existe = atuais.containsKey(p.id)
            if (existe && !sobrescrever) continue
            if (p.id == ID_PADRAO && !sobrescrever) continue
            if (salvar(p)) n++
        }
        return n
    }

    // ---------------------------------------------------------------- json

    private fun gravar(lista: List<Protocolo>) {
        gravarAtomico(arquivo, listaParaTexto(lista))
    }
}
