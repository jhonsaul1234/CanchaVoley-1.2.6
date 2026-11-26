package pe.canchavoley.app.plataforma

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64
import java.util.prefs.Preferences

/** Cifra y descifra el token. */
interface CifradorToken {
    fun cifrar(datos: ByteArray): ByteArray
    fun descifrar(datos: ByteArray): ByteArray
}

/**
 * DPAPI de Windows (CryptProtectData): el resultado solo lo puede descifrar el mismo usuario de
 * Windows en la misma PC, sin guardar ninguna clave en la app.
 */
object CifradorDpapi : CifradorToken {
    override fun cifrar(datos: ByteArray): ByteArray = Crypt32Util.cryptProtectData(datos)
    override fun descifrar(datos: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(datos)
}

fun esWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * Token de sesión del escritorio, guardado cifrado con DPAPI en las preferencias del usuario de
 * Windows (HKCU\Software\JavaSoft\Prefs\canchavoley). El token sin cifrar de la etapa 0, si existe,
 * se cifra la primera vez que se lee y se borra el valor anterior.
 * Fuera de Windows (solo desarrollo) no hay DPAPI y el token no se guarda en disco.
 */
class TokenStorageEscritorio(
    private val nodo: Preferences = Preferences.userRoot().node(nodoPreferencias()),
    private val cifrador: CifradorToken? = if (esWindows()) CifradorDpapi else null,
) : TokenStorage {
    private var soloEnMemoria: String? = null

    override suspend fun saveToken(token: String) {
        val c = cifrador
        if (c == null) {
            soloEnMemoria = token
            return
        }
        nodo.put(CLAVE_CIFRADA, Base64.getEncoder().encodeToString(c.cifrar(token.encodeToByteArray())))
        nodo.remove(CLAVE_SIN_CIFRAR)
        nodo.flush()
    }

    override suspend fun getToken(): String? {
        val c = cifrador ?: return soloEnMemoria
        nodo.get(CLAVE_CIFRADA, null)?.let { guardado ->
            return runCatching { c.descifrar(Base64.getDecoder().decode(guardado)).decodeToString() }.getOrNull()
        }
        val anterior = nodo.get(CLAVE_SIN_CIFRAR, null) ?: return null
        saveToken(anterior)
        return anterior
    }

    override suspend fun clearToken() {
        soloEnMemoria = null
        nodo.remove(CLAVE_CIFRADA)
        nodo.remove(CLAVE_SIN_CIFRAR)
        nodo.flush()
    }

    companion object {
        const val CLAVE_CIFRADA = "token_dpapi"
        /** Clave de la etapa 0 (token en texto plano). */
        const val CLAVE_SIN_CIFRAR = "bearer_token"
    }
}
