@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package pe.canchavoley.app.datos

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import pe.canchavoley.app.dominio.AvisosSistema
import pe.canchavoley.app.dominio.Fechas
import pe.edu.upeu.prolech.db.AppDatabase
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class ReservaLocal(val dto: ReservaDto, val estadoSync: String, val errorSync: String?) {
    val inicio: Instant get() = Fechas.parse(dto.inicio)!!
    val fin: Instant get() = Fechas.parse(dto.fin)!!
    val sinSincronizar: Boolean get() = estadoSync == PENDIENTE
    /** Ocupa la cancha: pendiente, confirmada o registrada sin conexión (aún no validada). */
    val ocupa: Boolean get() = dto.estado in listOf("pendiente", "confirmada", ESTADO_POR_SINCRONIZAR)

    companion object {
        const val SINCRONIZADO = "SINCRONIZADO"
        const val PENDIENTE = "PENDIENTE"
        const val RECHAZADA = "RECHAZADA"
        /** Estado local de una reserva registrada sin internet: nunca se muestra como confirmada. */
        const val ESTADO_POR_SINCRONIZAR = "por_sincronizar"
    }
}

data class ClienteLocal(val dto: ClienteDto, val estadoSync: String)

data class PagoLocal(val dto: PagoDto, val estadoSync: String)

data class OperacionLocal(val opUuid: String, val tipo: String, val entidadUuid: String?, val datos: JsonObject,
                          val creadaEn: String, val intentos: Long, val ultimoError: String?)

/** Copia local de CanchaVoley en SQLite (SQLDelight): funciona sin internet en escritorio y Android. */
class AlmacenLocal(private val db: AppDatabase) {
    private val q = db.canchaVoleyQueries
    private val ctx = Dispatchers.Default

    // ── Datos sueltos ─────────────────────────────────────────────────────────
    fun dato(clave: String): String? = q.leerDato(clave).executeAsOneOrNull()
    fun guardarDato(clave: String, valor: String) = q.guardarDato(clave, valor)
    fun borrarDato(clave: String) = q.borrarDato(clave)
    fun observarDato(clave: String): Flow<String?> = q.observarDato(clave).asFlow().mapToOneOrNull(ctx)

    fun dispositivoUuid(): String = dato(DISPOSITIVO) ?: Uuid.random().toString().also { guardarDato(DISPOSITIVO, it) }

    fun sesion(): SesionDto? = dato(SESION)?.let { runCatching { JsonCv.decodeFromString<SesionDto>(it) }.getOrNull() }
    fun guardarSesion(s: SesionDto) = guardarDato(SESION, JsonCv.encodeToString(SesionDto.serializer(), s.copy(token = null)))

    fun catalogo(): CatalogoDto? = dato(CATALOGO)?.let { runCatching { JsonCv.decodeFromString<CatalogoDto>(it) }.getOrNull() }
    fun observarCatalogo(): Flow<CatalogoDto?> = observarDato(CATALOGO).map { t -> t?.let { runCatching { JsonCv.decodeFromString<CatalogoDto>(it) }.getOrNull() } }
    fun guardarCatalogo(c: CatalogoDto) = guardarDato(CATALOGO, JsonCv.encodeToString(CatalogoDto.serializer(), c))

    // ── Ocupación guardada para consultar horarios sin conexión (app del cliente) ──
    fun ocupacion(): OcupacionDto? = dato(OCUPACION)?.let { runCatching { JsonCv.decodeFromString<OcupacionDto>(it) }.getOrNull() }
    fun guardarOcupacion(o: OcupacionDto) = guardarDato(OCUPACION, JsonCv.encodeToString(OcupacionDto.serializer(), o))

    // ── Avisos: copia para verlos sin conexión y control de los ya mostrados ──
    fun notificaciones(): NotificacionesCvDto? = dato(NOTIFICACIONES)?.let { runCatching { JsonCv.decodeFromString<NotificacionesCvDto>(it) }.getOrNull() }
    /** Guarda la copia de los avisos respetando los que se abrieron en este equipo y aún no se enviaron. */
    fun guardarNotificaciones(n: NotificacionesCvDto) =
        guardarDato(NOTIFICACIONES, JsonCv.encodeToString(NotificacionesCvDto.serializer(), AvisosSistema.conLeidas(n, lecturasPendientes())))
    fun observarNotificaciones(): Flow<NotificacionesCvDto?> = observarDato(NOTIFICACIONES).map { t ->
        t?.let { runCatching { JsonCv.decodeFromString<NotificacionesCvDto>(it) }.getOrNull() }
    }
    fun observarNoLeidas(): Flow<Int> = observarNotificaciones().map { it?.noLeidas ?: 0 }

    /** Avisos abiertos en este equipo que falta marcar como leídos en el servidor (se envían al sincronizar). */
    fun lecturasPendientes(): Set<Long> = dato(LECTURAS_PENDIENTES).orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    fun quitarLecturasPendientes(ids: Set<Long>) {
        val quedan = lecturasPendientes() - ids
        if (quedan.isEmpty()) borrarDato(LECTURAS_PENDIENTES) else guardarDato(LECTURAS_PENDIENTES, quedan.joinToString(","))
    }

    /** Un aviso abierto: se marca leído en la copia al instante (el contador baja) y queda pendiente de enviar. */
    fun marcarNotificacionLeida(id: Long) {
        guardarDato(LECTURAS_PENDIENTES, (lecturasPendientes() + id).toList().takeLast(200).joinToString(","))
        notificaciones()?.let { guardarNotificaciones(it) }
    }

    // ── Clientes ──────────────────────────────────────────────────────────────
    fun guardarCliente(c: ClienteDto, estadoSync: String = ReservaLocal.SINCRONIZADO) {
        if (c.eliminado) { q.borrarCliente(c.uuid); return }
        q.guardarCliente(c.uuid, c.id, c.nombre, c.telefono, JsonCv.encodeToString(ClienteDto.serializer(), c), estadoSync)
    }

    fun cliente(uuid: String): ClienteLocal? = q.clientePorUuid(uuid).executeAsOneOrNull()?.let { ClienteLocal(dec(it.json), it.estadoSync) }
    fun clientePorTelefono(tel: String): ClienteLocal? = q.clientePorTelefono(tel).executeAsOneOrNull()?.let { ClienteLocal(dec(it.json), it.estadoSync) }
    fun buscarClientes(texto: String): List<ClienteLocal> = q.buscarClientes(texto).executeAsList().map { ClienteLocal(dec(it.json), it.estadoSync) }

    /** El servidor unió un cliente creado sin conexión a otro ya existente (mismo teléfono). */
    fun reemplazarCliente(provisional: String, canonico: ClienteDto) {
        db.transaction {
            q.borrarCliente(provisional)
            guardarCliente(canonico)
            q.cambiarClienteDeReservas(canonico.uuid, provisional)
        }
    }

    // ── Reservas ──────────────────────────────────────────────────────────────
    fun guardarReserva(r: ReservaDto, estadoSync: String = ReservaLocal.SINCRONIZADO, error: String? = null) {
        val ini = Fechas.parse(r.inicio)?.toEpochMilliseconds() ?: return
        val fin = Fechas.parse(r.fin)?.toEpochMilliseconds() ?: return
        q.guardarReserva(r.uuid, r.canchaId, r.clienteUuid, ini, fin, r.estado,
            JsonCv.encodeToString(ReservaDto.serializer(), r.copy(pagos = null, eventos = null, pagosInformados = null)), estadoSync, error)
        r.pagos?.forEach { guardarPago(it.copy(reservaUuid = it.reservaUuid ?: r.uuid)) }
    }

    fun reserva(uuid: String): ReservaLocal? = q.reservaPorUuid(uuid).executeAsOneOrNull()?.let { ReservaLocal(decR(it.json), it.estadoSync, it.errorSync) }

    fun observarReserva(uuid: String): Flow<ReservaLocal?> = q.reservaPorUuid(uuid).asFlow().mapToOneOrNull(ctx)
        .map { it?.let { r -> ReservaLocal(decR(r.json), r.estadoSync, r.errorSync) } }

    fun reservasEntre(desde: Instant, hasta: Instant): List<ReservaLocal> =
        q.reservasEntre(desde.toEpochMilliseconds(), hasta.toEpochMilliseconds()).executeAsList().map { ReservaLocal(decR(it.json), it.estadoSync, it.errorSync) }

    fun observarReservasEntre(desde: Instant, hasta: Instant): Flow<List<ReservaLocal>> =
        q.reservasEntre(desde.toEpochMilliseconds(), hasta.toEpochMilliseconds()).asFlow().mapToList(ctx)
            .map { l -> l.map { ReservaLocal(decR(it.json), it.estadoSync, it.errorSync) } }

    fun observarReservasTodas(): Flow<List<ReservaLocal>> = q.reservasTodas().asFlow().mapToList(ctx)
        .map { l -> l.map { ReservaLocal(decR(it.json), it.estadoSync, it.errorSync) } }

    fun reservasDeCliente(clienteUuid: String): List<ReservaLocal> =
        q.reservasDeCliente(clienteUuid).executeAsList().map { ReservaLocal(decR(it.json), it.estadoSync, it.errorSync) }

    // ── Pagos ─────────────────────────────────────────────────────────────────
    fun guardarPago(p: PagoDto, estadoSync: String = ReservaLocal.SINCRONIZADO) {
        val reservaUuid = p.reservaUuid ?: return
        val ms = Fechas.parse(p.pagadoEn)?.toEpochMilliseconds() ?: 0L
        q.guardarPago(p.uuid, reservaUuid, ms, JsonCv.encodeToString(PagoDto.serializer(), p), estadoSync)
    }

    fun observarPagos(reservaUuid: String): Flow<List<PagoLocal>> = q.pagosDeReserva(reservaUuid).asFlow().mapToList(ctx)
        .map { l -> l.map { PagoLocal(JsonCv.decodeFromString(it.json), it.estadoSync) } }

    fun pagosDeReserva(reservaUuid: String): List<PagoLocal> = q.pagosDeReserva(reservaUuid).executeAsList().map { PagoLocal(JsonCv.decodeFromString(it.json), it.estadoSync) }

    fun pagosEntre(desde: Instant, hasta: Instant): List<PagoLocal> =
        q.pagosEntre(desde.toEpochMilliseconds(), hasta.toEpochMilliseconds()).executeAsList().map { PagoLocal(JsonCv.decodeFromString(it.json), it.estadoSync) }

    // ── Cola de operaciones ───────────────────────────────────────────────────
    fun encolar(tipo: String, entidadUuid: String?, datos: JsonObject): String {
        val op = Uuid.random().toString()
        db.transaction {
            val orden = q.siguienteOrden().executeAsOne()
            q.encolarOperacion(op, tipo, entidadUuid, datos.toString(), Fechas.iso(Fechas.ahora()), orden)
        }
        return op
    }

    fun operaciones(): List<OperacionLocal> = q.operacionesPendientes().executeAsList().map {
        OperacionLocal(it.opUuid, it.tipo, it.entidadUuid, JsonCv.decodeFromString(it.datosJson), it.creadaEn, it.intentos, it.ultimoError)
    }

    fun observarOperaciones(): Flow<List<OperacionLocal>> = q.operacionesPendientes().asFlow().mapToList(ctx).map { l ->
        l.map { OperacionLocal(it.opUuid, it.tipo, it.entidadUuid, JsonCv.decodeFromString(it.datosJson), it.creadaEn, it.intentos, it.ultimoError) }
    }

    fun cantidadOperaciones(): Long = q.contarOperaciones().executeAsOne()
    fun observarCantidadOperaciones(): Flow<Long> = q.contarOperaciones().asFlow().mapToOne(ctx)
    fun borrarOperacion(opUuid: String) = q.borrarOperacion(opUuid)
    fun borrarPago(uuid: String) = q.borrarPago(uuid)
    fun marcarErrorOperacion(opUuid: String, mensaje: String) = q.marcarErrorOperacion(mensaje, opUuid)

    /** Otra cuenta entra en este equipo: se borra la copia local (nunca si hay operaciones sin enviar). */
    fun vaciarDatosDeNegocio() {
        db.transaction {
            q.vaciarClientes(); q.vaciarReservas(); q.vaciarPagos(); q.vaciarDatos()
        }
    }

    private fun dec(json: String): ClienteDto = JsonCv.decodeFromString(json)
    private fun decR(json: String): ReservaDto = JsonCv.decodeFromString(json)

    companion object {
        const val DISPOSITIVO = "dispositivo_uuid"
        const val SESION = "sesion"
        const val CATALOGO = "catalogo"
        const val CURSOR = "cursor"
        const val ULTIMA_SYNC = "ultima_sync"
        const val ULTIMO_USUARIO = "ultimo_usuario"
        /** Marca de la base del servidor (negocio.creado_en) con la que se llenó la copia local. */
        const val MARCA_SERVIDOR = "marca_servidor"
        /** Ocupación de las canchas recibida en la última descarga (app del cliente). */
        const val OCUPACION = "ocupacion"
        /** Últimos avisos descargados (para verlos sin conexión) y el último mostrado como notificación. */
        const val NOTIFICACIONES = "notificaciones"
        const val ULTIMO_AVISO_MOSTRADO = "ultimo_aviso_mostrado"
        /** Reservas a las que ya se les recordó el plazo del adelanto (uuid separados por coma). */
        const val RECORDATORIOS_ADELANTO = "recordatorios_adelanto"
        /** Avisos leídos en el equipo sin conexión (id separados por coma), por enviar al servidor. */
        const val LECTURAS_PENDIENTES = "lecturas_pendientes"
    }
}
