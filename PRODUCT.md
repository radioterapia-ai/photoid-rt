# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

**Primário: o técnico de radioterapia**, dentro da sala de simulação, com o
paciente já posicionado na mesa. O trabalho dele é registrar o posicionamento
sem atrasar a simulação — identificar o paciente, fotografar por categoria,
preencher os dados e imprimir a ficha.

**Secundários: físicos e médicos.** Consultam o registro; o médico responsável é
um campo da simulação, não do cadastro.

**No tratamento**, dia a dia, quem confere o setup do paciente consulta as mesmas
fotos da simulação.

O app é oferecido em doze idiomas completos (pt, en, es, fr, de, it, pl, zh, ja,
ko, ar, bn) e abre no idioma do aparelho, com inglês de reserva. O árabe renderiza
da direita para a esquerda.

## Product Purpose

Em muitos serviços o registro de posicionamento é feito no celular de quem
estiver na sala, e a ficha é preenchida à mão depois. As fotos se perdem, ficam
no aparelho pessoal de alguém, ou chegam no primeiro dia de tratamento sem que
ninguém saiba de que paciente são.

O PhotoID RT roda num tablet dentro da sala. Identifica o paciente, organiza as
fotografias por categoria — rosto, etiqueta, posicionamento, acessórios,
impressos —, monta a ficha de posicionamento com a página de Time-Out, e imprime.
No módulo de Tratamento as mesmas fotografias são consultadas dia a dia.

O registro passa a existir, a pertencer ao serviço, e a ser encontrável no dia do
tratamento.

## Positioning

**O reconhecimento roda no aparelho, e o tamanho do APK é a prova física disso.**
Cerca de 57 dos 75 MB são o motor de reconhecimento do ML Kit, em quatro ABIs. O
app lê a etiqueta do paciente sem enviar a imagem a lugar nenhum.

**Não há destino nosso em parte alguma.** O app não tem servidor, e os autores não
recebem cópia. Toda saída de arquivo vai a um endereço que a própria instituição
forneceu — impressão em rede, pen-drive, app de sincronização que ela instalou, ou
o destino que ela configurou e ligou.

**A restrição de rede é verificada pelo build.** O ritual de validação falha quando
uma primitiva de rede aparece em arquivo fora de uma lista de oito. Acrescentar
rede exige edição explícita, que aparece no diff e vai a revisão.

**Não somos autores dos modelos.** Quem reconhece texto e lê código de barras é o
Google ML Kit, componente de terceiro. O que este projeto constrói é a camada de
acessibilidade: o fluxo de captura por categoria, a organização em disco, a ficha
de posicionamento com Time-Out, e as heurísticas que interpretam o texto já
reconhecido. Tornar alcançável não é ato neutro — é o que transforma biblioteca
disponível em usada na clínica, e por isso os avisos e a conferência humana em
cada campo sugerido são responsabilidade nossa.

## Operating Context

**A cena de uso cobre as quatro condições, e o design responde pela pior.**
Confirmado pelo Henrique em 20/09/2026:

- tablet **na mão, em pé**, ao lado da mesa, às vezes com uma mão só;
- **luz baixa ou sala escurecida** — luz de posicionamento e lasers;
- **luva ou mão ocupada**, toque sem precisão de ponta de dedo;
- e também **bancada, luz normal, sem luva**.

As quatro valem. O piso é o pior caso — luva, luz baixa, uma mão — sem que isso
penalize o uso de bancada.

**Fluxo central:** identificar o paciente (digitado, ou por leitura da etiqueta com
OCR e código de barras) → fotografar por categoria → preencher os dados da
simulação (médico, equipamento, sítio, riscos, observações, número de frações) →
gerar a ficha de posicionamento em PDF com a página de Time-Out → imprimir, pela
rede ou pelo serviço de impressão do Android.

**Arquivos** ficam no armazenamento compartilhado, em `PhotoID_RT/PHOTOS/`, para
que a galeria do tablet os enxergue. Sem a permissão de acesso a todos os
arquivos, o app cai na pasta privada — e avisa na tela, porque o Android apaga
essa pasta na desinstalação.

**Entrega dos arquivos ao servidor do serviço** tem duas respostas: apontar um app
de sincronização qualquer para a pasta, ou deixar o app entregar por SMB, WebDAV,
FTP, SFTP ou pasta de nuvem via seletor de documentos do Android.

**Aparelho de referência:** Galaxy Tab S6. Requisito mínimo Android 7.0 (API 24).

## Capabilities and Constraints

Restrições de produto confirmadas, que trabalho futuro preserva:

- **Cliques custam.** Os usuários rejeitam passo extra. Já houve campo opcional
  ficando em branco por isso — a resposta acordada foi instrução e treinamento,
  **não** obrigatoriedade. Não transformar campo opcional em obrigatório, não
  acrescentar confirmação onde ela pode ser inferida.
- **O app é universal.** Serve a qualquer serviço de radioterapia, não à clínica de
  origem. Nada de lista fixa, fluxo ou nomenclatura de um serviço só.
- **Sincronia existe desde a v4.0 e nasce desligada.** Desligada, não há serviço em
  segundo plano, tentativa de rede nem dado saindo por conta própria. É o ponto
  inegociável da função, porque é o que a Política de Privacidade afirma na seção 12.
- **A sincronização é de uma via e nunca apaga.** Não é limitação a remover: é o que
  separa cópia de segurança de espelhamento, e espelhamento com dado de paciente faz
  exclusão acidental no tablet apagar a cópia boa da instituição.
- **Credenciais não saem do aparelho.** Vivem em `EncryptedSharedPreferences` sob
  chave mestra do Android Keystore, e são deliberadamente excluídas do pacote de
  transferência de configuração, que viaja por e-mail e pen-drive.
- **Sem Bluetooth próprio.** Impressão por Bluetooth é coberta pelo serviço de
  impressão do Android. Protocolo próprio foi avaliado e recusado.
- **Sem microfone.** Comando por voz foi implementado e retirado: a promessa de que
  o áudio não sai do aparelho só vale no Android 13+, e o parque de tablets das
  clínicas não tem controle de versão. Não reintroduzir sem que a promessa valha em
  todas as versões alvo.
- **Idioma inteiro ou nenhum.** Toda string visível vai para os doze idiomas. O lint
  trata `MissingTranslation` e `ExtraTranslation` como erro, então falta ou sobra
  quebra o build. Plurais seguem as categorias do CLDR por idioma, que não
  acompanham o português.
- **Nenhum dado de paciente no repositório** — nem de amostra. A pasta sincroniza com
  o Google Drive, e `.gitignore` não impede sincronização.
- **Ferramenta de apoio, não dispositivo médico.** Não diagnostica, não mede, não
  interpreta imagem médica e não substitui julgamento profissional. Campo preenchido
  por leitura de etiqueta ou código de barras é **sugestão** e exige confirmação
  humana.

**Toolchain:** Gradle 8.13, AGP 8.13.2, Kotlin 1.9.20, JDK 21, compileSdk 34,
minSdk 24, targetSdk 34. `applicationId` `com.radioterapia.ai`, versão 4.0
(versionCode 25). Com minSdk 24, todo erro `NewApi` do lint é crash em potencial:
guardar por `Build.VERSION.SDK_INT`, nunca por `try/catch`.

**Superfície atual:** 24 Activities, 59 layouts, sistema de recursos em `values/`
(`colors`, `dimens`, `styles`, `themes`, `attrs`, `ids`), 45 drawables. Tema
`Theme.Radioterapia`, sobre `Theme.MaterialComponents.NoActionBar`. Quase toda
Activity estende `BaseActivity`, que fornece barra de título, slot de ação e
seletor de impressão.

## Brand Commitments

- **Nome:** PhotoID RT. Parte do ecossistema **Radioterapia.AI** (radioterapia.ai).
  Marca tratada em `TRADEMARK.md`.
- **Logotipo** existente e vinculante como ativo: "PhotoID" em branco, "RT" no
  gradiente.
- **Voz:** toda saída de IA é descrita como rascunho sujeito a revisão médica. Nunca
  escrever "nosso algoritmo", "nossa IA" ou "desenvolvemos o modelo" — em documento,
  em commit ou na interface. O correto é "o ML Kit reconhece", "integramos".
- **Aviso permanente:** ferramenta de apoio, não validada para uso clínico. Presente
  no `NOTICE`, no README e na tela Sobre.
- **Documentação em português do Brasil**; o README público é em inglês.

## Evidence on Hand

**Real, e disponível:**

- O código em si, v4.0, público no GitHub com Releases e `SHA256.txt` por versão.
- `docs/JORNADA.md` — a história real de cada funcionalidade, o que deu errado e por
  quê está como está. É a melhor evidência de campo que o projeto tem.
- `NOTICE`, `THIRD_PARTY.md`, `LICENSE`, `SECURITY.md`, `CITATION.cff`,
  `TRADEMARK.md` — autoria, licença e versão de cada componente de terceiro.
- `docs/img/banner.png` e `docs/img/fluxo.png`.
- Construído e exercitado em Galaxy Tab S6.

**Ausências que trabalho futuro não pode preencher com invenção:**

- **Nenhum serviço roda o app em rotina com paciente real** (confirmado em
  20/09/2026). Não existe número de adoção, depoimento, estudo de caso, comparação
  com prática anterior, nem métrica de tempo economizado. Não fabricar nenhum deles.
- Não há dado de paciente no repositório, por regra — nem para teste. Importação da
  base de pacientes se testa com dado inventado.

## Product Principles

1. **O clique é o recurso escasso.** Cada passo a mais é cobrado do técnico com o
   paciente na mesa. Inferir em vez de perguntar; treinar em vez de obrigar.
2. **Nada sai do aparelho sem o serviço ter decidido.** O padrão é local. Toda saída
   é a um endereço que a instituição forneceu, e o interruptor nasce desligado.
3. **Universal antes de conveniente.** Nenhuma lista fixa, fluxo ou nomenclatura de
   um serviço específico, mesmo quando o serviço de origem ganharia com isso.
4. **Somos a camada de acessibilidade, não o autor do modelo.** O crédito do
   reconhecimento é do ML Kit; a responsabilidade pelo alcance clínico e pela
   conferência humana é nossa.
5. **O idioma inteiro ou nenhum.** Doze idiomas completos, verificados pelo build.
   Idioma parcial não entra em `res/`.

## Accessibility & Inclusion

**Leitor de tela não é escopo hoje** — decisão explícita do Henrique em 20/09/2026,
registrada aqui como decisão e não como omissão. Medição atual: 15 dos 59 layouts
têm `contentDescription`. Trabalho futuro não deve tratar isso como defeito nem
abrir tarefa para corrigi-lo sem pedido.

**Inclusão que já é compromisso e permanece:** doze idiomas completos, com árabe da
direita para a esquerda, verificados pelo lint como erro de build.

**Necessidade de legibilidade que vem da cena, não de norma:** luva, luz baixa e uso
com uma mão são condições confirmadas de uso. Tamanho de alvo de toque, contraste e
corpo de texto respondem a elas como restrição de produto, o que é independente da
decisão sobre leitor de tela acima.
