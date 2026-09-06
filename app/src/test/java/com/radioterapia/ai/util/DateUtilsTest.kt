package com.radioterapia.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Cobre a validação de nascimento e a formatação de datas.
 * Motivação real: até a v55 a validação era só `texto.length != 10`, então
 * 31/02/2020 e 99/99/9999 entravam no cadastro e iam para a etiqueta do PDF.
 */
class DateUtilsTest {

    // ---------- nascimentoValido ----------

    @Test
    fun aceitaDataComum() {
        assertTrue(DateUtils.nascimentoValido("15/07/1982"))
        assertTrue(DateUtils.nascimentoValido("01/01/1950"))
    }

    @Test
    fun rejeitaDiaInexistenteNoMes() {
        assertFalse("31/02 não existe", DateUtils.nascimentoValido("31/02/2020"))
        assertFalse("31/04 não existe", DateUtils.nascimentoValido("31/04/1990"))
        assertFalse("31/06 não existe", DateUtils.nascimentoValido("31/06/1990"))
    }

    @Test
    fun trataAnoBissexto() {
        assertTrue("2020 é bissexto", DateUtils.nascimentoValido("29/02/2020"))
        assertFalse("2021 não é bissexto", DateUtils.nascimentoValido("29/02/2021"))
        assertTrue("2000 é bissexto (regra dos 400)", DateUtils.nascimentoValido("29/02/2000"))
        assertFalse("1900 NÃO é bissexto (regra dos 100)", DateUtils.nascimentoValido("29/02/1900"))
    }

    @Test
    fun rejeitaValoresImpossiveis() {
        assertFalse(DateUtils.nascimentoValido("99/99/9999"))
        assertFalse(DateUtils.nascimentoValido("00/00/0000"))
        assertFalse(DateUtils.nascimentoValido("15/13/1982"))
        assertFalse(DateUtils.nascimentoValido("00/07/1982"))
    }

    @Test
    fun rejeitaDataFutura() {
        val proximoAno = Calendar.getInstance().get(Calendar.YEAR) + 1
        assertFalse(DateUtils.nascimentoValido("01/01/$proximoAno"))
    }

    @Test
    fun rejeitaIdadeImplausivel() {
        assertFalse("mais de 130 anos", DateUtils.nascimentoValido("01/01/1800"))
    }

    @Test
    fun rejeitaFormatoInvalido() {
        assertFalse(DateUtils.nascimentoValido(null))
        assertFalse(DateUtils.nascimentoValido(""))
        assertFalse(DateUtils.nascimentoValido("15-07-1982"))
        assertFalse(DateUtils.nascimentoValido("1982-07-15"))
        assertFalse(DateUtils.nascimentoValido("5/7/1982"))
    }

    // ---------- formatarNascimento ----------

    @Test
    fun formataNosTresIdiomas() {
        assertEquals("15-JUL-1982", DateUtils.formatarNascimento("15/07/1982", "pt"))
        assertEquals("15-JUL-1982", DateUtils.formatarNascimento("15/07/1982", "en"))
        assertEquals("15-JUL-1982", DateUtils.formatarNascimento("15/07/1982", "es"))
    }

    @Test
    fun mesesDivergentesEntreIdiomas() {
        // Motivação: até a v55 o espanhol reaproveitava os meses do português.
        assertEquals("10-MAI-1975", DateUtils.formatarNascimento("10/05/1975", "pt"))
        assertEquals("10-MAY-1975", DateUtils.formatarNascimento("10/05/1975", "en"))
        assertEquals("10-MAY-1975", DateUtils.formatarNascimento("10/05/1975", "es"))

        assertEquals("03-SET-1960", DateUtils.formatarNascimento("03/09/1960", "pt"))
        assertEquals("03-SEP-1960", DateUtils.formatarNascimento("03/09/1960", "en"))
        assertEquals("03-SEP-1960", DateUtils.formatarNascimento("03/09/1960", "es"))

        assertEquals("20-DEZ-1999", DateUtils.formatarNascimento("20/12/1999", "pt"))
        assertEquals("20-DIC-1999", DateUtils.formatarNascimento("20/12/1999", "es"))

        assertEquals("07-JAN-1988", DateUtils.formatarNascimento("07/01/1988", "pt"))
        assertEquals("07-ENE-1988", DateUtils.formatarNascimento("07/01/1988", "es"))
    }

    @Test
    fun aceitaFormatosAlternativosDeEntrada() {
        assertEquals("15-JUL-1982", DateUtils.formatarNascimento("15071982", "pt"))
        assertEquals("15-JUL-1982", DateUtils.formatarNascimento("1982-07-15", "pt"))
        assertEquals("15-JUL-1982", DateUtils.formatarNascimento("15.07.1982", "pt"))
    }

    @Test
    fun devolveOriginalQuandoNaoInterpreta() {
        assertEquals("SEM DATA", DateUtils.formatarNascimento("SEM DATA", "pt"))
        assertEquals("", DateUtils.formatarNascimento(null, "pt"))
    }
}
