package pe.canchavoley.app

import pe.canchavoley.app.plataforma.InstanciaUnica
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Una sola ventana de CanchaVoley a la vez (la segunda copia avisa y se cierra). */
class InstanciaUnicaTest {
    private val carpeta: File = Files.createTempDirectory("cv-instancia").toFile()

    @Test
    fun la_primera_copia_reserva_y_la_segunda_no_hasta_que_la_primera_se_cierra() {
        val primera = InstanciaUnica(carpeta)
        val segunda = InstanciaUnica(carpeta)
        assertTrue(primera.reservar())
        assertTrue(primera.reservar())                 // volver a pedirlo no cambia nada
        assertFalse(segunda.reservar())
        primera.liberar()
        assertTrue(segunda.reservar())
        segunda.liberar()
    }

    @Test
    fun si_no_se_puede_crear_el_bloqueo_no_impide_abrir_la_app() {
        val archivoComun = File(carpeta, "no-es-carpeta").apply { writeText("x") }
        assertTrue(InstanciaUnica(File(archivoComun, "sub")).reservar())
    }
}
