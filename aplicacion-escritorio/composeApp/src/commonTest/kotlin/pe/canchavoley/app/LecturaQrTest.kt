package pe.canchavoley.app

import pe.canchavoley.app.dominio.CodigoQr
import pe.canchavoley.app.dominio.LecturaQr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lo que entrega la cámara, un lector USB o una persona se convierte en el código de la reserva. */
class LecturaQrTest {
    private val codigo = "K3J9X2M4Q8W1Z7R5T6Y0"   // mismo formato que el servidor: 20 letras y números

    @Test
    fun el_contenido_del_qr_se_lee_de_vuelta() {
        assertEquals("CANCHAVOLEY:$codigo", LecturaQr.contenido(codigo))
        assertEquals(codigo, LecturaQr.codigoDeReserva(LecturaQr.contenido(codigo)))
    }

    @Test
    fun tolera_minusculas_espacios_y_el_codigo_sin_prefijo() {
        assertEquals(codigo, LecturaQr.codigoDeReserva("  canchavoley:${codigo.lowercase()}\n"))
        assertEquals(codigo, LecturaQr.codigoDeReserva("K3J9 X2M4 Q8W1 Z7R5 T6Y0"))
        assertEquals(codigo, LecturaQr.codigoDeReserva(codigo.lowercase()))
    }

    @Test
    fun tolera_un_lector_usb_con_otra_distribucion_de_teclado() {
        // Un lector en modo teclado de EE. UU. sobre un teclado latinoamericano escribe «Ñ» en lugar de «:»
        assertEquals(codigo, LecturaQr.codigoDeReserva("CANCHAVOLEYÑ$codigo"))
        assertEquals(codigo, LecturaQr.codigoDeReserva("CANCHAVOLEY;$codigo"))
    }

    @Test
    fun rechaza_qr_que_no_son_de_canchavoley() {
        assertNull(LecturaQr.codigoDeReserva("https://ejemplo.pe/promo"))
        assertNull(LecturaQr.codigoDeReserva("abc"))
        assertNull(LecturaQr.codigoDeReserva(""))
    }

    @Test
    fun el_qr_de_una_reserva_cabe_en_una_version_pequena() {
        // Versión baja = módulos grandes = se lee mejor con la cámara a distancia.
        val qr = CodigoQr.codificar(LecturaQr.contenido(codigo))
        assertTrue(qr.version <= 3, "versión ${qr.version}")
    }
}
