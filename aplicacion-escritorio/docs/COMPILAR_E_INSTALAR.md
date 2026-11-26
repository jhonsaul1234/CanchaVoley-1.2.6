# CanchaVoley — compilar e instalar las apps (Windows y Android)

Requisitos (los mismos que ya funcionaron en la etapa 0): JDK 21 (Azul Zulu, con `jpackage`),
Gradle 9.5 (wrapper incluido), Android SDK 36 con build-tools 36.0.0 e internet la primera vez
(Gradle descarga las librerías nuevas, por ejemplo el lector de QR `zxing-android-embedded`).
Primero instala y enciende el servidor (`cancha-backend/docs/INSTALACION.md`).

Todos los comandos se ejecutan desde `CanchaVoley\cancha-app`.

## 1. Forma recomendada: un solo script

Doble clic en `scripts\compilar.cmd`, o en PowerShell:

```
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\compilar.ps1
```

El script (no borra nada, no toca bases de datos ni ProLech):

1. Se detiene si la carpeta no es la copia CanchaVoley.
2. Ejecuta las pruebas: `:composeApp:jvmTest` (88) y `:composeApp:testAndroidHostTest` (62).
3. Arma la app de Windows con su Java recortado y ejecuta `CanchaVoley.exe --autoprueba`
   (ver punto 3). Si algo falla, **no** crea el instalador y dice qué hacer.
4. Crea el instalador `.msi` y el APK de depuración.
5. Deja todo en `dist\`: `CanchaVoley-1.2.5.msi`, `CanchaVoley-1.2.5-debug.apk`,
   `SHA256.txt`, el informe de la autoprueba y el registro `compilacion-FECHA.log`.

Opciones: `-SinPruebas`, `-SoloWindows`, `-SoloAndroid`, `-TodosLosModulos` (ver punto 3) y
`-SinAutoprueba` (último recurso, si la autoprueba fallara por algo que no sea un módulo de Java).
Si no encuentra el Android SDK, hace solo la parte de Windows y lo avisa.

## 2. Comandos sueltos (si prefieres hacerlo a mano)

**No** pases la salida de `gradlew` por `| Tee-Object` ni otras tuberías: el proceso de
Gradle hereda la tubería y la consola se queda esperando.

```
.\gradlew :composeApp:jvmTest --rerun                  # 88 pruebas
.\gradlew :composeApp:testAndroidHostTest --rerun      # 62 pruebas (las comunes)
.\gradlew :composeDesktopApp:run                       # abrir la app de escritorio sin instalar
.\gradlew :composeDesktopApp:createDistributable       # app de Windows sin instalador
.\gradlew :composeDesktopApp:packageMsi                # instalador
.\gradlew :composeAndroidApp:assembleDebug             # APK
```

Los informes de pruebas quedan en `composeApp\build\reports\tests\`. Las de `jvmTest` son las
comunes (dominio, validaciones, navegación, QR, sincronización sin conexión, tablas para Excel,
semana y cliente fijo, WhatsApp, comprobantes PDF y libros de Excel) más las que solo existen en
escritorio (arranque con los módulos reales, token cifrado, migración de la base local, QR a
imagen, autoprueba, archivos de prueba en `composeApp\build\documentos-prueba\`).

**Si el antivirus bloquea la compilación de Windows.** En la PC de desarrollo la protección de
archivos no deja crear `runtime\bin\ucrtbase.dll` en Documentos ni en AppData\Local
(`createRuntimeImage` falla con *AccessDeniedException* y el instalador con el error 1310). Por eso:
`compilar.ps1` arma la app de escritorio en `%TEMP%\CanchaVoley-compilacion` (propiedad
`-PcanchaCarpetaCompilacion`) y jlink deja fuera la copia local de `ucrtbase.dll`, que Windows 10/11
ya trae. A mano: `.\gradlew :composeDesktopApp:packageMsi -PcanchaCarpetaCompilacion=%TEMP%\CanchaVoley-compilacion`.

## 3. App de Windows

- Instalador: `dist\CanchaVoley-1.2.5.msi` (lo copia `compilar.ps1`; Gradle lo deja en
  `%TEMP%\CanchaVoley-compilacion\composeDesktopApp\compose\binaries\main\msi\`).
- La versión 1.2.5 actualiza cualquier versión anterior (1.0.0 a 1.2.4; mismo `upgradeUuid`).
- Se instala para el usuario actual, con acceso en el menú Inicio y en el escritorio.
- La base local queda en `%APPDATA%\CanchaVoley\canchavoley.db` (si existía la `prolech.db` de
  la etapa 0, se renombra sola la primera vez). El servidor por defecto es `http://127.0.0.1:8001`.
- La sesión se guarda cifrada con DPAPI de Windows (solo tu usuario de Windows en esa PC puede
  leerla). Si había una sesión de la versión anterior, se cifra sola al abrir la app.

**Java recortado y autoprueba.** El instalador ya no lleva el Java completo, solo los módulos
que la app usa (12 de 69). Medido al armarlo en Linux: el Java del instalador baja de 162 MB a
85 MB (comprimido, de 55 MB a 28 MB); en Windows la proporción debería ser parecida, el tamaño
real del `.msi` se verá al compilar. Para estar seguros de que no falta nada, la app tiene un
modo sin ventana:

```
%TEMP%\CanchaVoley-compilacion\composeDesktopApp\compose\binaries\main\app\CanchaVoley\CanchaVoley.exe --autoprueba C:\ruta\informe.txt
```

Comprueba con ese mismo Java: base SQLite (en una carpeta temporal, no toca la real), lectura
de preferencias, cifrado DPAPI, conexiones HTTPS, una petición HTTP con JSON en 127.0.0.1, zona
horaria y dibujo de una pantalla. Devuelve 0 si todo pasó y deja el informe en el archivo
indicado. Si alguna vez fallara por un módulo faltante, se compila con el Java completo:

```
.\scripts\compilar.ps1 -TodosLosModulos
.\gradlew :composeDesktopApp:packageMsi -PcanchaTodosLosModulos=true
```

## 4. App Android

- APK: `composeAndroidApp\build\outputs\apk\debug\composeAndroidApp-debug.apk`
  (el script lo copia como `dist\CanchaVoley-1.2.5-debug.apk`).
- Instalar: copiar el APK al celular y abrirlo (permitir «instalar apps desconocidas»), o
  con el celular conectado por USB: `adb install -r dist\CanchaVoley-1.2.2-debug.apk`.
- `applicationId` = `pe.canchavoley.app`: **no** reemplaza a ProLech si también está instalada.
- La versión debug permite HTTP en la red local. En la pantalla de inicio de sesión toca
  **Servidor** y escribe la IP de la PC, por ejemplo `http://192.168.1.10:8001` (en el
  emulador de Android Studio sirve `http://10.0.2.2:8001`).
- La primera vez que se lee un QR con la cámara, Android pide el permiso de cámara.
- La sesión se guarda cifrada con una clave del Android Keystore; la de la versión anterior se
  cifra sola al abrir la app.
- Para publicar una versión release (solo HTTPS, firmada) hace falta un servidor con
  certificado y un archivo de firma propio (`.jks`, que nunca se sube a Git) configurado en
  `composeAndroidApp/build.gradle.kts`. No se creó ninguno: es una decisión del negocio.

## 5. Prueba de punta a punta sugerida

1. Servidor encendido con `--host=0.0.0.0`; administradora creada con `canchavoley:instalar`.
2. PC: entrar como administradora → Tarifas y horarios → cargar horario y tarifas reales.
3. PC: Modo llamada → cliente nuevo → reserva de 1 h 30 → registrar adelanto por Yape.
4. Celular: crear cuenta de cliente → Reservar → enviar solicitud → en la PC aparece en el
   panel → Aprobar → en el celular la reserva pasa a confirmada y aparece el QR.
5. QR: en otro celular con cuenta de recepción → Panel principal → **Escanear QR con la
   cámara** → apuntar al QR del cliente → se abre su reserva → **Llegó el cliente**. En la PC,
   el lector USB escribe el código en el mismo campo (o se escribe a mano) y se presiona Verificar.
6. Detalle de la reserva (PC, administradora) → **Precio y notas** → cambiar el precio
   acordado → queda en el historial. **Compartir** (celular) o **Copiar** (PC) el comprobante.
7. Sin conexión: apagar el servidor (o desconectar la red) → registrar una reserva en la PC →
   queda «Por sincronizar» y el contador de pendientes sube → encender el servidor → en
   menos de un minuto queda confirmada y el contador vuelve a 0.
8. Conflicto: con la PC sin conexión, reservar 18:00–19:00; desde el celular (con conexión)
   pedir el mismo horario y aprobarlo; reconectar la PC → su reserva queda **Rechazada** con
   el motivo y el aviso para ofrecer otro horario.
9. Sincronización → si una operación queda con error, **Reintentar ahora** o **Descartar**
   (descartar solo quita ese cambio de este equipo; lo demás sigue enviándose).
10. Pagos y caja → elegir el día → **Copiar cierre** (PC) o **Compartir cierre** (celular);
    en la PC, **Para Excel** y pegar en Excel con Ctrl+V.
11. Calendario → **Semana** → tocar una reserva o **Ver día**. Reportes → **Por mes** →
    **Copiar para Excel**.
12. Modo llamada → «Repetir: 4 semanas» → se registran 4 reservas (si una semana está ocupada,
    el aviso lo dice). Detalle de una reserva → **Recordar por WhatsApp**.
