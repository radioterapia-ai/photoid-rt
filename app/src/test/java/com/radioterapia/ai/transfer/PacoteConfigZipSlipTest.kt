package com.radioterapia.ai.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * O importador do pacote nao pode gravar fora da pasta de destino.
 *
 * O pacote chega de qualquer origem; o nome de cada entrada do .zip e dado nao
 * confiavel. Cada caso abaixo e uma forma conhecida de escapar da pasta.
 */
class PacoteConfigZipSlipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun base(): File = tmp.newFolder("protocolos")

    @Test
    fun `entrada comum fica dentro da pasta`() {
        val b = base()
        val d = PacoteConfig.destinoDentroDe(b, "p123/folha.pdf")
        assertNotNull(d)
        assertEquals(File(b.canonicalFile, "p123/folha.pdf").canonicalPath, d!!.path)
    }

    @Test
    fun `subir com dois pontos e recusado`() {
        assertNull(PacoteConfig.destinoDentroDe(base(), "../shared_prefs/config_radioterapia.xml"))
    }

    @Test
    fun `dois pontos no meio do caminho e recusado`() {
        assertNull(PacoteConfig.destinoDentroDe(base(), "p1/../../fora.txt"))
    }

    @Test
    fun `barra invertida nao disfarca a subida`() {
        assertNull(PacoteConfig.destinoDentroDe(base(), "p1\\..\\..\\fora.txt"))
    }

    @Test
    fun `caminho absoluto e recusado`() {
        assertNull(PacoteConfig.destinoDentroDe(base(), "/data/data/x/arquivo"))
    }

    @Test
    fun `nome vazio e recusado`() {
        val b = base()
        assertNull(PacoteConfig.destinoDentroDe(b, ""))
        assertNull(PacoteConfig.destinoDentroDe(b, "   "))
    }

    @Test
    fun `a propria pasta base nao e destino de arquivo`() {
        assertNull(PacoteConfig.destinoDentroDe(base(), "."))
    }

    @Test
    fun `byte nulo e recusado`() {
        assertNull(PacoteConfig.destinoDentroDe(base(), "p1/a\u0000.pdf"))
    }
}
