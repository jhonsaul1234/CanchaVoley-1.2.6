@file:OptIn(ExperimentalComposeUiApi::class)

package pe.canchavoley.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import kotlinx.coroutines.test.runTest
import pe.canchavoley.app.plataforma.PortapapelesSeguro
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DEF-16: con el portapapeles de Windows ocupado por otro programa, pegar o copiar no debe cerrar la app. */
class PortapapelesSeguroTest {

    private class PortapapelesFalso(var fallas: Int, val error: () -> Exception = { IllegalStateException("cannot open system clipboard") }) : Clipboard {
        var lecturas = 0
        var escrituras = 0
        var escrito: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? {
            lecturas++
            if (fallas-- > 0) throw error()
            return ClipEntry(StringSelection("Pedro Ccama"))
        }
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            escrituras++
            if (fallas-- > 0) throw error()
            escrito = clipEntry
        }
        override val nativeClipboard: NativeClipboard get() = Any()
    }

    @Test
    fun pegar_con_el_portapapeles_ocupado_reintenta_y_trae_el_texto() = runTest {
        val falso = PortapapelesFalso(fallas = 2)
        val entrada = PortapapelesSeguro(falso, esperaMs = 1).getClipEntry()
        assertEquals("Pedro Ccama", entrada!!.asAwtTransferable!!.getTransferData(DataFlavor.stringFlavor))
        assertEquals(3, falso.lecturas)
    }

    @Test
    fun si_sigue_ocupado_pegar_no_hace_nada_y_no_lanza() = runTest {
        val falso = PortapapelesFalso(fallas = 100)
        assertNull(PortapapelesSeguro(falso, intentos = 4, esperaMs = 1).getClipEntry())
        assertEquals(4, falso.lecturas)
    }

    @Test
    fun copiar_con_el_portapapeles_ocupado_reintenta() = runTest {
        val falso = PortapapelesFalso(fallas = 1)
        PortapapelesSeguro(falso, esperaMs = 1).setClipEntry(ClipEntry(StringSelection("x")))
        assertEquals(2, falso.escrituras)
        assertNotNull(falso.escrito)
    }

    @Test
    fun otro_error_al_leer_devuelve_vacio_sin_lanzar() = runTest {
        val falso = PortapapelesFalso(fallas = 1) { java.io.IOException("datos no disponibles") }
        assertNull(PortapapelesSeguro(falso, esperaMs = 1).getClipEntry())
    }

    @Test
    fun si_copiar_sigue_fallando_java_olvida_el_texto_que_no_llego_a_windows() = runTest {   // DEF-19
        var olvidos = 0
        PortapapelesSeguro(PortapapelesFalso(fallas = 100), intentos = 3, esperaMs = 1, alNoPoderCopiar = { olvidos++ })
            .setClipEntry(ClipEntry(StringSelection("x")))
        assertEquals(1, olvidos)
        PortapapelesSeguro(PortapapelesFalso(fallas = 0), esperaMs = 1, alNoPoderCopiar = { olvidos++ })
            .setClipEntry(ClipEntry(StringSelection("x")))
        assertEquals(1, olvidos)                 // si copia bien, no se toca nada
    }
}
