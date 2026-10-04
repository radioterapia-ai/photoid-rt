@echo off
REM ===================================================================
REM  PORTAO DE QUALIDADE do PhotoID RT - testes + lint, num comando.
REM
REM  Existe por dois motivos:
REM
REM  1. `assembleDebug` NAO roda lint: com ele, o lint pode estar
REM     vermelho sem ninguem ver. Aqui roda `check`, que roda os dois.
REM  2. JAVA_HOME escrito a mao leva o caminho do JDK de UMA maquina e
REM     quebra nas outras. Aqui o JDK e descoberto pelo Resolver-Jdk.ps1.
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
