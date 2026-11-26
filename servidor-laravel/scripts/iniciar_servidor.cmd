@echo off
rem CanchaVoley: enciende el servidor con la base real (cancha_voley).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar_servidor.ps1" %*
pause
