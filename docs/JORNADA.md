# A jornada de cada funcionalidade

Como cada parte do app nasceu, o que deu errado no caminho e por que ela está do
jeito que está hoje. Escrito para que decisões não sejam desfeitas por engano —
várias soluções atuais parecem estranhas até se saber o problema que resolvem.

Ordem: por área, não cronológica.

---

## Identificação do paciente

**A ideia.** Reduzir ao mínimo o que se digita antes de fotografar. O paciente
está na mesa; cada campo é tempo.

**O que mudou.** No começo a identificação pedia também o **médico responsável**.
Fazia sentido no papel e nenhum na prática: o médico pertence à simulação, não ao
cadastro, e pedir ali atrasava o único momento em que o técnico precisa ser
rápido. O campo foi movido para a tela de Confirmar dados, e a identificação
ficou com o essencial — nome, nascimento, prontuário e sexo.

**Erros corrigidos.**

*A máscara de data travava o backspace.* A barra `/` era inserida depois do
segundo dígito e ficava "pendurada" no fim do texto; ao apagar, o cursor
esbarrava nela e não voltava. Foi reescrita para inserir a barra **antes** do
próximo dígito, nunca deixando separador sobrando.

*A validação aceitava datas impossíveis.* Só media o comprimento do texto, então
`31/02/2020`, `99/99/9999` e datas futuras entravam no cadastro e iam impressas
na etiqueta. Hoje há validação real de calendário: mês 1–12, dia existente no
mês, ano bissexto correto (2000 é, 1900 não é), limite de 130 anos e recusa de
data futura. Coberta por testes.

*O OCR lia a data mas não a transferia.* Etiquetas de várias unidades usam
`24-MAI-1964`. O parser só aceitava dígitos, então a data aparecia no texto
reconhecido e sumia no formulário. Hoje reconhece mês por extenso ou abreviado
nos três idiomas e converte para `dd/MM/yyyy`.

*A identificação sumia ao continuar uma simulação.* O app restaurava as fotos do
rascunho mas não o nome, o prontuário e o nascimento — e pedia tudo de novo. A
reconstrução da sessão passou a restaurar também a identificação.

**Hoje.** Digitação manual ou leitura da etiqueta por OCR, com confirmação campo
a campo. Homônimos são detectados; prontuário já usado por outro paciente gera
alerta nomeando quem o usa.

---

## Cadastro e homônimos

**O problema que ninguém tinha visto.** O cadastro era indexado **só pelo nome**.
Duas pacientes "Maria da Silva" com prontuários diferentes compartilhavam o mesmo
registro: a segunda sobrescrevia nascimento e sexo da primeira, e a contagem de
simulações somava as duas. O efeito clínico é sério — a etiqueta e o cabeçalho do
PDF podiam sair com a data de nascimento da paciente errada.

**A correção.** Chave composta `"NOME | PRONTUÁRIO"`, com leitura retrocompatível:
registros antigos (só o nome) continuam válidos e migram assim que o prontuário
aparece. Sem prontuário informado, a resolução escolhe o registro **mais
completo** (peso por nascimento, prontuário e sexo; desempate por recência).

**O bug que a correção criou.** Três funções de atualização parcial
(`atualizarSexoMedico`, `atualizarEquipamento`, `salvarCaminhoFotoRosto`)
**criavam um registro do zero** se a chave não existisse. Fluxos que chamam sem
prontuário passaram a gerar uma chave base vazia ao lado da composta — e, como a
resolução devolvia a base assim que ela existisse, ela sequestrava as leituras.
Nascimento e sexo "sumiam". Hoje atualizações parciais não criam cadastro, e
`registrarSimulacao` consolida registros duplicados herdados de versões
anteriores.

**Versionamento.** O JSON ganhou `__schema__` com número de versão e backup
`.vN.bak` gravado antes de qualquer migração. A migração v1→v2 é preguiçosa —
converte quando o prontuário aparece, sem reprocessar milhares de registros na
abertura.

---

## Captura de fotos

**A ideia.** Cinco categorias, cada uma com propósito claro na ficha: Rosto (para
conferência de identidade), Etiqueta (rastreabilidade), Posicionamento (o setup),
Acessórios (máscaras, apoios), Impressos (documentos).

**Detalhes que vieram do campo.**

*Contador de fotos nas abas.* Existia só em Posicionamento. Acessórios e
Impressos também acumulam várias fotos e ficavam sem indicação — passaram a ter
o mesmo badge laranja.

*Nome do paciente sobrepondo o título.* No cabeçalho da câmera, nome longo
invadia o rótulo central da categoria. Resolvido com limite de largura e
reticências.

*Botão de captura cortado.* Na câmera de fotos adicionais, o círculo vermelho não
cabia inteiro em telas baixas. A barra ganhou altura mínima e, em celular
deitado, o botão encolhe dinamicamente para sobrar área de preview.

*Quatro botões após a foto.* Ao confirmar uma captura apareciam também os botões
do "rolo", que não faziam nada naquele momento. Hoje só os dois com função
aparecem.

**Hoje.** CameraX com zoom, flash, grade, silenciamento e troca de câmera.
Detector de qualidade avisa foto escura, clara ou borrada. Cada foto ganha marca
d'água EXIF com paciente e carimbo.

---

## Ficha em PDF e Time-Out

Esta é a parte mais trabalhada do app, e a que mais consumiu iterações.

**A ideia.** Uma folha que vai para o prontuário físico, com identificação do
paciente, foto do rosto, dados de segurança (Time-Out) e as fotos do
posicionamento.

**Etiqueta física × virtual.** Muitas clínicas colam uma etiqueta impressa do
sistema hospitalar na ficha. O app reserva um retângulo do tamanho configurado e
desenha as identificações **fora** dele. Quando não há etiqueta física, desenha
uma "etiqueta virtual" com contorno tracejado e os dados dentro.

**A geometria e seus erros.** A posição das identificações depende do espaço que
sobra ao lado da etiqueta:

- sobra ≥ 70pt → identificações **ao lado**;
- sobra < 55pt → **abaixo** (não há espaço lateral utilizável);
- entre os dois, com etiqueta baixa → abaixo; com etiqueta alta → ao lado, em
  fonte reduzida.

A regra inicial não previa **sobra negativa**. Com etiqueta de 100×70mm — comum
em uma das unidades — a etiqueta consome o bloco inteiro e a sobra fica em
−10pt, mas o código ainda tentava escrever ao lado: o nome e os IDs eram
desenhados **sobre a foto do paciente**. Junto disso, a caixa da etiqueta usava
uma régua de largura diferente da que decidia o layout (daí o desalinhamento com
o bloco do sítio), e a data da simulação ia parar sobre o bloco de equipamento.
Os três defeitos tinham a mesma origem geométrica e foram corrigidos juntos.

*Nome truncado contra a foto.* As identificações eram desenhadas em uma linha só,
cortando o nome. Hoje o nome quebra em várias linhas enquanto couber na altura da
etiqueta; se o conjunto ultrapassar, tudo é realocado abaixo.

*Observações centralizadas na vertical.* Uma observação de uma linha ficava no
meio do box, parecendo solta. Passou a começar na primeira linha, centralizada só
na horizontal.

*Rodapé.* Ganhou o formato `Clínica ● Página n`, sem total de páginas — a ficha
entra num prontuário com outras folhas, e "1/2" confundia. O `x/y` que aparecia
no canto era do visualizador de PDF do Android, não do arquivo.

*Impressão mais escura que a rotina antiga.* As fotos saíam mais escuras que pelo
Excel da clínica. Ganharam compensação leve de brilho (+7% e offset) aplicada
**só nas fotos**, no desenho do PDF — logo e gráficos ficam intactos.

*Margem de impressão.* Era fixa em ~10mm. Passou a ser configurável (padrão
15mm), aplicada por deslocamento do canvas: margem esquerda no retrato, superior
no paisagem.

**Hoje.** Página 1 é a ficha de Time-Out; as seguintes, o grid de fotos.
Adapta-se a etiqueta física ou virtual, a qualquer tamanho de etiqueta, às duas
orientações e à margem configurada.

---

## Fluxo de finalização

**O bug estrutural.** Os campos de Confirmar dados (médico, equipamento, sítio,
riscos, observações) **só apareciam depois de finalizar** — e apareciam
travados. O motivo: todo o setup deles estava dentro da função que roda no
pós-finalização. O comportamento era exatamente o inverso do pretendido.

**A reorganização.** Três fases claras:

1. **Confirmar dados** — box azul com o resumo, todos os campos editáveis, botões
   Voltar à câmera e Finalizar simulação.
2. **Simulação finalizada** — dados consolidados, alerta de sucesso, botões
   Visualizar PDF, Imprimir, Compartilhar, Editar simulação e Voltar ao Menu.
3. **Editar dados** — volta os campos editáveis, com Salvar alterações (que
   regenera o PDF), Editar cadastro e Adicionar mais fotos.

**Outro bug da mesma família.** O box azul saía vazio depois de finalizar, porque
o resumo era reconstruído **após** a limpeza da sessão. Passou a usar um snapshot
tirado antes.

**A separação que resolveu uma classe inteira de bugs.** Editar cadastro (nome,
nascimento, sexo, prontuário) estava disponível dentro do fluxo de simulação.
Como renomear o paciente **move a pasta**, o PDF em andamento e a sessão ficavam
apontando para um caminho que não existia mais — daí "simulação não localizada",
perda de sítio e riscos, e impossibilidade de salvar. A regra passou a ser:
**no fluxo de simulação só se edita a simulação**; cadastro só pelo carrossel ou
pelas Configurações.

**Erro de thread.** Salvar alterações falhava na primeira tentativa e funcionava
na segunda. Causa: uma função que escreve na tela era chamada de dentro de
`Dispatchers.IO`. Apareceu duas vezes em pontos diferentes.

---

## Módulo Tratamento e o carrossel

**A ideia.** No dia da irradiação, conferir o setup olhando as fotos da
simulação.

**A evolução do visor.** Começou com miniaturas que abriam fotos isoladas — e a
navegação morria ali: no rosto, não dava para avançar. Hoje é um **carrossel
único e contínuo** com todos os blocos em sequência (Resumo, Rosto, Etiqueta,
Posicionamentos, Acessórios, Impressos). Rolar atravessa tudo; a miniatura ativa
só muda de destaque; arrastar antes do primeiro item volta ao Resumo.

**A tela de Resumo.** Nasceu da ideia de um "modo beira-do-acelerador": uma tela
só, sem navegação, com o que importa na conferência. Rail de identificação com
nome grande e fio em gradiente, alertas em pílulas nas mesmas cores da ficha
(laranja para precaução de contato, vermelho para alergia, amarelo para risco de
queda), rosto em destaque, miniaturas empilhadas com overflow "+N mais…", dados
do Time-Out e observações.

**Erros corrigidos no caminho.**

*Contorno de seleção invisível.* O realce era pintado no `background` do frame, e
a miniatura o cobria inteiro. Só o bloco Resumo parecia funcionar, porque seu
ícone é pequeno. Passou para `foreground`, desenhado por cima.

*Miniatura de posicionamento mostrando a etiqueta.* Usava o primeiro item do
carrossel, que virou a etiqueta quando a lista foi unificada. Passou a usar a
primeira foto daquela categoria.

*Resumo sem médico, sítio e equipamento.* A leitura do Time-Out rodava na thread
principal varrendo pastas e não tinha plano B. Hoje é assíncrona, cai para o
cadastro do paciente quando o Time-Out não traz médico ou equipamento, e procura
na pasta real das fotos se a pasta resolvida não tiver os registros.

*Identificação duplicada.* Nome, prontuário e nascimento apareciam no rail e
outra vez na barra inferior. Na tela de Resumo a barra passou a mostrar a
observação; nos demais blocos segue com a identificação.

*Estado perdido ao girar.* O carrossel voltava para a primeira foto. Bloco e
página passaram a ser preservados.

**Popup de ações (⋮).** Subiu do canto flutuante sobre a foto para a barra de
título. Agrupado por intenção: Documentos (ver ficha, imprimir, compartilhar) e
Editar (cadastro, simulação, adicionar fotos), com Resimular separado em
vermelho por ser destrutivo.

---

## Impressão

**Como era.** Só impressora de rede por IP (JetDirect 9100 / IPP). Se ela não
respondesse, o botão simplesmente apagava — e o usuário ficava sem saída. Pior:
em um dos fluxos o app mostrava barra de progresso e "imprimia" mesmo com a
impressora offline, porque só verificava se havia **IP configurado**, nunca se
respondia.

**Como é.** Um **seletor de modo** antes de imprimir:

- **Impressora da rede** — testa a conexão de verdade e aparece esmaecida se não
  responder;
- **Pen-drive (cabo OTG)** — grava o PDF num pen-drive plugado no tablet;
- **Copiar para pasta de impressão** — pasta local, limpa a cada uso.

O botão nunca mais apaga: sem rede ainda há dois caminhos.

**O que não deu para fazer.** "Transformar o tablet em pen-drive" foi pedido e é
impossível: o Android abandonou USB Mass Storage no 4.4 e hoje só expõe MTP/PTP,
que impressoras não leem. O caminho viável é o inverso — o tablet como *host*
USB, com o pen-drive plugado via OTG. Foi o que se implementou.

**Detecção do pen-drive.** Duas frentes, porque nenhuma sozinha basta:
`UsbManager` enxerga o adaptador no instante em que é plugado, mas ainda não dá
para gravar; `StorageManager` confirma que existe volume removível **montado**.
Disso saem quatro estados na tela — sem cabo, reconhecendo, conectado (falta
escolher a pasta), pronto — atualizados ao vivo por um receiver enquanto o
diálogo está aberto. Uma animação de três quadros em loop mostra o cabo sendo
conectado.

**Organização no pen-drive.** O painel das impressoras é lento de navegar, então
as pastas têm prefixo numérico para a certa aparecer primeiro:
`1_PACIENTE_ATUAL`, `2_LOTE_INDIVIDUAIS`, `3_LOTE_AGRUPADO`, `9_ANTIGOS`.

Houve uma versão que **perguntava** "adicionar à sessão atual ou abrir nova?".
Foi removida: arquivar é sempre seguro, porque nada é apagado — ao gravar numa
pasta, o conteúdo anterior **dela** vai para `9_ANTIGOS` com carimbo de data e
hora. Um diálogo a menos.

**Impressão em lote.** Nas listas de pacientes, o ⋮ abre "Imprimir PDFs em lote".
A lista entra em modo seleção: a coluna direita (contagem, ícone de PDF, tag)
some e dá lugar a um checkbox. A seleção é guardada por chave nome+prontuário,
não por posição, então sobrevive à filtragem — dá para filtrar por data, marcar,
filtrar de novo e não perder nada.

**O lote agrupado e a decisão de não rasterizar.** A primeira versão juntava PDFs
prontos rasterizando página a página (`PdfRenderer` → bitmap → `PdfDocument`),
porque as bibliotecas que preservam vetores (iText, PDFBox) são AGPL. O resultado
funcionava mas era uma colagem de imagens: arquivo grande, texto não pesquisável.
A solução final foi melhor — **remontar o PDF do zero** a partir dos dados de
origem, extraindo o miolo do gerador para uma rotina que desenha num documento já
aberto. O lote virou um PDF nativo, e o mesclador rasterizado foi apagado.

---

## Editar simulação

**A ideia.** Cenário real: o paciente já foi embora e a ficha saiu sem médico,
sítio ou riscos. Precisava dar para corrigir sem refazer nada.

**Como funciona.** Pelo ⋮ do carrossel, uma tela dedicada com os campos do
Time-Out e as observações. Pré-carrega o que existe, grava nos stores e regenera
o PDF **mantendo a página de Time-Out**.

**Bugs corrigidos.** Uma condição sempre verdadeira (`if (config.pdfUsarEtiqueta
|| true)`) fazia a página de Time-Out voltar mesmo para clínicas que a
desligaram — e a variável testada nem era a certa. As observações não tinham
limite de 3 linhas, então o usuário digitava oito, salvava com sucesso e o PDF
mostrava três, em silêncio.

---

## Configurações

**A dor.** A cada atualização do app, refazer nome da clínica, médicos,
equipamentos, sítios, tamanho de etiqueta e orientação. Trabalhoso e propenso a
erro.

**A resposta.** Exportar/Importar configurações em JSON — todas as preferências
num arquivo, restauradas em segundos, inclusive num tablet novo. O logotipo fica
de fora por ser binário e rápido de reenviar. E as abas "Identidade da clínica" e
"Equipe e Tratamentos" viraram uma só, porque o usuário não lembrava em qual
estava cada campo.

**O acidente da fusão.** A junção das abas foi feita com um script que extraía o
corpo do bloco buscando a última chave — que pegou uma chave interna. O
resultado: três grupos de configuração **duplicados**, bindings parando no grupo
errado, uma checkbox perdida e a tela fechando o app ao abrir. A reconstrução
passou a contar chaves em vez de buscar texto, e entrou no ritual uma validação
que cruza os `R.id` usados em cada grupo com os IDs do layout que ele declara.

**Também nas Configurações.** Exportar/Importar base de dados (fotos + PDFs +
cadastro) para migrar de tablet; remoção por período com confirmação explícita
de ação irreversível; e a lista de sítios anatômicos, que era fixa e passou a ser
editável.

---

## DICOM

**A ideia.** As fotos vivem como JPG e PDF numa pasta. Em DICOM, viram objetos do
PACS/TPS vinculados ao mesmo Patient ID do prontuário oncológico — deixam de ser
anexo e passam a fazer parte do registro do paciente.

**Como foi feito.** Gerador próprio, sem dependências: preâmbulo de 128 bytes,
meta-informação em Explicit VR e dataset em Implicit VR Little Endian, pixels RGB
8 bits sem compressão — o formato mais conservador possível, que qualquer
visualizador antigo abre. Patient Name no formato `SOBRENOME^NOME`, Study e
Series UID compartilhados por toda a simulação.

Os `.dcm` são gravados **na mesma pasta do paciente**, então o FileSync os leva
junto sem nenhuma mudança de rotina.

**Ressalva honesta.** Nunca foi validado contra um PACS real. Antes de confiar em
produção, importar um arquivo num Aria/Mosaiq de teste e confirmar que o paciente
casa pelo ID.

---

## Modo horizontal

**O que estava acontecendo.** Em paisagem o app fechava sozinho, não avançava
telas, não habilitava paciente novo e mostrava botões antigos que já não
funcionavam.

**A causa.** Existiam quatro layouts paralelos em `res/layout-land/`, congelados
em versões antigas: sem os ids novos (crash imediato), com botões removidos do
código, sem campos exigidos pela validação.

**A correção.** `layout-land` foi apagado por inteiro. O Android passou a usar os
mesmos layouts em qualquer orientação, e as telas que precisam de arranjo
diferente decidem **em runtime** — como o Resumo, que põe o rail ao lado no
tablet deitado e empilhado no celular em pé. Uma fonte única de layout eliminou a
classe inteira de divergências entre orientações.

---

## Tipografia e botões

Um capítulo próprio porque consumiu várias rodadas e a causa não era óbvia.

**O sintoma.** Botões irmãos com fontes diferentes; texto cortado; ao girar o
tablet as fontes mudavam sozinhas; "COMPARTILHAR" virava só a seta.

**As causas, em ordem de descoberta.**

1. O uniformizador media a fonte **antes** do autosize estabilizar, congelando um
   valor errado. Passou a esperar dois ciclos de layout.
2. Media dentro de acordeão fechado, com largura zero. Passou a esperar largura
   válida — e depois a escutar o layout real, porque o acordeão pode abrir
   minutos depois.
3. Forçava `maxLines=1` em todos, matando os botões de duas linhas.
4. Era one-shot: congelava em pixels e nunca mais remedia. Ao girar (telas de
   câmera não recriam a Activity), a largura mudava e a fonte ficava errada.

**Hoje** o uniformizador respeita o `maxLines` do XML e reinstala um listener
permanente: toda mudança de largura religa o autosize, espera dois ciclos e fixa
de novo a menor fonte comum.

**Cantos retos que não sumiam.** O tema é MaterialComponents, então todo `Button`
é um MaterialButton — que ignora `android:background` custom e recorta os cantos.
A solução foi falar a língua dele: `backgroundTint` nos sólidos e um style
`OutlinedButton` nos vazados.

---

## Testes e qualidade

Por muito tempo o projeto não tinha teste nenhum — `app/src/` só tinha `main`.
Depois de vários ciclos build-quebra-conserta, entraram três suítes no que é puro
e crítico:

- **DateUtilsTest** — validação de nascimento (bissexto, datas impossíveis,
  futuro) e formatação nos três idiomas.
- **StorageLocalTest** — sanitização de nomes e casamento de pastas nos três
  formatos históricos.
- **StringsParidadeTest** — paridade e placeholders dos três idiomas, lendo os
  XMLs direto. Pega em segundos o que antes só o build acusava.

O lint ganhou portão: `MissingTranslation`, `ExtraTranslation` e
`MissingDefaultResource` quebram o build. `HardcodedText` ficou como aviso —
restam 17 textos fixos nos layouts, todos siglas e IPs de exemplo.
