@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app

import pe.canchavoley.app.datos.CanchaDto
import pe.canchavoley.app.datos.CatalogoDto
import pe.canchavoley.app.datos.HorarioDto
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.TarifaDto
import pe.canchavoley.app.dominio.DisponibilidadLocal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tarifa informada por el estudiante el 20/11/2026: UNA sola cancha a S/ 20 por hora (1 h S/ 20, 2 h S/ 40, 3 h S/ 60).
 * El horario 08:00–22:00 es de PRUEBA: el horario real lo configura la propietaria desde la app.
 */
class NegocioRealTest {
    private val catalogo = CatalogoDto(
        canchas = listOf(CanchaDto(1, "Cancha de vóley")),
        horarios = (1..7).map { HorarioDto(canchaId = 1, diaSemana = it, abre = "08:00", cierra = "22:00") },
        tarifas = (1..7).map { TarifaDto(canchaId = 1, diaSemana = it, desde = "08:00", hasta = "22:00", precioHora = "20.00") },
        reglas = ReglasDto(),
    )

    @Test
    fun una_cancha_a_20_soles_la_hora() {
        assertEquals(2000L, DisponibilidadLocal.precio(catalogo, 1, t(10), t(11)))       // 1 h
        assertEquals(4000L, DisponibilidadLocal.precio(catalogo, 1, t(10), t(12)))       // 2 h
        assertEquals(6000L, DisponibilidadLocal.precio(catalogo, 1, t(10), t(13)))       // 3 h
        assertEquals(4000L, DisponibilidadLocal.precio(catalogo, 1, t(19), t(21)))       // en la noche, la misma tarifa
        assertEquals(3000L, DisponibilidadLocal.precio(catalogo, 1, t(10), t(11, 30)))   // 1 h 30 min
    }

    @Test
    fun franja_de_todo_el_dia_de_00_00_a_00_00_tambien_cobra_20_soles_la_hora() {
        val todoElDia = catalogo.copy(tarifas = (1..7).map { TarifaDto(canchaId = 1, diaSemana = it, desde = "00:00", hasta = "00:00", precioHora = "20.00") })
        assertEquals(2000L, DisponibilidadLocal.precio(todoElDia, 1, t(8), t(9)))
        assertEquals(6000L, DisponibilidadLocal.precio(todoElDia, 1, t(20), t(23)))
        assertEquals(4000L, DisponibilidadLocal.precio(todoElDia, 1, t(21), t(23)))
    }

    @Test
    fun con_una_sola_cancha_los_libres_y_choques_son_de_esa_cancha() {
        val reservas = listOf(reservaLocal(10, 12))
        val libres = DisponibilidadLocal.libres(catalogo, reservas, 1, DIA, ahora = t(7))
        assertEquals(listOf(t(8) to t(10), t(12) to t(22)), libres.map { it.inicio to it.fin })
        assertEquals(t(10) to t(12), DisponibilidadLocal.choque(catalogo, reservas, 1, t(11), t(12))?.let { it.inicio to it.fin })
    }
}
