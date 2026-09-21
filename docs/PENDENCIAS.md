# Pendências e limitações

O que ficou em aberto, o que foi recusado de propósito e o que é limitação real
da plataforma. Serve para não reabrir discussão já encerrada e para não prometer
o impossível.

---

## Em aberto — vale fazer

### Card órfão: chave paralela vazia no `PatientCache`

**Relatado em 21/09/2026, não reproduzido.** Um paciente criado logo após o
cadastro do segundo protocolo ficou com um card que não some: o PDF e as fotos
foram apagados, mas o card seguiu exibindo nome e prontuário. Em «editar
cadastro» o nome e o prontuário aparecem preenchidos e a data de nascimento e o
sexo estão vazios; «editar informações da simulação» não abre.

O rastro aponta para uma **chave paralela vazia** — o footgun nº 9, que já
mordeu antes. `obterDadosPaciente` lê nome e prontuário da **CHAVE** e
nascimento e sexo do **CORPO** do registro. Um corpo vazio sob uma chave válida
produz exatamente esse sintoma, e explica por que a remoção não alcança o
registro: ela procura pela chave que o card mostra, que não é a que sobrou.

Não foi corrigido na v4.3 porque **não há caso que o reproduza**, e correção sem
caso é palpite — mexer no `PatientCache` às cegas é como a chave paralela
nasceu. O que a v4.3 corrigiu foi o que estava provado por leitura: o
casamento de pasta só por nome na exclusão e na edição.

**Para atacar:** procurar qual escrita parcial cria chave sem corpo. Os
candidatos são `atualizarEquipamento`, `atualizarSexoMedico` e `consolidar`,
que são os caminhos que gravam sem passar pelo cadastro completo.


### Importação da base de pacientes está sem tela de configuração

O `CsvSyncManager` é lido na abertura do app (`HomeActivity`), mas **não há
tela** que permita definir a pasta do CSV nem o mapeamento de colunas: os campos
viviam em `group_csv_database.xml`, layout que nenhum `adicionarGrupo` jamais
inflou. Com `csvPastaUnc` vazio, a `HomeActivity` sai cedo e a sincronia nunca
roda.

Pior: os binds eram declarados e nunca atribuídos, então salvar as Configurações
**apagava** `csvPastaUnc` e zerava o mapeamento. Esse bind destrutivo foi
removido; a falta de tela permanece.

**Decisão pendente:** ou se constrói o grupo de configuração do CSV nas
Configurações, ou se assume que a base de pacientes não é configurável no app e
o preenchimento continua manual. Enquanto não se decide, o código do
`CsvSyncManager` fica — é funcional, só inalcançável.

### Strings interpoladas ainda no código

Restam cerca de 140 literais em português com interpolação (`"Erro: $mensagem"`)
em logs internos e nomes de arquivo. As 23 visíveis ao usuário já foram
extraídas. Converter o resto exige transformar cada uma em recurso com argumentos
de formato — trabalho mecânico, mas que mexe em muitas assinaturas de uma vez.

### Textos fixos nos layouts

17 restantes: siglas (`RT`, `RT Sim`), símbolos (`▶`) e IPs de exemplo. Enquanto
existirem, `HardcodedText` fica como aviso no lint em vez de erro.

### `MANAGE_EXTERNAL_STORAGE`

Permissão ampla, necessária hoje para gravar em `/storage/emulated/0/PhotoID_RT`
onde o FileSync enxerga. Se um dia o app for para a Play Store, exige
justificativa formal — ou migração para SAF, que muda bastante código.

### Testes de PDF

As três suítes cobrem datas, nomes de pasta e traduções. O PDF, que é a parte
mais delicada, só é verificado a olho. Um teste que gere o PDF e confira
estrutura das páginas pegaria regressões de layout automaticamente.

### Preview antes de importar base

A importação valida a integridade do CSV, mas não mostra o que vai entrar. Um
resumo — quantos pacientes novos, quantos colidem, o que será sobrescrito — antes
de confirmar reduziria o risco.

---

## Recusado de propósito

Não são esquecimentos. Cada um foi avaliado e descartado com motivo.

**Checklist de Time-Out assinado.** Os usuários rejeitam cliques extras e já
deixam campos opcionais em branco. Tornar obrigatório iria contra o princípio de
automação sem cliques. A completude é tratada por instrução.

**Backup automático agendado.** A sincronia é contínua e externa (FileSync).
Duplicar isso dentro do app não agrega.

**Sítios sugeridos por equipamento.** O app é universal; a associação entre
acelerador e sítios é específica de cada serviço.

**Indicador de fila de envio.** O app não sincroniza mais pela rede. A única
função de rede é a impressora.

**Desfazer "DAR ALTA".** O histórico já permite realocar o paciente para
tratamento — o caminho de volta existe e é rastreável.

**Ajustes de fonte e contraste.** Avaliados e mantidos como estão, por decisão de
produto.

**Pareamento Bluetooth próprio.** O serviço de impressão do Android já cobre
Bluetooth através dos plugins do fabricante. Implementar protocolo próprio
significaria suportar cada modelo de impressora — alto risco, baixo retorno. Se
algum modelo específico da clínica não aparecer pelo serviço do sistema, vale
avaliar o caso concreto.

---

## Limitações da plataforma

**Tablet como pen-drive: impossível.** O Android abandonou USB Mass Storage no
4.4; hoje só expõe MTP/PTP, que impressoras não leem. Exigiria root ou firmware
do fabricante. A alternativa implementada é o inverso — tablet como *host* USB,
com pen-drive plugado por OTG.

**Sem biblioteca de PDF.** iText e PDFBox são AGPL, incompatível com a
distribuição de um app clínico. Toda a geração usa as APIs nativas do Android.
Foi por isso que o lote agrupado precisou ser **remontado** a partir dos dados,
em vez de mesclar arquivos prontos — a solução acabou melhor, mas veio dessa
restrição.

**Cadastro em JSON, não em banco.** Funciona bem na escala atual (milhares de
pacientes). Se a base crescer muito, a leitura completa na abertura pode pesar.
Há versionamento de schema pronto, então migrar depois é viável.

---

## Riscos conhecidos que valem monitorar

**Compensação de brilho na impressão.** O fator (+7% e offset) é empírico,
calibrado por comparação com a rotina antiga do Excel. Se em outra impressora
destoar, ajustar o valor em `PdfBuilder.paintFotoImpressao`.

**Modo lado-apertado da etiqueta.** Com etiqueta larga *e* alta, nomes muito
longos truncam em fonte mínima. É o preço de nunca invadir a tabela — mas se
aparecer com frequência, vale reavaliar os limiares de decisão.

**`runBlocking` no EditarPaciente.** A regeneração do PDF após migração usa
`runBlocking` para chamar uma função suspensa. Funciona, mas bloqueia a thread;
se a busca ficar lenta, converter para coroutine adequada.

## Pipeline de i18n defasado em 85 strings (21/09/2026)

`docs/i18n/pendente/traducoes` tem 779 chaves; `res/values-XX` tem 864.
`i18n_montar.py --gravar` apagaria as 85 e ressuscitaria 5 ja removidas.
Detalhe e o caminho de ressincronizacao em `docs/i18n/LEIA-ME.md`.
