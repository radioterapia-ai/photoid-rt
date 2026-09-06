# Traducao da interface — estado e como retomar

## Onde esta

| Idioma | strings.xml | plurals.xml | Oferecido no app |
|---|---|---|---|
| pt-BR (base) | 678 | 3 | sim |
| en, es | 678 | 3 | sim |
| **it, zh, ja, ar, bn** | **678** | **3** | **sim** |
| de, fr, ko, pl | — | pronto, em `pendente/` | **nao** |

Oito dos doze idiomas anunciados estao no app. Os quatro que faltam pararam no
meio: a conta bateu o limite mensal de gasto com 23 dos 63 blocos por traduzir.

## Por que os quatro nao estao em res/

`values-de/` com `plurals.xml` e sem `strings.xml` faz o lint acusar
`MissingTranslation` nas 678 chaves, e `abortOnError` esta ligado: o build para.
Idioma so entra em `res/` quando esta inteiro.

Pelo mesmo motivo, `LocaleManager.supportedLanguages` lista oito, e nao doze.
Oferecer um idioma sem traducao mostraria portugues no meio da tela em coreano,
sem aviso nenhum.

## Como retomar

1. `docs/i18n/fonte/fonte_b1.json` .. `fonte_b5.json` e `fonte_juridico.json`
   sao a fonte fatiada em blocos, no formato `{"k": chave, "pt": texto}`.
2. `docs/i18n/glossario/<lang>.md` fixa a terminologia clinica do idioma. E o
   que faz blocos traduzidos separadamente sairem coerentes — usar.
3. Traduzir cada bloco para `<lang>__<bloco>.json`, no formato
   `[{"k": ..., "v": ...}]`, **texto cru, sem escape de XML**.
4. Colocar os JSON em `pendente/traducoes/`, os plurais em `pendente/plurais/`,
   e rodar:

       python tools/i18n/i18n_montar.py --gravar
       python tools/i18n/i18n_plurais.py --gravar

   Os dois conferem antes de gravar: chaves faltando ou sobrando, placeholders
   alterados, `\n` perdido, escape duplo.
5. Acrescentar o codigo a `LocaleManager.supportedLanguages`.
6. `gradlew check` — o lint e `StringsParidadeTest` fecham o portao.

## A regra que nao se negocia neste trabalho

**O escape do Android e feito na montagem, nunca pelo tradutor.** Apostrofo vira
`\'`, `&` vira `&amp;`, e `\n` `\"` `\\` `\uXXXX` passam intactos. O italiano
sozinho gerou 144 apostrofos; deixar isso com o modelo seria depender de ele
acertar seiscentas vezes em nove idiomas, e apostrofo sem escape e o footgun n.6
do projeto.
