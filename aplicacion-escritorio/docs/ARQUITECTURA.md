# CanchaVoley — documentación técnica

## Visión general

```
 App Windows (Compose Desktop)  ─┐                         ┌─ MySQL cancha_voley
                                 ├── HTTP JSON /api/cv ──► Laravel (cancha-backend)
 App Android (Compose, KMP)     ─┘                         └─ reglas: choques, pagos, permisos
      │ SQLite local (SQLDelight)
      └─ cola de operaciones offline → /api/cv/sync/operaciones
         descarga de cambios       ← /api/cv/sync/cambios
```

Las dos apps comparten el mismo código Kotlin (`composeApp/src/commonMain`). El servidor es
la **fuente de verdad**: valida cada reserva y cada pago, también los creados sin conexión.

El proyecto nació de una copia de ProLech. El código de ProLech (acopios, GPS, rutas,
`com.example.prolechc`) ya se retiró; solo quedan sus tablas locales, sin consultas, para no
cambiar las bases SQLite ya instaladas (ver «Base local»). Objetivos: Android y JVM de
escritorio (se quitaron los de iOS, que no se usaban).

## Módulos del repositorio `cancha-app`

| Módulo | Contenido |
|---|---|
| `composeApp` | Biblioteca KMP con toda la app (`pe.canchavoley.app`): `commonMain` (lógica, pantallas, base local, red), `jvmMain` (Windows) y `androidMain` (Android). |
| `composeDesktopApp` | Programa de Windows (`pe.canchavoley.escritorio.MainKt`) e instaladores `.msi` / `.exe` con un Java recortado. |
| `composeAndroidApp` | APK (`applicationId pe.canchavoley.app`, versión 1.2.5; funciona desde Android 7.0, API 24). |

### Paquetes (`composeApp/src/commonMain/kotlin/pe/canchavoley/app`)

| Paquete | Archivos | Responsabilidad |
|---|---|---|
| `datos` | `Dtos.kt`, `ApiCancha.kt`, `Json.kt` | Contrato con la API; `Resultado` = `Ok` / `Error(codigo, mensaje)` / `SinConexion`. |
| | `AlmacenLocal.kt` | SQLite: reservas, clientes, pagos, catálogo, sesión y cola de operaciones. |
| | `MotorSync.kt` | Envío de la cola, descarga de cambios, estado y avisos de sincronización. |
| | `Repositorio.kt` | Lo que usan las pantallas: primero en línea y, sin conexión, guarda en local y encola. |
| | `GestorSesion.kt` | Login por dispositivo, sesión guardada para entrar sin internet, cambio de usuario seguro. |
| `dominio` | `Dinero.kt`, `Fechas.kt`, `Telefono.kt` | Céntimos (sin errores de redondeo), zona `America/Lima`, teléfonos en el mismo formato que el servidor. |
| | `DisponibilidadLocal.kt` | Turnos, libres, choques (con margen), horas de inicio libres, sugerencias y precio con el catálogo descargado. |
| | `CodigoQr.kt`, `LecturaQr.kt` | Generador QR propio (modo byte, corrección M) y lectura tolerante del código (`CANCHAVOLEY:<código>`, minúsculas, espacios, lector USB con otra distribución de teclado). |
| | `Validaciones.kt` | Reglas de todos los formularios (acceso, registro, servidor, cliente, reserva, pago, horario, tarifas, cierres), sin Compose para poder probarlas. |
| | `Navegacion.kt` | Menú de cada rol y qué hace «atrás». |
| | `Comprobante.kt` | Texto del comprobante de una reserva y del cierre de caja (para copiar o compartir). |
| | `Exportar.kt` | Tablas para pegar en Excel (columnas con tabulaciones, montos sin «S/»): reporte y movimientos de caja. |
| | `Semana.kt` | Semanas de lunes a domingo (vista semanal), series de cliente fijo (misma hora cada semana) y meses sin días futuros (reporte por mes). |
| | `WhatsApp.kt` | Enlace `wa.me` y textos (recordatorio, ofrecer otro horario) solo con datos de la reserva; no usa cuentas ni servicios de pago. |
| `plataforma` | `Sesion.kt` | `TokenStorage` y `TokenEnCache` (el token se descifra una vez al iniciar y queda en memoria). |
| | `Servidor.kt` | `ApiConfig` (dirección del servidor), `ConfigLocal` y `HttpClientFactory` (token, tiempos de espera, 401 → sesión vencida). |
| | `Conectividad.kt` | `MonitorServidor`: «en línea» = el servidor responde (TCP al host y puerto configurados). |
| | `Plataforma.kt` | `expect` de cada plataforma (base SQLite, módulo Koin) y módulo base. |
| | `LectorQr.kt`, `Compartir.kt` | Cámara (Android) y compartir texto (Android); en Windows no existen y la pantalla usa el lector USB y el portapapeles. |
| `ui` | `Shell.kt`, `Tema.kt`, `Iconos.kt`, `Componentes.kt`, `Pantalla*.kt`, `Acceso.kt`, `FormularioReserva.kt` | Navegación por rol (menú lateral agrupado ≥ 840 dp; barra superior e inferior en pantallas angostas), tema, 32 íconos vectoriales propios, componentes y pantallas. |
| `di` | `ModuloCanchaVoley.kt` | Koin y arranque (`iniciarCanchaVoley`). |

Propio de cada plataforma:

| | Windows (`jvmMain`) | Android (`androidMain`) |
|---|---|---|
| Base local | `%APPDATA%\CanchaVoley\canchavoley.db` | `canchavoley.db` de la app |
| Token | DPAPI de Windows (JNA `Crypt32Util`) en las preferencias del usuario | AES-GCM con clave en el Android Keystore, en DataStore |
| Conexión | `MonitorServidor` | `MonitorServidor` + `NetworkCallback` (revisa apenas cambia la red) |
| QR | Lector USB o teclado | Cámara (`zxing-android-embedded`, `ScanContract`) |
| Compartir | Portapapeles | Hoja de compartir de Android |
| Extra | `Autoprueba.kt` (`--autoprueba`) | `CanchaVoleyApplication`, `MainActivity` |

## Base local (SQLite, SQLDelight)

Paquete generado `pe.edu.upeu.prolech.db` (nombre heredado; cambiarlo no aporta y obligaría a
tocar todas las importaciones). Archivo `CanchaVoley.sq`, esquema versión 6 (`5.sqm` actualiza
bases de la versión 5). `Config.sq` guarda la dirección del servidor. `Acopio.sq`,
`Ganadero.sq`, `PuntoGps.sq` y `RutaCache.sq` son tablas de ProLech sin uso y sin consultas:
se conservan para que las instalaciones de la etapa 0 y las migraciones 1–5 no cambien.

| Tabla | Uso |
|---|---|
| `CvDato` | Clave/valor: sesión (sin token), catálogo, cursor de sincronización, id del dispositivo. |
| `CvCliente` | Clientes (JSON + teléfono y nombre para buscar). |
| `CvReserva` | Reservas (JSON + `inicioMs`, `finMs`, `estado`, `estadoSync`, `errorSync`). |
| `CvPago` | Pagos por reserva. |
| `CvOperacion` | Cola: `opUuid`, `tipo`, `entidadUuid`, `datosJson`, `orden`, `intentos`, `ultimoError`. |

`estadoSync`: `SINCRONIZADO`, `PENDIENTE` (cambio local sin enviar) o `RECHAZADA`.
La base de la etapa 0 (`prolech.db`) se renombra sola a `canchavoley.db` con sus archivos
`-journal`, `-wal` y `-shm`.

## Funcionamiento sin conexión

1. **Crear o cambiar** (reserva, cliente, pago, precio y notas, horario, cancelación…): la app
   genera el `uuid` (`kotlin.uuid.Uuid`), guarda en `Cv*` y agrega una operación a
   `CvOperacion` con su propio `op_uuid`.
   - Una reserva nueva queda con estado local **`por_sincronizar`**, nunca `confirmada`.
   - Antes de guardarla se revisa, con lo que el equipo conoce, que no choque con otra
     reserva ni con un cierre y que esté dentro del horario (`DisponibilidadLocal`).
2. **Enviar** (`MotorSync.enviar`): apenas el servidor vuelve a responder, cada 60 s y después
   de cada operación local. Envía hasta 50 operaciones por lote **en el orden en que se hicieron**.
   - `aplicada` → guarda lo que devolvió el servidor y borra la operación.
   - `rechazada` → guarda la reserva como `rechazada` (con el motivo), borra la operación y
     muestra un aviso.
   - `error` o sin red → la operación se conserva **con el mismo `op_uuid`** y se reintenta;
     el servidor reconoce los repetidos y responde lo mismo, así que no hay duplicados.
   - Una operación que falla siempre (por ejemplo, datos que el servidor nunca aceptará) se
     puede **descartar** desde Sincronización: la reserva local queda `rechazada` (o el pago
     local se borra) y las demás operaciones siguen su curso.
   - 401 → sesión vencida: la app vuelve al acceso con el aviso; la cola se conserva.
3. **Descargar** (`MotorSync.descargar`): `GET /sync/cambios?desde=<cursor>`; guarda catálogo,
   clientes, reservas y pagos, excepto los que tienen cambios locales pendientes (para no
   pisarlos antes de enviarlos). El cursor tiene 120 s de solapamiento.
4. **Saber si hay servidor** (`MonitorServidor`): abre una conexión TCP al host y puerto del
   servidor configurado; cada 15 s en línea y, sin conexión, con espera creciente hasta 60 s.
   En Android, un cambio de red adelanta la revisión (con 1 s de pausa para juntar avisos).

### Resolución de conflictos

- No hay «gana la última escritura»: el servidor aplica sus reglas a cada operación.
- Dos equipos que reservan el mismo horario sin conexión: el primero que sincroniza se queda
  con el horario; el segundo recibe `rechazada` con el motivo y la app lo muestra.
- Un pago hecho sin conexión siempre se registra (es dinero recibido); si su reserva terminó
  rechazada o cancelada queda como **saldo a favor** del cliente.
- Un cliente creado sin conexión con un teléfono que ya existía se une al existente
  (`unido_a_existente`) y la app reemplaza el registro provisional. Lo mismo si dos equipos lo
  registran a la vez (el índice único del servidor decide).
- El reenvío simultáneo de una misma reserva o pago (por ejemplo, el original y su reintento)
  recibe el mismo registro, nunca un 409 contra sí mismo.
- Cambiar de usuario en un equipo con operaciones pendientes está bloqueado; al cambiar de
  usuario se borran los datos de negocio locales del anterior.
- Si la base del servidor se vuelve a crear (por ejemplo, al reiniciar la base de demostración),
  la sesión trae otra marca (`negocio.creado_en`): al volver a entrar, la app descarta su copia
  local y la descarga de nuevo, salvo que tenga operaciones sin enviar (`SesionTest`).
- En Windows solo puede haber una ventana de CanchaVoley abierta por usuario (`InstanciaUnica`,
  bloqueo de `%APPDATA%\CanchaVoley\instancia.lock`): dos copias compartirían la base local y la
  cola. La segunda avisa «CanchaVoley ya está abierto» y se cierra.
- Errores inesperados (1.2.2): un error al sincronizar ya no cierra la app; se muestra como
  «Error al sincronizar» y se reintenta en la siguiente vuelta. Todo error no controlado deja un
  informe (`ultimo_error.txt`: versión, plataforma, dónde ocurrió y la traza, sin contraseñas) en la
  carpeta de datos de la app; al abrirla de nuevo se muestra para compartirlo (Android) o guardarlo
  en Documentos (Windows), y la sincronización espera hasta pulsar «Continuar» (`RegistroFallos`).
- En Windows, pegar o copiar cuando otro programa tiene ocupado el portapapeles ya no cierra la
  ventana (DEF-16): `PortapapelesSeguro` protege pegar y copiar en los campos de texto y
  `GestorPortapapelesSeguro` los botones «Copiar» (caja, reportes y comprobante), que si el
  portapapeles sigue ocupado avisan «No se pudo copiar…» en vez de cerrar la app (1.2.4).
- Si un copiado falla, Java igual se quedaba con ese texto como propio y pegar en la app lo devolvía en
  lugar de lo que había en el portapapeles de Windows (DEF-19). Desde la 1.2.5 `ContenidoPortapapelesJava`
  lo olvida; el lanzador abre `java.awt.datatransfer` (`--add-opens`) y la autoprueba lo comprueba.
- Android 7.0 y 7.1 (API 24-25) no traen `java.time`, que usa `kotlinx-datetime`: la app activa la
  desugarización de la biblioteca de Java (`coreLibraryDesugaring`, `desugar_jdk_libs`). Sin ella la
  app se cerraba al usar las fechas (DEF-15, corregido en la 1.2.3; comprobado en BlueStacks con
  Android 7.1).
- La app del cliente solo ofrece horas que respetan la anticipación mínima configurada en las
  reglas del negocio (la misma que valida el servidor): ya no muestra como libre una hora que luego
  rechazaría (DEF-18, 1.2.4).

## Seguridad

- Token Sanctum por dispositivo, **cifrado** en el equipo: DPAPI en Windows (solo el mismo
  usuario de Windows en la misma PC puede descifrarlo; fuera de Windows, solo en memoria) y
  AES-GCM con clave del Android Keystore en Android. El token sin cifrar de la etapa 0 se cifra
  y se borra la primera vez que se lee. La sesión guardada para entrar sin internet no incluye
  el token.
- Un 401 en cualquier pantalla (salvo el propio inicio de sesión) cierra la sesión en el
  equipo sin borrar lo guardado.
- Permisos por rol en el servidor (`rol:` middleware y comprobaciones en cada servicio, por
  ejemplo el precio acordado solo lo cambia la administradora) y en la interfaz (`Navegacion`).
- Android: `allowBackup=false`; el APK release solo permite HTTPS; el debug permite HTTP en la
  red local para el Laravel del negocio. Permiso de cámara pedido solo al escanear.
- Las bases de ProLech están protegidas en el servidor; las pruebas usan SQLite en memoria.

## Instalador de Windows

`composeDesktopApp/build.gradle.kts` incluye solo los módulos de Java necesarios
(`java.instrument`, `java.management`, `java.prefs`, `java.sql`, `jdk.unsupported`, más los
que Compose pone siempre: `java.base`, `java.desktop`, `java.logging`, `jdk.crypto.ec`).
Ícono: `composeDesktopApp/icono/canchavoley.ico` (16 a 256 px) para el programa, el acceso
directo y el menú Inicio; la ventana usa el mismo dibujo (`dibujarPelota`). En Android:
`ic_launcher` adaptable (fondo azul, pelota blanca, versión temática) y PNG para Android 7.
`CanchaVoley.exe --autoprueba` comprueba con ese Java la base SQLite, preferencias, DPAPI, TLS,
HTTP con JSON, zona horaria y dibujo con Skia; `scripts/compilar.ps1` la ejecuta antes de crear
el `.msi`. `-PcanchaTodosLosModulos=true` vuelve al Java completo.

## Pruebas

`.\gradlew :composeApp:jvmTest` (88) y `.\gradlew :composeApp:testAndroidHostTest` (62: las
comunes). Las comunes usan un servidor simulado con Ktor `MockEngine` y SQLite real.

| Prueba | Casos | Qué comprueba |
|---|---|---|
| `DominioTest` | 6 | Dinero, teléfonos, fechas con desfase de Lima, libres y choques con margen, sugerencias, precio sin inventar valores. |
| `SincronizacionCvTest` | 10 | Sin conexión → `por_sincronizar` y nunca confirmada; choque local bloqueado; al reconectar se confirma y se vacía la cola; rechazo → `rechazada`; error o sin red conserva la operación y su `op_uuid`; orden de envío; descarga y cursor; precio y notas sin conexión; descartar una operación sin perder las demás; un error inesperado al sincronizar se anota y no cierra la app. |
| `FormulariosYNavegacionTest` | 10 | Validaciones de acceso, registro, reserva, pago, horario, tarifas y cierres; horas de inicio libres; la app del cliente no ofrece horas dentro de la anticipación mínima (DEF-18); menú de cada rol; «atrás»; comprobante y cierre de caja. |
| `LecturaQrTest` | 5 | Ida y vuelta del contenido, versión pequeña, rechazo de QR ajenos, minúsculas/espacios, lector USB con otra distribución de teclado. |
| `PlataformaTest` | 5 | Dirección del servidor normalizada y validada, token leído una vez, 401 → sesión vencida (y otros errores no), monitor del servidor. |
| `ExportarYSemanaTest` | 8 | Tablas para Excel (reporte y caja, celdas sin tabulaciones, montos sumables, devoluciones en negativo), cobrado por usuario en el cierre, semanas de lunes a domingo (cambio de mes y de año, reserva de domingo 23:30), serie de cliente fijo y su resumen, reporte por mes sin días futuros. |
| `WhatsAppTest` | 3 | Enlace `wa.me` con número internacional y texto codificado (tildes, saltos), números inválidos, recordatorio (saldo, pendiente por adelanto o aprobación) y ofrecer otro horario. |
| `ArranqueTest` (solo JVM) | 1 | La app arranca con los módulos reales de Koin (los de `iniciarCanchaVoley`): se resuelven repositorio, sincronización, sesión, API, base y cliente HTTP; el token se lee una sola vez. |
| `EscritorioTest` (solo JVM) | 4 | Token cifrado y recuperado, migración del token sin cifrar, sin DPAPI no se escribe en disco, renombrado de `prolech.db`. |
| `MigracionBaseLocalTest` (solo JVM) | 2 | Una base de la versión 5 se actualiza a la 6 con el mismo esquema que una instalación nueva. |
| `CodigoQrArchivoTest` (solo JVM) | 1 | Genera QR como imágenes; `scripts/verificar_qr_opencv.py` las leyó con OpenCV (35 de 35 variantes: nítido, girado, en perspectiva, desenfocado, con ruido de cámara, con poca luz, pequeño). |
| `AutopruebaTest` (solo JVM) | 3 | La autoprueba del instalador pasa y su informe señala cada falla. |
| `DocumentosTest` | 8 | PDF válido (estructura, tabla de referencias, texto con tildes en WinAnsi, varias páginas), comprobante y cierre de caja con sus datos, ZIP con CRC32 correcto, libros .xlsx con sus hojas, aviso inmediato de hora no libre (CAM-01). |
| `SesionTest` | 4 | Al volver a entrar con otra marca de base del servidor se descarta la copia local vieja; con la misma marca o con operaciones sin enviar, no se borra nada. |
| `DocumentosArchivoTest` (solo JVM) | 2 | Escribe el comprobante, el cierre de caja y los Excel en `build/documentos-prueba` y no pisa archivos con el mismo nombre. |
| `InstanciaUnicaTest` (solo JVM) | 2 | Solo una copia abierta a la vez; si no se puede crear el bloqueo, la app igual abre. |
| `RegistroFallosTest` | 3 | El informe de error dice versión, plataforma, dónde ocurrió y la traza; se recorta si es muy largo; anotarlo nunca causa otro error. |
| `PortapapelesSeguroTest` (solo JVM) | 5 | DEF-16: con el portapapeles de Windows ocupado por otro programa, pegar reintenta y, si sigue ocupado, no hace nada (la app no se cierra); DEF-19: si copiar sigue fallando, Java olvida el texto que no llegó a Windows. |
| `ContenidoPortapapelesJavaTest` (solo JVM) | 2 | DEF-19: tras un copiado fallido, pegar ya no devuelve el texto que no llegó a Windows; la autoprueba comprueba el `--add-opens`. |
| `GestorPortapapelesSeguroTest` (solo JVM) | 4 | DEF-16 en los botones «Copiar»: reintenta y copia; si el portapapeles sigue ocupado avisa sin cerrar la app; leer devuelve vacío en vez de fallar. |

Resultados: 16/11/2026 en el entorno de desarrollo (Linux): 56 de 56 en `jvmTest`. 18/11/2026 en
la PC con Windows (build 1.1.0): 56 en `jvmTest` y 45 en `testAndroidHostTest`. 19/11/2026 en la PC
(build 1.2.0): 66 y 53; (build 1.2.1): 72 y 57. Con la versión 1.2.2 se agregan `RegistroFallosTest`, `PortapapelesSeguroTest` y una prueba de `SincronizacionCvTest` (un error inesperado al sincronizar no cierra la app): 80 en `jvmTest` (19/11/2026, entorno de desarrollo Linux) y en la PC 80 y 61. Con la 1.2.4 se agregan `GestorPortapapelesSeguroTest` y una prueba de la anticipación mínima: 85 y 62 en la PC (19/11/2026). Con la 1.2.5 se agregan `ContenidoPortapapelesJavaTest` y una prueba de `PortapapelesSeguroTest` (DEF-19): 88 en `jvmTest` (20/11/2026, entorno de desarrollo Linux).

## Limitaciones conocidas

- Las notificaciones son avisos dentro de la app (se consultan al servidor); no hay
  notificaciones push (Firebase).
- El comprobante es interno; no hay facturación electrónica SUNAT (no se implementa sin
  autorización: requiere credenciales y un proveedor).
- En Windows no se lee el QR con la cámara de la PC (se usa lector USB o el teclado).
- La cola guarda lo hecho sin conexión mientras no se borren los datos de la app.

## Cómo extender

- **Más canchas:** ya soportado (todas las consultas filtran por cancha).
- **Nueva operación offline:** agregar el tipo en `ServicioSync::TIPOS` (servidor), un caso
  en `Repositorio` que llame a `motor.encolar(tipo, uuid, datos)` y el texto en
  `PantallaSincronizacion`.
- **Cliente fijo:** `FormularioReserva` registra una reserva por semana (`Semana.serie`) con
  `Repositorio.crearReserva`; cada una se valida en el servidor (o queda en la cola sin conexión).
- **Nueva validación de formulario:** función en `dominio/Validaciones.kt` (devuelve el
  mensaje o `null`) y su caso en `FormulariosYNavegacionTest`.
