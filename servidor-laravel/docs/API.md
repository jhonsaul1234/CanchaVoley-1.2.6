# CanchaVoley — API REST (`/api/cv`)

Todas las rutas están en `routes/canchavoley.php` (incluido desde `routes/api.php`).

## Convenciones

- **Autenticación:** `POST /api/login` con `email`, `password` y `dispositivo`
  (`escritorio` o `android`). Devuelve un token Sanctum; cada dispositivo tiene su propio
  token (`cv-escritorio`, `cv-android`) y al volver a entrar solo se reemplaza el de ese
  dispositivo. Enviar `Authorization: Bearer <token>`.
- **Roles** (`users.rol`): `admin` (la administradora), `recepcion` (personal),
  `cliente` (cuenta creada desde Android). El middleware `rol:` rechaza con 403.
- **Fechas:** ISO 8601 con desfase de Lima, por ejemplo `2026-12-01T18:00:00-05:00`.
  Los intervalos son semiabiertos `[inicio, fin)`: una reserva que termina a las 19:00 no
  choca con otra que empieza a las 19:00 (salvo que se configure un margen).
- **Dinero:** texto con dos decimales (`"60.00"`), en soles.
- **Identificadores:** clientes, reservas y pagos usan `uuid` generado por la app, lo que
  permite crearlos sin conexión y reintentar sin duplicar.
- **Errores:** `401` sesión vencida, `403` sin permiso, `404` no existe, `409` conflicto
  (horario ocupado, operación duplicada; incluye `sugerencias` cuando aplica),
  `422` datos inválidos o regla del negocio.

## Rutas

### Públicas
| Método | Ruta | Uso |
|---|---|---|
| POST | `/api/cv/registro` | Cuenta de cliente (`nombre`, `email`, `password` ≥ 8, `telefono`). Si el teléfono ya existía como cliente registrado por llamada, se vincula. Límite: 5 por minuto por IP. |

### Cualquier usuario autenticado (admin, recepción, cliente)
| Método | Ruta | Uso |
|---|---|---|
| GET | `/api/cv/sesion` | Usuario, rol, negocio y ficha de cliente. |
| GET | `/api/cv/catalogo` | Negocio, canchas, horarios, tarifas vigentes, reglas y cierres. |
| GET | `/api/cv/disponibilidad?cancha_id&fecha=AAAA-MM-DD` | Turnos de atención, ocupados y libres del día. |
| GET | `/api/cv/recomendaciones?cancha_id&fecha&duracion_min&hora&dias` | Horarios libres más cercanos a la hora pedida. |
| GET | `/api/cv/reservas?desde&hasta&cancha_id&estado` | El cliente solo ve las suyas. |
| POST | `/api/cv/reservas` | Crear (`uuid`, `cancha_id`, `cliente_uuid`, `inicio`, `fin`, `origen`, `precio_final`, `notas`). Cliente → `pendiente/aprobacion`. Personal → `confirmada`, o `pendiente/adelanto` si la regla de adelanto lo exige. |
| GET | `/api/cv/reservas/{uuid}` | Detalle con pagos e historial. |
| POST | `/api/cv/reservas/{uuid}/cancelar` | `motivo`. El cliente solo dentro del plazo configurado. |
| POST | `/api/cv/reservas/{uuid}/reprogramar` | `inicio`, `fin` (vuelve a validar choques). |
| GET | `/api/cv/reservas/{uuid}/comprobante` | Comprobante interno (no reemplaza a SUNAT). |
| GET | `/api/cv/notificaciones` · POST `/api/cv/notificaciones/leer` | Avisos (solicitudes, aprobaciones, rechazos). |
| POST | `/api/cv/sync/operaciones` | Cola de operaciones hechas sin conexión (ver abajo). |
| GET | `/api/cv/sync/cambios?desde=` | Descarga de cambios para el SQLite local. |

### Administración y recepción
| Método | Ruta | Uso |
|---|---|---|
| GET/POST | `/api/cv/clientes` | Buscar (`q`) y crear. Si el teléfono ya existe, devuelve el cliente existente. |
| GET | `/api/cv/clientes/telefono/{telefono}` | Ficha rápida para el modo llamada. |
| GET/PUT | `/api/cv/clientes/{cliente}` | Ficha con historial / editar. |
| POST | `/api/cv/reservas/{uuid}/editar` | `precio_final` (solo administración, 0–99 999) y/o `notas` (≤ 2000). Solo reservas activas (`pendiente` o `confirmada`); si no, 409. Cada cambio queda en el historial (`precio_cambiado`, `notas_cambiadas`). |
| POST | `/api/cv/reservas/{uuid}/asistencia` | `asistio` true/false (check-in). |
| GET | `/api/cv/qr/{codigo}` | Verificar el QR de una reserva. |
| POST | `/api/cv/pagos` | `uuid`, `reserva_uuid`, `tipo` (adelanto/pago/devolucion), `metodo` (efectivo/yape/plin), `monto`, `numero_operacion`, `pagado_en`, `observacion`. |
| GET | `/api/cv/caja?fecha=` | Totales del día por método. |

### Solo administración
| Método | Ruta | Uso |
|---|---|---|
| POST | `/api/cv/reservas/{uuid}/aprobar` · `/rechazar` | Solicitudes de clientes (`precio_final` / `motivo`). |
| POST | `/api/cv/pagos/{uuid}/anular` | `motivo`; el número de operación queda libre y se guarda en `numero_operacion_anulado`. |
| PUT | `/api/cv/negocio` | Datos del negocio. |
| POST/PUT | `/api/cv/canchas`, `/api/cv/canchas/{cancha}` | Agregar o editar canchas. |
| PUT | `/api/cv/canchas/{cancha}/horarios` · `/tarifas` | Horario de atención y tarifas por franja. |
| POST | `/api/cv/canchas/{cancha}/copiar-configuracion` | Copiar horario y tarifas de otra cancha. |
| PUT | `/api/cv/reglas` | Reglas de reserva (todas opcionales; ver BASE_DE_DATOS.md). |
| POST/DELETE | `/api/cv/bloqueos`, `/api/cv/bloqueos/{bloqueo}` | Cierres (mantenimiento, feriados). |
| GET | `/api/cv/reportes/ocupacion?desde&hasta&cancha_id` | Ocupación, horas pico, ingresos, demanda no atendida. |
| GET/POST/PUT | `/api/cv/usuarios`, `/api/cv/usuarios/{user}` | Cuentas de recepción y administración. |

## Protección contra reservas y pagos duplicados

1. **Choques de horario:** `ServicioReservas` abre una transacción, bloquea la fila de la
   cancha (`SELECT … FOR UPDATE`) y busca, también con bloqueo, reservas `pendiente` o
   `confirmada` y cierres que se crucen con `[inicio − margen, fin + margen)`. Dos pedidos
   simultáneos para el mismo horario se atienden uno tras otro: el primero se guarda y el
   segundo recibe 409 con sugerencias.
2. **Reintentos:** si llega otra vez el mismo `uuid` (reserva, pago o cliente) se devuelve
   lo ya guardado con 200, sin crear otro registro. En reservas y pagos el `uuid` se vuelve a
   buscar dentro de la transacción bloqueada, así que dos envíos simultáneos del mismo registro
   reciben el mismo (201 y 200), nunca un 409 por sí mismo. Si dos equipos registran a la vez
   el mismo cliente (mismo `uuid` o mismo teléfono), el índice único decide y el segundo recibe
   el cliente ya guardado.
3. **Yape/Plin:** índice único (`negocio_id`, `metodo`, `numero_operacion`); un número ya
   usado responde 409. El efectivo repetido (mismo monto y reserva en pocos minutos) se
   acepta pero devuelve una advertencia.
4. **Adelantos vencidos:** al consultar o reservar esa cancha, una reserva
   `pendiente/adelanto` cuyo plazo venció sin completar el adelanto pasa a `cancelada`
   («Venció el plazo del adelanto»), libera el horario y avisa al cliente si tiene cuenta.

## Sincronización

`POST /api/cv/sync/operaciones`

```json
{
  "dispositivo_uuid": "6d3c…",
  "operaciones": [
    {"op_uuid": "a1…", "tipo": "crear_reserva", "entidad_uuid": "r1…",
     "creada_en": "2026-12-01T17:55:00-05:00",
     "datos": {"uuid": "r1…", "cancha_id": 1, "cliente_uuid": "c1…",
               "inicio": "2026-12-01T18:00:00-05:00", "fin": "2026-12-01T19:00:00-05:00"}}
  ]
}
```

Tipos: `crear_cliente`, `actualizar_cliente`, `crear_reserva`, `reprogramar_reserva`,
`cancelar_reserva`, `editar_reserva` (precio acordado y notas), `aprobar_reserva`,
`rechazar_reserva`, `registrar_pago`, `marcar_asistencia`. Máximo 100 por envío; se procesan en orden.

Respuesta: `{"resultados": [...], "servidor_ahora": "…"}` con un resultado por operación:

- `aplicada`: se guardó; incluye `entidad_tipo` y `entidad` (lo que quedó en el servidor).
- `rechazada`: el servidor no la aceptó (por ejemplo, horario ocupado); incluye `codigo`,
  `mensaje` y, si es una reserva, la `entidad` en estado `rechazada` para que la app la
  muestre así. Nunca se confirma algo creado sin conexión sin pasar por estas reglas.
- `error`: fallo temporal; no se guarda y la app lo reintenta con el mismo `op_uuid`.
- Si el `op_uuid` ya fue procesado, se devuelve la misma respuesta con `"repetida": true`.

Un pago enviado sin conexión sobre una reserva que terminó rechazada **no se pierde**: queda
registrado y la reserva muestra `saldo_a_favor` para devolverlo o usarlo después.

`GET /api/cv/sync/cambios?desde=<cursor>` devuelve `catalogo`, `clientes`, `reservas` y
`pagos` modificados desde el cursor (el personal ve todo el negocio; el cliente, lo suyo) y
un `cursor` nuevo (`servidor_ahora` − 120 s, para no perder cambios simultáneos).
Sin `desde` es una carga completa (desde hace 30 días en adelante).
