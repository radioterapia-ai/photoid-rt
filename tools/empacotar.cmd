@echo off
REM ===================================================================
REM  EMPACOTADOR do PhotoID RT - duplo clique ou linha de comando.
REM
REM  DESTINO=deploy | PROJETO=PHOTOID_RT | VERSAO lida do build.gradle
REM  Saida: C:\AI_DEPLOY\PHOTOID_RT\v<versao>-build<n>\
REM
REM  Roda o portao (testes + lint) antes de empacotar. Portao vermelho
REM  NAO gera entrega.
REM
REM  Opcoes, repassadas ao .ps1:
REM    -PularPortao   reempacota um APK ja validado, sem rebuildar
REM    -ComZip        gera tambem PHOTOID_RT_v<versao>.zip
REM ===================================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Empacotar.ps1" %*
set "CODIGO=%ERRORLEVEL%"
echo.
if not "%CODIGO%"=="0" (
    echo Empacotamento FALHOU. Nada foi entregue.
)
pause
exit /b %CODIGO%
