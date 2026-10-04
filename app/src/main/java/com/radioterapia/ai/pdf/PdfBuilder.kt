package com.radioterapia.ai.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.exifinterface.media.ExifInterface
import com.radioterapia.ai.R
import com.radioterapia.ai.branding.LogoManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gera os 2 PDFs ao finalizar uma simulação:
 *
 * 1) Folha de Posicionamento (gerar() → PDF principal)
 *    - A4 retrato
 *    - Cabeçalho generoso de 3 colunas (espaço pra etiqueta física | dados | logo+título)
 *    - Cabeçalho idêntico em TODAS as páginas
 *    - Tabela adaptativa 1×4 → 2×4 conforme número de fotos
 *    - Ordem: rosto → etiqueta → posicionamentos
 *    - Numeração tipo+número abaixo de cada célula
 *    - Paginação cheia: 8 fotos por página, depois rola pra próxima
 *
 * 2) Ficha de Acessórios (gerarFichaAcessorios() → PDF separado)
 *    - A4 retrato
 *    - Cabeçalho simplificado (nome+prontuário+data+logo)
 *    - Foto grande centralizada
 */
object PdfBuilder {

    // A4 em points (1 pt = 1/72"). Variáveis: trocadas conforme a orientação.
    private const val A4_CURTO = 595   // largura no retrato
    private const val A4_LONGO = 842   // altura no retrato
    private var PAGE_WIDTH = A4_CURTO
    private var PAGE_HEIGHT = A4_LONGO
    private var landscape = false
    private const val MARGIN = 28f          // margem base (~10 mm) já embutida no layout
    // Margem EXTRA configurável (além da base), aplicada por translate do canvas:
    // esquerda no retrato; superior no paisagem. Padrão de config: 15 mm.
    private var margemExtraEsqPt = 0f
    private var margemExtraTopoPt = 0f
    private const val HEADER_HEIGHT = 130f
    private const val MM_TO_PT = 2.83465f

    // Estado por geração (object singleton, como PAGE_WIDTH/textos)
    private var etiqLargPt = 60f * MM_TO_PT
    private var etiqAltPt = 30f * MM_TO_PT
    private var observacoesTexto = ""
    private var headerHAtual = HEADER_HEIGHT
    private var obsBandHeight = 0f

    /** Compensação de impressão: os drivers (JetDirect/IPP) escurecem as fotos
     *  em relação à rotina do Excel da clínica. Ganho leve de brilho aplicado
     *  SOMENTE nas fotos (grid + rosto); logo e gráficos ficam intactos. */
    private val paintFotoImpressao = Paint().apply {
        isFilterBitmap = true
        colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            1.07f, 0f, 0f, 0f, 8f,
            0f, 1.07f, 0f, 0f, 8f,
            0f, 0f, 1.07f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f))
    }

    // Tabela
    private const val FOTOS_POR_PAGINA = 8
    private const val GRID_COLS = 2
    private const val GRID_ROWS = 4
    private const val GAP_FOTO = 6f
    /** Teto de ampliação da célula quando sobra espaço na grade (25%). */
    private const val MAX_AMPLIACAO = 1.25f
    /** Raio dos cantos arredondados. Um só valor para o contorno da etiqueta e
     *  para as fotos, para os dois não saírem do mesmo desenho por descuido.
     *  `internal` porque a prévia da calibração do protocolo
     *  (ProtocoloPaginaView) arredonda o box com o mesmo raio. */
    internal const val RAIO_CANTO = 10f
    private const val LEGENDA_HEIGHT = 12f

    /** Proporção da foto capturada (16:9). Teto de largura da célula em
     *  paisagem, para não sobrar papel vazio ao lado da foto. */
    /** Fundo da linha da última fração na tabela do Time-Out. */
    private val VERMELHO_CLARO = Color.parseColor("#FBD9D9")
    /** Número da última fração, na primeira coluna. */
    private val VERMELHO_ESCURO = Color.parseColor("#9B1C1C")

    private const val ASPECTO_FOTO = 16f / 9f

    /** Corpo do rótulo e do valor na linha única sob a etiqueta grande.
     *  O mesmo par usado no canto superior da etiqueta, para a folha ter
     *  uma tipografia só. */
    /** Distância da linha divisória à base do cabeçalho. Ver desenharCabecalhoCompleto. */
    private const val FOLGA_DIVISORIA = 14f

    private const val ROT_LINHA = 7.5f
    private const val VAL_LINHA = 9.5f

    /**
     * Altura que a LINHA DE IDENTIFICACAO reserva sob o quadro da etiqueta.
     *
     * A faixa de topo da ficha de Time-Out tem altura fixa e a tabela de fracoes
     * comeca logo abaixo dela. Sem reservar este espaco, um quadro alto
     * empurraria a linha por cima do box de equipamento — que foi exatamente o
     * que a etiqueta de 100x50 mm fez enquanto o arranjo era decidido caso a
     * caso.
     */
    private const val ALTURA_LINHA_IDS = 16f

    /** Menor escala em que a linha de identificacao ainda se le no papel.
     *  70% de 9,5 pt da 6,65 pt no valor — o mesmo corpo das celulas da tabela
     *  de fracoes ao lado, que e lida e preenchida a mao todo dia. */
    private const val PISO_LINHA = 0.70f

    // ---- OBSERVAÇÕES: corpo, entrelinha e a caixa que cresce com o texto ----

    /** Corpo das observações, fixo: Typeface.DEFAULT preto, a família e o peso
     *  dos descritivos dentro do quadro da etiqueta. Não segue o corpo que o
     *  quadro calcula, que cai com nome longo e faria a observação mudar de
     *  tamanho de paciente para paciente. */
    private const val OBS_CORPO = 11.5f
    /** Piso do corpo das observações: o corpo que a caixa do Time-Out sempre
     *  usou. A observação nunca sai menor que isso; o texto que nem nele cabe
     *  no teto é cortado com " …" (ver arranjarObservacoes). */
    private const val OBS_CORPO_MIN = 8.5f
    private const val OBS_PASSO_CORPO = 0.5f
    private const val OBS_ENTRELINHA = 1.25f
    /** Respiro entre a borda da caixa e o texto, em cima, embaixo e dos lados. */
    private const val OBS_PAD = 6f
    /** Faixa do título "Observações" no alto da caixa do Time-Out. */
    private const val OBS_CAB_TO = 15f
    /** Caixa do Time-Out sem texto, ou com até 2 linhas: a de sempre, que deixa
     *  espaço para escrever à mão e não mexe na tabela. */
    private const val OBS_TO_ALTURA_MIN = 66f
    /**
     * Teto da altura do TEXTO das observações, nas duas páginas.
     *
     * Sai da tabela de 40 frações do Time-Out, que é A4 fixo: com a caixa de
     * 66 pt cada uma das 20 linhas tem 19,5 pt, e a menor linha em que ainda se
     * escreve à mão é 18 pt (6,35 mm, pauta estreita). São 20 × 1,5 = 30 pt a
     * ceder, e a caixa vai a no máximo 96 pt = 15 (título) + 2 × 6 (respiro) +
     * 69 (texto). Mudar este número muda a menor linha da tabela.
     */
    private const val OBS_TEXTO_MAX = 69f
    private const val OBS_TO_ALTURA_MAX = OBS_CAB_TO + 2 * OBS_PAD + OBS_TEXTO_MAX
    /** Largura do rótulo "Observações" à esquerda da faixa das páginas de fotos. */
    private const val OBS_ROTULO_W = 95f
    /** Largura útil do texto na caixa do Time-Out, que é sempre A4 retrato. */
    private const val LARGURA_TEXTO_OBS_TO = A4_CURTO - 2 * MARGIN - 2 * OBS_PAD

    // Estado por geração das observações (como obsBandHeight). Declarado depois
    // das constantes porque o inicializador não pode citar constante declarada
    // mais abaixo no objeto.
    /** Corpo das observações desta simulação, o MESMO no Time-Out e nas fotos. */
    private var obsCorpo = OBS_CORPO
    /** Linhas já quebradas para a faixa das páginas de fotos. */
    private var obsLinhasFotos: List<String> = emptyList()


    /** Define orientação da página (afeta PAGE_WIDTH/HEIGHT). */
    private fun configurarOrientacao(paisagem: Boolean) {
        landscape = paisagem
        if (paisagem) { PAGE_WIDTH = A4_LONGO; PAGE_HEIGHT = A4_CURTO }
        else { PAGE_WIDTH = A4_CURTO; PAGE_HEIGHT = A4_LONGO }
    }

    // ---- Rótulos localizados (preenchidos por carregarTextos() conforme idioma) ----
    private var txtPaciente = "PACIENTE"
    private var txtProntuario = "PRONTUÁRIO"
    private var txtNascimento = "NASCIMENTO"
    private var txtIdade = "IDADE"
    private var txtSexo = "SEXO"
    /** "REGISTRO" do bloco de identificacao. Separado de [txtProntuario], que e
     *  o rotulo da ficha de acessorios — os dois textos ja divergiam em pt-BR e
     *  igualar um ao outro mudaria uma folha que ninguem pediu para mexer. */
    private var txtRegistro = "REGISTRO"
    private var txtDataSim = "DATA DA SIMULAÇÃO:"
    private var txtTitulo1 = "FOTOS DE"
    private var txtTitulo2 = "POSICIONAMENTO"
    private var txtNovaSim = "NOVA SIMULAÇÃO"
    private var txtObservacoes = "Observações"
    private var txtRosto = "Rosto"
    private var txtEtiqueta = "Etiqueta"
    private var txtPosicionamento = "Posicionamento"
    private var txtAcessorio = "Acessório"

    // ---- Página TIME-OUT ----
    // Estes rótulos eram literais PT no meio do desenho. O cabeçalho da ficha já
    // era traduzido por carregarTextos(), mas a página de Time-Out não — então a
    // folha que vai para a sala do acelerador saía em português mesmo com o app
    // em espanhol ou inglês. É o documento impresso da conferência de segurança:
    // é o último lugar que pode estar num idioma que a equipe não lê.
    private var txtToPrecaucao = "PRECAUÇÃO DE CONTATO"
    private var txtToAlergia = "ALERGIA"
    private var txtToRiscoQueda = "RISCO DE QUEDA"
    private var txtToEquipamento = "Equipamento"
    private var txtToMedico = "Médico Responsável"
    private var txtToSitio = "Sítio anatômico / lateralidade"
    private var txtToRotina = "ROTINA DE PROCEDIMENTO SEGURO:"
    private var txtToPasso1 = ""
    private var txtToPasso2 = ""
    private var txtToPasso3 = ""
    private var txtToColNum = "#"
    private var txtToColFracao = "Fração"
    private var txtToColNome1 = "Nome e"
    private var txtToColNome2 = "e"
    private var txtToColNome3 = "Nasc."
    private var txtToColFoto = "Foto"
    private var txtToColSitio1 = "Sítio /"
    private var txtToColSitio2 = "Lateralida"
    private var txtToColFrac3 = "Total"
    private var txtToColFrac2 = "Dia/Total"
    private var txtToColQueixa1 = "Queixa e encaminhado"
    private var txtToColQueixa2 = "para avaliação"
    private var txtToColAssinatura = "Assinatura"
    private var txtToColTecnico = "Técnico"
    private var txtSim = "Sim"
    private var txtNao = "Não"
    private var txtFichaAcessorios = "FICHA DE ACESSORIOS"
    private var txtPagina = "Página %1\$d"
    private var txtRubTitulo = "RUBRICÁRIO"
    private var txtRubSubtitulo = "RADIOTERAPIA"
    private var txtRubNomePaciente = "Nome:"
    private var txtRubColRubrica = "Rubrica"

    private fun carregarTextos(context: Context) {
        txtToPrecaucao = context.getString(R.string.pdf_to_contact_precaution)
        txtToAlergia = context.getString(R.string.pdf_to_allergy)
        txtToRiscoQueda = context.getString(R.string.pdf_to_fall_risk)
        txtToEquipamento = context.getString(R.string.pdf_to_equipment)
        txtToMedico = context.getString(R.string.pdf_to_doctor)
        txtToSitio = context.getString(R.string.pdf_to_site)
        txtToRotina = context.getString(R.string.pdf_to_routine)
        txtToPasso1 = context.getString(R.string.pdf_to_step1)
        txtToPasso2 = context.getString(R.string.pdf_to_step2)
        txtToPasso3 = context.getString(R.string.pdf_to_step3)
        txtToColFracao = context.getString(R.string.pdf_to_col_fraction)
        txtToColNome1 = context.getString(R.string.pdf_to_col_name1)
        txtToColNome2 = context.getString(R.string.pdf_to_col_name2)
        txtToColFoto = context.getString(R.string.pdf_to_col_photo)
        txtToColSitio1 = context.getString(R.string.pdf_to_col_site1)
        txtToColSitio2 = context.getString(R.string.pdf_to_col_site2)
        txtToColNum = context.getString(R.string.pdf_to_col_num)
        txtToColNome3 = context.getString(R.string.pdf_to_col_name3)
        txtToColFrac3 = context.getString(R.string.pdf_to_col_frac3)
        txtToColFrac2 = context.getString(R.string.pdf_to_col_frac2)
        txtToColQueixa1 = context.getString(R.string.pdf_to_col_complaint1)
        txtToColQueixa2 = context.getString(R.string.pdf_to_col_complaint2)
        txtToColAssinatura = context.getString(R.string.pdf_to_col_sign)
        txtToColTecnico = context.getString(R.string.pdf_to_col_tech)
        txtSim = context.getString(R.string.pdf_yes)
        txtNao = context.getString(R.string.pdf_no)
        txtFichaAcessorios = context.getString(R.string.pdf_accessories_sheet)
        txtPagina = context.getString(R.string.pdf_page)
        txtRubTitulo = context.getString(R.string.rub_titulo_pdf)
        txtRubSubtitulo = context.getString(R.string.rub_subtitulo_pdf)
        txtRubNomePaciente = context.getString(R.string.rub_nome_paciente_pdf)
        txtRubColRubrica = context.getString(R.string.rub_col_rubrica)
        txtPaciente = context.getString(R.string.pdf_lbl_patient)
        txtProntuario = context.getString(R.string.pdf_lbl_record)
        txtNascimento = context.getString(R.string.pdf_lbl_birth)
        txtIdade = context.getString(R.string.pdf_lbl_age)
        txtSexo = context.getString(R.string.pdf_lbl_sex)
        txtRegistro = context.getString(R.string.pdf_lbl_registry)
        txtDataSim = context.getString(R.string.pdf_lbl_simdate)
        txtTitulo1 = context.getString(R.string.pdf_title_line1)
        txtTitulo2 = context.getString(R.string.pdf_title_line2)
        txtNovaSim = context.getString(R.string.pdf_new_sim)
        txtObservacoes = context.getString(R.string.pdf_observations)
        txtRosto = context.getString(R.string.pdf_lbl_face)
        txtEtiqueta = context.getString(R.string.pdf_lbl_label)
        txtPosicionamento = context.getString(R.string.pdf_lbl_positioning)
        txtAcessorio = context.getString(R.string.pdf_lbl_accessory)
    }

    /** Traduz um rótulo canônico em PT ("Rosto", "Posicionamento.2", "Acessório.1")
     *  para o idioma atual, preservando o sufixo ".N". */
    /** Rótulo é da etiqueta? Compara a base ("Etiqueta.2" → "Etiqueta"). O
     *  vocabulário interno é sempre PT; a tradução só acontece no desenho. */
    private fun ehEtiqueta(rotulo: String): Boolean =
        rotulo.substringBefore(".").trim().equals("Etiqueta", ignoreCase = true)

    private fun traduzirRotulo(rotuloPt: String): String {
        val partes = rotuloPt.split(".", limit = 2)
        val base = partes[0].trim()
        val sufixo = if (partes.size > 1) ".${partes[1].trim()}" else ""
        val traduzido = when (base) {
            "Rosto" -> txtRosto
            "Etiqueta" -> txtEtiqueta
            "Posicionamento" -> txtPosicionamento
            "Acessório", "Acessorio" -> txtAcessorio
            else -> base
        }
        return "$traduzido$sufixo"
    }

    data class IdExtra(val titulo: String, val valor: String)

    data class DadosCabecalho(
        val nomePaciente: String,
        val nascimento: String,
        val prontuario: String,
        val idsExtras: List<IdExtra>,        // Convênio, CPF, etc — máx 3
        val dataSimulacao: Date,
        val numeroSimulacao: Int,            // 1=primeira, 2+=nova simulação
        val nomeClinica: String,             // opcional
        val sexo: String = "",               // opcional (etiqueta virtual)
        val medicoAssistente: String = ""    // opcional (etiqueta virtual)
    )

    /**
     * Gera o PDF da Folha de Posicionamento.
     * @param fotos lista já ordenada (rosto, etiqueta, posicionamentos)
     */
    /**
     * Descrição completa de UMA simulação para o desenho. Existe para que a
     * mesma rotina sirva à ficha individual e ao **lote agrupado**: no lote,
     * várias simulações são desenhadas no MESMO PdfDocument, gerando um PDF
     * nativo (texto e vetores preservados) em vez de imagens rasterizadas.
     */
    data class ItemLote(
        val dados: DadosCabecalho,
        val fotos: List<File>,
        val rotulos: List<String>? = null,
        val landscape: Boolean = false,
        val etiquetaLarguraMm: Int = 60,
        val etiquetaAlturaMm: Int = 30,
        val observacoes: String = "",
        val timeOut: DadosTimeOut? = null,
        val margemImpressaoMm: Int = 15,
        /** Equipe do rubricario, ja agrupada por cargo e na ordem do servico.
         *  Vazia = a pagina nao e gerada. */
        val rubricario: List<Pair<String, List<com.radioterapia.ai.rubricario.RubricarioStore.Pessoa>>> = emptyList(),
        /** cargo -> sigla do registro (CRM, CNEN, CRT...), para o cabecalho. */
        val rubricarioRegistros: Map<String, String> = emptyMap(),
        val rubricarioRetrato: Boolean = true,
        /** Modelo em branco: cabecalho sem etiqueta e sem dados de cadastro. */
        val rubricarioSemPaciente: Boolean = false,
        /** Id do protocolo cujas paginas saem no fim. Vazio = ficha simples. */
        val protocoloId: String = ""
    )

    fun gerarFolhaPosicionamento(
        context: Context,
        dados: DadosCabecalho,
        fotos: List<File>,
        arquivoSaida: File,
        rotulos: List<String>? = null,
        landscape: Boolean = false,
        etiquetaLarguraMm: Int = 60,
        etiquetaAlturaMm: Int = 30,
        observacoes: String = "",
        timeOut: DadosTimeOut? = null,
        margemImpressaoMm: Int = 15,
        /** Protocolo desta simulação. Vazio = nenhuma página acrescentada. */
        protocoloId: String = ""
    ): File {
        // A equipe do rubricario e lida AQUI, e nao por quem chama, porque sao
        // varios os caminhos que geram esta ficha — nova simulacao, editar
        // simulacao, editar cadastro, foto no tratamento e exclusao de foto.
        // Ler num lugar so evita a pagina aparecer em uns e sumir em outros.
        val cfgRub = com.radioterapia.ai.AppConfig(context)

        // QUAL EQUIPE vai na folha: a do bloco que o PROTOCOLO escolhido aponta.
        //
        // O rubricário é a primeira página do protocolo, e um tablet que atende
        // duas clínicas tem um protocolo por clínica. Sem este roteamento, a
        // ficha saía com a equipe da outra unidade impressa — nomes de quem não
        // estava na sala, que é o oposto do que a folha existe para provar.
        //
        // SEM PROTOCOLO ESCOLHIDO cai no bloco padrão, e não em "nenhuma
        // equipe". Quando há mais de um protocolo a seleção começa vazia de
        // propósito, e amarrar o rubricário a ela faria a página sumir da ficha
        // de quem não escolheu — uma página inteira perdida em silêncio.
        val idBloco = try {
            if (protocoloId.isBlank())
                com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO
            else com.radioterapia.ai.protocolo.ProtocoloStore(context)
                .obter(protocoloId)?.rubricarioId
                ?: com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO
        } catch (_: Exception) {
            com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO
        }

        val equipe = if (!cfgRub.rubricarioAtivo) emptyList() else {
            val ordem = cfgRub.rubricarioCargos.split("\n").map { it.trim() }
                .filter { it.isNotBlank() }
                .map { com.radioterapia.ai.rubricario.RubricarioStore.separarCargo(it).first }
            com.radioterapia.ai.rubricario.RubricarioStore(context).porCargo(ordem, idBloco)
        }
        val registros = cfgRub.rubricarioCargos.split("\n").map { it.trim() }
            .filter { it.isNotBlank() }
            .associate { com.radioterapia.ai.rubricario.RubricarioStore.separarCargo(it) }

        val item = ItemLote(dados, fotos, rotulos, landscape,
            etiquetaLarguraMm, etiquetaAlturaMm, observacoes, timeOut, margemImpressaoMm,
            equipe, registros, cfgRub.rubricarioRetrato,
            protocoloId = protocoloId)
        val doc = PdfDocument()
        val logo = LogoManager(context).obterBitmap()
        try {
            val comVerso = desenharSimulacao(doc, context, item, logo)
            FileOutputStream(arquivoSaida).use { saida -> doc.writeTo(saida) }
            // Marca OU desmarca: a mesma ficha regerada com outro protocolo, sem
            // verso, deixa de forçar a borda longa na impressão.
            if (comVerso) marcarFrenteVerso(arquivoSaida, context)
            else desmarcarFrenteVerso(arquivoSaida, context)
            return arquivoSaida
        } finally {
            doc.close()
            if (logo != null && !logo.isRecycled) logo.recycle()
        }
    }

    /**
     * LOTE AGRUPADO — várias simulações num único PDF, desenhadas pela mesma
     * rotina da ficha individual. O resultado é um PDF de verdade: texto
     * pesquisável, vetores intactos, tamanho proporcional ao conteúdo. As
     * páginas são numeradas continuamente, para o operador conferir na
     * impressora se saiu tudo.
     *
     * Cada item traz a própria configuração (orientação, etiqueta, margem), de
     * modo que um lote com fichas configuradas de formas diferentes continua
     * correto — o PdfDocument aceita páginas de tamanhos distintos.
     *
     * UMA FOLHA NUNCA LEVA DOIS PACIENTES. Em frente-e-verso, um paciente com
     * número ímpar de páginas faria a primeira página do seguinte sair no verso
     * da última dele. Por isso, quando a impressão é frente-e-verso, cada
     * simulação começa em posição ímpar, com uma página em branco antes quando
     * preciso (ver [precisaPaginaSeparadora]). Em simplex nada muda.
     */
    fun gerarLoteAgrupado(context: Context, itens: List<ItemLote>, arquivoSaida: File): File? {
        if (itens.isEmpty()) return null
        val doc = PdfDocument()
        val logo = LogoManager(context).obterBitmap()
        var desenhadas = 0
        var algumVerso = false
        // Frente-e-verso quando a impressora está configurada assim, ou quando
        // algum protocolo do lote tem verso — que força a borda longa na
        // impressão por IP, qualquer que seja a configuração.
        val duplex = try {
            com.radioterapia.ai.AppConfig(context).printerDuplexMode != "simplex" ||
                itens.any { protocoloTemVerso(context, it.protocoloId) }
        } catch (_: Exception) { false }
        try {
            for (item in itens) {
                try {
                    if (precisaPaginaSeparadora(doc.pages.size, duplex))
                        acrescentarPaginaEmBranco(doc)
                    if (desenharSimulacao(doc, context, item, logo)) algumVerso = true
                    desenhadas++
                } catch (_: Exception) {
                    // Uma simulação com foto corrompida não derruba o lote inteiro.
                }
            }
            if (desenhadas == 0) return null
            FileOutputStream(arquivoSaida).use { saida -> doc.writeTo(saida) }
            if (algumVerso) marcarFrenteVerso(arquivoSaida, context)
            else desmarcarFrenteVerso(arquivoSaida, context)
            return arquivoSaida
        } catch (e: Exception) {
            return null
        } finally {
            doc.close()
            if (logo != null && !logo.isRecycled) logo.recycle()
        }
    }

    /**
     * Desenha UMA simulação (ficha de Time-Out + páginas de fotos) num
     * PdfDocument já aberto. Não cria nem fecha o documento — é o que permite
     * empilhar vários pacientes no mesmo arquivo.
     */
    /** @return `true` se a ficha desta simulacao tem folha frente-e-verso. */
    private fun desenharSimulacao(
        doc: PdfDocument, context: Context, item: ItemLote, logo: android.graphics.Bitmap?
    ): Boolean {
        var comVerso = false
        configurarOrientacao(item.landscape)
        carregarTextos(context)

        // ----- Configura etiqueta + observações DESTA simulação -----
        this.etiqLargPt = (item.etiquetaLarguraMm * MM_TO_PT)
        this.etiqAltPt = (item.etiquetaAlturaMm * MM_TO_PT)
        this.observacoesTexto = item.observacoes.trim()
        // Margem extra além da base (~10 mm). Config manda o total em mm.
        val extra = ((item.margemImpressaoMm - 10).coerceIn(0, 40)) * MM_TO_PT
        if (item.landscape) { margemExtraTopoPt = extra; margemExtraEsqPt = 0f }
        else { margemExtraEsqPt = extra; margemExtraTopoPt = 0f }

        headerHAtual = calcularHeaderH()
        // OBSERVAÇÕES: o corpo é escolhido UMA vez por simulação, olhando todas
        // as caixas em que o texto vai sair — a do Time-Out (quando há) e a
        // faixa das fotos, que é a mais estreita em retrato. Assim as duas
        // páginas saem sempre no mesmo corpo.
        //
        // A faixa tem a altura das linhas DESENHADAS, já quebradas pela
        // largura, e não das digitadas: uma linha digitada que quebra em duas
        // ganha a altura da segunda, em vez de desenhá-la por cima da borda.
        // A grade de fotos se encaixa no que sobra (ver desenharGridFotos).
        val medirObs = medidorObservacoes()
        val larguraFaixaObs = PAGE_WIDTH - MARGIN * 2 - OBS_ROTULO_W - 2 * OBS_PAD
        val largurasObs = (if (item.timeOut != null) listOf(LARGURA_TEXTO_OBS_TO)
            else emptyList()) + larguraFaixaObs
        obsCorpo = escolherCorpoObs(observacoesTexto, largurasObs, medirObs)
        val arranjoFotos = arranjarObservacoes(observacoesTexto, larguraFaixaObs, obsCorpo, medirObs)
        obsLinhasFotos = arranjoFotos.linhas
        obsBandHeight = alturaFaixaObsFotos(arranjoFotos)

        // ----- A etiqueta NÃO vai para o grid de fotos -----
        // Pedido dos técnicos: a folha impressa serve para conferir o setup, e a
        // etiqueta já aparece na página de Time-Out. Ela continua sendo
        // fotografada e GRAVADA na pasta do paciente (rastreabilidade) e segue
        // no carrossel do Tratamento — some só da impressão.
        //
        // O filtro mora aqui, e não em quem monta a lista, porque são quatro os
        // caminhos que geram esta folha (nova simulação, editar simulação,
        // editar cadastro e foto no tratamento). Filtrar na origem exigiria
        // acertar os quatro e lembrar do quinto que vier.
        val (fotos, rotulosGrid) = run {
            val pares = item.fotos.indices.map { i ->
                item.fotos[i] to (item.rotulos?.getOrNull(i) ?: rotuloPara(i))
            }.filterNot { (_, rot) -> ehEtiqueta(rot) }
            pares.map { it.first } to pares.map { it.second }
        }
        val dados = item.dados
        val totalPaginas = ((fotos.size - 1) / FOTOS_POR_PAGINA + 1).coerceAtLeast(1)

        // PRIMEIRA página desta simulação: TIME-OUT (quando configurado)
        if (item.timeOut != null) {
            try {
                desenharPaginaTimeOut(doc, context, dados, item.timeOut, logo,
                    item.observacoes, item.etiquetaLarguraMm, item.etiquetaAlturaMm, obsCorpo)
            } catch (_: Exception) { /* nunca bloqueia a folha de fotos */ }
        }

        for (pagina in 0 until totalPaginas) {
            val numeroPagina = doc.pages.size + 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, numeroPagina).create()
            val page = doc.startPage(info)
            val canvas = page.canvas
            // O recuo de furação vale para o conteúdo, não para o rodapé: ver
            // o restoreToCount antes de desenharRodape.
            val salvo = canvas.save()
            canvas.translate(margemExtraEsqPt, margemExtraTopoPt)

            desenharCabecalhoCompleto(canvas, dados, logo)

            if (obsBandHeight > 0f) {
                // ABAIXO da divisória, que agora fica em +FOLGA_DIVISORIA. Com
                // o valor antigo (+8) a caixa de observações passaria a nascer
                // por cima da régua.
                desenharBandaObservacoes(canvas, MARGIN + headerHAtual + FOLGA_DIVISORIA + 6f)
            }

            val inicio = pagina * FOTOS_POR_PAGINA
            val fim = (inicio + FOTOS_POR_PAGINA).coerceAtMost(fotos.size)
            val fotosPag = if (fotos.isEmpty()) emptyList() else fotos.subList(inicio, fim)
            desenharGridFotos(canvas, fotosPag, totalNaSimulacao = fotos.size,
                indiceInicial = inicio, rotulos = rotulosGrid, nomePaciente = dados.nomePaciente)

            // RODAPÉ NA FOLHA FÍSICA. Dentro do translate, na folha deitada o
            // recuo empurraria a base para fora do papel, e em pé o centro
            // andaria junto com o recuo.
            canvas.restoreToCount(salvo)
            desenharRodape(canvas, numeroPagina, dados.nomeClinica)

            doc.finishPage(page)
        }

        // ULTIMA pagina: RUBRICARIO. Depois das fotos de proposito — quem
        // manuseia a ficha procura a foto, nao a lista da equipe.
        if (item.rubricario.isNotEmpty()) {
            try { desenharPaginaRubricario(doc, context, dados, item, logo) }
            catch (_: Exception) { /* nunca bloqueia a ficha do paciente */ }
        }

        // PROTOCOLO: as paginas que o servico acrescenta, depois de tudo.
        //
        // DEPOIS DO RUBRICARIO de proposito. A ordem da ficha vai do que
        // identifica o paciente para o que o servico anexa: Time-Out, fotos,
        // quem assinou, e por ultimo os documentos proprios da instituicao.
        // Quem manuseia a ficha procura a foto, nao o termo de consentimento.
        if (item.protocoloId.isNotBlank()) {
            try {
                if (desenharProtocolo(doc, context, dados, item, logo)) comVerso = true
            } catch (_: Exception) { /* anexo do servico nunca derruba a ficha */ }
        }
        return comVerso
    }

    /**
     * Pagina de RUBRICARIO: a equipe do servico com cargo, registro e rubrica.
     *
     * E da CLINICA, nao do paciente: do paciente ela leva so a identificacao, na
     * linha "Nome:" do topo — como nos modelos em papel que o servico ja usa.
     *
     * DUAS COLUNAS quando a equipe nao cabe em uma. O criterio e a altura
     * necessaria, nao a contagem de pessoas: um servico com quatro cargos de
     * duas pessoas ocupa mais que um com um cargo de oito, porque cada cargo
     * gasta cabecalho proprio.
     */
    private fun desenharPaginaRubricario(
        doc: PdfDocument, context: Context, dados: DadosCabecalho,
        item: ItemLote, logo: android.graphics.Bitmap?
    ) {
        val retrato = item.rubricarioRetrato
        val larguraPag = if (retrato) A4_CURTO else A4_LONGO
        val alturaPag = if (retrato) A4_LONGO else A4_CURTO
        val info = PdfDocument.PageInfo.Builder(larguraPag, alturaPag, doc.pages.size + 1).create()
        val page = doc.startPage(info)
        val cv = page.canvas
        // MARGEM DE FURACAO, igual as demais paginas. Sem isto o rubricario era a
        // unica folha sem o recuo, e o furador comia a coluna do nome quando o
        // servico arquiva a ficha inteira na mesma pasta.
        //
        // O EIXO depende da orientacao DESTA pagina, nao da ficha de fotos: o
        // rubricario tem orientacao propria nas Configuracoes. Em retrato o
        // recuo vai a esquerda; em paisagem vai ao topo, que e onde a folha e
        // furada quando impressa deitada.
        val recuo = margemExtraEsqPt + margemExtraTopoPt   // so um deles e != 0
        // O rodape fica FORA do recuo: ver o restoreToCount antes dele.
        val salvoRub = cv.save()
        if (retrato) cv.translate(recuo, 0f) else cv.translate(0f, recuo)

        val mL = MARGIN
        val mR = larguraPag - MARGIN

        // CABECALHO IDENTICO AO DA FICHA DE FOTOS.
        //
        // Antes esta folha desenhava o proprio: logo num canto, titulo do outro,
        // e a identificacao do paciente reduzida a uma linha "Nome:". Grampeada
        // junto das demais, parecia vir de outro sistema — e a conferencia de
        // quem assinou perdia a etiqueta e os dados de cadastro que as outras
        // paginas trazem.
        //
        // PAGE_WIDTH e trocado porque o cabecalho se dimensiona por ele, e o
        // rubricario tem orientacao PROPRIA nas Configuracoes: pode ser retrato
        // enquanto a ficha de fotos e paisagem. Restaurado no fim para nao
        // contaminar quem desenhar depois.
        val pwSalvo = PAGE_WIDTH
        val phSalvo = PAGE_HEIGHT
        PAGE_WIDTH = larguraPag
        PAGE_HEIGHT = alturaPag
        headerHAtual = calcularHeaderH()
        // TITULO EM UMA LINHA SO: "RUBRICARIO". O subtitulo "RADIOTERAPIA" saiu
        // — a folha ja esta dentro da ficha de radioterapia, e a segunda linha
        // so repetia o contexto.
        desenharCabecalhoCompleto(cv, dados, logo, txtRubTitulo, "",
            item.rubricarioSemPaciente)
        var y = MARGIN + headerHAtual + FOLGA_DIVISORIA + 16f

        val pSub = Paint().apply {
            color = Color.parseColor("#555555"); textSize = 11f
            isFakeBoldText = true; isAntiAlias = true
        }
        val pLinha = Paint().apply {
            color = Color.parseColor("#333333"); textSize = 10f; isAntiAlias = true
        }
        val borda = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 0.8f
            color = Color.parseColor("#666666"); isAntiAlias = true
        }
        val fundoCab = Paint().apply {
            style = Paint.Style.FILL; color = Color.parseColor("#E8EAED")
        }

        // ---- monta os grupos ----
        val store = com.radioterapia.ai.rubricario.RubricarioStore(context)
        val grupos = item.rubricario

        val alturaCab = 15f
        val alturaLinha = 26f
        val alturaGrupo = { n: Int -> alturaCab + n * alturaLinha + 8f }
        val alturaTotal = grupos.sumOf { alturaGrupo(it.second.size).toDouble() }.toFloat()
        val disponivel = alturaPag - y - MARGIN - 18f
        val duasColunas = alturaTotal > disponivel

        val larguraCol = if (duasColunas) (mR - mL - 14f) / 2f else (mR - mL)
        var xCol = mL
        var yCol = y
        var colunaAtual = 0

        grupos.forEach { (cargo, pessoas) ->
            val h = alturaGrupo(pessoas.size)
            // Quebra para a segunda coluna quando a primeira encheu.
            if (duasColunas && colunaAtual == 0 && yCol + h > alturaPag - MARGIN - 18f) {
                colunaAtual = 1; xCol = mL + larguraCol + 14f; yCol = y
            }
            val registroDoCargo = item.rubricarioRegistros[cargo].orEmpty()

            // cabecalho do cargo
            // A RUBRICA ocupa a fatia menor da linha.
            //
            // Ficava com 30% e sobrava espaco vazio em volta de quase toda
            // assinatura, porque a rubrica e normalizada pela ALTURA da celula —
            // largura extra nao faz a rubrica crescer, so afasta o traco das
            // bordas. O que faltava era espaco para nome e registro, que sao o
            // que se le quando alguem confere a folha.
            val colNome = xCol
            val colReg = xCol + larguraCol * 0.56f
            val colRub = xCol + larguraCol * 0.78f
            cv.drawRect(xCol, yCol, xCol + larguraCol, yCol + alturaCab, fundoCab)
            cv.drawRect(xCol, yCol, xCol + larguraCol, yCol + alturaCab, borda)
            val pCab = Paint().apply {
                color = Color.BLACK; textSize = 9f
                isFakeBoldText = true; isAntiAlias = true
            }
            cv.drawText(cargo, colNome + 4f, yCol + 10.5f, pCab)
            if (registroDoCargo.isNotBlank())
                cv.drawText(registroDoCargo, colReg + 4f, yCol + 10.5f, pCab)
            cv.drawText(txtRubColRubrica, colRub + 4f, yCol + 10.5f, pCab)
            yCol += alturaCab

            pessoas.forEach { p ->
                val topo = yCol
                val base = yCol + alturaLinha
                cv.drawRect(xCol, topo, xCol + larguraCol, base, borda)
                cv.drawLine(colReg, topo, colReg, base, borda)
                cv.drawLine(colRub, topo, colRub, base, borda)

                // Nome encolhe se nao couber: cortar seria pior que reduzir.
                val pN = Paint(pLinha)
                var t = 10f
                while (t > 6.5f && pN.measureText(p.nome) > (colReg - colNome - 8f)) {
                    t -= 0.5f; pN.textSize = t
                }
                cv.drawText(p.nome, colNome + 4f, topo + alturaLinha / 2 + 3.5f, pN)
                if (p.registro.isNotBlank())
                    cv.drawText(p.registro, colReg + 4f, topo + alturaLinha / 2 + 3.5f, pLinha)

                // RUBRICA: desenhada dentro da celula, mantendo a proporcao. O
                // PNG vem recortado no traco e com fundo transparente, entao nao
                // apaga a linha da grade por baixo.
                store.bitmapAssinatura(p)?.let { bmp ->
                    try {
                        val cw = (xCol + larguraCol) - colRub - 8f
                        val ch = alturaLinha - 6f
                        // NORMALIZA PELA ALTURA, nao "encaixa dentro".
                        //
                        // Com minOf(cw/w, ch/h) cada rubrica ficava de um
                        // tamanho: quem assina compacto gerava um recorte alto e
                        // estreito, que enchia a altura e parecia grande; quem
                        // assina espalhado gerava um recorte largo e baixo, que
                        // batia na largura e saia achatado. Lado a lado na mesma
                        // folha, a diferenca era o que mais chamava atencao.
                        //
                        // Fixando a ALTURA, todas passam a ter o mesmo "corpo",
                        // que e o que o olho le como uniforme. A largura so
                        // manda quando a rubrica e larga demais para a celula —
                        // ai encolhe, porque transbordar seria pior.
                        val alvoH = ch * ALTURA_RUBRICA
                        var esc = alvoH / bmp.height
                        if (bmp.width * esc > cw) esc = cw / bmp.width
                        val w = bmp.width * esc
                        val h2 = bmp.height * esc
                        val cx = colRub + 4f + (cw - w) / 2f
                        val cy = topo + 3f + (ch - h2) / 2f
                        cv.drawBitmap(bmp, null, RectF(cx, cy, cx + w, cy + h2), null)
                    } catch (_: Exception) {}
                }
                yCol = base
            }
            yCol += 8f
        }

        cv.restoreToCount(salvoRub)
        desenharRodape(cv, doc.pages.size + 1, dados.nomeClinica)
        doc.finishPage(page)
        PAGE_WIDTH = pwSalvo
        PAGE_HEIGHT = phSalvo
    }

    /**
     * Paginas do protocolo, no fim da ficha.
     *
     * A ETIQUETA E O RODAPE SAO OS DESTA CLASSE, passados ao renderizador como
     * funcoes. A etiqueta do protocolo e desenhada pela MESMA rotina das
     * primeiras paginas (com fundo branco, porque cai sobre o documento da
     * clinica), e o rodape tem o mesmo formato e a mesma numeracao de toda
     * pagina da ficha. Uma rotina paralela no renderizador divergiria da
     * original a cada ajuste feito so numa delas.
     *
     * @return `true` se alguma folha do protocolo tem verso e alguma pagina
     *   entrou na ficha.
     */
    private fun desenharProtocolo(
        doc: PdfDocument, context: Context, dados: DadosCabecalho,
        item: ItemLote, logo: android.graphics.Bitmap?
    ): Boolean {
        val store = com.radioterapia.ai.protocolo.ProtocoloStore(context)
        val prot = store.obter(item.protocoloId) ?: return false
        if (prot.paginas.isEmpty()) return false

        val temVerso = protocoloTemVerso(store, prot)
        val acrescentadas = com.radioterapia.ai.protocolo.ProtocoloRenderer.desenhar(
            doc, context, prot, logo,
            desenharEtiqueta = { cv, r -> desenharEtiquetaVirtual(cv, r, dados, fundoBranco = true) },
            rodape = { cv, n, w, h -> desenharRodape(cv, n, dados.nomeClinica, w, h) })
        return temVerso && acrescentadas > 0
    }

    /** O protocolo [id] tem alguma folha com verso? Vazio ou ilegivel = nao. */
    private fun protocoloTemVerso(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        return try {
            val store = com.radioterapia.ai.protocolo.ProtocoloStore(context)
            val prot = store.obter(id) ?: return false
            protocoloTemVerso(store, prot)
        } catch (_: Exception) { false }
    }

    private fun protocoloTemVerso(
        store: com.radioterapia.ai.protocolo.ProtocoloStore,
        prot: com.radioterapia.ai.protocolo.ProtocoloStore.Protocolo
    ): Boolean = prot.paginas.any {
        store.arquivoPagina(prot, it) != null && store.arquivoVerso(prot, it) != null
    }

    /**
     * Separador do lote: a proxima simulacao precisa de uma pagina em branco
     * antes dela? So em frente-e-verso, e so quando o documento tem numero
     * impar de paginas — senao a primeira pagina do paciente seguinte cairia no
     * verso da ultima do anterior.
     */
    internal fun precisaPaginaSeparadora(paginasNoDocumento: Int, duplex: Boolean): Boolean =
        duplex && paginasNoDocumento % 2 == 1

    /** Pagina A4 retrato em branco, sem rodape: e efeito da impressao, nao conteudo. */
    private fun acrescentarPaginaEmBranco(doc: PdfDocument) {
        val pagina = doc.startPage(
            PdfDocument.PageInfo.Builder(A4_CURTO, A4_LONGO, doc.pages.size + 1).create())
        pagina.canvas.drawColor(Color.WHITE)
        doc.finishPage(pagina)
    }

    /**
     * RUBRICARIO AVULSO: uma folha so, sem paciente.
     *
     * A folha do servico e afixada no acelerador e reimpressa quando alguem
     * entra ou sai da equipe — nesse momento nao ha paciente nenhum envolvido, e
     * gerar uma ficha inteira so para extrair a ultima pagina seria absurdo.
     *
     * Sai como MODELO EM BRANCO: cabecalho nas mesmas posicoes da ficha do
     * paciente, mas sem a moldura tracejada da etiqueta e sem os rotulos de
     * cadastro. Uma folha vazia com campos de paciente desenhados convidaria
     * alguem a preenche-los a mao, e nao e disso que ela trata.
     */
    fun gerarRubricarioAvulso(
        context: Context, arquivoSaida: File,
        /** Qual equipe imprimir. Vem do bloco selecionado nas Configurações. */
        blocoId: String = com.radioterapia.ai.rubricario.RubricarioStore.ID_PADRAO
    ): File? {
        val cfg = com.radioterapia.ai.AppConfig(context)
        val ordem = cfg.rubricarioCargos.split("\n").map { it.trim() }
            .filter { it.isNotBlank() }
            .map { com.radioterapia.ai.rubricario.RubricarioStore.separarCargo(it).first }
        val equipe = com.radioterapia.ai.rubricario.RubricarioStore(context)
            .porCargo(ordem, blocoId)
        if (equipe.isEmpty()) return null

        val registros = cfg.rubricarioCargos.split("\n").map { it.trim() }
            .filter { it.isNotBlank() }
            .associate { com.radioterapia.ai.rubricario.RubricarioStore.separarCargo(it) }

        carregarTextos(context)
        // Margem de furacao tambem aqui: a folha vai para a mesma pasta.
        val extra = ((cfg.pdfMargemMm - 10).coerceIn(0, 40)) * MM_TO_PT
        if (cfg.rubricarioRetrato) { margemExtraEsqPt = extra; margemExtraTopoPt = 0f }
        else { margemExtraTopoPt = extra; margemExtraEsqPt = 0f }

        val doc = PdfDocument()
        val logo = LogoManager(context).obterBitmap()
        return try {
            val dados = DadosCabecalho(
                nomePaciente = "", nascimento = "", prontuario = "",
                idsExtras = emptyList(), dataSimulacao = java.util.Date(),
                numeroSimulacao = 1, nomeClinica = cfg.nomeClinica)
            // MODELO EM BRANCO: mesmo cabecalho das demais folhas, mesmas
            // posicoes, mas sem a moldura tracejada da etiqueta e sem os
            // rotulos de cadastro. Serve para conferir como a folha vai sair e
            // para afixar no acelerador, onde nao ha paciente nenhum.
            etiqLargPt = 60f * MM_TO_PT
            etiqAltPt = 30f * MM_TO_PT
            val item = ItemLote(dados, emptyList(), null, false,
                60, 30, "", null, cfg.pdfMargemMm,
                equipe, registros, cfg.rubricarioRetrato, rubricarioSemPaciente = true)
            desenharPaginaRubricario(doc, context, dados, item, logo)
            FileOutputStream(arquivoSaida).use { doc.writeTo(it) }
            arquivoSaida
        } catch (_: Exception) {
            null
        } finally {
            doc.close()
            if (logo != null && !logo.isRecycled) logo.recycle()
        }
    }

    fun gerarFichaAcessorios(
        context: Context,
        dados: DadosCabecalho,
        fotoAcessorios: File,
        arquivoSaida: File
    ): File {
        val logoManager = LogoManager(context)
        val logo = logoManager.obterBitmap()

        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = doc.startPage(info)
            val canvas = page.canvas

            desenharCabecalhoSimplificado(canvas, dados, logo)
            desenharFotoGrande(canvas, fotoAcessorios)
            desenharRodape(canvas, 1, dados.nomeClinica)

            doc.finishPage(page)
            FileOutputStream(arquivoSaida).use { saida -> doc.writeTo(saida) }
            return arquivoSaida
        } finally {
            doc.close()
            if (logo != null && !logo.isRecycled) logo.recycle()
        }
    }

    // =================== CABEÇALHOS ===================

    /**
     * Cabeçalho completo (folha de posicionamento): 3 colunas
     *   Esquerda: espaço pra colar a etiqueta física (caixa pontilhada)
     *   Centro:   dados do paciente (nome, nascimento, prontuário, IDs extras, data, nova sim.)
     *   Direita:  logo + título "FOTOS DE POSICIONAMENTO"
     */
    /** Dados da página TIME-OUT (rotina de procedimento seguro). */
    data class DadosTimeOut(
        val medicoResponsavel: String,
        val sitioTratamento: String,
        val riscoQueda: Boolean,
        val precaucaoContato: Boolean,
        val fotoRosto: File? = null,
        val equipamento: String = "",
        val alergia: String = "",  // "SIM" | "NAO" | "" (não informado)
        /** Ultima fracao prevista (1..40). 0 = nao informado, tabela sem realce. */
        val fracoesMax: Int = 0
    )

    /** Página TIME-OUT — desenhada 1:1 sobre a geometria do modelo oficial
     *  (Letter 612x792 pt): etiqueta virtual + foto do rosto, médico/risco/
     *  precaução, sítio, rotina, grade de 40 frações e observações no rodapé. */
    /** Página TIME-OUT — A4 retrato (595x842), mesmas margens da folha de fotos.
     *  Cabeçalho em sinergia com a ficha de fotos: logo topo-direita (40pt),
     *  etiqueta virtual TRACEJADA de cantos arredondados (mesmas dimensões da
     *  área física) alinhada ao topo da foto do rosto (sem bordas pretas).
     *  Equipamento + Médico responsável; alertas como TAGS flutuantes em
     *  gradiente (QUEDA amarela, ALERGIA vermelha, CONTATO laranja). */
    /** Página TIME-OUT v3 — A4 retrato, margens da folha de fotos. Alertas no
     *  CABEÇALHO (entre a margem esquerda e o logo). Coluna esquerda: etiqueta
     *  física (tracejada vazia + IDs compactos fora) OU etiqueta virtual
     *  centralizada. Foto do rosto sem borda, alinhada ao bloco 21-40.
     *  Equipamento+Médico até o fim do bloco 1; sítio a partir do bloco 2.
     *  Células das frações em cinza claro; observações centralizadas; rodapé. */
    private fun desenharPaginaTimeOut(
        doc: PdfDocument,
        context: Context,
        dados: DadosCabecalho,
        t: DadosTimeOut,
        logo: Bitmap?,
        observacoes: String,
        etiquetaLarguraMm: Int,
        etiquetaAlturaMm: Int,
        /** Corpo das observações, escolhido em desenharSimulacao para as duas páginas. */
        corpoObs: Float
    ) {
        val pw = 595; val ph = 842
        val numeroPagina = doc.pages.size + 1
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, numeroPagina).create())
        val cv = page.canvas
        // O RECUO VAI SEMPRE À ESQUERDA: esta página é retrato em qualquer
        // configuração, como o rubricário em retrato. No eixo Y, que é o da
        // ficha deitada, ele empurraria o pé da página para fora do papel. O
        // rodapé fica fora do recuo (ver o restoreToCount antes dele).
        val salvoTO = cv.save()
        cv.translate(margemExtraEsqPt + margemExtraTopoPt, 0f)
        val mL = MARGIN; val mR = pw - MARGIN
        val cinzaMedio = 0xFFD9D9D9.toInt()
        val cinzaClaro = 0xFFE8E8E8.toInt()
        val cinzaCelula = Color.parseColor("#CCEEEEEE")
        val borda = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFFBBBBBB.toInt() }
        val fundo = Paint().apply { style = Paint.Style.FILL }
        val txt = Paint().apply { isAntiAlias = true; color = Color.BLACK }
        fun texto(s: String, x: Float, y: Float, size: Float, bold: Boolean = false,
                  align: Paint.Align = Paint.Align.LEFT, cor: Int = Color.BLACK) {
            txt.textSize = size; txt.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            txt.textAlign = align; txt.color = cor
            cv.drawText(s, x, y, txt)
        }
        fun checkbox(cx: Float, cy: Float, lado: Float, marcado: Boolean) {
            cv.drawRect(cx - lado / 2, cy - lado / 2, cx + lado / 2, cy + lado / 2, borda)
            if (marcado) {
                val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.4f; color = Color.BLACK; isAntiAlias = true }
                cv.drawLine(cx - lado * 0.32f, cy, cx - lado * 0.08f, cy + lado * 0.30f, p)
                cv.drawLine(cx - lado * 0.08f, cy + lado * 0.30f, cx + lado * 0.36f, cy - lado * 0.34f, p)
            }
        }

        // Geometria da tabela definida ANTES (alinha topo e boxes)
        val blocoW = ((mR - mL) - 8f) / 2f
        val col2L = mL + blocoW + 8f            // borda esq. do bloco 21-40

        // ===== Cabeçalho: TAGS de alerta (esq/centro) + logo 40pt (dir) =====
        val logoH = LOGO_ALTURA
        val logoL = desenharLogoPadrao(cv, logo, mR.toFloat(), MARGIN)
        run {
            data class Tag(val texto: String, val c1: Int, val c2: Int, val corTxt: Int)
            val ativos = mutableListOf<Tag>()
            if (t.precaucaoContato) ativos.add(Tag(txtToPrecaucao, 0xFFFFB74D.toInt(), 0xFFF57C00.toInt(), Color.WHITE))
            if (t.alergia == "SIM") ativos.add(Tag(txtToAlergia, 0xFFEF5350.toInt(), 0xFFC62828.toInt(), Color.WHITE))
            if (t.riscoQueda) ativos.add(Tag(txtToRiscoQueda, 0xFFFFE082.toInt(), 0xFFFFC107.toInt(), Color.BLACK))
            if (ativos.isNotEmpty()) {
                val areaL = mL.toFloat()
                val areaR = (logoL - 12f).coerceAtLeast(areaL + 120f)
                val areaLivre = areaR - areaL
                val tagH = 20f
                var tagW = 168f
                var gap: Float
                var inicioX: Float
                if (ativos.size == 3) {
                    gap = (areaLivre - 3 * tagW) / 2f
                    if (gap < 5f) {
                        val fator = (areaLivre - 10f) / (3f * tagW)
                        tagW *= fator; gap = 5f
                    }
                    inicioX = areaL
                } else {
                    gap = 20f
                    val total = ativos.size * tagW + (ativos.size - 1) * gap
                    inicioX = ((areaL + areaR) / 2f - total / 2f).coerceAtLeast(areaL)
                }
                val tagTop = MARGIN + (logoH - tagH) / 2f
                ativos.forEachIndexed { idx, tag ->
                    val l = inicioX + idx * (tagW + gap)
                    val r = RectF(l, tagTop, l + tagW, tagTop + tagH)
                    val pf = Paint().apply {
                        isAntiAlias = true; style = Paint.Style.FILL
                        shader = android.graphics.LinearGradient(r.left, r.top, r.left, r.bottom,
                            tag.c1, tag.c2, android.graphics.Shader.TileMode.CLAMP)
                    }
                    cv.drawRoundRect(r, tagH / 2f, tagH / 2f, pf)
                    var tf = 10f
                    txt.textSize = tf; txt.typeface = Typeface.DEFAULT_BOLD
                    while (txt.measureText(tag.texto) > tagW - 12f && tf > 6.5f) { tf -= 0.5f; txt.textSize = tf }
                    texto(tag.texto, r.centerX(), r.centerY() + tf / 2.8f, tf, bold = true,
                        align = Paint.Align.CENTER, cor = tag.corTxt)
                }
            }
        }

        // ===== Topo: coluna esquerda (etiqueta) + foto do rosto (direita) =====
        val topoConteudo = MARGIN + logoH + 10f
        val alturaTopo = 150f
        // Mesma régua da decisão de layout (TO_BLOCO_W): antes o limite era
        // blocoW, e caixa e decisão discordavam em etiquetas largas.
        // O QUADRO DA ETIQUETA, UM SO em qualquer configuracao.
        //
        // A escolha "fisica ou virtual" saiu. O quadro agora traz SEMPRE os
        // dados do paciente dentro: quem usa etiqueta de papel cola por cima, e
        // e para esse caso que existe a linha logo abaixo, repetindo o que a
        // colagem encobre.
        //
        // A altura e limitada ao que a faixa de topo comporta MENOS a linha, de
        // modo que o quadro nunca mais empurre a identificacao por cima do box
        // de equipamento.
        val etqW = larguraCaixaEtiqueta(etiquetaLarguraMm * MM_TO_PT)
        val etqH = alturaCaixaEtiqueta(etiquetaAlturaMm * MM_TO_PT)
        // BASE COMUM ao quadro e a foto do rosto. A linha corre sob os dois, e
        // ancora-la no fim da faixa — em vez de na base do quadro — e o que faz
        // a ficha sair igual com etiqueta de 30 ou de 70 mm.
        val baseBloco = topoConteudo + alturaTopo - ALTURA_LINHA_IDS
        desenharEtiquetaVirtual(cv,
            RectF(mL, topoConteudo, mL + etqW, topoConteudo + etqH), dados)
        desenharLinhaIdentificacao(cv, mL, baseBloco + 12f, (mR - mL).toFloat(), dados)

        // Foto do rosto: SEM borda; esquerda alinhada ao bloco 21-40 (máximo);
        // encolhe se a etiqueta invadir; corte lateral central de até 25%.
        val rosto = try { t.fotoRosto?.let { BitmapFactory.decodeFile(it.absolutePath) } } catch (_: Exception) { null }
        if (rosto != null) {
            // A FOTO COMECA NA BORDA DO BLOCO 21-40, sempre.
            //
            // Antes ela recuava quando as identificacoes ficavam ao LADO da
            // etiqueta — o arranjo escolhido justamente com etiqueta pequena, e
            // portanto o caso em que a foto do rosto mais encolhia sem motivo
            // aparente. As identificacoes sairam de la: a coluna direita inteira
            // e da foto.
            val fotoL = col2L
            // MESMA BASE DO QUADRO: a linha de identificacao corre sob os dois, e
            // qualquer degrau entre eles apareceria bem em cima dela.
            val dest = RectF(fotoL, topoConteudo, mR.toFloat(), baseBloco)
            val escH = dest.height() / rosto.height
            val wProj = rosto.width * escH
            if (wProj >= dest.width()) {
                val visW = dest.width() / escH
                val cropFrac = (rosto.width - visW) / rosto.width
                if (cropFrac <= 0.25f) {
                    val x0 = ((rosto.width - visW) / 2f).toInt()
                    drawBitmapArredondado(cv, rosto,
                        android.graphics.Rect(x0, 0, (x0 + visW).toInt(), rosto.height),
                        dest, paintFotoImpressao)
                } else {
                    val visW2 = rosto.width * 0.75f
                    val x0 = ((rosto.width - visW2) / 2f).toInt()
                    val src = android.graphics.Rect(x0, 0, (x0 + visW2).toInt(), rosto.height)
                    val esc2 = minOf(dest.width() / visW2, dest.height() / rosto.height)
                    val w = visW2 * esc2; val h = rosto.height * esc2
                    drawBitmapArredondado(cv, rosto, src,
                        RectF(dest.centerX() - w / 2, dest.centerY() - h / 2,
                              dest.centerX() + w / 2, dest.centerY() + h / 2), paintFotoImpressao)
                }
            } else {
                drawBitmapArredondado(cv, rosto, null,
                    RectF(dest.centerX() - wProj / 2, dest.top,
                          dest.centerX() + wProj / 2, dest.bottom), paintFotoImpressao)
            }
            rosto.recycle()
        }

        // ===== Linha 2: Equipamento + Médico (até o fim do bloco 1) | Sítio (bloco 2) =====
        val l2Top = topoConteudo + alturaTopo + 10f
        val l2Bot = l2Top + 32f
        val boxEsqR = mL + blocoW
        val labelR = mL + 92f
        fundo.color = cinzaMedio
        cv.drawRect(mL, l2Top, labelR, l2Bot, fundo)
        cv.drawRect(mL, l2Top, boxEsqR, l2Bot, borda)
        cv.drawLine(labelR, l2Top, labelR, l2Bot, borda)
        cv.drawLine(mL, l2Top + 16f, boxEsqR, l2Top + 16f, borda)
        texto(txtToEquipamento, labelR - 4f, l2Top + 11f, 6.5f, bold = true, align = Paint.Align.RIGHT)
        texto(txtToMedico, labelR - 4f, l2Top + 27f, 6.5f, bold = true, align = Paint.Align.RIGHT)
        val cxVal = (labelR + boxEsqR) / 2f
        texto(t.equipamento, cxVal, l2Top + 12f, 9.5f, bold = true, align = Paint.Align.CENTER)
        texto(t.medicoResponsavel, cxVal, l2Top + 28f, 9.5f, bold = true, align = Paint.Align.CENTER)

        cv.drawRect(col2L, l2Top, mR.toFloat(), l2Bot, borda)
        fundo.color = cinzaMedio; cv.drawRect(col2L, l2Top, mR.toFloat(), l2Top + 14f, fundo)
        cv.drawRect(col2L, l2Top, mR.toFloat(), l2Top + 14f, borda)
        texto(txtToSitio, (col2L + mR) / 2f, l2Top + 10f, 7.5f, bold = true, align = Paint.Align.CENTER)
        run {
            var tam = 12f
            txt.textSize = tam; txt.typeface = Typeface.DEFAULT_BOLD
            val alvo = t.sitioTratamento.uppercase()
            while (txt.measureText(alvo) > (mR - col2L - 10f) && tam > 7f) { tam -= 0.5f; txt.textSize = tam }
            texto(alvo, (col2L + mR) / 2f, l2Bot - 5f, tam, bold = true, align = Paint.Align.CENTER)
        }

        // ===== Faixa TIME-OUT + rotina segura =====
        val bandTop = l2Bot + 8f
        val bandBot = bandTop + 34f
        fundo.color = cinzaClaro; cv.drawRect(mL, bandTop, mR.toFloat(), bandBot, fundo)
        cv.drawRect(mL, bandTop, mR.toFloat(), bandBot, borda)
        texto("TIME-OUT", mL + 6f, bandTop + 23f, 19f, bold = true)
        val rotX = mL + 148f
        texto(txtToRotina, rotX, bandTop + 8f, 6.5f, bold = true)
        texto(txtToPasso1, rotX + 2f, bandTop + 16f, 6.2f)
        texto(txtToPasso2, rotX + 2f, bandTop + 24f, 6.2f)
        texto(txtToPasso3, rotX + 2f, bandTop + 32f, 6.2f)

        // ===== Grade de 40 frações =====
        val topoTab = bandBot + 8f

        /*
            CABEÇALHO DE 32 pt, e não mais 22.

            A folga sempre esteve aqui e ninguém tinha medido: a tabela ocupa de
            320 a 742 pt, e alargar o cabeçalho em 10 pt tira 0,5 pt de cada uma
            das 20 linhas — de 7,06 para 6,88 mm de altura, que ninguém percebe
            escrevendo à mão. O cabeçalho, em troca, quase dobra de corpo.
         */
        val alturaCab = 32f
        val headerB = topoTab + alturaCab
        // A CAIXA DE OBSERVAÇÕES CRESCE PARA CIMA. A base fica presa no pé da
        // página porque embaixo dela só há 16 pt até a linha de base do rodapé;
        // crescendo para baixo, quatro linhas já cobririam o rodapé. A tabela
        // encolhe sozinha, porque a altura da linha sai do que sobra até fimTab.
        val fimObs = ph - MARGIN
        val pObs = Paint().apply {
            isAntiAlias = true; color = Color.BLACK; typeface = Typeface.DEFAULT
            textSize = corpoObs; textAlign = Paint.Align.CENTER
        }
        val arranjoObs = arranjarObservacoes(observacoes, LARGURA_TEXTO_OBS_TO, corpoObs) { s, c ->
            pObs.textSize = c; pObs.measureText(s)
        }
        pObs.textSize = arranjoObs.corpo
        val obsTop = fimObs - alturaCaixaObsTimeOut(arranjoObs.alturaTexto)
        val fimTab = obsTop - 6f
        val alturaLinha = (fimTab - headerB) / 20f
        val prop = floatArrayOf(0f, 22.5f, 48f, 73.5f, 98.5f, 124f, 175f, 213.5f, 252f)
        val fator = blocoW / 252f
        fun cols(base: Float) = FloatArray(prop.size) { base + prop[it] * fator }

        /*
            OS RÓTULOS, e o corpo em que eles cabem.

            O TAMANHO É MEDIDO, NÃO FIXADO. Em português tudo cabe em 7,4 pt, e
            é esse o alvo — é a língua em que a ficha é preenchida. Mas
            "Nasc." tem outro comprimento em alemão, e rótulo que estoura a
            célula sai por cima da grade, em silêncio, numa folha que ninguém
            confere antes de imprimir.

            A alternativa seria fixar o corpo que serve ao pior idioma, e aí o
            português pagaria por uma palavra polonesa. Aqui cada idioma fica no
            maior corpo que couber no SEU texto: mede-se uma vez, por página.

            O PISO É 4,2 pt — o tamanho de hoje. Abaixo disso a folha estaria
            pior do que estava, e a redução deixa de ser aceitável: é sinal de
            que o rótulo daquele idioma precisa ser encurtado na tradução.
         */
        val colunasCabecalho = listOf(
            listOf(txtToColNum),
            listOf(txtToColNome1, txtToColNome2, txtToColNome3),
            listOf(txtToColFoto),
            listOf(txtToColSitio1, txtToColSitio2),
            listOf(txtToColFracao, txtToColFrac2, txtToColFrac3),
            listOf(txtToColQueixa1, txtToColQueixa2),
            listOf(txtToColAssinatura, "$txtToColTecnico 1"),
            listOf(txtToColAssinatura, "$txtToColTecnico 2"))

        val tamCabecalho = run {
            val larguras = cols(0f)
            val regua = Paint().apply {
                isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD; textSize = 10f
            }
            var tam = 7.4f
            colunasCabecalho.forEachIndexed { ci, linhas ->
                // 1,2 pt de folga de cada lado: o texto nao deve encostar na grade.
                val disponivel = (larguras[ci + 1] - larguras[ci]) - 2.4f
                linhas.forEach { rotulo ->
                    val larg10 = regua.measureText(rotulo)
                    if (larg10 > 0f) tam = minOf(tam, disponivel * 10f / larg10)
                }
            }
            maxOf(tam, 4.2f)
        }
        fun desenharBloco(colsArr: FloatArray, numIni: Int) {
            val l = colsArr.first(); val r = colsArr.last()
            fundo.color = cinzaMedio; cv.drawRect(l, topoTab, r, headerB, fundo)
            // células da coluna Fração: cinza claro semitransparente (mesmo das tags de foto)
            fundo.color = cinzaCelula
            cv.drawRect(colsArr[0], headerB, colsArr[1], fimTab, fundo)

            // ÚLTIMA FRAÇÃO: a linha inteira, da coluna da fração até a
            // assinatura do técnico 2, ganha fundo vermelho claro.
            //
            // POR QUE A FOLHA INTEIRA E NÃO SÓ O NÚMERO: quem preenche a tabela
            // no acelerador percorre a LINHA, não a coluna. Marcar só o número
            // exigiria olhar para a esquerda a cada dia para saber se aquela é
            // a última; a faixa colorida aparece no campo de visão de quem já
            // está com a caneta na linha.
            //
            // Pintado ANTES das bordas e do conteúdo: o retângulo cobriria a
            // grade se viesse depois, e a tabela perderia as divisões
            // justamente na linha que precisa ser lida com atenção.
            if (t.fracoesMax in numIni until (numIni + 20)) {
                val idx = t.fracoesMax - numIni
                fundo.color = VERMELHO_CLARO
                cv.drawRect(l, headerB + idx * alturaLinha,
                            r, headerB + (idx + 1) * alturaLinha, fundo)
            }
            cv.drawRect(l, topoTab, r, fimTab, borda)
            colsArr.forEach { x -> cv.drawLine(x, topoTab, x, fimTab, borda) }
            for (i2 in 0..20) {
                val y = headerB + i2 * alturaLinha
                cv.drawLine(l, y, r, y, borda)
            }
            fun hc(ci: Int) = (colsArr[ci] + colsArr[ci + 1]) / 2
            fun cab(ci: Int, linhas: List<String>) {
                val entrelinha = tamCabecalho * 1.15f
                var y = topoTab + (alturaCab - (linhas.size - 1) * entrelinha) / 2f +
                        tamCabecalho * 0.36f
                linhas.forEach { txtLinha ->
                    texto(txtLinha, hc(ci), y, tamCabecalho, bold = true,
                          align = Paint.Align.CENTER)
                    y += entrelinha
                }
            }
            colunasCabecalho.forEachIndexed { ci, linhas -> cab(ci, linhas) }
            for (i2 in 0 until 20) {
                val yT = headerB + i2 * alturaLinha
                val yC = yT + alturaLinha / 2
                val ehUltima = (numIni + i2) == t.fracoesMax
                texto("" + (numIni + i2), hc(0), yC + 3f, 8.5f, bold = true,
                      align = Paint.Align.CENTER,
                      cor = if (ehUltima) VERMELHO_ESCURO else Color.BLACK)
                // Quadrado PROPORCIONAL a celula, nao mais fixo em 6,5 pt.
                // Com o valor fixo eles ficavam minusculos e perdidos no meio
                // de celulas largas — quem preenche a mao precisa de area para
                // marcar. O 0,60 da largura e 0,66 da altura deixam folga para
                // a borda sem encostar. O sim/nao continua 5,2: sao DOIS
                // quadrados na mesma celula, e crescer faria eles se tocarem.
                for (ci in 1..4) {
                    val ladoCel = minOf(
                        (colsArr[ci + 1] - colsArr[ci]) * 0.60f,
                        alturaLinha * 0.66f)
                    checkbox(hc(ci), yC, ladoCel, false)
                }
                val simX = colsArr[5] + (colsArr[6] - colsArr[5]) * 0.28f
                val naoX = colsArr[5] + (colsArr[6] - colsArr[5]) * 0.72f
                // CAIXA DE 10 pt, e nao mais 5,2 — de 1,83 para 3,53 mm.
                //
                // O aperto nunca foi horizontal: esta e a coluna MAIS LARGA da
                // tabela, e os dois quadrados tem 23 pt entre os centros. Era
                // vertical — o rotulo ficava em cima da caixa e os dois
                // disputavam os 20 pt da linha. Descer a caixa ate 1,3 pt da
                // borda inferior resolveu sem tocar em largura nenhuma.
                //
                // O tamanho passa a bater com o das caixas das colunas
                // vizinhas, que e a comparacao que quem preenche faz olhando a
                // propria linha.
                //
                // Quando a caixa de observacoes cresce e a linha encolhe, a
                // caixa acompanha (ver ladoCaixaSimNao): o topo dela fica sempre
                // 8,2 pt abaixo do topo da linha, sem invadir o rotulo.
                val ladoSimNao = ladoCaixaSimNao(alturaLinha)
                val cySimNao = yT + alturaLinha - 1.3f - ladoSimNao / 2f
                texto(txtSim, simX, yT + 7f, 6.5f, align = Paint.Align.CENTER)
                texto(txtNao, naoX, yT + 7f, 6.5f, align = Paint.Align.CENTER)
                checkbox(simX, cySimNao, ladoSimNao, false)
                checkbox(naoX, cySimNao, ladoSimNao, false)
            }
        }
        desenharBloco(cols(mL.toFloat()), 1)
        desenharBloco(cols(col2L), 21)

        // ===== Observações: centralizadas H e V (padrão da ficha de fotos) =====
        cv.drawRect(mL, obsTop, mR.toFloat(), fimObs.toFloat(), borda)
        fundo.color = cinzaMedio; cv.drawRect(mL, obsTop, mR.toFloat(), obsTop + 15f, fundo)
        cv.drawRect(mL, obsTop, mR.toFloat(), obsTop + 15f, borda)
        texto(txtObservacoes, (mL + mR) / 2f, obsTop + 11f, 9f, bold = true, align = Paint.Align.CENTER)
        // Linhas já quebradas pela largura medida com o MESMO Paint do desenho:
        // nenhuma passa da borda. Primeira linha no topo do corpo; centraliza
        // só na horizontal.
        var yObs = obsTop + OBS_CAB_TO + OBS_PAD + arranjoObs.corpo
        for (linhaObs in arranjoObs.linhas) {
            cv.drawText(linhaObs, (mL + mR) / 2f, yObs, pObs)
            yObs += arranjoObs.corpo * OBS_ENTRELINHA
        }

        cv.restoreToCount(salvoTO)
        desenharRodape(cv, numeroPagina, dados.nomeClinica, pw, ph)
        doc.finishPage(page)
    }

    /**
     * Altura do cabecalho para a etiqueta configurada.
     *
     * Extraida de desenharSimulacao porque o rubricario passou a usar o MESMO
     * cabecalho e precisa da mesma conta. Duplicar a formula faria as duas
     * folhas divergirem no dia em que uma so fosse ajustada.
     */
    private fun calcularHeaderH(): Float =
        (alturaCaixaEtiqueta(etiqAltPt) + ALTURA_LINHA_IDS + 8f).coerceAtLeast(HEADER_HEIGHT)

    /**
     * Cabecalho de TODAS as paginas da ficha.
     *
     * @param titulo1 primeira linha do titulo, a direita sob o logo. O
     *   rubricario passa o proprio; sem isto ele tinha um cabecalho inteiro so
     *   dele — logo do outro lado, titulo a esquerda, identificacao numa linha
     *   "Nome:" — e a folha nao parecia do mesmo documento.
     * @param semPaciente desenha SO o logo e o titulo. Serve ao modelo em
     *   branco do rubricario, gerado nas Configuracoes sem paciente nenhum
     *   selecionado: manter a moldura tracejada da etiqueta e os rotulos de
     *   cadastro num modelo vazio faria parecer ficha por preencher, e alguem
     *   preencheria a mao.
     */
    private fun desenharCabecalhoCompleto(canvas: Canvas, dados: DadosCabecalho, logo: Bitmap?,
                                          titulo1: String = txtTitulo1,
                                          titulo2: String = txtTitulo2,
                                          semPaciente: Boolean = false) {
        val totalW = PAGE_WIDTH - MARGIN * 2
        val colDirW = totalW * 0.30f
        val topo = MARGIN
        val baseHeader = topo + headerHAtual
        val leftAreaW = totalW - colDirW - 8f

        /*
            O TITULO FICA CENTRADO NO VAO ENTRE O LOGO E A LINHA DE IDENTIFICACAO.

            A linha de identificacao e desenhada com `PAGE_WIDTH - MARGIN * 2`
            de largura — ela atravessa a folha inteira, inclusive POR BAIXO
            da coluna do logo. O vao util do titulo vai da borda inferior do
            logo DESENHADO ate o topo das letras dessa linha, e o bloco do
            titulo e centrado nele (ver basesTituloCabecalho).

            Sem logo, conta-se a altura cheia de um logo: o titulo nao muda de
            lugar entre a clinica que tem logo e a que nao tem.

            ETIQUETA BAIXA: quando o vao nao comporta o titulo com folga, e a
            linha de identificacao que desce (ver yLinhaIdentificacao). O
            titulo nunca sobe por cima do logo.

            O modelo em branco usa a MESMA posicao da linha, que ali nao e
            desenhada, e por isso sai com o titulo no mesmo lugar da ficha.
         */
        val paintTitulo = Paint().apply {
            color = Color.parseColor("#333333"); textSize = 16f
            isFakeBoldText = true; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        val linhasTitulo = if (titulo2.isBlank()) 1 else 2
        val capTitulo = alturaTintaTitulo(paintTitulo, titulo1)
        val logoTopo = topo + 4f
        val topoLivre = logoTopo + alturaLogoDesenhado(logo, colDirW * 0.95f)
        val capIds = VAL_LINHA * CAP_RELATIVA
        val etqH = alturaCaixaEtiqueta(etiqAltPt)
        val yIdsBase = yLinhaIdentificacao(topo, etqH, topoLivre,
            alturaBlocoTitulo(capTitulo, ENTRELINHA_TITULO, linhasTitulo), capIds)

        if (semPaciente) {
            // Modelo em branco: nada de paciente entra. O bloco fica vazio de
            // proposito, e o titulo a direita sozinho diz o que a folha e.
        } else {
            // MESMO BLOCO DA FICHA DE TIME-OUT: quadro com os dados dentro e a
            // linha de identificacao sob ele. As duas folhas saem juntas e sao
            // conferidas lado a lado — cada diferenca de arranjo entre elas
            // custa uma leitura a mais de quem separa as fichas.
            val etqW = larguraCaixaEtiqueta(etiqLargPt)
            desenharEtiquetaVirtual(canvas,
                RectF(MARGIN, topo, MARGIN + etqW, topo + etqH), dados)
            desenharLinhaIdentificacao(canvas, MARGIN, yIdsBase,
                PAGE_WIDTH - MARGIN * 2, dados)
            if (dados.numeroSimulacao > 1) {
                val paintNova = Paint().apply {
                    color = Color.parseColor("#333333"); textSize = 10f
                    isFakeBoldText = true; isAntiAlias = true
                }
                canvas.drawText("⚠ $txtNovaSim ${dados.numeroSimulacao - 1}",
                    MARGIN + etqW + 14f, topo + 16f, paintNova)
            }
        }

        // ----- Coluna direita: logo (em cima) + título (embaixo) -----
        val xDir = MARGIN + leftAreaW + 8f
        val rectDir = RectF(xDir, topo, xDir + colDirW, baseHeader)

        val (baseTitulo1, baseTitulo2) = basesTituloCabecalho(topoLivre,
            yIdsBase - capIds, capTitulo, ENTRELINHA_TITULO, linhasTitulo)

        desenharLogoPadrao(canvas, logo, rectDir.right, logoTopo, colDirW * 0.95f)

        // Titulo de UMA linha (rubricario): a funcao ja o centra no vao, em
        // baseTitulo1.
        canvas.drawText(titulo1, rectDir.centerX(), baseTitulo1, paintTitulo)
        if (linhasTitulo == 2)
            canvas.drawText(titulo2, rectDir.centerX(), baseTitulo2, paintTitulo)


        // Linha divisória.
        //
        // FOLGA de 14 pt e não 4: com a etiqueta grande, a linha de dados fica
        // logo acima e a divisória encostava nela — os dois traços de texto e
        // régua se liam como um bloco só. A folga é o que separa o cabeçalho do
        // conteúdo.
        val paintLinha = Paint().apply { color = Color.parseColor("#BBBBBB"); strokeWidth = 1f }
        val yLinha = baseHeader + FOLGA_DIVISORIA
        canvas.drawLine(MARGIN, yLinha, PAGE_WIDTH - MARGIN, yLinha, paintLinha)
    }

    /**
     * LOGO DA CLINICA, identico em TODAS as paginas.
     *
     * Antes cada pagina desenhava o seu: a ficha de fotos e o Time-Out usavam
     * 40 pt com teto de largura, e o rubricario 30 pt sem teto. No papel, com
     * as folhas grampeadas, a diferenca de tamanho e de altura salta aos olhos.
     *
     * O teto de largura existe porque logo muito horizontal (uma assinatura
     * larga, por exemplo) escalaria pela altura e invadiria o titulo ao lado.
     *
     * @return a borda ESQUERDA do logo desenhado, para quem precisa desviar dele.
     */
    private fun desenharLogoPadrao(canvas: Canvas, logo: Bitmap?, bordaDir: Float,
                                   topo: Float, larguraMax: Float = 150f): Float {
        if (logo == null) return bordaDir
        return try {
            val esc = minOf(larguraMax / logo.width, LOGO_ALTURA / logo.height)
            val w = logo.width * esc
            val h = logo.height * esc
            canvas.drawBitmap(logo, null, RectF(bordaDir - w, topo, bordaDir, topo + h), null)
            bordaDir - w
        } catch (_: Exception) { bordaDir }
    }

    // ---- Geometria do título do cabeçalho (funções puras, testadas na JVM) ----

    /** Passo entre as duas linhas do título. */
    private const val ENTRELINHA_TITULO = 18f
    /** Sobra mínima do vão do título (somadas a de cima e a de baixo) quando a
     *  linha de identificação desce. */
    private const val FOLGA_TITULO = 8f
    /** Altura de maiúscula relativa ao corpo: reserva quando a medida falha, e
     *  topo das letras da linha de identificação no corpo cheio. */
    private const val CAP_RELATIVA = 0.72f
    /** Ampliação da medida da tinta do título. Ver [alturaTintaTitulo]. */
    private const val AMPLIACAO_MEDIDA = 64f

    /** Altura do logo como [desenharLogoPadrao] o desenha; sem logo, a altura cheia. */
    private fun alturaLogoDesenhado(logo: Bitmap?, larguraMax: Float): Float =
        if (logo == null) LOGO_ALTURA
        else try { alturaLogo(logo.width, logo.height, larguraMax) } catch (_: Exception) { LOGO_ALTURA }

    /** Mesmo `esc` de [desenharLogoPadrao]: altura cheia, ou menos quando o teto de largura manda. */
    internal fun alturaLogo(largura: Int, altura: Int, larguraMax: Float): Float {
        if (largura <= 0 || altura <= 0) return LOGO_ALTURA
        val esc = minOf(larguraMax / largura, LOGO_ALTURA / altura)
        return altura * esc
    }

    /**
     * Altura da tinta de [texto] acima da linha de base, no Paint do título.
     *
     * MEDIDA NA FONTE, e não estimada: cobre o CJK, cujos ideogramas passam da
     * altura de maiúscula do latim. A medida é feita com o corpo ampliado e
     * dividida de volta porque `getTextBounds` com `Rect` — o único disponível
     * abaixo da API 34 — devolve inteiros arredondados para fora; no corpo de
     * 16 pt isso vira 12 pt para uma tinta de 11,5, e o título sairia deslocado
     * conforme o idioma por uma conta de arredondamento.
     */
    private fun alturaTintaTitulo(paint: Paint, texto: String): Float {
        if (texto.isBlank()) return paint.textSize * CAP_RELATIVA
        return try {
            val ampliado = Paint(paint).apply { textSize = paint.textSize * AMPLIACAO_MEDIDA }
            val r = android.graphics.Rect()
            ampliado.getTextBounds(texto, 0, texto.length, r)
            capDeLimites(r.top, AMPLIACAO_MEDIDA, paint.textSize)
        } catch (_: Exception) { paint.textSize * CAP_RELATIVA }
    }

    /** Converte o topo medido no corpo ampliado; topo não negativo = medida vazia. */
    internal fun capDeLimites(topoAmpliado: Int, ampliacao: Float, corpo: Float): Float =
        if (topoAmpliado >= 0 || ampliacao <= 0f) corpo * CAP_RELATIVA
        else -topoAmpliado / ampliacao

    /** Altura do bloco do título: tinta da 1ª linha mais o passo das seguintes. */
    internal fun alturaBlocoTitulo(capTitulo: Float, entrelinha: Float, linhas: Int): Float =
        capTitulo + entrelinha * (linhas.coerceAtLeast(1) - 1)

    /**
     * Linhas de base do título, com o bloco centrado entre [topoLivre] (a borda
     * inferior do logo) e [baseLivre] (o topo das letras da linha de
     * identificação). A folga de cima é igual à de baixo.
     *
     * @return as bases da 1ª e da 2ª linha; com uma linha só, as duas são iguais.
     */
    internal fun basesTituloCabecalho(
        topoLivre: Float, baseLivre: Float, capTitulo: Float, entrelinha: Float, linhas: Int
    ): Pair<Float, Float> {
        val n = linhas.coerceAtLeast(1)
        val blocoH = alturaBlocoTitulo(capTitulo, entrelinha, n)
        val centro = (topoLivre + baseLivre) / 2f
        val base1 = centro - blocoH / 2f + capTitulo
        return base1 to (base1 + entrelinha * (n - 1))
    }

    /**
     * Linha de base da linha de identificação: 12 pt abaixo do quadro, como
     * sempre, ou mais abaixo quando o vão até o logo não comporta o título com
     * [FOLGA_TITULO] de sobra, repartida em cima e embaixo — o caso da
     * etiqueta baixa (menos de cerca de 27 mm), em que, com a linha no lugar
     * de sempre, o título subiria por cima do logo.
     */
    internal fun yLinhaIdentificacao(
        topo: Float, alturaQuadro: Float, topoLivre: Float, blocoTitulo: Float, capIds: Float
    ): Float = maxOf(topo + alturaQuadro + 12f,
        topoLivre + FOLGA_TITULO + blocoTitulo + capIds)

    /** Dados empilhados (rótulo em cima, valor embaixo) — usado ao lado da etiqueta normal. */
    /** Idade a partir de dd/MM/yyyy: anos; se menor de 18, "Xa Ym". */
    private fun idadeTexto(nascimento: String): String = try {
        val p = nascimento.trim().split("/")
        if (p.size != 3) "" else {
            val d = p[0].toInt(); val m = p[1].toInt(); val a = p[2].toInt()
            val hoje = java.util.Calendar.getInstance()
            var anos = hoje.get(java.util.Calendar.YEAR) - a
            var meses = hoje.get(java.util.Calendar.MONTH) + 1 - m
            if (hoje.get(java.util.Calendar.DAY_OF_MONTH) < d) meses -= 1
            if (meses < 0) { anos -= 1; meses += 12 }
            when {
                anos < 0 || anos > 130 -> ""
                anos < 18 -> "${anos}a ${meses}m"
                else -> "$anos anos"
            }
        }
    } catch (_: Exception) { "" }


    // ======= Layout unificado do bloco ETIQUETA FÍSICA + IDENTIFICAÇÕES =======
    // A geometria da ficha de TIME-OUT dita a regra e é REPLICADA na ficha de
    // fotos: mesma decisão (ids ao LADO ou ABAIXO da etiqueta) nas duas páginas.
    private const val TO_BLOCO_W = 265.5f      // largura do bloco 1 da tabela do Time-Out

    /**
     * Folga que NADA do cabecalho pode ocupar.
     *
     * Existe porque o desenho encostava: a identificacao terminava exatamente
     * onde comecava o box de equipamento, e qualquer campo a mais — um nome
     * longo que quebrava em duas linhas, um prontuario grande — passava por
     * cima. Reservar a folga em vez de confiar na conta faz o layout escolher
     * outro arranjo ANTES de colidir.
     */
    private const val FOLGA_SEGURANCA = 10f
    private const val TO_ALTURA_TOPO = 150f    // altura da faixa etiqueta/foto do rosto

    /**
     * Fichas que contem folha frente-e-verso de protocolo.
     *
     * O caminho de impressao recebe so o File, e a essa altura nao ha mais como
     * saber que protocolo gerou aquele PDF. Esta marca responde isso sem
     * arrastar o id do protocolo por varias assinaturas de funcao.
     *
     * A CHAVE E O NOME DO ARQUIVO, nao o caminho. A ficha e gerada no cache e
     * guardada na pasta do paciente com o MESMO nome, e o Historico e o
     * carrossel imprimem a copia da pasta: pelo caminho a marca nunca casaria
     * com ela, e a folha com verso sairia em simplex.
     *
     * GRAVADA EM PREFERENCIAS, para valer depois de reabrir o app. A lista e
     * curta e podada (ficam as [LIMITE_MARCAS_FV] mais recentes), e a ficha
     * regerada sem verso e desmarcada. Em memoria fica a copia de trabalho.
     *
     * Sem Context, a consulta usa as preferencias ja abertas nesta execucao —
     * por qualquer geracao de ficha ou por uma consulta que passou Context.
     */
    private const val PREFS_FRENTE_VERSO = "pdf_frente_verso"
    private const val CHAVE_MARCAS_FV = "fichas"
    internal const val LIMITE_MARCAS_FV = 300
    private val travaFrenteVerso = Any()
    @Volatile private var prefsFrenteVerso: android.content.SharedPreferences? = null
    /** Nomes marcados, do mais antigo ao mais recente. */
    private var marcasFV: List<String> = emptyList()
    /** As marcas gravadas ja foram lidas e juntadas as de memoria? */
    private var marcasLidas = false

    fun marcarFrenteVerso(arquivo: File, context: Context? = null) =
        registrarFrenteVerso(arquivo, true, context)

    fun desmarcarFrenteVerso(arquivo: File, context: Context? = null) =
        registrarFrenteVerso(arquivo, false, context)

    /** A ficha [arquivo] tem folha com verso? Passar [context] garante a leitura do que foi gravado. */
    fun temFrenteVerso(arquivo: File, context: Context? = null): Boolean = try {
        synchronized(travaFrenteVerso) {
            sincronizarMarcas(context)
            arquivo.name in marcasFV
        }
    } catch (_: Exception) { false }

    private fun registrarFrenteVerso(arquivo: File, comVerso: Boolean, context: Context?) {
        try {
            synchronized(travaFrenteVerso) {
                sincronizarMarcas(context)
                val depois = atualizarMarcasFrenteVerso(marcasFV, arquivo.name, comVerso)
                if (depois != marcasFV) {
                    marcasFV = depois
                    prefsFrenteVerso?.edit()
                        ?.putString(CHAVE_MARCAS_FV, marcasParaTexto(depois))?.apply()
                }
            }
        } catch (_: Exception) { /* marca e conveniencia de impressao, nunca requisito */ }
    }

    /** Abre as preferencias e junta, uma vez, o que esta gravado. Chamar sob a trava. */
    private fun sincronizarMarcas(context: Context?) {
        if (prefsFrenteVerso == null && context != null) {
            prefsFrenteVerso = (context.applicationContext ?: context)
                .getSharedPreferences(PREFS_FRENTE_VERSO, Context.MODE_PRIVATE)
        }
        val prefs = prefsFrenteVerso ?: return
        if (marcasLidas) return
        val gravadas = marcasDeTexto(prefs.getString(CHAVE_MARCAS_FV, null))
        // As gravadas sao mais antigas que qualquer marca feita so em memoria.
        val juntas = gravadas.filter { it !in marcasFV } + marcasFV
        marcasFV = if (juntas.size > LIMITE_MARCAS_FV) juntas.takeLast(LIMITE_MARCAS_FV) else juntas
        marcasLidas = true
    }

    /**
     * Lista de marcas depois de marcar ou desmarcar [nome]. A marca refeita vai
     * para o fim (a mais recente), e a poda tira as mais antigas.
     */
    internal fun atualizarMarcasFrenteVerso(
        atuais: List<String>, nome: String, marcar: Boolean, limite: Int = LIMITE_MARCAS_FV
    ): List<String> {
        // Quebra de linha e o separador da gravacao: um nome com ela se
        // partiria em dois ao ser lido de volta.
        if (nome.isBlank() || nome.contains('\n')) return atuais
        val sem = atuais.filter { it != nome }
        val nova = if (marcar) sem + nome else sem
        return if (nova.size > limite) nova.takeLast(limite.coerceAtLeast(0)) else nova
    }

    internal fun marcasDeTexto(texto: String?): List<String> =
        texto.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    internal fun marcasParaTexto(marcas: List<String>): String = marcas.joinToString("\n")

    /** Altura do logo, a MESMA em todas as paginas. Ver desenharLogoPadrao. */
    private const val LOGO_ALTURA = 40f

    /** Fracao da altura da celula que a rubrica ocupa. Igual para todas:
     *  e o que faz a folha parecer uniforme. Ver o desenho do rubricario. */
    private const val ALTURA_RUBRICA = 0.82f

    /** Largura do quadro: no maximo o bloco 1 da tabela do Time-Out, que e a
     *  regua a que o resto do cabecalho ja obedece. */
    private fun larguraCaixaEtiqueta(pt: Float): Float = pt.coerceAtMost(TO_BLOCO_W)

    /**
     * Altura do quadro: o que a faixa de topo comporta descontada a linha.
     *
     * O TETO E 134 pt (47 mm), e nao os 70 mm que a configuracao aceita. A faixa
     * de topo da ficha de Time-Out tem altura fixa — a tabela de 20 fracoes
     * comeca logo abaixo — e a linha de identificacao mora nos ultimos 16 pt
     * dela. Etiqueta configurada acima disso sai desenhada menor; nao ha para
     * onde crescer sem comer linha da tabela.
     *
     * O MESMO TETO VALE NA FICHA DE FOTOS, onde o cabecalho poderia crescer. E
     * deliberado: as duas folhas saem juntas, e uma etiqueta de papel que
     * coubesse numa e nao na outra seria pior que um quadro um pouco menor nas
     * duas.
     */
    private fun alturaCaixaEtiqueta(pt: Float): Float =
        pt.coerceAtMost(TO_ALTURA_TOPO - ALTURA_LINHA_IDS)

    /**
     * A LINHA DE IDENTIFICACAO, sob o quadro da etiqueta.
     *
     * REPETE DE PROPOSITO o que ja esta impresso dentro do quadro. O quadro e
     * onde a etiqueta de papel do servico e colada, e colada ela cobre tudo o
     * que estiver ali — nome inclusive. Esta linha e a copia que sobrevive a
     * colagem: e por ela que a ficha continua identificavel depois.
     *
     * A DATA DA SIMULACAO so aparece AQUI, e nunca dentro do quadro. E a unica
     * informacao do conjunto que a etiqueta do servico nao traz, entao seria
     * justamente a que a colagem faria desaparecer da folha.
     */
    private fun desenharLinhaIdentificacao(
        canvas: Canvas, xEsq: Float, yBase: Float, largura: Float, dados: DadosCabecalho
    ) {
        // TODOS OS CAMPOS DO QUADRO, na mesma ordem dele, mais a data.
        //
        // Idade e sexo entraram depois: estavam so dentro do quadro, que e
        // exatamente o que a etiqueta de papel cobre. Eram os dois campos que a
        // colagem apagava da folha sem deixar copia em lugar nenhum.
        val partes = mutableListOf<Pair<String, String>>()
        if (dados.nomePaciente.isNotBlank()) partes.add(txtPaciente to dados.nomePaciente)
        // A IDADE ANDA COM A DATA DE NASCIMENTO, num campo so.
        //
        // Como campo separado ela custava um rotulo inteiro — "IDADE: " — mais
        // um separador, e seis campos rotulados nao cabem nesta largura sem
        // apertar os outros cinco. Entre parenteses ao lado da data ela nao
        // paga rotulo nenhum, e fica onde quem le ja procura: ninguem confere
        // idade sem olhar a data de nascimento.
        val idade = idadeTexto(dados.nascimento)
        when {
            dados.nascimento.isNotBlank() -> partes.add(txtNascimento to
                (dados.nascimento + if (idade.isNotBlank()) "  ($idade)" else ""))
            idade.isNotBlank() -> partes.add(txtIdade to idade)
        }
        if (dados.prontuario.isNotBlank()) partes.add(txtRegistro to dados.prontuario)
        if (dados.sexo.isNotBlank()) partes.add(txtSexo to dados.sexo)
        partes.add(txtDataSim.removeSuffix(":") to
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(dados.dataSimulacao))

        // NEGRITO DE VERDADE no rotulo, com typeface bold e nao isFakeBoldText:
        // o negrito sintetico quase nao se distingue do valor neste corpo, e a
        // linha vira um bloco unico de texto onde nao se acha o campo procurado.
        val pRot = Paint().apply {
            color = Color.parseColor("#444444"); textSize = ROT_LINHA
            typeface = android.graphics.Typeface.DEFAULT_BOLD; isAntiAlias = true
        }
        val pVal = Paint().apply {
            color = Color.BLACK; textSize = VAL_LINHA
            typeface = android.graphics.Typeface.DEFAULT; isAntiAlias = true
        }
        val pSep = Paint().apply {
            color = Color.parseColor("#888888"); textSize = VAL_LINHA; isAntiAlias = true
        }
        // SEPARADOR CURTO. Com cinco campos, quatro separadores de cinco
        // caracteres custavam quase 40 pt — o bastante para empurrar a linha
        // inteira um degrau de escala para baixo.
        val sep = " · "

        // CABE EM DOIS DEGRAUS, e NENHUM CAMPO SOME.
        //
        // 1. Encolhe proporcionalmente, com piso em 70%. Abaixo disso o valor
        //    cai de 9,5 para menos de 6,7 pt, e a linha deixa de se ler no
        //    papel impresso — que e o unico lugar onde ela e lida.
        // 2. Se nem no piso couber, o NOME e truncado. Ele e o unico campo de
        //    comprimento imprevisivel, e o unico que ainda aparece inteiro
        //    dentro do quadro logo acima. Os demais tem tamanho conhecido e
        //    nao ha por que um deles pagar pelo comprimento do outro.
        //
        // Uma versao anterior derrubava a IDADE quando a escala caia demais.
        // Nao e mais preciso: ela deixou de ser campo rotulado e passou a andar
        // entre parenteses com a data de nascimento, que e o que liberou a
        // largura de que a linha precisava.
        fun medir(e: Float): Float {
            var w = 0f
            partes.forEachIndexed { i, (rot, valor) ->
                pRot.textSize = ROT_LINHA * e
                pVal.textSize = VAL_LINHA * e
                pSep.textSize = VAL_LINHA * e
                w += pRot.measureText("$rot: ") + pVal.measureText(valor)
                if (i < partes.size - 1) w += pSep.measureText(sep)
            }
            return w
        }
        fun ajustar(): Float {
            var e = 1f
            while (e > PISO_LINHA && medir(e) > largura) e -= 0.02f
            return e
        }
        val escala = ajustar()
        pRot.textSize = ROT_LINHA * escala
        pVal.textSize = VAL_LINHA * escala
        pSep.textSize = VAL_LINHA * escala
        while (medir(escala) > largura && partes[0].second.length > 8) {
            partes[0] = partes[0].first to (partes[0].second.dropLast(2))
        }

        var x = xEsq
        partes.forEachIndexed { i, (rot, valor) ->
            val pref = "$rot: "
            canvas.drawText(pref, x, yBase, pRot); x += pRot.measureText(pref)
            canvas.drawText(valor, x, yBase, pVal); x += pVal.measureText(valor)
            if (i < partes.size - 1) {
                canvas.drawText(sep, x, yBase, pSep); x += pSep.measureText(sep)
            }
        }
    }

    /** Quebra um texto em várias linhas que caibam em maxW (por palavras; se uma
     *  palavra isolada não couber, quebra por caracteres). */
    private fun quebrarLinhas(texto: String, paint: Paint, maxW: Float): List<String> =
        quebrarLinhas(texto, maxW) { paint.measureText(it) }

    /** O mesmo algoritmo, com a medida passada de fora: puro, testável na JVM. */
    internal fun quebrarLinhas(texto: String, maxW: Float, medir: (String) -> Float): List<String> {
        val palavras = texto.split(" ").filter { it.isNotBlank() }
        val linhas = mutableListOf<String>()
        var atual = ""
        for (p in palavras) {
            val tent = if (atual.isEmpty()) p else "$atual $p"
            if (medir(tent) <= maxW) { atual = tent; continue }
            if (atual.isNotEmpty()) { linhas.add(atual); atual = "" }
            if (medir(p) <= maxW) { atual = p; continue }
            var resto = p                       // palavra sozinha maior que a caixa
            while (resto.isNotEmpty()) {
                var corte = resto.length
                while (corte > 1 && medir(resto.substring(0, corte)) > maxW) corte--
                linhas.add(resto.substring(0, corte)); resto = resto.substring(corte)
            }
        }
        if (atual.isNotEmpty()) linhas.add(atual)
        return linhas
    }

    // ---- Observações: quebra, corpo e caixa (funções puras, testadas na JVM) ----

    /** Medida das observações: Typeface.DEFAULT, o Paint do desenho nas duas páginas. */
    private fun medidorObservacoes(): (String, Float) -> Float {
        val p = Paint().apply { isAntiAlias = true; typeface = Typeface.DEFAULT }
        return { s, corpo -> p.textSize = corpo; p.measureText(s) }
    }

    /**
     * Linhas das observações numa largura: respeita a quebra digitada, descarta
     * linhas vazias e espaços nas pontas, e quebra cada parágrafo pela largura.
     */
    internal fun linhasObservacoes(
        texto: String, largura: Float, corpo: Float, medir: (String, Float) -> Float
    ): List<String> = texto.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        .flatMap { par -> quebrarLinhas(par, largura) { medir(it, corpo) } }

    /** Altura do texto de [linhas] linhas no [corpo], com a entrelinha da folha. */
    internal fun alturaTextoObs(linhas: Int, corpo: Float): Float =
        linhas * corpo * OBS_ENTRELINHA

    /**
     * O corpo das observações desta simulação: o maior, de 11,5 pt descendo de
     * 0,5 em 0,5, em que o texto cabe em [OBS_TEXTO_MAX] em TODAS as caixas de
     * [larguras]. A mais estreita decide, e as duas páginas saem no mesmo corpo.
     *
     * O piso é [OBS_CORPO_MIN] (8,5 pt): texto que nem nele cabe sai no piso e é
     * cortado por [arranjarObservacoes].
     */
    internal fun escolherCorpoObs(
        texto: String, larguras: List<Float>, medir: (String, Float) -> Float
    ): Float {
        if (texto.isBlank() || larguras.isEmpty()) return OBS_CORPO
        var corpo = OBS_CORPO
        while (corpo >= OBS_CORPO_MIN - 0.001f) {
            val c = corpo
            val cabe = larguras.all { w ->
                alturaTextoObs(linhasObservacoes(texto, w, c, medir).size, c) <= OBS_TEXTO_MAX + 0.001f
            }
            if (cabe) return c
            corpo -= OBS_PASSO_CORPO
        }
        return OBS_CORPO_MIN
    }

    /** O texto arranjado numa caixa: corpo, linhas a desenhar e a altura delas. */
    internal data class ArranjoObs(
        val corpo: Float,
        val linhas: List<String>,
        val alturaTexto: Float,
        /** `true` só quando nem no piso o texto coube e a última linha termina em " …". */
        val cortado: Boolean
    )

    /**
     * Linhas das observações numa caixa de [largura], no [corpo] já escolhido.
     *
     * Se nem assim couber em [OBS_TEXTO_MAX], ficam as linhas que cabem e a
     * última termina em " …", medida para caber. É o único corte possível, e
     * só acontece com o corpo no piso, acima de 6 linhas desenhadas a 8,5 pt.
     * Nenhuma linha passa da largura: a medida é a do Paint do desenho.
     */
    internal fun arranjarObservacoes(
        texto: String, largura: Float, corpo: Float, medir: (String, Float) -> Float
    ): ArranjoObs {
        val linhas = linhasObservacoes(texto, largura, corpo, medir)
        val passo = corpo * OBS_ENTRELINHA
        if (alturaTextoObs(linhas.size, corpo) <= OBS_TEXTO_MAX + 0.001f)
            return ArranjoObs(corpo, linhas, alturaTextoObs(linhas.size, corpo), false)
        val cabem = ((OBS_TEXTO_MAX + 0.001f) / passo).toInt().coerceAtLeast(1)
        val ficam = linhas.take(cabem).toMutableList()
        var ultima = ficam.last()
        while (ultima.isNotEmpty() && medir("$ultima …", corpo) > largura)
            ultima = ultima.dropLast(1).trimEnd()
        ficam[ficam.size - 1] = if (ultima.isEmpty()) "…" else "$ultima …"
        return ArranjoObs(corpo, ficam, alturaTextoObs(ficam.size, corpo), true)
    }

    /**
     * Caixa de observações do Time-Out: título, respiro e texto, entre a caixa
     * de sempre (66 pt, que com até 2 linhas não mexe na tabela) e o teto de
     * 96 pt, em que a linha da tabela chega a 18 pt.
     */
    internal fun alturaCaixaObsTimeOut(alturaTexto: Float): Float =
        (OBS_CAB_TO + 2 * OBS_PAD + alturaTexto).coerceIn(OBS_TO_ALTURA_MIN, OBS_TO_ALTURA_MAX)

    /** Faixa das páginas de fotos (caixa mais o respiro até a grade); 0 sem texto. */
    internal fun alturaFaixaObsFotos(arranjo: ArranjoObs): Float =
        if (arranjo.linhas.isEmpty()) 0f else arranjo.alturaTexto + 2 * OBS_PAD + 6f

    /**
     * Caixa Sim/Não da tabela do Time-Out: 10 pt, ou menos quando a linha
     * encolhe. O topo dela fica sempre 8,2 pt abaixo do topo da linha, 1,2 pt
     * sob a base do rótulo (yT + 7): com a caixa fixa, na linha de 18,6 pt
     * sobrariam 0,3 pt entre os dois.
     */
    internal fun ladoCaixaSimNao(alturaLinha: Float): Float = minOf(10f, alturaLinha - 9.5f)

    /**
     * O QUADRO DA ETIQUETA — o mesmo nas duas folhas, em qualquer configuracao.
     *
     * Contorno cinza TRACEJADO de cantos arredondados, com nome, idade, data de
     * nascimento, registro e sexo dentro. O tracejado e o convite a colar a
     * etiqueta de papel do servico por cima; o conteudo impresso e o que serve
     * a quem nao usa etiqueta — e, para quem usa, o que identifica a folha
     * ANTES de ela ser colada, na hora de separar as fichas na impressora.
     *
     * A DATA DA SIMULACAO nao entra aqui: ver desenharLinhaIdentificacao.
     *
     * E TAMBEM A ETIQUETA DAS PAGINAS DO PROTOCOLO, pela mesma rotina — so
     * assim as duas saem iguais.
     *
     * @param fundoBranco pinta o quadro de branco antes do contorno. So o
     *   protocolo usa: la a etiqueta cai sobre o documento da clinica, e sem o
     *   fundo o texto se misturaria com o que estiver impresso no ponto. Nas
     *   primeiras paginas o padrao `false` deixa o desenho como sempre foi.
     */
    private fun desenharEtiquetaVirtual(
        canvas: Canvas, rect: RectF, dados: DadosCabecalho, fundoBranco: Boolean = false
    ) {
        if (fundoBranco) {
            canvas.drawRoundRect(rect, RAIO_CANTO, RAIO_CANTO, Paint().apply {
                color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true
            })
        }
        val dash = Paint().apply {
            color = Color.parseColor("#999999"); style = Paint.Style.STROKE; strokeWidth = 0.9f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f); isAntiAlias = true
        }
        canvas.drawRoundRect(rect, RAIO_CANTO, RAIO_CANTO, dash)

        // Margem interna simétrica: mesma distância nas duas bordas (~projeção da
        // largura de uma célula "Fração"/"Nascimento" da tabela abaixo).
        val margem = 10f
        val xTexto = rect.left + margem
        val maxW = rect.width() - margem * 2f
        val maxH = rect.height() - 8f
        if (maxW <= 20f || maxH <= 12f) return

        val pNome = Paint().apply {
            isAntiAlias = true; color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD; textSize = 12f
            textAlign = Paint.Align.LEFT
        }
        val pId = Paint().apply {
            isAntiAlias = true; color = Color.BLACK
            typeface = Typeface.DEFAULT; textSize = 8.5f
            textAlign = Paint.Align.LEFT
        }

        // ROTULOS TRADUZIDOS. Estavam fixos em portugues, e este quadro era a
        // unica parte da ficha que saia assim — "Nascimento" no meio de uma
        // folha em japones. Passava despercebido enquanto ele era a excecao;
        // agora que e a etiqueta de TODA ficha, sairia em toda impressao.
        val ids = mutableListOf<String>()
        idadeTexto(dados.nascimento).let { if (it.isNotBlank()) ids.add("$txtIdade: $it") }
        if (dados.nascimento.isNotBlank()) ids.add("$txtNascimento: ${dados.nascimento}")
        if (dados.prontuario.isNotBlank()) ids.add("$txtRegistro: ${dados.prontuario}")
        if (dados.sexo.isNotBlank()) ids.add("$txtSexo: ${dados.sexo}")

        data class Ln(val txt: String, val paint: Paint, val h: Float)

        /**
         * Monta o conjunto num par de corpos e diz que altura ele ocupa.
         *
         * O nome quebra em quantas linhas precisar; as identificacoes ocupam uma
         * cada. E a mesma conta do desenho, por isso a decisao e o traco nunca
         * discordam.
         */
        fun montar(corpoNome: Float, corpoId: Float): Pair<List<Ln>, Float> {
            pNome.textSize = corpoNome
            pId.textSize = corpoId
            val hNome = corpoNome + 3f
            val hId = corpoId + 2.5f
            val out = mutableListOf<Ln>()
            for (ln in quebrarLinhas(dados.nomePaciente.uppercase(), pNome, maxW))
                out.add(Ln(ln, pNome, hNome))
            for (t in ids) out.add(Ln(t, pId, hId))
            return out to out.sumOf { it.h.toDouble() }.toFloat()
        }

        val (corpoNome, corpoId) = corposEtiqueta(rect.height(), maxH) { n, i -> montar(n, i).second }
        // GUARDA: montar de novo, POR ULTIMO, com os corpos aceitos. As linhas
        // guardam o Paint e nao o corpo, e o laco de crescer termina numa
        // tentativa recusada que deixa os dois Paints maiores que o aceito
        // (meio ponto no nome, 0,31 nas identificacoes). Sem esta chamada o
        // texto sai maior que a medida e encosta na borda de baixo.
        var (linhas, totalH) = montar(corpoNome, corpoId)
        if (totalH > maxH) {
            val cabem = mutableListOf<Ln>()
            var acum = 0f
            for (l in linhas) {
                if (acum + l.h > maxH) break
                cabem.add(l); acum += l.h
            }
            linhas = cabem; totalH = acum
        }
        if (linhas.isEmpty()) return

        // Centraliza verticalmente o conjunto, que agora cabe.
        var y = rect.centerY() - totalH / 2f + linhas.first().h - 3f
        for (l in linhas) {
            var t = l.txt
            while (l.paint.measureText(t) > maxW && t.length > 6) t = t.dropLast(2)
            canvas.drawText(t, xTexto, y, l.paint)
            y += l.h
        }
    }

    /**
     * Os corpos do nome e das identificações no quadro da etiqueta.
     *
     * O CORPO SAI DA ALTURA DO QUADRO, e nao de um numero fixo. Com 12 pt para
     * todo tamanho, a etiqueta de 100x50 mm tinha quatro vezes a area da de
     * 60x30 e a mesma letra — o espaco que o servico configurou ficava sobrando
     * em volta de um nome pequeno.
     *
     * O PISO E 12 pt de proposito: e o corpo que a etiqueta padrao ja usa, e
     * quadro pequeno nao deve sair com nome MENOR que o da etiqueta padrao.
     * Quem reduz abaixo disso e o laco de caber, e so quando nao ha alternativa.
     *
     * CABE OU ENCOLHE: o conjunto e reduzido ate caber na altura do quadro,
     * com piso de legibilidade — nome a 7 pt, identificacoes a 6 pt. No piso, o
     * que nao couber e cortado por quem desenha, em vez de transbordar; o que
     * se perde sobrevive na linha logo abaixo do quadro.
     *
     * SOBROU ALTURA: cresce de volta, ate o teto. Sem isto, quadro alto e
     * estreito — em que o nome quebra em tres linhas e o laco de caber reduziu
     * o corpo — ficaria com letra pequena e um palmo de vazio embaixo.
     *
     * @param alturaConjunto altura que o conjunto ocupa num par de corpos; e a
     *   mesma conta do desenho, por isso a decisao e o traco nunca discordam.
     * @return o par ACEITO (nome, identificacoes). Quem desenha usa exatamente
     *   este par, e nao o da ultima tentativa.
     */
    internal fun corposEtiqueta(
        alturaQuadro: Float, maxH: Float, alturaConjunto: (Float, Float) -> Float
    ): Pair<Float, Float> {
        var corpoNome = (alturaQuadro * 0.13f).coerceIn(12f, 20f)
        var corpoId = (corpoNome * 0.62f).coerceIn(8.5f, 12f)
        var totalH = alturaConjunto(corpoNome, corpoId)
        while (totalH > maxH && corpoNome > 7f) {
            corpoNome -= 0.5f
            corpoId = (corpoId - 0.35f).coerceAtLeast(6f)
            totalH = alturaConjunto(corpoNome, corpoId)
        }
        while (corpoNome < 20f) {
            val nome = corpoNome + 0.5f
            val id = (corpoId + 0.31f).coerceAtMost(12f)
            if (alturaConjunto(nome, id) > maxH) break
            corpoNome = nome
            corpoId = id
        }
        return corpoNome to corpoId
    }

    /**
     * Faixa de Observações (estilo da ficha clínica): rótulo "Observações" com
     * fundo cinza à esquerda e, à direita, a caixa com o texto centralizado.
     *
     * A caixa tem a altura das linhas já quebradas ([obsLinhasFotos]), no corpo
     * comum às duas páginas ([obsCorpo]); a grade de fotos começa abaixo dela e
     * se encaixa no que sobra. Primeira linha no topo da caixa, centralizada só
     * na horizontal; o rótulo fica no meio da altura.
     */
    private fun desenharBandaObservacoes(canvas: Canvas, yTop: Float) {
        val xEsq = MARGIN
        val larguraTotal = PAGE_WIDTH - MARGIN * 2
        val larguraRotulo = OBS_ROTULO_W
        val altura = obsBandHeight - 6f
        val rectRotulo = RectF(xEsq, yTop, xEsq + larguraRotulo, yTop + altura)
        val rectBox = RectF(xEsq + larguraRotulo, yTop, xEsq + larguraTotal, yTop + altura)

        val paintFundo = Paint().apply { color = Color.parseColor("#EEEEEE"); style = Paint.Style.FILL }
        canvas.drawRect(rectRotulo, paintFundo)
        val paintBorda = Paint().apply {
            color = Color.parseColor("#BBBBBB"); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        canvas.drawRect(rectRotulo, paintBorda)
        canvas.drawRect(rectBox, paintBorda)

        val paintRot = Paint().apply {
            color = Color.parseColor("#444444"); textSize = 9f
            isFakeBoldText = true; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(txtObservacoes, rectRotulo.centerX(), rectRotulo.centerY() + 3f, paintRot)

        val paintObs = Paint().apply {
            color = Color.BLACK; textSize = obsCorpo; typeface = Typeface.DEFAULT
            isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        var ty = rectBox.top + OBS_PAD + obsCorpo
        obsLinhasFotos.forEach {
            canvas.drawText(it, rectBox.centerX(), ty, paintObs)
            ty += obsCorpo * OBS_ENTRELINHA
        }
    }

    /**
     * Cabeçalho simplificado (ficha de acessórios): 1 linha de dados + título + logo
     */
    private fun desenharCabecalhoSimplificado(canvas: Canvas, dados: DadosCabecalho, logo: Bitmap?) {
        val topo = MARGIN
        val totalW = PAGE_WIDTH - MARGIN * 2

        val paintTitulo = Paint().apply {
            color = Color.parseColor("#333333")
            textSize = 20f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText(txtFichaAcessorios, MARGIN, topo + 18f, paintTitulo)

        val paintLabel = Paint().apply {
            color = Color.parseColor("#444444")
            textSize = 9f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val paintValor = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }

        var y = topo + 36f
        canvas.drawText(txtPaciente, MARGIN, y, paintLabel)
        canvas.drawText(dados.nomePaciente, MARGIN + 60f, y, paintValor)
        y += 14f
        if (dados.prontuario.isNotBlank()) {
            canvas.drawText(txtProntuario, MARGIN, y, paintLabel)
            canvas.drawText(dados.prontuario, MARGIN + 60f, y, paintValor)
            y += 14f
        }
        canvas.drawText("DATA SIM.", MARGIN, y, paintLabel)
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        canvas.drawText(fmt.format(dados.dataSimulacao), MARGIN + 60f, y, paintValor)

        if (logo != null) {
            val ratio = logo.width.toFloat() / logo.height.toFloat()
            val maxW = 130f
            val maxH = 60f
            val (lw, lh) = if (logo.width > logo.height) maxW to (maxW / ratio)
                            else (maxH * ratio) to maxH
            val rectLogo = RectF(
                PAGE_WIDTH - MARGIN - lw, topo,
                (PAGE_WIDTH - MARGIN), topo + lh
            )
            canvas.drawBitmap(logo, null, rectLogo, null)
        }

        // Linha divisória
        val paintLinha = Paint().apply { color = Color.parseColor("#BBBBBB"); strokeWidth = 1f }
        canvas.drawLine(MARGIN, topo + 78f, PAGE_WIDTH - MARGIN, topo + 78f, paintLinha)
    }

    // =================== TABELA DE FOTOS ===================

    /**
     * Desenha a grade de fotos. Layout adaptativo na 1ª página, fixo nas demais.
     *
     * @param fotosPag fotos desta página (até 8)
     * @param totalNaSimulacao total de fotos da simulação (para layout adaptativo)
     * @param indiceInicial índice da 1ª foto desta página dentro da simulação completa
     */
    private fun desenharGridFotos(canvas: Canvas, fotosPag: List<File>, totalNaSimulacao: Int,
                                  indiceInicial: Int, rotulos: List<String>? = null,
                                  nomePaciente: String = "") {
        // Comeca depois da divisoria (+FOLGA_DIVISORIA) e da banda de
        // observacoes. Estava em +12, que passou a cair ACIMA da regua quando a
        // folga da divisoria subiu para 14.
        val areaTopo = MARGIN + headerHAtual + FOLGA_DIVISORIA + 8f + obsBandHeight
        // Os 28 pt de baixo sao do rodape na folha FISICA. Esta area esta no
        // canvas deslocado pelo recuo, que na folha deitada vai ao topo: sem
        // descontar o recuo a grade desceria por cima do rodape. Em pe o recuo
        // de topo e zero e nada muda.
        val areaBase = PAGE_HEIGHT - 28f - margemExtraTopoPt
        val areaW = PAGE_WIDTH - MARGIN * 2
        val areaH = areaBase - areaTopo

        // Layout pela quantidade de fotos DESTA página (antes era pelo total da
        // simulação, e só na primeira página). Assim uma última página com 3
        // fotos ganha o mesmo destaque que uma simulação de 3 fotos.
        val cells = calcularLayoutFotos(
            fotosPag.size, areaTopo, areaW, areaH,
            cols = if (landscape) 4 else GRID_COLS,
            rows = if (landscape) 2 else GRID_ROWS,
            distribuicao = if (landscape) distribuicaoPaisagem(fotosPag.size)
                           else distribuicaoRetrato(fotosPag.size),
            limitarPelaProporcao = landscape
        ).map { celula(it.x, it.y, it.w, it.h) }

        // Desenha cada célula com sua foto
        fotosPag.forEachIndexed { localIdx, arquivo ->
            if (localIdx >= cells.size) return@forEachIndexed
            val cell = cells[localIdx]
            val indiceGlobal = indiceInicial + localIdx
            val rotulo = rotulos?.getOrNull(indiceGlobal) ?: rotuloPara(indiceGlobal)
            desenharCelula(canvas, cell, arquivo, rotulo, nomePaciente)
        }
    }

    /**
     * Layout do grid de fotos, com AMPLIAÇÃO de até 25%.
     *
     * A grade de referência é a cheia — 2 colunas x 4 linhas em retrato, 4 x 2
     * em paisagem — e é ela que define o tamanho "normal" de célula. Com a
     * grade cheia (8 fotos) não sobra espaço, então nada muda: a folha de 8
     * fotos sai idêntica à de antes. Com menos fotos sobram linhas/colunas, e
     * a célula cresce até o limite de [MAX_AMPLIACAO] para dar mais destaque,
     * com o bloco centralizado na área.
     *
     * O teto de 25% existe porque sem ele 2 fotos virariam meia página cada
     * uma: destaque demais atrapalha a conferência lado a lado.
     *
     * QUANTAS FOTOS POR LINHA vem de fora, por orientação
     * ([distribuicaoRetrato] e [distribuicaoPaisagem]). Uma generalização que
     * calculava isso aqui — encher as colunas de cima para baixo — mudou o
     * retrato sem que ninguém pedisse: 5 fotos passaram de 1,1,1,2 para 2,2,1.
     * A regra por quantidade é layout acordado com a clínica, não detalhe de
     * implementação, e por isso mora fora desta função.
     */
    internal data class Celula(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * Quantas fotos em cada linha, no modo PAISAGEM.
     *
     * A folha deitada tem area larga e baixa. Distribuir em UMA linha — que era
     * o que acontecia ate 4 fotos — deixava cada foto minuscula entre faixas de
     * papel vazio, e as fotos ja sao landscape: espalhar na horizontal e o pior
     * uso possivel do espaco.
     *
     * Sempre DUAS linhas (menos com uma foto so), com a metade MENOR em cima:
     *
     *   2 -> 1 + 1     4 -> 2 + 2     6 -> 3 + 3     8 -> 4 + 4
     *   3 -> 1 + 2     5 -> 2 + 3     7 -> 3 + 4
     *
     * Cada linha e centralizada por si. Em quantidade impar, o centro da pagina
     * cai no MEIO da foto do meio na linha impar, e na juncao entre as fotos na
     * linha par — que e o alinhamento que se espera ao olhar a folha.
     */
    internal fun distribuicaoPaisagem(qtdFotos: Int): List<Int> {
        val n = qtdFotos.coerceAtLeast(1)
        if (n == 1) return listOf(1)
        val cima = n / 2                 // metade menor em cima
        return listOf(cima, n - cima)
    }

    /**
     * Quantas fotos em cada linha, no modo RETRATO.
     *
     * UMA COLUNA ATE 4 FOTOS, e a partir da quinta as linhas duplas vao sendo
     * abertas DE BAIXO PARA CIMA:
     *
     *   1..4 -> 1 por linha        5 -> 1,1,1,2      7 -> 1,2,2,2
     *                              6 -> 1,1,2,2      8 -> 2,2,2,2
     *
     * POR QUE NAO E A REGRA DA PAISAGEM: a folha em pe tem area alta e estreita,
     * e a foto e deitada. Uma coluna so da a cada foto a largura inteira da
     * folha — que e o maior tamanho possivel para uma foto 16:9 ali. Encher duas
     * colunas antes da hora reduz cada foto a metade da largura sem usar a
     * altura que sobra.
     *
     * DE BAIXO PARA CIMA porque a leitura da ficha comeca pelo alto: as
     * primeiras fotos, que sao as que mais se confere, ficam grandes, e o
     * aperto cai nas ultimas.
     *
     * Restaurada nesta forma depois de uma generalizacao que passou a encher
     * duas colunas de cima para baixo (5 fotos viravam 2,2,1). A regra por
     * quantidade nao era detalhe de implementacao: era o layout que a clinica
     * ja usava.
     */
    internal fun distribuicaoRetrato(qtdFotos: Int): List<Int> {
        val n = qtdFotos.coerceIn(1, GRID_COLS * GRID_ROWS)
        if (n <= GRID_ROWS) return List(n) { 1 }
        val duplas = (n - GRID_ROWS).coerceAtMost(GRID_ROWS)
        val simples = GRID_ROWS - duplas
        return List(simples) { 1 } + List(duplas) { 2 }
    }

    /**
     * Puro e sem tipos do Android de propósito: assim `PdfGridTest` verifica a
     * geometria na JVM, em segundos. O PDF era a parte mais delicada do app e a
     * única conferida só a olho.
     */
    internal fun calcularLayoutFotos(
        qtdFotos: Int, topo: Float, w: Float, h: Float,
        cols: Int, rows: Int, margem: Float = MARGIN, gap: Float = GAP_FOTO,
        /** Fotos por linha. Null = preenche as colunas de cima para baixo. Ver
         *  [distribuicaoPaisagem] e [distribuicaoRetrato]. */
        distribuicao: List<Int>? = null,
        /**
         * Limita a LARGURA da célula pela proporção da foto (16:9).
         *
         * Vale só na folha deitada. Lá, 2 fotos davam uma célula de 3:1 — a foto
         * encaixa pela altura e sobra faixa de papel vazio dos dois lados. Em
         * retrato o teto seria nocivo: a coluna única existe justamente para dar
         * à foto a largura inteira da folha, e limitá-la pela proporção
         * desfaria o ganho.
         *
         * Separado de [distribuicao] de propósito: as duas orientações passam
         * distribuição, e só uma quer o teto.
         */
        limitarPelaProporcao: Boolean = false
    ): List<Celula> {
        val n = qtdFotos.coerceIn(1, cols * rows)

        // Célula da grade cheia = referência de tamanho "normal".
        val baseW = (w - gap * (cols - 1)) / cols
        val baseH = (h - gap * (rows - 1)) / rows

        val porLinha = distribuicao ?: run {
            val r = (n + cols - 1) / cols
            (0 until r).map { minOf(cols, n - it * cols) }
        }
        val rowsUsadas = porLinha.size
        val colsUsadas = porLinha.maxOrNull() ?: 1

        var cellW = minOf((w - gap * (colsUsadas - 1)) / colsUsadas, baseW * MAX_AMPLIACAO)
        val cellH = minOf((h - gap * (rowsUsadas - 1)) / rowsUsadas, baseH * MAX_AMPLIACAO)

        // TETO PELA PROPORÇÃO DA FOTO, só quando a distribuição é dada (paisagem).
        // Sem isto, 2 fotos numa folha deitada davam uma célula de 3:1 — a foto
        // encaixa pela altura e sobra faixa de papel vazio dos dois lados, que é
        // exatamente a queixa. A célula ainda reserva a faixa da legenda.
        if (limitarPelaProporcao) {
            val alturaFoto = (cellH - LEGENDA_HEIGHT).coerceAtLeast(1f)
            cellW = minOf(cellW, alturaFoto * ASPECTO_FOTO)
        }

        val blocoH = cellH * rowsUsadas + gap * (rowsUsadas - 1)
        val y0 = topo + (h - blocoH) / 2f

        val cells = mutableListOf<Celula>()
        var restantes = n
        for ((linha, pedido) in porLinha.withIndex()) {
            // Última linha incompleta fica centralizada em vez de encostada
            // à esquerda (ex.: 5 fotos = 2 + 2 + 1 no retrato).
            val nestaLinha = minOf(pedido, restantes)
            if (nestaLinha <= 0) break
            restantes -= nestaLinha
            val larguraLinha = cellW * nestaLinha + gap * (nestaLinha - 1)
            val xLinha = margem + (w - larguraLinha) / 2f
            val y = y0 + linha * (cellH + gap)
            for (col in 0 until nestaLinha) {
                cells.add(Celula(xLinha + col * (cellW + gap), y, cellW, cellH))
            }
        }
        return cells
    }

    private fun celula(x: Float, y: Float, w: Float, h: Float) = RectF(x, y, x + w, y + h)

    private fun rotuloPara(indice: Int): String {
        // Por convenção: índice 0 = rosto, 1 = etiqueta, 2+ = posicionamentos
        // EM PORTUGUES, DE PROPOSITO: o que sai daqui passa por traduzirRotulo()
        // no ponto de desenho (desenharCelula), que mapeia "Rosto"/"Etiqueta"/
        // "Posicionamento" para pdf_lbl_*. Traduzir aqui faria o mapa nao casar
        // e o rotulo sairia no idioma errado — ou sem traducao nenhuma.
        return when (indice) {
            0 -> "Rosto"
            1 -> "Etiqueta"
            else -> "Posicionamento.${indice - 1}"
        }
    }

    private fun desenharCelula(canvas: Canvas, cell: RectF, arquivo: File, rotulo: String,
                               nomePaciente: String = "") {
        // Célula com fundo branco e borda cinza espessa.
        val paintBordaEspessa = Paint().apply {
            color = Color.parseColor("#BBBBBB"); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        val paintFundoBranco = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(cell, paintFundoBranco)
        canvas.drawRect(cell, paintBordaEspessa)

        // Área da imagem com pequeno inset, pra foto NÃO encostar/ultrapassar a borda.
        val inset = 3f
        val areaImg = RectF(cell.left + inset, cell.top + inset, cell.right - inset, cell.bottom - inset)

        val bitmap = decodeOrientado(arquivo, areaImg.width().toInt(), areaImg.height().toInt())
        if (bitmap != null) {
            desenharFotoSemDeformar(canvas, bitmap, areaImg)
            bitmap.recycle()
        }

        // Faixa de identificação INFERIOR: CINZA CLARA semi-transparente, SOBRE a foto
        // (não reduz o tamanho da foto). Texto em PRETO. Economiza tinta na impressão.
        val faixaH = 16f
        val faixaRect = RectF(cell.left + inset, cell.bottom - inset - faixaH,
                              cell.right - inset, cell.bottom - inset)
        val paintFaixa = Paint().apply {
            color = Color.parseColor("#CCEEEEEE")  // cinza bem claro ~80%
            style = Paint.Style.FILL
        }
        canvas.drawRect(faixaRect, paintFaixa)

        val paintRotulo = Paint().apply {
            color = Color.BLACK
            textSize = 8.5f
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            typeface = Typeface.SANS_SERIF
        }
        val paintNome = Paint().apply {
            color = Color.parseColor("#333333")
            textSize = 7.5f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.SANS_SERIF
        }
        val baselineY = faixaRect.bottom - 5f
        canvas.drawText(traduzirRotulo(rotulo), faixaRect.left + 5f, baselineY, paintRotulo)
        if (nomePaciente.isNotBlank()) {
            canvas.drawText(nomePaciente, faixaRect.right - 5f, baselineY, paintNome)
        }
    }

    /**
     * Desenha o bitmap com cantos levemente arredondados, no mesmo raio do
     * contorno da etiqueta ([RAIO_CANTO]).
     *
     * Usa clip de path em vez de bitmap pré-recortado: o PDF guarda o recorte
     * como vetor, então o canto sai liso em qualquer ampliação e a impressão
     * não ganha nenhum pixel a mais.
     */
    private fun drawBitmapArredondado(
        canvas: Canvas, bitmap: Bitmap, src: android.graphics.Rect?, dest: RectF, paint: Paint
    ) {
        val save = canvas.save()
        try {
            canvas.clipPath(Path().apply {
                addRoundRect(dest, RAIO_CANTO, RAIO_CANTO, Path.Direction.CW)
            })
            canvas.drawBitmap(bitmap, src, dest, paint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /**
     * Centraliza foto na célula sem deformar (fitCenter), preserva orientação EXIF.
     */
    private fun desenharFotoSemDeformar(canvas: Canvas, bitmap: Bitmap, area: RectF) {
        val ratioFoto = bitmap.width.toFloat() / bitmap.height.toFloat()
        val ratioCell = area.width() / area.height()

        val (drawW, drawH) = if (ratioFoto > ratioCell) {
            area.width() to (area.width() / ratioFoto)
        } else {
            (area.height() * ratioFoto) to area.height()
        }
        val cx = area.centerX()
        val cy = area.centerY()
        val drawRect = RectF(cx - drawW / 2, cy - drawH / 2, cx + drawW / 2, cy + drawH / 2)
        drawBitmapArredondado(canvas, bitmap, null, drawRect, paintFotoImpressao)
    }

    /**
     * Desenha uma foto grande centralizada (Ficha de acessórios).
     */
    private fun desenharFotoGrande(canvas: Canvas, arquivo: File) {
        val topo = MARGIN + 90f
        val baixo = PAGE_HEIGHT - 30f
        val area = RectF(MARGIN, topo, PAGE_WIDTH - MARGIN, baixo)

        val bitmap = decodeOrientado(arquivo, area.width().toInt(), area.height().toInt()) ?: return
        try {
            desenharFotoSemDeformar(canvas, bitmap, area)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodifica bitmap respeitando a orientação EXIF e com sample size adequado.
     */
    private fun decodeOrientado(arquivo: File, larguraDestino: Int, alturaDestino: Int): Bitmap? {
        return try {
            val opcoesProbe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(arquivo.absolutePath, opcoesProbe)
            val origW = opcoesProbe.outWidth
            val origH = opcoesProbe.outHeight
            if (origW <= 0 || origH <= 0) return null

            var sample = 1
            while (origW / sample > larguraDestino * 2 && origH / sample > alturaDestino * 2) sample *= 2

            val opcoes = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var bitmap = BitmapFactory.decodeFile(arquivo.absolutePath, opcoes) ?: return null
            // Padrão do produto: imagens SEMPRE na horizontal dentro do PDF.
            // Se a foto veio em pé (retrato), deita 90° antes de posicionar na célula.
            if (bitmap.height > bitmap.width) {
                val mrot = android.graphics.Matrix().apply { postRotate(90f) }
                val deitado = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mrot, true)
                if (deitado != bitmap) bitmap.recycle()
                bitmap = deitado
            }

            // Aplicar rotação EXIF
            val orientacao = try {
                ExifInterface(arquivo.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

            val rotacao = when (orientacao) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotacao != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotacao) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                bitmap = rotated
            }
            bitmap
        } catch (e: Exception) { null }
    }

    /**
     * Rodape de TODA pagina numerada da ficha — Time-Out, fotos, rubricario,
     * rubricario avulso e as paginas do protocolo, frente e verso:
     *  - "<Nome da Clinica> • Pagina n" (quando a clinica esta informada)
     *  - "Pagina n" (caso contrario)
     *
     * n e a posicao da pagina no arquivo, contadas as folhas em branco de
     * pareamento (que nao levam rodape): e o numero que a impressora e o
     * dialogo de impressao mostram.
     *
     * Centralizado na largura FISICA da pagina: quem chama desenha fora do
     * recuo de furacao (ver os restoreToCount antes de cada chamada).
     */
    private fun desenharRodape(canvas: Canvas, numeroPagina: Int,
                                nomeClinica: String = "",
                                pageW: Int = PAGE_WIDTH, pageH: Int = PAGE_HEIGHT) {
        val paint = Paint().apply {
            color = Color.parseColor("#666666")
            textSize = 9f
            isAntiAlias = true
        }
        val texto = textoRodape(nomeClinica, String.format(txtPagina, numeroPagina))
        // Nome de clínica muito longo encolhe o rodapé para caber entre as
        // margens, em vez de passar da borda do papel.
        paint.textSize = corpoRodape(paint.measureText(texto), pageW - MARGIN * 2)
        val w = paint.measureText(texto)
        canvas.drawText(texto, (pageW - w) / 2f, (pageH - 12).toFloat(), paint)
    }

    /** Corpo do rodapé: 9 pt, ou o que couber em [larguraMax], com piso de 6,5 pt. */
    internal fun corpoRodape(larguraEm9pt: Float, larguraMax: Float): Float =
        if (larguraEm9pt <= larguraMax || larguraEm9pt <= 0f) 9f
        else (9f * larguraMax / larguraEm9pt).coerceAtLeast(6.5f)

    /**
     * Texto do rodape: clinica e pagina separadas por " • " (U+2022, um espaco
     * de cada lado). Sem clinica, so a pagina. Puro, testado na JVM, e usado
     * tambem pela previa da calibracao do protocolo.
     */
    internal fun textoRodape(nomeClinica: String, paginaFormatada: String): String {
        val clinica = nomeClinica.trim()
        return if (clinica.isEmpty()) paginaFormatada.trim()
        else "$clinica \u2022 ${paginaFormatada.trim()}"
    }
}
