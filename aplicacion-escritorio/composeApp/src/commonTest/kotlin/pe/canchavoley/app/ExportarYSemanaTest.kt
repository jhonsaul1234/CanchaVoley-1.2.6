package pe.canchavoley.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pe.canchavoley.app.datos.ClienteFrecuenteDto
import pe.canchavoley.app.datos.DiaOcupacionDto
import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.ReporteOcupacionDto
import pe.canchavoley.app.dominio.Comprobante
import pe.canchavoley.app.dominio.Exportar
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Semana
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tablas para Excel (reporte y caja), cobrado por usuario y la vista semanal de la agenda. */
class ExportarYSemanaTest {

    private fun lineas(texto: String) = texto.split("\n")

    @Test
    fun la_tabla_no_se_rompe_con_tabulaciones_ni_saltos_dentro_de_una_celda() {
        val t = Exportar.tabla(listOf(listOf("Ana\tPérez", "línea 1\nlínea 2"), listOf("x", "y")))
        assertEquals(listOf("Ana Pérez\tlínea 1 línea 2", "x\ty"), lineas(t))
    }

    @Test
    fun reporte_para_excel_con_montos_que_se_pueden_sumar() {
        val r = ReporteOcupacionDto(
            desde = "2026-11-10", hasta = "2026-11-16", horasAbiertas = 98.0, horasReservadas = 30.5, ocupacionPct = 31.12,
            porDia = listOf(DiaOcupacionDto("2026-11-10", 14.0, 6.0, 42.86), DiaOcupacionDto("2026-11-11", 0.0, 0.0, null)),
            minutosPorHora = JsonArray((0..23).map { JsonPrimitive(if (it == 18) 180 else if (it == 19) 90 else 0) }),
            horasPico = listOf(18, 19),
            reservasPorOrigen = buildJsonObject { put("llamada", 3); put("app", 2) },
            demandaNoAtendida = 2,
            ingresos = mapOf("efectivo" to "100.00", "yape" to "1150.5", "plin" to "0.00"),
            ingresosTotal = "1250.50",
            clientesFrecuentes = listOf(ClienteFrecuenteDto("Ana Pérez", 4, 6.0)),
        )
        val l = lineas(Exportar.reporte(r, "Cancha Prueba"))
        assertEquals("Reporte de Cancha Prueba\tDel 2026-11-10 al 2026-11-16", l[0])
        listOf(
            "Ocupación (%)\t31.1", "Horas reservadas\t30.5", "Horas de atención\t98", "Ingresos netos (S/)\t1250.50",
            "Efectivo (S/)\t100.00", "Yape (S/)\t1150.50", "Plin (S/)\t0.00", "Horas pico\t18:00, 19:00",
            "Fecha\tOcupación (%)\tHoras reservadas\tHoras de atención", "2026-11-10\t42.9\t6\t14", "2026-11-11\tcerrado\t0\t0",
            "Hora\tMinutos reservados", "18:00\t180", "19:00\t90", "llamada\t3", "app\t2", "Ana Pérez\t4\t6",
        ).forEach { assertTrue(it in l, "falta la fila «$it» en:\n${l.joinToString("\n")}") }
        assertFalse(l.any { "S/ " in it }, "los montos van sin «S/ » para que Excel los sume")
        assertFalse(l.any { it.startsWith("05:00") }, "las horas sin reservas no se listan")
    }

    @Test
    fun movimientos_de_caja_con_devoluciones_en_negativo_y_anulados_marcados() {
        val dia = LocalDate(2026, 11, 16)
        val pagos = listOf(
            PagoDto(uuid = "p1", tipo = "adelanto", metodo = "yape", monto = "20.00", numeroOperacion = "123456",
                numeroComprobante = "CV-000001", pagadoEn = Fechas.iso(Fechas.instante(dia, LocalTime(9, 5))), cliente = "Ana", registradoPor = "Recepción"),
            PagoDto(uuid = "p2", tipo = "devolucion", metodo = "efectivo", monto = "10.00", pagadoEn = Fechas.iso(Fechas.instante(dia, LocalTime(10, 0)))),
            PagoDto(uuid = "p3", tipo = "pago", metodo = "plin", monto = "15.00", estado = "anulado", pagadoEn = Fechas.iso(Fechas.instante(dia, LocalTime(11, 0)))),
        )
        val l = lineas(Exportar.movimientosDeCaja(dia, pagos))
        assertEquals(4, l.size)
        assertEquals("2026-11-16\t09:05\tAna\tadelanto\tyape\t20.00\t123456\tCV-000001\tRecepción\tválido", l[1])
        assertTrue(l[2].contains("\tdevolucion\tefectivo\t-10.00\t"), l[2])
        assertTrue(l[3].endsWith("\tanulado"), l[3])
    }

    @Test
    fun el_cierre_de_caja_dice_cuanto_cobro_cada_usuario() {
        val texto = Comprobante.cierreDeCaja("Cancha Prueba", LocalDate(2026, 11, 16), mapOf("efectivo" to 5000L), 5000L, emptyList(),
            porUsuario = mapOf("Rosa" to 3000L, "Recepción" to 2000L))
        assertTrue(texto.contains("Cobrado por: Rosa S/ 30.00 · Recepción S/ 20.00"), texto)
        val sinDatos = Comprobante.cierreDeCaja(null, LocalDate(2026, 11, 16), emptyMap(), 0L, emptyList())
        assertFalse(sinDatos.contains("Cobrado por"))
    }

    @Test
    fun las_semanas_van_de_lunes_a_domingo() {
        assertEquals(LocalDate(2026, 11, 16), Semana.lunes(LocalDate(2026, 11, 16)))   // lunes
        assertEquals(LocalDate(2026, 11, 16), Semana.lunes(LocalDate(2026, 11, 22)))   // domingo
        assertEquals(LocalDate(2026, 10, 26), Semana.lunes(LocalDate(2026, 11, 1)))    // domingo, cambia de mes
        assertEquals(7, Semana.dias(LocalDate(2026, 12, 28)).size)
        assertEquals(LocalDate(2027, 1, 3), Semana.dias(LocalDate(2026, 12, 28)).last())
        assertEquals("16 al 22 de noviembre", Semana.titulo(LocalDate(2026, 11, 16)))
        assertEquals("30 de noviembre al 6 de diciembre", Semana.titulo(LocalDate(2026, 11, 30)))
        assertEquals("28 de diciembre de 2026 al 3 de enero de 2027", Semana.titulo(LocalDate(2026, 12, 28)))
    }

    @Test
    fun la_semana_muestra_los_siete_dias_en_orden_y_por_dia_del_negocio() {
        val lunes = LocalDate(2026, 11, 16)
        // (día, hora local de Lima): el domingo 23:30 en Lima ya es lunes en UTC y debe quedar en domingo.
        val reservas = listOf(
            "c" to Fechas.instante(LocalDate(2026, 11, 18), LocalTime(20, 0)),
            "a" to Fechas.instante(LocalDate(2026, 11, 18), LocalTime(8, 0)),
            "d" to Fechas.instante(LocalDate(2026, 11, 22), LocalTime(23, 30)),
            "fuera" to Fechas.instante(LocalDate(2026, 11, 23), LocalTime(9, 0)),
        )
        val semana = Semana.agrupar(lunes, reservas) { it.second }
        assertEquals(Semana.dias(lunes), semana.map { it.first })
        assertEquals(listOf("a", "c"), semana[2].second.map { it.first })                   // miércoles, por hora
        assertEquals(listOf("d"), semana[6].second.map { it.first })                        // domingo
        assertTrue(semana[0].second.isEmpty() && semana.none { (_, l) -> l.any { it.first == "fuera" } })
    }

    @Test
    fun cliente_fijo_misma_hora_cada_semana_y_resumen_de_lo_que_no_se_pudo() {
        val serie = Semana.serie(LocalDate(2026, 11, 20), 4)
        assertEquals(listOf(LocalDate(2026, 11, 20), LocalDate(2026, 11, 27), LocalDate(2026, 12, 4), LocalDate(2026, 12, 11)), serie)
        assertEquals(1, Semana.serie(LocalDate(2026, 11, 20), 0).size)                         // al menos la primera
        assertEquals(Semana.MAX_SEMANAS, Semana.serie(LocalDate(2026, 11, 20), 99).size)       // con tope
        assertEquals("Cliente fijo: 4 de 4 reservas registradas.", Semana.resumenSerie(4, 4, 0, emptyList()))
        assertEquals("Cliente fijo: 3 de 4 reservas registradas (1 sin conexión, por validar). No se pudo: vie 4 dic (Ese horario ya está ocupado).",
            Semana.resumenSerie(4, 3, 1, listOf(LocalDate(2026, 12, 4) to "Ese horario ya está ocupado.")))
    }

    @Test
    fun el_reporte_por_mes_no_pide_dias_futuros() {
        val hoy = LocalDate(2026, 11, 16)
        assertEquals(LocalDate(2026, 11, 1) to hoy, Semana.mes(hoy, hoy))
        assertEquals(LocalDate(2026, 10, 1) to LocalDate(2026, 10, 31), Semana.mes(LocalDate(2026, 10, 5), hoy))
        assertEquals(LocalDate(2028, 2, 1) to LocalDate(2028, 2, 29), Semana.mes(LocalDate(2028, 2, 10), LocalDate(2028, 12, 31)))
    }
}
