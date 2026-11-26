@file:Suppress("DEPRECATION")

package pe.canchavoley.app

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import pe.canchavoley.app.plataforma.GestorPortapapelesSeguro
import pe.canchavoley.app.plataforma.MENSAJE_PORTAPAPELES_OCUPADO
import pe.canchavoley.app.plataforma.copiarTexto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEF-16 (botones «Copiar» de caja, reportes y comprobante): con el portapapeles ocupado la app no se cierra. */
class GestorPortapapelesSeguroTest {

    private class GestorFalso(var fallas: Int) : ClipboardManager {
        var intentos = 0
        var copiado: String? = null
        override fun setText(annotatedString: AnnotatedString) {
            intentos++
            if (fallas-- > 0) throw IllegalStateException("cannot open system clipboard")
            copiado = annotatedString.text
        }
        override fun getText(): AnnotatedString? {
            if (fallas > 0) throw IllegalStateException("cannot open system clipboard")
            return copiado?.let { AnnotatedString(it) }
        }
    }

    private val esperas = mutableListOf<Long>()
    private var olvidos = 0
    private fun gestor(base: ClipboardManager) =
        GestorPortapapelesSeguro(base, intentos = 6, esperaMs = 40, esperar = { esperas += it }, alNoPoderCopiar = { olvidos++ })

    @Test
    fun copiar_con_el_portapapeles_ocupado_un_momento_reintenta_y_copia() {
        val base = GestorFalso(fallas = 2)
        assertTrue(copiarTexto(gestor(base), "Cierre de caja: S/ 552.50"))
        assertEquals("Cierre de caja: S/ 552.50", base.copiado)
        assertEquals(3, base.intentos)
        assertEquals(listOf(40L, 40L), esperas)
        assertEquals(0, olvidos)
    }

    @Test
    fun copiar_con_el_portapapeles_siempre_ocupado_avisa_y_no_cierra_la_app() {
        val base = GestorFalso(fallas = 100)
        assertFalse(copiarTexto(gestor(base), "Reporte"))
        assertEquals(6, base.intentos)
        assertNull(base.copiado)
        assertEquals(1, olvidos)                 // DEF-19: Java olvida el texto que no llegó a Windows
        assertTrue(MENSAJE_PORTAPAPELES_OCUPADO.startsWith("No se pudo copiar"))
    }

    @Test
    fun leer_con_el_portapapeles_ocupado_devuelve_nada_en_vez_de_fallar() {
        val g = gestor(GestorFalso(fallas = 100))
        assertNull(g.getText())
        assertFalse(g.hasText())
    }

    @Test
    fun sin_proteccion_el_portapapeles_ocupado_lanzaria_el_error_que_cerraba_la_app() {
        val base = GestorFalso(fallas = 1)
        val error = runCatching { base.setText(AnnotatedString("x")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("cannot open system clipboard", error.message)
    }
}
