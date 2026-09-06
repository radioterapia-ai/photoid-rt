# Como isto foi construído

Este arquivo guarda as mensagens de commit do desenvolvimento anterior à
publicação. O histórico do Git foi recomeçado antes de o repositório sair —
não por haver segredo nele (a varredura não achou chave, credencial, DICOM nem
dado de paciente), mas porque os 38 commits carregavam o e-mail pessoal do autor
no metadado, e recomeçar descarta de uma vez todos os objetos superados, o que
deixa uma árvore só para auditar em vez de setecentos blobs.

**As mensagens ficam porque elas são o registro do raciocínio.** O que explica
por que o código mudou de ideia não é o diff — é a frase que alguém escreveu no
dia, e a data ao lado dela.

Da publicação em diante, o histórico do Git é a fonte.

---


## Agosto de 2026

- **04/08/2026** — chore: importa PhotoID RT E6 sob controle de versao
- **04/08/2026** — chore: para de versionar .claude/settings.local.json
- **04/08/2026** — chore: restaura o wrapper do Gradle em 8.13
- **04/08/2026** — fix: crash ao fotografar em Android 7-10 (getDisplay sem guarda de API)
- **04/08/2026** — fix: zera os 9 erros de lint (gradlew check passa)
- **04/08/2026** — docs: corrige a toolchain e documenta o portao de qualidade
- **04/08/2026** — chore: remove 29 imports nao usados
- **04/08/2026** — chore: remove 18 funcoes privadas sem chamador (~400 linhas)
- **04/08/2026** — chore: remove recursos orfaos (12 layouts, 9 drawables, 174 strings x3)
- **04/08/2026** — docs: move o historico E5 para docs/ e atualiza as contagens
- **04/08/2026** — perf: abertura do app deixa de ter 1,5 s de espera e I/O na thread principal
- **04/08/2026** — feat: folha de posicionamento sem a etiqueta, fotos ate 25% maiores e com  cantos arredondados
- **04/08/2026** — feat: remove o DICOM e tira a atualizacao da base do caminho de abertura
- **05/08/2026** — fix: corrige os 4 bugs de campo reportados pelos tecnicos
- **05/08/2026** — chore: versao 1.2 (build 10) e instrucao de instalacao no tablet
- **05/08/2026** — docs: versiona o ponto de retomada da sessao de manutencao
- **08/08/2026** — fix: Time-Out perdido na finalizacao, homonimos, i18n e 3 funcoes novas
- **09/08/2026** — revert: remove o comando por voz e a permissao de microfone
- **11/08/2026** — build: empacotador com destino declarado, deteccao de JDK e guarda de CSV
- **12/08/2026** — chore: versiona o .gitattributes que protege o CRLF dos .cmd
- **12/08/2026** — docs: autoria de terceiro, licenca Apache-2.0 e confinamento de rede
- **12/08/2026** — feat: termos e privacidade alinhados a Apache-2.0, com consentimento versionado
- **16/08/2026** — chore: remove codigo morto e o bind de CSV que apagava a configuracao
- **18/08/2026** — build: empacotador aponta para C:\AI_DEPLOY e exige que a raiz exista
- **25/08/2026** — feat: importar da galeria, separar simulacoes e logo novo no splash
- **25/08/2026** — feat: rubricario, excluir fotos, e o registro vazio que sumia com a miniatura
- **25/08/2026** — feat: ajustes da segunda rodada da clinica, na ficha e nas fotos
- **25/08/2026** — feat: arquivar em vez de apagar, e cabecalho unico no rubricario
- **26/08/2026** — fix: grid retrato como era, e a data da simulacao uma vez so
- **27/08/2026** — feat: cinco idiomas novos, abertura em dois passos e transferencia por item
- **30/08/2026** — feat: ultima fracao no Time-Out, aviso de campo vazio e recorte que reduz
- **30/08/2026** — feat: fundacao do Protocolo — paginas finais que o servico acrescenta a ficha
- **30/08/2026** — feat: Protocolo — paginas finais do servico, calibraveis e transferiveis
- **30/08/2026** — feat: os 12 idiomas completos — frances, alemao, coreano e polones fechados

## Setembro de 2026

- **02/09/2026** — fix: contagem da miniatura de posicionamento somava a ficha inteira
- **02/09/2026** — feat: rubricario roteado — blocos por equipe, escolhidos pelo protocolo
- **02/09/2026** — chore: v3.1 (build 21)
- **06/09/2026** — feat: assinatura de release propria, caminho efetivo na tela e idioma do aparelho

---

## O que não está aqui

As decisões de produto que foram **recusadas**, e os motivos, estão em
`docs/PENDENCIAS.md`. Os erros que custaram build estão em `docs/ARMADILHAS.md`.
A história de cada funcionalidade — a ideia, os erros, o estado atual — está em
`docs/JORNADA.md`.

