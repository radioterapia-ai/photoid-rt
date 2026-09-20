package com.radioterapia.ai.branding

/**
 * A marca do projeto, num lugar só.
 *
 * POR QUE ISTO EXISTE
 * A licença é Apache-2.0 e a seção 6 dela **não licencia marca**: um fork pode
 * usar o código, não o nome. Só que uma exigência dessas só é honesta se ela
 * for cumprível — política que manda trocar o nome e espalha esse nome por
 * dezenas de arquivos transforma "renomeie" numa caça sem fim, e quem forka
 * acaba deixando pedaço nosso para trás sem má intenção nenhuma.
 *
 * Então tudo o que carrega a marca e é **visível para o usuário** se resolve
 * aqui e nas strings (`app_name`, `learn_more`, `about_website` em cada
 * `res/values-XX`; `about_title` só no `values` base, com
 * `translatable="false"`, porque nome de produto não se traduz e espalhá-lo por
 * doze arquivos era justamente o que tornava "renomeie" impraticável). Trocar
 * esses dois lugares e o ícone basta.
 *
 * O QUE **NÃO** ENTRA AQUI
 * O namespace `com.radioterapia.ai` fica como está, e a `TRADEMARK.md` diz
 * expressamente que o fork pode mantê-lo. Namespace de pacote é identificador
 * técnico que nenhum usuário vê; exigir a troca custaria renomear ~100 arquivos
 * e não protegeria ninguém de confusão sobre a origem do produto — que é a
 * única coisa que a política de marca existe para evitar.
 *
 * Também não entra o logotipo do SERVIÇO, que é do próprio usuário e é tratado
 * pelo [LogoManager]. Aquele nunca foi nosso.
 */
object Marca {

    /**
     * O nome do produto, para uso em caminho de arquivo e nome de pasta.
     *
     * COMO ELE SE ESCREVE QUANDO É LOGOTIPO: **"PhotoID" em branco e "RT" no
     * gradiente** — a mesma construção do logotipo do ecossistema, onde
     * "radioterapia" é branco e ".ai" recebe o gradiente. Duas palavras, um
     * contraste, e o olho separa o produto da família sem precisar de legenda.
     *
     * O gradiente é o de [GRADIENTE_LOGOTIPO], que é o que `colors.xml` declara
     * como idêntico ao logo.
     *
     * Aqui a constante é texto puro, porque caminho de arquivo não tem cor. A
     * regra vale para peça gráfica — o cabeçalho do repositório a segue. A tela
     * Sobre ainda desenha o nome chapado: mudá-la exige `SpannableString` com
     * `LinearGradient`, e é alteração de interface que ninguém pediu.
     */
    const val NOME = "PhotoID RT"

    /**
     * O gradiente do logotipo, espelho de `grad_start`/`grad_mid`/`grad_end` em
     * `res/values/colors.xml`.
     *
     * ATENÇÃO À DIVERGÊNCIA, que ainda não foi resolvida: a identidade do
     * ecossistema (`references/identidade-visual.md` da skill de marketing)
     * define o acento do perfil @radioterapia.ai como `#0A2A6B → #24D3EE`,
     * azul → ciano. O `colors.xml` declara `#42A5F5 → #7B6FE0 → #B254E8`,
     * azul → roxo, dizendo-o idêntico ao logo — e o logotipo renderizado em
     * `assets/marca/logotipo_9x16.png` é, de fato, azul → roxo.
     *
     * Um dos dois documentos envelheceu. Enquanto não se sabe qual, as peças
     * usam este, que é o que o logotipo mostra.
     */
    val GRADIENTE_LOGOTIPO = listOf(0xFF42A5F5.toInt(), 0xFF7B6FE0.toInt(), 0xFFB254E8.toInt())

    /** O nome do ecossistema. Também é a marca registrada. */
    const val ECOSSISTEMA = "Radioterapia.AI"

    /** O site, para os botões que abrem o navegador. */
    const val SITE = "https://radioterapia.ai"

    /**
     * A pasta pública onde vão os arquivos que o usuário exporta para si —
     * estatística e log —, dentro de `Documents/`.
     *
     * SEPARADA das fotos de propósito: as fotos moram em `PhotoID_RT/PHOTOS/`,
     * na raiz, porque é de lá que o FileSync as recolhe. Isto aqui é material
     * que o usuário abre no próprio tablet, e `Documents/` é onde o Android
     * espera encontrá-lo.
     */
    const val PASTA_DOCUMENTOS = "Documents/$ECOSSISTEMA"

    fun subpastaDocumentos(nome: String): String = "$PASTA_DOCUMENTOS/$nome"
}
