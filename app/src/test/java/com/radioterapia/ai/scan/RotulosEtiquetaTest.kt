package com.radioterapia.ai.scan

import com.radioterapia.ai.scan.RotulosEtiqueta.Familia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vocabulario de rotulos da etiqueta e as regras que decidem quando uma
 * palavra e rotulo e quando e nome.
 *
 * O erro caro aqui e o de um lado so: rotulo tomado por nome poe o nome do
 * medico no cadastro do paciente; nome tomado por rotulo apaga o sobrenome
 * do paciente. Os dois lados estao cobertos.
 */
class RotulosEtiquetaTest {

    // ---------- distancia e dobra ----------

    @Test
    fun `distancia de edicao com transposicao`() {
        assertEquals(2, RotulosEtiqueta.distancia("NASSCIMETO", "NASCIMENTO"))
        assertEquals(1, RotulosEtiqueta.distancia("NASCIMNETO", "NASCIMENTO"))
        assertEquals(1, RotulosEtiqueta.distancia("MEDICI", "MEDICO"))
        assertEquals(1, RotulosEtiqueta.distancia("CASTELLA", "CARTELLA"))
        assertEquals(3, RotulosEtiqueta.distancia("", "ABC"))
        assertEquals(0, RotulosEtiqueta.distancia("PRONTUARIO", "PRONTUARIO"))
    }

    @Test
    fun `distancia e simetrica`() {
        val pares = listOf(
            "NASSCIMETO" to "NASCIMENTO", "PACIENTF" to "PACIENTE", "REGLSTRO" to "REGISTRO",
            "MEDINA" to "MEDICAL", "ABCDEF" to "FEDCBA",
        )
        for ((a, b) in pares) {
            assertEquals("$a/$b", RotulosEtiqueta.distancia(a, b), RotulosEtiqueta.distancia(b, a))
        }
    }

    @Test
    fun `dobra tira acento e reduz letras com traco`() {
        assertEquals("PLEC", RotulosEtiqueta.dobrar("PŁEĆ"))
        assertEquals("LOZKO", RotulosEtiqueta.dobrar("ŁÓŻKO"))
        assertEquals("GESCHLECHT", RotulosEtiqueta.dobrar("Geschlecht"))
        assertEquals("PRONTUARIO", RotulosEtiqueta.dobrar("Prontuário"))
        assertEquals("N", RotulosEtiqueta.dobrar("Nº"))
        assertEquals("AGE", RotulosEtiqueta.dobrar("ÂGE"))
        assertEquals("STRASSE", RotulosEtiqueta.dobrar("Straße"))
    }

    // ---------- rotulos encontrados no comeco da linha ----------

    @Test
    fun `MEDICO FULANO DE TAL e rotulo de outra pessoa`() {
        val a = RotulosEtiqueta.noInicio("MEDICO FULANO DE TAL")
        assertNotNull(a)
        assertEquals(Familia.PESSOA, a!!.familia)
        assertFalse(a.ambiguo)
        assertFalse(a.comSeparador)
        assertEquals("FULANO DE TAL", a.resto)
    }

    @Test
    fun `separador e caixa original do valor`() {
        val a = RotulosEtiqueta.noInicio("MÉDICO: Fulano")!!
        assertEquals(Familia.PESSOA, a.familia)
        assertTrue(a.comSeparador)
        assertEquals("Fulano", a.resto)
    }

    @Test
    fun `rotulo mais longo vence`() {
        assertEquals(Familia.PESSOA, RotulosEtiqueta.noInicio("NOME DO MEDICO: JOAO")!!.familia)
        assertEquals(Familia.PESSOA, RotulosEtiqueta.noInicio("NOME DA MÃE: JOANA SILVA")!!.familia)
        assertEquals(Familia.OUTRO, RotulosEtiqueta.noInicio("NOME SOCIAL: JOANA")!!.familia)
        assertEquals(Familia.OUTRO, RotulosEtiqueta.noInicio("MATRÍCULA DO CONVÊNIO: 123")!!.familia)
        assertEquals(Familia.NOME, RotulosEtiqueta.noInicio("NOME DO UTENTE: ANA")!!.familia)
    }

    @Test
    fun `NASSCIMETO com ponto e rotulo aproximado`() {
        val a = RotulosEtiqueta.noInicio("NASSCIMETO.")!!
        assertEquals(Familia.NASCIMENTO, a.familia)
        assertTrue(a.aproximado)
        assertEquals("", a.resto)
        assertTrue(a.comEvidencia)
    }

    @Test
    fun `aproximacao so com separador ou linha inteira`() {
        val a = RotulosEtiqueta.noInicio("PACIENTF: MARIA DA SILVA")!!
        assertEquals(Familia.NOME, a.familia)
        assertTrue(a.aproximado)
        assertEquals("MARIA DA SILVA", a.resto)
        assertNull(RotulosEtiqueta.noInicio("PACIENTF MARIA DA SILVA"))
    }

    @Test
    fun `erros de OCR comuns em rotulo longo`() {
        val casos = mapOf(
            "NASCIMETO:" to Familia.NASCIMENTO,
            "NASCIMNETO:" to Familia.NASCIMENTO,
            "PRONTUAR1O:" to Familia.REGISTRO,
            "REGlSTRO:" to Familia.REGISTRO,
            "NASCIMENT0: 12/03/1960" to Familia.NASCIMENTO,
        )
        for ((linha, familia) in casos) {
            assertEquals(linha, familia, RotulosEtiqueta.noInicio(linha)?.familia)
        }
    }

    @Test
    fun `nomes parecidos com rotulo nao sao rotulo`() {
        val nomes = listOf(
            "MEDINA SOUZA", "MEDICI LORENZO", "SEXTON JAMES", "REGIS PEREIRA", "IDALINA COSTA",
            "REGINA DA SILVA", "MATEUS SOUZA", "LEITAO MARCOS", "NASCIMBENI MARIO", "CASTELLA",
            "PAZIENTI", "NASCIMENTO, ANA PAULA", "NASCIMENTO ANA PAULA", "MAE JEMISON",
            "WARD JOHN", "ALTER ANNA MARIA", "SALA MARIA", "ZIMMER HANS PETER",
            "NASCIMENTO-SILVA ANA", "PATIENCE", "CONVENTO", "SERVIDIO", "NASCIMBENI",
        )
        for (n in nomes) {
            val a = RotulosEtiqueta.noInicio(n)
            assertTrue(n, a == null || !a.comEvidencia)
            assertFalse(n, RotulosEtiqueta.comecaComRotulo(n))
        }
    }

    @Test
    fun `rotulo ambiguo com evidencia vale`() {
        val linhas = listOf(
            "WARD: 5", "MAE: JOANA SILVA", "NASCIMENTO", "NASCIMENTO: 12/03/1960",
            "DATA 03/10/2026", "REG 12345", "SALA - 3", "NASCIM 12/03/1960", "DTNASC: 12/03/1960",
        )
        for (l in linhas) assertTrue(l, RotulosEtiqueta.comecaComRotulo(l))
    }

    /** Rotulo ambiguo de data so possui data: o prontuario colado nao e nascimento. */
    @Test
    fun `rotulo ambiguo de data nao possui numero que nao e data`() {
        val a = RotulosEtiqueta.todos("MARIA DO NASCIMENTO 0012345").single()
        assertEquals(Familia.NASCIMENTO, a.familia)
        assertFalse(a.possuiValor)
        val b = RotulosEtiqueta.todos("MARIA DO NASCIMENTO 12/03/1960").single()
        assertTrue(b.possuiValor)
    }

    /**
     * Sobrenome comum no meio da linha (WARD, SALA, DATA) so possui numero
     * curto; no comeco da linha, ou com separador, continua possuindo o valor.
     */
    @Test
    fun `sobrenome no meio da linha so possui numero curto`() {
        assertFalse(RotulosEtiqueta.todos("JOHN WARD 00123456").single().possuiValor)
        assertTrue(RotulosEtiqueta.todos("JOHN WARD 5").single().possuiValor)
        assertFalse(RotulosEtiqueta.todos("MARIA DATA 12/03/1960").single().possuiValor)
        assertTrue(RotulosEtiqueta.todos("DATA 03/10/2026").single().possuiValor)
        assertTrue(RotulosEtiqueta.todos("JOHN WARD: 00123456").single().possuiValor)
        // Sigla curta, que nao e sobrenome, segue a regra de antes.
        assertTrue(RotulosEtiqueta.todos("MARIA DA SILVA CPF 12345678900").single().possuiValor)
    }

    /** O til de MAE e a evidencia: o prenome ingles Mae nao tem til. */
    @Test
    fun `MAE com til e rotulo sem precisar de dois-pontos`() {
        val a = RotulosEtiqueta.noInicio("MÃE JOANA DA SILVA")!!
        assertEquals(Familia.PESSOA, a.familia)
        assertFalse(a.ambiguo)
        assertTrue(a.comEvidencia)
        val b = RotulosEtiqueta.noInicio("MAE JEMISON")!!
        assertTrue(b.ambiguo)
        assertFalse(b.comEvidencia)
    }

    /** Ponto e travessao nao sao separador forte; dois-pontos, mesmo depois de ponto, e. */
    @Test
    fun `separador forte`() {
        assertTrue(RotulosEtiqueta.todos("MARIA DA SILVA MAE: JOANA").last().separadorForte)
        assertTrue(RotulosEtiqueta.noInicio("D.N.: 12/03/1960")!!.separadorForte)
        assertFalse(RotulosEtiqueta.todos("ANA PAULA NASCIMENTO - 12/03/1960").single().separadorForte)
        assertFalse(RotulosEtiqueta.noInicio("DR. JOAO")!!.separadorForte)
    }

    @Test
    fun `titulos de medico sao rotulo de outra pessoa`() {
        val familias = RotulosEtiqueta.familiasPorRotulo()
        val amb = RotulosEtiqueta.ambiguidadePorRotulo()
        for (r in listOf("PROF", "PROFA", "PROFESSOR", "PROFESSORA", "REQUISITANTE", "PR")) {
            assertEquals(r, setOf(Familia.PESSOA), familias[r])
        }
        assertEquals(false, amb["PROF"])
        assertEquals(true, amb["PR"])
        assertEquals(Familia.PESSOA, RotulosEtiqueta.noInicio("PROFª ANA COSTA")?.familia)
    }

    @Test
    fun `prefixo de numero`() {
        val a = RotulosEtiqueta.noInicio("Nº PRONTUÁRIO: 1234")!!
        assertEquals(Familia.REGISTRO, a.familia)
        assertEquals(0, a.inicio)
        assertEquals("1234", a.resto)
        assertEquals(Familia.REGISTRO, RotulosEtiqueta.noInicio("N° DOSSIER 99")?.familia)
        assertEquals(Familia.REGISTRO, RotulosEtiqueta.noInicio("NR KSIĘGI 12345")?.familia)
        // Numero de utente e documento nacional, nem nome nem prontuario.
        val u = RotulosEtiqueta.noInicio("Nº UTENTE 123456789")!!
        assertEquals(Familia.OUTRO, u.familia)
        assertTrue(RotulosEtiqueta.Tipo.NUMERO in u.tipos)
        assertEquals(Familia.OUTRO, RotulosEtiqueta.noInicio("UTENTE Nº 123456789")?.familia)
    }

    @Test
    fun `todos encontra os rotulos em ordem e sem sobreposicao`() {
        val achados = RotulosEtiqueta.todos("NASC: 12/03/1960 PRONTUARIO: 1234 SEXO F")
        assertEquals(listOf(Familia.NASCIMENTO, Familia.REGISTRO, Familia.OUTRO), achados.map { it.familia })
        assertTrue(achados.zipWithNext().all { (a, b) -> a.fimValor <= b.inicio })
    }

    @Test
    fun `rotulo colado aos digitos`() {
        val a = RotulosEtiqueta.noInicio("PRONTUARIO123456")!!
        assertEquals(Familia.REGISTRO, a.familia)
        assertEquals("123456", a.resto)
    }

    /**
     * Vocabulario das escritas sem alfabeto latino. Fica dormente enquanto o
     * reconhecedor do ML Kit for o latino, mas tem de estar certo para quando
     * deixar de ser.
     */
    @Test
    fun `rotulos em escrita nao latina`() {
        assertEquals(Familia.OUTRO, RotulosEtiqueta.noInicio("性别：女")?.familia)
        val zh = RotulosEtiqueta.noInicio("姓名王小明")!!
        assertEquals(Familia.NOME, zh.familia)
        assertEquals("王小明", zh.resto)
        assertEquals(Familia.REGISTRO, RotulosEtiqueta.noInicio("病历号12345")?.familia)
        assertEquals(Familia.REGISTRO, RotulosEtiqueta.noInicio("患者ID: 12345")?.familia)
        assertEquals(Familia.NASCIMENTO, RotulosEtiqueta.noInicio("生年月日：1960年3月12日")?.familia)
        assertEquals(Familia.OUTRO, RotulosEtiqueta.noInicio("성별: 여")?.familia)
        assertEquals(Familia.REGISTRO, RotulosEtiqueta.noInicio("등록번호: 12345")?.familia)
        assertEquals(Familia.OUTRO, RotulosEtiqueta.noInicio("주민등록번호: 600312")?.familia)
        assertEquals(Familia.NASCIMENTO, RotulosEtiqueta.noInicio("تاريخ الميلاد: 12/03/1960")?.familia)
        assertEquals(Familia.PESSOA, RotulosEtiqueta.noInicio("الطبيب المعالج: احمد")?.familia)
        assertEquals(Familia.NASCIMENTO, RotulosEtiqueta.noInicio("জন্ম তারিখ: 12/03/1960")?.familia)
        assertEquals(Familia.NOME, RotulosEtiqueta.noInicio("রোগীর নাম: করিম")?.familia)
    }

    // ---------- invariantes do vocabulario ----------

    /**
     * Um mesmo rotulo (PATIENT, AGE, SEXO, MEDICO, DATA, SALA, NOME,
     * REGISTRO...) aparece em varios idiomas; tem de ser da mesma familia em
     * todos, senao o resultado depende da ordem da lista.
     */
    @Test
    fun `nenhum rotulo pertence a duas familias`() {
        val conflitos = RotulosEtiqueta.familiasPorRotulo().filterValues { it.size > 1 }
        assertTrue("rotulos em mais de uma familia: $conflitos", conflitos.isEmpty())
    }

    /** O mesmo vale para os tipos: SALA em espanhol e em polones possui a mesma coisa. */
    @Test
    fun `nenhum rotulo tem dois conjuntos de tipos`() {
        val conflitos = RotulosEtiqueta.tiposPorRotulo().filterValues { it.size > 1 }
        assertTrue("rotulos com tipos diferentes: $conflitos", conflitos.isEmpty())
    }

    /**
     * Operadora possui numero ou texto (o nome do convenio). Leito, quarto e
     * enfermaria possuem numero ou LOCAL, que nao e TEXTO: o nome da unidade
     * pode estar ali, mas texto nao prova o alinhamento da tabela. Idade, CPF
     * e carteirinha so possuem numero.
     */
    @Test
    fun `tipos dos rotulos de operadora e de lugar`() {
        val operadora = setOf(RotulosEtiqueta.Tipo.NUMERO, RotulosEtiqueta.Tipo.TEXTO)
        for (l in listOf("CONVÊNIO:", "PLANO:", "PLANO DE SAÚDE:", "INSURANCE:", "HEALTH PLAN:", "ASEGURADORA:",
                "OBRA SOCIAL:", "MUTUELLE:", "KRANKENKASSE:", "UBEZPIECZENIE:")) {
            assertEquals(l, operadora, RotulosEtiqueta.noInicio(l)?.tipos)
        }
        val lugar = setOf(RotulosEtiqueta.Tipo.NUMERO, RotulosEtiqueta.Tipo.LOCAL)
        for (l in listOf("LEITO:", "QUARTO:", "ENFERMARIA:", "WARD:", "BED:", "ROOM:", "CAMA:", "HABITACIÓN:",
                "CHAMBRE:", "STATION:", "ZIMMER:", "LETTO:", "STANZA:", "ŁÓŻKO:", "SALA:")) {
            assertEquals(l, lugar, RotulosEtiqueta.noInicio(l)?.tipos)
        }
        for (l in listOf("IDADE:", "CPF:", "CARTEIRINHA:", "TELEFONE:", "AGE:", "PESEL:")) {
            assertEquals(l, setOf(RotulosEtiqueta.Tipo.NUMERO), RotulosEtiqueta.noInicio(l)?.tipos)
        }
    }

    @Test
    fun `vocabulario cobre os doze idiomas e os rotulos pedidos`() {
        val familias = RotulosEtiqueta.familiasPorRotulo()
        val esperados = mapOf(
            "MEDICO" to Familia.PESSOA, "MEDICA" to Familia.PESSOA, "DR" to Familia.PESSOA,
            "DRA" to Familia.PESSOA, "GENERO" to Familia.OUTRO, "SEXO" to Familia.OUTRO,
            "IDADE" to Familia.OUTRO, "CONVENIO" to Familia.OUTRO, "PRONTUARIO" to Familia.REGISTRO,
            "REGISTRO" to Familia.REGISTRO, "NASCIMENTO" to Familia.NASCIMENTO,
            "DATA DE NASC" to Familia.NASCIMENTO, "NOME SOCIAL" to Familia.OUTRO,
            "ATENDIMENTO" to Familia.OUTRO, "PLEC" to Familia.OUTRO, "GEBURTSDATUM" to Familia.NASCIMENTO,
            "DATA DI NASCITA" to Familia.NASCIMENTO, "FECHA DE NACIMIENTO" to Familia.NASCIMENTO,
            "DATE DE NAISSANCE" to Familia.NASCIMENTO, "DATE OF BIRTH" to Familia.NASCIMENTO,
            "姓名" to Familia.NOME, "生年月日" to Familia.NASCIMENTO, "생년월일" to Familia.NASCIMENTO,
            RotulosEtiqueta.dobrar("تاريخ") + " " + RotulosEtiqueta.dobrar("الميلاد") to Familia.NASCIMENTO,
            "জন্ম তারিখ" to Familia.NASCIMENTO,
        )
        for ((rotulo, familia) in esperados) {
            assertEquals(rotulo, setOf(familia), familias[rotulo])
        }
    }

    /** MEDICO, MEDICA e GENERO sao rotulo sem precisar de evidencia, por decisao de produto. */
    @Test
    fun `ambiguidade dos rotulos`() {
        val amb = RotulosEtiqueta.ambiguidadePorRotulo()
        for (r in listOf("MEDICO", "MEDICA", "GENERO", "DR", "DRA", "PT", "PAC", "NOM", "SEX", "PRONTUARIO")) {
            assertEquals(r, false, amb[r])
        }
        for (r in listOf("NASCIMENTO", "DATA", "SALA", "WARD", "ALTER", "ZIMMER", "MAE", "REG", "MAT", "D N", "NE LE")) {
            assertEquals(r, true, amb[r])
        }
    }

    /**
     * Nenhum rotulo aceito por aproximacao fica dentro do limite de um nome
     * ou sobrenome real. Rotulo novo que colida com esta lista quebra o
     * build: ou ele passa a casar so exato (SEM_APROXIMACAO), ou nao entra.
     */
    @Test
    fun `aproximacao nunca alcanca nome real`() {
        val aproximaveis = RotulosEtiqueta.aproximaveis()
        assertTrue(aproximaveis.size > 50)
        for (rotulo in listOf("CARTELLA", "PAZIENTE", "SERVICIO", "PATIENTE", "CONVENIO")) {
            assertFalse(rotulo, rotulo in aproximaveis)
        }
        // Nome que E rotulo exato (NASCIMENTO, SALA) casa antes de chegar a
        // aproximacao; o que vale para ele e a ambiguidade, testada abaixo.
        val exatos = RotulosEtiqueta.familiasPorRotulo().keys
        val colisoes = ArrayList<String>()
        for (nome in NOMES_REAIS) {
            val n = RotulosEtiqueta.dobrar(nome)
            if (n in exatos) continue
            for (rotulo in aproximaveis) {
                val limite = RotulosEtiqueta.limiteAproximacao(rotulo.length)
                if (n != rotulo && RotulosEtiqueta.distancia(n, rotulo) <= limite) colisoes.add("$nome~$rotulo")
            }
        }
        assertTrue("colisoes: $colisoes", colisoes.isEmpty())
    }

    /**
     * O mesmo, de ponta a ponta: cada nome real, sozinho na linha (onde a
     * aproximacao vale) ou abrindo a linha, nunca e tomado por rotulo, a nao
     * ser que seja exatamente uma palavra de rotulo ambiguo, e entao so com
     * evidencia.
     */
    @Test
    fun `nome real nunca abre linha como rotulo`() {
        val ambiguidade = RotulosEtiqueta.ambiguidadePorRotulo()
        for (nome in NOMES_REAIS) {
            val chave = RotulosEtiqueta.dobrar(nome)
            assertFalse(nome, RotulosEtiqueta.comecaComRotulo("$nome MARIA SOUZA"))
            if (ambiguidade[chave] == true) continue
            assertNull(nome, RotulosEtiqueta.noInicio(nome))
        }
    }

    @Test
    fun `limite de aproximacao por tamanho`() {
        assertEquals(0, RotulosEtiqueta.limiteAproximacao(7))
        assertEquals(1, RotulosEtiqueta.limiteAproximacao(8))
        assertEquals(1, RotulosEtiqueta.limiteAproximacao(9))
        assertEquals(2, RotulosEtiqueta.limiteAproximacao(10))
    }

    private companion object {
        /**
         * Sobrenomes e prenomes comuns em portugues, espanhol, italiano,
         * frances, alemao, polones e ingles, mais os vizinhos de rotulo que
         * ja se sabe que existem (CASTELLA, PAZIENTI, SERVIDIO, PATIENCE,
         * CONVENTO, NASCIMBENI, MEDICI, MEDINA, SEXTON, REGIS, IDALINA).
         * MEDICO e GENERO ficam fora de proposito: sao rotulo por decisao de
         * produto, mesmo existindo como sobrenome raro.
         */
        val NOMES_REAIS = listOf(
            // vizinhos conhecidos de rotulo
            "CASTELLA", "CANTELLA", "CARTELLI", "PAZIENTI", "SERVIDIO", "PATIENCE", "NASCIMBENI",
            "MEDICI", "MEDINA", "SEXTON", "REGIS", "IDALINA", "CHAMBERS", "CONVENTO", "LEITAO",
            "REGINA", "MATEUS", "MATOS", "MATTOS", "NASCIMENTO", "SALA", "ALTER", "ZIMMER", "WARD",
            "BORN", "MADRE", "CAMA", "ALEGRIA", "ALLEGRA", "PACIENCIA",
            "VITORIA", "VICTORIA", "DOCTOR", "CHARTERS", "SERVICE",
            // portugues
            "SILVA", "SANTOS", "OLIVEIRA", "SOUZA", "SOUSA", "RODRIGUES", "FERREIRA", "ALVES",
            "PEREIRA", "LIMA", "GOMES", "COSTA", "RIBEIRO", "MARTINS", "CARVALHO", "ALMEIDA",
            "LOPES", "SOARES", "FERNANDES", "VIEIRA", "BARBOSA", "ROCHA", "DIAS", "ANDRADE",
            "MOREIRA", "NUNES", "MARQUES", "MACHADO", "MENDES", "FREITAS", "CARDOSO", "RAMOS",
            "GONÇALVES", "SANTANA", "TEIXEIRA", "ARAÚJO", "PINTO", "CORREIA", "MONTEIRO", "MOURA",
            "CAVALCANTI", "CAVALCANTE", "BEZERRA", "MEDEIROS", "BRAGA", "CUNHA", "PIRES",
            "AZEVEDO", "CAMPOS", "CASTRO", "BORGES", "NOGUEIRA", "MIRANDA", "PAIVA", "BATISTA",
            "FARIAS", "QUEIROZ", "SAMPAIO", "BRITO", "TAVARES", "MELO", "DUARTE", "LEITE",
            "AMARAL", "MAGALHÃES", "VASCONCELOS", "BARROS", "GUIMARÃES", "SIQUEIRA", "PACHECO",
            "FONSECA", "PASSOS", "PRADO", "RESENDE", "REZENDE", "XAVIER", "CORDEIRO", "ASSIS",
            "AGUIAR", "MOTA", "DANTAS", "VELOSO", "FIGUEIREDO", "PEIXOTO", "BRANDÃO", "ROSÁRIO",
            "CONCEIÇÃO", "LACERDA", "SALES", "VALENTE", "MESQUITA", "ABREU", "CALDEIRA",
            "PROENÇA", "ESTEVES", "MEIRELES", "FALCÃO", "GALVÃO", "VILELA", "VIANA", "CHAVES",
            "PORTO", "BOTELHO", "CAMARGO", "FRANCO", "GODOY", "MARINHO", "NEVES", "TORRES",
            "ASSUNÇÃO", "PROCÓPIO", "PRESTES", "PROTÁSIO", "REGADAS", "MATRICARDI",
            // prenomes em portugues
            "MARIA", "ANA", "JOÃO", "JOSÉ", "ANTÔNIO", "FRANCISCO", "CARLOS", "PAULO", "PEDRO",
            "LUCAS", "LUIZ", "MARCOS", "GABRIEL", "RAFAEL", "DANIEL", "MARCELO", "BRUNO",
            "EDUARDO", "FELIPE", "RAIMUNDO", "RODRIGO", "MANOEL", "ANDRÉ", "FERNANDO", "FÁBIO",
            "LEONARDO", "GUSTAVO", "GUILHERME", "LEANDRO", "TIAGO", "ANDERSON", "RICARDO",
            "MÁRCIO", "JORGE", "SEBASTIÃO", "ALEXANDRE", "ROBERTO", "EDSON", "DIEGO", "VITOR",
            "SÉRGIO", "CLÁUDIO", "MATHEUS", "THIAGO", "GERALDO", "ADRIANO", "LUCIANO", "JÚLIO",
            "RENATO", "VINÍCIUS", "ROGÉRIO", "SAMUEL", "RONALDO", "FLÁVIO", "IGOR", "DOUGLAS",
            "CÍCERO", "MAURÍCIO", "DANILO", "HENRIQUE", "CAIO", "REGINALDO", "JOAQUIM",
            "BENEDITO", "GILBERTO", "CRISTIANO", "ELIAS", "WILSON", "VALDIR", "EMERSON", "SEVERINO",
            "FABRÍCIO", "MAURO", "JONAS", "GILMAR", "FABIANO", "WESLEY", "DIOGO", "ADILSON",
            "ALESSANDRO", "EVERTON", "OSVALDO", "WILLIAN", "SILVIO", "HÉLIO", "REINALDO",
            "FRANCISCA", "ANTÔNIA", "ADRIANA", "JULIANA", "MÁRCIA", "FERNANDA", "PATRÍCIA",
            "ALINE", "SANDRA", "CAMILA", "AMANDA", "BRUNA", "JÉSSICA", "LETÍCIA", "JÚLIA",
            "LUCIANA", "VANESSA", "MARIANA", "GABRIELA", "LARISSA", "CLÁUDIA", "BEATRIZ",
            "LUANA", "RITA", "SÔNIA", "RENATA", "ELIANE", "JOSEFA", "SIMONE", "NATÁLIA",
            "CRISTIANE", "CARLA", "DÉBORA", "ROSÂNGELA", "JAQUELINE", "DANIELA", "MÔNICA",
            "TATIANE", "LÚCIA", "RAIMUNDA", "ISABEL", "ELIZABETE", "LUZIA", "DAIANE", "KARINA",
            "ANDREIA", "TERESA", "TEREZINHA", "APARECIDA", "GRAZIELA", "PRISCILA", "SABRINA",
            "SILVANA", "MARLENE", "IVONE", "CÉLIA", "SOLANGE", "DENISE", "LAURA", "ALICE",
            "HELENA", "VALENTINA", "SOFIA", "CECÍLIA", "IRACEMA", "ODETE", "ZULMIRA", "GENOVEVA",
            "MATILDE", "MADALENA", "EMÍLIA", "AMÉLIA", "CONSOLAÇÃO", "PERPÉTUA", "PROVIDÊNCIA",
            // espanhol
            "GARCÍA", "RODRÍGUEZ", "GONZÁLEZ", "FERNÁNDEZ", "LÓPEZ", "MARTÍNEZ", "SÁNCHEZ",
            "PÉREZ", "GÓMEZ", "JIMÉNEZ", "RUIZ", "HERNÁNDEZ", "DÍAZ", "MORENO", "MUÑOZ",
            "ÁLVAREZ", "ROMERO", "ALONSO", "GUTIÉRREZ", "NAVARRO", "DOMÍNGUEZ", "VÁZQUEZ", "GIL",
            "RAMÍREZ", "SERRANO", "BLANCO", "MOLINA", "MORALES", "SUÁREZ", "ORTEGA", "DELGADO",
            "ORTIZ", "RUBIO", "MARÍN", "SANZ", "NÚÑEZ", "IGLESIAS", "GARRIDO", "CORTÉS",
            "CASTILLO", "LOZANO", "GUERRERO", "CANO", "PRIETO", "MÉNDEZ", "CRUZ", "CALVO",
            "GALLEGO", "VIDAL", "LEÓN", "HERRERA", "MÁRQUEZ", "PEÑA", "FLORES", "CABRERA", "VEGA",
            "FUENTES", "CARRASCO", "DIEZ", "CABALLERO", "REYES", "NIETO", "AGUILAR", "PASCUAL",
            "HERRERO", "LORENZO", "MONTERO", "HIDALGO", "GIMÉNEZ", "IBÁÑEZ", "FERRER", "DURÁN",
            "SANTIAGO", "BENÍTEZ", "MORA", "VICENTE", "VARGAS", "ARIAS", "CARMONA", "CRESPO",
            "ROMÁN", "PASTOR", "SOTO", "SÁEZ", "VELASCO", "MOYA", "SOLER", "PARRA", "ESTEBAN",
            "BRAVO", "GALLARDO", "ROJAS", "EXPÓSITO",
            // italiano
            "ROSSI", "RUSSO", "FERRARI", "ESPOSITO", "BIANCHI", "ROMANO", "COLOMBO", "RICCI",
            "MARINO", "GRECO", "GALLO", "CONTI", "MANCINI", "GIORDANO", "RIZZO", "LOMBARDI",
            "MORETTI", "BARBIERI", "FONTANA", "SANTORO", "MARIANI", "RINALDI", "CARUSO",
            "FERRARA", "GALLI", "MARTINI", "LEONE", "LONGO", "GENTILE", "MARTINELLI", "VITALE",
            "LOMBARDO", "SERRA", "COPPOLA", "MARCHETTI", "PARISI", "VILLA", "CONTE", "FERRARO",
            "FERRI", "FABBRI", "BIANCO", "MARINI", "GRASSO", "VALENTINI", "MESSINA", "GATTI",
            "PELLEGRINI", "PALUMBO", "SANNA", "FARINA", "RIZZI", "MONTI", "CATTANEO", "MORELLI",
            "AMATO", "SILVESTRI", "MAZZA", "TESTA", "PELLEGRINO", "CARBONE", "GIULIANI",
            "BENEDETTI", "BARONE", "ROSSETTI", "CAPUTO", "MONTANARI", "GUERRA", "PALMIERI",
            "BERNARDI", "MARTINO", "FIORE", "FERRETTI", "BELLINI", "BASILE", "RIVA", "DONATI",
            "PIRAS", "VITALI", "BATTAGLIA", "SARTORI", "NERI", "COSTANTINI", "MILANI", "PAGANO",
            "RUGGIERO", "SORRENTINO", "ORLANDO", "NEGRI", "CASTELLANI", "CASTELLO", "CASTELLI",
            "CANTELLI", "CARTELLONI", "NASCIMBENE", "NATALE", "LETTIERI", "STANZANI", "REPETTO",
            "GIUSEPPE", "GIOVANNI", "FRANCESCA", "ALESSANDRA", "LORENZA", "ASSUNTA", "CONCETTA",
            // frances
            "BERNARD", "THOMAS", "PETIT", "ROBERT", "RICHARD", "DURAND", "DUBOIS", "MOREAU",
            "LAURENT", "SIMON", "MICHEL", "LEFEBVRE", "LEROY", "ROUX", "BERTRAND", "MOREL",
            "FOURNIER", "GIRARD", "BONNET", "DUPONT", "LAMBERT", "FONTAINE", "ROUSSEAU",
            "VINCENT", "LEFÈVRE", "FAURE", "MERCIER", "BLANC", "GUÉRIN", "BOYER", "GARNIER",
            "CHEVALIER", "FRANÇOIS", "LEGRAND", "GAUTHIER", "PERRIN", "ROBIN", "CLÉMENT",
            "MORIN", "NICOLAS", "HENRY", "ROUSSEL", "MATHIEU", "MASSON", "MARCHAND", "DUVAL",
            "DENIS", "DUMONT", "LEMAIRE", "NOËL", "DUFOUR", "MEUNIER", "BRUN", "BLANCHARD",
            "GIRAUD", "JOLY", "RIVIÈRE", "BRUNET", "GAILLARD", "BARBIER", "ARNAUD", "GÉRARD",
            "ROCHE", "RENARD", "ROY", "LEROUX", "COLIN", "CARON", "PICARD", "ROGER", "FABRE",
            "AUBERT", "LEMOINE", "RENAUD", "DUMAS", "LACROIX", "OLIVIER", "PHILIPPE",
            "BOURGEOIS", "PIERRE", "BENOÎT", "LECLERC", "PAYET", "ROLLAND", "GUILLAUME",
            "LECOMTE", "DUPUY", "HUBERT", "BERGER", "CARPENTIER", "DUPUIS", "MOULIN",
            "DESCHAMPS", "HUET", "VASSEUR", "BOUCHER", "FLEURY", "ROYER", "JACQUET", "PARIS",
            "POIRIER", "MARTY", "AUBRY", "GUYOT", "CARRÉ", "CHARLES", "RENAULT", "CHARPENTIER",
            "MÉNARD", "MAILLARD", "BARON", "BERTIN", "BAILLY", "HERVÉ", "COLLET", "LÉGER",
            "BOUVIER", "JULIEN", "PRÉVOST", "MILLET", "PERROT", "COUSIN", "GERMAIN", "BRETON",
            "BESSON", "LANGLOIS", "RÉMY", "PELLETIER", "LEBLANC", "BARRE", "LEBRUN",
            "MARCHAL", "MALLET", "HAMON", "BOULANGER", "JACOB", "MONNIER", "MICHAUD",
            "CHAMBRON", "CHAMBRIER",
            // alemao
            "MÜLLER", "SCHMIDT", "SCHNEIDER", "FISCHER", "WEBER", "MEYER", "WAGNER", "BECKER",
            "SCHULZ", "HOFFMANN", "SCHÄFER", "KOCH", "BAUER", "RICHTER", "KLEIN", "WOLF",
            "SCHRÖDER", "NEUMANN", "SCHWARZ", "ZIMMERMANN", "BRAUN", "KRÜGER", "HOFMANN",
            "HARTMANN", "LANGE", "SCHMITT", "WERNER", "SCHMITZ", "KRAUSE", "MEIER", "LEHMANN",
            "SCHMID", "SCHULZE", "MAIER", "KÖHLER", "HERRMANN", "KÖNIG", "WALTER", "MAYER",
            "HUBER", "KAISER", "FUCHS", "PETERS", "LANG", "SCHOLZ", "MÖLLER", "WEISS", "JUNG",
            "HAHN", "SCHUBERT", "VOGEL", "FRIEDRICH", "KELLER", "GÜNTHER", "FRANK", "WINKLER",
            "ROTH", "BECK", "LORENZ", "BAUMANN", "FRANKE", "ALBRECHT", "SCHUSTER", "LUDWIG",
            "BÖHM", "WINTER", "KRAUS", "SCHUMACHER", "KRÄMER", "VOGT", "STEIN", "JÄGER", "OTTO",
            "SOMMER", "GROSS", "SEIDEL", "HEINRICH", "BRANDT", "HAAS", "SCHREIBER", "GRAF",
            "SCHULTE", "DIETRICH", "ZIEGLER", "KUHN", "POHL", "ENGEL", "HORN", "BUSCH",
            "BERGMANN", "VOIGT", "SAUER", "ARNOLD", "WOLFF", "PFEIFFER", "ALTERMANN",
            "GEBHARDT", "GEBAUER", "NACHTMANN",
            // polones
            "NOWAK", "KOWALSKI", "WIŚNIEWSKI", "WÓJCIK", "KOWALCZYK", "KAMIŃSKI", "LEWANDOWSKI",
            "ZIELIŃSKI", "SZYMAŃSKI", "WOŹNIAK", "DĄBROWSKI", "KOZŁOWSKI", "JANKOWSKI", "MAZUR",
            "KWIATKOWSKI", "WOJCIECHOWSKI", "KRAWCZYK", "KACZMAREK", "PIOTROWSKI", "GRABOWSKI",
            "ZAJĄC", "PAWŁOWSKI", "KRÓL", "MICHALSKI", "WRÓBLEWSKI", "WIECZOREK", "JABŁOŃSKI",
            "NOWAKOWSKI", "MAJEWSKI", "OLSZEWSKI", "STĘPIEŃ", "MALINOWSKI", "JAWORSKI",
            "ADAMCZYK", "DUDEK", "NOWICKI", "PAWLAK", "GÓRSKI", "WITKOWSKI", "WALCZAK", "SIKORA",
            "BARAN", "RUTKOWSKI", "MICHALAK", "SZEWCZYK", "OSTROWSKI", "TOMASZEWSKI",
            "PIETRZAK", "ZALEWSKI", "WRÓBEL", "MARCINIAK", "JASIŃSKI", "ZAWADZKI", "JAKUBOWSKI",
            "SADOWSKI", "DUDA", "WILK", "CHMIELEWSKI", "WŁODARCZYK", "BORKOWSKI", "SOKOŁOWSKI",
            "SZCZEPAŃSKI", "SAWICKI", "ŁUCZAK", "KUCHARSKI", "MACIEJEWSKI", "KALINOWSKI",
            "WYSOCKI", "MAZUREK", "KUBIAK", "KOŁODZIEJ", "CZARNECKI", "URBAŃSKI", "SOBCZAK",
            "KONIECZNY", "GŁOWACKI", "ZAKRZEWSKI", "KRAJEWSKI", "KRUPA", "LASKOWSKI",
            "ZIÓŁKOWSKI", "GAJEWSKI", "MRÓZ", "BRZEZIŃSKI", "SZULC", "MAKOWSKI", "PRZYBYLSKI",
            "KOWALSKA", "NOWAKOWSKA", "LEKARSKI",
            "AGNIESZKA", "MAŁGORZATA", "KATARZYNA", "BARBARA", "MAGDALENA", "ELŻBIETA",
            "JOANNA", "ALEKSANDRA", "MONIKA", "KRZYSZTOF", "TOMASZ", "PIOTR", "PAWEŁ", "MICHAŁ",
            // ingles
            "SMITH", "JOHNSON", "WILLIAMS", "BROWN", "JONES", "MILLER", "DAVIS", "WILSON",
            "TAYLOR", "MOORE", "JACKSON", "MARTIN", "LEE", "THOMPSON", "WHITE", "HARRIS",
            "CLARK", "LEWIS", "ROBINSON", "WALKER", "YOUNG", "ALLEN", "KING", "WRIGHT", "SCOTT",
            "NGUYEN", "HILL", "GREEN", "ADAMS", "NELSON", "BAKER", "HALL", "RIVERA", "CAMPBELL",
            "MITCHELL", "CARTER", "ROBERTS", "PHILLIPS", "EVANS", "TURNER", "PARKER",
            "EDWARDS", "COLLINS", "STEWART", "MORRIS", "MURPHY", "COOK", "ROGERS", "MORGAN",
            "COOPER", "PETERSON", "BAILEY", "REED", "KELLY", "HOWARD", "KIM", "COX",
            "RICHARDSON", "WATSON", "BROOKS", "CHAVEZ", "WOOD", "JAMES", "BENNETT", "GRAY",
            "MENDOZA", "HUGHES", "PRICE", "SANDERS", "PATEL", "MYERS", "LONG", "ROSS", "FOSTER",
            "POWELL", "JENKINS", "PERRY", "RUSSELL", "SULLIVAN", "BELL", "COLEMAN", "BUTLER",
            "HENDERSON", "BARNES", "FISHER", "SIMMONS", "JORDAN", "PATTERSON", "ALEXANDER",
            "HAMILTON", "GRAHAM", "REYNOLDS", "GRIFFIN", "WALLACE", "WEST", "COLE", "HAYES",
            "BRYANT", "GIBSON", "ELLIS", "STEVENS", "MURRAY", "FORD", "MARSHALL", "OWENS",
            "HARRISON", "MCDONALD", "WOODS", "WASHINGTON", "KENNEDY", "WELLS", "FREEMAN",
            "WEBB", "TUCKER", "BURNS", "CRAWFORD", "OLSON", "SIMPSON", "PORTER", "HUNTER",
            "GORDON", "SHAW", "SNYDER", "MASON", "DIXON", "HUNT", "HICKS", "HOLMES", "PALMER",
            "BLACK", "ROBERTSON", "BOYD", "ROSE", "STONE", "FOX", "WARREN", "MILLS", "RICE",
            "DANIELS", "FERGUSON", "NICHOLS", "STEPHENS", "WEAVER", "RYAN", "GARDNER", "PAYNE",
            "GRANT", "DUNN", "SPENCER", "HAWKINS", "PIERCE", "HANSEN", "HART", "BRADLEY",
            "KNIGHT", "ELLIOTT", "CUNNINGHAM", "DUNCAN", "ARMSTRONG", "HUDSON", "CARROLL",
            "LANE", "RILEY", "ANDREWS", "BERRY", "PERKINS", "JOHNSTON", "MATTHEWS", "RICHARDS",
            "WILLIS", "CARPENTER", "LAWRENCE", "WARDEN", "WARDLE",
            "BIRTHE", "PHYSICK", "PATTISON", "DOCKERY", "RECORDON",
            "MARGARET", "ELIZABETH", "JENNIFER", "PATRICIA", "CHRISTOPHER", "JONATHAN",
            "STEPHANIE", "KIMBERLY", "PRISCILLA", "GENEVIEVE", "CONSTANCE", "PRUDENCE",
        )
    }
}
