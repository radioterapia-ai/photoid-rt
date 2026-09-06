# Arquitetura

Como o app está organizado, por onde os dados passam e quais formatos são
gravados. Escrito para quem vai mexer no código sem ter acompanhado a
construção.

---

## Os dois módulos

O app se divide em duas metades que compartilham armazenamento mas têm fluxos
independentes.

**Simulação** — o que acontece no dia do planejamento. Identifica o paciente,
captura as fotos por categoria, coleta os dados do Time-Out e produz a ficha em
PDF. Ponto de entrada: `HomeActivity → SimulationHomeActivity → MainActivity`.

**Tratamento** — o que acontece nos dias de irradiação. Busca o paciente, mostra
as fotos da simulação para conferir o setup, permite adicionar fotos novas e
reimprimir. Ponto de entrada: `HomeActivity → TreatmentActivity →
PatientViewerActivity`.

O elo entre eles é a **pasta do paciente** no disco. Não há banco relacional: o
sistema de arquivos é a fonte de verdade, e o cadastro em JSON é um índice.

---

## Camadas

### Telas (`MainActivity`, `ui/`, `treatment/`, `wizard/`)

`BaseActivity` é a superclasse de quase todas. Fornece:

- barra de título própria (não usa ActionBar), com seta de voltar e engrenagem;
- um **slot de ação à direita** (`acaoToolbar()`), usado pelo ⋮ do carrossel e
  das listas;
- **toda a lógica de impressão** — seletor de modo, impressão por IP, pen-drive
  OTG, cópia para pasta.

Colocar comportamento comum na `BaseActivity` propaga para tudo. Foi assim que
carrossel, finalização e câmera de fotos adicionais passaram a compartilhar o
mesmo seletor de impressão sem duplicar código.

### Domínio (`patient/`, `session/`, `util/*Store`)

`SessionManager` guarda a **sessão em andamento**: fotos capturadas, categoria de
cada uma, identificação do paciente, carimbo de início. Persiste em JSON, o que
permite fechar o app e continuar depois ("rascunho"). Expira em 30 dias.

`PatientCache` é o **cadastro**: nome, prontuário, nascimento, sexo, médico e
equipamento habituais, contagem de simulações. Chave `"NOME | PRONTUÁRIO"` para
distinguir homônimos — ver a seção de formatos.

`TimeOutStore` e `ObsStore` guardam os **dados da simulação** (médico, sítio,
equipamento, riscos, alergia, observações) dentro da pasta da própria simulação.
Ficam separados do cadastro de propósito: o médico que atendeu naquela simulação
não é necessariamente o médico habitual do paciente.

### Geração (`pdf/`, `dicom/`)

`PdfBuilder` desenha tudo vetorialmente com `android.graphics.pdf.PdfDocument`.
Duas entradas:

- `gerarFolhaPosicionamento(...)` — uma simulação, um arquivo;
- `gerarLoteAgrupado(context, itens, saida)` — várias simulações no mesmo PDF.

As duas chamam a mesma rotina interna `desenharSimulacao(doc, ...)`, que desenha
num documento **já aberto**. É isso que permite empilhar pacientes sem rasterizar
nada — o PDF do lote tem texto pesquisável e vetores intactos.

`DicomExporter` gera DICOM Secondary Capture das fotos, sem biblioteca externa.

### Infraestrutura (`util/StorageLocal`, `print/`, `usb/`, `backup/`)

`StorageLocal` é a **fonte única de verdade sobre caminhos**. Qualquer código que
precise saber onde algo está deve perguntar a ele, nunca concatenar strings.

---

## Fluxo de uma simulação, do começo ao fim

1. **Identificação** — digitação manual ou OCR da etiqueta (`scan/`). Coleta nome,
   nascimento, prontuário e sexo. Só isso: médico e equipamento pertencem à
   simulação e são pedidos depois.
2. **Captura** — `MainActivity` com CameraX. Categorias: Rosto, Etiqueta,
   Posicionamento, Acessórios, Impressos. Cada foto passa pelo detector de
   qualidade (escura/clara/borrada) e ganha marca d'água EXIF.
3. **Confirmar dados** — `FinalizarActivity`, fase 1. Médico, equipamento, sítio,
   risco de queda, precaução de contato, alergia, observações. Todos opcionais.
4. **Finalização** — copia as fotos para a pasta do paciente, grava Time-Out e
   observações, gera o PDF (e os `.dcm`, se ligado), registra no cadastro e no
   log de auditoria.
5. **Simulação finalizada** — fase 2. Visualizar PDF, Imprimir, Compartilhar,
   Editar simulação, Voltar ao Menu.

A separação em fases nasceu de um bug: os campos apareciam **depois** de
finalizar, travados, porque o setup inteiro estava dentro da função que só roda
no pós-finalização.

---

## Fluxo do tratamento

`TreatmentActivity` lista os pacientes. `PatientViewerActivity` mostra um
paciente, com um **carrossel único e contínuo**:

```
Resumo → Rosto → Etiqueta → Posicionamentos → Acessórios → Impressos
```

Rolar atravessa tudo; a miniatura ativa apenas muda de destaque. Arrastar antes
do primeiro item volta ao Resumo. Clicar numa miniatura salta para a primeira
foto daquele bloco.

O **Resumo** é a landing page: identificação, alertas em pílulas coloridas,
rosto em destaque, miniaturas empilhadas, dados do Time-Out e observações. Foi
desenhado para conferência à beira do acelerador — uma tela, sem navegação.

---

## Formatos gravados

### Pasta do paciente

```
PhotoID_RT/PHOTOS/JOAO DA SILVA - 12345/
├── JOAO_DA_SILVA_ROSTO_03-AGO-2026_14-22-10.jpg
├── JOAO_DA_SILVA_POSICIONAMENTO_1_...jpg
├── JOAO_DA_SILVA_FOLHA_SIMULACAO_...pdf
├── JOAO_DA_SILVA_FOLHA_SIMULACAO_NOVASIM1_...pdf
├── timeout_1.json          dados da simulação 1
├── obs_1.txt               observações da simulação 1
└── *.dcm                   DICOM, quando habilitado
```

Reirradiação usa a **mesma pasta**, com sufixo `NOVA SIMULACAO n` na pasta do
servidor e `_NOVASIMn` nos arquivos.

### Cadastro (`pacientes_cache.json`)

```json
{
  "__schema__": { "versao": 2, "atualizado_em": 1754234567890 },
  "JOAO DA SILVA | 12345": {
    "prontuario": "12345",
    "nascimento": "24/05/1964",
    "sexo": "M",
    "medico_assistente": "Dra. Ana",
    "equipamento": "Halcyon 2",
    "simulacoes": 2,
    "ultima_simulacao": 1754200000000
  }
}
```

A chave composta resolve homônimos. Registros antigos (só o nome) continuam
sendo lidos e migram para a forma composta assim que o prontuário aparece.
`__schema__` traz a versão; antes de qualquer migração, um backup `.vN.bak` é
gravado ao lado.

### PDF gerado

Página 1 é a **ficha de Time-Out**: cabeçalho com logo, etiqueta física (espaço
reservado) ou virtual, identificações, foto do rosto, tabela de frações,
equipamento, médico, sítio, tags de alerta e observações.

Páginas seguintes são o **grid de fotos**, com cabeçalho repetido e rodapé
`Clínica ● Página n`.

A geometria se adapta ao tamanho da etiqueta configurado: com etiqueta pequena as
identificações ficam ao lado dela; com etiqueta grande (a partir de ~80mm) vão
abaixo, porque não há sobra lateral.

### Pen-drive (OTG)

```
PHOTOID/
├── 1_PACIENTE_ATUAL/      ficha avulsa
├── 2_LOTE_INDIVIDUAIS/    um PDF por paciente
├── 3_LOTE_AGRUPADO/       todos num arquivo só
└── 9_ANTIGOS/             histórico, com carimbo de data/hora
```

Os prefixos numéricos existem porque o painel da impressora ordena
alfabeticamente — a pasta certa aparece primeiro, sem rolagem.

---

## Idiomas

Três arquivos `strings.xml` (PT, EN, ES) com **569 chaves cada**, paridade total.
(Eram 743 até a limpeza dos órfãos — 174 chaves restavam do subsistema de rede
e do onboarding antigo, sem nenhuma referência no código ou nos layouts.)
`DateUtils` tem tabelas de meses separadas por idioma, porque as abreviações
divergem: MAI/MAY, SET/SEP, DEZ/DIC.

O lint quebra o build com `MissingTranslation` e `ExtraTranslation`. Uma suíte de
teste (`StringsParidadeTest`) verifica paridade e placeholders lendo os XMLs
diretamente — roda em segundos, antes do build.

---

## Dependências principais

| Biblioteca | Uso |
|---|---|
| CameraX | captura de fotos |
| ML Kit (text, barcode, document scanner) | OCR da etiqueta, código de barras |
| SMBJ | leitura de pasta de rede (herança; sincronia hoje é externa) |
| Room + KSP | persistência da fila de envio |
| Glide | carregamento de imagens |
| OpenCSV | importação de base de pacientes |
| ZXing | leitura de QR na replicação de configuração |
| security-crypto | preferências criptografadas |

Sem biblioteca de PDF: geração e composição usam as APIs nativas do Android. A
decisão foi consciente — iText e PDFBox são AGPL, incompatível com distribuição
de um app clínico.
