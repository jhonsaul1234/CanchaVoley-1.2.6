@echo off
rem CanchaVoley: servidor de DEMOSTRACION (datos ficticios) para probar con un celular en la misma red Wi-Fi.
rem Muestra la direccion para escribir en la app del celular. Si Windows pregunta por el firewall, permitir solo en redes privadas.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar_servidor.ps1" -Demo -Red %*
pause
