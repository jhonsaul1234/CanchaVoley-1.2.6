# CanchaVoley — base de datos `cancha_voley`

Las tablas de ProLech que vinieron con la copia se conservan sin cambios. CanchaVoley
reutiliza `users` (con `rol` = `admin`, `recepcion` o `cliente`; la columna ya es
`string(30)`), `personal_access_tokens` (Sanctum) y `notificaciones` (avisos dentro de la
app: solicitudes nuevas, aprobaciones, rechazos, reservas liberadas). Las migraciones
`2026_11_16_100001` a `100011` crean 11 tablas nuevas; no borran ni modifican ninguna.

## Tablas nuevas

| Tabla | Para qué sirve | Claves y reglas |
|---|---|---|
| `negocios` | Datos del negocio (nombre, teléfono, dirección, zona horaria, moneda). | — |
| `canchas` | Una fila por cancha; hoy una, lista para más. | único (`negocio_id`, `nombre`) |
| `horarios_atencion` | Turnos por día (1 = lunes … 7 = domingo). `cierra ≤ abre` significa que cierra al día siguiente. Varios turnos por día permitidos. | índice (`cancha_id`, `dia_semana`) |
| `tarifas` | Precio por hora por franja y día, con vigencia (`vigente_desde/hasta`). `hasta` 00:00 = medianoche. | índice (`cancha_id`, `dia_semana`, `vigente_desde`) |
| `reglas_reserva` | Reglas configurables, **todas opcionales** (NULL = sin regla). | único `negocio_id` |
| `bloqueos` | Cierres por mantenimiento, feriado o evento; `cancha_id` NULL = todo el local. | borrado lógico |
| `clientes` | Clientes con o sin cuenta. `user_id` solo si tienen cuenta Android. | `uuid` único; único (`negocio_id`, `telefono`) |
| `reservas` | Reservas de cualquier duración. | `uuid` y `codigo_qr` únicos; CHECK `fin > inicio` |
| `pagos` | Adelantos, pagos y devoluciones (efectivo, Yape, Plin). | `uuid` único; único (`negocio_id`, `metodo`, `numero_operacion`); CHECK `monto > 0` |
| `reserva_eventos` | Historial de cada reserva (creada, aprobada, pago, cambio de horario…). | — |
| `operaciones_sync` | Operaciones recibidas de los equipos sin conexión, con la respuesta dada. | `op_uuid` único |

### `reglas_reserva` (lo que define la administradora)

`duracion_minima_min`, `duracion_maxima_min`, `paso_min` (de cuánto en cuánto se ofrecen
duraciones), `alinear_inicio_min` (horas de inicio permitidas, por ejemplo cada 30 min),
`margen_entre_reservas_min`, `anticipacion_minima_min`, `anticipacion_maxima_dias`,
`regla_cruce_franjas` (`prorrateo` o `franja_inicio`), `adelanto_tipo` (`monto` o
`porcentaje`), `adelanto_valor`, `adelanto_plazo_min`, `adelanto_obligatorio`,
`cancelacion_limite_horas`, `cancelacion_devolucion` (`total`, `parcial`, `ninguna`,
`credito`), `cancelacion_porcentaje`, `reprogramacion_limite_horas`, `tolerancia_no_show_min`.

Si una regla está vacía no se aplica. Si una reserva cruza dos franjas de precio y no hay
`regla_cruce_franjas`, el sistema no inventa un precio: pide el precio acordado.

## Estados de una reserva

| `estado` | `pendiente_por` | Ocupa el horario | Cuándo |
|---|---|---|---|
| `pendiente` | `aprobacion` | Sí | Solicitud de un cliente desde Android; espera a la administradora. |
| `pendiente` | `adelanto` | Sí, hasta `adelanto_vence_en` | La regla exige adelanto y aún no se completa. Al completarlo pasa a `confirmada`. |
| `confirmada` | — | Sí | Registrada por el personal o aprobada. |
| `cancelada` | — | No | Cancelada (motivo en `motivo_estado`). |
| `rechazada` | — | No | Rechazada por la administradora o por el servidor al sincronizar. |

`asistencia`: `asistio` / `no_asistio` (con `check_in_en`). `origen`: `llamada`,
`presencial`, `whatsapp`, `app`, `otro`.

## Pagos

- `tipo`: `adelanto`, `pago` o `devolucion` (la devolución no puede superar lo pagado).
- `estado`: `valido` o `anulado`. Solo la administradora anula; el número de operación se
  mueve a `numero_operacion_anulado` para poder registrarlo de nuevo si fue un error.
- El saldo y el estado de pago de cada reserva se calculan con los pagos válidos. Si la
  reserva ya no ocupa la cancha (cancelada o rechazada) lo pagado aparece como saldo a favor.

## Agregar más canchas

Desde la app: Tarifas y horarios → Canchas → Agregar, y luego «Copiar horario y tarifas de…».
Todas las consultas de disponibilidad, reservas y reportes ya filtran por `cancha_id`.
