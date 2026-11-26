<#
CanchaVoley - preparar la base de DEMOSTRACION (cancha_voley_demo) con datos FICTICIOS.

USO:
    doble clic en scripts\preparar_demo.cmd             (crea la base si no existe y carga los datos)
    doble clic en scripts\reiniciar_demo.cmd            (borra la base de demostracion y la vuelve a crear)

  o desde PowerShell, dentro de CanchaVoley\cancha-backend:
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\preparar_demo.ps1 [-Reiniciar] [-SinPausa]

QUE HACE:
    - Trabaja SOLO en la base cancha_voley_demo. Nunca toca cancha_voley (la real) ni las bases de ProLech.
    - Crea la base si no existe y sus tablas (php artisan migrate).
    - Carga negocio, canchas, horarios, tarifas, reglas, clientes, reservas y pagos INVENTADOS
      (php artisan canchavoley:demo). No son datos del negocio real.
    - Con -Reiniciar borra primero las tablas de cancha_voley_demo (pide escribir SI).
    - Cuentas de demostracion (clave Demo2026): admin@demo.canchavoley, recepcion@demo.canchavoley,
      cliente@demo.canchavoley.
Despues: scripts\iniciar_servidor_demo.cmd y, en la app, servidor http://127.0.0.1:8001.
#>
[CmdletBinding()]
param(
    [switch]$Reiniciar,
    [switch]$SinPausa,
    [switch]$Si
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

function Escribir([string]$texto, [string]$color = 'Gray') { Write-Host $texto -ForegroundColor $color }
function Terminar([int]$codigo) {
    if (-not $SinPausa) { Escribir 'Presione Enter para cerrar.' 'Yellow'; [void](Read-Host) }
    exit $codigo
}

if (-not ((Test-Path -LiteralPath (Join-Path $raiz 'artisan')) -and (Test-Path -LiteralPath (Join-Path $raiz 'routes\canchavoley.php')))) {
    Escribir "ERROR: este script solo se ejecuta en CanchaVoley\cancha-backend (carpeta revisada: $raiz)." 'Red'; Terminar 1
}
if (-not (Test-Path -LiteralPath (Join-Path $raiz '.env'))) { Escribir 'ERROR: falta el archivo .env.' 'Red'; Terminar 1 }

$php = (Get-Command php.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $php) {
    $php = (Get-ChildItem 'C:\laragon\bin\php\php-8.5*\php.exe' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1).FullName
}
if (-not $php) { Escribir 'ERROR: no se encontro php.exe (Laragon).' 'Red'; Terminar 1 }

if (-not (Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue)) {
    $mysqld = Get-ChildItem 'C:\laragon\bin\mysql\*\bin\mysqld.exe' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $mysqld) { Escribir 'ERROR: MySQL esta apagado. Enciendalo desde Laragon ("Iniciar todo").' 'Red'; Terminar 1 }
    $ini = Join-Path (Split-Path -Parent $mysqld.DirectoryName) 'my.ini'
    Escribir 'Encendiendo MySQL de Laragon...'
    Start-Process -FilePath $mysqld.FullName -ArgumentList ('--defaults-file="{0}"' -f $ini) -WindowStyle Hidden | Out-Null
    for ($i = 0; $i -lt 40; $i++) { Start-Sleep -Seconds 1; if (Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue) { break } }
}

if ($Reiniciar -and -not $Si) {
    Escribir 'Se BORRARAN todas las tablas de la base de demostracion cancha_voley_demo (datos ficticios).' 'Yellow'
    Escribir 'La base real cancha_voley no se toca. Escriba SI para continuar:' 'Yellow'
    if ((Read-Host) -ne 'SI') { Escribir 'Cancelado: no se cambio nada.'; Terminar 0 }
}

Push-Location $raiz
$env:DB_DATABASE = 'cancha_voley_demo'
$ErrorActionPreference = 'Continue'
try {
    Escribir '== Base de demostracion: crear si no existe y aplicar migraciones ==' 'Cyan'
    & $php artisan migrate --force
    if ($LASTEXITCODE -ne 0) { Escribir 'ERROR: fallo la migracion de cancha_voley_demo.' 'Red'; Terminar 1 }
    Escribir '== Datos ficticios ==' 'Cyan'
    $argumentos = @('artisan', 'canchavoley:demo')
    if ($Reiniciar) { $argumentos += '--reiniciar' }
    & $php @argumentos
    if ($LASTEXITCODE -ne 0) { Escribir 'ERROR: no se cargaron los datos de demostracion.' 'Red'; Terminar 1 }
    Escribir ''
    Escribir 'Listo. Ahora: scripts\iniciar_servidor_demo.cmd' 'Green'
} finally {
    Remove-Item Env:\DB_DATABASE -ErrorAction SilentlyContinue
    Pop-Location
}
Terminar 0
