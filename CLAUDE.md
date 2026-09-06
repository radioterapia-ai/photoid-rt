# CLAUDE.md — PhotoID RT

Contexto permanente do projeto. O Claude Code lê este arquivo automaticamente a
cada sessão; ele existe para evitar que decisões já tomadas sejam re-tomadas
erradas, e para que erros já cometidos não voltem.

---

## O que é o app

Documentação fotográfica de posicionamento em radioterapia, para tablets Android
usados dentro da sala de simulação e do acelerador. Faz parte do ecossistema
**Radioterapia.AI**.

Fluxo central: identificar o paciente → fotografar (rosto, etiqueta,
posicionamentos, acessórios, impressos) → preencher os dados da simulação
(médico, equipamento, sítio, riscos, observações) → gerar a **ficha de
posicionamento em PDF** com a página de **Time-Out** → imprimir.

Depois, no módulo de **Tratamento**, as fotos da simulação são consultadas dia a
dia para conferir o setup do paciente.

Público: técnicos de radioterapia, físicos e médicos. A interface fala **doze
idiomas**, todos completos: pt, en, es, fr, de, it, pl, zh, ja, ko, ar e bn.
Português do Brasil é a língua em que o app foi escrito; **o idioma em que ele
abre é outra coisa** — vem do aparelho, com inglês de reserva (ver
`i18n/LocaleManager.resolverIdiomaInicial`). Estado: `docs/i18n/LEIA-ME.md`.

---

## Restrições de produto (não reabrir sem pedido explícito)

**Cliques custam.** Os usuários rejeitam qualquer passo extra. Já ocorreu de
campos opcionais ficarem em branco por causa disso — a resposta acordada foi
instrução e treinamento, **não** obrigatoriedade. Não transformar campo opcional
em obrigatório, não adicionar confirmação onde ela pode ser inferida.

**O app é universal.** Serve a qualquer serviço de radioterapia, não à clínica de
origem. Nada de listas fixas, fluxos ou nomenclatura específicos de um serviço.

**Sincronia não é do app.** Fotos e PDFs vão para o servidor por FileSync,
externo. A única função de rede do app é a impressora. Não reintroduzir upload,
fila de envio ou indicadores de sincronização.

**Sem Bluetooth próprio.** Impressão por Bluetooth é coberta pelo serviço de
impressão do Android, via plugins do fabricante. Implementar protocolo próprio
foi avaliado e recusado (alto risco, baixo retorno).

**Sem microfone.** O comando por voz na captura foi implementado e **retirado**.
A promessa de que o áudio não sai do aparelho só vale no Android 13+, onde existe
API de reconhecimento local; abaixo disso `EXTRA_PREFER_OFFLINE` é preferência, e
sem pacote de idioma o sistema envia o áudio à rede. O app é universal e as
clínicas compram tablets sem controle de versão — não há como garantir o
processamento local em todo o parque. Sem garantia, a função não entra. O
microfone da sala capta a conversa com o paciente. **Não reintroduzir sem que a
promessa valha em todas as versões alvo.**

**Idioma:** toda string visível vai para `res/values*/strings.xml` em **todos**
os idiomas oferecidos. O lint trata `MissingTranslation` e `ExtraTranslation`
como erro, então falta ou sobra quebra o build — o que é a rede de segurança,
não um estorvo. String que deve sair igual em qualquer idioma (o rótulo
trilíngue da escolha de idioma) leva `translatable="false"` e vive só no base.

Idioma **só entra em `res/` quando está inteiro**. Pasta com `plurals.xml` e sem
`strings.xml` derruba o build, e código em `supportedLanguages` sem tradução
mostraria português no meio de uma tela em japonês.

**Plurais são por idioma.** As categorias vêm do CLDR e não acompanham o
português: polonês usa `one/few/many/other`, japonês só `other`, árabe as seis.
Categoria de menos faz a frase sair errada numa faixa de números que ninguém
testa. `pt`, `es`, `fr` e `it` precisam de `many` (multiplos de um milhão, pela
preposição: "um milhão **de** pacientes") — o CLDR 38 criou a categoria em 2020 e
muita tabela antiga ainda lista só `one/other`.

**Não somos autores dos modelos.** O que este projeto constrói é a **camada de
acessibilidade**. Quem reconhece o texto da etiqueta e lê o código de barras é o
Google ML Kit, componente de terceiro. Nunca escrever "nosso algoritmo", "nossa
IA" ou "desenvolvemos o modelo" — em documento, em commit ou na interface. O
correto é "o ML Kit reconhece", "integramos". De nossa autoria: o fluxo de
captura, a organização em disco, a ficha de posicionamento e as heurísticas que
interpretam o texto **já reconhecido** (`scan/EtiquetaParser.kt`).

Tornar alcançável não é neutro: é o que transforma biblioteca disponível em
usada na clínica. Por isso a declaração de apoio clínico e a conferência humana
em cada campo sugerido são responsabilidade nossa, e não enfeite.

**Rede confinada a quatro arquivos.** `PrinterClient`, `SmbClient`,
`CsvSyncManager` e `TreatmentPhotoFetcher`. O ritual de validação **quebra** se
aparecer primitiva de rede fora deles. Acrescentar rede exige incluir o arquivo
na lista de `primitivas_de_rede()`, o que aparece no diff e vai a revisão — o
risco nunca foi o que o app faz, e sim uma biblioteca nova sair para a internet
sem ninguém decidir. Detalhe da política em
`res/xml/network_security_config.xml`, que também explica por que negar texto
claro por padrão **não** é implementável aqui.

**Nenhum CSV de paciente dentro do projeto.** A pasta está em `C:\AI_PROJETOS`, que
sincroniza com o Google Drive. O `.gitignore` evita o commit e **não impede a
sincronização** — um CSV real largado aqui para testar a importação da base já
subiu antes de alguém notar, e apagar depois não desfaz. Para testar o
`CsvSyncManager`, use arquivo com dado inventado, ou aponte a configuração para
uma pasta fora de `C:\AI_PROJETOS`. `*.csv`, `*.xlsx`, `*.xlsb` e `*.dcm` estão no
`.gitignore` como segunda barreira, não como permissão.

---

## Arquitetura em uma página

```
com.radioterapia.ai/
├── MainActivity            câmera de simulação + identificação (1.742 linhas)
├── BaseActivity            toolbar comum + TODA a lógica de impressão
├── HomeActivity            entrada: Simulação | Tratamento
├── SimulationHomeActivity  nova simulação / continuar rascunho
├── pdf/PdfBuilder          ficha de posicionamento + Time-Out (1.391 linhas)
├── patient/PatientCache    cadastro em JSON, chave "NOME | PRONTUÁRIO"
├── session/SessionManager  sessão de fotos em andamento (rascunho)
├── util/StorageLocal       ONDE fica cada pasta — fonte única de verdade
├── util/TimeOutStore       dados da simulação (médico, sítio, riscos)
├── util/ObsStore           observações da simulação
├── treatment/              módulo Tratamento: carrossel, fotos adicionais
├── ui/                     Finalizar, Editar cadastro, Editar simulação,
│                           Histórico, Configurações, Logs
├── usb/PenDriveHelper      gravação em pen-drive por OTG
├── scan/EtiquetaParser     OCR da etiqueta do paciente
├── util/DateUtils          datas nos 8 idiomas (CLDR) + validação de calendário
└── transfer/PacoteConfig   exportar/importar configuração em .zip, item a item
```

**Herança:** quase toda Activity estende `BaseActivity`, que fornece a barra de
título, o slot de ação (⋮) e o seletor de impressão. Colocar algo comum ali
propaga para todas as telas — mas cuidado com o footgun nº 1 abaixo.

---

## Convenções de armazenamento

Mudar qualquer uma destas quebra bases já instaladas em campo.

```
PhotoID_RT/PHOTOS/
└── <NOME NORMALIZADO> - <PRONTUÁRIO>/           pasta do paciente
    ├── NOME_ROSTO_<data>.jpg
    ├── NOME_POSICIONAMENTO_1_<data>.jpg
    ├── NOME_FOLHA_SIMULACAO_<data>.pdf
    ├── NOME_FOLHA_SIMULACAO_NOVASIM1_<data>.pdf   reirradiação
    └── (arquivos .dcm ao lado, quando DICOM está ligado)
```

- **Nome normalizado**: sem acento, maiúsculas, sem caracteres ilegais de
  arquivo (`/ \ : * ? " < > |`), sem ponto ou espaço no fim.
- **Reirradiação**: mesma pasta do paciente, sufixo `NOVA SIMULACAO n` na pasta
  e `_NOVASIMn` nos arquivos.
- **Resolução de pasta**: sempre por `StorageLocal.resolverPastaSim()`, nunca
  concatenando strings. O nome vindo do servidor pode divergir do local.
- **Cadastro** (`PatientCache`): JSON em `filesDir`, chave `"NOME | PRONTUÁRIO"`,
  com versão de schema e backup automático antes de migrar.

---

## Toolchain (medida, não suposta)

Gradle **8.13** · AGP **8.13.2** · Kotlin **1.9.20** · JDK **21** · compileSdk 34
· minSdk 24.

O JBR do Android Studio é o **25** e o Gradle 8.13 **não roda nele** — falha com
"Type T not present", que não parece erro de JDK. O JDK fica fixado em
`.gradle/config.properties` (IDE) e em `~/.gradle/gradle.properties` (linha de
comando); nenhum dos dois é versionado.

---

## Ritual de validação (obrigatório antes de entregar qualquer lote)

Cada item nasceu de um build quebrado. Rodar todos, sempre.

**0. `gradlew check`** — roda test + lint juntos. Este é o portão.
`assembleDebug` **não** roda lint; foi por isso que o portão da E6 ficou
vermelho por meses sem ninguém ver. Esperado: 24 testes, 0 falhas, 0 erros.

1. **Chaves balanceadas** em todos os `.kt`.
2. **Diff de funções por arquivo** contra a versão anterior — função removida que
   ainda é chamada no mesmo arquivo = erro.
3. **Diff de campos por arquivo** — mesma coisa para `val`/`var`.
4. **`return@` inválido** — `try`, `catch`, `if`, `when`, `for` não aceitam label.
5. **`return` em corpo-expressão** (`fun x() = try {`) — não compila.
6. **Imports x usos** — classe usada sem import.
7. **R.id por grupo × layout** — nas Configurações, cada `adicionarGrupo` só pode
   usar IDs do layout que declarou (seguindo `<include>`).
8. **Strings**: paridade entre **todos** os `values-XX` (descobertos pela pasta,
   nunca listados), placeholders iguais, apóstrofo escapado (`\'`), escapes
   válidos, `&` escapado, e plurais com `other` em todo idioma.
9. **XML** de todos os recursos + manifest.
10. **Empacotar e conferir dentro do zip** — o que não está no zip não existe.

Os scripts ficam em `docs/scripts_validacao.py`.

---

## Footguns — erros que já custaram builds

**1. Recorte por índice engole vizinhos.** Ao mover ou remover um bloco,
delimitar por âncoras textuais e conferir quantas definições de topo existem no
trecho. Já aconteceu três vezes: sumiram funções (`abrirDialogEditarIdentificacao`,
`configurarSelecao`) e 24 campos inteiros de uma Activity.

**2. Extrair corpo de lambda com `rfind("}")`.** Pega uma chave interna. Contar
chaves a partir da primeira `{`. Esse erro duplicou três grupos de Configurações
e derrubou a tela.

**3. Diff global mascara órfã local.** Se duas classes têm método de mesmo nome,
o conjunto global continua completo. O diff tem que ser **por arquivo**.

**4. Script Python que aborta no meio não grava nada.** Um `assert` que falha
descarta todos os `write()` posteriores do mesmo script. Separar em scripts
independentes quando as alterações são de arquivos diferentes.

**5. View tocada em `Dispatchers.IO`.** Erro clássico: "Only the original thread
that created a view hierarchy can touch its views". Já apareceu duas vezes.
Capturar tudo o que vem da UI **antes** do `launch`.

**6. Apóstrofo em string EN/ES.** Português quase não usa; inglês usa sempre.
Validar TODOS os idiomas, não só o base. O português quase não usa apóstrofo e
o inglês tinha quatro; o italiano sozinho tem 144, e o francês mais. Por isso o
escape é feito na geração do arquivo, nunca à mão nem pelo tradutor.

**7. Enum como valor.** `val X = SessionManager.Category` não compila. Usar
import.

**8. `ByteArray + Int`** não existe em Kotlin — só `+ Byte`.

**9. Atualização parcial criando registro.** No `PatientCache`, gravar só o
médico num paciente inexistente criava chave paralela vazia que sequestrava as
leituras. Atualizações parciais **não** criam cadastro.

**10. `zip -r` atualiza zip existente.** Apagar o arquivo de destino antes de
empacotar, senão arquivos removidos ressuscitam.

**11. `catch (Exception)` não pega API ausente.** Chamar método que não existe
na versão do Android lança `NoSuchMethodError`, que é `Error`, **não**
`Exception` — o `try/catch` em volta não protege e o app fecha. Foi assim que
`display?.rotation` (`getDisplay()`, API 30) derrubava a captura de foto em
tablet API 24–29, nas duas telas de câmera, com um `catch (_: Exception) {}`
dando falsa sensação de segurança. Com `minSdk 24`, todo erro `NewApi` do lint é
crash em potencial: guardar com `Build.VERSION.SDK_INT`, não com `try/catch`.

**12. `obtainStyledAttributes` exige o `int[]` ordenado.** Montar o array de
atributos à mão compila e roda, mas se os IDs não estiverem em ordem crescente
devolve o atributo **errado**, em silêncio. Usar `declare-styleable` e deixar o
AAPT gerar o array.

---

## Estilo de trabalho esperado

- **Medir antes de afirmar.** Ler o código antes de propor a causa de um bug. A
  hipótese mais óbvia já se mostrou errada várias vezes.
- **Causa-raiz, não sintoma.** Quando três bugs aparecem juntos, geralmente têm
  uma causa só.
- **Entregar arquivo completo**, não patch parcial, quando a correção afeta algo
  já entregue.
- **Reportar falha explicitamente.** Se algo não foi feito ou não é possível,
  dizer — não entregar meia solução silenciosamente.
- **Validar em campo.** O usuário testa em tablet Galaxy Tab S6 com pacientes
  reais e volta com prints. Priorizar o que trava o fluxo clínico.
