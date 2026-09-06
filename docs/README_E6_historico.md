# PhotoID RT - historico da entrega E6

So consulta. O README da raiz e o ponto de entrada do projeto.
Este arquivo guarda o que a entrega E6 mudou, no formato em que foi
escrito na epoca.

---

## O que mudou na E6

### 🔴 Correções de risco clínico

| # | Problema | Efeito em campo | Correção |
|---|----------|-----------------|----------|
| 1 | Cadastro indexado só pelo **nome** | Dois pacientes homônimos compartilhavam registro: nascimento, sexo e contagem de simulações do paciente errado iam para o PDF | Chave composta `NOME \| PRONTUÁRIO`, com leitura retrocompatível e migração automática dos registros antigos |
| 2 | Condição sempre verdadeira em "Editar simulação" | Clínica com página de Time-Out desligada recebia a página de volta ao editar | Passa a respeitar `pdfIncluiTimeOut` |
| 3 | Validação de nascimento só media o comprimento | `31/02/2020`, `99/99/9999` e datas futuras entravam no cadastro e na etiqueta | Validação real de calendário (mês, dia do mês, ano bissexto, limite de 130 anos, não-futura) nos 4 pontos de entrada |
| 4 | 196 mensagens fixas em português no código | Usuário em EN/ES via diálogos e erros em português | 33 mensagens de UI + 22 textos de layout extraídos para recursos nos 3 idiomas |
| 5 | `DateUtils` sem meses em espanhol | PDFs em ES saíam com meses em português (MAI/SET/DEZ em vez de MAY/SEP/DIC) | Array `MESES_ES` próprio |
| 6 | Nome não removia caracteres ilegais de arquivo | OCR da etiqueta podia injetar `/ \ : * ? " < > |` e quebrar a criação da pasta | Sanitização completa + remoção de ponto/espaço final (regra Windows/SMB) |
| 7 | Nenhuma checagem de espaço em disco | Tablet cheio gravava fotos e PDF pela metade | Bloqueio com aviso quando restam menos de 80 MB |
| 8 | `copyTo` sem proteção na reconstrução da sessão | IOException derrubava o app no meio do salvamento | Falha isolada por foto, sem perder a sessão |
| 9 | Prontuário duplicado não era checado | Mesmo número em pacientes diferentes, sem aviso | Alerta identificando o paciente que já usa o número |
| 10 | Observações truncavam em silêncio em "Editar simulação" | Usuário digitava 8 linhas, o PDF mostrava 3 | Mesmo limite de 3 linhas da tela de finalização |

### 🟢 Melhorias

- **Testes automatizados** (`app/src/test/`) — primeira suíte do projeto:
  `DateUtilsTest` (validação e formatação de datas nos 3 idiomas),
  `StorageLocalTest` (sanitização e casamento de pastas),
  `StringsParidadeTest` (paridade e placeholders dos 3 idiomas, lendo os XMLs).
  Rodam na JVM em segundos: `./gradlew test`
- **Portão de qualidade no build** — `MissingTranslation`, `ExtraTranslation` e
  `MissingDefaultResource` agora **quebram o build**. Foi exatamente esse tipo de
  falha (string existindo só em EN/ES) que chegou ao tablet antes.
- **Proteção contra perda de fotos** — a seta ← do cabeçalho na câmera de fotos
  adicionais ia direto para `finish()`, descartando o rolo sem aviso; agora passa
  pela mesma confirmação do botão voltar. O aviso da câmera principal informa
  **quantas fotos** estão no rascunho.
- **Exportar / Importar base de dados** (Configurações → Base de dados) — o
  `BackupManager` existia mas nunca havia sido exposto na interface. Gera e lê um
  pacote com fotos, PDFs e cadastro para **migrar de tablet**.

### 🔵 Segunda rodada da E6 (auditoria após o primeiro build)

**Correções**
- **Strings de UI com interpolação** — as 23 mensagens visíveis (`"Erro: $e"`,
  `"Exportado: $nome"`…) viraram recursos com argumentos de formato nos 3 idiomas.
  Restam apenas literais de log interno e nomes de arquivo.
- **Zero `!!` no projeto** — os três pontos de NPE potencial (preview do logo,
  URI do logo no wizard, título da aba) agora usam cópia local ou `?.let`.
- **`.first()` — falso positivo.** A varredura acusou 4 casos; a inspeção mostrou
  que **todos já estavam guardados** (`isNotEmpty()`, `return` antecipado, array
  de tamanho fixo). Nada foi alterado: mexer em código correto só adiciona risco.
- **Versão de schema no cadastro** — `__schema__` com número de versão, backup
  `.v<N>.bak` antes de qualquer migração e migração preguiçosa v1→v2 (chave
  composta). Sete pontos de iteração foram blindados para não tratar a entrada de
  metadados como paciente.
- **Integridade do CSV na importação** — `analisarCsv()` recusa arquivo vazio, sem
  cabeçalho reconhecível, sem linhas aproveitáveis ou com excesso de linhas
  inválidas (separador/colunas trocados). Fotos e PDFs entram; o cadastro só é
  mesclado se o arquivo for íntegro.
- **Estado preservado na rotação** — carrossel (bloco + página) e histórico
  (busca + rolagem) sobrevivem ao giro do tablet.

**Melhorias**
- **Aviso preventivo de armazenamento** na abertura do app abaixo de 500 MB — o
  bloqueio no finalizar continua, mas avisar só ali é tarde demais.

**Novidades**
- **DICOM Secondary Capture** (`dicom/DicomExporter.kt`) — cada foto ganha um
  `.dcm` na **mesma pasta do paciente**, com Patient Name/ID/Birth Date/Sex,
  Study e Series UID compartilhados pela simulação. Vai junto no FileSync e entra
  no PACS/TPS como objeto do paciente, não como anexo. Implementação própria, sem
  dependências, em Explicit VR (meta) + Implicit VR LE (dataset), RGB 8 bits.
  Ligável em Configurações → PDF.
- **Bloco "Resumo" no carrossel** — primeiro bloco, abre por padrão: nome e
  identificadores, **alertas em faixa vermelha** (queda, contato, alergia), rosto
  e posicionamento lado a lado, e os dados do Time-Out. Conferência à beira do
  acelerador numa tela só, sem navegação. Os demais blocos seguem iguais e tocar
  nas fotos do resumo leva ao bloco correspondente.

### Decisões conscientes (avaliadas e recusadas)

- **Checklist de Time-Out assinado** — recusado: os usuários rejeitam cliques
  extras e já negligenciam campos opcionais. A completude será tratada por
  instrução, não por obrigatoriedade.
- **Backup automático agendado** — desnecessário: a sincronia é contínua, feita
  por FileSync fora do app.
- **Sítios sugeridos por equipamento** — recusado: o app é universal, não
  específico de um serviço.
- **Indicador de fila de envio** — removido do escopo: o app não sincroniza mais
  pela rede; a única função de rede é a impressora.
- **Desfazer "DAR ALTA"** — desnecessário: o histórico já permite realocar o
  paciente para tratamento.
- **Ajustes de fonte e contraste** — mantidos como estão, por decisão do produto.

---

## Como testar a E6

### Instalação
1. Abra o projeto no Android Studio e sincronize o Gradle.
2. `Build > Make Project` e instale o APK de debug no tablet.

### Testes unitários (novo)
```
./gradlew test
```
Falha esperada = zero. Se algum idioma perder uma chave, `StringsParidadeTest`
acusa antes do build.

### Roteiro clínico sugerido
1. **Homônimos** — cadastre dois pacientes com o mesmo nome e prontuários
   diferentes. Confirme que cada PDF sai com o nascimento e o sexo corretos e que
   a contagem de simulações não é compartilhada.
2. **Datas inválidas** — tente `31/02/2020`, `29/02/2021` e uma data futura.
   Todas devem ser recusadas. `29/02/2020` deve ser aceita.
3. **Prontuário duplicado** — edite um paciente atribuindo o prontuário de outro;
   o alerta deve nomear o paciente que já usa o número.
4. **Espaço em disco** — com o tablet quase cheio, finalize uma simulação: o app
   deve avisar antes de começar.
5. **Perda de fotos** — na câmera de fotos adicionais, capture 3 fotos e toque na
   seta ← do cabeçalho. Deve pedir confirmação.
6. **Migração de tablet** — Configurações → Base de dados → Exportar base;
   importe em outro tablet e confira fotos, PDFs e cadastro.
7. **Idiomas** — troque para inglês e espanhol e percorra as telas: os diálogos
   extraídos devem aparecer traduzidos, e as datas dos PDFs em espanhol devem usar
   ENE/ABR/MAY/AGO/SEP/DIC.

---

