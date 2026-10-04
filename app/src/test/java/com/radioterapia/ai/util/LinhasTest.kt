package com.radioterapia.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leitura das listas de configuração guardadas como texto, um item por linha.
 * O que entra aqui sai como opção nos seletores da finalização: item vazio
 * vira opção em branco, e espaço ou `\r` na ponta vira um sítio que parece
 * igual a outro e não é.
 */
class LinhasTest {

    @Test
    fun `apara cada linha e descarta as vazias`() {
        assertEquals(listOf("A", "B", "C"), Linhas.deTexto("A\n  B \n\nC\n"))
    }

    @Test
    fun `texto vazio ou so de espacos da lista vazia`() {
        assertTrue(Linhas.deTexto("").isEmpty())
        assertTrue(Linhas.deTexto("   ").isEmpty())
        assertTrue(Linhas.deTexto("\n\n \n").isEmpty())
    }

    @Test
    fun `quebra de linha do Windows nao deixa caractere invisivel no item`() {
        val itens = Linhas.deTexto("MAMA DIREITA\r\nMAMA ESQUERDA\r\n\r\nPELE\r\n")
        assertEquals(listOf("MAMA DIREITA", "MAMA ESQUERDA", "PELE"), itens)
        assertTrue(itens.none { it.contains('\r') })
    }

    @Test
    fun `espaco interno do item e preservado`() {
        assertEquals(listOf("PELVE FEMININA - COLO UTERINO"),
            Linhas.deTexto("  PELVE FEMININA - COLO UTERINO  "))
    }

    @Test
    fun `ordem e duplicatas sao mantidas como vieram`() {
        // Quem cura a lista e o servico; a leitura nao reordena nem deduplica.
        assertEquals(listOf("RETO", "BEXIGA", "RETO"), Linhas.deTexto("RETO\nBEXIGA\nRETO"))
    }

    @Test
    fun `a lista padrao de sitios sobrevive a ida e volta pelo texto`() {
        val texto = TimeOutData.SITIOS.joinToString("\n")
        assertEquals(TimeOutData.SITIOS, Linhas.deTexto(texto))
    }
}
