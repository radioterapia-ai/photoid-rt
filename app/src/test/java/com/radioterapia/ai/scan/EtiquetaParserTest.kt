package com.radioterapia.ai.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leitura da etiqueta do paciente.
 *
 * O que o ML Kit entrega e texto solto, com as linhas na ordem em que ele as
 * encontrou e sem garantia nenhuma de pontuacao. O parser decide qual linha e
 * o nome — e errar aqui significa a ficha inteira sair com o paciente errado.
 *
 * Os casos reproduzem etiquetas reais e as variacoes delas que o parser
 * precisa distinguir: rotulo de outro campo, sobrenome que parece rotulo,
 * numero e data que pertencem a outro rotulo.
 */
class EtiquetaParserTest {

    // ---------- rotulo de campo nunca e nome ----------

    /**
     * O defeito relatado, exatamente como acontecia.
     *
     * A etiqueta traz "PACIENTE: MARIA DA SILVA". O criterio do rotulo
     * explicito resolveria isso — se o OCR lesse os dois-pontos. Dois-pontos e
     * um sinal fino e o ML Kit o perde com frequencia; sobra "PACIENTE" numa
     * linha sozinha, que tem oito letras, nenhum digito e 100% de letras, e
     * por isso passava em pareceNome() e virava o nome do paciente.
     */
    @Test
    fun `rotulo sozinho nao vira nome quando o OCR perde os dois-pontos`() {
        val ocr = "PACIENTE\nMARIA DA SILVA\nPRONTUARIO 123456"
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome(ocr))
    }

    @Test
    fun `com os dois-pontos continua funcionando`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("PACIENTE: MARIA DA SILVA\n123456"))
    }

    @Test
    fun `rotulo colado ao nome, sem separador`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("PACIENTE MARIA DA SILVA\n123456"))
    }

    /**
     * A etiqueta e impressa pelo sistema do hospital, no idioma DELE — que nao
     * e necessariamente o idioma em que o tablet esta configurado. Um servico
     * em Londres e um em Berlim imprimem rotulos diferentes, e o app e
     * universal.
     */
    @Test
    fun `rotulo em outros idiomas tambem e ignorado`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("PATIENT\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOMBRE\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("PAZIENTE\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("PATIENTENNAME\nMARIA DA SILVA"))
    }

    /**
     * A lista nao pode virar "recusa qualquer coisa": nome de paciente que
     * CONTENHA uma dessas palavras tem que continuar passando. So a linha
     * inteira sendo o rotulo e que e recusada.
     */
    @Test
    fun `nome que contem palavra de rotulo continua valendo`() {
        assertTrue(EtiquetaParser.extrairNome("MARIA NOME DA SILVA").isNotEmpty())
        assertEquals("ANA PAULA NASCIMENTO",
            EtiquetaParser.extrairNome("ANA PAULA NASCIMENTO\n99887"))
    }

    // ---------- nome: rotulo de outro campo recusa a linha inteira ----------

    /**
     * Depois de rotulo de medico vem o nome real de OUTRA pessoa, que passa em
     * qualquer teste de nome; descolar o rotulo e ficar com o resto poria o
     * medico no cadastro do paciente. Caixa vazia.
     */
    @Test
    fun `MEDICO FULANO DE TAL sozinho nao vira nome`() {
        assertEquals("", EtiquetaParser.extrairNome("MEDICO FULANO DE TAL"))
    }

    @Test
    fun `linha do medico antes do nome e pulada`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("MEDICO FULANO DE TAL\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("MEDICO FULANO DE TAL\nMARIA DA SILVA\nPRONTUARIO 123456"))
    }

    @Test
    fun `linha do medico depois do nome nao entra na juncao`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("MARIA DA SILVA\nMEDICO FULANO DE TAL"))
    }

    @Test
    fun `variantes do rotulo de medico`() {
        val variantes = listOf(
            "MÉDICO FULANO DE TAL", "MEDICO: FULANO DE TAL", "MÉDICO:FULANO DE TAL",
            "Médico Fulano de Tal", "MEDICA FULANA DE TAL", "MÉDICO(A): FULANO DE TAL",
            "DR. FULANO DE TAL", "DR FULANO DE TAL", "DRA FULANA DE TAL",
            "MEDICO RESPONSAVEL FULANO DE TAL", "NOME DO MEDICO: FULANO DE TAL",
            "CRM 123456 FULANO",
        )
        for (v in variantes) {
            assertEquals(v, "MARIA DA SILVA", EtiquetaParser.extrairNome("$v\nMARIA DA SILVA"))
            assertEquals(v, "", EtiquetaParser.extrairNome(v))
        }
    }

    /** Rotulos de outros campos, sozinhos ou com valor, inclusive com erro de OCR ("NASSCIMETO."). */
    @Test
    fun `rotulos de outros campos nunca viram nome`() {
        val rotulos = listOf(
            "IDADE 63 ANOS", "SEXO FEMININO", "GENERO FEMININO", "GÊNERO: FEMININO",
            "CONVENIO PARTICULAR", "CONVÊNIO: PARTICULAR", "PRONTUARIO", "PRONTUÁRIO:",
            "DATA DE NASC", "DATA DE NASC.", "DATA DE NASCIMENTO", "NASCIMENTO",
            "NASSCIMETO.", "REGISTRO", "REGISTRO:",
        )
        for (r in rotulos) {
            assertEquals(r, "MARIA DA SILVA", EtiquetaParser.extrairNome("$r\nMARIA DA SILVA"))
            assertEquals(r, "", EtiquetaParser.extrairNome(r))
        }
    }

    @Test
    fun `NASSCIMETO com erro de OCR nao vira nome`() {
        assertEquals("", EtiquetaParser.extrairNome("NASSCIMETO."))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("NASSCIMETO.\nMARIA DA SILVA"))
    }

    @Test
    fun `mae e responsavel sao outra pessoa`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME DA MÃE: JOANA DA SILVA\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("MAE: JOANA DA SILVA\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("RESPONSAVEL JOAO SOUZA\nMARIA DA SILVA"))
    }

    @Test
    fun `segunda linha com rotulo nao se junta ao nome`() {
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA\nSEXO FEMININO"))
    }

    /** O OCR junta numa linha o que a etiqueta imprime lado a lado. */
    @Test
    fun `rotulo no meio da linha corta o nome`() {
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA SEXO: F"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA MEDICO: JOAO PEREIRA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA SEXO F 12/03/1960"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PACIENTE: MARIA DA SILVA SEXO: F"))
    }

    /** Com uma palavra so de nome antes do rotulo, melhor vazio que "MARIA SEXO: F". */
    @Test
    fun `rotulo do nome com uma palavra e outro rotulo fica vazio`() {
        assertEquals("", EtiquetaParser.extrairNome("PACIENTE: MARIA SEXO: F"))
    }

    /**
     * Rotulo no fim da linha do nome, com o valor na linha de baixo
     * ("MARIA DA SILVA MAE:" e "JOANA DA SILVA"). O corte tira o rotulo e os
     * dois-pontos, e com eles o que impedia a juncao: a linha de baixo e o
     * valor dele, e colada ao nome passaria por um nome comprido normal.
     */
    @Test
    fun `rotulo cortado no fim da linha nao cola o valor dele ao nome`() {
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA MÃE:\nJOANA DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA MEDICO:\nJOAO PEREIRA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA PAI:\nJOSE DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA NOME DA MÃE:\nJOANA DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA ACOMPANHANTE:\nJOANA DA SILVA"))
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("JOHN SMITH MOTHER:\nJANE SMITH"))
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("JOHN SMITH DOCTOR:\nJANE DOE"))
        assertEquals("MARIA DA SILVA SANTOS",
            EtiquetaParser.extrairNome("MARIA DA SILVA SANTOS CONVÊNIO:\nBRADESCO SAUDE"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA LEITO:\nONCOLOGIA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA PRONT:\nSOUZA"))
        // O valor do rotulo cortado tambem nao faz o papel da linha 1.
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("SEXO: F MAE:\nJOANA DA SILVA\nMARIA DA SILVA"))
        // Nome que quebra antes do rotulo da coluna ao lado continua na linha de baixo.
        assertEquals("MARIA APARECIDA DO NASCIMENTO",
            EtiquetaParser.extrairNome("MARIA APARECIDA DO SEXO: F\nNASCIMENTO"))
    }

    @Test
    fun `sobrenomes parecidos com rotulo continuam sendo nome`() {
        val nomes = listOf(
            "MEDINA SOUZA", "MEDICI LORENZO", "SEXTON JAMES", "REGIS PEREIRA", "IDALINA COSTA",
            "REGINA DA SILVA", "MAE JEMISON", "NASCIMENTO, ANA PAULA", "MATEUS SOUZA",
            "LEITAO MARCOS", "NASCIMBENI MARIO", "PATIENCE OKAFOR", "CASTELLA MARIO",
        )
        for (n in nomes) assertEquals(n, n, EtiquetaParser.extrairNome(n))
    }

    /** Ordem sobrenome-primeiro: o sobrenome que tambem e rotulo nao some. */
    @Test
    fun `sobrenome que e rotulo ambiguo no comeco da linha fica`() {
        assertEquals("NASCIMENTO ANA PAULA", EtiquetaParser.extrairNome("NASCIMENTO ANA PAULA"))
        assertEquals("ALTER ANNA MARIA", EtiquetaParser.extrairNome("ALTER ANNA MARIA"))
    }

    /** CASTELLA esta a uma letra de CARTELLA, e por isso CARTELLA so casa exato. */
    @Test
    fun `sobrenome sozinho na segunda linha se junta ao nome`() {
        assertEquals("MARIO CASTELLA", EtiquetaParser.extrairNome("MARIO\nCASTELLA"))
        assertEquals("MARIO NASCIMBENI", EtiquetaParser.extrairNome("MARIO\nNASCIMBENI"))
        assertEquals("GRACE PATIENCE", EtiquetaParser.extrairNome("GRACE\nPATIENCE"))
    }

    @Test
    fun `rotulo do nome e descolado e o valor conferido`() {
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PACIENTF: MARIA DA SILVA"))
        assertEquals("MARIA DA SILVA-SOUZA",
            EtiquetaParser.extrairNome("PACIENTE: MARIA DA SILVA-SOUZA"))
        assertEquals("", EtiquetaParser.extrairNome("PACIENTE MARIA"))
        assertEquals("", EtiquetaParser.extrairNome("PACIENTE:\nMEDICO JOAO PEREIRA"))
    }

    @Test
    fun `valor descolado mantem acento e caixa`() {
        assertEquals("Maria Conceição da Silva",
            EtiquetaParser.extrairNome("Paciente: Maria Conceição da Silva"))
    }

    @Test
    fun `cabecalho do hospital antes do rotulo do nome`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("HOSPITAL SAO LUCAS\nPACIENTE MARIA DA SILVA"))
    }

    @Test
    fun `rotulo de medico nos idiomas latinos`() {
        assertEquals("MARY JONES", EtiquetaParser.extrairNome("PHYSICIAN: JOHN SMITH\nMARY JONES"))
        assertEquals("MARY JONES", EtiquetaParser.extrairNome("DOCTOR: JOHN SMITH\nMARY JONES"))
        assertEquals("MARIA LOPEZ GARCIA",
            EtiquetaParser.extrairNome("MÉDICO TRATANTE: JUAN PEREZ\nMARIA LOPEZ GARCIA"))
        assertEquals("MARIE CLAIRE MARTIN",
            EtiquetaParser.extrairNome("MÉDECIN: JEAN DUPONT\nMARIE CLAIRE MARTIN"))
        assertEquals("ANNA SCHMIDT",
            EtiquetaParser.extrairNome("BEHANDELNDER ARZT: HANS MULLER\nANNA SCHMIDT"))
        assertEquals("GIULIA BIANCHI",
            EtiquetaParser.extrairNome("MEDICO CURANTE: MARIO ROSSI\nGIULIA BIANCHI"))
        assertEquals("ANNA NOWAK",
            EtiquetaParser.extrairNome("LEKARZ PROWADZĄCY: JAN KOWALSKI\nANNA NOWAK"))
        assertEquals("ANNA NOWAK", EtiquetaParser.extrairNome("PŁEĆ: K\nANNA NOWAK"))
    }

    @Test
    fun `rotulo ambiguo com evidencia e pulado`() {
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("WARD: 5\nJOHN SMITH"))
        assertEquals("ANNA NOWAK", EtiquetaParser.extrairNome("SALA: 3\nANNA NOWAK"))
    }

    /** NOME SOCIAL nao e sugerido: o nome civil mantem a pasta igual a do sistema do hospital. */
    @Test
    fun `nome social nao e sugerido`() {
        assertEquals("JOAO DA SILVA",
            EtiquetaParser.extrairNome("NOME SOCIAL: JOANA SOUZA\nNOME CIVIL: JOAO DA SILVA"))
        assertEquals("", EtiquetaParser.extrairNome("NOME SOCIAL: JOANA SOUZA"))
        assertEquals("", EtiquetaParser.extrairNome("NOME SOCIAL\nJOANA SOUZA"))
    }

    /**
     * Rotulo sozinho numa linha e o valor na de baixo: o nome do medico nao
     * pode virar a linha 1, nem se juntar ao nome do paciente.
     */
    @Test
    fun `linha abaixo de rotulo sozinho de outra pessoa nao e nome`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("MÉDICO:\nFULANO DE TAL\nMARIA DA SILVA"))
        assertEquals("", EtiquetaParser.extrairNome("MÉDICO:\nFULANO DE TAL"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("DR.\nFULANO DE TAL\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME DA MÃE\nJOANA SOUZA\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("ALERGIA:\nDIPIRONA\nMARIA DA SILVA"))
        // Rotulo de numero ou de data sozinho nao toma a linha de texto de baixo.
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PRONTUARIO\nMARIA DA SILVA"))
    }

    /**
     * Debaixo do rotulo sozinho de convenio, plano, seguro, leito ou
     * enfermaria vem o valor dele, e ele pode ser texto (UNIMED, SUS,
     * ONCOLOGIA): essa linha nao faz o papel da linha 1 nem se cola ao nome.
     */
    @Test
    fun `valor de operadora ou de unidade debaixo do rotulo sozinho nao entra no nome`() {
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("CONVÊNIO:\nUNIMED\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA SANTOS", EtiquetaParser.extrairNome("CONVENIO\nSUS\nMARIA DA SILVA SANTOS"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PLANO:\nAMIL\nMARIA DA SILVA"))
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("INSURANCE:\nBLUE CROSS\nJOHN SMITH"))
        assertEquals("MARIA DA SILVA SANTOS", EtiquetaParser.extrairNome("LEITO\nONCOLOGIA\nMARIA DA SILVA SANTOS"))
        assertEquals("MARIA DA SILVA SANTOS",
            EtiquetaParser.extrairNome("ENFERMARIA:\nONCOLOGIA\nMARIA DA SILVA SANTOS"))
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("WARD:\nONCOLOGY\nJOHN SMITH"))
        // Rotulo que so possui numero continua sem tomar a linha de texto.
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("CPF\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("IDADE\nMARIA DA SILVA"))
    }

    /** Rotulo do nome + ligacao + outro rotulo: quem manda e o segundo. */
    @Test
    fun `rotulo composto fora do vocabulario e do segundo rotulo`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME DO RESPONSAVEL JOAO SOUZA\nMARIA DA SILVA"))
        assertEquals("", EtiquetaParser.extrairNome("NOME DO RESPONSAVEL JOAO SOUZA"))
        assertEquals("", EtiquetaParser.extrairNome("NOMBRE DEL MEDICO JUAN PEREZ"))
        assertEquals("", EtiquetaParser.extrairNome("NAME OF THE PHYSICIAN JOHN SMITH"))
        assertEquals("", EtiquetaParser.extrairNome("NOME DO CONVENIO UNIMED"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME COMPLETO DO PACIENTE: MARIA DA SILVA"))
        // Nome de verdade depois do rotulo continua valendo.
        assertEquals("ANA PAULA SOUZA", EtiquetaParser.extrairNome("PACIENTE ANA PAULA SOUZA"))
    }

    /**
     * Nome quebrado depois de particula: nome nenhum termina em DO, DA, DOS ou
     * DE, entao a linha seguinte e a continuacao, mesmo sendo uma palavra so
     * que tambem e rotulo ambiguo (NASCIMENTO, e o erro de OCR dele). Rotulo
     * sem ambiguidade nao continua nome nenhum.
     */
    @Test
    fun `nome quebrado depois de particula continua na linha seguinte`() {
        assertEquals("ANA PAULA DO NASSCIMETO", EtiquetaParser.extrairNome("ANA PAULA DO\nNASSCIMETO"))
        assertEquals("MARIA APARECIDA DO NASCIMENTO",
            EtiquetaParser.extrairNome("MARIA APARECIDA DO\nNASCIMENTO"))
        assertEquals("ANA PAULA DO NASCIMENTO",
            EtiquetaParser.extrairNome("PACIENTE: ANA PAULA DO\nNASCIMENTO"))
        assertEquals("MARIA DA SILVA SOUZA", EtiquetaParser.extrairNome("PACIENTE: MARIA DA\nSILVA SOUZA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PACIENTE:\nMARIA DA\nSILVA"))
        assertEquals("MARIA DA", EtiquetaParser.extrairNome("MARIA DA\nPRONTUARIO"))
    }

    /** DA, DE e DU também fecham nome chinês: a linha de outra pessoa não é continuação. */
    @Test
    fun `particula no fim nao cola a linha de outra pessoa`() {
        assertEquals("LI DA", EtiquetaParser.extrairNome("LI DA\nDOCTOR JOHN SMITH"))
        assertEquals("LI DA", EtiquetaParser.extrairNome("PACIENTE: LI DA\nMAE JOANA SILVA"))
        assertEquals("MARIA APARECIDA DO",
            EtiquetaParser.extrairNome("MARIA APARECIDA DO\nPAI JOSE DA SILVA"))
    }

    // ---------- etiqueta impressa em tabela ----------

    /**
     * Rotulos numa coluna e valores em outra: o ML Kit entrega todos os
     * rotulos e depois todos os valores. A linha abaixo do ULTIMO rotulo e o
     * valor do PRIMEIRO; toma-la como valor do rotulo de cima punha o nome da
     * mae ou do medico na caixa do paciente.
     */
    @Test
    fun `etiqueta em tabela - o nome sai do rotulo do nome pela posicao`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("Paciente:\nMãe:\nMARIA DA SILVA\nJOANA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("Mãe:\nPaciente:\nJOANA DA SILVA\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("Nome:\nNasc:\nMãe:\nMARIA DA SILVA\n12/03/1960\nJOANA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("Paciente:\nMédico:\nMARIA DA SILVA\nJOAO PEREIRA\nPRONT 123"))
        assertEquals("Maria da Silva",
            EtiquetaParser.extrairNome("Nome:\nMédico:\nMaria da Silva\nDr. João Pereira"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PACIENTE:\nSEXO:\nMARIA DA SILVA\nF"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME:\nSETOR:\nMARIA DA SILVA\nONCOLOGIA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("HOSPITAL SAO LUCAS\nPACIENTE:\nMÃE:\nMARIA DA SILVA\nJOANA DA SILVA"))
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("FIRST NAME:\nLAST NAME:\nJOHN\nSMITH"))
    }

    @Test
    fun `etiqueta em tabela - prontuario e nascimento pela posicao`() {
        assertEquals("0012345", EtiquetaParser.extrairProntuario("NASC:\nPRONT:\n12/03/1960\n0012345"))
        assertEquals("0012345",
            EtiquetaParser.extrairProntuario("DATA DE NASCIMENTO:\nPRONTUÁRIO:\n12/03/1960\n0012345"))
        assertEquals("0012345", EtiquetaParser.extrairProntuario("DN:\nREGISTRO:\n12/03/1960\n0012345"))
        assertEquals("0012345", EtiquetaParser.extrairProntuario("Pront:\nNasc:\n0012345\n12/03/1960"))
        assertEquals("0012345",
            EtiquetaParser.extrairProntuario("CONVENIO:\nPRONTUARIO:\nUNIMED 1234 5678\n0012345"))
        assertEquals("12/03/1960",
            EtiquetaParser.extrairDataNascimento("NASC:\nADMISSAO:\n12/03/1960\n01/10/2026"))
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("NASC:\nDATA:\n12/03/1960\n03/10/2026"))
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("DATA:\nNASC:\n03/10/2026\n12/03/1960"))
        // Linha que sobra depois da tabela fica fora dela.
        val sobra = "PACIENTE:\nPRONT:\nNASC:\nMARIA DA SILVA\n0012345\n12/03/1960\nHOSPITAL SAO LUCAS"
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome(sobra))
        assertEquals("0012345", EtiquetaParser.extrairProntuario(sobra))
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento(sobra))
    }

    /**
     * Valor que faltou desalinha a tabela. A forma do valor ainda resolve
     * quando so um rotulo pode possui-lo; quando dois podem, a caixa fica
     * vazia, porque o valor pode ser de outra pessoa.
     */
    @Test
    fun `tabela desalinhada so entrega o valor que um rotulo so pode possuir`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("PACIENTE:\nNASC:\nPRONT:\nMARIA DA SILVA\n0012345"))
        assertEquals("0012345", EtiquetaParser.extrairProntuario("NASC:\nPRONT:\n0012345"))
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("PRONT:\nNASC:\n12/03/1960"))
        assertEquals("", EtiquetaParser.extrairNome("PACIENTE:\nMÃE:\nPRONT:\nMARIA DA SILVA\n0012345"))
        assertEquals("", EtiquetaParser.extrairProntuario("PRONT:\nCPF:\n12345678900"))
        // Rotulo ambiguo sozinho debaixo de um nome e o sobrenome que quebrou,
        // e nao abre tabela.
        assertEquals("123456", EtiquetaParser.extrairProntuario("JOSE CARLOS\nSALA\nPRONTUARIO\n123456"))
    }

    /**
     * Sobrenome ambiguo sozinho (NASCIMENTO, WARD, SALA, ZIMMER) entre os
     * valores da tabela e o nome que quebrou. Encerrar ali a lista de valores
     * deixava fora da tabela a linha seguinte, que e da mae ou do medico, e
     * ela virava o nome do paciente. A tabela fica desalinhada e a caixa,
     * vazia; o cabecalho do hospital tambem nao toma o lugar do nome.
     */
    @Test
    fun `sobrenome ambiguo quebrado no meio da tabela nao solta a linha de outra pessoa`() {
        assertEquals("",
            EtiquetaParser.extrairNome("Paciente:\nMãe:\nMARIA APARECIDA DO\nNASCIMENTO\nJOANA DA SILVA"))
        assertEquals("",
            EtiquetaParser.extrairNome("PACIENTE:\nMEDICO:\nANA PAULA DO\nNASCIMENTO\nJOAO PEREIRA"))
        assertEquals("", EtiquetaParser.extrairNome("Patient:\nMother:\nMARY ELIZABETH\nWARD\nJANE WARD"))
        assertEquals("", EtiquetaParser.extrairNome("Patient:\nDoctor:\nMARY ELIZABETH\nWARD\nJOHN SMITH"))
        assertEquals("", EtiquetaParser.extrairNome("Nombre:\nMadre:\nMARIA JOSE\nSALA\nROSA SALA"))
        assertEquals("", EtiquetaParser.extrairNome("Name:\nArzt:\nANNA MARIA\nZIMMER\nHANS MULLER"))
        assertEquals("", EtiquetaParser.extrairNome(
            "HOSPITAL SAO LUCAS\nPaciente:\nMãe:\nMARIA APARECIDA DO\nNASCIMENTO\nJOANA DA SILVA"))
        assertEquals("",
            EtiquetaParser.extrairNome("HOSPITAL SAO LUCAS\nPACIENTE:\nMÃE:\nPRONT:\nMARIA DA SILVA\n0012345"))
        // Quando o outro rotulo nao possui texto, o nome e remontado.
        val pront = "Paciente:\nPront:\nMARIA APARECIDA DO\nNASCIMENTO\n0012345"
        assertEquals("MARIA APARECIDA DO NASCIMENTO", EtiquetaParser.extrairNome(pront))
        assertEquals("0012345", EtiquetaParser.extrairProntuario(pront))
    }

    /**
     * O rotulo ambiguo sozinho entre os valores tambem pode ser rotulo de
     * verdade, e continua entre os donos possiveis da linha de baixo: a data
     * de impressao nao vira nascimento, e ele nao faz a tabela passar por
     * alinhada.
     */
    @Test
    fun `rotulo ambiguo sozinho entre os valores continua dono possivel da linha de baixo`() {
        assertEquals("", EtiquetaParser.extrairDataNascimento("PACIENTE:\nMAE:\nMARIA DA SILVA\nDATA\n03/10/2026"))
        assertEquals("12/03/1960",
            EtiquetaParser.extrairDataNascimento("PACIENTE:\nMAE:\nMARIA DA SILVA\nDATA\n03/10/2026\n12/03/1960"))
        assertEquals("", EtiquetaParser.extrairProntuario("SALA\nMRN:\nCPF:\n12345678900\nSALA\nSAUDE"))
    }

    /**
     * Idade, CPF, leito e atendimento nao aceitam texto como prova de
     * alinhamento. Se aceitassem, o nome da operadora quebrado em duas linhas
     * faria a tabela desalinhada passar por alinhada, e o numero do leito ou
     * do atendimento desceria para o prontuario.
     */
    @Test
    fun `texto debaixo de rotulo de numero nao alinha a tabela`() {
        val leito = "MARIA DA SILVA\nConvenio:\nLeito:\nProntuario:\nSUL AMERICA\nSAUDE\n1204\n0012345"
        assertEquals("", EtiquetaParser.extrairProntuario(leito))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome(leito))
        val atend = "MARIA DA SILVA\nConvenio:\nAtendimento:\nProntuario:\nSUL AMERICA\nSAUDE\n99887766\n0012345"
        assertEquals("", EtiquetaParser.extrairProntuario(atend))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome(atend))
        assertEquals("", EtiquetaParser.extrairProntuario("Prontuario:\nLeito:\n1204\nMARIA DA SILVA"))
        assertEquals("", EtiquetaParser.extrairProntuario("Prontuario:\nAtendimento:\n99887766\nMARIA DA SILVA"))
        // Leito com numero na unidade continua alinhando.
        val uti = "PACIENTE:\nLEITO:\nPRONT:\nMARIA DA SILVA\nUTI 12\n0012345"
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome(uti))
        assertEquals("0012345", EtiquetaParser.extrairProntuario(uti))
    }

    /**
     * Na tabela desalinhada, o texto que o rotulo de outro campo pode ter (o
     * nome da unidade debaixo do leito, a linha sem dono certo debaixo da
     * idade ou do atendimento) nao vira nome: na duvida, caixa vazia, e
     * nunca a linha seguinte, que pode ser da mae.
     */
    @Test
    fun `texto de dono incerto na tabela desalinhada nao vira nome`() {
        assertEquals("", EtiquetaParser.extrairNome("PACIENTE:\nLEITO:\nUTI ADULTO"))
        assertEquals("", EtiquetaParser.extrairNome("PACIENTE:\nLEITO:\nUTI ADULTO\nMARIA DA SILVA"))
        assertEquals("", EtiquetaParser.extrairNome("Prontuario:\nLeito:\n1204\nMARIA DA SILVA"))
        assertEquals("", EtiquetaParser.extrairNome("Idade:\nPaciente:\nMARIA DA SILVA\nJOANA DA SILVA"))
        assertEquals("", EtiquetaParser.extrairNome("Atendimento:\nDOB:\nSUL AMERICA\n12/03/1960\nMARIA DA SILVA"))
    }

    /** Prontuario disputado por outro rotulo nao cai no maior numero sem dono. */
    @Test
    fun `prontuario incerto na tabela deixa a caixa vazia`() {
        assertEquals("", EtiquetaParser.extrairProntuario("99999999\nPRONT:\nCPF:\n12345678900"))
        assertEquals("",
            EtiquetaParser.extrairProntuario("7891234567890\nPRONT:\nCPF:\n12345678900\nMARIA DA SILVA"))
    }

    @Test
    fun `prontuario nao pega o ano do nascimento`() {
        assertEquals("0012345", EtiquetaParser.extrairProntuario("PRONTUARIO\n12/03/1960 0012345"))
    }

    /** Data do ano primeiro, comum em alemao, japones, chines e coreano, tambem nao e prontuario. */
    @Test
    fun `prontuario nao pega o ano da data do ano primeiro`() {
        assertEquals("00123456", EtiquetaParser.extrairProntuario("JOHN SMITH\nMRN\n1960-03-12\n00123456"))
        assertEquals("00123456", EtiquetaParser.extrairProntuario("JOHN SMITH\nMRN\n1960-03-12 00123456"))
        assertEquals("0012345",
            EtiquetaParser.extrairProntuario("ANNA SCHMIDT\nPATIENTENNUMMER\n1960-03-12 0012345"))
        assertEquals("0012345", EtiquetaParser.extrairProntuario("PRONTUARIO\n1960/03/12 0012345"))
        assertEquals("0012345", EtiquetaParser.extrairProntuario("PRONTUARIO: 1960.03.12 0012345"))
        assertEquals("0012345", EtiquetaParser.extrairProntuario("患者ID：1960年3月12日 0012345"))
    }

    /** So sai o que tem forma de data possivel: numero impresso em grupos fica. */
    @Test
    fun `semDatas tira so as datas`() {
        assertEquals("0012345", EtiquetaParser.semDatas("1960-03-12 0012345").trim())
        assertEquals("0012345", EtiquetaParser.semDatas("12/03/1960 0012345").trim())
        assertEquals("1234-56-78", EtiquetaParser.semDatas("1234-56-78"))
        assertEquals("2024/12345", EtiquetaParser.semDatas("2024/12345"))
        assertEquals("19600312", EtiquetaParser.semDatas("19600312"))
    }

    /**
     * Numero curto depois da data do ano primeiro (idade, ano do prontuario)
     * nao transforma o fim dela em data do dia primeiro, sobrando o ano.
     */
    @Test
    fun `data do ano primeiro seguida de numero curto sai inteira`() {
        assertEquals("64", EtiquetaParser.semDatas("1960-03-12 64").trim())
        assertEquals("00123456", EtiquetaParser.extrairProntuario("JOHN SMITH\nMRN\n1960-03-12 64 00123456"))
        assertEquals("0012345",
            EtiquetaParser.extrairProntuario("ANNA SCHMIDT\nPATIENTENNUMMER\n1960-03-12 64 J 0012345"))
        assertEquals("1234", EtiquetaParser.extrairProntuario("MRN\n1960-03-12 1234"))
    }

    /** O ultimo recurso nao devolve linha que ja foi reprovada como nome. */
    @Test
    fun `sem candidata a caixa fica vazia`() {
        assertEquals("", EtiquetaParser.extrairNome("PACIENTE:\nSEXO:\nF"))
        assertEquals("", EtiquetaParser.extrairNome("123456"))
        assertEquals("", EtiquetaParser.extrairNome(": - ."))
    }

    // ---------- rotulo ambiguo, titulos e rotulos compostos ----------

    /** Dois-pontos e prova de rotulo: MAE, PAI, DOCTOR, DOB, DN, REG e MRN cortam a linha. */
    @Test
    fun `rotulo ambiguo com dois-pontos corta o nome`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME: MARIA DA SILVA MÃE: JOANA DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME: MARIA DA SILVA PAI: JOSE DA SILVA"))
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("PATIENT: JOHN SMITH DOCTOR: JANE DOE"))
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("PATIENT: JOHN SMITH DOB: 12/03/1960"))
        assertEquals("JOHN SMITH", EtiquetaParser.extrairNome("PATIENT: JOHN SMITH MRN: 00123456"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PACIENTE: MARIA DA SILVA DN: 12/03/1960"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME: MARIA DA SILVA NASCIMENTO: 12/03/1960"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PACIENTE: MARIA DA SILVA REG: 12345"))
    }

    /**
     * Rotulo ambiguo de outra pessoa sem dois-pontos: pode ser sobrenome
     * ("MAE JEMISON"), mas nunca e colado ao nome do paciente, e outra linha
     * vale mais que ele. MAE com til e rotulo: o prenome Mae nao tem til.
     */
    @Test
    fun `rotulo ambiguo de outra pessoa sem dois-pontos nao entra no nome`() {
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA\nMÃE JOANA DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MÃE JOANA DA SILVA\nMARIA DA SILVA"))
        assertEquals("", EtiquetaParser.extrairNome("MÃE JOANA DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA MÃE JOANA DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PAI JOSE DA SILVA\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA\nPAI JOSE DA SILVA"))
        assertEquals("MARY JONES", EtiquetaParser.extrairNome("DOCTOR JOHN SMITH\nMARY JONES"))
        assertEquals("MARY JONES", EtiquetaParser.extrairNome("MARY JONES\nDOCTOR JOHN SMITH"))
        assertEquals("MARIA LOPEZ", EtiquetaParser.extrairNome("MADRE ROSA GARCIA\nMARIA LOPEZ"))
        assertEquals("ANNA SCHMIDT", EtiquetaParser.extrairNome("ARZT HANS MULLER\nANNA SCHMIDT"))
        assertEquals("ANNA NOWAK", EtiquetaParser.extrairNome("LEKARZ JAN KOWALSKI\nANNA NOWAK"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MED JOAO PEREIRA\nMARIA DA SILVA"))
        assertEquals("GIULIA BIANCHI", EtiquetaParser.extrairNome("SESSO F\nGIULIA BIANCHI"))
        assertEquals("GIULIA BIANCHI", EtiquetaParser.extrairNome("GIULIA BIANCHI\nSESSO F"))
        // Sozinho na etiqueta, continua sendo nome.
        assertEquals("MAE JEMISON", EtiquetaParser.extrairNome("MAE JEMISON"))
    }

    /** "Prof. Dr." e como hospital universitario imprime o medico; DR sem ponto tambem corta. */
    @Test
    fun `titulos de medico recusam a linha e cortam o nome`() {
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA\nPROF DR JOAO PEREIRA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PROF. DR. JOAO PEREIRA\nMARIA DA SILVA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA\nPROF. DR. JOAO PEREIRA"))
        assertEquals("ANNA SCHMIDT", EtiquetaParser.extrairNome("PROF. DR. MED. HANS MULLER\nANNA SCHMIDT"))
        assertEquals("GIULIA BIANCHI", EtiquetaParser.extrairNome("PROF. MARIO ROSSI\nGIULIA BIANCHI"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("PROFª ANA COSTA\nMARIA DA SILVA"))
        assertEquals("MARIE MARTIN", EtiquetaParser.extrairNome("PR DUPONT JEAN\nMARIE MARTIN"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA DR JOAO PEREIRA"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("MARIA DA SILVA DRA ANA COSTA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("REQUISITANTE: DR JOAO PEREIRA\nMARIA DA SILVA"))
    }

    /** O vocabulario nao lista todo papel: ligacao + papel + dois-pontos e rotulo de outra pessoa. */
    @Test
    fun `rotulo do nome com papel fora do vocabulario e de outra pessoa`() {
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("MARIA DA SILVA\nNOME DO CÔNJUGE: JOSE DA SILVA"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("NOME DO CÔNJUGE: JOSE DA SILVA\nMARIA DA SILVA"))
        assertEquals("MARIA LOPEZ",
            EtiquetaParser.extrairNome("NOMBRE DEL FAMILIAR: JUAN PEREZ\nMARIA LOPEZ"))
        assertEquals("MARIA DA SILVA",
            EtiquetaParser.extrairNome("MARIA DA SILVA\nNOME DO ESPOSO(A): JOSE DA SILVA"))
        assertEquals("JANE DOE", EtiquetaParser.extrairNome("JANE DOE\nNAME OF SPOUSE: JOHN DOE"))
        // O rotulo do proprio paciente continua valendo.
        assertEquals("JANE DOE", EtiquetaParser.extrairNome("NAME OF PATIENT: JANE DOE"))
        assertEquals("MARIA LOPEZ", EtiquetaParser.extrairNome("NOMBRE DE LA PACIENTE: MARIA LOPEZ"))
        assertEquals("MARIA DA SILVA", EtiquetaParser.extrairNome("NOME E SOBRENOME: MARIA DA SILVA"))
    }

    /** ANA LUZ e o nome, e nao palavra de ligacao de um rotulo composto. */
    @Test
    fun `nome de duas palavras curtas depois do rotulo`() {
        assertEquals("ANA LUZ", EtiquetaParser.extrairNome("PACIENTE ANA LUZ SEXO F"))
        assertEquals("ANA LUZ", EtiquetaParser.extrairNome("PACIENTE ANA LUZ PRONTUARIO 12345"))
        assertEquals("AMY LEE", EtiquetaParser.extrairNome("PATIENT AMY LEE SEX: F"))
    }

    // ---------- prontuario: so o numero de rotulo de prontuario, ou de ninguem ----------

    @Test
    fun `prontuario com rotulo continua funcionando`() {
        assertEquals("123456", EtiquetaParser.extrairProntuario("PRONTUARIO 123456"))
        assertEquals("0012345", EtiquetaParser.extrairProntuario("PRONT.: 0012345"))
        assertEquals("12345", EtiquetaParser.extrairProntuario("REG 12345"))
        assertEquals("00123456", EtiquetaParser.extrairProntuario("MRN: 00123456"))
    }

    @Test
    fun `numero do nascimento na mesma linha nao e prontuario`() {
        assertEquals("1234", EtiquetaParser.extrairProntuario("NASC: 12/03/1960 PRONTUARIO: 1234"))
    }

    @Test
    fun `rotulo de prontuario sozinho e dono da linha de baixo`() {
        assertEquals("1234", EtiquetaParser.extrairProntuario("PRONTUARIO\n1234"))
    }

    @Test
    fun `documento nacional e convenio nao viram prontuario`() {
        assertEquals("0012345",
            EtiquetaParser.extrairProntuario("MARIA DA SILVA\nCNS 898001234567890\n0012345"))
        assertEquals("123456", EtiquetaParser.extrairProntuario("CPF: 12345678900\n123456"))
        assertEquals("", EtiquetaParser.extrairProntuario("PESEL: 60031212345\nANNA NOWAK"))
        assertEquals("123456789",
            EtiquetaParser.extrairProntuario("IPP : 123456789\nNIR : 1600375123456"))
    }

    @Test
    fun `CRM do medico e nascimento sem barras nao viram prontuario`() {
        assertEquals("", EtiquetaParser.extrairProntuario("MARIA DA SILVA\nMEDICO JOAO PEREIRA CRM 123456"))
        assertEquals("", EtiquetaParser.extrairProntuario("NASC: 12031960\nMARIA DA SILVA"))
    }

    /** Atendimento muda a cada visita: usa-lo partiria o paciente em duas pastas. */
    @Test
    fun `numero de atendimento nunca e prontuario`() {
        assertEquals("12345",
            EtiquetaParser.extrairProntuario("ATENDIMENTO: 99887766\nPRONTUARIO: 12345"))
        assertEquals("", EtiquetaParser.extrairProntuario("ATENDIMENTO: 99887766"))
    }

    @Test
    fun `numero sem dono continua no fallback`() {
        assertEquals("123456", EtiquetaParser.extrairProntuario("REGINA DA SILVA 123456"))
        assertEquals("0012345", EtiquetaParser.extrairProntuario("MARIA DO NASCIMENTO 0012345"))
    }

    /**
     * Sobrenome que tambem e rotulo de sala, quarto ou idade, no meio da
     * linha, so possui numero curto: o numero longo que vem depois do nome e
     * o prontuario, e a data e o nascimento.
     */
    @Test
    fun `sobrenome que e rotulo de numero ou data nao toma o valor do nome`() {
        assertEquals("00123456", EtiquetaParser.extrairProntuario("JOHN WARD 00123456"))
        assertEquals("123456", EtiquetaParser.extrairProntuario("ANA SALA 123456"))
        assertEquals("1234567", EtiquetaParser.extrairProntuario("MARIA ZIMMER 1234567"))
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("MARIA DATA 12/03/1960"))
        // O numero curto continua sendo da sala.
        assertEquals("123456", EtiquetaParser.extrairProntuario("MARIA DA SILVA SALA 3\n123456"))
        assertEquals("", EtiquetaParser.extrairProntuario("MARIA DA SILVA CPF 12345678900"))
    }

    @Test
    fun `rotulo de prontuario colado ao numero`() {
        assertEquals("123456", EtiquetaParser.extrairProntuario("PRONTUARIO123456"))
    }

    // ---------- nascimento: so a data de rotulo de nascimento, ou de ninguem ----------

    @Test
    fun `data de impressao nao e nascimento`() {
        assertEquals("12/03/1960",
            EtiquetaParser.extrairDataNascimento("DATA: 03/10/2026\nMARIA DA SILVA 12/03/1960"))
        assertEquals("12/03/1960",
            EtiquetaParser.extrairDataNascimento("DATA: 03/10/2026\nNASC: 12/03/1960"))
    }

    @Test
    fun `data de admissao nao e nascimento`() {
        assertEquals("12/03/1960",
            EtiquetaParser.extrairDataNascimento("ADMISSAO 01/10/2026 DN 12/03/1960"))
        assertEquals("", EtiquetaParser.extrairDataNascimento("MARIA DO NASCIMENTO ADM: 01/10/2026"))
    }

    @Test
    fun `sobrenome NASCIMENTO seguido de data continua valendo`() {
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("MARIA DO NASCIMENTO 12/03/1960"))
    }

    @Test
    fun `rotulo de nascimento sozinho e dono da linha de baixo`() {
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("DATA DE NASCIMENTO\n12/03/1960"))
    }

    @Test
    fun `rotulo de nascimento com erro de OCR ou abreviado`() {
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("NASSCIMETO: 12/03/1960"))
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("NASCIM 12/03/1960"))
    }

    @Test
    fun `rotulo de nascimento nos idiomas latinos`() {
        assertEquals("24/05/1964", EtiquetaParser.extrairDataNascimento("DATE OF BIRTH: 24 MAY 1964"))
        assertEquals("24/01/1964", EtiquetaParser.extrairDataNascimento("FECHA DE NACIMIENTO: 24-ENE-1964"))
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("GEBURTSDATUM: 12.03.1960"))
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("DATA URODZENIA: 12.03.1960"))
    }

    @Test
    fun `mes desconhecido deixa a caixa vazia`() {
        assertEquals("", EtiquetaParser.extrairDataNascimento("NASC: 24 ABC 1964"))
    }

    @Test
    fun `data sem rotulo continua no fallback`() {
        assertEquals("12/03/1960", EtiquetaParser.extrairDataNascimento("MARIA DA SILVA\n12/03/1960"))
    }

    /**
     * O idioma do app vira o Locale padrao. Em bengali ou arabe, "%02d"
     * formatado com o Locale padrao sairia com os algarismos do idioma.
     */
    @Test
    fun `data sai com algarismos ASCII em qualquer idioma`() {
        val anterior = java.util.Locale.getDefault()
        try {
            for (tag in listOf("bn", "ar")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                assertEquals(tag, "12/03/1960", EtiquetaParser.extrairDataNascimento("NASC: 12/3/60"))
            }
        } finally {
            java.util.Locale.setDefault(anterior)
        }
    }
}
