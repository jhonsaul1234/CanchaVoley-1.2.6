@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import pe.canchavoley.app.datos.CanchaDto
import pe.canchavoley.app.datos.CatalogoDto
import pe.canchavoley.app.datos.HorarioDto
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.ReservaLocal
import pe.canchavoley.app.datos.TarifaDto
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.DisponibilidadLocal
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Telefono
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Valores FICTICIOS solo para pruebas (no son horarios ni precios del negocio). */
fun catalogoDePrueba(reglas: ReglasDto = ReglasDto()) = CatalogoDto(
    canchas = listOf(CanchaDto(1, "Cancha de prueba")),
    horarios = (1..7).map { HorarioDto(canchaId = 1, diaSemana = it, abre = "08:00", cierra = "22:00") },
    tarifas = (1..7).flatMap { listOf(TarifaDto(canchaId = 1, diaSemana = it, desde = "08:00", hasta = "18:00", precioHora = "30.00"),
        TarifaDto(canchaId = 1, diaSemana = it, desde = "18:00", hasta = "22:00", precioHora = "40.00")) },
    reglas = reglas,
)

val DIA = LocalDate(2030, 6, 4)
fun t(h: Int, m: Int = 0) = Fechas.instante(DIA, LocalTime(h, m))
fun reservaLocal(ini: Int, fin: Int, estado: String = "confirmada", uuid: String = "r$ini") =
    ReservaLocal(ReservaDto(uuid = uuid, canchaId = 1, inicio = Fechas.iso(t(ini)), fin = Fechas.iso(t(fin)), estado = estado), ReservaLocal.SINCRONIZADO, null)

class DominioTest {
    @Test fun dinero_en_centimos_sin_redondeos_flotantes() {
        assertEquals(4550L, Dinero.aCentimos("45.5"))
        assertEquals(4550L, Dinero.aCentimos("45,50"))
        assertEquals(-1000L, Dinero.aCentimos("-10"))
        assertNull(Dinero.aCentimos("abc"))
        assertEquals("0.10", Dinero.aTexto(10))
        assertEquals("S/ 60.00", Dinero.soles("60"))
    }

    @Test fun telefono_con_el_mismo_formato_que_el_servidor() {
        assertEquals("51987654321", Telefono.normalizar("987 654 321"))
        assertEquals("51987654321", Telefono.normalizar("+51 987-654-321"))
        assertEquals("987 654 321", Telefono.mostrar("51987654321"))
        assertTrue(!Telefono.valido("12"))
    }

    @Test fun iso_con_desfase_de_lima() {
        assertEquals("2030-06-04T10:30:00-05:00", Fechas.iso(t(10, 30)))
        assertEquals(t(10, 30), Fechas.parse("2030-06-04T15:30:00Z"))
    }

    @Test fun libres_del_dia_y_choques_con_reservas_y_margen() {
        val cat = catalogoDePrueba()
        val reservas = listOf(reservaLocal(10, 12), reservaLocal(14, 15, "por_sincronizar"), reservaLocal(16, 17, "cancelada"))
        val libres = DisponibilidadLocal.libres(cat, reservas, 1, DIA, ahora = t(0))
        assertEquals(listOf(8 to 10, 12 to 14, 15 to 22), libres.map { Fechas.local(it.inicio).hour to Fechas.local(it.fin).hour })
        assertNotNull(DisponibilidadLocal.choque(cat, reservas, 1, t(11), t(13)))
        assertNull(DisponibilidadLocal.choque(cat, reservas, 1, t(12), t(13)))          // contigua: sin choque
        val conMargen = catalogoDePrueba(ReglasDto(margenEntreReservasMin = 15))
        assertNotNull(DisponibilidadLocal.choque(conMargen, reservas, 1, t(12), t(13)))
        assertTrue(DisponibilidadLocal.dentroDeHorario(cat, 1, t(20), t(22)))
        assertTrue(!DisponibilidadLocal.dentroDeHorario(cat, 1, t(21), t(23)))
    }

    @Test fun sugerencias_cercanas_a_la_hora_pedida() {
        val s = DisponibilidadLocal.sugerencias(catalogoDePrueba(), listOf(reservaLocal(10, 12)), 1, DIA, 120, preferida = t(11))
        assertEquals(t(12), s.first().inicio)
        assertTrue(s.none { it.inicio < t(12) && it.fin > t(10) })
    }

    @Test fun precio_con_tarifas_sin_inventar_valores() {
        assertEquals(4500L, DisponibilidadLocal.precio(catalogoDePrueba(), 1, t(10), t(11, 30)))
        assertNull(DisponibilidadLocal.precio(catalogoDePrueba(), 1, t(17), t(19)))           // cruza franjas sin regla
        assertEquals(7000L, DisponibilidadLocal.precio(catalogoDePrueba(ReglasDto(reglaCruceFranjas = "prorrateo")), 1, t(17), t(19)))
        assertEquals(6000L, DisponibilidadLocal.precio(catalogoDePrueba(ReglasDto(reglaCruceFranjas = "franja_inicio")), 1, t(17), t(19)))
        assertNull(DisponibilidadLocal.precio(catalogoDePrueba(), 1, t(6), t(7)))             // sin tarifa
    }
}
