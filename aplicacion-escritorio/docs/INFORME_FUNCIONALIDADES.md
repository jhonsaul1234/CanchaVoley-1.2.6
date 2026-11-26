# CanchaVoley — informe de funcionalidades (actualizado el 16/11/2026, madrugada)

Leyenda: **Probado** = con pruebas automáticas ejecutadas · **Compila** = el código compila y
sus partes lógicas tienen pruebas, pero la pantalla no se revisó a la vista · **Pendiente en
la PC** = requiere la PC de Windows o un Android para comprobarse (en el entorno de Claude no
hay SDK de Android, Windows ni MySQL).

## Servidor (`cancha-backend`)

| Funcionalidad | Estado |
|---|---|
| 11 tablas nuevas en `cancha_voley` (sin tocar las heredadas) | Probado en SQLite · migración en MySQL pendiente en la PC (`scripts/actualizar_base.ps1`) |
| Protección de las bases `prolech`, `prolech_db`, `prolech_pass` | Probado |
| Registro de clientes desde Android, login por dispositivo, roles admin / recepción / cliente | Probado |
| Clientes con y sin cuenta; teléfono único (también si dos equipos lo registran a la vez); búsqueda por teléfono en cualquier formato | Probado |
| Canchas (lista para más de una), horarios por turnos (incluso pasada la medianoche), tarifas por franja con vigencia, reglas opcionales, cierres | Probado |
| Disponibilidad, sugerencias y recomendación de horarios | Probado |
| Reservas de cualquier duración; sin cruces (bloqueo por cancha + margen); 409 con alternativas | Probado (también con 10–25 solicitudes simultáneas) |
| Reenvío simultáneo de la misma reserva o pago: recibe el mismo registro, nunca un 409 contra sí mismo | Probado |
| Solicitudes de clientes pendientes hasta aprobación de la administradora; aprobar / rechazar | Probado |
| Cancelar y reprogramar con reglas; asistencia; código QR | Probado |
| Editar precio acordado (solo administración) y notas, con historial; también sin conexión | Probado |
| Pagos efectivo / Yape / Plin, adelantos, devoluciones, anulación por la administradora; sin duplicados por `uuid` ni por número de operación | Probado (también simultáneos) |
| Adelanto obligatorio y liberación automática si vence | Probado |
| Sincronización: cola ordenada, idempotencia por `op_uuid`, rechazos, saldo a favor, descarga por cursor | Probado |
| Reportes de ocupación, ingresos, horas pico, demanda no atendida; caja del día | Probado |
| Avisos (notificaciones dentro de la app) | Probado |
| Comando `canchavoley:instalar` | Probado |

Resultado: `php artisan test` 236 de 236 (1120 aserciones); CanchaVoley 61 de 61.

## Apps Windows y Android (`cancha-app`)

| Funcionalidad | Estado |
|---|---|
| SQLite local y migración de bases ya instaladas (`prolech.db` → `canchavoley.db`) | Probado (JVM) |
| Trabajo sin conexión: reservas «por sincronizar», nunca confirmadas sin el servidor; choques locales bloqueados | Probado (JVM, servidor simulado) |
| Sincronización automática (apenas vuelve el servidor, cada minuto y tras cada cambio), reintentos sin duplicar, rechazos visibles | Probado (JVM, servidor simulado) |
| Reintentar o descartar una operación con error sin perder las demás | Probado (lógica) · pantalla: Compila |
| «En línea» = el servidor responde (no solo «hay red»); en Android revisa al cambiar de red | Probado (lógica) · Android: Pendiente en la PC |
| Token cifrado: DPAPI en Windows, Android Keystore en Android; migración del token anterior | Windows: Probado (lógica, con cifrador simulado) · DPAPI real y Android: Pendiente en la PC |
| Sesión vencida (401) en cualquier pantalla → vuelve al acceso sin perder lo guardado | Probado |
| Validación de todos los formularios (acceso, registro, servidor, cliente, reserva, pago, horario, tarifas, cierres) | Probado |
| Menú por rol y botón «atrás» de Android (cierra el detalle, vuelve al inicio, sale) | Probado (lógica) · Android: Pendiente en la PC |
| Horas de inicio libres como botones al reservar | Probado (lógica) · pantalla: Compila |
| Lectura de QR: cámara en Android (zxing), lector USB/teclado en Windows; lectura tolerante | Lógica: Probado · cámara: Pendiente en la PC |
| Generador de QR | Probado (OpenCV leyó 35 de 35 variantes, incluso girado, desenfocado y con poca luz) |
| Comprobante de la reserva y cierre de caja para copiar (Windows) o compartir (Android) | Probado (texto) · pantalla: Compila |
| Caja: cobrado por usuario; movimientos y reportes «para Excel» (tabla para pegar con Ctrl+V) | Probado (tablas) · pantalla: Compila |
| Agenda semanal (lunes a domingo) y reportes por mes | Probado (lógica de semanas y meses) · pantalla: Compila |
| Cliente fijo: la misma hora cada semana (4, 8 o 12), cada fecha validada por separado | Probado (fechas y resumen) · pantalla: Compila |
| WhatsApp: recordatorio u ofrecer otro horario con el mensaje escrito (enlace `wa.me`, sin cuentas) | Probado (enlace y textos) · abrir WhatsApp: Pendiente en la PC y el celular |
| Diseño: tema propio, 32 íconos vectoriales, logo, menú agrupado, diseño adaptable a celular y PC; ícono propio del programa (instalador, menú Inicio, ventana) y del APK (adaptable y temático) | Compila (íconos, logo e íconos de la app revisados como imagen) · revisión a la vista: Pendiente en la PC |
| Panel, calendario, modo llamada, detalle de reserva, clientes, caja | Compila |
| Tarifas y horarios, reglas, cierres (también «todo el día»), canchas, negocio; reportes; usuarios | Compila |
| Cliente: horarios y solicitud, mis reservas, QR, comprobantes, avisos, cuenta | Compila |
| Instalador de Windows con Java recortado + `--autoprueba` | Autoprueba con el Java recortado: Probado en Linux (pasa; y detecta la falta de `java.sql` o de `jdk.crypto.ec`) · `.msi`: Pendiente en la PC |
| APK Android | Pendiente en la PC (`scripts/compilar.ps1`) |

Resultado: `jvmTest` 56 de 56 (incluye el arranque con los módulos reales). `testAndroidHostTest` (45) pendiente en la PC.

## No incluido todavía

- Notificaciones push (Firebase); hoy los avisos se ven dentro de la app.
- Boleta o factura electrónica SUNAT (el comprobante es interno; no se implementa sin tu
  autorización porque requiere credenciales y un proveedor).
- Cambiar la configuración, ver reportes o anular pagos sin conexión.
- Paquetes o promociones de precio (hoy: precio por hora por franja, o precio acordado).
- Leer el QR con la cámara de la PC (en Windows: lector USB o teclado).
- APK release firmado (requiere crear un archivo de firma propio, que nunca se sube a Git).
