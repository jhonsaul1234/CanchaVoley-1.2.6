# CanchaVoley — manual de usuario

CanchaVoley sirve para registrar y controlar las reservas de la cancha: quién reservó,
para qué día y hora, cuánto dura, cuánto pagó y por qué medio. Funciona en la PC del negocio
(Windows) y en celulares Android, **con y sin internet**.

## 1. Quién usa qué

| Perfil | Dónde | Qué puede hacer |
|---|---|---|
| **Administradora** | PC y celular | Todo: reservas, clientes, pagos, aprobar o rechazar solicitudes, anular pagos, tarifas y horarios, reglas, cierres, canchas, reportes y usuarios. |
| **Recepción** | PC y celular | Reservas por llamada o en persona, clientes, pagos y caja, asistencia y QR. No configura precios ni horarios, no ve reportes ni usuarios, no aprueba solicitudes ni anula pagos. |
| **Cliente** | Celular Android | Ver horarios libres, **solicitar** una reserva (queda pendiente hasta que la administradora la apruebe), ver sus reservas, su QR, sus comprobantes y sus avisos. |

## 2. Entrar al sistema

1. Abre CanchaVoley.
2. Escribe tu correo y contraseña y presiona **Entrar**.
3. Solo la primera vez en un celular: toca **Servidor** y escribe la dirección de la PC del
   negocio, por ejemplo `http://192.168.1.10:8001` (te la da la administradora).

- Si no hay internet pero ya entraste antes en ese equipo, puedes entrar igual con lo guardado.
- La sesión queda guardada **cifrada** en el equipo (en Windows solo la puede leer tu usuario de
  Windows; en Android, con una clave del propio celular). Si la sesión se cierra desde otro
  equipo o vence, la app vuelve a esta pantalla con el aviso «Tu sesión terminó»: entra de
  nuevo y lo guardado en el equipo se conserva.
- Si hay operaciones sin enviar, el sistema no deja entrar con **otro** usuario en ese equipo
  hasta sincronizarlas (así nada se pierde ni se mezcla).
- Clientes: en el celular toca **¿Eres cliente? Crea tu cuenta**, con tu nombre, celular,
  correo y una contraseña de al menos 8 caracteres. Si el negocio ya te tenía registrado por
  teléfono, tu cuenta se une a ese registro y verás tus reservas anteriores.

En la parte superior siempre verás un **punto de color** (verde: conectado; gris: sin
conexión) y, si hay cambios sin enviar, un número con las **operaciones pendientes**.

## 3. Primera configuración (administradora)

Menú **Tarifas y horarios**. Nada viene con valores inventados: mientras un dato no se
configure, el sistema no lo aplica o te lo pide al reservar.

1. **Horario**: para cada día, uno o más turnos (por ejemplo 08:00–13:00 y 15:00–23:00). Si
   cierra después de medianoche, pon la hora de cierre del día siguiente (por ejemplo 01:00).
   «Copiar el lunes a todos los días» ahorra tiempo.
2. **Tarifas**: precio por hora por franja (por ejemplo 18:00–22:00). Una tarifa nueva no
   cambia el precio de las reservas ya hechas.
3. **Reglas** (todas opcionales):
   - Duración y horarios: duración mínima y máxima, de cuánto en cuánto se ofrece la duración,
     cada cuántos minutos puede empezar una reserva, minutos libres entre reservas,
     anticipación mínima y máxima.
   - Cobro si una reserva cruza dos franjas de precio: proporcional o con el precio de la
     franja en que empieza. Si no se elige, se pide el precio acordado.
   - Adelanto: monto fijo o porcentaje, plazo para pagarlo y si es obligatorio. Con adelanto
     obligatorio, la reserva queda **pendiente** hasta completarlo y, si vence el plazo, se libera.
   - Cancelaciones y faltas: hasta cuántas horas antes puede cancelar el cliente, qué se
     devuelve, hasta cuándo se puede reprogramar y la tolerancia de llegada.
4. **Cierres**: días u horas en que la cancha no se alquila (mantenimiento, feriado, evento).
   Marca **Todo el día** para cerrar el día completo, o indica desde y hasta qué hora, y el
   motivo. Al registrar un cierre, el sistema avisa cuántas reservas hay en ese horario, pero no las
   cancela solo: avisa a esos clientes y decide qué hacer con cada reserva.
5. **Canchas**: hoy hay una. Para agregar otra, **Agregar** y luego «Copiar horario y tarifas de…».
6. **Negocio**: nombre, teléfono y dirección (salen en los comprobantes).

## 4. El día a día (administradora y recepción)

### Panel principal
Muestra las reservas de hoy, lo cobrado, las solicitudes por aprobar, las próximas reservas
y la tarjeta **Llegada del cliente: verificar código QR**:

- En el **celular**: **Escanear QR con la cámara** y apunta al QR del cliente (la primera vez
  Android pide permiso para usar la cámara).
- En la **PC**: con un lector USB el código se escribe solo en el campo; si no, escríbelo a
  mano y presiona **Verificar**.

Si el código es válido, se abre la reserva y marcas **Llegó el cliente**. Sin internet se
verifica con las reservas guardadas en el equipo.

### Modo llamada (cuando llama un cliente)
1. Escribe el número de celular (en cualquier formato: 987 654 321, +51…).
2. Si ya es cliente, aparece su ficha: nombre, notas, últimas reservas y cuántas veces no
   asistió. Si es nuevo, escribe su nombre y **Guardar cliente**.
3. Elige el día y la duración (por ejemplo 1 h, 1 h 30, 2 h): aparecen como botones las
   **horas de inicio libres** para esa duración. Toca una o escribe otra hora. Se muestra el
   precio estimado según la tarifa.
   - **Cliente fijo:** en «Repetir» elige 4, 8 o 12 semanas para registrar la misma hora cada
     semana. Cada fecha se valida por separado: si alguna ya está ocupada, las demás se
     registran igual y un aviso dice cuáles no se pudieron.
4. Indica cómo llegó la reserva (llamada, en persona, WhatsApp…) y presiona **Registrar reserva**.
   Si no hay tarifa para ese horario, escribe el precio acordado.
5. Si el cliente paga o deja adelanto, **Registrar adelanto o pago**.

Si ese horario ya lo tomó otra persona (por ejemplo, desde otro equipo un segundo antes), el
sistema no lo duplica: avisa y ofrece los horarios libres más cercanos.

### Calendario de reservas
- **Día**: por cancha, los turnos de atención, las reservas (con su estado y si están
  pagadas), los espacios **libres** y los cierres (con su motivo). Toca un espacio libre para
  reservarlo (el formulario se abre justo debajo) o una reserva para ver su detalle.
- **Semana**: de lunes a domingo, las reservas de cada día con su horario, cliente y estado,
  y cuántas horas se reservaron. Toca una reserva para abrirla o **Ver día** para ir a ese día.
  Usa los datos guardados en el equipo, así que también funciona sin internet.

### Detalle de una reserva
- **Registrar pago**: tipo (adelanto, pago o devolución), método (efectivo, Yape o Plin),
  monto y, para Yape/Plin, el **número de operación** (no se puede registrar dos veces el
  mismo). Una devolución no puede ser mayor que lo cobrado. Si registras dos veces el mismo
  monto en efectivo en pocos minutos, el sistema te pide revisar que no sea un duplicado.
- **Llegó el cliente** / **No asistió** (asistencia).
- **Precio y notas** (administradora) o **Notas** (recepción): corrige el precio acordado o
  agrega una nota. Cada cambio de precio queda en el historial de la reserva («Precio
  acordado: S/ 40.00 → S/ 35.00»). Solo en reservas pendientes o confirmadas.
- **Cambiar horario** (valida que el nuevo horario esté libre) y **Cancelar** (con motivo).
- **Aprobar** / **Rechazar** solicitudes de clientes (solo la administradora). Si la
  solicitud no tiene precio (no había tarifa), se pide al aprobar.
- Pagos: cada pago tiene su comprobante interno (CV-000123). Solo la administradora puede
  **Anular** un pago, indicando el motivo.
- **Compartir** (celular: WhatsApp, correo…) o **Copiar** (PC, para pegarlo donde quieras) el
  comprobante de la reserva con sus pagos y el saldo.
- **Recordar por WhatsApp** (reservas pendientes o confirmadas) u **Ofrecer otro horario**
  (reservas rechazadas): abre WhatsApp (en la PC, WhatsApp Web o el de escritorio) con el
  mensaje ya escrito al celular del cliente; tú decides si lo envías. El mensaje solo usa los
  datos de la reserva (día, hora, saldo, código).
- El **QR** de la reserva identifica al cliente cuando llega.

> El comprobante es interno del negocio; **no reemplaza** una boleta o factura electrónica de SUNAT.

### Clientes
Buscar por nombre o teléfono, crear y editar (nombre, celular, notas, cliente frecuente) y
ver el historial de reservas de cada uno. No se repiten clientes con el mismo teléfono. En la
ficha, **WhatsApp** abre el chat con el cliente.

### Pagos y caja
Elige el día: totales por método (efectivo, Yape, Plin), **cuánto cobró cada usuario** y la
lista de movimientos. Los pagos se registran desde el detalle de cada reserva.

- **Copiar cierre** (PC) o **Compartir cierre** (celular): resumen del día para enviarlo o
  guardarlo.
- **Para Excel** (PC): copia los movimientos como tabla; abre Excel y pega con **Ctrl+V**
  (cada dato queda en su columna y los montos se pueden sumar; las devoluciones van en negativo).

Sin internet, la caja se calcula con los pagos guardados en el equipo (lo avisa en pantalla) y
no muestra lo cobrado por usuario.

### Reportes (administradora)
Últimos 7 días, últimos 30 o **por mes** (con flechas para ir a meses anteriores): ocupación
(%), ingresos (descontando devoluciones), horas pico, demanda no atendida (reservas
rechazadas o canceladas), ocupación por día, ingresos por método, cómo llegan las reservas y
clientes frecuentes. **Copiar para Excel** (PC) copia todo como tabla para pegarlo en Excel;
en el celular, **Compartir**. Requieren conexión.

### Usuarios (administradora)
Crear cuentas para recepción o administración, activarlas o desactivarlas y cambiar contraseñas.

### Avisos
Solicitudes nuevas, aprobaciones, rechazos y reservas liberadas por adelanto vencido.

## 5. Sin internet

| Se puede sin internet | Necesita internet |
|---|---|
| Consultar reservas, clientes y pagos guardados en el equipo | Reportes, usuarios, avisos y anular pagos |
| Registrar clientes, reservas, pagos, asistencia, cambios de horario, cancelaciones, aprobaciones y rechazos (se envían al volver la conexión) | Cambiar la configuración (horario, tarifas, reglas, cierres, canchas) |
| Verificar un QR con las reservas guardadas en el equipo; ver el horario y el precio con las tarifas descargadas | Ver lo que otros equipos hicieron mientras estabas sin conexión |

- Lo hecho sin internet queda **«Por sincronizar»** y se envía solo al volver la conexión
  (o con **Sincronización → Sincronizar ahora**).
- Una reserva hecha sin internet **nunca** se da por confirmada hasta que el servidor la
  valida. Si mientras tanto otro equipo tomó ese horario, quedará **Rechazada** con el motivo,
  y verás el aviso para llamar al cliente y ofrecerle otro horario.
- Si cobraste un pago sobre una reserva que luego fue rechazada, el pago **no se pierde**:
  queda como **saldo a favor** del cliente. Para avisarle, en esa reserva usa **Ofrecer otro
  horario** (WhatsApp). Para usar el saldo en la nueva reserva: en la rechazada registra una
  **Devolución** por ese monto y, en la nueva, un **Pago** con el mismo método; en la caja los
  dos movimientos se compensan (o devuélvele el dinero, si lo prefiere).
- En **Sincronización** ves la cola de operaciones pendientes, la última sincronización y
  cualquier problema. Si una operación queda con error, **Reintentar ahora** la vuelve a
  enviar; **Descartar** la quita de este equipo (por ejemplo, un dato mal escrito que el
  servidor nunca aceptará). Descartar no borra nada en el servidor y las demás operaciones
  siguen enviándose.
- La app detecta sola cuándo vuelve el servidor (en el celular, también al cambiar de Wi-Fi a
  datos o viceversa) y envía lo pendiente sin que tengas que hacer nada.

## 6. Para clientes (Android)

1. **Reservar**: elige el día, mira los horarios libres, elige la hora y la duración y envía
   la solicitud. Queda **pendiente** hasta que la administradora la apruebe; recibirás un aviso.
   Sin internet, la solicitud se guarda y se envía sola cuando vuelvas a tener conexión.
2. **Mis reservas**: próximas e historial; toca una para ver el detalle, el QR y los pagos.
3. Desde el detalle puedes **cambiar el horario** de una solicitud pendiente y **cancelar**:
   una solicitud pendiente, siempre; una reserva confirmada, solo si el negocio configuró un
   plazo de cancelación y todavía estás dentro de él. Para cualquier otro cambio, comunícate
   con el negocio.
4. **Al llegar**, muestra tu QR en recepción.

En Android, el botón **atrás** cierra el detalle abierto; en otra pantalla vuelve a la
primera del menú; y en la primera sale de la app (en todos los perfiles).

## 7. Estados de una reserva

| Estado | Significado |
|---|---|
| Por sincronizar | Hecha en este equipo sin internet; aún no la validó el servidor. |
| Pendiente (por aprobar) | Solicitud de un cliente; espera a la administradora. El horario queda apartado. |
| Pendiente (falta adelanto) | La regla exige adelanto; se confirma al completarlo o se libera si vence el plazo. |
| Confirmada | Reserva firme. |
| Cancelada | Cancelada por el negocio o por el cliente (o por adelanto vencido). |
| Rechazada | No aprobada, o el horario ya estaba tomado cuando se sincronizó. |
