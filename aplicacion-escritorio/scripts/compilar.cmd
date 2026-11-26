@echo off
rem CanchaVoley: compila y prueba las apps (ver compilar.ps1). Acepta las mismas opciones,
rem por ejemplo:  scripts\compilar.cmd -SoloWindows
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0compilar.ps1" %*
echo.
pause
