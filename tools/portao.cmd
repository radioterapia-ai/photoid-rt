@echo off
REM ===================================================================
REM  PORTAO DE QUALIDADE do PhotoID RT - testes + lint, num comando.
REM
REM  Existe por dois motivos:
REM
REM  1. `assembleDebug` NAO roda lint. Foi por isso que o portao ficou
REM     vermelho por meses sem ninguem ver. Aqui roda `check`, que roda
REM     os dois.
REM  2. A linha com JAVA_HOME estava escrita a mao na documentacao, com
REM     o caminho do JDK de UMA maquina. Agora o JDK e descoberto.
REM
REM  Uso:  tools\portao.cmd
REM ===================================================================
setlocal
cd /d "%~dp0.."

for /f "usebackq delims=" %%J in (
    `powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Resolver-Jdk.ps1"`
) do set "JAVA_HOME=%%J"

if not defined JAVA_HOME (
    echo.
    echo ERRO: JDK nao encontrado. A mensagem acima explica como resolver.
    exit /b 1
)

echo JDK: %JAVA_HOME%
echo.

call gradlew.bat check --console=plain
if errorlevel 1 (
    echo.
    echo PORTAO VERMELHO: teste ou lint falhou. Nada foi entregue.
    exit /b 1
)

echo.
echo PORTAO VERDE: testes e lint passaram.
exit /b 0
