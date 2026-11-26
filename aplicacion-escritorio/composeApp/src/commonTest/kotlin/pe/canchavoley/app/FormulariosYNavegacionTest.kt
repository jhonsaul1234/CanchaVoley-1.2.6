package pe.canchavoley.app

import kotlinx.datetime.LocalDate
import pe.canchavoley.app.datos.NegocioDto
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.CatalogoDto
import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.dominio.Comprobante
import pe.canchavoley.app.dominio.DisponibilidadLocal
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Intervalo
import pe.canchavoley.app.dominio.Navegacion
import pe.canchavoley.app.dominio.Validaciones
import pe.canchavoley.app.ui.inicioMinimoOfrecido
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Navegación por rol, botón «atrás» y validaciones de los formularios (las mismas funciones que usa la interfaz). */
class FormulariosYNavegacionTest {

    @Test
    fun cada_rol_ve_solo_su_menu() {
        val admin = Navegacion.destinos("admin")
        val recepcion = Navegacion.destinos("recepcion")
        val cliente = Navegacion.destinos("cliente")
        assertTrue(listOf("CONFIGURACION", "USUARIOS", "REPORTES").all { it in admin })
        assertTrue(listOf("CONFIGURACION", "USUARIOS", "REPORTES").none { it in recepcion })
        assertTrue(listOf("PANEL", "AGENDA", "LLAMADA", "CLIENTES", "CAJA").all { it in recepcion })
        assertEquals(listOf("HORARIOS", "MIS_RESERVAS", "NOTIFICACIONES", "CUENTA"), cliente)
        assertEquals(cliente, Navegacion.destinos("chofer"))           // roles de ProLech: solo lo de un cliente
        assertEquals("PANEL", admin.first())
    }

    @Test
    fun atras_cierra_el_detalle_luego_vuelve_al_inicio_y_al_final_sale() {
        assertEquals(Navegacion.Volver.CERRAR_DETALLE, Navegacion.alVolver("CAJA", "PANEL", detalleAbierto = true))
        assertEquals(Navegacion.Volver.IR_AL_INICIO, Navegacion.alVolver("CAJA", "PANEL", detalleAbierto = false))
        assertNull(Navegacion.alVolver("PANEL", "PANEL", detalleAbierto = false))
    }

    @Test
    fun acceso_y_registro() {
        assertNotNull(Validaciones.inicioSesion("", "x"))
        assertNotNull(Validaciones.inicioSesion("sin-arroba", "clave"))
        assertNull(Validaciones.inicioSesion("ana@correo.pe", "clave"))
        assertNotNull(Validaciones.registroCliente("Ana Pérez", "ana@correo.pe", "corta", "987654321"))
        assertNotNull(Validaciones.registroCliente("Ana Pérez", "ana@correo.pe", "suficiente", "123"))
        assertNull(Validaciones.registroCliente("Ana Pérez", "ana@correo.pe", "suficiente", "987 654 321"))
        assertNotNull(Validaciones.servidor("http://"))
        assertNull(Validaciones.servidor("192.168.1.10:8001"))
    }

    @Test
    fun pagos() {
        assertNotNull(Validaciones.pago("pago", "0", "efectivo", ""))
        assertNotNull(Validaciones.pago("pago", "abc", "efectivo", ""))
        assertNull(Validaciones.pago("pago", "25.50", "efectivo", ""))
        assertNotNull(Validaciones.pago("pago", "25", "yape", "op 12"))          // espacios no
        assertNull(Validaciones.pago("pago", "25", "yape", "ABC-12345"))
        assertNotNull(Validaciones.pago("devolucion", "30", "efectivo", "", pagado = 2000))
        assertNull(Validaciones.pago("devolucion", "20", "efectivo", "", pagado = 2000))
    }

    @Test
    fun reserva_nueva() {
        val ahora = Fechas.instante(DIA, kotlinx.datetime.LocalTime(12, 0))
        assertEquals("Primero identifica o registra al cliente.", Validaciones.reserva("18:00", 60, t(18), ahora, conCliente = false, precio = "", esPersonal = true))
        assertNotNull(Validaciones.reserva("", 60, null, ahora, true, "", true))
        assertNotNull(Validaciones.reserva("25:00", 60, null, ahora, true, "", true))
        assertNotNull(Validaciones.reserva("10:00", 60, t(10), ahora, true, "", true))             // ya pasó
        assertNotNull(Validaciones.reserva("18:00", 60, t(18), ahora, true, "abc", true))           // precio inválido
        assertNull(Validaciones.reserva("18:00", 90, t(18), ahora, true, "45", true))
        assertNull(Validaciones.reserva("18:00", 90, t(18), ahora, conCliente = false, precio = null, esPersonal = false))  // app del cliente
    }

    @Test
    fun horario_y_tarifas() {
        assertNull(Validaciones.turnos(listOf("08:00" to "13:00", "15:00" to "23:00")))
        assertNull(Validaciones.turnos(listOf("18:00" to "01:00")))                                 // pasa la medianoche
        assertNotNull(Validaciones.turnos(listOf("08:00" to "14:00", "13:00" to "20:00")))           // se superponen
        assertNotNull(Validaciones.turnos(listOf("08:00" to "08:00")))
        assertNotNull(Validaciones.turnos(listOf("8" to "13:00")))
        val tarde = Triple(setOf(1, 2), "18:00" to "22:00", "40")
        assertNull(Validaciones.franjas(listOf(Triple(setOf(1, 2), "08:00" to "18:00", "30"), tarde)))
        assertNotNull(Validaciones.franjas(listOf(Triple(setOf(2), "16:00" to "19:00", "35"), tarde)))  // martes se cruza
        assertNotNull(Validaciones.franjas(listOf(Triple(emptySet(), "08:00" to "18:00", "30"))))
        assertNotNull(Validaciones.franjas(listOf(Triple(setOf(1), "08:00" to "18:00", "0"))))
        assertEquals(LocalDate(2026, 12, 1), Validaciones.fecha("2026-12-01"))
        assertNull(Validaciones.fecha("01/12/2026"))
    }

    @Test
    fun cierres_de_la_cancha() {
        assertNotNull(Validaciones.cierre("08:00", "12:00", false, ""))
        assertNull(Validaciones.cierre("", "", true, "Feriado"))
        assertNull(Validaciones.cierre("20:00", "00:00", false, "Evento"))                          // hasta la medianoche
        assertNotNull(Validaciones.cierre("20:00", "18:00", false, "Evento"))
    }

    @Test
    fun horas_de_inicio_dentro_de_lo_libre() {
        val libres = listOf(Intervalo(t(8, 15), t(11)), Intervalo(t(18), t(20)))
        val horas = DisponibilidadLocal.horasDeInicio(libres, duracionMin = 90, paso = 30).map { Fechas.hora(it) }
        assertEquals(listOf("08:30", "09:00", "09:30", "18:00", "18:30"), horas)
        val desde = DisponibilidadLocal.horasDeInicio(libres, 60, 60, ahora = t(9, 10)).map { Fechas.hora(it) }
        assertEquals(listOf("10:00", "18:00", "19:00"), desde)                                       // sin las que ya pasaron
        assertTrue(DisponibilidadLocal.horasDeInicio(libres, 300, 30).isEmpty())
    }

    @Test
    fun comprobante_y_cierre_de_caja() {
        val reserva = ReservaDto(uuid = "r1", canchaId = 1, clienteNombre = "Ana Pérez", clienteTelefono = "51987654321",
            inicio = Fechas.iso(t(18)), fin = Fechas.iso(t(19, 30)), duracionMin = 90, estado = "confirmada",
            precioFinal = "60.00", totalPagado = "20.00", saldo = "40.00", codigoQr = "ABCDEFGHIJKLMNOPQRST")
        val pagos = listOf(
            PagoDto(uuid = "p1", tipo = "adelanto", metodo = "yape", monto = "20.00", numeroOperacion = "123456", numeroComprobante = "CV-000001", pagadoEn = Fechas.iso(t(12))),
            PagoDto(uuid = "p2", tipo = "pago", metodo = "efectivo", monto = "15.00", estado = "anulado", numeroComprobante = "CV-000002", pagadoEn = Fechas.iso(t(12))),
        )
        val texto = Comprobante.texto(NegocioDto(nombre = "Cancha Prueba", telefono = "51911222333"), reserva, pagos, "Cancha 1")
        listOf("Cancha Prueba", "Ana Pérez", "18:00–19:30", "S/ 60.00", "CV-000001", "op. 123456", "Saldo: S/ 40.00", "no reemplaza").forEach {
            assertTrue(texto.contains(it), "falta «$it» en:\n$texto")
        }
        assertFalse(texto.contains("CV-000002"))                                                      // los anulados no salen
        val caja = Comprobante.cierreDeCaja("Cancha Prueba", DIA, mapOf("yape" to 2000L), 2000L, pagos)
        assertTrue(caja.contains("Yape: S/ 20.00") && caja.contains("Total: S/ 20.00") && caja.contains("(+1 anulados)"), caja)
    }

    @Test
    fun la_app_del_cliente_no_ofrece_horas_dentro_de_la_anticipacion_minima() {   // DEF-18
        val libres = listOf(Intervalo(t(21), t(23)))
        val cat = CatalogoDto(reglas = ReglasDto(anticipacionMinimaMin = 60))
        val ahora = t(21, 33)
        val delCliente = inicioMinimoOfrecido(ahora, cat, esPersonal = false)
        assertEquals("22:33", Fechas.hora(delCliente))
        assertTrue(DisponibilidadLocal.horasDeInicio(libres, 60, 30, ahora = delCliente).isEmpty())          // 22:00 ya no se ofrece
        assertEquals(listOf("22:00"), DisponibilidadLocal.horasDeInicio(libres, 60, 30, ahora = inicioMinimoOfrecido(ahora, cat, esPersonal = true)).map { Fechas.hora(it) })
        assertEquals(ahora, inicioMinimoOfrecido(ahora, CatalogoDto(), esPersonal = false))                // sin regla configurada
        assertEquals(ahora, inicioMinimoOfrecido(ahora, null, esPersonal = false))
    }
}
