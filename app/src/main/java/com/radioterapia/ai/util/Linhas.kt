package com.radioterapia.ai.util

/**
 * Listas de configuração guardadas como texto, um item por linha (sítios,
 * equipamentos, médicos).
 *
 * Uma leitura só para todas: cada tela que repetia o `split` à mão podia
 * divergir das outras na primeira vez que só uma fosse corrigida.
 */
object Linhas {

    /**
     * Itens do texto, na ordem: quebra em `\n`, apara cada linha e descarta as
     * vazias. O `\r` de um texto colado do Windows cai no aparo, então
     * `"A\r\nB"` dá `[A, B]`, sem caractere invisível no fim do item.
     */
    fun deTexto(s: String): List<String> =
        s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
