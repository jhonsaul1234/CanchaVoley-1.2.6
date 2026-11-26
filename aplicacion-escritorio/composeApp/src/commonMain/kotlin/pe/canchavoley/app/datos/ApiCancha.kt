package pe.canchavoley.app.datos

import pe.canchavoley.app.plataforma.ApiConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Resultado de una llamada a la API: éxito, error del servidor (con su mensaje) o sin conexión. */
sealed interface Resultado<out T> {
    data class Ok<T>(val valor: T) : Resultado<T>
    data class Error(val codigo: Int, val mensaje: String, val detalle: ErrorApiDto? = null) : Resultado<Nothing>
    data object SinConexion : Resultado<Nothing>
}

inline fun <T, R> Resultado<T>.map(f: (T) -> R): Resultado<R> = when (this) {
    is Resultado.Ok -> Resultado.Ok(f(valor))
    is Resultado.Error -> this
    Resultado.SinConexion -> Resultado.SinConexion
}

/** Cliente de la API de CanchaVoley (/api/cv). Usa el HttpClient de la app (token y 401 ya configurados). */
class ApiCancha(private val http: HttpClient, private val base: () -> String = { ApiConfig.BASE_URL }) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    private fun u(ruta: String) = base().trimEnd('/') + ruta

    suspend fun login(email: String, password: String, dispositivo: String): Resultado<String> =
        llamar<LoginRespuestaDto> { http.post(u("/api/login")) { cuerpo(mapOf("email" to email, "password" to password, "dispositivo" to dispositivo)) } }
            .map { it.token }

    suspend fun registro(nombre: String, email: String, password: String, telefono: String, dispositivo: String): Resultado<SesionDto> =
        llamar { http.post(u("/api/cv/registro")) { cuerpo(mapOf("nombre" to nombre, "email" to email, "password" to password, "telefono" to telefono, "dispositivo" to dispositivo)) } }

    suspend fun logout(): Resultado<Unit> = llamar<JsonObject> { http.post(u("/api/logout")) }.map { }

    suspend fun sesion(): Resultado<SesionDto> = llamar { http.get(u("/api/cv/sesion")) }

    suspend fun catalogo(): Resultado<CatalogoDto> = llamar { http.get(u("/api/cv/catalogo")) }

    suspend fun disponibilidad(canchaId: Long, fecha: String): Resultado<DisponibilidadDto> =
        llamar { http.get(u("/api/cv/disponibilidad")) { parameter("cancha_id", canchaId); parameter("fecha", fecha) } }

    suspend fun recomendaciones(canchaId: Long, fecha: String, duracionMin: Int, hora: String?): Resultado<SugerenciasDto> =
        llamar { http.get(u("/api/cv/recomendaciones")) {
            parameter("cancha_id", canchaId); parameter("fecha", fecha); parameter("duracion_min", duracionMin)
            if (hora != null) parameter("hora", hora)
        } }

    // ── Reservas ──────────────────────────────────────────────────────────────
    suspend fun reservas(desde: String?, hasta: String?): Resultado<List<ReservaDto>> =
        llamar { http.get(u("/api/cv/reservas")) { desde?.let { parameter("desde", it) }; hasta?.let { parameter("hasta", it) } } }

    suspend fun reserva(uuid: String): Resultado<ReservaDto> = llamar { http.get(u("/api/cv/reservas/$uuid")) }

    suspend fun crearReserva(datos: JsonObject): Resultado<ReservaDto> = llamar { http.post(u("/api/cv/reservas")) { cuerpo(datos) } }

    suspend fun accionReserva(uuid: String, accion: String, datos: Map<String, Any?> = emptyMap()): Resultado<ReservaDto> =
        llamar { http.post(u("/api/cv/reservas/$uuid/$accion")) { cuerpo(datos) } }

    suspend fun porQr(codigo: String): Resultado<ReservaDto> = llamar { http.get(u("/api/cv/qr/${codigo.trim()}")) }

    // ── Clientes ──────────────────────────────────────────────────────────────
    suspend fun clientes(q: String): Resultado<List<ClienteDto>> = llamar { http.get(u("/api/cv/clientes")) { parameter("q", q) } }

    suspend fun clientePorTelefono(telefono: String): Resultado<FichaClienteDto> = llamar { http.get(u("/api/cv/clientes/telefono/$telefono")) }

    suspend fun fichaCliente(id: Long): Resultado<FichaClienteDto> = llamar { http.get(u("/api/cv/clientes/$id")) }

    suspend fun crearCliente(datos: JsonObject): Resultado<ClienteDto> = llamar { http.post(u("/api/cv/clientes")) { cuerpo(datos) } }

    suspend fun actualizarCliente(id: Long, datos: Map<String, Any?>): Resultado<ClienteDto> = llamar { http.put(u("/api/cv/clientes/$id")) { cuerpo(datos) } }

    // ── Pagos y caja ──────────────────────────────────────────────────────────
    suspend fun registrarPago(datos: JsonObject): Resultado<RespuestaPagoDto> = llamar { http.post(u("/api/cv/pagos")) { cuerpo(datos) } }

    suspend fun anularPago(uuid: String, motivo: String): Resultado<RespuestaPagoDto> =
        llamar { http.post(u("/api/cv/pagos/$uuid/anular")) { cuerpo(mapOf("motivo" to motivo)) } }

    suspend fun caja(fecha: String): Resultado<CajaDto> = llamar { http.get(u("/api/cv/caja")) { parameter("fecha", fecha) } }

    // ── Administración ────────────────────────────────────────────────────────
    suspend fun reporte(desde: String, hasta: String): Resultado<ReporteOcupacionDto> =
        llamar { http.get(u("/api/cv/reportes/ocupacion")) { parameter("desde", desde); parameter("hasta", hasta) } }

    suspend fun guardarNegocio(datos: Map<String, Any?>): Resultado<NegocioDto> = llamar { http.put(u("/api/cv/negocio")) { cuerpo(datos) } }

    suspend fun crearCancha(nombre: String): Resultado<CanchaDto> = llamar { http.post(u("/api/cv/canchas")) { cuerpo(mapOf("nombre" to nombre)) } }

    suspend fun actualizarCancha(id: Long, datos: Map<String, Any?>): Resultado<CanchaDto> = llamar { http.put(u("/api/cv/canchas/$id")) { cuerpo(datos) } }

    suspend fun guardarHorarios(canchaId: Long, horarios: List<Map<String, Any?>>): Resultado<List<HorarioDto>> =
        llamar { http.put(u("/api/cv/canchas/$canchaId/horarios")) { cuerpo(mapOf("horarios" to horarios)) } }

    suspend fun guardarTarifas(canchaId: Long, tarifas: List<Map<String, Any?>>, vigenteDesde: String?): Resultado<List<TarifaDto>> =
        llamar { http.put(u("/api/cv/canchas/$canchaId/tarifas")) { cuerpo(mapOf("tarifas" to tarifas, "vigente_desde" to vigenteDesde)) } }

    suspend fun copiarConfiguracion(canchaId: Long, desde: Long): Resultado<JsonObject> =
        llamar { http.post(u("/api/cv/canchas/$canchaId/copiar-configuracion")) { cuerpo(mapOf("desde_cancha_id" to desde)) } }

    suspend fun guardarReglas(datos: Map<String, Any?>): Resultado<ReglasDto> = llamar { http.put(u("/api/cv/reglas")) { cuerpo(datos) } }

    suspend fun crearBloqueo(datos: Map<String, Any?>): Resultado<BloqueoCreadoDto> = llamar { http.post(u("/api/cv/bloqueos")) { cuerpo(datos) } }

    suspend fun eliminarBloqueo(id: Long): Resultado<BloqueoDto> = llamar { http.delete(u("/api/cv/bloqueos/$id")) }

    suspend fun usuarios(): Resultado<List<UsuarioPersonalDto>> = llamar { http.get(u("/api/cv/usuarios")) }

    suspend fun crearUsuario(datos: Map<String, Any?>): Resultado<UsuarioPersonalDto> = llamar { http.post(u("/api/cv/usuarios")) { cuerpo(datos) } }

    suspend fun actualizarUsuario(id: Long, datos: Map<String, Any?>): Resultado<UsuarioPersonalDto> = llamar { http.put(u("/api/cv/usuarios/$id")) { cuerpo(datos) } }

    suspend fun notificaciones(): Resultado<NotificacionesCvDto> = llamar { http.get(u("/api/cv/notificaciones")) }

    suspend fun leerNotificaciones(): Resultado<JsonObject> = llamar { http.post(u("/api/cv/notificaciones/leer")) }

    suspend fun leerNotificacion(id: Long): Resultado<JsonObject> = llamar { http.post(u("/api/cv/notificaciones/$id/leer")) }

    // ── Pago informado por el cliente (22/11/2026) ────────────────────────────
    suspend fun informarPago(reservaUuid: String, datos: Map<String, Any?>): Resultado<RespuestaPagoInformadoDto> =
        llamar { http.post(u("/api/cv/reservas/$reservaUuid/informar-pago")) { cuerpo(datos) } }

    suspend fun descartarPagoInformado(uuid: String, motivo: String): Resultado<RespuestaPagoInformadoDto> =
        llamar { http.post(u("/api/cv/pagos-informados/$uuid/descartar")) { cuerpo(mapOf("motivo" to motivo)) } }

    // ── Sincronización ────────────────────────────────────────────────────────
    suspend fun enviarOperaciones(lote: LoteOperacionesDto): Resultado<RespuestaLoteDto> = llamar { http.post(u("/api/cv/sync/operaciones")) { cuerpo(lote) } }

    suspend fun cambios(desde: String?): Resultado<CambiosDto> = llamar { http.get(u("/api/cv/sync/cambios")) { desde?.let { parameter("desde", it) } } }

    // ── Apoyo ─────────────────────────────────────────────────────────────────
    private fun HttpRequestBuilder.cuerpo(valor: Any) {
        contentType(ContentType.Application.Json)
        setBody(
            when (valor) {
                is JsonObject -> valor.toString()
                is LoteOperacionesDto -> json.encodeToString(LoteOperacionesDto.serializer(), valor)
                is Map<*, *> -> Json.aJson(valor).toString()
                else -> valor.toString()
            }
        )
    }

    private suspend inline fun <reified T> llamar(bloque: () -> HttpResponse): Resultado<T> {
        val respuesta = try {
            bloque()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Resultado.SinConexion
        }
        val texto = try { respuesta.bodyAsText() } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
        if (respuesta.status.isSuccess()) {
            return try {
                Resultado.Ok(json.decodeFromString<T>(texto))
            } catch (e: Exception) {
                Resultado.Error(respuesta.status.value, "Respuesta inesperada del servidor.")
            }
        }
        val error = try { json.decodeFromString<ErrorApiDto>(texto) } catch (e: Exception) { null }
        val mensaje = error?.errors?.values?.flatten()?.firstOrNull()
            ?: error?.message
            ?: when (respuesta.status.value) {
                401 -> "Tu sesión venció. Vuelve a iniciar sesión."
                403 -> "No tienes permisos para esta acción."
                404 -> "No encontrado."
                else -> "Error del servidor (${respuesta.status.value})."
            }
        return Resultado.Error(respuesta.status.value, mensaje, error)
    }
}
