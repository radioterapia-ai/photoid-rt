package com.radioterapia.ai.transfer

import com.radioterapia.ai.sync.PerfilSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que senha guardada continua valendo depois de importar um pacote.
 *
 * A senha nunca viaja no pacote, mas o destino viaja. Perfil ou configuração
 * de rede que chega apontando para outro servidor, outra conta ou outra pasta
 * não pode herdar a senha deste aparelho: ela seria entregue ao endereço novo
 * na primeira conexão, sem o aviso de senha ausente, porque ela existe.
 */
class PacoteConfigPerfisTest {

    private fun smb(id: String) = PerfilSync(
        id = id,
        nome = "Servidor da radioterapia",
        tipo = PerfilSync.Tipo.SMB,
        host = "10.0.0.5",
        porta = 445,
        usuario = "tablet_a",
        protocolo = "SMB3",
        share = "fotos",
        dominio = "HOSPITAL",
        caminhoRemoto = "RT/PHOTOID")

    // ---------- perfis de sincronização ----------

    @Test
    fun `mesmo destino com nome estado e verificacao de pastas diferentes mantem a senha`() {
        val local = smb("p1")
        val vindo = local.copy(
            nome = "Outro nome",
            ativo = false,
            injecaoDireta = !local.injecaoDireta,
            ultimaSincronizacao = 123L,
            ultimoErro = "erro antigo")

        assertTrue(PacoteConfig.mesmoDestino(local, vindo))
        assertTrue(PacoteConfig.idsQuePerdemASenha(listOf(local), listOf(vindo)).isEmpty())
    }

    @Test
    fun `qualquer campo que decide o destino faz o perfil perder a senha`() {
        val local = smb("p1")
        val variantes = mapOf(
            "tipo" to local.copy(tipo = PerfilSync.Tipo.FTP),
            "host" to local.copy(host = "10.9.9.9"),
            "porta" to local.copy(porta = 4450),
            "usuario" to local.copy(usuario = "svc_b"),
            "protocolo" to local.copy(protocolo = "SMB2"),
            "share" to local.copy(share = "outra"),
            "dominio" to local.copy(dominio = "UNIDADE_B"),
            "urlBase" to local.copy(urlBase = "https://nas/dav/"),
            "safUri" to local.copy(safUri = "content://tree/x"),
            "caminhoRemoto" to local.copy(caminhoRemoto = "OUTRA/PASTA"))

        variantes.forEach { (campo, vindo) ->
            assertFalse(campo, PacoteConfig.mesmoDestino(local, vindo))
            assertEquals(campo, setOf("p1"),
                PacoteConfig.idsQuePerdemASenha(listOf(local), listOf(vindo)))
        }
    }

    @Test
    fun `perfil com id que nao existia aqui chega sem senha`() {
        val local = smb("p1")
        val novo = smb("p2")
        assertEquals(setOf("p2"),
            PacoteConfig.idsQuePerdemASenha(listOf(local), listOf(local, novo)))
    }

    @Test
    fun `aparelho sem perfil nenhum faz todo perfil importado pedir a senha`() {
        val importados = listOf(smb("p1"), smb("p2"))
        assertEquals(setOf("p1", "p2"), PacoteConfig.idsQuePerdemASenha(emptyList(), importados))
    }

    @Test
    fun `id em branco nunca entra na lista`() {
        val semId = smb("")
        assertTrue(PacoteConfig.idsQuePerdemASenha(emptyList(), listOf(semId)).isEmpty())
    }

    @Test
    fun `so os importados contam e o local que o pacote nao traz nao entra`() {
        val ficou = smb("p1")
        val sumiu = smb("p9")
        assertTrue(PacoteConfig.idsQuePerdemASenha(listOf(ficou, sumiu), listOf(ficou)).isEmpty())
    }

    // ---------- rede SMB de antes dos perfis ----------

    @Test
    fun `chaves da rede antiga sao as do item Rede`() {
        assertEquals(
            setOf("smb_host", "smb_porta", "smb_protocolo", "smb_dominio",
                "smb_usuario", "smb_caminho_unc"),
            PacoteConfig.Item.REDE.prefs.toSet())
    }

    @Test
    fun `endereco usuario ou pasta da rede antiga diferentes derrubam a senha global`() {
        assertTrue(PacoteConfig.mudaDestinoSmb("smb_caminho_unc",
            "\\\\10.0.0.5\\fotos", "\\\\10.9.9.9\\x"))
        assertTrue(PacoteConfig.mudaDestinoSmb("smb_usuario", "tablet_a", "svc_b"))
        assertTrue(PacoteConfig.mudaDestinoSmb("smb_host", "10.0.0.5", "10.9.9.9"))
        assertTrue(PacoteConfig.mudaDestinoSmb("smb_dominio", "HOSPITAL", "UNIDADE_B"))
        assertTrue(PacoteConfig.mudaDestinoSmb("smb_protocolo", "SMB3", "SMB1"))
        assertTrue(PacoteConfig.mudaDestinoSmb("smb_porta", 445, 4450))
    }

    @Test
    fun `mesmo valor na rede antiga mantem a senha global`() {
        assertFalse(PacoteConfig.mudaDestinoSmb("smb_caminho_unc",
            "\\\\10.0.0.5\\fotos", "\\\\10.0.0.5\\fotos"))
        assertFalse(PacoteConfig.mudaDestinoSmb("smb_usuario", "tablet_a", "tablet_a"))
        // Número gravado como texto num aparelho e como inteiro no pacote.
        assertFalse(PacoteConfig.mudaDestinoSmb("smb_porta", "445", 445))
    }

    @Test
    fun `campo vazio aqui preenchido pelo pacote derruba a senha global`() {
        // Somar preenche a chave em branco: o destino deixa de ser o de antes.
        assertTrue(PacoteConfig.mudaDestinoSmb("smb_host", null, "10.9.9.9"))
        assertTrue(PacoteConfig.mudaDestinoSmb("smb_host", "", "10.9.9.9"))
        // Ausente e vazio são o mesmo destino.
        assertFalse(PacoteConfig.mudaDestinoSmb("smb_dominio", null, ""))
    }

    @Test
    fun `chave fora da rede antiga nao mexe na senha global`() {
        assertFalse(PacoteConfig.mudaDestinoSmb("nome_clinica", "Unidade A", "Unidade B"))
        assertFalse(PacoteConfig.mudaDestinoSmb("printer_ip", "10.0.0.7", "10.9.9.7"))
    }
}
