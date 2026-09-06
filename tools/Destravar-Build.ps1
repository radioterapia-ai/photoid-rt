#Requires -Version 5.1
<#
 Destrava app\build quando o Gradle falha com "Unable to delete directory" ou
 AccessDeniedException.

 A CAUSA: o projeto mora em C:\AI_PROJETOS, que sincroniza com o Google Drive. O
 GoogleDriveFS marca arquivos como SOMENTE LEITURA enquanto os processa, e o
 Gradle apaga a pasta de saida a cada build. O erro que aparece fala em "process
 has files open", o que manda procurar processo travado, mas nao ha processo
 nenhum segurando nada: e o atributo ReadOnly, e limpar o atributo resolve.

 NAO APAGA NADA. So tira o atributo, o que faz deste script algo seguro de rodar
 a qualquer momento, inclusive por engano.

 A CORRECAO DE VERDADE e excluir "app\build" da sincronizacao do Drive (nas
 preferencias do Google Drive, em "Pastas do computador"). Enquanto isso nao for
 feito, este script e o desentupidor.

 Uso:
   powershell -ExecutionPolicy Bypass -File tools\Destravar-Build.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$raizProjeto = Split-Path $PSScriptRoot -Parent
$alvo = Join-Path $raizProjeto 'app\build'

if (-not (Test-Path $alvo)) {
    Write-Host "Nada a fazer: $alvo nao existe."
    exit 0
}

$somenteLeitura = [IO.FileAttributes]::ReadOnly
$n = 0

Get-ChildItem $alvo -Recurse -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Attributes -band $somenteLeitura } |
    ForEach-Object {
        try { $_.Attributes = $_.Attributes -band (-bnot $somenteLeitura); $n++ } catch { }
    }

$raiz = Get-Item $alvo -Force -ErrorAction SilentlyContinue
if ($raiz -and ($raiz.Attributes -band $somenteLeitura)) {
    try { $raiz.Attributes = $raiz.Attributes -band (-bnot $somenteLeitura); $n++ } catch { }
}

Write-Host "Atributo somente-leitura removido de $n item(ns) em app\build."
if ($n -eq 0) {
    Write-Host ''
    Write-Host 'Nenhum item estava marcado. Se o build continua falhando, a causa'
    Write-Host 'e outra: veja se o Android Studio esta com um build em andamento.'
}
