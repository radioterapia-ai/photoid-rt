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

## Assinatura — a chave de release e onde ela mora

Desde a v3.2 as entregas saem assinadas com uma **keystore de release própria**,
não mais com o certificado de debug. O certificado do que está em campo:

```
CN=Henrique Faria Braga, OU=PhotoID RT, O=Radioterapia.AI, C=BR
RSA 4096 · válido até 2061 · emitido em 06/09/2026
SHA-256  10:1B:E6:0F:1D:A1:22:16:0A:F6:34:04:82:72:67:EF:
         CC:C1:B2:39:27:22:B6:43:06:CA:09:AB:71:52:A0:C3
```

Para conferir de qual chave veio um APK qualquer, sem instalar nada:

```bash
apksigner verify --print-certs PhotoID_RT_LATEST.apk
```

### Onde a chave fica, e por que não aqui

O `app/build.gradle` lê o caminho de um `.properties` apontado pela variável de
ambiente `PHOTOID_RT_KEYSTORE`. Ele fica **fora** de `C:\AI_PROJETOS` e de
`C:\AI_DEPLOY`, que sincronizam com o Drive: chave de assinatura em nuvem
pessoal é o pior caso deste projeto — quem a tem publica uma atualização
maliciosa assinada como o autor, e o Android instala por cima sem perguntar.

**Sem a variável o build não quebra**: cai no não-assinado, que é o que um clone
do repositório público deve fazer. Quem clona não tem a chave e não precisa dela
para compilar. O APK sai como `app-release-unsigned.apk`, e o empacotador
recusa entregá-lo — release sem assinatura tem de ser visível, não silencioso.

### A armadilha, que continua valendo

APK assinado com certificado **diferente** não atualiza por cima do instalado: o
Android recusa com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Trocar de chave obriga
a **desinstalar**, e desinstalar apaga o `filesDir`, onde vive o **cadastro de
pacientes do `PatientCache`** (nomes, prontuários, contagem de simulações). As
fotos sobrevivem *se* a permissão de acesso total estiver concedida; o cadastro
não sobrevive de jeito nenhum.

Por isso: **perder a keystore custa o cadastro de todo tablet em campo.** Antes
de qualquer troca de chave, exporte a base em Configurações → Base de dados, e
reimporte depois de reinstalar.

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
