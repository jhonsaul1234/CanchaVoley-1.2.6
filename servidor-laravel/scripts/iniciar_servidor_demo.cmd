@echo off
rem CanchaVoley: enciende el servidor con la base de DEMOSTRACION (datos ficticios).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar_servidor.ps1" -Demo %*
pause
