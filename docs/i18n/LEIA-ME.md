# Traducao da interface — estado e como retomar

## Onde esta

**Os doze estao no app, completos.** pt-BR (base), en, es, fr, de, it, pl, zh,
ja, ko, ar e bn — todos com o mesmo numero de chaves e os tres `plurals`, e
todos listados em `LocaleManager.supportedLanguages`.

`pendente/` guarda o material de trabalho dos ultimos quatro (de, fr, ko, pl),
que ficaram para tras por um tempo e entraram depois. Ele fica por servir de
EXEMPLO do formato: e o par entrada/saida real de uma traducao que passou pelo
montador e pelo portao.

## A regra que mantem isso de pe

Idioma so entra em `res/` quando esta INTEIRO. `values-de/` com `plurals.xml` e
sem `strings.xml` faz o lint acusar `MissingTranslation` em todas as chaves, e
`abortOnError` esta ligado: o build para.

E `LocaleManager.supportedLanguages` so lista o que existe em `res/`. Oferecer
um idioma sem traducao mostraria portugues no meio de uma tela em coreano, sem
aviso nenhum.

Nenhuma das duas e um estorvo: sao a rede que impede um idioma pela metade de
chegar a um serviço.

## Como retomar

Vale para acrescentar um DECIMO TERCEIRO idioma, que e o caminho que este
diretorio existe para deixar aberto.

1. `docs/i18n/fonte/fonte_b*.json` e `fonte_juridico.json` sao a fonte fatiada
   em blocos, no formato `{"k": chave, "pt": texto}`. Regere-os do `values/`
   atual antes de comecar: a base cresce a cada versao.
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
