package pe.canchavoley.app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pe.canchavoley.app.plataforma.ApiConfig
import pe.canchavoley.app.plataforma.HttpClientFactory
import pe.canchavoley.app.plataforma.MonitorServidor
import pe.canchavoley.app.plataforma.TokenEnCache
import pe.canchavoley.app.plataforma.hostYPuerto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlataformaTest {

    private fun cliente(estado: HttpStatusCode, token: String?, avisos: MutableList<String>) =
        HttpClientFactory.create(
            tokenProvider = { token },
            alSesionVencida = { avisos += "vencida" },
            motor = MockEngine { respond("""{"message":"Unauthenticated."}""", estado) },
        )

    @Test
    fun un_401_en_cualquier_pantalla_marca_la_sesion_vencida() = runTest {
        val avisos = mutableListOf<String>()
        cliente(HttpStatusCode.Unauthorized, "token-viejo", avisos).get("http://10.0.2.2:8001/api/cv/reservas")
        assertEquals(listOf("vencida"), avisos)
    }

    @Test
    fun otros_errores_o_el_login_no_cierran_la_sesion() = runTest {
        val avisos = mutableListOf<String>()
        cliente(HttpStatusCode.UnprocessableEntity, "token", avisos).get("http://x/api/cv/reservas")
        cliente(HttpStatusCode.Unauthorized, null, avisos).post("http://x${ApiConfig.LOGIN}")
        cliente(HttpStatusCode.Unauthorized, "token", avisos).post("http://x${ApiConfig.LOGIN}")
        assertEquals(emptyList(), avisos)
    }

    @Test
    fun el_token_se_lee_una_sola_vez_y_queda_en_memoria() = runTest {
        val disco = TokenFalso("abc")
        val cache = TokenEnCache(disco)
        assertNull(cache.actual)
        assertEquals("abc", cache.cargar())
        assertEquals("abc", cache.getToken())
        assertEquals("abc", cache.actual)
        assertEquals(1, disco.lecturas)
        cache.saveToken("nuevo")
        assertEquals("nuevo", cache.actual)
        assertEquals("nuevo", disco.token)
        cache.clearToken()
        assertNull(cache.actual)
        assertNull(disco.token)
        assertEquals(1, disco.lecturas)
    }

    @Test
    fun direccion_del_servidor_normalizada_y_validada() {
        assertEquals("http://192.168.1.10:8001", ApiConfig.normalizar(" 192.168.1.10:8001/ "))
        assertEquals("https://reservas.ejemplo.pe", ApiConfig.normalizar("https://reservas.ejemplo.pe/"))
        assertTrue(ApiConfig.esValida("192.168.1.10:8001"))
        assertFalse(ApiConfig.esValida("http://"))
        assertFalse(ApiConfig.esValida("192.168.1.10:99999"))
        assertEquals("192.168.1.10" to 8001, hostYPuerto("192.168.1.10:8001"))
        assertEquals("reservas.ejemplo.pe" to 443, hostYPuerto("https://reservas.ejemplo.pe/api"))
        assertEquals("pc-del-local" to 80, hostYPuerto("pc-del-local"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun el_monitor_sigue_al_servidor_y_revisa_antes_al_avisarle() = runTest {
        var responde = false
        var sondeos = 0
        val monitor = MonitorServidor(backgroundScope, sondear = { sondeos++; responde }, intervaloMs = 15_000, intervaloMaximoMs = 60_000)
        runCurrent()
        assertFalse(monitor.hayConexion())
        assertEquals(1, sondeos)

        responde = true
        advanceTimeBy(29_000)                 // sin conexión espera 30 s (15 s × 2) antes de reintentar
        runCurrent()
        assertFalse(monitor.hayConexion())
        assertEquals(1, sondeos)

        monitor.revisarAhora()                // p. ej. Android avisa que volvió el Wi-Fi
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(monitor.hayConexion())
        assertEquals(2, sondeos)

        responde = false
        advanceTimeBy(15_100)                 // en línea revisa cada 15 s
        runCurrent()
        assertFalse(monitor.hayConexion())
        assertEquals(3, sondeos)
    }
}
