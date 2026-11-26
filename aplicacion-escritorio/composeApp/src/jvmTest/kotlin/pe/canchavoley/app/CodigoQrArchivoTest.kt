package pe.canchavoley.app

import pe.canchavoley.app.dominio.CodigoQr
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Escribe los QR de prueba como texto (para decodificarlos con OpenCV fuera de Gradle) y revisa su estructura. */
class CodigoQrArchivoTest {
    private val textos = listOf(
        "CANCHAVOLEY:ABCDEFGHIJKLMNOPQRST",
        "CANCHAVOLEY:K3J9X2M4Q8W1Z7R5T6Y0",
        "Hola",
        "https://ejemplo.pe/reserva/0f8fad5b-d9cb-469f-a165-70867728950e?x=1",
        "Reserva confirmada: Cancha de vóley, sábado 18:00–20:00. Código CANCHAVOLEY:ABCDEFGHIJKLMNOPQRST",
    )

    @Test
    fun genera_qr_validos_y_los_escribe_para_verificarlos() {
        val carpeta = File(System.getProperty("java.io.tmpdir"), "qr-canchavoley").apply { mkdirs() }
        textos.forEachIndexed { i, t ->
            val qr = CodigoQr.codificar(t)
            assertEquals(17 + 4 * qr.version, qr.tamano)
            // Patrones de búsqueda en las tres esquinas
            for ((f, c) in listOf(0 to 0, 0 to qr.tamano - 7, qr.tamano - 7 to 0)) {
                assertTrue(qr.oscuro(f, c) && qr.oscuro(f + 6, c + 6) && !qr.oscuro(f + 1, c + 1) && qr.oscuro(f + 3, c + 3))
            }
            val texto = (0 until qr.tamano).joinToString("\n") { f -> (0 until qr.tamano).joinToString("") { c -> if (qr.oscuro(f, c)) "1" else "0" } }
            File(carpeta, "qr$i.txt").writeText(texto)
            File(carpeta, "qr$i.esperado").writeText(t)
        }
    }
}
