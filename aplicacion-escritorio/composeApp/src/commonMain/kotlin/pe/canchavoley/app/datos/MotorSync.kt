@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.datos

import pe.canchavoley.app.plataforma.RegistroFallos
import kotlinx.coroutines.CancellationException
import pe.canchavoley.app.plataforma.MonitorConectividad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import pe.canchavoley.app.dominio.Fechas

data class EstadoSync(
    val sincronizando: Boolean = false,
    val ultimaSync: String? = null,
    val error: String? = null,
)

/** Aviso para el personal cuando el servidor rechaza algo hecho sin conexión (hay que llamar al cliente). */
data class AvisoSync(val titulo: String, val mensaje: String, val reservaUuid: String? = null)

/**
 * CanchaVoley: sincronización automática (reutiliza la idea del SyncManager de ProLech: Mutex,
 * primero envía y luego descarga, se dispara al volver la conexión y un 401 detiene sin perder datos).
 *
 * Diferencia con ProLech: no hay «gana el último que escribe». Lo hecho sin conexión se guarda como
 * operaciones en orden; el servidor las valida con sus reglas y su respuesta manda (una reserva
 * offline nunca se confirma sin esa validación).
 */
class MotorSync(
    private val api: ApiCancha,
    private val almacen: AlmacenLocal,
    private val monitor: MonitorConectividad,
    private val scope: CoroutineScope,
    private val sesionActiva: suspend () -> Boolean,
    private val alSesionVencida: () -> Unit,
    private val intervaloMs: Long = 60_000,
    /** Después de una sincronización completa (p. ej. revisar avisos y mostrar notificaciones). */
    private val alSincronizar: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val _estado = MutableStateFlow(EstadoSync(ultimaSync = almacen.dato(AlmacenLocal.ULTIMA_SYNC)))
    val estado: StateFlow<EstadoSync> = _estado.asStateFlow()
    private val _avisos = MutableSharedFlow<AvisoSync>(extraBufferCapacity = 20)
    val avisos: SharedFlow<AvisoSync> = _avisos.asSharedFlow()
    private var trabajos: List<Job> = emptyList()

    fun iniciar() {
        if (trabajos.isNotEmpty()) return
        trabajos = listOf(
            // Al volver la conexión se sincroniza solo.
            scope.launch { monitor.enLinea.filter { it }.collect { sincronizar() } },
            // Con conexión, cada minuto se traen los cambios de los otros equipos.
            scope.launch {
                while (isActive) {
                    delay(intervaloMs)
                    if (monitor.hayConexion()) sincronizar()
                }
            },
        )
    }

    fun solicitar() { scope.launch { sincronizar() } }

    /** Envía la cola y luego descarga cambios. Devuelve un texto con el resultado. */
    suspend fun sincronizar(): String = mutex.withLock {
        if (!sesionActiva()) return "Sin sesión"
        _estado.value = _estado.value.copy(sincronizando = true, error = null)
        val resultado = try {
            val enviadas = enviar() ?: return@withLock terminar("Sin conexión")
            val ok = descargar()
            if (ok) {
                // Un fallo al revisar avisos nunca detiene la sincronización (se reintenta en la próxima vuelta).
                try { alSincronizar() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            if (!ok) terminar("Sin conexión") else terminar(null, "Sincronizado ($enviadas operaciones enviadas)")
        } catch (e: SesionVencidaCv) {
            alSesionVencida()
            terminar("Sesión vencida")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Un error inesperado no debe cerrar la app: se anota el informe y se reintenta en la próxima vuelta.
            RegistroFallos.anotar(e, "sincronización")
            terminar("Error al sincronizar: " + (e.message ?: e::class.simpleName ?: "desconocido"))
        }
        resultado
    }

    private fun terminar(error: String?, ok: String? = null): String {
        val ahora = if (error == null) Fechas.iso(Fechas.ahora()).also { almacen.guardarDato(AlmacenLocal.ULTIMA_SYNC, it) } else _estado.value.ultimaSync
        _estado.value = EstadoSync(sincronizando = false, ultimaSync = ahora, error = error)
        return error ?: ok.orEmpty()
    }

    /** @return operaciones aplicadas, o null si no hubo conexión. */
    private suspend fun enviar(): Int? {
        var aplicadas = 0
        while (true) {
            val ops = almacen.operaciones().take(50)
            if (ops.isEmpty()) return aplicadas
            val lote = LoteOperacionesDto(almacen.dispositivoUuid(), ops.map { OperacionDto(it.opUuid, it.tipo, it.entidadUuid, it.datos, it.creadaEn) })
            val respuesta = when (val r = api.enviarOperaciones(lote)) {
                is Resultado.Ok -> r.valor
                Resultado.SinConexion -> return null
                is Resultado.Error -> {
                    if (r.codigo == 401) throw SesionVencidaCv()
                    ops.forEach { almacen.marcarErrorOperacion(it.opUuid, r.mensaje) }
                    return aplicadas
                }
            }
            var detenerse = false
            for (res in respuesta.resultados) {
                val op = ops.firstOrNull { it.opUuid == res.opUuid } ?: continue
                when (res.resultado) {
                    "aplicada" -> { aplicar(op, res); almacen.borrarOperacion(op.opUuid); aplicadas++ }
                    "rechazada" -> { rechazada(op, res); almacen.borrarOperacion(op.opUuid) }
                    else -> { almacen.marcarErrorOperacion(op.opUuid, res.mensaje ?: "Error del servidor"); detenerse = true }
                }
                if (detenerse) break
            }
            if (detenerse || ops.size < 50) return aplicadas
        }
    }

    private fun aplicar(op: OperacionLocal, res: ResultadoOperacionDto) {
        guardarEntidad(res)
        if (op.tipo == "crear_cliente" && res.unidoAExistente) {
            val canonico = res.entidad?.let { JsonCv.decodeFromJsonElement(ClienteDto.serializer(), it) }
            if (canonico != null && op.entidadUuid != null && op.entidadUuid != canonico.uuid) {
                almacen.reemplazarCliente(op.entidadUuid, canonico)
            }
        }
        res.advertencia?.let { _avisos.tryEmit(AvisoSync("Atención", it, res.reserva?.uuid)) }
    }

    private fun rechazada(op: OperacionLocal, res: ResultadoOperacionDto) {
        if (res.entidad != null) {
            guardarEntidad(res)
        } else if (op.tipo == "crear_reserva" && op.entidadUuid != null) {
            almacen.reserva(op.entidadUuid)?.let { local ->
                almacen.guardarReserva(local.dto.copy(estado = "rechazada", motivoEstado = res.mensaje), ReservaLocal.RECHAZADA, res.mensaje)
            }
        } else if (op.entidadUuid != null) {
            // Cambio rechazado sobre algo existente: se vuelve a traer la versión del servidor en la descarga.
            almacen.reserva(op.entidadUuid)?.let { almacen.guardarReserva(it.dto, ReservaLocal.SINCRONIZADO, res.mensaje) }
        }
        val titulo = when (op.tipo) {
            "crear_reserva" -> "Reserva sin conexión rechazada"
            "registrar_pago" -> "Pago no registrado"
            else -> "Operación rechazada"
        }
        _avisos.tryEmit(AvisoSync(titulo, res.mensaje ?: "El servidor rechazó la operación.", op.entidadUuid.takeIf { op.tipo.contains("reserva") }))
    }

    private fun guardarEntidad(res: ResultadoOperacionDto) {
        val e = res.entidad ?: return
        when (res.entidadTipo) {
            "cliente" -> almacen.guardarCliente(JsonCv.decodeFromJsonElement(ClienteDto.serializer(), e))
            "reserva" -> almacen.guardarReserva(JsonCv.decodeFromJsonElement(ReservaDto.serializer(), e))
            "pago" -> {
                val pago = JsonCv.decodeFromJsonElement(PagoDto.serializer(), e)
                almacen.guardarPago(pago)
                res.reserva?.let { almacen.guardarReserva(it) }
            }
        }
    }

    /** @return false si no hubo conexión. */
    private suspend fun descargar(): Boolean {
        val c = when (val r = api.cambios(almacen.dato(AlmacenLocal.CURSOR))) {
            is Resultado.Ok -> r.valor
            Resultado.SinConexion -> return false
            is Resultado.Error -> { if (r.codigo == 401) throw SesionVencidaCv(); _estado.value = _estado.value.copy(error = r.mensaje); return true }
        }
        // Las descargas incrementales traen solo los cierres que cambiaron: se combinan con los guardados.
        almacen.guardarCatalogo(if (c.cargaCompleta) c.catalogo else c.catalogo.conBloqueosCombinados(almacen.catalogo()?.bloqueos.orEmpty()))
        // App del cliente: ocupación de las canchas para consultar horarios sin conexión.
        c.ocupacion?.let { almacen.guardarOcupacion(it) }
        val conCambiosLocales = almacen.operaciones().mapNotNull { it.entidadUuid }.toSet()
        c.clientes.forEach { if (it.uuid !in conCambiosLocales) almacen.guardarCliente(it) }
        c.reservas.forEach { if (it.uuid !in conCambiosLocales) almacen.guardarReserva(it) }
        c.pagos.forEach { if (it.uuid !in conCambiosLocales) almacen.guardarPago(it) }
        almacen.guardarDato(AlmacenLocal.CURSOR, c.cursor)
        return true
    }

    /** Operación que se ejecuta ahora mismo (en línea): se guarda en la cola si no hay conexión. */
    fun encolar(tipo: String, entidadUuid: String?, datos: JsonObject): String {
        val op = almacen.encolar(tipo, entidadUuid, datos)
        if (monitor.hayConexion()) solicitar()
        return op
    }
}

class SesionVencidaCv : Exception("Sesión vencida")
