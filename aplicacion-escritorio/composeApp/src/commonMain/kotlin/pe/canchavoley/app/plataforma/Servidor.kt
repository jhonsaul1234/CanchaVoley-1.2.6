package pe.canchavoley.app.plataforma

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import pe.edu.upeu.prolech.db.AppDatabase
import kotlin.concurrent.Volatile

/** Dirección del servidor Laravel de CanchaVoley. Se cambia desde la pantalla de acceso. */
object ApiConfig {
    /** Valor inicial en Android (emulador → PC). El escritorio usa http://127.0.0.1:8001. */
    const val SERVIDOR_POR_DEFECTO = "http://10.0.2.2:8001"

    @Volatile
    var BASE_URL: String = SERVIDOR_POR_DEFECTO
        private set

    const val LOGIN = "/api/login"
    const val LOGOUT = "/api/logout"

    /** Normaliza lo que escribe el usuario: agrega http:// y quita la "/" final y los espacios. */
    fun normalizar(url: String): String {
        val t = url.trim()
        val (esquema, resto) = when {
            t.startsWith("https://", ignoreCase = true) -> "https://" to t.substring(8)
            t.startsWith("http://", ignoreCase = true) -> "http://" to t.substring(7)
            else -> "http://" to t
        }
        return esquema + resto.trimEnd('/')
    }

    /** true si la dirección tiene forma de URL usable (http/https + host). */
    fun esValida(url: String): Boolean {
        val n = normalizar(url)
        val resto = n.substringAfter("://")
        val host = resto.substringBefore('/').substringBefore(':')
        val puerto = resto.substringBefore('/').substringAfter(':', "")
        return host.isNotBlank() && !host.contains(' ') && (puerto.isEmpty() || puerto.toIntOrNull()?.let { it in 1..65535 } == true)
    }

    fun cambiarServidor(url: String) {
        BASE_URL = normalizar(url)
    }
}

/** Ajustes del equipo guardados en SQLite (tabla Config). */
class ConfigLocal(database: AppDatabase) {
    private val queries = database.configQueries

    fun servidor(): String =
        queries.obtenerConfig(CLAVE_SERVIDOR).executeAsOneOrNull() ?: ApiConfig.SERVIDOR_POR_DEFECTO

    /** Guarda la dirección y la aplica de inmediato a todas las llamadas. */
    fun guardarServidor(url: String) {
        val normalizada = ApiConfig.normalizar(url)
        queries.guardarConfig(CLAVE_SERVIDOR, normalizada)
        ApiConfig.cambiarServidor(normalizada)
    }

    /** Se llama al iniciar la app. */
    fun aplicarServidorGuardado() = ApiConfig.cambiarServidor(servidor())

    /** true si nunca se guardó un servidor en este equipo. */
    fun sinServidorGuardado(): Boolean = queries.obtenerConfig(CLAVE_SERVIDOR).executeAsOneOrNull() == null

    private companion object {
        const val CLAVE_SERVIDOR = "servidor_api"
    }
}

/**
 * Cliente HTTP (Ktor) común a Windows y Android.
 * [tokenProvider] devuelve el token vigente (o null); [alSesionVencida] se llama cuando el servidor
 * rechaza el token (401) en cualquier pantalla, para volver al acceso sin borrar los datos locales.
 */
object HttpClientFactory {
    fun create(
        tokenProvider: () -> String?,
        alSesionVencida: () -> Unit = {},
        motor: HttpClientEngine? = null,
    ): HttpClient =
        if (motor != null) HttpClient(motor) { configurar(tokenProvider, alSesionVencida) }
        else HttpClient { configurar(tokenProvider, alSesionVencida) }

    private fun HttpClientConfig<*>.configurar(tokenProvider: () -> String?, alSesionVencida: () -> Unit) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
        // Red del local: fallar rápido y dejar lo hecho en la cola para enviarlo después.
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        defaultRequest {
            tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.Accept, "application/json")
        }
        HttpResponseValidator {
            validateResponse { respuesta ->
                val conToken = respuesta.call.request.headers[HttpHeaders.Authorization] != null
                if (respuesta.status.value == 401 && conToken &&
                    !respuesta.call.request.url.encodedPath.endsWith(ApiConfig.LOGIN)
                ) {
                    alSesionVencida()
                }
            }
        }
    }
}
