package pe.canchavoley.app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import pe.canchavoley.app.datos.AlmacenLocal
import pe.canchavoley.app.datos.ApiCancha
import pe.canchavoley.app.datos.GestorSesion
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.Resultado
import pe.canchavoley.app.plataforma.EstadoSesion
import pe.canchavoley.app.plataforma.HttpClientFactory
import pe.edu.upeu.prolech.db.AppDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Inicio de sesión cuando la base del servidor se volvió a crear (por ejemplo, al reiniciar la base de
 * demostración): la copia local vieja no se mezcla con los datos nuevos.
 */
class SesionTest {
    private var marcaDelServidor: String? = "2026-11-18T22:59:00-05:00"
    private val servidor = MockEngine { req ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val ruta = req.url.encodedPath
        when {
            ruta.endsWith("/api/login") -> respond("""{"token":"token-nuevo"}""", HttpStatusCode.OK, json)
            ruta.endsWith("/api/cv/sesion") -> {
                val marca = marcaDelServidor?.let { ""","creado_en":"$it"""" } ?: ""
                respond(
                    """{"usuario":{"id":1,"nombre":"Administradora","email":"admin@prueba.test","rol":"admin"},""" +
                        """"es_personal":true,"es_admin":true,"negocio":{"id":1,"nombre":"Negocio de prueba"$marca}}""",
                    HttpStatusCode.OK, json,
                )
            }
            else -> respondError(HttpStatusCode.NotFound)
        }
    }
    private val almacen = AlmacenLocal(AppDatabase(crearDriverEnMemoria()))
    private val tokens = TokenFalso()
    private val api = ApiCancha(HttpClientFactory.create(tokenProvider = { tokens.token }, motor = servidor)) { "http://servidor" }
    private val gestor = GestorSesion(api, almacen, tokens, EstadoSesion(), "prueba")

    private fun guardarReservaVieja() = almacen.guardarReserva(
        ReservaDto(uuid = "reserva-vieja", canchaId = 1, inicio = "2026-11-20T18:00:00-05:00", fin = "2026-11-20T19:00:00-05:00", estado = "confirmada"),
    )

    private suspend fun entrar() = assertIs<Resultado.Ok<*>>(gestor.iniciar("admin@prueba.test", "clave"))

    @Test
    fun la_misma_cuenta_y_la_misma_base_conservan_la_copia_local() = runTest {
        entrar()
        guardarReservaVieja()
        entrar()
        assertNotNull(almacen.reserva("reserva-vieja"))
        assertEquals(marcaDelServidor, almacen.dato(AlmacenLocal.MARCA_SERVIDOR))
    }

    @Test
    fun si_la_base_del_servidor_se_volvio_a_crear_se_descarta_la_copia_vieja() = runTest {
        entrar()
        guardarReservaVieja()
        marcaDelServidor = "2026-11-19T06:36:00-05:00"     // se reinició la base de demostración
        entrar()
        assertNull(almacen.reserva("reserva-vieja"))
        assertEquals("2026-11-19T06:36:00-05:00", almacen.dato(AlmacenLocal.MARCA_SERVIDOR))
        assertEquals("admin@prueba.test", almacen.dato(AlmacenLocal.ULTIMO_USUARIO))
    }

    @Test
    fun con_operaciones_sin_enviar_no_se_borra_nada() = runTest {
        entrar()
        guardarReservaVieja()
        almacen.encolar("crear_reserva", "reserva-vieja", JsonObject(emptyMap()))
        marcaDelServidor = "2026-11-19T06:36:00-05:00"
        entrar()
        assertNotNull(almacen.reserva("reserva-vieja"))
        assertEquals(1L, almacen.cantidadOperaciones())
    }

    @Test
    fun un_servidor_sin_marca_no_borra_la_copia_local() = runTest {
        marcaDelServidor = null
        entrar()
        guardarReservaVieja()
        entrar()
        assertNotNull(almacen.reserva("reserva-vieja"))
    }
}
