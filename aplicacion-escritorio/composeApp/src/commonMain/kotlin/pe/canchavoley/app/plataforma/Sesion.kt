package pe.canchavoley.app.plataforma

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Almacén del token de sesión (Bearer de Sanctum). Cada plataforma lo guarda cifrado:
 * Windows con DPAPI (ligado al usuario de Windows) y Android con una clave del Android Keystore.
 */
interface TokenStorage {
    suspend fun saveToken(token: String)
    suspend fun getToken(): String?
    suspend fun clearToken()
}

/**
 * Copia en memoria del token: las peticiones HTTP leen [actual] sin tocar el disco ni descifrar
 * en cada llamada. Se carga una vez al iniciar ([cargar]) y se mantiene al día al guardar o borrar.
 */
class TokenEnCache(private val almacen: TokenStorage) : TokenStorage {
    private val mutex = Mutex()
    @Volatile private var cargado = false
    @Volatile private var valor: String? = null

    /** Token vigente (null si no hay sesión o aún no se cargó). */
    val actual: String? get() = valor

    suspend fun cargar(): String? = mutex.withLock {
        if (!cargado) {
            valor = runCatching { almacen.getToken() }.getOrNull()
            cargado = true
        }
        valor
    }

    override suspend fun saveToken(token: String) = mutex.withLock {
        almacen.saveToken(token)
        valor = token
        cargado = true
    }

    override suspend fun getToken(): String? = cargar()

    override suspend fun clearToken() = mutex.withLock {
        runCatching { almacen.clearToken() }
        valor = null
        cargado = true
    }
}

/**
 * Estado global de la sesión. Cuando el servidor rechaza el token (401) la app vuelve al
 * inicio de sesión SIN borrar lo guardado sin conexión: al volver a entrar se envía.
 */
class EstadoSesion {
    private val _vencida = MutableStateFlow(false)
    val vencida: StateFlow<Boolean> = _vencida.asStateFlow()

    fun marcarVencida() { _vencida.value = true }
    fun restablecer() { _vencida.value = false }
}
