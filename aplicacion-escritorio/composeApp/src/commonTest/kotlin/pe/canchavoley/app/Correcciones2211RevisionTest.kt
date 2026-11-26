@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app

import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.ReglasReserva
import pe.canchavoley.app.dominio.TipoWhatsApp
import pe.canchavoley.app.ui.textoCancelada
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Revisión de las correcciones del 22/11/2026 después de probarlas en la PC (Windows de prueba + BlueStacks):
 * avisos que no correspondían al estado, textos de la regla de devolución y anulación de un cobro ya devuelto.
 * Todos los datos son FICTICIOS.
 */
class Correcciones2211RevisionTest {
    private fun reserva(estado: String = "confirmada", pendientePor: String? = null, asistencia: String? = null, pagado: String = "0.00") =
        ReservaDto(uuid = "res-rev", canchaId = 1, inicio = Fechas.iso(t(16)), fin = Fechas.iso(t(17)), estado = estado,
            pendientePor = pendientePor, asistencia = asistencia, precioFinal = "20.00", totalPagado = pagado,
            adelantoRequerido = "10.00", clienteNombre = "Cliente Prueba", clienteTelefono = "51900000001")

    private fun pago(tipo: String, monto: String, estado: String = "valido") =
        PagoDto(uuid = "p-$tipo-$monto-$estado", tipo = tipo, metodo = "efectivo", monto = monto, estado = estado)

    private val reglas = ReglasDto(toleranciaNoShowMin = 15)

    // ── «No asistió se podrá marcar…» y WhatsApp según el estado ──────────────
    @Test fun solicitud_por_aprobar_no_anuncia_no_asistio_ni_recordatorio() {
        val a = ReglasReserva.acciones(reserva("pendiente", pendientePor = "aprobacion"), true, true, t(9), reglas)
        assertFalse(a.noAsistio)
        assertNull(a.noAsistioDesde)                                  // antes aparecía «se podrá marcar desde las 16:15»
        assertEquals(TipoWhatsApp.CONTACTO, a.whatsapp)               // no se «recuerda» una reserva que aún no se aceptó
    }

    @Test fun cancelada_o_ya_atendida_no_anuncia_no_asistio() {
        assertNull(ReglasReserva.acciones(reserva("cancelada", pagado = "10.00"), true, true, t(9), reglas).noAsistioDesde)
        assertNull(ReglasReserva.acciones(reserva("rechazada"), true, true, t(9), reglas).noAsistioDesde)
        assertNull(ReglasReserva.acciones(reserva(asistencia = "asistio"), true, true, t(16, 5), reglas).noAsistioDesde)
    }

    @Test fun confirmada_futura_si_recuerda_y_anuncia_desde_cuando_no_asistio() {
        val a = ReglasReserva.acciones(reserva(), true, false, t(9), reglas)
        assertEquals(TipoWhatsApp.RECORDATORIO, a.whatsapp)
        assertEquals(t(16, 15), a.noAsistioDesde)
        // Esperando el adelanto (ya aprobada): el recordatorio sí corresponde.
        assertEquals(TipoWhatsApp.RECORDATORIO, ReglasReserva.acciones(reserva("pendiente", pendientePor = "adelanto"), true, false, t(9), reglas).whatsapp)
    }

    // ── Anular un cobro que ya se devolvió ─────────────────────────────────────
    @Test fun no_se_anula_un_cobro_que_ya_se_devolvio() {
        val devueltaTodo = reserva("cancelada", pagado = "0.00")            // adelanto 10 − devolución 10
        assertTrue(ReglasReserva.anulacionDejaNegativo(devueltaTodo, pago("adelanto", "10.00")))
        assertFalse(ReglasReserva.anulacionDejaNegativo(devueltaTodo, pago("devolucion", "10.00")))   // la devolución sí
        assertFalse(ReglasReserva.anulacionDejaNegativo(reserva(pagado = "20.00"), pago("adelanto", "10.00")))
        assertFalse(ReglasReserva.anulacionDejaNegativo(devueltaTodo, pago("adelanto", "10.00", estado = "anulado")))
    }

    @Test fun devuelto_suma_solo_devoluciones_validas() {
        assertEquals(1500L, ReglasReserva.devuelto(listOf(pago("adelanto", "10.00"), pago("devolucion", "10.00"),
            pago("devolucion", "5.00"), pago("devolucion", "7.00", estado = "anulado"))))
        assertEquals(0L, ReglasReserva.devuelto(emptyList()))
    }

    // ── Qué pasa con lo pagado al cancelar (según la regla configurada; nada inventado) ──
    @Test fun texto_al_cancelar_sigue_la_regla_configurada() {
        val credito = ReglasReserva.textoPagadoAlCancelar(1000, ReglasDto(cancelacionDevolucion = "credito"))
        assertTrue("crédito a tu favor" in credito && "coordinará la devolución" !in credito, credito)
        val ninguna = ReglasReserva.textoPagadoAlCancelar(1000, ReglasDto(cancelacionDevolucion = "ninguna"))
        assertTrue("no se devuelve" in ninguna && "a tu favor" !in ninguna, ninguna)
        assertTrue("todo lo que pagaste (S/ 10.00)" in ReglasReserva.textoPagadoAlCancelar(1000, ReglasDto(cancelacionDevolucion = "total")))
        val parcial = ReglasReserva.textoPagadoAlCancelar(1000, ReglasDto(cancelacionDevolucion = "parcial", cancelacionPorcentaje = "50"))
        assertTrue("50 %" in parcial && "S/ 5.00 de S/ 10.00" in parcial, parcial)
        assertTrue("Comunícate con el negocio" in ReglasReserva.textoPagadoAlCancelar(1000, null))   // sin regla no se promete nada
    }

    @Test fun aviso_de_cancelada_despues_de_devolver_lo_cobrado() {
        val r = reserva("cancelada").copy(motivoEstado = "Prueba")
        val personal = textoCancelada(r, pagado = 0, devuelto = 1000, esCliente = false, esAdmin = true, reglas = null)
        assertEquals("Cancelada: Prueba. Ya se devolvió lo cobrado (S/ 10.00): no queda nada por cobrar ni devolver.", personal)
        assertEquals("Cancelada: Prueba. Se te devolvió S/ 10.00.", textoCancelada(r, 0, 1000, true, false, null))
        assertEquals("Cancelada: Prueba. No tiene pagos: no hay nada que cobrar ni devolver.", textoCancelada(r, 0, 0, false, true, null))
        val conCredito = textoCancelada(r, 1000, 0, true, false, ReglasDto(cancelacionDevolucion = "credito"))
        assertNotNull(conCredito)
        assertTrue("crédito a tu favor" in conCredito, conCredito)
    }
}
