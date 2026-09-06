# Armadilhas e ritual de validação

Cada item aqui custou pelo menos um build quebrado. A lista existe para que não
custe um segundo.

---

## Erros de edição de código

### 1. Recorte por índice engole vizinhos

O mais caro do projeto — aconteceu **três vezes**.

Ao remover ou mover um bloco delimitando por duas âncoras e cortando o que está
entre elas, tudo o que morava no meio vai junto. As chaves continuam balanceadas
(a função removida era completa), então nenhuma verificação superficial acusa.

- Remoção de `pedirMedicoEConcluir` levou junto `abrirDialogEditarIdentificacao`.
- Remoção de duas funções órfãs levou junto `configurarSelecao`, usada em três
  lugares.
- Movimentação da impressão para a `BaseActivity` levou junto **24 campos** do
  `PatientViewerActivity` — 120 erros de compilação em cascata.

**Como evitar:** delimitar por âncoras textuais literais, contar quantas
definições de topo existem no trecho antes de cortar, e rodar os diffs de função
e de campo depois.

### 2. Extrair corpo de lambda com `rfind("}")`

Pega uma chave interna, não o fechamento do bloco. Duplicou três grupos das
Configurações e fez a tela fechar o app.

**Como evitar:** contar chaves a partir da primeira `{`, incrementando e
decrementando até voltar a zero.

### 3. Script que aborta no meio não grava nada

Um `assert` que falha descarta todos os `write()` posteriores **do mesmo
script** — inclusive os de arquivos que já tinham sido processados com sucesso.

**Como evitar:** um script por arquivo quando as alterações são independentes, e
conferir o resultado depois de cada execução em vez de encadear.

### 4. Diff global mascara órfã local

Duas classes com método de mesmo nome: se uma perde o método, o conjunto global
continua completo e o diff não acusa. Foi assim que `configurarSelecao` sumiu
sem alarme — outra classe recém-criada tinha um método homônimo.

**Como evitar:** o diff tem que ser **por arquivo**, cruzando com as chamadas no
mesmo arquivo.

---

## Erros de Kotlin

### 5. `return@try` não existe

`try`, `catch`, `finally`, `if`, `when`, `for`, `while` não são lambdas e não
aceitam label. Em corpo de bloco, o `return` simples já funciona.

### 6. `return` em corpo-expressão

`fun x(): Int = try { ... return 0 ... }` não compila. Converter para corpo de
bloco.

### 7. Enum como valor

`val Cat = SessionManager.Category` não compila sem companion object. Usar
`import ...SessionManager.Category`.

### 8. `ByteArray + Int`

Kotlin tem `plus(Byte)`, não `plus(Int)`, e literais não convertem
implicitamente. Escrever `+ 0.toByte()`.

### 9. Visibilidade em valor padrão de classe aninhada

Função `private` usada como valor padrão de parâmetro numa `data class` aninhada
pode ser recusada dependendo da versão do compilador. Deixar não-privada.

---

## Erros de Android

### 10. View tocada em `Dispatchers.IO`

"Only the original thread that created a view hierarchy can touch its views."
Apareceu duas vezes, em pontos diferentes, e o sintoma engana: falha na primeira
tentativa e funciona na segunda, porque o dado já foi gravado antes da exceção.

**Como evitar:** capturar tudo o que vem da UI **antes** do `launch`; nunca
chamar de dentro do IO uma função que escreve na tela.

### 11. Contorno de seleção invisível

Pintado no `background`, fica atrás da imagem que ocupa todo o frame. Usar
`foreground`.

### 12. MaterialButton ignora `android:background`

O tema MaterialComponents transforma todo `Button` em MaterialButton, que recorta
cantos de backgrounds custom. Usar `backgroundTint` para sólidos e style
`Widget.MaterialComponents.Button.OutlinedButton` para vazados.

### 13. `AnimationDrawable` iniciada antes de anexar

Fica parada no primeiro quadro. Iniciar dentro de `post { }`.

### 14. Layouts paralelos em `layout-land`

Congelam em versões antigas e divergem silenciosamente do retrato — crash,
botões mortos, campos ausentes. Preferir **um layout só**, com arranjo decidido
em runtime quando necessário.

### 15. Medição de fonte antes do autosize estabilizar

Ler `textSize` logo após o primeiro layout devolve valor transitório. Esperar
dois ciclos (`post { post { } }`) e re-medir a cada mudança de largura.

---

## Erros de recursos

### 16. Apóstrofo não escapado

Quebra o build de recursos com mensagem enganosa ("Invalid unicode escape
sequence") apontando linha vizinha. Escrever `\'` ou envolver a string em aspas
duplas.

Passou despercebido porque a validação rodava **só no arquivo português** —
português quase não usa apóstrofo, inglês usa sempre.

### 17. String só em um idioma

`cache_confirm_warning` existiu só em EN e ES; num tablet em português, o diálogo
estourava com `ResourceNotFoundException`. O lint com `MissingTranslation` como
erro pega isso no build; a suíte `StringsParidadeTest` pega em segundos.

### 18. Detector de escape que não avança sobre o par

Ao validar `\\` (barra literal), um detector ingênuo trata a segunda barra como
novo escape solto e gera falso positivo. Avançar o índice sobre o par consumido.

### 19. `R.id` de outro layout

Nas Configurações, cada grupo declara um layout e só pode usar IDs dele. Um
binding no grupo errado passa na compilação e explode em runtime.

**Como evitar:** validação que cruza os `R.id` usados no lambda de cada
`adicionarGrupo` com os IDs do layout declarado, seguindo `<include>`
recursivamente.

---

## Erros de empacotamento

### 20. `zip -r` atualiza zip existente

Não substitui: mescla. Arquivos removidos do projeto ressuscitam no pacote.
Apagar o destino antes.

### 21. Conferir no working dir não basta

Só o que está **dentro do zip** conta. Uma inserção por índice que casou o
parêntese errado passou nos asserts locais e chegou ao pacote sem efeito — só a
conferência no zip acusou.

---

## O ritual completo

Rodar tudo antes de considerar um lote pronto. Os scripts estão em
`scripts_validacao.py`.

| # | Verificação | Pega |
|---|---|---|
| 1 | Chaves `{}` balanceadas em todo `.kt` | corte mal feito |
| 2 | Diff de **funções por arquivo** vs. versão anterior | função engolida |
| 3 | Diff de **campos por arquivo** vs. versão anterior | campo engolido |
| 4 | `return@` em construção sem label | erro 5 |
| 5 | `return` em corpo-expressão | erro 6 |
| 6 | Classe usada sem import | erro 7 |
| 7 | `R.id` por grupo × layout declarado | erro 19 |
| 8 | View tocada dentro de `Dispatchers.IO` | erro 10 |
| 9 | Strings: paridade 3 idiomas, placeholders, escapes, `&` | erros 16–18 |
| 10 | XML de todos os recursos + manifest | XML malformado |
| 11 | Lixo de filesystem (`{`, pastas com nome estranho) | brace expansion |
| 12 | Empacotar com destino limpo + conferir dentro do zip | erros 20–21 |

**Por que por arquivo e não global:** as verificações 2 e 3 só funcionam se
comparadas arquivo a arquivo. Um método que existe em outra classe engana o
conjunto global — foi exatamente o que deixou passar a perda de
`configurarSelecao`.

**Complemento barato:** `gradlew test` roda as três suítes na JVM em segundos e
cobre datas, nomes de pasta e traduções antes de qualquer compilação Android.
