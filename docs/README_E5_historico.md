# Radioterapia.AI — Entrega E5 (Polimento Final)

🎉 **Última de 5 entregas. App completo.**

Após validar esta entrega, você pode subir o projeto definitivamente para o Android Studio Panda 4 (ou Koala/Ladybug) e gerar o APK final para distribuição às clínicas.

## Acumulado E1 → E5 (App completo)

### Da E1 (mantido)
- Splash, Home com 2 botões (Simulação/Tratamento), seletor de idiomas (PT/EN/ES), engrenagem com bolinha vermelha
- Configurações em accordion 16 grupos, tela Sobre

### Da E2 (mantido)
- Câmera com 4 categorias (rosto/etiqueta/posicionamento/acessórios)
- Pinch-to-zoom + slider, flash 3 modos, grid de enquadramento
- Marca d'água visível só em posicionamento, EXIF invisível em todas
- 2 PDFs: Folha de Posicionamento (paginação adaptativa 1×4 → 2×4) + Ficha de Acessórios
- Envio multi-destino (1 primário + 4 backups)
- Impressão JetDirect → IPP fallback

### Da E3 (mantido)
- Sincronização CSV via SMB com auto-detect de separador
- Mapeamento de colunas configurável (nome/nasc/prontuário + 3 IDs extras)
- Cache local Room SQLite com busca exata + difusa
- Validação cruzada CSV ↔ histórico local
- Edição de paciente com aviso e log de auditoria

### Da E4 (mantido)
- Módulo Tratamento (Paperless) acessível pela Home
- Visualização split (rosto+etiqueta esquerda, carrossel direita)
- Múltiplas simulações por paciente no dropdown
- Adicionar foto durante tratamento → re-gera PDF → oferece imprimir

### Novo na E5 — Polimento e completude

✅ **Wizard de primeira execução** (7 passos)
- Disparado automaticamente na primeira vez que o app é aberto
- Splash → Wizard ou Home (conforme `wizard_done` no SharedPreferences)
- Passos:
  1. **Boas-vindas** com lista do que será configurado
  2. **Idioma** (PT/EN/ES) com radio buttons
  3. **Identidade da clínica** (nome + logo)
  4. **Servidor SMB** (host, share, usuário, senha)
  5. **CSV** (pasta + se tem cabeçalho)
  6. **Mapeamento de colunas** (números 1-indexados)
  7. **Resumo** + botão Concluir
- Botões "Anterior" e "Próximo" em todas as telas
- Botão "Pular tudo" no canto inferior esquerdo (com confirmação)
- Cada passo é opcional — pode ser configurado depois nas Configurações
- Ao concluir, salva tudo de uma vez no `AppConfig`, `CredentialStore`, `LogoManager`, `CsvMapping` e `LocaleManager`

✅ **Replicação de configuração** (QR + JSON)
- Aberta a partir de Configurações → Replicar configuração
- **Exportar QR**: gera QR de 600×600 com toda a configuração serializada (sem senha SMB)
- **Importar QR**: scanner abre, lê QR de outro tablet, importa
- **Exportar JSON**: salva em `Documents/Radioterapia.AI/Config/config_radioterapia_*.json`
- **Importar JSON**: abre seletor de arquivo (SAF), lê e importa
- Aviso destacado em todas as opções: "senha SMB não é incluída por segurança"
- Após importar, dialog informa que precisa preencher senha manualmente
- Versionado (`v: 1`) para evolução futura sem quebrar tablets antigos

✅ **Pendências de envio offline com botão funcional**
- Quando primário SMB falha em uma simulação finalizada, os arquivos são copiados pra `filesDir/pending_files/<id>/`
- `PendingUploadManager` (JSON em `pending_uploads.json`) gerencia a lista
- **Bolinha vermelha 🔴 na engrenagem** acende quando há pendências (já era infraestrutura na E1, agora funcional)
- Tela `PendingUploadActivity` (acessível em Configurações → Status de Conexão):
  - Lista cada pendência com nome, tentativas e último erro
  - Botões "Reenviar" (individual) e "Remover da fila"
  - Botão "Reenviar todos" no rodapé com confirmação
  - Ao reenviar: tenta cada destino ativo; sucesso → remove da fila; falha → atualiza tentativas

✅ **Tela de Logs / Auditoria**
- Acessível em Configurações → Log de Auditoria
- Lê o `audit_log.jsonl` da E3
- **Filtro por tipo** via Spinner: Todos / Edições / Sync CSV / Divergências / Uploads / Impressões / Finalizações / Erros
- **Cores por tipo de evento** no badge:
  - Roxo = EDIT
  - Azul = SYNC_CSV / PRINT
  - Laranja = DIVERGENCE
  - Verde = UPLOAD / FINISH
  - Vermelho = ERROR
- Cada item: timestamp, tipo, mensagem, detalhes em fonte monoespaçada
- **Exportar TXT** (Documents/Radioterapia.AI/Logs/auditoria_*.txt)
- **Limpar logs antigos** (90 dias) — confirma antes

✅ **Dashboard de estatísticas**
- Acessível em Configurações → Estatísticas
- Cards numéricos:
  - Total de pacientes (cache local)
  - Reirradiações detectadas (≥ 2 simulações)
  - Últimos 7 dias / Últimos 30 dias (do log)
  - Uploads OK / Impressões / Erros
- **Exportar TXT** completo com lista detalhada de todos os pacientes (Documents/Radioterapia.AI/Stats/)

✅ **Bolinha vermelha 🔴 na engrenagem agora funcional**
- `HomeActivity.atualizarBadgeEngrenagem()` consulta `PendingUploadManager.contar()`
- Visível só quando há pendências reais
- Atualiza no `onResume` (volta da tela de pendências, do envio etc)

## Como instalar e testar

### Instalação
1. Extrair `RadioterapiaAi_E5.zip`
2. Android Studio → File → Open → selecionar pasta `E5/`
3. Sync Gradle (vai trazer ZXing core via dep transitiva, viewpager2, Room, etc)
4. Run no tablet

### Testes da E5

#### Teste 1 — Wizard de primeira execução
- [ ] **Apague os dados do app** (Configurações Android → Apps → Radioterapia.AI → Storage → Clear data) ou desinstale e reinstale
- [ ] Abre o app → Splash → **Wizard aparece**
- [ ] Passo 1: Boas-vindas com lista do que vai ser configurado
- [ ] Toque "Próximo" → Passo 2: Idioma → escolha "🇺🇸 English" → Próximo
- [ ] Passo 3: Nome da clínica + logo → preencha "Minha Clínica" + escolha logo PNG → Próximo
- [ ] Passo 4: SMB → preencha host/share/usuario/senha → Próximo
- [ ] Passo 5: CSV → preencha pasta UNC + escolha "Sim" para cabeçalho → Próximo
- [ ] Passo 6: Mapeamento → digite 2, 3, 1 (exemplo) → Próximo
- [ ] Passo 7: Resumo aparece com tudo configurado → "Concluir"
- [ ] **Cai na HomeActivity já no idioma escolhido**
- [ ] Configurações → veja que tudo foi populado

#### Teste 2 — Wizard reabrir e editar
- [ ] No SharedPreferences, manualmente desligue `wizard.done` (ou apague dados outra vez)
- [ ] Abra o app → Wizard volta com **valores pré-preenchidos** dos campos antes salvos

#### Teste 3 — Pular wizard
- [ ] Apague dados do app, abra de novo, no Passo 1 toque "Pular tudo" no canto
- [ ] Confirmação aparece → confirma → vai pra HomeActivity
- [ ] Configurações → todos os campos vazios

#### Teste 4 — Botão Anterior
- [ ] No wizard, navegue até passo 4, toque "Anterior" 2x
- [ ] Volta ao passo 2 com idioma ainda escolhido (estado preservado)

#### Teste 5 — Bolinha vermelha por pendência
- [ ] Configure SMB com IP errado de propósito
- [ ] Faça uma simulação completa e finalize → envio falha
- [ ] Volte para Home → **bolinha vermelha aparece na engrenagem**
- [ ] Configure SMB correto → vá em Configurações → Status de Conexão → "Envios pendentes"
- [ ] Tela de pendências mostra a simulação com erro
- [ ] Toque "Reenviar" → progresso → sucesso → item desaparece
- [ ] Volte → bolinha vermelha sumiu

#### Teste 6 — Reenviar todos
- [ ] Provoque 3 falhas (3 simulações com SMB errado)
- [ ] Bolinha vermelha
- [ ] Configurações → "Envios pendentes" → 3 itens
- [ ] Configure SMB correto e volte → "Reenviar todos"
- [ ] Confirma → progresso "1/3, 2/3, 3/3" → toast "✓ 3 reenviado(s)"
- [ ] Lista zerada

#### Teste 7 — Tela de Logs com filtros
- [ ] Configurações → Log de Auditoria → Tela LogsActivity
- [ ] Lista populada com vários tipos (sync CSV, uploads, finalizações, etc)
- [ ] Spinner → "Erros" → filtra apenas ERROR
- [ ] Spinner → "Sync CSV" → mostra apenas eventos do CSV
- [ ] Spinner → "Todos" → volta tudo
- [ ] Cada item mostra timestamp, tipo colorido, mensagem, detalhes em monoespaçado

#### Teste 8 — Exportar logs
- [ ] LogsActivity → "Exportar logs"
- [ ] Toast: "Exportado: auditoria_*.txt"
- [ ] Abrir gerenciador de arquivos do tablet → `Documents/Radioterapia.AI/Logs/`
- [ ] Arquivo .txt está lá com conteúdo legível

#### Teste 9 — Dashboard de estatísticas
- [ ] Configurações → Estatísticas → StatsActivity
- [ ] Cards numéricos calculados em tempo real:
  - Total de pacientes
  - Reirradiações
  - Últimos 7/30 dias
  - Uploads OK / Impressões / Erros
- [ ] Texto detalhado abaixo
- [ ] "Exportar PDF" → vira TXT em `Documents/Radioterapia.AI/Stats/`

#### Teste 10 — Replicação via QR
- [ ] Configure tudo no tablet A
- [ ] Configurações → Replicar configuração → ReplicateActivity
- [ ] Aviso visível: "Senha SMB não é incluída"
- [ ] "Exportar QR" → QR aparece grande na tela
- [ ] No tablet B (com app instalado), abra ReplicateActivity → "Importar QR"
- [ ] Câmera abre, aponte para o QR do tablet A
- [ ] Confirmação "Sobrescrever todas as configurações?"
- [ ] Confirma → mensagem de sucesso → tablet B agora tem nome de clínica, idioma, host SMB, mapeamento etc
- [ ] **Senha SMB do tablet B continua vazia** (precisa preencher manualmente)

#### Teste 11 — Replicação via JSON
- [ ] Configurações → Replicar configuração → "Exportar JSON"
- [ ] Toast com nome do arquivo
- [ ] `Documents/Radioterapia.AI/Config/config_radioterapia_*.json` existe
- [ ] Compartilhe via Drive/USB para outro tablet
- [ ] No outro tablet: ReplicateActivity → "Importar JSON" → seletor de arquivo → escolha o .json
- [ ] Importa com sucesso, mensagem aparece
- [ ] Configurações no novo tablet refletem o que veio do JSON

#### Teste 12 — Wizard só dispara uma vez
- [ ] Após concluir o wizard pela primeira vez, feche e reabra o app várias vezes
- [ ] Sempre vai direto pro HomeActivity após o splash
- [ ] Apenas se apagar dados do app, o wizard volta a aparecer

## Estrutura completa do projeto E5

```
app/src/main/
├── AndroidManifest.xml
├── java/com/radioterapia/ai/
│   ├── AppConfig.kt
│   ├── DestinoSmb.kt
│   ├── HomeActivity.kt
│   ├── MainActivity.kt
│   ├── SimulationHomeActivity.kt
│   ├── SplashActivity.kt
│   ├── audit/AuditLogger.kt
│   ├── branding/LogoManager.kt
│   ├── csv/
│   │   ├── CsvImporter.kt
│   │   ├── CsvMapping.kt
│   │   ├── CsvSyncManager.kt
│   │   └── PatientLookup.kt
│   ├── data/
│   │   ├── AppDatabase.kt
│   │   ├── PatientDao.kt
│   │   └── PatientEntity.kt
│   ├── exif/ExifWatermark.kt
│   ├── i18n/LocaleManager.kt
│   ├── patient/
│   │   ├── PatientCache.kt
│   │   └── SuspectNameValidator.kt
│   ├── pdf/PdfBuilder.kt
│   ├── pending/                            ← NOVO E5
│   │   ├── PendingUploadActivity.kt
│   │   ├── PendingUploadManager.kt
│   │   └── PendingAdapter.kt
│   ├── print/PrinterClient.kt
│   ├── quality/PhotoQualityDetector.kt
│   ├── replicate/                          ← NOVO E5
│   │   ├── ConfigSerializer.kt
│   │   └── ReplicateActivity.kt
│   ├── scan/
│   │   ├── EtiquetaParser.kt
│   │   └── ScanPacienteActivity.kt
│   ├── security/CredentialStore.kt
│   ├── session/SessionManager.kt
│   ├── smb/SmbClient.kt
│   ├── stats/                              ← NOVO E5
│   │   └── StatsActivity.kt
│   ├── treatment/
│   │   ├── AddPhotoInTreatmentActivity.kt
│   │   ├── CarrosselAdapter.kt
│   │   ├── PatientViewerActivity.kt
│   │   ├── TreatmentActivity.kt
│   │   └── TreatmentPhotoFetcher.kt
│   ├── ui/
│   │   ├── AboutActivity.kt
│   │   ├── EditarPacienteActivity.kt
│   │   ├── FinalizarActivity.kt
│   │   ├── GridOverlay.kt
│   │   ├── HistoricoActivity.kt
│   │   ├── HistoricoAdapter.kt
│   │   ├── LogsActivity.kt                 ← NOVO E5
│   │   ├── LogsAdapter.kt                  ← NOVO E5
│   │   ├── SettingsActivity.kt
│   │   ├── ThumbAdapter.kt
│   │   └── settings/                       (vários grupos)
│   ├── watermark/VisibleWatermark.kt
│   └── wizard/                             ← NOVO E5
│       └── WizardActivity.kt
└── res/
    ├── drawable/         (ícones, logo, backgrounds)
    ├── layout/           (todas as activities + 7 wizard_step_*.xml + ...)
    ├── layout-land/      (versões paisagem específicas)
    ├── values/           (strings PT, cores, styles)
    ├── values-en/
    └── values-es/
```

## Comportamento do app completo (visão geral)

```
Primeira abertura:
  Splash → Wizard (7 passos) → Home

Aberturas seguintes:
  Splash → Home (com auto-sync silencioso do CSV)

Home:
  ├── Botão SIMULAÇÃO → SimulationHome → MainActivity (captura) → FinalizarActivity (PDFs+envio) → Home
  ├── Botão TRATAMENTO → TreatmentActivity → PatientViewer → AddPhotoInTreatment (opcional)
  └── Engrenagem ⚙ (com 🔴 se pendências) → SettingsActivity (16 grupos)
       ├── Status conexão (com botão "Pendências")
       ├── Idiomas
       ├── Identidade da clínica (logo + nome)
       ├── Pasta local
       ├── SMB (rede, credenciais)
       ├── Destinos de backup (5)
       ├── CSV (pasta + mapeamento + sync)
       ├── Impressora (IP + teste)
       ├── PDF (envia ao servidor sim/não)
       ├── Câmera (grid + outros)
       ├── Cache & dados (rascunho, histórico)
       ├── Estatísticas → StatsActivity
       ├── Log de Auditoria → LogsActivity
       ├── Replicar configuração → ReplicateActivity (QR + JSON)
       ├── Restaurar padrões
       └── Sobre
```

## Próximos passos sugeridos

1. **Compilar no Android Studio** definitivamente, gerar APK
2. Testar exaustivamente em hardware real (Galaxy Tab S6)
3. Empacotar APK assinado para distribuição
4. Treinar o pessoal das clínicas com base nos READMEs (E1 a E5 cobrem todos os fluxos)

## Limitações do app que ficaram para evolução futura (fora do escopo das 5 entregas)

- Importação manual de CSV via SAF (botão na engrenagem ainda é stub)
- Câmera contínua tentando detectar QR/etiqueta automaticamente no módulo Tratamento (atualmente exige tap em botão)
- Sincronização bidirecional com servidor (hoje é só leitura para CSV e gravação para fotos)
- Telemetria opcional para a clínica (taxa de sucesso por equipamento, etc)
- Notificações push quando aparecer paciente novo no CSV

## Ao reportar
1. **Wizard funcionou na primeira execução?** Os 7 passos navegam corretamente?
2. **Bolinha vermelha** acende quando há pendência e some quando reenvia?
3. **Replicação via QR** entre dois tablets transferiu tudo (menos senha)?
4. **Logs** filtra por tipo? Cores diferentes por categoria? Exporta TXT?
5. **Estatísticas** calculam números corretos com base no histórico real?
6. **App como um todo** está estável? Roda fluxos completos (simulação → tratamento → adicionar foto → reimprimir) sem crash?

🚀 **Boa sorte na produção!**
