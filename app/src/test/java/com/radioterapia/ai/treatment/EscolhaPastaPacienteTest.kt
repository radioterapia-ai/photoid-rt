package com.radioterapia.ai.treatment

import com.radioterapia.ai.treatment.TreatmentPhotoFetcher.DecisaoPasta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Escolha da pasta do paciente entre homônimas.
 *
 * "MARIA DA SILVA - 1001" e "MARIA DA SILVA - 2002" casam as duas pelo nome.
 * Pegar a primeira da listagem leria as fotos, a ficha e os alertas da outra,
 * e gravaria nela: a foto de rosto nova iria para a pasta da homônima, o rosto
 * dela iria para as arquivadas e a ficha dela seria substituída.
 *
 * Não tocam em Context: só a decisão entre nomes de pasta.
 */
class EscolhaPastaPacienteTest {

    private val nome = "Maria da Silva"
    private val p1001 = "MARIA DA SILVA - 1001"
    private val p2002 = "MARIA DA SILVA - 2002"
    private val antiga = "MARIA DA SILVA"
    private val outra = "JOSE SOUZA - 77"

    private fun aceitas(pastas: List<String>, pront: String) =
        TreatmentPhotoFetcher.escolherPastasPaciente(pastas, nome, pront).aceitas

    // ---------- prontuário da pasta ----------

    @Test
    fun `prontuario da pasta no formato novo`() {
        assertEquals("1001", TreatmentPhotoFetcher.prontuarioDaPastaDoPaciente(p1001, nome))
    }

    @Test
    fun `sufixo de reirradiacao nao entra no prontuario`() {
        assertEquals("1001", TreatmentPhotoFetcher.prontuarioDaPastaDoPaciente(
            "MARIA DA SILVA - 1001 NOVA SIMULACAO 1", nome))
    }

    @Test
    fun `pasta so com o nome nao tem prontuario`() {
        assertEquals("", TreatmentPhotoFetcher.prontuarioDaPastaDoPaciente(antiga, nome))
    }

    @Test
    fun `nome com hifen por dentro nao vira prontuario`() {
        assertEquals("", TreatmentPhotoFetcher.prontuarioDaPastaDoPaciente("ANA - MARIA", "Ana - Maria"))
        assertEquals("9", TreatmentPhotoFetcher.prontuarioDaPastaDoPaciente("ANA - MARIA - 9", "Ana - Maria"))
    }

    /**
     * Pasta legada "NOME_1001" traz o prontuário colado ao nome. Lida como
     * "sem prontuário", passaria por pasta antiga da paciente 2002.
     */
    @Test
    fun `pasta legada com prontuario colado ao nome conta como prontuario`() {
        assertEquals("1001", TreatmentPhotoFetcher.prontuarioDaPastaDoPaciente("MARIA DA SILVA_1001", nome))
        assertEquals(listOf(p2002), aceitas(listOf("MARIA DA SILVA_1001", p2002), "2002"))
    }

    // ---------- leitura com prontuário ----------

    @Test
    fun `com prontuario so entra a pasta dele`() {
        assertEquals(listOf(p2002), aceitas(listOf(p1001, p2002, outra), "2002"))
        assertEquals(listOf(p1001), aceitas(listOf(p2002, p1001), "1001"))
    }

    @Test
    fun `prontuario comparado so por letras e digitos e sem caixa`() {
        assertEquals(listOf("MARIA DA SILVA - RT-1001"),
            aceitas(listOf("MARIA DA SILVA - RT-1001", p2002), "rt1001"))
    }

    @Test
    fun `pasta de prontuario divergente nunca entra`() {
        assertTrue(aceitas(listOf(p1001), "2002").isEmpty())
    }

    @Test
    fun `pasta antiga entra quando nenhuma homonima traz outro prontuario`() {
        assertEquals(listOf(p2002, antiga), aceitas(listOf(antiga, p2002), "2002"))
        assertEquals(listOf(antiga), aceitas(listOf(antiga), "2002"))
    }

    @Test
    fun `pasta antiga fica de fora quando ha homonima de outro prontuario`() {
        assertEquals(listOf(p2002), aceitas(listOf(antiga, p1001, p2002), "2002"))
        assertTrue(aceitas(listOf(antiga, p1001), "2002").isEmpty())
    }

    @Test
    fun `nome mais curto nao adota a pasta de nome mais longo`() {
        val e = TreatmentPhotoFetcher.escolherPastasPaciente(listOf("ANA MARIA - 999"), "Ana", "")
        assertTrue(e.candidatas.isEmpty())
    }

    // ---------- leitura sem prontuário ----------

    @Test
    fun `sem prontuario devolve todas e marca ambigua com homonimas`() {
        val e = TreatmentPhotoFetcher.escolherPastasPaciente(listOf(p1001, p2002, outra), nome, "")
        assertEquals(listOf(p1001, p2002), e.aceitas)
        assertTrue(e.ambigua)
    }

    @Test
    fun `sem prontuario pasta antiga ao lado de pasta com prontuario e ambigua`() {
        assertTrue(TreatmentPhotoFetcher.escolherPastasPaciente(listOf(antiga, p1001), nome, "").ambigua)
    }

    @Test
    fun `sem prontuario um paciente so nao e ambiguo`() {
        val e = TreatmentPhotoFetcher.escolherPastasPaciente(
            listOf(p1001, "MARIA DA SILVA - 1001 NOVA SIMULACAO 1"), nome, "")
        assertFalse(e.ambigua)
        assertFalse(TreatmentPhotoFetcher.escolherPastasPaciente(listOf(antiga), nome, "").ambigua)
    }

    // ---------- gravação ----------

    @Test
    fun `grava na pasta aberta quando ela e deste prontuario`() {
        assertEquals(DecisaoPasta.Existente(p2002),
            TreatmentPhotoFetcher.decidirPasta(listOf(p1001, p2002), nome, "2002", p2002))
    }

    @Test
    fun `pasta aberta de prontuario divergente e recusada e nao trocada por outra`() {
        assertSame(DecisaoPasta.Incerta,
            TreatmentPhotoFetcher.decidirPasta(listOf(p1001, p2002), nome, "2002", p1001))
    }

    @Test
    fun `sem prontuario e com homonimas recusa mesmo com a pasta aberta`() {
        assertSame(DecisaoPasta.Incerta,
            TreatmentPhotoFetcher.decidirPasta(listOf(p1001, p2002), nome, "", p2002))
    }

    @Test
    fun `pasta aberta fora do PHOTOS cai na regra do prontuario`() {
        assertEquals(DecisaoPasta.Existente(p2002), TreatmentPhotoFetcher.decidirPasta(
            listOf(p1001, p2002), nome, "2002", "MARIA DA SILVA POSICIONAMENTO"))
    }

    @Test
    fun `cria so quando nenhuma pasta casa com o nome`() {
        assertSame(DecisaoPasta.Nenhuma,
            TreatmentPhotoFetcher.decidirPasta(listOf(outra), nome, "2002", ""))
        assertSame(DecisaoPasta.Nenhuma,
            TreatmentPhotoFetcher.decidirPasta(emptyList(), nome, "", ""))
    }

    @Test
    fun `so a pasta de outro prontuario e recusada, nao criada`() {
        assertSame(DecisaoPasta.Incerta,
            TreatmentPhotoFetcher.decidirPasta(listOf(p1001), nome, "2002", ""))
    }

    @Test
    fun `entre a pasta canonica e a antiga fica a canonica`() {
        assertEquals(DecisaoPasta.Existente(p2002),
            TreatmentPhotoFetcher.decidirPasta(listOf(antiga, p2002), nome, "2002", ""))
    }

    @Test
    fun `sem prontuario e um paciente so usa a pasta dele`() {
        assertEquals(DecisaoPasta.Existente(p1001),
            TreatmentPhotoFetcher.decidirPasta(listOf(p1001, outra), nome, "", ""))
    }

    /**
     * A única pasta do tablet não desempata quando o cadastro tem outro
     * registro do mesmo nome: ela pode ser a da homônima, e este paciente,
     * sem prontuário, não ter pasta nenhuma aqui.
     */
    @Test
    fun `sem prontuario e com homonimas no cadastro nenhuma pasta e certa`() {
        assertSame(DecisaoPasta.Incerta,
            TreatmentPhotoFetcher.decidirPasta(listOf(p1001), nome, "", p1001, homonimosNoCadastro = true))
        assertSame(DecisaoPasta.Incerta,
            TreatmentPhotoFetcher.decidirPasta(emptyList(), nome, "", "", homonimosNoCadastro = true))
    }

    @Test
    fun `homonimas no cadastro nao pesam quando ha prontuario`() {
        assertEquals(DecisaoPasta.Existente(p2002), TreatmentPhotoFetcher.decidirPasta(
            listOf(p1001, p2002), nome, "2002", p2002, homonimosNoCadastro = true))
    }

    // ---------- simulações ----------

    private fun sim(pasta: String, num: Int, t: Long) = TreatmentPhotoFetcher.Simulacao(
        nomePaciente = "MARIA DA SILVA", nomePastaCompleto = pasta, numeroSimulacao = num,
        timestampPrincipal = t, fotos = listOf(TreatmentPhotoFetcher.FotoInfo(
            File("x.jpg"), "x.jpg", TreatmentPhotoFetcher.TipoFoto.ROSTO, t)),
        origem = TreatmentPhotoFetcher.Origem.LOCAL_TABLET)

    @Test
    fun `simulacao exata pela pasta e pelo numero, sem cair na de outra pasta`() {
        val a = sim(p1001, 1, 10L)
        val b = sim(p2002, 1, 20L)
        assertSame(a, TreatmentPhotoFetcher.simulacaoExata(listOf(b, a), 1, p1001))
        assertNull(TreatmentPhotoFetcher.simulacaoExata(listOf(b), 1, p1001))
        assertNull(TreatmentPhotoFetcher.simulacaoExata(listOf(a), 2, p1001))
        assertSame(b, TreatmentPhotoFetcher.simulacaoExata(listOf(a, b), 1, "SERVIDOR", null, p2002))
    }

    @Test
    fun `sem prontuario e com homonimas nenhuma simulacao e atribuida`() {
        val sims = listOf(sim(p1001, 1, 10L), sim(p2002, 1, 20L))
        assertTrue(TreatmentPhotoFetcher.simulacoesInequivocas(sims, nome, "").isEmpty())
        assertEquals(listOf(sims[0]), TreatmentPhotoFetcher.simulacoesInequivocas(sims, nome, "1001"))
    }

    @Test
    fun `simulacao de pasta fora da convencao passa como veio`() {
        val servidor = sim("MARIA DA SILVA POSICIONAMENTO", 1, 30L)
        assertEquals(listOf(servidor),
            TreatmentPhotoFetcher.simulacoesInequivocas(listOf(servidor), nome, "1001"))
    }

    @Test
    fun `sem prontuario e com homonimas no cadastro a unica pasta nao e atribuida`() {
        val a = sim(p1001, 1, 10L)
        val servidor = sim("MARIA DA SILVA POSICIONAMENTO", 1, 30L)
        assertEquals(listOf(servidor), TreatmentPhotoFetcher.simulacoesInequivocas(
            listOf(a, servidor), nome, "", homonimosNoCadastro = true))
        assertEquals(listOf(a), TreatmentPhotoFetcher.simulacoesInequivocas(
            listOf(a), nome, "1001", homonimosNoCadastro = true))
    }

    // ---------- pasta de registros (Time-Out e observações) ----------

    @Test
    fun `pasta de registros com o nome da pasta aberta serve`() {
        assertTrue(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            p2002, p2002, listOf(p1001, p2002), nome, ""))
    }

    /**
     * O resolvedor, sem prontuário, fica com a primeira homônima de nome
     * exato; com prontuário, aceita a pasta antiga ao lado de uma homônima de
     * outro prontuário. Nos dois casos o Time-Out seria o da outra.
     */
    @Test
    fun `pasta de registros de homonima nao serve`() {
        assertFalse(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            p1001, "MARIA DA SILVA POSICIONAMENTO", listOf(p1001, p2002), nome, ""))
        assertFalse(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            antiga, null, listOf(antiga, p1001), nome, "2002"))
        assertFalse(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            p1001, null, listOf(p1001, p2002), nome, "2002"))
    }

    @Test
    fun `pasta de registros aceita pela regra serve`() {
        assertTrue(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            p2002, "MARIA DA SILVA POSICIONAMENTO", listOf(p1001, p2002), nome, "2002"))
        assertTrue(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            antiga, null, listOf(antiga), nome, "2002"))
    }

    @Test
    fun `pasta de registros que nem casa pelo nome nao serve`() {
        assertFalse(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            "ANA MARIA - 999", null, listOf("ANA MARIA - 999"), "Ana", ""))
    }

    @Test
    fun `pasta de registros sem prontuario e com homonimas no cadastro so a confirmada`() {
        assertFalse(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            p1001, null, listOf(p1001), nome, "", homonimosNoCadastro = true))
        assertTrue(TreatmentPhotoFetcher.pastaDeRegistrosServe(
            p1001, p1001, listOf(p1001), nome, "", homonimosNoCadastro = true))
    }

    // ---------- cadastro ----------

    @Test
    fun `cadastro com prontuario pedido serve so se o prontuario bate`() {
        assertTrue(TreatmentPhotoFetcher.cadastroServeAoPaciente("2002", "2002", true))
        assertTrue(TreatmentPhotoFetcher.cadastroServeAoPaciente("RT-2002", "rt2002", true))
        assertTrue(TreatmentPhotoFetcher.cadastroServeAoPaciente("2002", "", true))
        assertFalse(TreatmentPhotoFetcher.cadastroServeAoPaciente("2002", "1001", false))
    }

    @Test
    fun `cadastro sem prontuario pedido nao empresta o da homonima`() {
        assertFalse(TreatmentPhotoFetcher.cadastroServeAoPaciente("", "1001", false))
        assertFalse(TreatmentPhotoFetcher.cadastroServeAoPaciente("", "", true))
        assertTrue(TreatmentPhotoFetcher.cadastroServeAoPaciente("", "", false))
    }
}
