<#
CanchaVoley - compilar y probar las apps en la PC con Windows.

USO (PowerShell, dentro de la carpeta CanchaVoley\cancha-app):

    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\compilar.ps1

  o doble clic en scripts\compilar.cmd

OPCIONES:
    -SinPruebas        no ejecuta las pruebas automaticas
    -SoloWindows       solo el instalador de Windows (.msi)
    -SoloAndroid       solo el APK
    -TodosLosModulos   el instalador lleva el Java completo (pesa mas). Usar solo si la
                       autoprueba avisa que falta algo en el Java recortado.
    -SinAutoprueba     no ejecuta la autoprueba (ultimo recurso, si fallara por otra causa;
                       revisa luego la app instalada con CanchaVoley.exe --autoprueba)

QUE HACE (no borra nada, no toca bases de datos, no toca ProLech):
    1. Revisa que esta carpeta sea la copia CanchaVoley y busca Java y el Android SDK.
    2. Pruebas: :composeApp:jvmTest y :composeApp:testAndroidHostTest.
    3. Windows: arma la app con su Java recortado (en %TEMP%\CanchaVoley-compilacion, fuera de
       Documentos), ejecuta "CanchaVoley.exe --autoprueba"
       (base SQLite, token cifrado con DPAPI, HTTPS, JSON, zona horaria y dibujo de pantallas)
       y, si todo pasa, crea el instalador .msi.
    4. Android: APK de depuracion (se instala copiandolo al celular).
    5. Copia los resultados a la carpeta dist\ con su SHA-256 y deja un registro
       dist\compilacion-FECHA.log

La salida de Gradle NO se pasa por tuberias (| Tee-Object, etc.): el proceso de Gradle
hereda la tuberia y la consola se queda esperando. Por eso se muestra directo en pantalla.
#>
[CmdletBinding()]
param(
    [switch]$SinPruebas,
    [switch]$SoloWindows,
    [switch]$SoloAndroid,
    [switch]$TodosLosModulos,
    [switch]$SinAutoprueba
)

$ErrorActionPreference = 'Stop'

# --- 1. Carpeta correcta -------------------------------------------------------------
$raiz = Split-Path -Parent $PSScriptRoot
$ajustes = Join-Path $raiz 'settings.gradle.kts'
$esCanchaVoley = (Test-Path -LiteralPath $ajustes) -and
    (Select-String -LiteralPath $ajustes -Pattern 'rootProject.name = "CanchaVoley"' -SimpleMatch -Quiet) -and
    (Test-Path -LiteralPath (Join-Path $raiz 'composeDesktopApp'))
if (-not $esCanchaVoley) {
    Write-Host "ERROR: este script solo se ejecuta en la copia CanchaVoley\cancha-app (no en ProLech)." -ForegroundColor Red
    Write-Host "Carpeta revisada: $raiz" -ForegroundColor Red
    exit 1
}
if ($SoloWindows -and $SoloAndroid) {
    Write-Host "ERROR: usa -SoloWindows o -SoloAndroid, no los dos." -ForegroundColor Red
    exit 1
}

$dist = Join-Path $raiz 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$marca = Get-Date -Format 'yyyyMMdd-HHmmss'
$registro = Join-Path $dist "compilacion-$marca.log"
$inicio = Get-Date

function Escribir([string]$texto, [string]$color = 'Gray') {
    Write-Host $texto -ForegroundColor $color
    Add-Content -LiteralPath $registro -Value ('[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $texto) -Encoding UTF8
}

function Detener([string]$motivo) {
    Escribir ''
    Escribir "ERROR: $motivo" 'Red'
    Escribir "Registro completo: $registro" 'Yellow'
    Pop-Location
    exit 1
}

function Texto-De([scriptblock]$comando) {
    # Ejecuta un comando corto (java -version) y devuelve su salida como texto, aunque escriba en stderr.
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return ((& $comando 2>&1) | ForEach-Object { "$_" }) -join ' / ' }
    catch { return "(no se pudo ejecutar: $($_.Exception.Message))" }
    finally { $ErrorActionPreference = $anterior }
}

function Gradle([string]$descripcion, [string[]]$tareas) {
    $argumentos = @($tareas)
    if ($TodosLosModulos) { $argumentos += '-PcanchaTodosLosModulos=true' }
    Escribir ''
    Escribir "== $descripcion ==" 'Cyan'
    Escribir ("gradlew " + ($argumentos -join ' '))
    $t = Get-Date
    # Misma consola y sin tuberias; se espera solo a gradlew (no al demonio de Gradle, que sigue vivo).
    $proceso = Start-Process -FilePath $gradlew -ArgumentList $argumentos -WorkingDirectory $raiz -NoNewWindow -PassThru
    $null = $proceso.Handle
    $proceso.WaitForExit()
    $codigo = $proceso.ExitCode
    $minutos = [math]::Round(((Get-Date) - $t).TotalMinutes, 1)
    if ($codigo -ne 0) {
        Detener "$descripcion fallo (codigo $codigo, $minutos min). El detalle esta en los mensajes de Gradle de arriba."
    }
    Escribir "OK ($minutos min)" 'Green'
}

function Megas([string]$ruta) {
    return '{0:N1} MB' -f ((Get-Item -LiteralPath $ruta).Length / 1MB)
}

Push-Location $raiz
$gradlew = Join-Path $raiz 'gradlew.bat'
# La app de escritorio se compila en la carpeta temporal del usuario: en Documentos y en AppData\Local la
# proteccion de archivos de la PC (antivirus) no deja crear runtime\bin\ucrtbase.dll y createRuntimeImage
# fallaba con AccessDeniedException; en %TEMP% si se puede. El .msi se copia luego a dist\.
$carpetaCompilacion = Join-Path $env:TEMP 'CanchaVoley-compilacion'
$salidaEscritorio = Join-Path $carpetaCompilacion 'composeDesktopApp'
$propiedadCarpeta = "-PcanchaCarpetaCompilacion=$carpetaCompilacion"

Escribir "CanchaVoley - compilacion $marca" 'Cyan'
Escribir "Carpeta: $raiz"

# --- Java ------------------------------------------------------------------------------
if ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    Escribir "JAVA_HOME: $env:JAVA_HOME"
} else {
    $comandoJava = Get-Command java.exe -ErrorAction SilentlyContinue
    $java = if ($comandoJava) { $comandoJava.Source } else { $null }
}
if ($java -and (Test-Path -LiteralPath $java)) {
    Escribir ("Java: " + (Texto-De { & $java -version 2>&1 }))
} else {
    Escribir "AVISO: no se encontro java.exe (ni JAVA_HOME). Gradle buscara un JDK 21 por su cuenta;" 'Yellow'
    Escribir "       si falla, instala el JDK 21 que se uso en la etapa 0 y define JAVA_HOME." 'Yellow'
}

# --- Android SDK -----------------------------------------------------------------------
$sdk = $null
$propiedades = Join-Path $raiz 'local.properties'
if (Test-Path -LiteralPath $propiedades) {
    $linea = Select-String -LiteralPath $propiedades -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($linea) { $sdk = $linea.Matches[0].Groups[1].Value.Trim() -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
$hayAndroid = [bool]($sdk -and (Test-Path -LiteralPath $sdk))
if ($hayAndroid) {
    Escribir "Android SDK: $sdk"
} elseif ($SoloAndroid) {
    Detener "No se encontro el Android SDK (local.properties sdk.dir, ANDROID_HOME o ANDROID_SDK_ROOT)."
} elseif (-not $SoloWindows) {
    Escribir "AVISO: no se encontro el Android SDK; se omiten el APK y las pruebas de Android." 'Yellow'
}
$conWindows = -not $SoloAndroid
$conAndroid = $hayAndroid -and -not $SoloWindows

# --- 2. Pruebas ------------------------------------------------------------------------
if (-not $SinPruebas) {
    $tareas = @(':composeApp:jvmTest', '--rerun')
    if ($conAndroid) { $tareas += @(':composeApp:testAndroidHostTest', '--rerun') }
    Gradle 'Pruebas automaticas' $tareas
    Escribir "Informes: composeApp\build\reports\tests\"
}

$resultados = @()

# --- 3. Windows ------------------------------------------------------------------------
if ($conWindows) {
    Gradle 'App de Windows con su Java recortado' @(':composeDesktopApp:createDistributable', $propiedadCarpeta)

    $exe = Join-Path $salidaEscritorio 'compose\binaries\main\app\CanchaVoley\CanchaVoley.exe'
    if (-not (Test-Path -LiteralPath $exe)) { Detener "No se encontro $exe" }
    $informe = Join-Path $dist "autoprueba-$marca.txt"
    Escribir ''
    if ($SinAutoprueba) {
        Escribir 'AVISO: autoprueba omitida por pedido (-SinAutoprueba).' 'Yellow'
    } else {
        Escribir '== Autoprueba del Java recortado (CanchaVoley.exe --autoprueba) ==' 'Cyan'
        $proceso = Start-Process -FilePath $exe -ArgumentList @('--autoprueba', ('"{0}"' -f $informe)) -PassThru
        $null = $proceso.Handle
        if (-not $proceso.WaitForExit(180000)) {
            Stop-Process -Id $proceso.Id -Force
            Detener 'La autoprueba no termino en 3 minutos.'
        }
        if (Test-Path -LiteralPath $informe) {
            Get-Content -LiteralPath $informe -Encoding UTF8 | ForEach-Object { Escribir "   $_" }
        }
        if ($proceso.ExitCode -ne 0) {
            if ($TodosLosModulos) {
                Detener ("La autoprueba fallo (codigo $($proceso.ExitCode)) incluso con el Java completo. Revisa $informe " +
                    "(como ultimo recurso: .\scripts\compilar.ps1 -SinAutoprueba).")
            }
            Detener ("La autoprueba fallo (codigo $($proceso.ExitCode)). Revisa $informe. Si falta un modulo de Java: " +
                ".\scripts\compilar.ps1 -TodosLosModulos ; si la falla es otra: .\scripts\compilar.ps1 -SinAutoprueba")
        }
        Escribir 'Autoprueba OK' 'Green'
    }

    Gradle 'Instalador de Windows (.msi)' @(':composeDesktopApp:packageMsi', $propiedadCarpeta)
    $carpetaMsi = Join-Path $salidaEscritorio 'compose\binaries\main\msi'
    $msi = Get-ChildItem -LiteralPath $carpetaMsi -Filter '*.msi' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $msi) { Detener "No se encontro el .msi en $carpetaMsi" }
    $version = (Select-String -LiteralPath (Join-Path $raiz 'composeDesktopApp\build.gradle.kts') -Pattern 'packageVersion\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
    $destino = Join-Path $dist "CanchaVoley-$version.msi"
    Copy-Item -LiteralPath $msi.FullName -Destination $destino -Force
    $resultados += $destino
}

# --- 4. Android ------------------------------------------------------------------------
if ($conAndroid) {
    Gradle 'App Android (APK de depuracion)' @(':composeAndroidApp:assembleDebug')
    $apk = Join-Path $raiz 'composeAndroidApp\build\outputs\apk\debug\composeAndroidApp-debug.apk'
    if (-not (Test-Path -LiteralPath $apk)) { Detener "No se encontro $apk" }
    $version = (Select-String -LiteralPath (Join-Path $raiz 'composeAndroidApp\build.gradle.kts') -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
    $destino = Join-Path $dist "CanchaVoley-$version-debug.apk"
    Copy-Item -LiteralPath $apk -Destination $destino -Force
    $resultados += $destino
}

# --- 5. Resumen ------------------------------------------------------------------------
Escribir ''
Escribir '== Resultado ==' 'Cyan'
if ($resultados.Count -eq 0) {
    Escribir 'No se genero ningun instalador (solo pruebas).'
} else {
    $sumas = Join-Path $dist 'SHA256.txt'
    $lineas = foreach ($archivo in $resultados) {
        $hash = (Get-FileHash -LiteralPath $archivo -Algorithm SHA256).Hash.ToLower()
        Escribir ("{0}  ({1})" -f $archivo, (Megas $archivo)) 'Green'
        "$hash  $(Split-Path -Leaf $archivo)"
    }
    Set-Content -LiteralPath $sumas -Value $lineas -Encoding ASCII
    Escribir "SHA-256: $sumas"
}
$total = [math]::Round(((Get-Date) - $inicio).TotalMinutes, 1)
Escribir "Listo en $total min. Registro: $registro" 'Green'
Pop-Location
exit 0
