@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package pe.canchavoley.app.datos

import pe.canchavoley.app.plataforma.MonitorConectividad
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.DisponibilidadLocal
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.LecturaQr
import pe.canchavoley.app.dominio.Intervalo
import pe.canchavoley.app.dominio.Telefono
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface ResultadoAccion<out T> {
    /** El servidor lo aplicó. */
    data class Hecho<T>(val valor: T, val aviso: String? = null) : ResultadoAccion<T>
    /** Sin conexión: quedó guardado en el equipo y se enviará solo al volver internet. */
    data class GuardadoSinConexion<T>(val valor: T) : ResultadoAccion<T>
    data class Fallo(val mensaje: String, val sugerencias: List<Intervalo> = emptyList(), val codigo: Int = 0) : ResultadoAccion<Nothing>
}

data class NuevaReserva(
    val canchaId: Long,
    val clienteUuid: String?,
    val inicio: Instant,
    val fin: Instant,
    val origen: String = "llamada",
    val precioFinal: String? = null,
    val notas: String? = null,
)

/**
 * Horarios de un día. [desdeElServidor] = datos actuales; si es false, son los guardados en el equipo
 * ([actualizadaEn]: de cuándo son) y lo «libre» no está verificado: el servidor decide al sincronizar.
 * [conDatosDeOcupacion] = false: sin conexión no hay datos guardados de ese día (cualquier hora puede estar tomada).
 */
data class VistaDisponibilidad(
    val turnos: List<Intervalo>, val ocupados: List<Intervalo>, val libres: List<Intervalo>,
    val desdeElServidor: Boolean, val horarioConfigurado: Boolean,
    val actualizadaEn: Instant? = null, val conDatosDeOcupacion: Boolean = true,
)

data class FichaCliente(val cliente: ClienteDto?, val telefono: String, val resumen: ResumenClienteDto?, val historial: List<ReservaDto>, val desdeElServidor: Boolean)

data class VistaCaja(val fecha: LocalDate, val totales: Map<String, Long>, val total: Long, val pagos: List<PagoDto>, val desdeElServidor: Boolean,
                     /** Cobrado por cada usuario (solo con conexión: lo calcula el servidor). */
                     val porUsuario: Map<String, Long> = emptyMap())

/**
 * Punto único que usan las pantallas. En línea habla con el servidor y guarda la respuesta en la
 * copia local; sin conexión trabaja con la copia local y deja las operaciones en cola.
 */
class Repositorio(
    private val api: ApiCancha,
    private val almacen: AlmacenLocal,
    private val monitor: MonitorConectividad,
    private val motor: MotorSync,
    private val sesion: GestorSesion,
) {
    val enLinea: StateFlow<Boolean> get() = monitor.enLinea
    val estadoSync get() = motor.estado
    val avisos get() = motor.avisos
    fun operacionesPendientes(): Flow<Long> = almacen.observarCantidadOperaciones()
    fun listaOperaciones(): Flow<List<OperacionLocal>> = almacen.observarOperaciones()
    suspend fun sincronizarAhora(): String = motor.sincronizar()

    /**
     * Quita de la cola una operación que el servidor no logra procesar (por ejemplo, un error que se
     * repite) para que no bloquee a las demás. Lo creado solo en este equipo queda marcado como no
     * enviado y se vuelven a descargar los datos del servidor para dejar el equipo igual a él.
     */
    suspend fun descartarOperacion(opUuid: String): String {
        val op = almacen.operaciones().firstOrNull { it.opUuid == opUuid } ?: return "La operación ya no está pendiente."
        almacen.borrarOperacion(opUuid)
        val entidad = op.entidadUuid
        when {
            entidad == null -> Unit
            op.tipo == "crear_reserva" -> almacen.reserva(entidad)?.let {
                almacen.guardarReserva(it.dto.copy(estado = "rechazada", motivoEstado = "Descartada en este equipo: no se envió al servidor."),
                    ReservaLocal.RECHAZADA, "Descartada en este equipo")
            }
            op.tipo == "registrar_pago" -> almacen.borrarPago(entidad)
        }
        almacen.borrarDato(AlmacenLocal.CURSOR)       // la próxima descarga es completa
        motor.solicitar()
        return "Operación descartada. Se vuelven a descargar los datos del servidor."
    }

    fun catalogo(): Flow<CatalogoDto?> = almacen.observarCatalogo()
    fun catalogoActual(): CatalogoDto = almacen.catalogo() ?: CatalogoDto()

    suspend fun refrescarCatalogo(): CatalogoDto {
        (api.catalogo() as? Resultado.Ok)?.let { almacen.guardarCatalogo(it.valor) }
        return catalogoActual()
    }

    // ── Consultas locales (funcionan sin internet) ────────────────────────────
    fun reservasEntre(desde: Instant, hasta: Instant): Flow<List<ReservaLocal>> = almacen.observarReservasEntre(desde, hasta)
    fun reservasDelDia(fecha: LocalDate): Flow<List<ReservaLocal>> =
        almacen.observarReservasEntre(Fechas.instante(fecha, LocalTime(0, 0)), Fechas.instante(fecha.plus(DatePeriod(days = 1)), LocalTime(0, 0)))
    fun todasLasReservas(): Flow<List<ReservaLocal>> = almacen.observarReservasTodas()
    fun reserva(uuid: String): Flow<ReservaLocal?> = almacen.observarReserva(uuid)
    fun pagos(reservaUuid: String): Flow<List<PagoLocal>> = almacen.observarPagos(reservaUuid)

    /** Detalle con historial desde el servidor (si hay conexión); lo guarda en la copia local. */
    suspend fun detalle(uuid: String): ReservaDto? = when (val r = api.reserva(uuid)) {
        is Resultado.Ok -> r.valor.also { almacen.guardarReserva(it) }
        else -> almacen.reserva(uuid)?.dto
    }

    suspend fun disponibilidad(canchaId: Long, fecha: LocalDate): VistaDisponibilidad {
        if (monitor.hayConexion()) {
            val r = api.disponibilidad(canchaId, fecha.toString())
            if (r is Resultado.Ok) {
                fun c(l: List<IntervaloDto>) = l.mapNotNull { i -> Fechas.parse(i.inicio)?.let { a -> Fechas.parse(i.fin)?.let { b -> Intervalo(a, b) } } }
                return VistaDisponibilidad(c(r.valor.turnos), c(r.valor.ocupados), c(r.valor.libres), true, r.valor.horarioConfigurado)
            }
        }
        val cat = catalogoActual()
        val turnos = DisponibilidadLocal.turnos(cat, canchaId, fecha)
        val reservas = reservasCercanas(fecha)
        // App del cliente: sus reservas no bastan; se suma la ocupación recibida en la última conexión.
        val ocupacion = if (esCliente()) almacen.ocupacion() else null
        val extra = DisponibilidadLocal.intervalosGuardados(ocupacion, canchaId)
        val ocupados = if (turnos.isEmpty()) emptyList() else DisponibilidadLocal.ocupados(cat, reservas, canchaId, turnos.first().inicio, turnos.last().fin, extra = extra)
        return VistaDisponibilidad(turnos, ocupados, DisponibilidadLocal.libres(cat, reservas, canchaId, fecha, extra = extra), false,
            DisponibilidadLocal.tieneHorario(cat, canchaId),
            actualizadaEn = Fechas.parse(if (esCliente()) ocupacion?.generadoEn else almacen.dato(AlmacenLocal.ULTIMA_SYNC)),
            conDatosDeOcupacion = !esCliente() || DisponibilidadLocal.cubre(ocupacion, fecha))
    }

    private fun esCliente(): Boolean = sesion.sesion.value?.esPersonal == false

    /** Ocupación guardada para consultar sin conexión (solo en la app del cliente), sin el horario de [excluir]. */
    private fun ocupacionGuardada(canchaId: Long, excluir: String? = null): List<Intervalo> {
        if (!esCliente()) return emptyList()
        val propia = excluir?.let { almacen.reserva(it) }?.let { Intervalo(it.inicio, it.fin) }
        return DisponibilidadLocal.intervalosGuardados(almacen.ocupacion(), canchaId, quitar = propia)
    }

    suspend fun sugerencias(canchaId: Long, fecha: LocalDate, duracionMin: Int, hora: LocalTime?, excluir: String? = null): List<Intervalo> {
        if (monitor.hayConexion() && excluir == null) {
            val r = api.recomendaciones(canchaId, fecha.toString(), duracionMin, hora?.let { "${Fechas.dos(it.hour)}:${Fechas.dos(it.minute)}" })
            if (r is Resultado.Ok) return r.valor.sugerencias.mapNotNull { i -> Fechas.parse(i.inicio)?.let { a -> Fechas.parse(i.fin)?.let { b -> Intervalo(a, b) } } }
        }
        return DisponibilidadLocal.sugerencias(catalogoActual(), reservasCercanas(fecha, 8), canchaId, fecha, duracionMin, hora?.let { Fechas.instante(fecha, it) },
            excluir = excluir, extra = ocupacionGuardada(canchaId, excluir))
    }

    /** Precio estimado con las tarifas descargadas (el servidor lo confirma). */
    fun precioEstimado(canchaId: Long, inicio: Instant, fin: Instant): Long? = DisponibilidadLocal.precio(catalogoActual(), canchaId, inicio, fin)

    private fun reservasCercanas(fecha: LocalDate, dias: Int = 2): List<ReservaLocal> =
        almacen.reservasEntre(Fechas.instante(fecha.plus(DatePeriod(days = -1)), LocalTime(0, 0)), Fechas.instante(fecha.plus(DatePeriod(days = dias)), LocalTime(0, 0)))

    // ── Reservas ──────────────────────────────────────────────────────────────
    suspend fun crearReserva(n: NuevaReserva): ResultadoAccion<ReservaDto> {
        val uuid = Uuid.random().toString()
        val cliente = n.clienteUuid?.let { almacen.cliente(it)?.dto }
        val datos = objetoJson(
            "uuid" to uuid, "cancha_id" to n.canchaId, "cliente_uuid" to n.clienteUuid,
            "inicio" to Fechas.iso(n.inicio), "fin" to Fechas.iso(n.fin), "origen" to n.origen,
            "precio_final" to n.precioFinal, "notas" to n.notas,
        ).let { JsonObject(it.filterValues { v -> v.toString() != "null" }) }

        if (monitor.hayConexion() && cliente?.let { almacen.cliente(it.uuid)?.estadoSync == ReservaLocal.PENDIENTE } != true) {
            when (val r = api.crearReserva(datos)) {
                is Resultado.Ok -> { almacen.guardarReserva(r.valor); return ResultadoAccion.Hecho(r.valor) }
                is Resultado.Error -> return fallo(r)
                Resultado.SinConexion -> Unit
            }
        }
        // Sin conexión: se revisa la copia local para no registrar un choque evidente desde este equipo
        // (en la app del cliente, también la ocupación guardada en la última conexión).
        val cat = catalogoActual()
        val guardada = ocupacionGuardada(n.canchaId)
        DisponibilidadLocal.choque(cat, reservasCercanas(Fechas.local(n.inicio).date), n.canchaId, n.inicio, n.fin, extra = guardada)?.let {
            val cuando = (if (esCliente()) almacen.ocupacion()?.generadoEn else almacen.dato(AlmacenLocal.ULTIMA_SYNC))?.let { t -> " (actualizados el ${Fechas.fechaHora(t)})" }.orEmpty()
            return ResultadoAccion.Fallo("Según los datos guardados$cuando, ese horario ya está ocupado (${Fechas.hora(it.inicio)}–${Fechas.hora(it.fin)}).",
                DisponibilidadLocal.sugerencias(cat, reservasCercanas(Fechas.local(n.inicio).date, 8), n.canchaId, Fechas.local(n.inicio).date,
                    ((n.fin - n.inicio).inWholeMinutes).toInt(), n.inicio, extra = guardada))
        }
        val precio = n.precioFinal ?: precioEstimado(n.canchaId, n.inicio, n.fin)?.let { Dinero.aTexto(it) }
        val esCliente = sesion.sesion.value?.esPersonal == false
        val local = ReservaDto(
            uuid = uuid, canchaId = n.canchaId,
            clienteUuid = n.clienteUuid ?: sesion.sesion.value?.cliente?.uuid,
            clienteNombre = cliente?.nombre ?: sesion.sesion.value?.cliente?.nombre,
            clienteTelefono = cliente?.telefono ?: sesion.sesion.value?.cliente?.telefono,
            inicio = Fechas.iso(n.inicio), fin = Fechas.iso(n.fin), duracionMin = ((n.fin - n.inicio).inWholeMinutes).toInt(),
            estado = ReservaLocal.ESTADO_POR_SINCRONIZAR, origen = if (esCliente) "app" else n.origen,
            precioFinal = if (esCliente) null else precio, creadaOffline = true, notas = n.notas,
        )
        almacen.guardarReserva(local, ReservaLocal.PENDIENTE)
        motor.encolar("crear_reserva", uuid, datos)
        return ResultadoAccion.GuardadoSinConexion(local)
    }

    /** cancelar | reprogramar | asistencia | aprobar | rechazar | editar (precio y notas) */
    suspend fun accionReserva(uuid: String, accion: String, datos: Map<String, Any?>): ResultadoAccion<ReservaDto> {
        if (monitor.hayConexion()) {
            when (val r = api.accionReserva(uuid, accion, datos)) {
                is Resultado.Ok -> { almacen.guardarReserva(r.valor); return ResultadoAccion.Hecho(r.valor) }
                is Resultado.Error -> return fallo(r)
                Resultado.SinConexion -> Unit
            }
        }
        val local = almacen.reserva(uuid) ?: return ResultadoAccion.Fallo("La reserva no está en este equipo.")
        val tipo = mapOf("cancelar" to "cancelar_reserva", "reprogramar" to "reprogramar_reserva", "asistencia" to "marcar_asistencia",
            "aprobar" to "aprobar_reserva", "rechazar" to "rechazar_reserva", "editar" to "editar_reserva")[accion] ?: return ResultadoAccion.Fallo("Acción desconocida.")
        val actualizada = when (accion) {
            "cancelar" -> local.dto.copy(estado = "cancelada", motivoEstado = datos["motivo"]?.toString())
            "reprogramar" -> {
                val ini = Fechas.parse(datos["inicio"]?.toString()) ?: return ResultadoAccion.Fallo("Hora inválida.")
                val fin = Fechas.parse(datos["fin"]?.toString()) ?: return ResultadoAccion.Fallo("Hora inválida.")
                DisponibilidadLocal.choque(catalogoActual(), reservasCercanas(Fechas.local(ini).date), local.dto.canchaId, ini, fin, excluir = uuid,
                    extra = ocupacionGuardada(local.dto.canchaId, uuid))?.let {
                    return ResultadoAccion.Fallo("Según los datos guardados, ese horario ya está ocupado.")
                }
                local.dto.copy(inicio = Fechas.iso(ini), fin = Fechas.iso(fin), duracionMin = ((fin - ini).inWholeMinutes).toInt())
            }
            "asistencia" -> local.dto.copy(asistencia = if (datos["asistio"] == true) "asistio" else "no_asistio")
            "editar" -> local.dto.copy(
                precioFinal = if (datos.containsKey("precio_final")) datos["precio_final"]?.toString() ?: local.dto.precioFinal else local.dto.precioFinal,
                notas = if (datos.containsKey("notas")) datos["notas"]?.toString() else local.dto.notas,
            )
            else -> local.dto                     // aprobar/rechazar: el estado lo decide el servidor al sincronizar
        }
        almacen.guardarReserva(actualizada, ReservaLocal.PENDIENTE)
        motor.encolar(tipo, uuid, Json.aJsonObjeto(datos + ("reserva_uuid" to uuid)))
        return ResultadoAccion.GuardadoSinConexion(actualizada)
    }

    suspend fun verificarQr(codigo: String): ResultadoAccion<ReservaDto> {
        val limpio = LecturaQr.codigoDeReserva(codigo)
            ?: return ResultadoAccion.Fallo("Ese código no es de una reserva de CanchaVoley.")
        if (monitor.hayConexion()) {
            when (val r = api.porQr(limpio)) {
                is Resultado.Ok -> { almacen.guardarReserva(r.valor); return ResultadoAccion.Hecho(r.valor) }
                is Resultado.Error -> return fallo(r)
                Resultado.SinConexion -> Unit
            }
        }
        // Sin conexión se valida contra las reservas descargadas.
        val hoy = Fechas.hoy()
        val local = almacen.reservasEntre(Fechas.instante(hoy.plus(DatePeriod(days = -7)), LocalTime(0, 0)), Fechas.instante(hoy.plus(DatePeriod(days = 60)), LocalTime(0, 0)))
            .firstOrNull { it.dto.codigoQr == limpio }
        return local?.let { ResultadoAccion.GuardadoSinConexion(it.dto) } ?: ResultadoAccion.Fallo("El código no corresponde a ninguna reserva descargada.")
    }

    // ── Clientes ──────────────────────────────────────────────────────────────
    suspend fun buscarClientes(texto: String): List<ClienteDto> {
        if (monitor.hayConexion()) {
            val r = api.clientes(texto)
            if (r is Resultado.Ok) { r.valor.forEach { if (almacen.cliente(it.uuid)?.estadoSync != ReservaLocal.PENDIENTE) almacen.guardarCliente(it) }; return r.valor }
        }
        val digitos = texto.filter { it.isDigit() }
        return almacen.buscarClientes(if (digitos.length >= 3 && digitos.length == texto.trim().length) digitos else texto.trim()).map { it.dto }
    }

    /** Modo llamada: quién llama (por teléfono) y su historial. */
    suspend fun fichaPorTelefono(telefono: String): FichaCliente {
        val tel = Telefono.normalizar(telefono)
        if (monitor.hayConexion()) {
            val r = api.clientePorTelefono(tel)
            if (r is Resultado.Ok) {
                r.valor.cliente?.let { almacen.guardarCliente(it) }
                r.valor.historial.forEach { if (almacen.reserva(it.uuid)?.sinSincronizar != true) almacen.guardarReserva(it) }
                return FichaCliente(r.valor.cliente, tel, r.valor.resumen, r.valor.historial, true)
            }
        }
        val c = almacen.clientePorTelefono(tel)?.dto
        val historial = c?.let { almacen.reservasDeCliente(it.uuid).map { r -> r.dto } } ?: emptyList()
        return FichaCliente(c, tel, null, historial, false)
    }

    suspend fun crearCliente(nombre: String, telefono: String, notas: String?, frecuente: Boolean = false): ResultadoAccion<ClienteDto> {
        val uuid = Uuid.random().toString()
        val tel = Telefono.normalizar(telefono)
        val datos = objetoJson("uuid" to uuid, "nombre" to nombre.trim(), "telefono" to tel, "notas" to notas, "es_frecuente" to frecuente)
        if (monitor.hayConexion()) {
            when (val r = api.crearCliente(datos)) {
                is Resultado.Ok -> { almacen.guardarCliente(r.valor); return ResultadoAccion.Hecho(r.valor) }
                is Resultado.Error -> return fallo(r)
                Resultado.SinConexion -> Unit
            }
        }
        almacen.clientePorTelefono(tel)?.let { return ResultadoAccion.Hecho(it.dto, "Ya existía un cliente con ese teléfono.") }
        val local = ClienteDto(uuid = uuid, nombre = nombre.trim(), telefono = tel, notas = notas, esFrecuente = frecuente)
        almacen.guardarCliente(local, ReservaLocal.PENDIENTE)
        motor.encolar("crear_cliente", uuid, datos)
        return ResultadoAccion.GuardadoSinConexion(local)
    }

    suspend fun actualizarCliente(c: ClienteDto, nombre: String, telefono: String, notas: String?, frecuente: Boolean): ResultadoAccion<ClienteDto> {
        val datos = mapOf("nombre" to nombre.trim(), "telefono" to Telefono.normalizar(telefono), "notas" to notas, "es_frecuente" to frecuente)
        if (monitor.hayConexion() && c.id > 0) {
            when (val r = api.actualizarCliente(c.id, datos)) {
                is Resultado.Ok -> { almacen.guardarCliente(r.valor); return ResultadoAccion.Hecho(r.valor) }
                is Resultado.Error -> return fallo(r)
                Resultado.SinConexion -> Unit
            }
        }
        val local = c.copy(nombre = nombre.trim(), telefono = Telefono.normalizar(telefono), notas = notas, esFrecuente = frecuente)
        almacen.guardarCliente(local, ReservaLocal.PENDIENTE)
        motor.encolar("actualizar_cliente", c.uuid, Json.aJsonObjeto(datos + ("uuid" to c.uuid)))
        return ResultadoAccion.GuardadoSinConexion(local)
    }

    // ── Pagos ─────────────────────────────────────────────────────────────────
    suspend fun registrarPago(reservaUuid: String, tipo: String, metodo: String, monto: String, numeroOperacion: String?, observacion: String?): ResultadoAccion<PagoDto> {
        val uuid = Uuid.random().toString()
        val datos = JsonObject(objetoJson(
            "uuid" to uuid, "reserva_uuid" to reservaUuid, "tipo" to tipo, "metodo" to metodo, "monto" to monto,
            "numero_operacion" to numeroOperacion?.takeIf { it.isNotBlank() }, "observacion" to observacion?.takeIf { it.isNotBlank() },
            "pagado_en" to Fechas.iso(Fechas.ahora()),
        ).filterValues { it.toString() != "null" })
        if (monitor.hayConexion() && almacen.reserva(reservaUuid)?.sinSincronizar != true) {
            when (val r = api.registrarPago(datos)) {
                is Resultado.Ok -> {
                    almacen.guardarPago(r.valor.pago.copy(reservaUuid = reservaUuid))
                    almacen.guardarReserva(r.valor.reserva)
                    return ResultadoAccion.Hecho(r.valor.pago, r.valor.advertencia)
                }
                is Resultado.Error -> return fallo(r)
                Resultado.SinConexion -> Unit
            }
        }
        val local = PagoDto(uuid = uuid, reservaUuid = reservaUuid, tipo = tipo, metodo = metodo, monto = monto,
            numeroOperacion = numeroOperacion, pagadoEn = Fechas.iso(Fechas.ahora()), creadoOffline = true)
        almacen.guardarPago(local, ReservaLocal.PENDIENTE)
        motor.encolar("registrar_pago", uuid, datos)
        return ResultadoAccion.GuardadoSinConexion(local)
    }

    suspend fun anularPago(uuid: String, reservaUuid: String, motivo: String): ResultadoAccion<PagoDto> = soloEnLinea(api.anularPago(uuid, motivo)) {
        almacen.guardarPago(it.pago.copy(reservaUuid = reservaUuid)); almacen.guardarReserva(it.reserva); it.pago
    }

    suspend fun caja(fecha: LocalDate): VistaCaja {
        if (monitor.hayConexion()) {
            val r = api.caja(fecha.toString())
            if (r is Resultado.Ok) {
                return VistaCaja(fecha, r.valor.totales.mapValues { Dinero.aCentimos(it.value) ?: 0 }, Dinero.aCentimos(r.valor.total) ?: 0, r.valor.pagos, true,
                    r.valor.porUsuario.comoMapaDeTextos().mapValues { Dinero.aCentimos(it.value) ?: 0 })
            }
        }
        val pagos = almacen.pagosEntre(Fechas.instante(fecha, LocalTime(0, 0)), Fechas.instante(fecha.plus(DatePeriod(days = 1)), LocalTime(0, 0))).map { it.dto }
        val totales = mutableMapOf("efectivo" to 0L, "yape" to 0L, "plin" to 0L)
        pagos.filter { it.estado == "valido" }.forEach { p ->
            val c = (Dinero.aCentimos(p.monto) ?: 0) * if (p.tipo == "devolucion") -1 else 1
            totales[p.metodo] = (totales[p.metodo] ?: 0) + c
        }
        return VistaCaja(fecha, totales, totales.values.sum(), pagos, false)
    }

    // ── Administración (requiere conexión) ────────────────────────────────────
    suspend fun reporte(desde: LocalDate, hasta: LocalDate) = api.reporte(desde.toString(), hasta.toString())
    suspend fun guardarNegocio(datos: Map<String, Any?>) = soloEnLinea(api.guardarNegocio(datos)) { refrescarCatalogo(); it }
    suspend fun crearCancha(nombre: String) = soloEnLinea(api.crearCancha(nombre)) { refrescarCatalogo(); it }
    suspend fun actualizarCancha(id: Long, datos: Map<String, Any?>) = soloEnLinea(api.actualizarCancha(id, datos)) { refrescarCatalogo(); it }
    suspend fun guardarHorarios(canchaId: Long, horarios: List<Map<String, Any?>>) = soloEnLinea(api.guardarHorarios(canchaId, horarios)) { refrescarCatalogo(); it }
    suspend fun guardarTarifas(canchaId: Long, tarifas: List<Map<String, Any?>>, desde: String?) = soloEnLinea(api.guardarTarifas(canchaId, tarifas, desde)) { refrescarCatalogo(); it }
    suspend fun copiarConfiguracion(canchaId: Long, desde: Long) = soloEnLinea(api.copiarConfiguracion(canchaId, desde)) { refrescarCatalogo(); it }
    suspend fun guardarReglas(datos: Map<String, Any?>) = soloEnLinea(api.guardarReglas(datos)) { refrescarCatalogo(); it }
    suspend fun crearBloqueo(datos: Map<String, Any?>) = soloEnLinea(api.crearBloqueo(datos)) { refrescarCatalogo(); it }
    suspend fun eliminarBloqueo(id: Long) = soloEnLinea(api.eliminarBloqueo(id)) { refrescarCatalogo(); it }
    suspend fun usuarios() = api.usuarios()
    suspend fun crearUsuario(datos: Map<String, Any?>) = soloEnLinea(api.crearUsuario(datos)) { it }
    suspend fun actualizarUsuario(id: Long, datos: Map<String, Any?>) = soloEnLinea(api.actualizarUsuario(id, datos)) { it }
    /** Avisos del servidor; sin conexión, la última copia guardada (y si es o no la actual). */
    suspend fun notificaciones(): Pair<NotificacionesCvDto?, Boolean> = when (val r = api.notificaciones()) {
        is Resultado.Ok -> { almacen.guardarNotificaciones(r.valor); r.valor to true }
        else -> almacen.notificaciones() to false
    }
    suspend fun leerNotificaciones() = api.leerNotificaciones().also { if (it is Resultado.Ok) notificaciones() }
    /**
     * Marca un aviso como leído al abrirlo: en la copia del equipo al instante y en el servidor ahora o, si no hay
     * conexión, en la próxima sincronización (no vuelve a contar como «sin leer»).
     */
    suspend fun leerNotificacion(id: Long) {
        almacen.marcarNotificacionLeida(id)
        if (api.leerNotificacion(id) !is Resultado.SinConexion) {
            almacen.quitarLecturasPendientes(setOf(id))
            notificaciones()
        }
    }
    /** Copia guardada de los avisos (se actualiza en cada sincronización, también en segundo plano). */
    fun notificacionesGuardadas(): Flow<NotificacionesCvDto?> = almacen.observarNotificaciones()
    /** Avisos sin leer (de la última descarga): para el contador del menú. */
    fun noLeidas(): Flow<Int> = almacen.observarNoLeidas()

    // ── Pago informado por el cliente (requiere conexión: es solo una referencia para el personal) ──
    suspend fun informarPago(reservaUuid: String, metodo: String, numero: String, monto: String, nota: String?): ResultadoAccion<PagoInformadoDto> =
        soloEnLinea(api.informarPago(reservaUuid, mapOf("metodo" to metodo, "numero_operacion" to numero.trim(), "monto" to monto,
            "nota" to nota?.trim()?.takeIf { it.isNotEmpty() }))) { almacen.guardarReserva(it.reserva); it.pagoInformado }

    suspend fun descartarPagoInformado(uuid: String, motivo: String): ResultadoAccion<PagoInformadoDto> =
        soloEnLinea(api.descartarPagoInformado(uuid, motivo)) { almacen.guardarReserva(it.reserva); it.pagoInformado }

    private suspend fun <T, R> soloEnLinea(r: Resultado<T>, alTerminar: suspend (T) -> R): ResultadoAccion<R> = when (r) {
        is Resultado.Ok -> ResultadoAccion.Hecho(alTerminar(r.valor))
        is Resultado.Error -> fallo(r)
        Resultado.SinConexion -> ResultadoAccion.Fallo("Necesitas conexión con el servidor para esta acción.")
    }

    private fun fallo(r: Resultado.Error): ResultadoAccion.Fallo = ResultadoAccion.Fallo(
        r.mensaje,
        r.detalle?.sugerencias.orEmpty().mapNotNull { i -> Fechas.parse(i.inicio)?.let { a -> Fechas.parse(i.fin)?.let { b -> Intervalo(a, b) } } },
        r.codigo,
    )
}

private fun kotlinx.serialization.json.Json.Default.aJsonObjeto(m: Map<String, Any?>): JsonObject = aJson(m) as JsonObject
private val Json = kotlinx.serialization.json.Json
