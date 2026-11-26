<#
CanchaVoley - encender el servidor en esta PC (Laragon).

USO:
    doble clic en scripts\iniciar_servidor.cmd        (base real cancha_voley)
    doble clic en scripts\iniciar_servidor_demo.cmd   (base de demostracion, datos ficticios)

  o desde PowerShell, dentro de CanchaVoley\cancha-backend:
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\iniciar_servidor.ps1 [-Red] [-Demo] [-Puerto 8001]

OPCIONES:
    -Red      tambien atiende a los celulares de la misma red Wi-Fi (escucha en 0.0.0.0).
              Si Windows pregunta por el firewall, permitir solo en "redes privadas".
    -Demo     usa la base de demostracion cancha_voley_demo (preparar antes con preparar_demo.cmd).
    -Puerto   puerto del servidor (por defecto 8001; la app de escritorio usa http://127.0.0.1:8001).

QUE HACE (no cambia datos):
    1. Revisa que la carpeta sea CanchaVoley\cancha-backend y que el .env use la base cancha_voley.
    2. Busca el PHP 8.5 de Laragon y enciende el MySQL de Laragon si esta apagado.
    3. Avisa si el puerto ya esta ocupado o si faltan migraciones (usar actualizar_base.cmd).
    4. Muestra la direccion para la app de escritorio y, con -Red, la de los celulares.
    5. Inicia "php artisan serve". Para apagar el servidor: Ctrl+C o cerrar la ventana.
#>
[CmdletBinding()]
param(
    [switch]$Red,
    [switch]$Demo,
    [int]$Puerto = 8001
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

function Escribir([string]$texto, [string]$color = 'Gray') { Write-Host $texto -ForegroundColor $color }
function Detener([string]$motivo) {
    Escribir ''
    Escribir "ERROR: $motivo" 'Red'
    Escribir 'Presione Enter para cerrar.' 'Yellow'
    [void](Read-Host)
    exit 1
}

# --- 1. Carpeta y .env -------------------------------------------------------------------
if (-not ((Test-Path -LiteralPath (Join-Path $raiz 'artisan')) -and (Test-Path -LiteralPath (Join-Path $raiz 'routes\canchavoley.php')))) {
    Detener "este script solo se ejecuta en CanchaVoley\cancha-backend (carpeta revisada: $raiz)."
}
$rutaEnv = Join-Path $raiz '.env'
if (-not (Test-Path -LiteralPath $rutaEnv)) { Detener 'falta el archivo .env (ver docs\INSTALACION.md, paso 2).' }
$baseEnv = ''
foreach ($linea in (Get-Content -LiteralPath $rutaEnv -Encoding UTF8)) {
    if ($linea -match '^\s*DB_DATABASE\s*=\s*"?([^"\s]*)"?') { $baseEnv = $Matches[1] }
}
if ($baseEnv -ne 'cancha_voley') { Detener "el .env apunta a la base '$baseEnv'. Debe ser DB_DATABASE=cancha_voley." }
if (Test-Path -LiteralPath (Join-Path $raiz 'bootstrap\cache\config.php')) {
    Escribir 'AVISO: hay configuracion en cache; se limpia para que el servidor lea el .env actual.' 'Yellow'
}
$base = if ($Demo) { 'cancha_voley_demo' } else { 'cancha_voley' }

# --- 2. PHP y MySQL -----------------------------------------------------------------------
$php = (Get-Command php.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $php) {
    $php = (Get-ChildItem 'C:\laragon\bin\php\php-8.5*\php.exe' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1).FullName
}
if (-not $php) { Detener 'no se encontro php.exe (Laragon: C:\laragon\bin\php\php-8.5...).' }

function MySQL-Activo { return [bool](Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue) }
if (-not (MySQL-Activo)) {
    $mysqld = Get-ChildItem 'C:\laragon\bin\mysql\*\bin\mysqld.exe' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $mysqld) { Detener 'MySQL esta apagado y no se encontro el de Laragon. Encienda MySQL desde Laragon ("Iniciar todo").' }
    $ini = Join-Path (Split-Path -Parent $mysqld.DirectoryName) 'my.ini'
    Escribir "Encendiendo MySQL de Laragon ($($mysqld.FullName))..."
    Start-Process -FilePath $mysqld.FullName -ArgumentList ('--defaults-file="{0}"' -f $ini) -WindowStyle Hidden | Out-Null
    $listo = $false
    for ($i = 0; $i -lt 40 -and -not $listo; $i++) { Start-Sleep -Seconds 1; $listo = MySQL-Activo }
    if (-not $listo) { Detener 'MySQL no encendio en 40 segundos. Enciendalo desde Laragon ("Iniciar todo").' }
}
Escribir 'MySQL: encendido' 'Green'

# --- 3. Puerto y migraciones ---------------------------------------------------------------
$ocupado = Get-NetTCPConnection -State Listen -LocalPort $Puerto -ErrorAction SilentlyContinue | Select-Object -First 1
if ($ocupado) {
    $proceso = Get-Process -Id $ocupado.OwningProcess -ErrorAction SilentlyContinue
    Detener ("el puerto $Puerto ya esta en uso por $($proceso.ProcessName) (PID $($ocupado.OwningProcess)). " +
        'Si es un servidor anterior de CanchaVoley, cierre su ventana o finalice ese php.exe en el Administrador de tareas.')
}

Push-Location $raiz
try {
    if ($Demo) { $env:DB_DATABASE = 'cancha_voley_demo' } else { Remove-Item Env:\DB_DATABASE -ErrorAction SilentlyContinue }
    $anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    if (Test-Path -LiteralPath (Join-Path $raiz 'bootstrap\cache\config.php')) { & $php artisan config:clear | Out-Null }
    $estado = & $php artisan migrate:status 2>&1 | ForEach-Object { "$_" }
    $codigo = $LASTEXITCODE
    $ErrorActionPreference = $anterior
    if ($codigo -ne 0) {
        if ($Demo) { Detener "no se pudo leer la base $base. Prepare la demostracion con scripts\preparar_demo.cmd." }
        Detener ("no se pudo leer la base ${base}:`n" + (($estado | Select-Object -Last 5) -join "`n"))
    }
    $pendientes = @($estado | Where-Object { $_ -match 'Pending' })
    if ($pendientes.Count -gt 0) {
        Escribir ("AVISO: hay {0} migracion(es) pendiente(s) en {1}. Ejecute scripts\actualizar_base.cmd." -f $pendientes.Count, $base) 'Yellow'
    }

    # --- 4. Direcciones ---------------------------------------------------------------------
    $escucha = if ($Red) { '0.0.0.0' } else { '127.0.0.1' }
    Escribir ''
    Escribir '==============================================================' 'Cyan'
    Escribir (" CanchaVoley - servidor encendido  (base: $base" + $(if ($Demo) { ', DATOS FICTICIOS' } else { '' }) + ')') 'Cyan'
    Escribir '==============================================================' 'Cyan'
    Escribir " App de escritorio en esta PC:  http://127.0.0.1:$Puerto"
    if ($Red) {
        $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.InterfaceAlias -notlike 'vEthernet*' }
        foreach ($ip in $ips) { Escribir (" Celulares en la misma Wi-Fi:    http://{0}:{1}   ({2})" -f $ip.IPAddress, $Puerto, $ip.InterfaceAlias) }
        Escribir ' Si Windows pregunta por el firewall, permita el acceso solo en redes privadas.' 'Yellow'
    } else {
        Escribir ' (Para celulares use iniciar_servidor.ps1 -Red)'
    }
    Escribir ' Para apagar el servidor: Ctrl+C o cierre esta ventana.'
    Escribir ''

    # --- 5. Servidor ---------------------------------------------------------------------------
    # --no-reload: el proceso del servidor recibe DB_DATABASE (con -Demo) en lugar de volver a leer solo el .env.
    & $php artisan serve "--host=$escucha" "--port=$Puerto" --no-reload
} finally {
    Remove-Item Env:\DB_DATABASE -ErrorAction SilentlyContinue
    Pop-Location
}
