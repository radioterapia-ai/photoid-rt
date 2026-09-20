# PhotoID RT — lote de setembro/2026

**Objetivo:** fechar duas versões e abrir o repositório. A v3.4 resolve interface,
bugs de campo e a unificação da etiqueta. A v4.0 entrega o motor de sincronização
nativo, que tira a dependência do FolderSync. Só depois das duas, o repositório
vira público.

**Onde este arquivo mora:** `.planning/` na raiz do projeto. Precisa entrar no
`.gitignore` no primeiro commit — planejamento interno não vai para repositório
público.

## Next Step

Fase 2.1 — ligar o *Success check* nos dois diálogos do OCR.

---

## Decisões já tomadas (rodadas de grilling, 19–20/09)

| # | Decisão | Motivo registrado |
|---|---|---|
| 1 | v3.4 (UI + bugs) antes, v4.0 (sync) emendada | Bugs travam o fluxo clínico hoje; o sync não trava nada porque o FolderSync funciona |
| 2 | SMB fica em `smbj`, com plano B medido | Já está no APK e funciona em campo nos 3 arquivos de rede. Troca para `jcifs-ng` só com evidência de falha NTLM no AD real |
| 3 | Nuvem por **SAF**, sem OAuth nosso | Zero Client ID, zero Client Secret, zero console. O usuário entra com a conta corporativa dele no app do provedor |
| 4 | Sincronização de uma via **nunca apaga nem renomeia no remoto** | O tablet alimenta o servidor, não manda nele. Bug, cartão com defeito ou toque errado em "Remover casos antigos" não pode apagar prontuário |
| 5 | Moldura da câmera só em simulação e tratamento | O scanner tem outro recorte; moldura 16:9 ali ensinaria errado |
| 6 | Mão da pinça desenhada em **vetor** | PNG de mão pesa no APK, não escala e não acompanha o tema |
| 7 | Só o **indicador** das abas desliza, o conteúdo não | `PreviewView` deslizado pisca e custa quadros na hora de mirar |
| 8 | Categoria "impressos" **não volta** | Os técnicos passaram a fotografar tudo em acessórios |
| 9 | Etiqueta unificada: opção física/virtual **sai**; tamanho **fica** | Se colarem etiqueta por cima, tem de ser do mesmo tamanho |
| 10 | Data da simulação **só no mini-log**, nunca dentro do box | O box pode ser coberto por papel; a data não pode sumir |
| 11 | Repositório público **depois** de tudo limpo, com tratamento de imagem | Decide o Dr. Henrique, substituindo a trava do orquestrador. Registrado |
| 12 | Sincronização é **opcional**, desligada por padrão | Com ela desligada o app roda exatamente como hoje. Quem já usa FolderSync não é forçado a migrar, e o motor novo não vira caminho obrigatório de dado de paciente |

---

## Fase 1 — v3.4: câmera e ensino do enquadramento
**Status:** complete

- [x] 1.1 `camera/MolduraRecorteView.kt` — View própria que desenha o recorte fiel
      (16:9, largura inteira do visor, cantos arredondados com o mesmo raio do PDF)
      e escurece o resto com transparência leve. Ler o raio real em `PdfBuilder`.
- [x] 1.2 Acoplar em `MainActivity` (simulação) e `AddPhotoInTreatmentActivity`
      (tratamento). **Não** em `ScanPacienteActivity`.
- [x] 1.3 `res/drawable/aviso_pinca.xml` — `AnimatedVectorDrawable` de 3 estados
      (dedos afastados → pinçando → juntos), 2 ciclos ≈3 s, depois fade out.
- [x] 1.4 Balão flutuante "Ajuste para encaixar": fundo azul translúcido da paleta,
      texto branco, com a animação ao lado.
- [x] 1.5 Mesmo balão na tela de recorte (`CropActivity`), sinalizando que dá para
      reajustar.
- [x] 1.6 String `cam_ajuste_encaixar` nos 12 idiomas.

## Fase 2 — v3.4: animações reconstruídas
**Status:** in_progress

`transitions.dev` é CSS/JS, web-only. Nada é portado — tudo é reconstruído em
primitivas Android.

- [ ] 2.1 **Success check** do OCR: `AnimatedVectorDrawable` com `trimPathStart`/
      `trimPathEnd` desenhando o traço do visto, mais fade, rotação leve e
      *Y-bob*. Substitui a troca de cor nos dois diálogos de conferência
      (campo e sexo) em `MainActivity`.
- [ ] 2.2 **Error state shake**: `SpringAnimation` em `translationX` com
      auto-revert, nos campos reprovados na validação do cadastro e da finalização.
- [ ] 2.3 **Tabs sliding**: indicador deslizante na barra de categorias da câmera.
      São 4 abas (`tabFace`, `tabLabel`, `tabPositioning`, `tabAccessories`), views
      próprias — não há `TabLayout` no projeto. Só o indicador anima.
- [ ] 2.4 **Accordion** nas listas da finalização (médico, equipamento, sítio).
- [ ] 2.5 **Toggle** em riscos, precauções e alergias, com as cores que já vão para
      o cabeçalho do PDF: amarelo `#FFE082/#FFC107` (queda), laranja
      `#FFB74D/#F57C00` (precaução), vermelho `#EF5350/#C62828` (alergia).
- [ ] 2.6 Respeitar `prefers-reduced-motion` do sistema (`Settings.Global
      .ANIMATOR_DURATION_SCALE == 0`): animação desligada no aparelho vale para nós.

## Fase 3 — v3.4: bugs de protocolo
**Status:** pending

- [ ] 3.1 **Miniatura girada.** Causa medida: `desenharMiniatura()` usa
      `BitmapFactory.decodeFile`, que ignora a orientação EXIF. O app já trata EXIF
      em `ImagemUtils`, `ExifWatermark`, `PdfBuilder` e `VisibleWatermark` — a
      miniatura ficou de fora. Passar a usar o caminho que já existe.
- [ ] 3.2 **Girar à mão.** Botão de rotação na miniatura (90° por toque), gravando
      a imagem já girada.
- [ ] 3.3 **Seleção de protocolo não aparece ao finalizar.** Causa NÃO medida —
      vou debugar antes de propor. Sintoma: salva, avança para a tela de imprimir,
      volta sozinho em menos de 1 s e só então mostra os protocolos.
- [ ] 3.4 **Editar simulação pelo histórico não oferece protocolo.** Igualar as
      variáveis da tela de edição às da tela de confirmação pós-fotos.

## Fase 4 — v3.4: três pontos no resumo
**Status:** pending

- [ ] 4.1 Renomear "Adicionar / Editar fotos desta simulação" para
      "Ajustar recorte, adicionar ou editar fotos desta simulação" — 12 idiomas.
- [ ] 4.2 **Foto de rosto não ajustável.** Causa NÃO medida — debugar primeiro.

## Fase 5 — v3.4: etiqueta unificada
**Status:** pending

- [ ] 5.1 Remover a escolha física/virtual das Configurações **e** do onboarding
      (o passo de PDF perde o checkbox).
- [ ] 5.2 Manter o tamanho da etiqueta configurável.
- [ ] 5.3 Box da etiqueta sempre com os dados do paciente dentro.
- [ ] 5.4 Mini-log em linha única **abaixo** do box, antes dos equipamentos, com
      nome, nascimento e prontuário duplicados **mais a data da simulação**.
- [ ] 5.5 A data da simulação aparece **só no log**, nunca dentro do box.
- [ ] 5.6 Log e box mantêm a mesma posição e conteúdo qualquer que seja o tamanho
      da etiqueta.
- [ ] 5.7 Limpar `pdfUsarEtiqueta` do código, das Configurações e do onboarding.

## Fase 6 — v3.4: SOBRE
**Status:** pending

- [ ] 6.1 `about_title`: "Radioterapia.AI" → "PhotoID RT" (12 idiomas).
- [ ] 6.2 `about_description`: tirar o FileSync como se fosse obrigatório; dizer
      que o usuário escolhe o app externo **ou** a sincronização interna.
- [ ] 6.3 Termos e política: nesta versão **só correção editorial**, sem novo
      aceite. A mudança material — o app passar a enviar dado de paciente por conta
      própria — chega com o motor, e é lá que a versão do aceite sobe.

## Fase 7 — v3.4: portão e entrega
**Status:** pending

- [ ] 7.1 `docs/scripts_validacao.py` verde.
- [ ] 7.2 `gradlew check` verde (55 testes, lint 0 erros).
- [ ] 7.3 Versão 3.4 / build 24, commit, empacotamento assinado.
- [ ] 7.4 Deploy local em `v3.4-build24\` e `latest\`.

---

## Fase 8 — v4.0: fundação do motor
**Status:** pending

- [ ] 8.1 Estender `primitivas_de_rede()` no ritual, **no mesmo commit** que cria
      os arquivos novos, com o motivo escrito. Autorizado.
- [ ] 8.2 Credenciais em `CredentialStore` (EncryptedSharedPreferences com
      MasterKey no Android Keystore) — já existe. Senha **nunca** sai no pacote de
      transferência.
- [ ] 8.3 Teste de conexão real contra o AD do hospital com `smbj`. Se der
      `STATUS_LOGON_FAILURE`, migrar para `jcifs-ng` **com a evidência anexada**.

## Fase 9 — v4.0: modelo e adaptadores
**Status:** pending

- [ ] 9.1 `sync/PerfilSync.kt` — N perfis independentes, renomeáveis.
- [ ] 9.2 Origem fixa e só de leitura: a pasta de armazenamento do app.
- [ ] 9.3 Adaptador **SMB**: protocolo (SMB2/3, legacy v1), host, porta 445
      editável, share isolado aceitando `$`, caminho remoto absoluto, usuário,
      senha, domínio NTLM isolado, checkbox de bypass de traverse.
- [ ] 9.4 Adaptador **WebDAV**.
- [ ] 9.5 Adaptador **FTP/SFTP**.
- [ ] 9.6 Adaptador **SAF** — qualquer nuvem já instalada no tablet (Drive,
      OneDrive, Dropbox), sem credencial nossa.

## Fase 10 — v4.0: o motor
**Status:** pending

- [ ] 10.1 **Injeção direta**: escrever no caminho absoluto final sem listar a raiz
      nem os diretórios intermediários. Em hospital é comum não haver leitura nas
      pastas superiores.
- [ ] 10.2 Montagem do pacote NTLM juntando o domínio do campo próprio à
      matrícula — o usuário não formata nada.
- [ ] 10.3 **Uma via, sem exclusão**: nunca apaga nem renomeia no remoto.
- [ ] 10.4 Índice local do que já subiu, para não reenviar o acervo inteiro.
- [ ] 10.5 `WorkManager`: periódico configurável **e** gatilhos — ao salvar foto,
      ao finalizar simulação, ao abrir o app.
- [ ] 10.6 Retentativa com recuo, e fila que sobrevive a reinício do tablet.

## Fase 11 — v4.0: a tela
**Status:** pending

- [ ] 11.1 Aba "Sincronização de Prontuários" nas Configurações.
- [ ] 11.2 Cartão por perfil: origem fixa à esquerda, destino dinâmico à direita.
- [ ] 11.3 Tooltips `(?)` com os textos especificados — host/IP, formatos de
      usuário e domínio, share name, bypass — nos 12 idiomas.
- [ ] 11.4 **Botão de teste de conexão** nos moldes do da impressora, com log
      end-to-end hiperdetalhado e copiável em caso de falha.
- [ ] 11.5 Estado por perfil: última sincronização, pendentes, último erro.
- [ ] 11.6 **Interruptor mestre, desligado por padrão.** Com a sincronização
      desativada o app se comporta exatamente como hoje: nenhum serviço em
      segundo plano, nenhuma tentativa de rede, nenhum dado saindo por conta
      própria. Quem já usa FolderSync não é obrigado a migrar.

## Fase 12 — v4.0: termos e entrega
**Status:** pending

- [ ] 12.1 Termos e política: versão nova, **novo aceite**, descrevendo a
      sincronização própria e o que sai do aparelho.
- [ ] 12.2 Portão completo, versão 4.0, empacotamento, deploy local.

---

## Fase 13 — abertura do repositório
**Status:** pending

- [ ] 13.1 Varredura final do histórico.
- [ ] 13.2 `.planning/` no `.gitignore`.
- [ ] 13.3 Tratamento de imagem profissional: capturas de tela, identidade e
      sincronia de marca — **consultar o chat designer** antes.
- [ ] 13.4 README com as imagens.
- [ ] 13.5 Repositório privado → público.
- [ ] 13.6 Release final; URL fixa
      `releases/latest/download/PhotoID_RT_LATEST.apk` entregue para o site.

---

## Decisions Made

Ver tabela no topo.

## Errors Encountered

| Erro | Tentativa | Resolução |
|---|---|---|
| `cat >` sobre `values/ids.xml` apagou `baseContentContainer`, quebrando `BaseActivity` em 3 pontos | 1 | Restaurado do git, os dois ids no mesmo arquivo. **Segunda vez nesta empreitada** que sobrescrevo arquivo sem ler antes (a primeira foi o `README.md`). Regra: `cat >` só em caminho que eu acabei de confirmar que não existe |
| `mergeDebugResources` falhou com "Unable to delete directory / process has files open" | 1 | Causa real é o GoogleDriveFS marcando `app/build` como somente-leitura **durante** o build, não processo travado. `tools/Destravar-Build.ps1` e recompilar |
