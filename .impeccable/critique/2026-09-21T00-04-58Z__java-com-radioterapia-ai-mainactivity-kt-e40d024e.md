---
target: MainActivity
total_score: 23
max_score: 40
na_heuristics: 
p0_count: 1
p1_count: 3
target_identity: "file:C:\\AI_PROJETOS\\PHOTOID_RT\\app\\src\\main\\java\\com\\radioterapia\\ai\\MainActivity.kt"
target_fingerprint: "sha256:29a62f0f46a2397d192a9bbf4993b89a5c2662ed9d8cd43ee901be7bd21242b8"
target_path: "C:\\AI_PROJETOS\\PHOTOID_RT\\app\\src\\main\\java\\com\\radioterapia\\ai\\MainActivity.kt"
timestamp: 2026-09-21T00-04-58Z
slug: java-com-radioterapia-ai-mainactivity-kt-e40d024e
---
# Crítica de Design — MainActivity (tela de captura da simulação)

**Method: dual-agent** (A: revisão de design · B: evidência determinística), isolados e em paralelo.

⚠️ **Desvio de ordem declarado:** a referência pede que A conclua antes de as evidências determinísticas entrarem no contexto de síntese. B terminou primeiro, então B foi lido antes de A. A permaneceu isolado — formou o julgamento sem ver nada de B —, de modo que o veredito de especificidade continua não-ancorado. O risco residual é de ponderação na síntese, declarado em vez de escondido.

**Crítica estática:** não há aparelho conectado, imagem de sistema instalada nem AVD (`adb devices` vazio, `system-images` vazio). Nenhum achado abaixo depende de captura de tela; todos são deriváveis do código.

## Design Health Score

| # | Heurística | Nota | Questão-chave |
|---|---|---|---|
| 1 | Visibilidade do estado do sistema | 3 | Troca automática de categoria só anunciada por Toast efêmero (MainActivity.kt:916) |
| 2 | Correspondência com o mundo real | 3 | "Voltar" abre Revisar identificação (:167); "Salvar" num impresso significa Voltar (:2005) |
| 3 | Controle e liberdade | 2 | "Voltar" no 3º diálogo do OCR reinicia a identificação inteira (:1487), sem aviso |
| 4 | Consistência e padrões | 2 | Três diálogos irmãos, três superfícies; FieldLabel usado num e refeito à mão no outro; alvos de 44/46/56/60/68dp na mesma jornada |
| 5 | Prevenção de erro | 2 | Foto da etiqueta entra na sessão antes de confirmação humana (:1185, :1260) |
| 6 | Reconhecer em vez de lembrar | 2 | Prontuário desaparece após a identificação (activity_main.xml:27-39) |
| 7 | Flexibilidade e eficiência | 2 | Código de barras inalcançável desta tela; caminho "rápido" custa ~11 toques contra 2 do digitado |
| 8 | Estética e minimalismo | 2 | 13 alvos simultâneos em modo câmera; DESIGN.md pede densidade média-baixa |
| 9 | Recuperação de erro | 3 | Arquivadas recuperáveis, _ORIGINAL para reenquadrar n vezes, rascunho mantido, fallbacks em cadeia |
| 10 | Ajuda e documentação | 2 | "(?)" nomeado no DESIGN.md não existe aqui; dica do OCR a 10sp |
| **Total** | | **23/40** | **Abaixo da média — funcional, com defeitos de consequência clínica** |

Nenhuma heurística `n/a`. Nota de A; a evidência de B não a moveu — endureceu 4, 5 e 8, e não contradisse nenhuma.

## Veredito de Especificidade

**A (não-ancorado):** metade autoral, metade Android de prateleira, e a divisão é geográfica — acima da linha do visor é deste produto, abaixo dela não é de ninguém.

Autoral: as cinco abas são as seções da ficha impressa; a MolduraRecorteView existe porque o grid do PDF precisa de proporção uniforme; o obturador de 78dp está parado no polegar com visor 16:9.

Genérico: abas em #263238 com texto #B0BEC5 (Blue Grey 800/200 do Material), fora da escada tonal do DESIGN.md; três AlertDialog de fábrica; dialog_novo_paciente.xml não usa @style/FieldLabel nem @style/InputField, que existem e são usados em activity_finalizar.xml. O PhotoIdHeaderView — único locus de voz tipográfica segundo o DESIGN.md — não aparece nesta tela.

**B (determinístico):** o detector embutido rodou (detect --json, exit 0, array vazio). Causa mecânica identificada: live-browser.js só reconhece .html, .css, .svelte e .astro. Exit 0 significa "nada elegível", não "nada errado". Doze checks nativos substituíram a cobertura:

- Alvo de toque < 48dp: 11 no celular, 5 no tablet
- Hex cravado no layout: 19
- Dimensão sem par celular/tablet: 25 textSize + 39 layout_*
- Contraste < 4,5:1: 10 pares (1 falso positivo declarado)
- textSize em dp: 0 · tooltipText: 0 · dimen órfã: 0 · NewApi sem guarda: 0
- Cor de alerta fora do alerta: 1

**Sobreposições visuais:** nenhuma. Não há navegador aplicável a Android nativo nem aparelho. Sinal de recurso: crítica estática, sem evidência renderizada.

## Impressão Geral

Tela bem pensada e mal vestida. Os defeitos graves não estão na aparência: estão em três lugares onde o app promete uma proteção e não a entrega. A maior oportunidade não é redesenhar — é fechar a distância entre o que os documentos afirmam e o que o código faz, porque em três casos a diferença é de dado de paciente.

## O Que Está Funcionando

1. **O ciclo de reenquadramento não destrói nada.** A foto salva reabre editável, partindo do _ORIGINAL guardado antes do primeiro corte; dá para abrir o enquadramento, não só fechá-lo. Desenhado a partir da queixa real.
2. **A degradação nunca deixa o técnico sem saída.** Scanner falha -> câmera comum; sem galeria -> gerenciador de arquivos; recorte indisponível -> "melhor importada torta que perdida"; sessão vazia -> reconstrói da pasta. Cada fallback tem o caso que o gerou comentado.
3. **As travas mecânicas estão de pé.** Zero textSize em dp, zero tooltipText, zero dimen órfã, zero NewApi sem guarda, contentDescription em 9 de 9. O footgun nº 11 do CLAUDE.md está corrigido com catch (_: Throwable), que cobre NoSuchMethodError.

## Problemas Prioritários

### [P0] A etiqueta entra na sessão antes de existir paciente confirmado

processarOcr (:1183-1186) e processarCodigoBarras (:1258-1261) gravam a etiqueta em Category.LABEL imediatamente. Nos três caminhos de abandono — "Voltar" (:1487), "Cancelar e verificar" (:1374), "Não é o mesmo paciente" (:1695) — o código limpa nome, prontuário e nascimento e não toca nas fotos. SessionManager.limparSessao() existe (:262) e não é chamada em nenhum.

Consequência: a etiqueta do paciente A fica na pasta do paciente B, vai para o PDF e sincroniza para o servidor da instituição. temRascunho() devolve true com _fotos não vazio, então o app oferece "continuar rascunho" com a etiqueta órfã.

Correção: segurar em etiquetaPendente: File? e só chamar adicionarFoto(..., Category.LABEL) dentro de finalizarIdentificacao(); apagar o pendente em qualquer abandono. Custo: zero cliques.
Comando: /impeccable harden MainActivity

### [P1] O alerta de prontuário já usado não existe, e o CLAUDE.md afirma que existe

CLAUDE.md: "prontuário já usado por outro paciente gera alerta nomeando quem o usa". Busca ampla em app/src/main/java/ e em strings.xml volta vazia. obterContagemSimulacoes(nome, prontuario) é chaveada por nome E prontuário, então nome diferente com o mesmo prontuário devolve 0 e passa em silêncio.

É a proteção contra o bug de homônimos que a JORNADA.md descreve com efeito clínico sério. A chave composta resolveu o lado do cadastro; o lado da entrada ficou sem aviso.

Correção: decidir primeiro qual dos dois envelheceu. O documento descreve uma proteção, então vale confirmar se ela foi perdida numa refatoração ou nunca existiu.
Comando: /impeccable harden MainActivity (após decisão)

### [P1] O caminho mais rápido de identificação não é oferecido, e o oferecido custa mais que digitar

abrirDialogIdentificarPaciente (:1040-1062) tem dois botões: Digitar e OCR. modoScanAtual nasce MODO_OCR (:134) e só é reatribuído a MODO_OCR (:1055). MODO_BARRAS existe, ScanPacienteActivity o implementa, e é alcançável só pelo HistoricoActivity. processarCodigoBarras é código morto nesta tela.

O código de barras é o único caminho determinístico. Sem ele, o scan no caso universal custa ~11 toques; digitar custa 2. O caminho "rápido" é o caro — inverte o princípio nº 1 do produto.

Correção: terceiro botão no seletor com modoScanAtual = MODO_BARRAS (o destino já existe inteiro). E colapsar os três diálogos seriais num só: etiqueta grande no topo, três campos empilhados, um visto por campo preservado, um Confirmar.
Comando: /impeccable distill MainActivity

### [P1] A primeira decisão da simulação usa a cor que o próprio DESIGN.md proíbe para esse papel

dialog_modo_paciente.xml:25 e :34 usam backgroundTint="@color/brand_primary" (#42A5F5) com texto branco. Contraste medido por A e por B independentemente: 2,6:1. Reprova o AA (4,5:1) e o limiar de texto grande (3:1). Com brand_primary_dark seria 5,4:1. Altura 60dp, não os 68dp de @style/ButtonPrimary.

O DESIGN.md diz sobre o azul escuro: "texto branco por cima precisa de contraste, e o azul claro não o dá"; e sobre o #42A5F5: "é cor de identidade, não de superfície". Sala escurecida, luva, paciente na mesa — e estes são os dois únicos alvos corretamente dimensionados da tela, com a cor errada.

Correção: style="@style/ButtonPrimary" nos dois. Resolve cor e altura juntas e remove dois números fixos do layout. Não precisa de tela: a aritmética sai dos hexadecimais.
Comando: /impeccable polish dialog_modo_paciente.xml

### [P2] Laranja e amarelo como estado de sistema, em território reservado ao risco clínico

Badge de contagem em #FF9800 (category_tab.xml:45, confirmado); flash, som e câmera frontal em #FFD54F (:616, :980, :985, :1002, :1005); asterisco de obrigatório em #E53935, cinco vezes, sendo action_discard #C62828. O badge acumula três achados independentes de B: hex cravado, contraste 2,2:1, laranja fora do alerta.

A Regra do Alerta Clínico existe porque as pílulas espelham a ficha de Time-Out impressa. Um técnico que aprendeu no papel que amarelo é risco de queda vê três ícones amarelos acesos sem significado clínico.

Nota de honestidade: a JORNADA.md registra o badge laranja como melhoria deliberada. A decisão documentada foi o badge, não a cor — a JORNADA resolveu a ausência de contador, e o DESIGN.md (posterior) resolveu o significado do laranja. Decisões diferentes; a segunda é a mais recente. Mas é chamado do Henrique.
Comando: /impeccable colorize MainActivity

## Sinais Vermelhos por Persona

**Técnico apressado, de luva, sala escurecida** (persona primária do PRODUCT.md): primeira decisão com contraste 2,6:1 e alvo de 60dp. Onze alvos abaixo de 48dp no celular, cinco em qualquer aparelho — flash, mudo e trocar câmera a 44dp. "Finalizar simulação" a 56dp no tablet, contra os 68dp que o DESIGN.md exige pela luva. Rótulo ZOOM com scrim de 20%: com lençol branco no enquadramento cai para 1,6:1.

**Técnico de primeira semana:** a categoria muda sozinha após salvar, avisada por Toast de ~2s, numa tela onde ele olha o paciente. O prontuário some da barra superior e o único caminho de reconferência é um botão rotulado "Voltar" que não volta. Errar o nome no terceiro diálogo do OCR perde as três confirmações sem aviso.

**Serviço em japonês ou árabe:** o portão de finalizar mostra duas linhas traduzidas e uma terceira em português. contentDescription="Etiqueta capturada" e "Foto" ficam em português em qualquer idioma. photos_count é "%1$d foto(s)" — "(s)" não é traduzível para árabe, polonês ou japonês, e a mesma marca está em draft_restored, ok_photo_added, draft_kept_count, gallery_added e resim_existing.

## Observações Menores

- **Portão de finalizar pergunta três vezes, e a terceira não traduz.** Confirmado: strings.xml:171-172 já terminam com "Prosseguir mesmo assim?", e :2213 concatena uma terceira, cravada. Conserto: apagar o sufixo de uma linha. Mesma família: :1395 "Nome completo" (R.string.full_name existe), :1707 "$nome • Nova sim. ${num-1}" (visível em toda reirradiação), :1940 "⚠ Pode estar borrada" (escura e clara usam getString, só a borrada não).
- **Texto de conferência a 10sp** (dialog_confirmar_campo.xml:51 e :68): menor corpo do produto, na evidência contra a qual o técnico confere a sugestão.
- **Hierarquia invertida na barra superior:** nome do paciente a 14sp, categoria a 20sp.
- **Três atributos de API 26 ignorados em silêncio na 24-25:** autoSizeTextType em três botões e na aba; paddingHorizontal em dois layouts. Os dois últimos contradizem a regra escrita pelo próprio projeto em styles.xml:117 e :133. Em API 24-25 o texto não reduz e trunca em de, pl, fr.
- **txtWifiStatus é trabalho para ninguém:** declarada gone e 0dp, mas atualizarStatusUI() chama WifiManager.connectionInfo (obsoleta desde a API 29) a cada onResume e a cada foto salva.
- **Duas exceções à doutrina plana** em 59 layouts: aviso_encaixar.xml:19 (6dp) e activity_settings.xml:27 (4dp). Zero CardView no projeto.
- **Precisão dos assessments, aferida:** B acertou category_tab.xml:45 (A disse :52); A acertou dialog_modo_paciente.xml:25,34 (B disse :24,33). Conferir a linha antes de editar.

## Perguntas a Considerar

1. Se a etiqueta já foi fotografada, por que o app ainda faz quatro perguntas em quatro telas?
2. Por que a categoria avança sozinha e avisa com Toast, se o comentário em :887 diz que ninguém está olhando a tela?
3. O que aconteceria se a barra superior mostrasse nome + prontuário o tempo todo?
4. Se a página 1 da ficha é o Time-Out, por que o portão de finalizar valida objetos e não identidade?
5. O laranja e o amarelo valem mais marcando "há 3 fotos aqui", ou marcando precaução de contato?
