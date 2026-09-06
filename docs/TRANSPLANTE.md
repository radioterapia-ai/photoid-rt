# Transplante do projeto para o notebook

Passo a passo para colocar o PhotoID RT rodando na máquina nova, com Claude Code
como ambiente de trabalho.

---

## 1. O que instalar antes

| Ferramenta | Versão | Por quê |
|---|---|---|
| **JDK** | **21** | Gradle 8.13 + AGP 8.13.2. Ver a nota abaixo |
| **Android Studio** | Koala ou mais novo | Traz o Android SDK e o emulador |
| **Android SDK Platform** | API 34 | `compileSdk 34` |
| **Android SDK Build-Tools** | 34.x | — |
| **Claude Code** | atual | Já instalado |
| **Git** | qualquer | Já em uso — ver seção 4 |
| **Python** | 3.x | Roda `docs/scripts_validacao.py` |

**A toolchain real do projeto é esta** — Gradle **8.13**, AGP **8.13.2**, Kotlin
**1.9.20**, JDK **21**. Versões anteriores deste documento diziam Gradle 8.7 /
AGP 8.2.2 / JDK 17; a migração aconteceu e a documentação não acompanhou. Se
encontrar 8.7 mencionado em algum lugar, é resíduo.

**Sobre o JDK — o erro é fácil de diagnosticar errado.** O JBR que vem com o
Android Studio hoje é o **25**, e o Gradle 8.13 não roda nele: falha com

```
Could not create an instance of type DefaultReportContainer
   > Type T not present
```

que não parece um erro de JDK. O Gradle 8.13 suporta até o JDK 23. Use o **21**.
O JDK 17 não é mais necessário.

Duas coisas já estão configuradas nesta máquina e resolvem isso sozinhas:

- `.gradle/config.properties` (local do projeto, não versionado) — é o que o
  Android Studio usa, via *Gradle JDK = GRADLE_LOCAL_JAVA_HOME*;
- `~/.gradle/gradle.properties` com `org.gradle.java.home` — faz o `gradlew` da
  linha de comando subir o daemon no 21 mesmo quando o `JAVA_HOME` do terminal
  aponta para o JBR 25 (que é o caso do terminal embutido do Android Studio).

Se for para outra máquina, refaça um dos dois, ou exporte `JAVA_HOME` apontando
para um JDK 21.

No Android Studio, em *SDK Manager → SDK Tools*, marque também **Android SDK
Command-line Tools** — o Claude Code usa para rodar build sem abrir a IDE.

---

## 2. Descompactar e apontar o SDK

Extraia `RadioterapiaAi_E6.zip` onde preferir. Sugestão de caminho curto, porque
o Windows tem limite de 260 caracteres e este projeto tem pastas aninhadas:

```
C:\dev\PhotoIDRT\
```

Dentro, crie o arquivo `local.properties` na raiz (ao lado de `settings.gradle`),
apontando para o seu SDK:

```
sdk.dir=C\:\\Users\\SEU_USUARIO\\AppData\\Local\\Android\\Sdk
```

Esse arquivo é local por natureza e **não** vai no controle de versão.

---

## 3. Primeiro build

No terminal, dentro da pasta do projeto:

```
gradlew.bat assembleDebug          # Windows
./gradlew assembleDebug            # Linux/macOS
```

A primeira execução baixa o Gradle e as dependências — leva alguns minutos.
O APK sai em `app/build/outputs/apk/debug/`.

Para rodar a suíte de testes (JVM, sem device, segundos):

```
gradlew.bat test
```

Três suítes devem passar (24 testes): validação de datas, sanitização de nomes
de pasta e paridade das traduções.

### O portão de qualidade — `gradlew check`

```
gradlew.bat check
```

`check` roda **test + lint** de uma vez. Use este, não só o `test`.

Isso importa mais do que parece: o `assembleDebug` **não roda o lint**. Só
`check`, `lint` e `build` rodam. Durante a E6 o portão foi configurado
(`MissingTranslation`, `ExtraTranslation` e `MissingDefaultResource` quebram o
build) e ficou meses vermelho sem ninguém ver, porque o comando do dia a dia
nunca passava por ele — inclusive um erro que fecharia o app em tablets antigos.

Esperado hoje: **24 testes, 0 falhas, 0 erros de lint**. Os avisos restantes são
conhecidos e aceitos.

---

## 4. Controle de versão — já feito

O projeto atravessou dezenas de ciclos de correção sem histórico de versões — a
recuperação de código perdido dependeu de zips antigos. O repositório Git já
existe, com o estado E6 no primeiro commit e o `.gitignore` cobrindo `build/`,
`.gradle/`, `.idea/`, `local.properties`, `*.apk` e chaves de assinatura.

A regra é **um commit por lote de alterações**, com o resultado do
`gradlew check` no corpo da mensagem. É o que permite responder "o que mudou
desde o último build que funcionava" em segundos.

Convenção de assunto:

```
fix:   corrige comportamento errado
feat:  funcionalidade nova
chore: build, dependências, arquivos de projeto
docs:  documentação
```

---

## 5. Trabalhar com o Claude Code

O arquivo `CLAUDE.md` na raiz é lido automaticamente a cada sessão. Ele traz as
restrições de produto, as convenções de armazenamento, o ritual de validação e a
lista de footguns. Mantenha-o atualizado: quando uma decisão for tomada ou um
erro novo aparecer, registre ali.

Sugestões de uso que combinam com este projeto:

- **Peça o diagnóstico antes da correção.** O padrão que funcionou nas sessões
  anteriores foi: descrever o sintoma, deixar o Claude ler o código, confirmar a
  causa-raiz, só então corrigir. Vários bugs que pareciam três eram um.
- **Rode `gradlew test` antes de cada build completo.** É mais rápido e pega
  regressão de tradução e de data.
- **Rode os scripts de validação** (`docs/scripts_validacao.py`) depois de
  qualquer refatoração que mova código entre arquivos. Foi exatamente esse tipo
  de operação que quebrou o build mais vezes.
- **Teste no tablet real.** Muitos problemas só apareceram no Galaxy Tab S6 com
  paciente na sala: fonte que não cabe, foto que colide, rotação que perde estado.

---

## 6. Instalar no tablet

Com depuração USB ativada no tablet:

```
gradlew.bat installDebug
```

Ou transfira o APK de `app/build/outputs/apk/debug/` e instale manualmente.

Na primeira execução o app pede as permissões e abre o assistente de configuração
inicial. Para não refazer tudo à mão, use **Configurações → Identidade da clínica
→ Importar configurações** com um JSON exportado do tablet atual — restaura nome
da clínica, médicos, equipamentos, sítios, tamanho de etiqueta e orientação. O
logotipo é reenviado à parte.

---

## 7. Estrutura de pastas do projeto

```
PhotoIDRT/
├── CLAUDE.md                    contexto permanente (lido pelo Claude Code)
├── README.md                    visão geral da entrega E6
├── docs/
│   ├── TRANSPLANTE.md           este arquivo
│   ├── ARQUITETURA.md           módulos, fluxos de dados, formatos
│   ├── JORNADA.md               história de cada funcionalidade
│   ├── ARMADILHAS.md            erros cometidos e como evitá-los
│   ├── PENDENCIAS.md            o que ficou em aberto e por quê
│   └── scripts_validacao.py     as verificações do ritual
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/java/com/radioterapia/ai/   69 arquivos, ~17.300 linhas
│       ├── main/res/                        56 layouts, 569 strings × 3 idiomas
│       └── test/java/                       3 suítes de teste
├── build.gradle
├── settings.gradle
└── gradle/wrapper/
```

---

## 8. Se o build falhar

**"Type T not present"** ou **"Could not create an instance of type
DefaultReportContainer"** — JDK errado, apesar de a mensagem não sugerir isso.
É o Gradle 8.13 rodando no JBR 25. Confirme o **JDK 21** em *Settings → Build
Tools → Gradle → Gradle JDK*, ou o `org.gradle.java.home` em
`~/.gradle/gradle.properties`.

**"JAVA_HOME is not set and no 'java' command could be found"** — o `gradlew`
precisa de um JVM para se iniciar; `org.gradle.java.home` só decide o daemon,
não o lançador. Exporte `JAVA_HOME` apontando para o JDK 21.

**"Unsupported class file major version"** — JDK errado, na direção oposta.

**"SDK location not found"** — falta o `local.properties` ou o caminho está
errado. Barras invertidas precisam ser duplicadas no Windows.

**"Failed to flatten XML ... Invalid unicode escape"** — apóstrofo não escapado
numa string. Rode a validação de strings do `scripts_validacao.py`; ela aponta o
arquivo e a chave.

**Erros de compilação em cascata (dezenas de linhas)** — quase sempre é uma
causa só: um bloco de código movido para o lugar errado. Rode os diffs de função
e de campo antes de tentar corrigir linha a linha.

**Caminho muito longo no Windows** — mova o projeto para uma pasta mais rasa
(`C:\dev\`).
