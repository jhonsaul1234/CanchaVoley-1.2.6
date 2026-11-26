package pe.canchavoley.app.plataforma

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.datosSesion: DataStore<Preferences> by preferencesDataStore(name = "canchavoley_sesion")
/** Archivo de la etapa 0 (token sin cifrar): se lee una vez para migrarlo y se vacía. */
private val Context.datosAnteriores: DataStore<Preferences> by preferencesDataStore(name = "prolechc_prefs")

private val TOKEN_CIFRADO = stringPreferencesKey("token_cifrado")
private val TOKEN_ANTERIOR = stringPreferencesKey("bearer_token")

/**
 * Token de sesión cifrado con AES-GCM. La clave vive en el Android Keystore (no se puede
 * extraer del celular) y el texto cifrado se guarda en DataStore. Si la clave ya no sirve
 * (por ejemplo después de restaurar el celular) se descarta el token y se pide entrar otra vez.
 */
class TokenStorageAndroid(private val context: Context) : TokenStorage {

    override suspend fun saveToken(token: String) {
        val cifrado = cifrar(token)
        context.datosSesion.edit { it[TOKEN_CIFRADO] = cifrado }
    }

    override suspend fun getToken(): String? {
        context.datosSesion.data.first()[TOKEN_CIFRADO]?.let { guardado ->
            val token = runCatching { descifrar(guardado) }.getOrNull()
            if (token == null) clearToken()
            return token
        }
        val anterior = runCatching { context.datosAnteriores.data.first()[TOKEN_ANTERIOR] }.getOrNull() ?: return null
        saveToken(anterior)
        runCatching { context.datosAnteriores.edit { it.remove(TOKEN_ANTERIOR) } }
        return anterior
    }

    override suspend fun clearToken() {
        context.datosSesion.edit { it.remove(TOKEN_CIFRADO) }
    }

    private fun clave(): SecretKey {
        val almacen = KeyStore.getInstance(PROVEEDOR).apply { load(null) }
        (almacen.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR)
        generador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generador.generateKey()
    }

    private fun cifrar(texto: String): String {
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(Cipher.ENCRYPT_MODE, clave())
        val iv = cifrador.iv
        val datos = cifrador.doFinal(texto.encodeToByteArray())
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + datos, Base64.NO_WRAP)
    }

    private fun descifrar(guardado: String): String {
        val todo = Base64.decode(guardado, Base64.NO_WRAP)
        val largoIv = todo[0].toInt()
        val iv = todo.copyOfRange(1, 1 + largoIv)
        val datos = todo.copyOfRange(1 + largoIv, todo.size)
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, iv))
        return cifrador.doFinal(datos).decodeToString()
    }

    private companion object {
        const val PROVEEDOR = "AndroidKeyStore"
        const val ALIAS = "canchavoley_token"
        const val TRANSFORMACION = "AES/GCM/NoPadding"
    }
}
