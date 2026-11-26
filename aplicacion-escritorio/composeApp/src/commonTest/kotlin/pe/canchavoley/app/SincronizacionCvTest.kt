@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app

import pe.canchavoley.app.plataforma.EstadoSesion
import pe.canchavoley.app.plataforma.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.canchavoley.app.datos.AlmacenLocal
import pe.canchavoley.app.datos.ApiCancha
import pe.canchavoley.app.datos.ClienteDto
import pe.canchavoley.app.datos.GestorSesion
import pe.canchavoley.app.datos.JsonCv
import pe.canchavoley.app.datos.MotorSync
import pe.canchavoley.app.datos.NuevaReserva
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.ReservaLocal
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.dominio.Fechas
import pe.edu.upeu.prolech.db.AppDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Servidor falso: decide la respuesta de cada operación según `decidir`. */
private class ServidorFalso {
    var conectado = true
    var decidir: (JsonObject) -> String = { op -> aplicada(op) }
    val opRecibidas = mutableListOf<String>()

    val motor = MockEngine { req ->
        if (!conectado) throw IOException("sin red")
        val ruta = req.url.encodedPath
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            ruta.endsWith("/api/cv/sync/operaciones") -> {
                val cuerpo = Json.parseToJsonElement(String((req.body as io.ktor.http.content.TextContent).text.toByteArray())).jsonObject
                val res = cuerpo["operaciones"]!!.jsonArray.map { op ->
                    opRecibidas += op.jsonObject["op_uuid"]!!.jsonPrimitive.content
                    decidir(op.jsonObject)
                }
                respond("""{"resultados":[${res.joinToString(",")}]}""", HttpStatusCode.OK, json)
            }
            ruta.endsWith("/api/cv/sync/cambios") ->
                respond("""{"servidor_ahora":"2030-06-04T10:00:00-05:00","cursor":"2030-06-04T09:58:00-05:00","catalogo":${JsonCv.encodeToString(pe.canchavoley.app.datos.CatalogoDto.serializer(), catalogoDePrueba())},"clientes":[],"reservas":[],"pagos":[]}""", HttpStatusCode.OK, json)
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    companion object {
        fun reservaJson(op: JsonObject, estado: String): String {
            val d = op["datos"]!!.jsonObject
            return """{"uuid":${d["uuid"]},"cancha_id":1,"inicio":${d["inicio"]},"fin":${d["fin"]},"estado":"$estado","duracion_min":60,"codigo_qr":"QRPRUEBA"}"""
        }
        fun aplicada(op: JsonObject) = """{"op_uuid":${op["op_uuid"]},"resultado":"aplicada","entidad_tipo":"reserva","entidad":${reservaJson(op, "confirmada")}}"""
        fun rechazada(op: JsonObject) = """{"op_uuid":${op["op_uuid"]},"resultado":"rechazada","codigo":409,"mensaje":"Ese horario ya está ocupado.","entidad_tipo":"reserva","entidad":${reservaJson(op, "rechazada")}}"""
        fun error500(op: JsonObject) = """{"op_uuid":${op["op_uuid"]},"resultado":"error","codigo":500,"mensaje":"Error del servidor; se reintentará."}"""
    }
}

class SincronizacionCvTest {
    private val servidor = ServidorFalso()
    private val monitor = MonitorConectividadFalso(false)
    private val almacen = AlmacenLocal(AppDatabase(crearDriverEnMemoria())).apply {
        guardarCatalogo(catalogoDePrueba())
        guardarCliente(ClienteDto(uuid = "cli-1", nombre = "Cliente Prueba", telefono = "51900000001"))
    }
    private val api = ApiCancha(HttpClientFactory.create(tokenProvider = { "token-prueba" }, motor = servidor.motor)) { "http://servidor" }
    private val tokens = TokenFalso("token-prueba")
    private val motorSync = MotorSync(api, almacen, monitor, CoroutineScope(SupervisorJob()), sesionActiva = { true }, alSesionVencida = {})
    private val repo = Repositorio(api, almacen, monitor, motorSync, GestorSesion(api, almacen, tokens, EstadoSesion(), "prueba"))

    private suspend fun reservarSinConexion(h: Int) = repo.crearReserva(NuevaReserva(1, "cli-1", t(h), t(h + 1), precioFinal = "30.00"))

    @Test
    fun sin_conexion_queda_por_sincronizar_y_nunca_confirmada() = runTest {
        val r = reservarSinConexion(10)
        assertIs<ResultadoAccion.GuardadoSinConexion<ReservaDto>>(r)
        val local = almacen.reserva(r.valor.uuid)!!
        assertEquals(ReservaLocal.ESTADO_POR_SINCRONIZAR, local.dto.estado)
        assertTrue(local.sinSincronizar)
        assertEquals(1, almacen.cantidadOperaciones())
    }

    @Test
    fun sin_conexion_no_deja_registrar_un_choque_evidente_desde_el_mismo_equipo() = runTest {
        reservarSinConexion(10)
        val otra = repo.crearReserva(NuevaReserva(1, "cli-1", t(10, 30), t(11, 30), precioFinal = "30.00"))
        assertIs<ResultadoAccion.Fallo>(otra)
        assertTrue(otra.sugerencias.isNotEmpty())
        assertEquals(1, almacen.cantidadOperaciones())
    }

    @Test
    fun al_volver_la_conexion_el_servidor_confirma_y_la_cola_se_vacia() = runTest {
        val r = reservarSinConexion(10) as ResultadoAccion.GuardadoSinConexion
        monitor.enLinea.value = true
        motorSync.sincronizar()
        val local = almacen.reserva(r.valor.uuid)!!
        assertEquals("confirmada", local.dto.estado)
        assertEquals("QRPRUEBA", local.dto.codigoQr)
        assertEquals(ReservaLocal.SINCRONIZADO, local.estadoSync)
        assertEquals(0, almacen.cantidadOperaciones())
    }

    @Test
    fun un_error_inesperado_al_sincronizar_no_cierra_la_app_y_conserva_la_operacion() = runTest {
        val antes = pe.canchavoley.app.plataforma.RegistroFallos.guardar
        val informes = mutableListOf<String>()
        try {
            pe.canchavoley.app.plataforma.RegistroFallos.guardar = { informes += it }
            reservarSinConexion(10)
            // Respuesta del servidor que no se puede leer: antes la excepción salía de la sincronización
            // y en Android cerraba la app.
            servidor.decidir = { op -> """{"op_uuid":${op["op_uuid"]},"resultado":"aplicada","entidad_tipo":"reserva","entidad":{"no_es":"una reserva"}}""" }
            monitor.enLinea.value = true
            val resultado = motorSync.sincronizar()
            assertTrue(resultado.startsWith("Error al sincronizar"), resultado)
            assertTrue(motorSync.estado.value.error!!.startsWith("Error al sincronizar"))
            assertEquals(1, almacen.cantidadOperaciones())
            assertTrue(informes.single().contains("Dónde: sincronización"))
        } finally {
            pe.canchavoley.app.plataforma.RegistroFallos.guardar = antes
        }
    }

    @Test
    fun si_el_servidor_la_rechaza_queda_rechazada_y_avisa() = runTest {
        val r = reservarSinConexion(10) as ResultadoAccion.GuardadoSinConexion
        servidor.decidir = ServidorFalso::rechazada
        monitor.enLinea.value = true
        motorSync.sincronizar()
        assertEquals("rechazada", almacen.reserva(r.valor.uuid)!!.dto.estado)
        assertEquals(0, almacen.cantidadOperaciones())
    }

    @Test
    fun error_del_servidor_o_sin_red_conserva_la_operacion_con_el_mismo_identificador() = runTest {
        reservarSinConexion(10)
        monitor.enLinea.value = true
        servidor.decidir = ServidorFalso::error500
        motorSync.sincronizar()
        assertEquals(1, almacen.cantidadOperaciones())
        assertEquals(1L, almacen.operaciones().single().intentos)

        servidor.conectado = false
        motorSync.sincronizar()
        assertEquals(1, almacen.cantidadOperaciones())

        servidor.conectado = true
        servidor.decidir = ServidorFalso::aplicada
        motorSync.sincronizar()
        assertEquals(0, almacen.cantidadOperaciones())
        // El reintento usó siempre el mismo op_uuid: el servidor no puede duplicarla.
        assertEquals(1, servidor.opRecibidas.distinct().size)
        assertEquals(2, servidor.opRecibidas.size)
    }

    @Test
    fun las_operaciones_se_envian_en_el_orden_en_que_se_hicieron() = runTest {
        val a = reservarSinConexion(10) as ResultadoAccion.GuardadoSinConexion
        val b = reservarSinConexion(12) as ResultadoAccion.GuardadoSinConexion
        val ops = almacen.operaciones()
        assertEquals(listOf(a.valor.uuid, b.valor.uuid), ops.map { it.entidadUuid })
        monitor.enLinea.value = true
        motorSync.sincronizar()
        assertEquals(ops.map { it.opUuid }, servidor.opRecibidas)
    }

    @Test
    fun la_descarga_guarda_el_catalogo_y_el_cursor() = runTest {
        monitor.enLinea.value = true
        motorSync.sincronizar()
        assertEquals("2030-06-04T09:58:00-05:00", almacen.dato(AlmacenLocal.CURSOR))
        assertEquals(1, almacen.catalogo()!!.canchas.size)
        assertTrue(motorSync.estado.value.ultimaSync != null)
    }
    @Test
    fun una_operacion_que_falla_siempre_se_puede_descartar_sin_perder_las_demas() = runTest {
        val a = reservarSinConexion(10) as ResultadoAccion.GuardadoSinConexion
        reservarSinConexion(12)
        monitor.enLinea.value = true
        servidor.decidir = { op -> if (op["entidad_uuid"]?.jsonPrimitive?.content == a.valor.uuid) ServidorFalso.error500(op) else ServidorFalso.aplicada(op) }
        motorSync.sincronizar()
        motorSync.sincronizar()
        // La primera bloquea la cola (se envían en orden) y acumula intentos.
        assertEquals(2, almacen.cantidadOperaciones())
        assertTrue(almacen.operaciones().first().intentos >= 2)

        repo.descartarOperacion(almacen.operaciones().first().opUuid)
        val descartada = almacen.reserva(a.valor.uuid)!!
        assertEquals("rechazada", descartada.dto.estado)
        assertEquals(ReservaLocal.RECHAZADA, descartada.estadoSync)
        motorSync.sincronizar()
        assertEquals(0, almacen.cantidadOperaciones())                 // la segunda ya se envió
    }

    @Test
    fun cambiar_precio_y_notas_sin_conexion_queda_en_cola() = runTest {
        val r = ReservaDto(uuid = "res-1", canchaId = 1, inicio = Fechas.iso(t(18)), fin = Fechas.iso(t(19)), estado = "confirmada", precioFinal = "40.00")
        almacen.guardarReserva(r)
        val res = repo.accionReserva("res-1", "editar", mapOf("precio_final" to "35.00", "notas" to "Descuento por cliente frecuente"))
        assertIs<ResultadoAccion.GuardadoSinConexion<*>>(res)
        val local = almacen.reserva("res-1")!!
        assertEquals("35.00", local.dto.precioFinal)
        assertEquals("Descuento por cliente frecuente", local.dto.notas)
        val op = almacen.operaciones().single()
        assertEquals("editar_reserva", op.tipo)
        assertEquals("res-1", op.datos["reserva_uuid"]?.jsonPrimitive?.content)
    }
}
