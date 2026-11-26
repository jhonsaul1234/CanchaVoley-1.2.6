@echo off
rem CanchaVoley: pruebas del servidor, respaldo y migraciones de cancha_voley (ver actualizar_base.ps1).
rem Acepta las mismas opciones, por ejemplo:  scripts\actualizar_base.cmd -SoloRevisar
rem (Guardado con fin de linea CRLF: ver .gitattributes.)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0actualizar_base.ps1" %*
echo.
pause
