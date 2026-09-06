# Instalação no tablet — PhotoID RT

Como levar o app para a clínica e instalar num tablet Android.

---

## O que você está instalando

| | |
|---|---|
| Pacote | `com.radioterapia.ai` |
| Nome no tablet | **PhotoID RT** |
| Versão | **1.8 (build 16)** |
| Android mínimo | 7.0 (API 24) |
| Permissões | Câmera e armazenamento. **Sem microfone** — ver nota abaixo. |
| Assinatura | certificado **Android Debug** — ver a seção "Assinatura" abaixo |

O APK fica em `entrega/`, gerado por `gradlew assembleDebug`.

---

## Instalar

1. Copie o `.apk` para o tablet — cabo USB, pen-drive por OTG ou pasta de rede.
2. No tablet, abra o **Meus Arquivos** e toque no `.apk`.
3. O Android vai pedir para permitir instalação de fontes desconhecidas para o
   aplicativo de arquivos. Autorize. É pedido uma vez por aparelho.
4. Confirme a instalação.

Instalando por cabo, com o tablet em modo desenvolvedor:

```bash
adb install -r "PHOTOID_RT_v1.8_build16.apk"
```

O `-r` reinstala por cima preservando os dados.

---

## Depois de instalar — o passo que NÃO pode ser pulado

Abra o app e conceda **"Acesso a todos os arquivos"** (All files access) quando
ele pedir. No Android 11+ isso fica em
*Configurações → Apps → PhotoID RT → Permissões → Arquivos e mídia → Permitir
acesso para gerenciar todos os arquivos.*

**Por que isso importa mais do que parece:** o app escolhe onde gravar de acordo
com essa permissão (`StorageLocal.base()`):

- **Com** a permissão → fotos e PDFs vão para `/PhotoID_RT/PHOTOS/` na raiz do
  armazenamento. Ficam visíveis para o FileSync e **sobrevivem** a uma
  desinstalação do app.
- **Sem** a permissão → tudo cai numa pasta privada do app. O FileSync não
  enxerga, e **desinstalar o app apaga todas as fotos dos pacientes.**

Confira depois de tirar a primeira foto: a pasta `PhotoID_RT` tem que aparecer
na raiz do armazenamento, não dentro de `Android/data`.

---

## Por que o app não pede microfone

O comando por voz na captura chegou a ser implementado e foi **retirado por
decisão de produto**, não por dificuldade técnica.

A promessa era que o áudio nunca sairia do tablet. Ela só se sustenta no
**Android 13+**, onde existe uma API de reconhecimento exclusivamente local.
Abaixo disso, `EXTRA_PREFER_OFFLINE` é apenas uma *preferência*: sem o pacote de
idioma instalado, o sistema envia o áudio à rede. O microfone de uma sala de
simulação capta a conversa com o paciente.

Como o app é universal e as clínicas compram tablets sem controle de qual versão
do Android vem, não havia como garantir processamento local em todo o parque.
Sem garantia, a função não entra. **Não reintroduzir sem que a promessa valha em
todas as versões alvo.**

## Conferir que o tablet está com o build certo

*Configurações → Apps → PhotoID RT* deve mostrar **versão 1.8**. Versão menor que
essa é build anterior aos consertos dos bugs de campo — inclusive o do Time-Out
que não chegava ao disco e o que gravava dados de simulação na pasta de outro
paciente.

Se aparecer **1.3**, é o build que pedia permissão de microfone; substitua.

---

## Assinatura — decida isto antes de espalhar para vários tablets

Este APK é assinado com o **certificado de debug** do Android. Ele instala e
funciona normalmente, e é o mesmo certificado de todas as entregas anteriores,
então atualiza por cima do que já está instalado sem perder nada.

O que ainda não existe: uma **keystore de release** própria. O `app/build.gradle`
não tem `signingConfig`, então `assembleRelease` produz um APK **não assinado**,
que o Android recusa instalar.

**A armadilha:** APK assinado com certificado diferente **não atualiza** por cima
do instalado. O Android recusa com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Para
migrar de debug para release seria preciso **desinstalar** — e desinstalar apaga
o `filesDir`, onde vive o **cadastro de pacientes do `PatientCache`** (nomes,
prontuários, contagem de simulações). As fotos sobrevivem *se* a permissão de
acesso total estiver concedida; o cadastro não sobrevive de jeito nenhum.

Ou seja: **quanto mais tablets receberem o build debug, mais caro fica migrar
depois.** Decida agora:

- **Seguir com debug** — nada a fazer, é o que está entregue. Aceitável para
  distribuição interna por sideload, sem loja. O app fica marcado `debuggable`.
- **Criar keystore de release** — o certo para produção, e a hora de fazer é
  antes do próximo tablet. Passos abaixo.

### Se optar pela keystore de release

Gere a keystore você mesmo (a senha é sua e não deve ser versionada nem passar
por terceiros):

```bash
keytool -genkeypair -v -keystore photoid-rt-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias photoid
```

Guarde o arquivo `.jks` e a senha fora do repositório e **com backup**: perder a
keystore significa nunca mais conseguir atualizar o app instalado. Depois disso,
o `signingConfig` entra no `app/build.gradle` lendo as senhas de um
`keystore.properties` não versionado, e a linha vira `gradlew assembleRelease`.

---

## Regerar o APK

Da raiz do projeto:

```bash
tools\portao.cmd
```

O `portao.cmd` descobre o JDK sozinho (`tools/Resolver-Jdk.ps1`) e roda testes
+ lint. Para gerar o APK e montar a entrega versionada, use o empacotador:

```bash
tools\empacotar.cmd
```

`check` roda testes + lint (o `assembleDebug` sozinho **não** roda lint). A saída
fica em `app/build/outputs/apk/debug/app-debug.apk`.
