<#
================================================================
 EMPACOTADOR do PhotoID RT
================================================================

 DESTINO=pendrive  ·  PROJETO=PHOTOID_RT  ·  VERSAO lida do build.gradle

 POR QUE PENDRIVE
 O APK carrega 56,6 MB de bibliotecas nativas de terceiro — o motor de OCR
 (`libmlkit_google_ocr_pipeline.so`) e o leitor de codigo de barras
 (`libbarhopper_v3.so`) do Google ML Kit, em 4 ABIs. E imagem de terceiro
 embarcada, e o criterio do ENTREGA.md manda isso para pendrive.

 PHOTOID RT E A EXCECAO DO CONTRATO DE INSTALADOR
 O contrato geral (secao 0) manda gerar um .zip com INSTALAR.cmd que instala
 em C:\RADIOTERAPIA_AI\<PROJETO>\ e registra em _instalados.json. Aqui NAO:
 o artefato e um APK que instala no TABLET, nao na estacao. Nao ha o que
 instalar no Windows, nao ha atalho na area de trabalho, e escrever no
 _instalados.json seria mentir — diria que o PhotoID RT esta naquela estacao
 quando ele esta num Galaxy Tab dentro da sala de simulacao.
 O que o contrato exige e cumprido: pacote versionado, entrega.txt com
 PROJETO/VERSAO/DATA, e LEIA-ME.txt com o procedimento de instalacao.

 O APK FICA SOLTO, sem .zip por cima. O .zip do contrato existe para deixar
 o download do Drive limpo; este projeto nao vai para o Drive. E um APK
 dentro de um .zip obriga o tecnico a descompactar antes de copiar para o
 tablet — um passo a mais no lugar onde a regra do produto e que clique
 custa. Se quiser o .zip mesmo assim, use -ComZip.

 SEM CAMINHO ABSOLUTO: o destino e montado a partir de $Destino, $Projeto e
 da versao lida do app/build.gradle.
#>
[CmdletBinding()]
param(
    [switch] $PularPortao,   # so para reempacotar um APK ja validado
    [switch] $ComZip,        # gera tambem <PROJETO>_v<VERSAO>.zip
    [switch] $ManterBuild    # nao limpa app\build ao final
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- contrato
$Destino = 'deploy'            # deploy | pendrive
#
# MUDOU DE 'pendrive' PARA 'deploy' EM 16/08/2026, e o motivo nao foi
# reavaliacao do criterio: C:\PENDRIVE_AI_EXPORT DEIXOU DE EXISTIR na
# reorganizacao do ecossistema. Nao ha destino "pendrive" para onde
# entregar. Todo pacote vai para C:\AI_DEPLOY, na raiz do C:.
$Projeto = 'PHOTOID_RT'

$RaizPorDestino = @{
    'deploy'   = (Join-Path $env:SystemDrive 'AI_DEPLOY')
    'pendrive' = (Join-Path $env:SystemDrive 'PENDRIVE_AI_EXPORT')
}

function Falhar($msg) {
    Write-Host ''
    Write-Host "ERRO: $msg" -ForegroundColor Red
    Write-Host ''
    exit 1
}

function Etapa($n, $txt) { Write-Host ''; Write-Host "[$n] $txt" -ForegroundColor Cyan }

<#
    Grava texto em UTF-8 SEM BOM.

    `Set-Content -Encoding UTF8` no Windows PowerShell 5.1 escreve BOM. Num
    LEIA-ME isso e' feio; no `entrega.txt` e' defeito: o arquivo existe para ser
    lido por outra ferramenta, e um parser ingenuo le a primeira chave como
    "﻿PROJETO" em vez de "PROJETO" e nao encontra o campo. Falha silenciosa e
    dificil de enxergar, porque o BOM e invisivel em quase todo editor.
#>
function Gravar-Texto {
    param([string] $Caminho, [string] $Conteudo)
    $utf8SemBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Caminho, $Conteudo, $utf8SemBom)
}

$raizProjeto = Split-Path $PSScriptRoot -Parent
Set-Location $raizProjeto

if (-not $RaizPorDestino.ContainsKey($Destino)) {
    Falhar "DESTINO invalido: '$Destino'. Use 'deploy' ou 'pendrive'."
}

# A RAIZ do destino tem que JA EXISTIR. Criar sozinho seria recriar uma
# pasta que o ecossistema pode ter apagado de proposito — foi assim que
# uma arvore-sombra apareceu antes, com o build indo para um lugar que
# ninguem olhava e parecendo ter dado certo.
$raizDestino = $RaizPorDestino[$Destino]
if (-not (Test-Path $raizDestino)) {
    Falhar @"
A raiz do destino nao existe: $raizDestino

DESTINO esta como '$Destino'. Se a estrutura do ecossistema mudou, ajuste
`$Destino e `$RaizPorDestino no topo deste script — nao crie a pasta na mao.
Ver ENTREGA.md.
"@
}

# ---------------------------------------------------------------- 1. versao
Etapa 1 'Lendo a versao do app/build.gradle'

$gradle = Join-Path $raizProjeto 'app\build.gradle'
if (-not (Test-Path $gradle)) { Falhar "app/build.gradle nao encontrado em $raizProjeto." }

$txtGradle = Get-Content $gradle -Raw
if ($txtGradle -notmatch 'versionName\s+"([^"]+)"') { Falhar 'versionName nao encontrado no build.gradle.' }
$versionName = $Matches[1]
if ($txtGradle -notmatch 'versionCode\s+(\d+)')     { Falhar 'versionCode nao encontrado no build.gradle.' }
$versionCode = $Matches[1]

# A versao da PASTA leva o build: duas geracoes do mesmo versionName nao podem
# cair na mesma pasta e se sobrescrever. E a pasta que responde "qual versao
# esta naquele tablet" quando alguem abrir chamado.
$versaoPasta = "v$versionName-build$versionCode"
Write-Host "    versionName=$versionName  versionCode=$versionCode"
Write-Host "    pasta de versao: $versaoPasta"

# ---------------------------------------------------------------- 2. portao
if ($PularPortao) {
    Write-Host ''
    Write-Host '[2] PORTAO PULADO (-PularPortao). Use so para reempacotar.' -ForegroundColor Yellow
} else {
    Etapa 2 'Portao de qualidade (testes + lint) e build do APK'
    $jdk = & (Join-Path $PSScriptRoot 'Resolver-Jdk.ps1')
    if ($LASTEXITCODE -ne 0 -or -not $jdk) { Falhar 'JDK nao encontrado (mensagem acima).' }
    $env:JAVA_HOME = $jdk
    Write-Host "    JDK: $jdk"

    # DESTRAVA ANTES DE COMECAR.
    #
    # O projeto mora em C:\AI_PROJETOS, que sincroniza com o Drive, e o
    # GoogleDriveFS marca arquivos de app\build como somente-leitura enquanto os
    # processa. O Gradle apaga essa pasta a cada build e falha com "Unable to
    # delete directory" — mensagem que manda procurar processo travado, quando o
    # que ha e um atributo. Ja custou varias entregas interrompidas no meio.
    & (Join-Path $PSScriptRoot 'Destravar-Build.ps1') | Out-Null

    & (Join-Path $raizProjeto 'gradlew.bat') check assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) {
        # UMA segunda tentativa, e so uma. O Drive pode remarcar os arquivos
        # DURANTE o build, e nesse caso destravar de novo resolve. Se falhar
        # duas vezes seguidas, a causa e outra — teste ou lint de verdade — e
        # insistir so esconderia o defeito.
        Write-Host ''
        Write-Host '    portao falhou; destravando app\build e tentando uma vez mais' -ForegroundColor Yellow
        & (Join-Path $PSScriptRoot 'Destravar-Build.ps1') | Out-Null
        & (Join-Path $raizProjeto 'gradlew.bat') check assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) {
            Falhar 'O portao falhou (teste ou lint). Nada foi empacotado — corrija antes de entregar.'
        }
    }
}

# ---------------------------------------------------------------- 3. APK
Etapa 3 'Localizando o APK gerado'

<#
    O ARTEFATO E O DE RELEASE, e nao o de debug.

    Build de debug e `debuggable` por definicao: qualquer pessoa com o tablet na
    mao conecta um depurador a um aplicativo com fotografia de paciente aberta.
    Entregar isso a um servico conhecido ja era ruim; pendurar numa Release
    publica seria outra coisa.

    Sem a chave o Gradle produz `app-release-unsigned.apk`, que nao instala. O
    empacotador FALHA nesse caso em vez de cair no debug — cair seria entregar
    um binario diferente do que se pediu, com o mesmo nome de sempre.
#>
$apk = Join-Path $raizProjeto 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) {
    $semAssinar = Join-Path $raizProjeto 'app\build\outputs\apk\release\app-release-unsigned.apk'
    if (Test-Path $semAssinar) {
        $prop = Join-Path $HOME '.gradle\gradle.properties'
        $linha = if (Test-Path $prop) {
            (Select-String -Path $prop -Pattern '^photoidKeystore=' |
             Select-Object -First 1).Line
        } else { '(sem ~/.gradle/gradle.properties)' }
        Falhar ("O release saiu SEM ASSINATURA." + [Environment]::NewLine +
                "       O build procura a chave em DOIS lugares, nesta ordem:" + [Environment]::NewLine +
                "       1. a propriedade photoidKeystore do Gradle — e a que vale," + [Environment]::NewLine +
                "          porque chega ao daemon mesmo que ele ja esteja rodando;" + [Environment]::NewLine +
                "       2. a variavel de ambiente PHOTOID_RT_KEYSTORE, para CI." + [Environment]::NewLine +
                [Environment]::NewLine +
                "       Propriedade: " + $linha + [Environment]::NewLine +
                "       Variavel:    '" + $env:PHOTOID_RT_KEYSTORE + "'" + [Environment]::NewLine +
                [Environment]::NewLine +
                "       O arquivo apontado precisa existir E o .jks dentro dele tambem.")
    }
    Falhar "APK nao encontrado em $apk. Rode sem -PularPortao."
}

$apkInfo = Get-Item $apk
$mb = [math]::Round($apkInfo.Length / 1MB, 1)
Write-Host "    $($apkInfo.Name)  ($mb MB)"

# Confere que o APK e MESMO desta versao. Sem isto, um -PularPortao depois de
# mudar a versao entregaria o binario antigo dentro da pasta nova — o erro que
# a pasta por versao existe justamente para impedir.
$aapt = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory -EA SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
if ($aapt) {
    $badging = & $aapt dump badging $apk 2>$null | Select-String "^package:" | Select-Object -First 1
    if ($badging -notmatch "versionCode='$versionCode'" -or $badging -notmatch "versionName='$versionName'") {
        Falhar "O APK nao corresponde a versao do build.gradle.`n       build.gradle: $versionName / $versionCode`n       APK:          $badging"
    }
    Write-Host '    versao do APK confere com o build.gradle'
} else {
    Write-Host '    AVISO: aapt2 nao encontrado; versao do APK nao conferida.' -ForegroundColor Yellow
}

<#
    A ASSINATURA E A IDENTIDADE DO APLICATIVO, e ela nao pode mudar sem querer.

    O Android compara certificado ao atualizar: um APK assinado por outra chave
    NAO instala por cima, e o unico caminho seria desinstalar — o que apaga
    cadastro, rubricario e protocolos de cada aparelho em campo. Depois da
    primeira Release publica isso e irreversivel, porque nao se avisa quem
    baixou.

    Por isso a impressao digital vai conferida aqui, contra o valor esperado.
    Ela e informacao publica: esta dentro de todo APK que sai.

    TROCADA UMA VEZ, em 20/09/2026, e o registro fica porque a proxima pessoa
    que pensar em troca-la precisa saber o que isso custou.

    A keystore original (SHA-256 101BE60F...) foi PERDIDA: nao estava em maquina
    nenhuma nem em backup. Sem ela nao ha como atualizar o que esta instalado,
    entao os dois tablets em campo desinstalaram e reinstalaram — e o cadastro
    do PatientCache so sobreviveu porque foi exportado antes.

    Esta trava recusou o primeiro pacote da chave nova, que e o comportamento
    certo: trocar o valor abaixo E a decisao, e ela aparece no diff. Se este
    numero mudar de novo sem um paragrafo aqui explicando, alguma coisa deu
    errado.
#>
$IMPRESSAO_ESPERADA = '7589DE5ADE90CF475A455B2234BD6F4CF33ED4E534EF129BCFD6B55EB25F0C33'

Etapa 4 'Conferindo a assinatura do APK'
$apksigner = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory -EA SilentlyContinue |
             Sort-Object Name -Descending |
             ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
             Where-Object { Test-Path $_ } | Select-Object -First 1
if ($apksigner) {
    $certs = & $apksigner verify --print-certs $apk 2>&1
    $linhaDn = ($certs | Select-String 'certificate DN:' | Select-Object -First 1)
    $linhaSha = ($certs | Select-String 'certificate SHA-256 digest:' | Select-Object -First 1)
    if ("$linhaDn" -match 'Android Debug') {
        Falhar 'O APK esta assinado com a chave de DEBUG. Nao se distribui isso.'
    }
    $impressao = ''
    if ("$linhaSha" -match '([0-9a-fA-F]{64})') { $impressao = $Matches[1].ToUpper() }
    if ($impressao -ne $IMPRESSAO_ESPERADA) {
        Falhar ("A assinatura do APK NAO e a do projeto." + [Environment]::NewLine +
                "       esperada: $IMPRESSAO_ESPERADA" + [Environment]::NewLine +
                "       no APK:   $impressao" + [Environment]::NewLine +
                "       Um APK com outra assinatura nao atualiza os aparelhos instalados.")
    }
    Write-Host '    assinatura confere com a chave do projeto'
} else {
    Write-Host '    AVISO: apksigner nao encontrado; assinatura NAO conferida.' -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 4b. varredura
Etapa '4b' 'Varrendo o pacote por credencial e dado de paciente'

# O ENTREGA.md conta que uma chave de 72 bytes ja viajou dentro de um bundle de
# 7,9 GB. Aqui a superficie e pequena — um APK — mas a varredura fica porque o
# custo e zero e a falha e cara.
$padroes = @('BEGIN RSA PRIVATE KEY', 'BEGIN PRIVATE KEY', 'BEGIN OPENSSH PRIVATE KEY')
$achados = @()
$bytes = [System.IO.File]::ReadAllBytes($apk)
$comoTexto = [System.Text.Encoding]::ASCII.GetString($bytes)
foreach ($p in $padroes) { if ($comoTexto.Contains($p)) { $achados += $p } }
if ($achados.Count -gt 0) {
    Falhar "CREDENCIAL DENTRO DO PACOTE: $($achados -join ', '). Empacotamento abortado."
}
Write-Host '    nenhuma chave privada no pacote'

# ---------------------------------------------------------------- 5. destino
Etapa 5 'Montando a pasta de versao'

$pastaDestino = Join-Path (Join-Path $RaizPorDestino[$Destino] $Projeto) $versaoPasta
Write-Host "    $pastaDestino"

if (Test-Path $pastaDestino) {
    Write-Host '    pasta ja existe — conteudo sera substituido' -ForegroundColor Yellow
} else {
    New-Item -ItemType Directory -Path $pastaDestino -Force | Out-Null
}

$nomeApk = "${Projeto}_v${versionName}_build${versionCode}.apk"
Copy-Item $apk (Join-Path $pastaDestino $nomeApk) -Force

$data = Get-Date -Format 'yyyy-MM-dd'

# SHA256.txt -- impressao digital do APK publicado.
#
# POR QUE O EMPACOTADOR GERA: o APK vai para uma pasta do Drive e de la para o
# tablet da clinica, passando por download. Sem o hash publicado, quem instala
# nao tem como distinguir um download corrompido de um arquivo trocado, e a
# unica resposta possivel a "esse APK e o de voces?" seria a confianca.
#
# Ficava de fora e era escrito a mao na hora de publicar. O do build 16 saiu
# nomeando um arquivo que nao existia na pasta -- que e exatamente o que
# acontece com passo manual: ele nao falha, ele sai errado em silencio.
#
# Reaproveita os bytes ja lidos na varredura de credencial: o APK nao e lido
# duas vezes.
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $hashApk = ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
} finally { $sha.Dispose() }

Gravar-Texto (Join-Path $pastaDestino 'SHA256.txt') @"
$nomeApk
sha256  $hashApk
bytes   $($bytes.Length)
versao  $versionName (build $versionCode)
data    $data

Conferir depois de baixar, no Windows:

  certutil -hashfile $nomeApk SHA256

O valor tem que bater exatamente. Se nao bater, o download veio
corrompido ou alterado - nao instale.
"@
Write-Host "    SHA256.txt  ($($hashApk.Substring(0,16))...)"

# entrega.txt — o que o contrato exige que todo pacote carregue.
@"
PROJETO=$Projeto
VERSAO=$versionName
BUILD=$versionCode
DATA=$data
DESTINO=$Destino
ARTEFATO=$nomeApk
ALVO=tablet Android (nao instala na estacao)
"@ | ForEach-Object { Gravar-Texto (Join-Path $pastaDestino 'entrega.txt') $_ }

# LEIA-ME.txt — procedimento de instalacao no Android.
@"
================================================================
 PhotoID RT $versionName (build $versionCode)
 Documentacao fotografica de posicionamento em radioterapia
================================================================

ESTE PACOTE NAO INSTALA NO COMPUTADOR

O artefato e um APK: instala no TABLET Android usado dentro da sala
de simulacao e do acelerador. Nao ha nada para executar no Windows,
e por isso este pacote nao escreve em C:\RADIOTERAPIA_AI\ nem no
_instalados.json — dizer que o PhotoID RT esta "nesta estacao" seria
falso.


COMO INSTALAR NO TABLET

1. Copie o arquivo $nomeApk
   para o tablet: cabo USB, pen-drive por OTG ou pasta de rede.

2. No tablet, abra "Meus Arquivos" e toque no .apk.

3. O Android vai pedir para permitir instalacao de fontes
   desconhecidas para o aplicativo de arquivos. Autorize.
   E pedido uma vez por aparelho.

4. Confirme a instalacao.

Se ja houver uma versao instalada, esta instala por cima e PRESERVA
as fotos e o cadastro de pacientes: o certificado de assinatura e o
mesmo de todas as entregas anteriores.


DEPOIS DE INSTALAR — O PASSO QUE NAO PODE SER PULADO

Abra o app e conceda "Acesso a todos os arquivos".
No Android 11+: Configuracoes > Apps > PhotoID RT > Permissoes >
Arquivos e midia > Permitir acesso para gerenciar todos os arquivos.

POR QUE ISSO IMPORTA: o app escolhe onde gravar conforme essa
permissao.
  COM a permissao  -> fotos e PDFs vao para /PhotoID_RT/PHOTOS/ na
                      raiz do armazenamento. O FileSync enxerga, e
                      os arquivos SOBREVIVEM a desinstalacao do app.
  SEM a permissao  -> tudo cai numa pasta privada do app. O FileSync
                      nao enxerga, e desinstalar APAGA todas as fotos
                      dos pacientes.

Confira depois da primeira foto: a pasta PhotoID_RT tem que aparecer
na raiz do armazenamento, e nao dentro de Android/data.


CONFERIR A VERSAO NO TABLET

Configuracoes > Apps > PhotoID RT deve mostrar versao $versionName.


O QUE ESTE PACOTE NAO PEDE

Microfone. O comando por voz chegou a ser implementado e foi
retirado: a garantia de que o audio nao sai do aparelho so vale no
Android 13+, e as clinicas compram tablets sem controle de versao.
Sem garantia em todo o parque, a funcao nao entra.


DADO DE PACIENTE

Fica no tablet e no servidor da clinica, nunca neste pacote. As
fotos vao do tablet ao servidor pelo FileSync, que e externo ao app.

Gerado em $data por tools\Empacotar.ps1
"@ | ForEach-Object { Gravar-Texto (Join-Path $pastaDestino 'LEIA-ME.txt') $_ }

# ---------------------------------------------------------------- 6. zip
if ($ComZip) {
    Etapa 6 'Gerando o .zip do contrato'
    $zip = Join-Path $pastaDestino "${Projeto}_v${versionName}.zip"
    # `Compress-Archive` ATUALIZA um zip existente: sem apagar antes, arquivo
    # removido de uma geracao para outra ressuscita dentro do pacote.
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path (Join-Path $pastaDestino '*') -DestinationPath $zip
    Write-Host "    $zip"
}

# ---------------------------------------------------------------- 7. conferir
Etapa 7 'Conferindo o que foi entregue'

$itens = Get-ChildItem $pastaDestino | Sort-Object Name
foreach ($i in $itens) {
    Write-Host ("    {0,-46} {1,8:N1} MB" -f $i.Name, ($i.Length / 1MB))
}

$obrigatorios = @($nomeApk, 'entrega.txt', 'LEIA-ME.txt')
$faltando = $obrigatorios | Where-Object { -not (Test-Path (Join-Path $pastaDestino $_)) }
if ($faltando) { Falhar "Faltou no pacote: $($faltando -join ', ')" }

<#
    O APELIDO DE NOME FIXO, em pasta propria.

    Existe porque a URL da Release mais recente do GitHub so responde se o nome
    do arquivo NAO mudar entre versoes:

      .../releases/latest/download/PhotoID_RT_LATEST.apk

    FICA EM latest\, e nao dentro da pasta da versao: a pasta da versao responde
    "o que foi entregue nesta versao", e dois arquivos ali sao duas respostas.
    Ja aconteceu de tres entregas ficarem SO com o nome fixo, sem a copia
    versionada — e dali nao se descobre o que esta instalado sem abrir o
    entrega.txt ao lado.

    E E GERADO AQUI, nao a mao. Apelido feito a mao envelhece calado: publicar a
    versao seguinte e esquecer de atualiza-lo deixa o link servindo a anterior
    para sempre, sem erro em lugar nenhum — o arquivo existe, abre e instala.
#>
Etapa '7b' 'Atualizando o apelido de nome fixo (latest)'

$pastaLatest = Join-Path (Join-Path $RaizPorDestino[$Destino] $Projeto) 'latest'
if (-not (Test-Path $pastaLatest)) { New-Item -ItemType Directory -Path $pastaLatest -Force | Out-Null }

$apelido = Join-Path $pastaLatest 'PhotoID_RT_LATEST.apk'
Copy-Item $apk $apelido -Force
Copy-Item (Join-Path $pastaDestino 'entrega.txt') (Join-Path $pastaLatest 'entrega.txt') -Force

$shaVersao  = (Get-FileHash (Join-Path $pastaDestino $nomeApk) -Algorithm SHA256).Hash
$shaApelido = (Get-FileHash $apelido -Algorithm SHA256).Hash

# POR HASH, nao por data: copia nova com bytes velhos passa numa comparacao de
# data de modificacao e falha aqui, que e onde tem de falhar.
if ($shaVersao -ne $shaApelido) {
    Falhar "O apelido latest nao bate com o APK da versao. Entrega abortada."
}

@"
PhotoID_RT_LATEST.apk
sha256  $($shaApelido.ToLower())
bytes   $((Get-Item $apelido).Length)
versao  $versionName (build $versionCode)
data    $data

Este arquivo e SEMPRE a versao mais recente. O nome nao muda entre
versoes de proposito: e o alvo do link permanente.

A copia com a versao no nome esta em $versaoPasta\.

Conferir depois de baixar, no Windows:

  certutil -hashfile PhotoID_RT_LATEST.apk SHA256
"@ | ForEach-Object { Gravar-Texto (Join-Path $pastaLatest 'SHA256.txt') $_ }

Write-Host "    $pastaLatest"
Write-Host "    sha256 confere com $versaoPasta"


# ---------------------------------------------------------------- 8. limpeza
<#
    Limpa app\build depois de entregar.

    O projeto mora em C:\AI_PROJETOS, que SINCRONIZA com o Google Drive, e o .gitignore
    nao tem efeito nenhum sobre o Drive. Deixar a saida de build aqui manda ~320
    MB regeneraveis para a nuvem a cada geracao — e nao e so banda: o
    GoogleDriveFS abre os arquivos enquanto varre, e o `gradlew clean` (que
    apaga tudo ou nada) chega a FALHAR por isso.

    Roda so depois da entrega estar conferida: o APK ja esta a salvo na pasta de
    versao, entao nao ha nada aqui que nao volte com um build. O custo — um
    rebuild completo na proxima vez — cai sobre o empacotamento, que acontece
    uma vez por release, e nao sobre o desenvolvimento do dia a dia.

    Usa Remove-Item em vez de `gradlew clean` justamente por causa do Drive:
    remocao arquivo a arquivo atravessa o bloqueio intermitente que derruba o
    clean inteiro.
#>
if (-not $ManterBuild) {
    Etapa 8 'Limpando a saida de build (nao deve sincronizar com o Drive)'
    $buildDir = Join-Path $raizProjeto 'app\build'
    if (Test-Path $buildDir) {
        $antes = (Get-ChildItem $buildDir -Recurse -File -EA SilentlyContinue |
                  Measure-Object Length -Sum).Sum
        try {
            Remove-Item $buildDir -Recurse -Force -ErrorAction Stop
            Write-Host ("    liberados {0:N0} MB" -f ($antes / 1MB))
        } catch {
            Write-Host '    AVISO: nao foi possivel limpar por completo.' -ForegroundColor Yellow
            Write-Host '    Provavel causa: GoogleDriveFS ou Android Studio com arquivo aberto.'
            Write-Host '    A ENTREGA ESTA FEITA — isto e so espaco em disco.' -ForegroundColor Yellow
        }
    }
} else {
    Write-Host ''
    Write-Host '[8] app\build MANTIDO (-ManterBuild).' -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'ENTREGA CONCLUIDA' -ForegroundColor Green
Write-Host "  projeto: $Projeto"
Write-Host "  versao:  $versionName (build $versionCode)"
Write-Host "  destino: $Destino"
Write-Host "  pasta:   $pastaDestino"
Write-Host "  latest:  $pastaLatest"
Write-Host ''
exit 0
