<#
CanchaVoley - probar el servidor y crear las tablas nuevas en la base cancha_voley (PC con Laragon).

USO (PowerShell, dentro de la carpeta CanchaVoley\cancha-backend):

    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\actualizar_base.ps1

  o doble clic en scripts\actualizar_base.cmd

OPCIONES:
    -SoloRevisar        solo muestra que falta (pruebas + migraciones pendientes); no respalda ni migra
    -SinPruebas         no ejecuta php artisan test
    -SinRespaldo        no hace el respaldo con mysqldump (solo si ya tienes un respaldo reciente)
    -Php <ruta>         php.exe a usar (si no esta en el PATH)
    -MysqlDump <ruta>   mysqldump.exe a usar (si no esta en el PATH ni en C:\laragon)

QUE HACE:
    1. Verifica que la carpeta sea CanchaVoley\cancha-backend y que el .env use
       DB_DATABASE=cancha_voley. Con prolech, prolech_db, prolech_pass u otra base se detiene.
    2. php artisan test: las pruebas usan SQLite en memoria, no tocan MySQL.
    3. php artisan migrate:status y migrate --pretend: muestran que se aplicaria, sin cambiar nada.
    4. Respaldo de cancha_voley con mysqldump en respaldos\ (esa carpeta no se sube a Git).
    5. Pide escribir SI y recien entonces ejecuta php artisan migrate.
       Las migraciones de CanchaVoley solo CREAN tablas nuevas; no borran ni cambian las existentes.

La clave de MySQL se lee del .env y no se muestra; para mysqldump se pasa en un archivo temporal
que se borra al terminar.
#>
[CmdletBinding()]
param(
    [switch]$SoloRevisar,
    [switch]$SinPruebas,
    [switch]$SinRespaldo,
    [string]$Php,
    [string]$MysqlDump
)

$ErrorActionPreference = 'Stop'

# --- 1. Carpeta y .env -----------------------------------------------------------------
$raiz = Split-Path -Parent $PSScriptRoot
$rutaEnv = Join-Path $raiz '.env'
$esCanchaVoley = (Test-Path -LiteralPath (Join-Path $raiz 'artisan')) -and
    (Test-Path -LiteralPath (Join-Path $raiz 'routes\canchavoley.php'))
if (-not $esCanchaVoley) {
    Write-Host "ERROR: este script solo se ejecuta en la copia CanchaVoley\cancha-backend." -ForegroundColor Red
    Write-Host "Carpeta revisada: $raiz" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path -LiteralPath $rutaEnv)) {
    Write-Host "ERROR: falta el archivo .env (ver docs\INSTALACION.md, paso 2)." -ForegroundColor Red
    exit 1
}

function Leer-Env([string]$ruta) {
    $valores = @{}
    foreach ($linea in (Get-Content -LiteralPath $ruta -Encoding UTF8)) {
        if ($linea -match '^\s*([A-Z0-9_]+)\s*=\s*(.*)$') {
            $nombre = $Matches[1]
            $valor = $Matches[2].Trim()
            if ($valor.Length -ge 2 -and (($valor.StartsWith('"') -and $valor.EndsWith('"')) -or
                ($valor.StartsWith("'") -and $valor.EndsWith("'")))) {
                $valor = $valor.Substring(1, $valor.Length - 2)
            }
            $valores[$nombre] = $valor
        }
    }
    return $valores
}

$config = Leer-Env $rutaEnv
$conexion = "$($config['DB_CONNECTION'])"
$base = "$($config['DB_DATABASE'])"
if ($base -ne 'cancha_voley') {
    Write-Host "ERROR: el .env apunta a la base '$base'. Este script solo trabaja con cancha_voley." -ForegroundColor Red
    Write-Host "       Corrige DB_DATABASE=cancha_voley en $rutaEnv" -ForegroundColor Red
    exit 1
}
if ($conexion -ne 'mysql' -and $conexion -ne 'mariadb') {
    Write-Host "ERROR: DB_CONNECTION='$conexion'. En la PC del negocio debe ser mysql (Laragon)." -ForegroundColor Red
    exit 1
}

$marca = Get-Date -Format 'yyyyMMdd-HHmmss'
$carpetaRespaldos = Join-Path $raiz 'respaldos'
New-Item -ItemType Directory -Force -Path $carpetaRespaldos | Out-Null
$registro = Join-Path $carpetaRespaldos "actualizar_base-$marca.log"

function Escribir([string]$texto, [string]$color = 'Gray') {
    Write-Host $texto -ForegroundColor $color
    Add-Content -LiteralPath $registro -Value ('[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $texto) -Encoding UTF8
}

function Detener([string]$motivo) {
    Escribir ''
    Escribir "ERROR: $motivo" 'Red'
    Escribir "Registro: $registro" 'Yellow'
    Pop-Location
    exit 1
}

function Buscar([string]$indicado, [string]$comando, [string]$patronLaragon) {
    if ($indicado) {
        if (Test-Path -LiteralPath $indicado) { return (Resolve-Path -LiteralPath $indicado).Path }
        return $null
    }
    $enPath = Get-Command $comando -ErrorAction SilentlyContinue
    if ($enPath) { return $enPath.Source }
    $candidatos = Get-ChildItem -Path $patronLaragon -ErrorAction SilentlyContinue | Sort-Object FullName -Descending
    if ($candidatos) { return @($candidatos)[0].FullName }
    return $null
}

function Artisan([string]$descripcion, [string[]]$argumentos) {
    Escribir ''
    Escribir "== $descripcion ==" 'Cyan'
    Escribir ("php artisan " + ($argumentos -join ' '))
    & $phpExe artisan @argumentos
    $codigo = $LASTEXITCODE
    if ($codigo -ne 0) { Detener "$descripcion fallo (codigo $codigo). El detalle esta arriba." }
    Escribir 'OK' 'Green'
}

Push-Location $raiz
Escribir "CanchaVoley - servidor y base de datos ($marca)" 'Cyan'
Escribir "Carpeta: $raiz"
Escribir "Base: $base en $($config['DB_HOST']):$($config['DB_PORT']) (usuario $($config['DB_USERNAME']))"

# --- PHP -------------------------------------------------------------------------------
$phpExe = Buscar $Php 'php.exe' 'C:\laragon\bin\php\php-8.5*\php.exe'
if (-not $phpExe) { Detener "No se encontro php.exe. Usa -Php C:\laragon\bin\php\<version>\php.exe" }
$versionPhp = "$(& $phpExe -r 'echo PHP_VERSION;')"
Escribir "PHP: $phpExe ($versionPhp)"
if ($versionPhp -notmatch '^8\.(5|[6-9])') {
    Escribir "AVISO: el vendor\ de este proyecto exige PHP 8.5 (es la version que ya usa ProLech en Laragon)." 'Yellow'
}

# --- 2. Pruebas ------------------------------------------------------------------------
if (-not $SinPruebas) {
    Artisan 'Pruebas del servidor (SQLite en memoria, no tocan MySQL)' @('test')
}

# --- 3. Que falta ----------------------------------------------------------------------
Artisan 'Migraciones aplicadas y pendientes' @('migrate:status')
Artisan 'SQL que se ejecutaria (no cambia nada)' @('migrate', '--pretend')
if ($SoloRevisar) {
    Escribir ''
    Escribir 'Solo revision: no se respaldo ni se migro nada.' 'Green'
    Pop-Location
    exit 0
}

# --- 4. Respaldo -----------------------------------------------------------------------
if ($SinRespaldo) {
    Escribir 'AVISO: se omite el respaldo por pedido (-SinRespaldo).' 'Yellow'
} else {
    $dumpExe = Buscar $MysqlDump 'mysqldump.exe' 'C:\laragon\bin\mysql\*\bin\mysqldump.exe'
    if (-not $dumpExe) {
        Detener "No se encontro mysqldump.exe. Usa -MysqlDump <ruta> (en Laragon: C:\laragon\bin\mysql\<version>\bin\mysqldump.exe)."
    }
    $respaldo = Join-Path $carpetaRespaldos "cancha_voley-$marca.sql"
    Escribir ''
    Escribir '== Respaldo de cancha_voley ==' 'Cyan'
    Escribir "mysqldump: $dumpExe"
    $servidor = if ($config['DB_HOST']) { $config['DB_HOST'] } else { '127.0.0.1' }
    $puerto = if ($config['DB_PORT']) { $config['DB_PORT'] } else { '3306' }
    $clave = "$($config['DB_PASSWORD'])" -replace '\\', '\\' -replace '"', '\"'
    $opciones = Join-Path $env:TEMP ('cv-mysql-' + [guid]::NewGuid().ToString('N') + '.cnf')
    $contenido = "[client]`r`nuser=`"$($config['DB_USERNAME'])`"`r`npassword=`"$clave`"`r`nhost=$servidor`r`nport=$puerto`r`n"
    [System.IO.File]::WriteAllText($opciones, $contenido, (New-Object System.Text.UTF8Encoding($false)))
    try {
        & $dumpExe "--defaults-extra-file=$opciones" --single-transaction --no-tablespaces --routines `
            --default-character-set=utf8mb4 "--result-file=$respaldo" --databases cancha_voley
        $codigo = $LASTEXITCODE
    } finally {
        Remove-Item -LiteralPath $opciones -Force -ErrorAction SilentlyContinue
    }
    if ($codigo -ne 0 -or -not (Test-Path -LiteralPath $respaldo)) {
        Detener "El respaldo fallo (codigo $codigo). No se migro nada."
    }
    Escribir ("Respaldo: {0} ({1:N1} KB)" -f $respaldo, ((Get-Item -LiteralPath $respaldo).Length / 1KB)) 'Green'
}

# --- 5. Migrar -------------------------------------------------------------------------
Escribir ''
$respuesta = Read-Host 'Escribe SI para crear las tablas pendientes en cancha_voley (cualquier otra cosa cancela)'
if ("$respuesta".Trim() -ne 'SI') {
    Escribir 'Cancelado: no se aplico ninguna migracion.' 'Yellow'
    Pop-Location
    exit 0
}
Artisan 'Crear las tablas pendientes' @('migrate', '--force')
Artisan 'Estado final' @('migrate:status')
Escribir ''
Escribir 'Listo. Si es la primera vez, crea el negocio y la administradora (docs\INSTALACION.md, paso 5):' 'Green'
Escribir '  php artisan canchavoley:instalar --negocio="..." --cancha="Cancha 1" --admin-nombre="..." --admin-email="..." --admin-password="..."'
Escribir "Registro: $registro"
Pop-Location
exit 0
