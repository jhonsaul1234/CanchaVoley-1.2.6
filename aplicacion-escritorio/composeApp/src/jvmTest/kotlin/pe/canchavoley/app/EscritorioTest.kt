package pe.canchavoley.app

import kotlinx.coroutines.test.runTest
import pe.canchavoley.app.plataforma.CifradorToken
import pe.canchavoley.app.plataforma.NOMBRE_BD
import pe.canchavoley.app.plataforma.NOMBRE_BD_ANTERIOR
import pe.canchavoley.app.plataforma.TokenStorageEscritorio
import pe.canchavoley.app.plataforma.renombrarBaseAnterior
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cifrador de prueba (en Windows se usa DPAPI): invierte y enmascara los bytes. */
private object CifradorPrueba : CifradorToken {
    override fun cifrar(datos: ByteArray) = datos.reversedArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun descifrar(datos: ByteArray) = datos.map { (it.toInt() xor 0x5A).toByte() }.toByteArray().reversedArray()
}

class EscritorioTest {
    private val nodo = Preferences.userRoot().node("canchavoley-prueba-${UUID.randomUUID()}")

    @AfterTest
    fun limpiar() = nodo.removeNode()

    @Test
    fun el_token_se_guarda_cifrado_y_se_recupera() = runTest {
        val almacen = TokenStorageEscritorio(nodo, CifradorPrueba)
        almacen.saveToken("12|token-secreto")
        val guardado = nodo.get(TokenStorageEscritorio.CLAVE_CIFRADA, null)
        assertNotEquals(null, guardado)
        assertFalse(String(Base64.getDecoder().decode(guardado)).contains("token-secreto"))
        assertEquals("12|token-secreto", TokenStorageEscritorio(nodo, CifradorPrueba).getToken())
        almacen.clearToken()
        assertNull(TokenStorageEscritorio(nodo, CifradorPrueba).getToken())
    }

    @Test
    fun el_token_sin_cifrar_de_la_etapa_0_se_cifra_al_leerlo() = runTest {
        nodo.put(TokenStorageEscritorio.CLAVE_SIN_CIFRAR, "7|antiguo")
        val almacen = TokenStorageEscritorio(nodo, CifradorPrueba)
        assertEquals("7|antiguo", almacen.getToken())
        assertNull(nodo.get(TokenStorageEscritorio.CLAVE_SIN_CIFRAR, null))
        assertEquals("7|antiguo", TokenStorageEscritorio(nodo, CifradorPrueba).getToken())
    }

    @Test
    fun sin_dpapi_el_token_no_se_escribe_en_disco() = runTest {
        val almacen = TokenStorageEscritorio(nodo, cifrador = null)
        almacen.saveToken("solo-memoria")
        assertEquals("solo-memoria", almacen.getToken())
        assertEquals(0, nodo.keys().size)
    }

    @Test
    fun la_base_de_la_etapa_0_se_renombra_con_sus_archivos() {
        val carpeta = Files.createTempDirectory("cv-base").toFile()
        File(carpeta, NOMBRE_BD_ANTERIOR).writeText("base")
        File(carpeta, "$NOMBRE_BD_ANTERIOR-wal").writeText("wal")
        renombrarBaseAnterior(carpeta, File(carpeta, NOMBRE_BD))
        assertTrue(File(carpeta, NOMBRE_BD).exists())
        assertTrue(File(carpeta, "$NOMBRE_BD-wal").exists())
        assertFalse(File(carpeta, NOMBRE_BD_ANTERIOR).exists())
        // Si ya existe la nueva, no se toca nada.
        File(carpeta, NOMBRE_BD_ANTERIOR).writeText("otra")
        renombrarBaseAnterior(carpeta, File(carpeta, NOMBRE_BD))
        assertEquals("base", File(carpeta, NOMBRE_BD).readText())
        carpeta.deleteRecursively()
    }
}
