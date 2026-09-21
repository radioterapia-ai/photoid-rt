---
name: PhotoID RT
description: Documentação fotográfica de posicionamento em radioterapia, num tablet dentro da sala
colors:
  brand-primary: "#42A5F5"
  brand-primary-dark: "#1565C0"
  brand-accent: "#69A7DB"
  grad-start: "#42A5F5"
  grad-mid: "#7B6FE0"
  grad-end: "#B254E8"
  bg-main: "#00030E"
  bg-card: "#0B132C"
  bg-elevated: "#0E1839"
  bg-input: "#1A2545"
  divider: "#1E2D52"
  text-primary: "#FFFFFF"
  text-secondary: "#B8C5D6"
  text-tertiary: "#8896AC"
  action-save: "#2E7D32"
  action-discard: "#C62828"
  action-neutral: "#455A64"
  confirm-green: "#00E676"
  confirm-pendente: "#90A4AE"
  alerta-contato: "#F57C00"
  alerta-alergia: "#C62828"
  alerta-queda: "#FFC107"
typography:
  display:
    fontFamily: "sans-serif-bold (Roboto Bold, face do sistema)"
    fontSize: "56sp"
    fontWeight: 700
  headline:
    fontFamily: "sans-serif-bold"
    fontSize: "44sp"
    fontWeight: 700
  title:
    fontFamily: "sans-serif-bold"
    fontSize: "17sp"
    fontWeight: 700
  body:
    fontFamily: "sans-serif"
    fontSize: "17sp"
    fontWeight: 400
  label:
    fontFamily: "sans-serif-bold"
    fontSize: "15sp"
    fontWeight: 700
  overline:
    fontFamily: "sans-serif"
    fontSize: "11sp"
    fontWeight: 400
    letterSpacing: "0.13em"
rounded:
  hair: "1dp"
  sm: "8dp"
  md: "14dp"
  lg: "16dp"
  pill: "20dp"
spacing:
  xs: "4dp"
  sm: "6dp"
  md: "8dp"
  lg: "14dp"
  xl: "20dp"
  gutter: "36dp"
components:
  button-primary:
    backgroundColor: "{colors.brand-primary-dark}"
    textColor: "{colors.text-primary}"
    typography: "{typography.label}"
    height: "68dp"
  button-secondary:
    textColor: "{colors.text-primary}"
    typography: "{typography.label}"
    rounded: "{rounded.md}"
    height: "68dp"
  button-outline-cyan:
    textColor: "{colors.text-primary}"
    rounded: "{rounded.lg}"
  input-field:
    backgroundColor: "{colors.bg-input}"
    textColor: "{colors.text-primary}"
    typography: "{typography.body}"
    padding: "14dp"
  settings-group:
    backgroundColor: "{colors.bg-card}"
    rounded: "{rounded.sm}"
  popup-item:
    textColor: "{colors.text-primary}"
    typography: "{typography.body}"
    height: "52dp"
    padding: "0 20dp"
  alert-pill:
    textColor: "{colors.text-primary}"
    rounded: "{rounded.lg}"
  dica-botao:
    textColor: "{colors.brand-primary}"
    size: "44dp"
---

# Design System: PhotoID RT

## Overview

**Creative North Star: "A Prancheta do Técnico"**

O registro de posicionamento que antes era celular pessoal e papel preenchido
depois, agora feito direito. A prancheta é o modelo mental: um objeto que se
segura em pé, ao lado da mesa, que não se perde, e cuja saída é um documento
impresso que pertence ao serviço. A tela não é destino — é o caminho até a ficha.

O humor é **minimalismo e "idiot-proof"**, nas palavras do autor, e as duas
metades puxam para o mesmo lado. Minimalismo aqui não é pouco conteúdo: é pouca
decisão por tela, e nenhuma decoração disputando atenção com a fotografia do
paciente. "Idiot-proof" é o corretivo que impede o minimalismo de virar
austeridade perigosa — alvo grande demais para errar, estado visível sem
interpretação, e nenhum caminho que só funcione se a pessoa lembrar de algo. Sob
luva, com luz baixa e o paciente esperando na mesa, uma interface elegante que
exige precisão é uma interface quebrada.

O mundo é escuro por necessidade, não por moda. O fundo `#00030E` é quase preto
azulado, e ele existe para que a fotografia carregue toda a luz da tela — a mesma
razão pela qual o negatoscópio tinha o entorno escuro. Tudo o que não é conteúdo
recua para camadas tonais poucos passos acima do fundo.

**Anti-referências confirmadas.** Este app não pode virar **aplicativo de
consumo** — nada de ilustração simpática, mascote, cantos muito arredondados,
microinteração festiva ou emoji. E não pode virar **prontuário corporativo
cinza** — nada de cinza institucional, tabela densa sem hierarquia ou formulário
infinito. As duas são reais: a primeira destrói a credibilidade clínica, a
segunda destrói o motivo de o app existir.

**Key Characteristics:**

- Fundo quase preto azulado; a fotografia é a fonte de luz da tela.
- Profundidade por camada tonal e traço de 1dp, nunca por sombra.
- Alvo primário de 68dp — acima do mínimo do Material, por causa da luva.
- Face do sistema, sem fonte própria; hierarquia por tamanho e peso.
- Gradiente azul→roxo reservado ao logotipo e a um fio de régua.
- Laranja, vermelho e amarelo são território exclusivo do alerta clínico.

## Colors

Paleta extraída do logotipo do ecossistema, sobre um fundo quase preto. A
saturação alta é rara e sempre significa alguma coisa: marca, ação destrutiva ou
risco clínico. O resto do sistema vive entre o azul-escuro e o cinza-azulado.

### Primary

- **Azul de Marca** (`#42A5F5`): o azul do logotipo. Aparece em ícone de dica, em
  contorno de bloco selecionado e como início do gradiente. É cor de identidade,
  não de superfície.
- **Azul de Ação** (`#1565C0`): o preenchimento do botão primário. Mais escuro
  que o azul de marca de propósito — texto branco por cima precisa de contraste,
  e o azul claro não o dá.
- **Azul Sereno** (`#69A7DB`): contorno do botão secundário. Presente o bastante
  para se ver sob luz baixa, apagado o bastante para não competir com o primário.

### Secondary

- **Gradiente do Logotipo** (`#42A5F5` → `#7B6FE0` → `#B254E8`): azul → roxo, a
  assinatura da família Radioterapia.AI. Aparece no `PhotoIdHeaderView` e num
  único fio de régua no visualizador do paciente.

### Tertiary

- **Verde de Confirmação** (`#00E676`): o visto confirmado nos diálogos de
  conferência do OCR. Não é o verde de botão — este é verde de **primeiro plano**,
  desenhado para viver sobre o fundo quase preto, onde verde escuro sumiria.
- **Cinza Pendente** (`#90A4AE`, aplicado a 50% de opacidade): o mesmo visto,
  ainda não confirmado. Apagado de propósito, para que o confirmado tenha para
  onde subir.

### Neutral

- **Fundo** (`#00030E`): preto azulado profundo. A tela inteira, a barra de
  status, a janela.
- **Cartão** (`#0B132C`): o primeiro degrau acima do fundo. Grupos, blocos,
  agrupamentos de configuração.
- **Elevado** (`#0E1839`): o segundo degrau, para o que flutua sobre o cartão.
- **Campo** (`#1A2545`): o fundo do campo de entrada. É o degrau mais claro, e
  isso é o que diz "aqui se digita" sem precisar de borda.
- **Divisor** (`#1E2D52`): o traço de 1dp que separa. Faz o trabalho que a sombra
  faria em outro sistema.
- **Texto Primário** (`#FFFFFF`), **Secundário** (`#B8C5D6`), **Terciário**
  (`#8896AC`): três degraus, e só três.

### Ações de consequência

- **Salvar** (`#2E7D32`) · **Descartar** (`#C62828`) · **Neutro** (`#455A64`).

### Named Rules

**A Regra do Alerta Clínico — o escopo é a FORMA, não o hexadecimal.**

Laranja (`#F57C00`), amarelo (`#FFC107`) e vermelho (`#C62828`) pertencem ao
risco do paciente — precaução de contato, risco de queda e alergia — **quando
aparecem como tarja ou pílula**: cápsula de raio 16dp, com gradiente, texto
centralizado. É essa forma que espelha a ficha de Time-Out impressa, e a
correspondência com o papel **é** a função: quem leu a ficha reconhece o mesmo
código na tela.

Fora dessa forma, vermelho como **texto** ou como preenchimento de **botão
destrutivo** é ação de consequência, e é legítimo — `action-discard` e
`alerta-alergia` compartilham o mesmo `#C62828` de propósito, e ninguém confunde
um botão "Excluir" com uma tarja de alergia. Inventar um segundo vermelho só
para desempatar o token criaria um quarto vermelho num sistema que já tem
`#C62828`, `#EF5350` e `#E53935` soltos — que é o problema que esta regra existe
para resolver.

**O que continua proibido:** laranja e amarelo como **estado de sistema** —
contador, flash ligado, som ligado, câmera frontal ativa. Foi o uso que gastava o
vocabulário: três ícones amarelos acesos na tela de captura, nenhum deles
clínico, para um técnico que aprendeu no papel que amarelo é risco de queda. É
por isso que `colors.xml` diz "sem laranja" e existe um `pill_alert_orange` ao
mesmo tempo.

**Decisão em aberto, registrada para não ser tomada por engano:** o laranja
`#FF9800` do contador de fotos por aba (`category_tab.xml:45`) é o caso-limite —
a `JORNADA.md` registra o *badge* como melhoria deliberada, e esta regra cobre a
*cor*. São decisões diferentes. O Henrique adiou em 20/09/2026 para decidir com a
tela na frente. **Não mexer até lá.**

**A Regra do Brilho, não do Matiz.** Estado confirmado e não confirmado se
distinguem por **luminosidade**, não só por cor: `#00E676` contra o fundo dá cerca
de 12,6:1, e contra o cinza pendente a 50% a diferença deixa de ser de matiz e
passa a ser de brilho. Quem não distingue vermelho e verde continua lendo o
estado. Qualquer par de estados novo segue a mesma regra.

**A Regra do Gradiente Raro — vale para o gradiente de MARCA.**

O gradiente **azul→roxo** (`#42A5F5` → `#7B6FE0` → `#B254E8`) aparece no
logotipo e em um fio de régua. Em nenhum outro lugar: não é fundo de tela, não é
preenchimento de botão, não é realce de card. A raridade é o que o faz significar
marca.

**Fora do escopo desta regra:** o `btn_module_3d` (`#3765AB` → `#1565C0`), que
preenche os botões de módulo da Home, da Simulação e do Tratamento. É um
gradiente de **um azul para outro azul**, não carrega a marca, e é o elemento
mais distintivo do app fora do logotipo. A doutrina plana trata de
**profundidade** — sombra, `elevation`, `CardView` —, não de preenchimento.
Confirmado pelo Henrique em 20/09/2026: **não mexer**. O nome do arquivo (`_3d`)
briga com a doutrina plana e é dívida de nomenclatura, não de design.

**Divergência resolvida em 20/09/2026.** `identidade-visual.md` da skill de
marketing define o acento do perfil @radioterapia.ai como `#0A2A6B → #24D3EE`
(azul→ciano). Medição pixel a pixel de `assets/marca/marca_ai.png` mostra o
gradiente real correndo **na diagonal**, de `#70A6D3` (matiz 207°) a `#977CBB`
(matiz 266°) — azul→violeta, direção oposta à do ciano. A decisão do Henrique: os
valores do app são a definição, e o PNG é apenas um render com halo, que lava a
ponta. **Fica pendente corrigir o ciano em `identidade-visual.md`**; ele é que
envelheceu.

## Typography

**Face única:** a do sistema (`sans-serif` / Roboto). Não há fonte própria
empacotada, e isso é escolha defensável num app que fala doze idiomas — a face do
sistema já traz as formas de árabe, bengali, chinês, japonês e coreano que uma
fonte latina não teria.

**Character:** neutra e funcional. Toda a personalidade tipográfica do produto
está concentrada num único lugar — o `PhotoIdHeaderView` —, e o resto do app
deliberadamente não tem voz tipográfica própria. Hierarquia se faz por **tamanho e
peso**, nunca por família.

### Hierarchy

Os valores abaixo são os de **tablet** (`values-sw600dp`), que é o aparelho alvo.
O celular usa a coluna compacta, em `values/dimens.xml`.

- **Display** (bold, 56sp tablet / 38sp celular): o "PhotoID RT" da tela de
  entrada. Só o `PhotoIdHeaderView`.
- **Headline** (bold, 44sp / 28sp): o mesmo cabeçalho nos módulos — "RT Sim",
  "RT Check". O tamanho é o que separa a casa do cômodo.
- **Título de Módulo** (bold, 26sp / 18sp): o rótulo dentro do botão de módulo.
- **Título de Seção** (bold, 17sp): cabeçalho de grupo dentro de uma tela.
- **Corpo** (regular, 17sp): campo de entrada, item de popup, conteúdo.
- **Rótulo** (bold, 15sp, cor secundária): o rótulo acima do campo.
- **Descrição** (regular, 14sp, cor secundária): a linha explicativa sob o
  cabeçalho de seção.
- **Sobrelinha** (regular, 11sp, caixa alta, tracking 0,13em, cor terciária): o
  cabeçalho de seção dentro do popup de ações. É o único uso de caixa alta com
  tracking no app.

### Named Rules

**A Regra do sp.** Todo tamanho de texto em `sp`, nunca `dp` nem `px`, para que o
texto siga a configuração de tamanho de fonte do sistema. Num app usado com luz
baixa por gente que pode precisar de corpo maior, isso não é detalhe.

**A Regra dos Dois Aparelhos.** Tamanho de texto e altura de elemento não se
escrevem no layout: vão para `dimens.xml`, com o par celular/tablet. Um valor
fixo no layout quebra um dos dois, e o que quebra é sempre o que ninguém testou.

## Layout

**Dois tamanhos, um layout.** O app não duplica telas: `values/dimens.xml` traz os
valores compactos (celular, paisagem com pouca altura) e `values-sw600dp/dimens.xml`
os sobrescreve com os valores espaçosos do tablet. A margem externa das telas de
seleção vai de 16dp a 36dp; a altura do botão de módulo, de 62dp a 110dp.

**Ritmo vertical** em passos de 4dp: 4, 6, 8, 12, 14, 20. O `14dp` é o padding
interno do campo, e o `20dp` o padding lateral do popup.

**Densidade:** média-baixa. Poucos elementos por tela, cada um grande. É o que
"minimalismo e idiot-proof" pede, e é o oposto do prontuário corporativo que
consta como anti-referência.

## Elevation & Depth

**O sistema é plano, e isso é doutrina.** A medição do `res/` inteiro encontrou
**duas** declarações de `elevation` e **zero** `CardView`. A profundidade não vem
de sombra: vem de uma escada tonal de quatro degraus sobre o fundo quase preto —
`#00030E` → `#0B132C` → `#0E1839` → `#1A2545` — reforçada por um traço de 1dp na
cor do divisor.

Num fundo tão escuro, sombra praticamente não se vê; o que separa superfícies é o
degrau de tom. Tentar acrescentar sombra aqui gasta recurso de desenho para
produzir quase nada.

### Named Rules

**A Regra do Plano.** Superfície se distingue por **tom e traço de 1dp**, nunca
por sombra. Não acrescentar `elevation`, `CardView` nem sombra desenhada. Precisa
separar duas superfícies? Suba um degrau na escada tonal, ou ponha o traço.

## Shapes

**Retângulo de canto suave, em quatro passos.** `1dp` para o traço divisor, `8dp`
para agrupamento de conteúdo (grupo de configurações), `14dp` para controle que se
toca (botão), `16dp` para o que se destaca (pílula de alerta, contorno ciano), e
`20–22dp` para a pílula flutuante, que é quase cápsula.

Não há círculo além do botão de captura, e não há forma irregular, recorte
diagonal ou silhueta decorativa em parte alguma. A forma nunca é o assunto.

**Uma inconsistência medida, que este documento registra em vez de esconder:** o
botão primário não declara `cornerRadius` e herda o padrão do Material 2, enquanto
o secundário declara 14dp e o contorno ciano declara 16dp. Os três deveriam
concordar. Corrigir isso é uma tarefa, não uma regra — e não foi feita aqui.

### Named Rules

**A Regra do Canto Contido.** Nada de cantos muito arredondados. O raio máximo do
sistema é 16dp em elemento retangular; cápsula só em pílula flutuante. Canto muito
redondo é a assinatura do app de consumo, que consta como anti-referência.

## Components

O app é construído sobre **Material Components (Material 2)**, com
`Theme.MaterialComponents.NoActionBar` e os papéis de cor mapeados em
`themes.xml`. Não é Material 3, e migrar não é tarefa deste documento — mas quem
acrescentar componente deve usar o Material 2 que já está ali, não misturar as
duas gerações.

### Botões

- **Forma:** retângulo de canto suave. Primário herda o padrão do Material;
  secundário 14dp; contorno ciano 16dp.
- **Primário:** preenchido em Azul de Ação (`#1565C0`), texto branco bold 16sp,
  **altura mínima 68dp**.
- **Secundário:** transparente com contorno de 2dp em Azul Sereno (`#69A7DB`),
  texto branco, altura mínima 68dp.
- **Contorno ciano** (`#4FC3F7`, 2dp, raio 16dp): variante usada onde o contorno
  precisa saltar mais. O comentário no código registra por quê é `strokeColor`
  nativo do Material e não `background` próprio: fundo customizado conflita com o
  Material e cortava os cantos.

### Campos

- **Estilo:** fundo em Campo (`#1A2545`), sem borda, padding de 14dp, texto 17sp
  branco, dica em terciário. O degrau de tom **é** a borda.
- **Rótulo:** acima do campo, bold 15sp em secundário, com 14dp de respiro acima.

### Agrupamento

- **Grupo de configurações:** fundo Cartão (`#0B132C`), raio 8dp, traço de 1dp em
  Divisor. É o padrão de agrupamento do app — não há `CardView` em lugar nenhum.
- **Bloco selecionado:** fundo `#12244F` com traço de 2dp em Azul de Marca. A
  seleção se diz por **contorno**, não por preenchimento saturado.

### Popup de ações

- **Cabeçalho de seção:** 11sp, caixa alta, tracking 0,13em, terciário.
- **Item:** altura 52dp, padding lateral 20dp, texto 17sp, com
  `?attr/selectableItemBackground` para o toque ter resposta.
- **Divisor:** 1dp em `#1E2D52`.

### Pílula de alerta clínico

Cápsula de raio 16dp com gradiente vertical, em três variantes: contato
(`#FFB74D`→`#F57C00`, texto branco), alergia (`#EF5350`→`#C62828`, texto branco) e
queda (`#FFE082`→`#FFC107`, **texto quase preto** `#1A1A1A`). O texto escuro no
amarelo não é exceção estética: é o que mantém o contraste legível.

### Botão de dica "(?)"

Alvo de 44dp que abre um diálogo. O comentário no código registra a decisão e ela
vale como regra geral: **não usar `tooltipText`** — em tablet não existe passar o
mouse, e o tooltip do Android só aparece em toque longo, que ninguém descobre.

### PhotoIdHeaderView (componente-assinatura)

O único lugar onde o app tem voz tipográfica. Desenha numa linha "PhotoID " em
branco sólido seguido do sufixo (`RT`, `RT Sim`, `RT Check`) preenchido com o
gradiente azul→roxo. É a mesma construção do logotipo do ecossistema
("radioterapia" branco + ".ai" no gradiente), e é o que amarra o produto à família
sem precisar de legenda. O tamanho cria a hierarquia: 56sp na Home, 44sp nos
módulos.

## Do's and Don'ts

### Do:

- **Do** dar **68dp de altura à ação primária da tela**, e **48dp de piso a todo
  o resto**. Hierarquia por tamanho de alvo, não só por cor — e sob luva, o alvo
  grande é o que impede o erro.
  **Exceção nomeada, com o motivo dentro da regra:** o botão de dica `(?)` tem
  44dp porque é alvo de **dica**, não de tarefa — errá-lo não custa nada, e
  ampliá-lo empurraria o rótulo que ele explica. É a única exceção.
  **Fora do escopo:** a Home, a Simulação e o Tratamento estão como o Henrique
  quer e **não entram nesta conta** (decisão de 20/09/2026).
- **Do** separar superfícies subindo um degrau na escada tonal
  (`#00030E` → `#0B132C` → `#0E1839` → `#1A2545`) ou com traço de 1dp em
  `#1E2D52`.
- **Do** escrever todo tamanho de texto em `sp` e todo par celular/tablet em
  `dimens.xml` + `values-sw600dp/dimens.xml`.
- **Do** distinguir estados por **brilho** além de matiz, como `#00E676` contra
  `#90A4AE` a 50%.
- **Do** usar os componentes do Material 2 que já estão no projeto.
- **Do** usar emoji **só como glifo de navegação** numa lista de grupos, como os
  13 ícones das Configurações: renderiza igual nos doze idiomas sem exigir 13
  drawables, e ajuda a varrer a lista sob luz baixa. Confirmado em 20/09/2026.

### Don't:

- **Don't** acrescentar sombra, `elevation` ou `CardView`. O sistema é plano por
  doutrina.
- **Don't** usar laranja, vermelho ou amarelo fora do alerta clínico. Essas três
  cores pertencem ao risco do paciente e espelham a ficha de Time-Out impressa.
- **Don't** espalhar o gradiente. Logotipo e um fio de régua; nada além disso.
- **Don't** usar `android:tooltipText` para explicar coisa alguma — em tablet ele
  só aparece em toque longo e ninguém o descobre. Alvo de 44dp abrindo diálogo.
- **Don't** arredondar canto além de 16dp em elemento retangular. Canto muito
  redondo é a assinatura do app de consumo, que é anti-referência confirmada.
- **Don't** introduzir cinza institucional, tabela densa sem hierarquia ou
  formulário longo. O prontuário corporativo cinza é a outra anti-referência.
- **Don't** misturar Material 3 com o Material 2 que está no projeto.
- **Don't** escrever tamanho de texto ou altura de elemento direto no layout.
- **Don't** pôr emoji em **frase que o usuário lê**, em **mensagem de estado**
  ou **dentro do PDF**. É ali que ele vira tom de aplicativo de consumo, que é
  anti-referência confirmada — e o PDF é documento clínico. O glifo de
  navegação da lista de grupos é a única exceção, e está nos Do's acima.

## Alcance desta versão

**Estas regras valem para código novo e para todo arquivo que for tocado.** Elas
descrevem para onde o sistema vai, não o que ele já é — e a diferença está
medida, não estimada:

- **50 alvos abaixo de 48dp** nas duas densidades, fora da Home e das duas telas
  de módulo, que estão fora de escopo por decisão.
- **O caminho de configuração inteiro — 23 layouts — não referencia `@dimen`**,
  então no Galaxy Tab S6 ele renderiza com os valores compactos de celular.
  `ButtonPrimary` e `ButtonSecondary` existem e são usados em **dois** arquivos,
  ambos da v4.0: a fronteira do sistema é temporal, não conceitual.
- **104 cores hexadecimais cravadas** em 28 dos 59 layouts.
- **171 atributos de API 26** (`paddingHorizontal`, `autoSize*`) num app com
  `minSdk 24`, ignorados em silêncio no Android 7.0 e 7.1.

Isto é lista de trabalho nomeada, não motivo para baixar a régua. Quem ler este
documento e concluir que o app já está conforme leu errado — e é justamente para
impedir essa leitura que os números estão aqui.
