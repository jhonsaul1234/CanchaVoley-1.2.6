@echo off
rem CanchaVoley: crea la base de demostracion cancha_voley_demo con datos ficticios.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0preparar_demo.ps1" %*
