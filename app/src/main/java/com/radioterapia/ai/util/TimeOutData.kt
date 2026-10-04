package com.radioterapia.ai.util

/**
 * Lista padrão de sítios de tratamento do Time-Out, em português (editável na
 * hora pelo usuário).
 *
 * É a reserva de toda instalação sem idioma de sítios fixado no fim do
 * onboarding: a que já estava em uso quando a fixação passou a existir, e a que
 * restaurou os padrões. Quem tem o idioma fixado recebe a mesma lista
 * traduzida, do recurso `sitios_padrao` (ver AppConfig.sitiosLista).
 *
 * GUARDA: `sitios_padrao` em `values/strings.xml` tem de ser idêntico a esta
 * lista unida por `\n` — mesmos itens, mesma ordem, mesmos acentos. Uma
 * instalação fixada em português lê o recurso; uma sem fixação lê esta
 * constante; divergindo, as duas mostram listas diferentes no mesmo idioma.
 * SitiosPadraoTest quebra se divergirem. Cada tradução mantém a mesma ordem e
 * a mesma quantidade de itens.
 */
object TimeOutData {
    val SITIOS = listOf(
        "BEXIGA", "BRAQUITERAPIA UTERINA", "BRAQUITERAPIA VAGINAL",
        "CABEÇA E PESCOÇO", "CANAL ANAL", "ESÔFAGO", "ESTÔMAGO", "GINECOMASTIA",
        "LINFOMA", "MAMA DIREITA", "MAMA ESQUERDA", "MAMA BILATERAL",
        "METÁSTASE ÓSSEA", "PÂNCREAS", "PELE",
        "PELVE FEMININA - COLO UTERINO", "PELVE GINECOLÓGICA - ENDOMÉTRIO",
        "PÊNIS E TESTÍCULOS", "PRÓSTATA", "PULMÃO", "QUELÓIDE",
        "RADIOCIRURGIA DE CRÂNIO", "RETO", "SARCOMA",
        "SBRT DE ADRENAL", "SBRT DE FÍGADO", "SBRT DE LINFONODO",
        "SBRT DE METÁSTASES ÓSSEAS", "SBRT DE PRÓSTATA", "SBRT DE PÂNCREAS",
        "SBRT DE PULMÃO", "SBRT DE RIM",
        "SNC - CÉREBRO (PRIMÁRIO)", "SNC - CÉREBRO TOTAL", "SNC - NEUROEIXO", "TBI"
    )
}
