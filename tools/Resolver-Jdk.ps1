<#
    Descobre o JDK que o Gradle deste projeto precisa e devolve o caminho.

    POR QUE EXISTE
    O `gradlew` nao acha Java sozinho num terminal sem Java no PATH, e a
    documentacao passou a carregar a linha
    `JAVA_HOME="C:/Users/henri/.jdks/jbr-21.0.11"` escrita a mao. Isso e caminho
    absoluto de UMA maquina dentro de arquivo versionado: quebra no computador
    de qualquer outra pessoa, e contraria a regra do ecossistema de nao ter
    caminho absoluto no codigo.

    A ordem de busca vai do explicito para o palpite, e nunca chuta em silencio:
    sem JDK utilizavel, aborta com mensagem em portugues.

    ATENCAO A VERSAO: o Gradle 8.13 deste projeto NAO roda no JBR 25 do Android
    Studio — falha com "Type T not present", que nao parece erro de JDK. Por isso
    a checagem de versao maxima existe e nao e decorativa.
#>
[CmdletBinding()]
param(
    [int] $VersaoMinima = 17,
    [int] $VersaoMaxima = 21
)

$ErrorActionPreference = 'Stop'

function Test-Jdk {
    param([string] $Caminho)
    if ([string]::IsNullOrWhiteSpace($Caminho)) { return $false }
    return (Test-Path (Join-Path $Caminho 'bin\java.exe'))
}

<#
    Versao maior do JDK.

    Le o arquivo `release`, que todo JDK traz na raiz, em vez de executar
    `java -version`. Executar era fragil por um motivo nada obvio: o `java`
    escreve a versao em STDERR, e com $ErrorActionPreference = 'Stop' isso vira
    excecao dependendo do host que chamou o script — o detector funcionava
    chamado do PowerShell e falhava chamado do Bash, reportando "versao 0" para
    um JDK perfeitamente valido.
#>
function Get-VersaoJdk {
    param([string] $Caminho)
    # 1. arquivo `release` (JAVA_VERSION="21.0.11")
    $release = Join-Path $Caminho 'release'
    if (Test-Path $release) {
        foreach ($linha in (Get-Content $release -ErrorAction SilentlyContinue)) {
            if ($linha -match '^JAVA_VERSION="?(\d+)') { return [int]$Matches[1] }
        }
    }
    # 2. nome da pasta (jbr-21.0.11, jdk-17.0.9, temurin-21)
    if ((Split-Path $Caminho -Leaf) -match '(?:^|[^\d])(\d{1,2})(?:[.\-]|$)') {
        return [int]$Matches[1]
    }
    # 3. ultimo recurso: perguntar ao proprio java, isolando o stderr
    try {
        $saida = & cmd.exe /c "`"$(Join-Path $Caminho 'bin\java.exe')`" -version 2>&1"
        if (($saida | Out-String) -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

$candidatos = [System.Collections.Generic.List[string]]::new()

# 1. JAVA_HOME, quando o ambiente ja resolveu.
if (Test-Jdk $env:JAVA_HOME) { $candidatos.Add($env:JAVA_HOME) }

# 2. O que o proprio Gradle usa na linha de comando. Fonte de verdade real:
#    e o arquivo que decide o build, e nao e versionado.
$gp = Join-Path $env:USERPROFILE '.gradle\gradle.properties'
if (Test-Path $gp) {
    foreach ($linha in Get-Content $gp) {
        if ($linha -match '^\s*org\.gradle\.java\.home\s*=\s*(.+)$') {
            $c = $Matches[1].Trim() -replace '\\\\', '\' -replace '\\:', ':'
            if (Test-Jdk $c) { $candidatos.Add($c) }
        }
    }
}

# 3. O pin do Android Studio para este projeto (.gradle/ e gitignorado).
$cp = Join-Path $PSScriptRoot '..\.gradle\config.properties'
if (Test-Path $cp) {
    foreach ($linha in Get-Content $cp) {
        if ($linha -match '^\s*java\.home\s*=\s*(.+)$') {
            $c = $Matches[1].Trim() -replace '\\\\', '\' -replace '\\:', ':'
            if (Test-Jdk $c) { $candidatos.Add($c) }
        }
    }
}

# 4. JDKs instalados nos lugares habituais, do mais novo para o mais antigo.
foreach ($raiz in @("$env:USERPROFILE\.jdks", "$env:ProgramFiles\Java",
                    "$env:ProgramFiles\Eclipse Adoptium",
                    "$env:LOCALAPPDATA\Programs\Eclipse Adoptium")) {
    if (Test-Path $raiz) {
        Get-ChildItem $raiz -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { if (Test-Jdk $_.FullName) { $candidatos.Add($_.FullName) } }
    }
}

foreach ($c in $candidatos) {
    $v = Get-VersaoJdk $c
    if ($v -ge $VersaoMinima -and $v -le $VersaoMaxima) {
        Write-Output $c
        exit 0
    }
}

Write-Host ''
Write-Host "ERRO: nenhum JDK entre $VersaoMinima e $VersaoMaxima foi encontrado." -ForegroundColor Red
Write-Host ''
Write-Host 'O Gradle 8.13 deste projeto precisa de JDK 17 a 21. O JBR 25 que vem'
Write-Host 'com o Android Studio NAO serve: falha com "Type T not present", que nao'
Write-Host 'parece erro de JDK e ja custou horas de diagnostico.'
Write-Host ''
Write-Host 'Resolva de uma destas formas:'
Write-Host '  - defina JAVA_HOME apontando para um JDK 17-21; ou'
Write-Host '  - instale o JDK 21 pelo Android Studio'
Write-Host '    (Settings > Build Tools > Gradle > Gradle JDK > Download JDK 21); ou'
Write-Host "  - acrescente a linha abaixo em $gp"
Write-Host '        org.gradle.java.home=C\:\\caminho\\para\\o\\jdk21'
if ($candidatos.Count -gt 0) {
    Write-Host ''
    Write-Host 'Encontrados, mas fora da faixa aceita:'
    foreach ($c in $candidatos) { Write-Host ("  {0}  (versao {1})" -f $c, (Get-VersaoJdk $c)) }
}
exit 1
