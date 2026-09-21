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
 * Cada caso deste arquivo veio de uma etiqueta real.
 */
class EtiquetaParserTest {

    // ---------- rotulo de campo nunca e nome (relato de campo, 21/09/2026) ----------

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
}
