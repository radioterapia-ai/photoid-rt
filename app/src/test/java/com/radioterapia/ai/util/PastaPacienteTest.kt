package com.radioterapia.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Resolução da pasta do paciente.
 *
 * Cada caso aqui nasceu de um defeito que grava ou lê no paciente ERRADO — o
 * tipo de falha que não aparece na tela e só é descoberta quando alguém nota
 * que a ficha de uma paciente traz o sítio de tratamento de outra.
 *
 * Não tocam em `Context`: só a formação e a comparação de nomes, que é onde os
 * defeitos estavam.
 */
class PastaPacienteTest {

    // ---------- nome da pasta ----------

    @Test
    fun `pasta leva nome e prontuario`() {
        assertEquals("MARIA SILVA - 123",
            StorageLocal.nomePastaPaciente("Maria Silva", "123"))
    }

    @Test
    fun `sem prontuario a pasta fica so com o nome`() {
        assertEquals("MARIA SILVA", StorageLocal.nomePastaPaciente("Maria Silva", ""))
    }

    @Test
    fun `acento sai do nome da pasta`() {
        assertEquals("JOAO CONCEICAO - 9",
            StorageLocal.nomePastaPaciente("João Conceição", "9"))
    }

    /**
     * Hífen e apóstrofo são LEGAIS em nome de arquivo e não podem ser
     * removidos: a normalização que os apagava criava a pasta "MARIA DARC"
     * enquanto a busca procurava "MARIA D'ARC", e a pasta ficava invisível
     * para o próprio app.
     */
    @Test
    fun `apostrofo e hifen sobrevivem no nome da pasta`() {
        assertEquals("MARIA D'ARC SILVA-SOUZA - 5",
            StorageLocal.nomePastaPaciente("Maria D'Arc Silva-Souza", "5"))
    }

    // ---------- casamento pasta x paciente ----------

    @Test
    fun `casa a pasta do proprio paciente`() {
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA - 123", "Maria Silva"))
    }

    @Test
    fun `casa pasta antiga com separador underscore`() {
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA_123", "Maria Silva"))
    }

    /** Compatibilidade: pastas gravadas pela normalização antiga, sem apóstrofo. */
    @Test
    fun `casa pasta legada gravada sem apostrofo`() {
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA DARC - 5", "Maria D'Arc"))
    }

    /**
     * O caso que motivou tudo: "ANA" NÃO pode adotar a pasta de "ANA MARIA".
     * O sufixo aceito depois do nome é só o prontuário (dígitos).
     */
    @Test
    fun `nome que e prefixo de outro nao casa`() {
        assertFalse(StorageLocal.pastaCasaPaciente("ANA MARIA - 999", "Ana"))
    }

    @Test
    fun `homonimas com prontuarios diferentes sao pastas distintas`() {
        val a = StorageLocal.nomePastaPaciente("Maria Silva", "123")
        val b = StorageLocal.nomePastaPaciente("Maria Silva", "456")
        assertFalse("homônimas não podem compartilhar pasta", a == b)
    }

    // ---------- leitura de volta ----------

    @Test
    fun `extrai prontuario da pasta`() {
        assertEquals("123", StorageLocal.prontuarioDaPasta("MARIA SILVA - 123"))
    }

    @Test
    fun `extrai prontuario ignorando sufixo de reirradiacao`() {
        assertEquals("123",
            StorageLocal.prontuarioDaPasta("MARIA SILVA - 123 NOVA SIMULACAO 1"))
    }

    @Test
    fun `pasta sem prontuario devolve vazio`() {
        assertEquals("", StorageLocal.prontuarioDaPasta("MARIA SILVA"))
    }

    @Test
    fun `extrai nome da pasta sem prontuario nem sufixo`() {
        assertEquals("MARIA SILVA",
            StorageLocal.nomeDaPasta("MARIA SILVA - 123 NOVA SIMULACAO 2"))
    }

    // ---------- prontuario NAO numerico (relato de campo, 21/09/2026) ----------

    /**
     * O prontuario nao precisa ser numerico, e a pasta nao se importa.
     *
     * `nomePastaPaciente` grava o prontuario COMO FOI DIGITADO, e servico
     * nenhum e obrigado a usar numero puro — "RT-2024-001" e "A1234" sao
     * formatos comuns. A comparacao exigia digitos no sufixo, entao a pasta
     * existia, com as fotos dentro, e o modulo de Tratamento respondia
     * "paciente nao encontrado". Pelo cadastro o paciente seguia la, com card
     * e miniatura — que e como o defeito aparece para quem usa.
     */
    @Test
    fun `pasta casa com prontuario alfanumerico`() {
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA - RT2024", "Maria Silva"))
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA - RT-2024-001", "Maria Silva"))
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA - A1234", "Maria Silva"))
    }

    @Test
    fun `pasta com prontuario numerico continua casando`() {
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA - 123456", "Maria Silva"))
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA_123456", "Maria Silva"))
        assertTrue(StorageLocal.pastaCasaPaciente("MARIA SILVA", "Maria Silva"))
    }

    /**
     * A regra nova nao pode virar "casa com qualquer um": o sobrenome a mais
     * precisa continuar sendo OUTRO paciente, senao a ficha de uma sai com as
     * fotos de outra — que e o defeito que este arquivo inteiro existe para
     * impedir.
     */
    @Test
    fun `nome mais longo continua sendo outro paciente`() {
        assertFalse(StorageLocal.pastaCasaPaciente("MARIA SILVA SANTOS - 99", "Maria Silva"))
        assertFalse(StorageLocal.pastaCasaPaciente("MARIA SILVA SANTOS", "Maria Silva"))
        assertFalse(StorageLocal.pastaCasaPaciente("JOAO SILVA - RT2024", "Maria Silva"))
    }

    // ---------- as pastas que a EXCLUSAO vai apagar ----------

    @get:Rule
    val tmp = TemporaryFolder()

    private fun photos(vararg nomes: String): java.io.File {
        val base = tmp.newFolder("PHOTOS")
        for (n in nomes) java.io.File(base, n).mkdirs()
        return base
    }

    @Test
    fun `exclusao nao leva a pasta da homonima`() {
        // O defeito: o casamento era so por nome, e o listFiles decidia qual
        // das duas Marias perdia as fotos.
        val base = photos("MARIA SILVA - 123", "MARIA SILVA - 456")
        val achadas = StorageLocal.pastasDoPaciente(base, "Maria Silva", "456")
        assertEquals(1, achadas.size)
        assertEquals("MARIA SILVA - 456", achadas[0].name)
    }

    @Test
    fun `exclusao leva tambem as pastas de reirradiacao`() {
        val base = photos("MARIA SILVA - 123",
                          "MARIA SILVA - 123 NOVA SIMULACAO 1",
                          "MARIA SILVA - 123 NOVA SIMULACAO 2")
        val achadas = StorageLocal.pastasDoPaciente(base, "Maria Silva", "123")
        assertEquals(3, achadas.size)
    }

    @Test
    fun `reirradiacao da homonima fica de fora`() {
        val base = photos("MARIA SILVA - 123",
                          "MARIA SILVA - 456 NOVA SIMULACAO 1")
        val achadas = StorageLocal.pastasDoPaciente(base, "Maria Silva", "123")
        assertEquals(1, achadas.size)
        assertEquals("MARIA SILVA - 123", achadas[0].name)
    }

    @Test
    fun `na duvida nao apaga nada`() {
        // Prontuario informado e nenhuma pasta com ele: devolver a pasta "mais
        // parecida" seria apagar a pessoa errada. Lista vazia e a resposta.
        val base = photos("MARIA SILVA - 123", "MARIA SILVA - 456")
        assertEquals(0, StorageLocal.pastasDoPaciente(base, "Maria Silva", "999").size)
    }

    @Test
    fun `prefixo de nome nao arrasta a pasta da outra`() {
        // "ANA" casa por prefixo com "ANA MARIA - 999".
        val base = photos("ANA MARIA - 999")
        assertEquals(0, StorageLocal.pastasDoPaciente(base, "Ana", "111").size)
    }

    @Test
    fun `pasta antiga sem prontuario no nome ainda e encontrada`() {
        // Base gravada antes de o prontuario entrar no nome da pasta.
        val base = photos("MARIA SILVA")
        val achadas = StorageLocal.pastasDoPaciente(base, "Maria Silva", "123")
        assertEquals(1, achadas.size)
        assertEquals("MARIA SILVA", achadas[0].name)
    }
}
