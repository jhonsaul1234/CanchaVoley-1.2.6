@echo off
rem CanchaVoley: borra y vuelve a crear la base de demostracion (pide escribir SI).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0preparar_demo.ps1" -Reiniciar %*
