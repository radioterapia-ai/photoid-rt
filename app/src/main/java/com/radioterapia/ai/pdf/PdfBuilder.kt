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
     *  para as fotos, para os dois não saírem do mesmo desenho por descuido. */
    private const val RAIO_CANTO = 10f
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
            if (desenharSimulacao(doc, context, item, logo)) marcarFrenteVerso(arquivoSaida)
            FileOutputStream(arquivoSaida).use { saida -> doc.writeTo(saida) }
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
     */
    fun gerarLoteAgrupado(context: Context, itens: List<ItemLote>, arquivoSaida: File): File? {
        if (itens.isEmpty()) return null
        val doc = PdfDocument()
        val logo = LogoManager(context).obterBitmap()
        var desenhadas = 0
        try {
            for (item in itens) {
                try {
                    if (desenharSimulacao(doc, context, item, logo))
                        marcarFrenteVerso(arquivoSaida)
                    desenhadas++
                } catch (_: Exception) {
                    // Uma simulação com foto corrompida não derruba o lote inteiro.
                }
            }
            if (desenhadas == 0) return null
            FileOutputStream(arquivoSaida).use { saida -> doc.writeTo(saida) }
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
        // Altura PROPORCIONAL ao numero de linhas. Era fixa em 54, dimensionada
        // para tres linhas; com as observacoes reduzidas a duas, sobrava uma
        // faixa de papel vazio dentro da caixa e o grid de fotos comecava mais
        // abaixo do que precisava — na folha deitada isso empurrava as fotos
        // para o centro e deixava a caixa parecendo deslocada.
        obsBandHeight = if (observacoesTexto.isBlank()) 0f else {
            val nLinhas = observacoesTexto.split("\n")
                .count { it.isNotBlank() }.coerceIn(1, 2)
            18f + nLinhas * 12f
        }

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
                    item.observacoes, item.etiquetaLarguraMm, item.etiquetaAlturaMm)
            } catch (_: Exception) { /* nunca bloqueia a folha de fotos */ }
        }

        for (pagina in 0 until totalPaginas) {
            val numeroPagina = doc.pages.size + 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, numeroPagina).create()
            val page = doc.startPage(info)
            val canvas = page.canvas
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

        desenharRodape(cv, doc.pages.size + 1, dados.nomeClinica)
        doc.finishPage(page)
        PAGE_WIDTH = pwSalvo
        PAGE_HEIGHT = phSalvo
    }

    /**
     * Paginas do protocolo, no fim da ficha.
     *
     * O TOTAL do rodape e calculado ANTES de desenhar, somando as paginas ja
     * feitas com as que o protocolo vai acrescentar. Sem isso o "n de N" das
     * paginas do protocolo diria um total que ainda nao existe — e a folha
     * anexada contradiria a que a antecede.
     */
    /** @return `true` se alguma folha do protocolo tem verso. */
    private fun desenharProtocolo(
        doc: PdfDocument, context: Context, dados: DadosCabecalho,
        item: ItemLote, logo: android.graphics.Bitmap?
    ): Boolean {
        val store = com.radioterapia.ai.protocolo.ProtocoloStore(context)
        val prot = store.obter(item.protocoloId) ?: return false
        if (prot.paginas.isEmpty()) return false

        val jaFeitas = doc.pages.size

        /*
            A CONTA TEM DE INCLUIR O VERSO E A FOLHA DE AJUSTE.

            O rodape diz "n de N", e o N e calculado aqui, antes de desenhar. A
            simulacao abaixo repete a MESMA regra de pareamento do
            ProtocoloRenderer: quando uma folha tem verso e a frente cairia em
            posicao par, entra uma pagina em branco antes. Contar so as frentes
            faria a ficha anunciar um total menor do que ela tem — e num
            documento clinico o rodape que erra o total e pior que rodape nenhum.
         */
        var posicao = jaFeitas
        var aAcrescentar = 0
        var temVerso = false
        prot.paginas.forEach { pag ->
            val nFrente = store.arquivoPagina(prot, pag)
                ?.let { com.radioterapia.ai.pdf.PdfPaginas.contar(it) } ?: 0
            if (nFrente == 0) return@forEach
            val nVerso = store.arquivoVerso(prot, pag)
                ?.let { com.radioterapia.ai.pdf.PdfPaginas.contar(it) } ?: 0
            if (nVerso > 0) {
                temVerso = true
                if ((posicao + 1) % 2 == 0) { aAcrescentar++; posicao++ }
            }
            aAcrescentar += nFrente + nVerso
            posicao += nFrente + nVerso
        }
        if (aAcrescentar == 0) return false

        val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        com.radioterapia.ai.protocolo.ProtocoloRenderer.desenhar(
            doc, context, prot,
            com.radioterapia.ai.protocolo.ProtocoloRenderer.Identificacao(
                nome = dados.nomePaciente,
                nascimento = dados.nascimento,
                prontuario = dados.prontuario,
                dataSimulacao = fmt.format(dados.dataSimulacao)),
            logo,
            numeroInicial = jaFeitas + 1,
            totalDaFicha = jaFeitas + aAcrescentar)
        return temVerso
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
        etiquetaAlturaMm: Int
    ) {
        val pw = 595; val ph = 842
        val numeroPagina = doc.pages.size + 1
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, numeroPagina).create())
        val cv = page.canvas
        cv.translate(margemExtraEsqPt, margemExtraTopoPt)
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
        val fimObs = ph - MARGIN
        val obsTop = fimObs - 66f
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
                val ladoSimNao = 10f
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
        run {
            // DUAS linhas, nao tres. A terceira era o que empurrava o
            // cabecalho e provocava o corte com etiqueta 100x50 mm. A
            // altura liberada foi para o espaco de seguranca acima das
            // caixas de equipamento e sitio.
            val linhas = observacoes.split("\n").map { it.trim() }.filter { it.isNotBlank() }.take(2)
            if (linhas.isNotEmpty()) {
                val corpoTopo = obsTop + 15f
                val lineH = 13f
                // Primeira linha no topo do corpo; centraliza só na horizontal.
                var y = corpoTopo + 13f
                for (l2 in linhas) {
                    texto(l2.take(110), (mL + mR) / 2f, y, 8.5f, align = Paint.Align.CENTER)
                    y += lineH
                }
            }
        }

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

        if (semPaciente) {
            // Modelo em branco: nada de paciente entra. O bloco fica vazio de
            // proposito, e o titulo a direita sozinho diz o que a folha e.
        } else {
            // MESMO BLOCO DA FICHA DE TIME-OUT: quadro com os dados dentro e a
            // linha de identificacao sob ele. As duas folhas saem juntas e sao
            // conferidas lado a lado — cada diferenca de arranjo entre elas
            // custa uma leitura a mais de quem separa as fichas.
            val etqW = larguraCaixaEtiqueta(etiqLargPt)
            val etqH = alturaCaixaEtiqueta(etiqAltPt)
            desenharEtiquetaVirtual(canvas,
                RectF(MARGIN, topo, MARGIN + etqW, topo + etqH), dados)
            desenharLinhaIdentificacao(canvas, MARGIN, topo + etqH + 12f,
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
        val tituloBlocoTopo = baseHeader - 50f
        val logoTopo = topo + 4f
        val logoAreaBase = tituloBlocoTopo - 8f

        desenharLogoPadrao(canvas, logo, rectDir.right, logoTopo, colDirW * 0.95f)

        val paintTitulo = Paint().apply {
            color = Color.parseColor("#333333"); textSize = 16f
            isFakeBoldText = true; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        // Titulo de UMA linha (rubricario) fica no meio da faixa das duas, e
        // nao na posicao da primeira: desenhado em -34 com nada em -18, ele
        // pareceria descolado para cima do bloco do logo.
        if (titulo2.isBlank()) {
            canvas.drawText(titulo1, rectDir.centerX(), baseHeader - 24f, paintTitulo)
        } else {
            canvas.drawText(titulo1, rectDir.centerX(), baseHeader - 34f, paintTitulo)
            canvas.drawText(titulo2, rectDir.centerX(), baseHeader - 18f, paintTitulo)
        }



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

    /** Altura do logo, a MESMA em todas as paginas. Ver desenharLogoPadrao. */
    /**
     * Fichas desta sessao que contem folha frente-e-verso de protocolo.
     *
     * O caminho de impressao recebe so o File, e a essa altura nao ha mais como
     * saber que protocolo gerou aquele PDF. Este conjunto responde isso sem
     * arrastar o id do protocolo por cinco assinaturas de funcao.
     *
     * VIVE EM MEMORIA, e o degrade e deliberado: reimprimir do Historico depois
     * de reabrir o app cai na configuracao de duplex da impressora, que e o
     * comportamento de sempre. Melhor perder o automatismo numa reimpressao do
     * que gravar estado em disco para um dado que so vale por alguns minutos.
     */
    private val fichasFrenteVerso =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun marcarFrenteVerso(arquivo: File) {
        try { fichasFrenteVerso.add(arquivo.absolutePath) } catch (_: Exception) {}
    }

    fun temFrenteVerso(arquivo: File): Boolean =
        try { fichasFrenteVerso.contains(arquivo.absolutePath) } catch (_: Exception) { false }

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
        val partes = mutableListOf<Pair<String, String>>()
        if (dados.nomePaciente.isNotBlank()) partes.add(txtPaciente to dados.nomePaciente)
        if (dados.nascimento.isNotBlank()) partes.add(txtNascimento to dados.nascimento)
        if (dados.prontuario.isNotBlank()) partes.add(txtRegistro to dados.prontuario)
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
        val sep = "  ·  "

        // Encolhe proporcionalmente ate caber, com PISO: abaixo de 72% a linha
        // deixa de ser legivel no papel, que e onde ela e lida. Se nem assim
        // couber, o nome — o campo mais longo e o unico que tambem aparece no
        // quadro — e truncado, e nao os demais.
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
        var escala = 1f
        while (escala > 0.72f && medir(escala) > largura) escala -= 0.04f
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
    private fun quebrarLinhas(texto: String, paint: Paint, maxW: Float): List<String> {
        val palavras = texto.split(" ").filter { it.isNotBlank() }
        val linhas = mutableListOf<String>()
        var atual = ""
        for (p in palavras) {
            val tent = if (atual.isEmpty()) p else "$atual $p"
            if (paint.measureText(tent) <= maxW) { atual = tent; continue }
            if (atual.isNotEmpty()) { linhas.add(atual); atual = "" }
            if (paint.measureText(p) <= maxW) { atual = p; continue }
            var resto = p                       // palavra sozinha maior que a caixa
            while (resto.isNotEmpty()) {
                var corte = resto.length
                while (corte > 1 && paint.measureText(resto.substring(0, corte)) > maxW) corte--
                linhas.add(resto.substring(0, corte)); resto = resto.substring(corte)
            }
        }
        if (atual.isNotEmpty()) linhas.add(atual)
        return linhas
    }

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
     */
    private fun desenharEtiquetaVirtual(canvas: Canvas, rect: RectF, dados: DadosCabecalho) {
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

        // CABE OU ENCOLHE. O conjunto e reduzido ate caber na altura do quadro,
        // com piso de legibilidade: nome a 7 pt, identificacoes a 6 pt. Abaixo
        // disso o papel impresso nao se le, e reduzir mais so esconderia o
        // problema — por isso, no piso, o que nao couber e CORTADO em vez de
        // transbordar. O que se perde aqui sobrevive na linha logo abaixo do
        // quadro, que traz nome, nascimento, registro e data.
        var corpoNome = 12f
        var corpoId = 8.5f
        var (linhas, totalH) = montar(corpoNome, corpoId)
        while (totalH > maxH && corpoNome > 7f) {
            corpoNome -= 0.5f
            corpoId = (corpoId - 0.35f).coerceAtLeast(6f)
            val r = montar(corpoNome, corpoId)
            linhas = r.first; totalH = r.second
        }
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
     * Faixa de Observações (estilo da ficha clínica): rótulo "Observações" com
     * fundo cinza à esquerda + caixa com o texto centralizado (até 2 linhas) à direita.
     */
    private fun desenharBandaObservacoes(canvas: Canvas, yTop: Float) {
        val xEsq = MARGIN
        val larguraTotal = PAGE_WIDTH - MARGIN * 2
        val larguraRotulo = 95f
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
            color = Color.BLACK; textSize = 9.5f; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        // Respeita as quebras de linha digitadas pelo usuário (\n) e, se ainda for
        // largo demais, quebra por largura — no máximo 2 linhas no total.
        val linhas = mutableListOf<String>()
        for (linhaBruta in observacoesTexto.split("\n")) {
            if (linhas.size >= 3) break
            val restante = 3 - linhas.size
            linhas.addAll(quebrarSeNecessario(linhaBruta, paintObs, rectBox.width() - 12f, restante))
        }
        val linhasFinal = linhas.take(2)   // ver comentario acima: 3 -> 2
        // Primeira linha no TOPO do box (não centraliza na vertical); centro só na horizontal.
        var ty = rectBox.top + 15f
        linhasFinal.forEach { canvas.drawText(it, rectBox.centerX(), ty, paintObs); ty += 12f }
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
        val areaBase = PAGE_HEIGHT - 28f
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
     * Rodapé do PDF.
     * Sem versão "Radioterapia.AI v.X.X" - apenas:
     *  - "<Nome da Clínica> • Página X / Y" (quando clínica informada)
     *  - "Página X / Y" (caso contrário)
     */
    private fun desenharRodape(canvas: Canvas, numeroPagina: Int,
                                nomeClinica: String = "",
                                pageW: Int = PAGE_WIDTH, pageH: Int = PAGE_HEIGHT) {
        val paint = Paint().apply {
            color = Color.parseColor("#666666")
            textSize = 9f
            isAntiAlias = true
        }
        val pag = String.format(txtPagina, numeroPagina)
        val texto = if (nomeClinica.isNotBlank()) "$nomeClinica  ●  $pag" else pag
        val w = paint.measureText(texto)
        canvas.drawText(texto, (pageW - w) / 2f, (pageH - 12).toFloat(), paint)
    }

    private fun quebrarSeNecessario(texto: String, paint: Paint, larguraMax: Float, maxLinhas: Int): List<String> {
        if (paint.measureText(texto) <= larguraMax) return listOf(texto)
        val palavras = texto.split(" ")
        val linhas = mutableListOf<StringBuilder>()
        linhas.add(StringBuilder())
        for (p in palavras) {
            val l = linhas.last()
            val tentativa = if (l.isEmpty()) p else "$l $p"
            if (paint.measureText(tentativa) <= larguraMax) {
                if (l.isEmpty()) l.append(p) else { l.append(" "); l.append(p) }
            } else {
                if (linhas.size >= maxLinhas) {
                    // Trunca
                    val ultimo = linhas.last().toString() + " …"
                    linhas[linhas.size - 1] = StringBuilder(ultimo)
                    return linhas.map { it.toString() }
                }
                linhas.add(StringBuilder(p))
            }
        }
        return linhas.map { it.toString() }
    }
}
