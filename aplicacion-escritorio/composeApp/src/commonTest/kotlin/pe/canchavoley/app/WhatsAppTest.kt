package pe.canchavoley.app

import io.ktor.http.decodeURLQueryComponent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.WhatsApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Enlaces wa.me y textos para el cliente (recordatorio y ofrecer otro horario). */
class WhatsAppTest {

    private val dia = LocalDate(2026, 11, 20)   // viernes
    private fun reserva(estado: String = "confirmada", pendientePor: String? = null, saldo: String? = "40.00") = ReservaDto(
        uuid = "r1", canchaId = 1, clienteNombre = "Ana Pérez", clienteTelefono = "51987654321",
        inicio = Fechas.iso(Fechas.instante(dia, LocalTime(18, 0))), fin = Fechas.iso(Fechas.instante(dia, LocalTime(19, 30))),
        duracionMin = 90, estado = estado, pendientePor = pendientePor, precioFinal = "60.00", totalPagado = "20.00",
        saldo = saldo, codigoQr = "ABCDEFGHIJKLMNOPQRST",
    )

    @Test
    fun el_enlace_lleva_el_numero_internacional_y_el_texto_codificado() {
        val enlace = WhatsApp.enlace("987 654 321", "Hola Ana, mañana 18:00\nGracias")!!
        assertTrue(enlace.startsWith("https://wa.me/51987654321?text="), enlace)
        assertFalse(enlace.any { it == ' ' || it == '\n' || it.code > 127 }, "el enlace debe ir codificado: $enlace")
        assertEquals("Hola Ana, mañana 18:00\nGracias", enlace.substringAfter("text=").decodeURLQueryComponent())
        assertEquals("https://wa.me/51987654321", WhatsApp.enlace("51987654321"))
        assertEquals("https://wa.me/51987654321", WhatsApp.enlace("987654321", "   "))
        assertNull(WhatsApp.enlace(null))
        assertNull(WhatsApp.enlace("12345"))
    }

    @Test
    fun recordatorio_con_los_datos_de_la_reserva_y_nada_inventado() {
        val texto = WhatsApp.recordatorio("Cancha Prueba", reserva(), "Cancha 1")
        assertEquals("Hola Ana, te recordamos tu reserva en Cancha Prueba (Cancha 1): viernes 20 de noviembre de 2026 de 18:00 a 19:30. " +
            "Saldo por pagar: S/ 40.00. Código de tu reserva: ABCDEFGHIJKLMNOPQRST.", texto)
        val sinSaldo = WhatsApp.recordatorio(null, reserva(saldo = "0.00"), null)
        assertTrue(sinSaldo.startsWith("Hola Ana, te recordamos tu reserva en la cancha: viernes"), sinSaldo)
        assertFalse(sinSaldo.contains("Saldo"))
        assertTrue(WhatsApp.recordatorio(null, reserva("pendiente", "adelanto"), null).contains("Para confirmarla falta el adelanto."))
        assertTrue(WhatsApp.recordatorio(null, reserva("pendiente", "aprobacion"), null).contains("Aún está pendiente de confirmación."))
    }

    @Test
    fun ofrecer_otro_horario_cuando_la_reserva_fue_rechazada() {
        val texto = WhatsApp.otroHorario("Cancha Prueba", reserva("rechazada"))
        assertEquals("Hola Ana, te escribimos de Cancha Prueba por tu reserva del viernes 20 de noviembre de 2026 de 18:00 a 19:30: " +
            "ese horario no está disponible. ¿Te podemos ofrecer otro horario?", texto)
    }
}
