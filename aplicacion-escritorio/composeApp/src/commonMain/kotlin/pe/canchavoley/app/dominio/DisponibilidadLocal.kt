@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.dominio

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.LocalTime
import pe.canchavoley.app.datos.CatalogoDto
import pe.canchavoley.app.datos.OcupacionDto
import pe.canchavoley.app.datos.ReservaLocal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class Intervalo(val inicio: Instant, val fin: Instant) {
    val minutos: Int get() = ((fin - inicio).inWholeMinutes).toInt()
}

/**
 * Disponibilidad calculada en el equipo con los datos descargados (para consultar sin internet).
 * Es la misma lógica que el servidor, pero solo orientativa: la decisión final es siempre del servidor.
 */
object DisponibilidadLocal {
    const val PASO_SUGERENCIAS = 30

    fun turnos(c: CatalogoDto, canchaId: Long, fecha: LocalDate): List<Intervalo> {
        val dia = fecha.dayOfWeek.isoDayNumber
        val lista = c.horarios.filter { it.canchaId == canchaId && it.diaSemana == dia }.mapNotNull { h ->
            val a = Fechas.leerHora(h.abre) ?: return@mapNotNull null
            val b = Fechas.leerHora(h.cierra) ?: return@mapNotNull null
            val ini = Fechas.instante(fecha, a)
            var fin = Fechas.instante(fecha, b)
            if (fin <= ini) fin = Fechas.instante(fecha.plus(DatePeriod(days = 1)), b)
            Intervalo(ini, fin)
        }
        return fusionar(lista)
    }

    fun tieneHorario(c: CatalogoDto, canchaId: Long) = c.horarios.any { it.canchaId == canchaId }

    /**
     * Aviso inmediato cuando se ESCRIBE una hora que no está libre (los turnos, ocupados y libres son los
     * de la pantalla: del servidor o, sin conexión, del equipo). null = no hay nada que avisar.
     * Es solo orientativo: al registrar, el servidor vuelve a validar y decide.
     */
    fun avisoHoraEscrita(turnos: List<Intervalo>, ocupados: List<Intervalo>, libres: List<Intervalo>, inicio: Instant, fin: Instant): String? = when {
        turnos.isEmpty() -> null                                                   // sin horario: decide el servidor
        turnos.none { it.inicio <= inicio && fin <= it.fin } -> "Esa hora está fuera del horario de atención."
        ocupados.any { it.inicio < fin && it.fin > inicio } -> "Esa hora se cruza con otra reserva o con un cierre."
        libres.none { it.inicio <= inicio && fin <= it.fin } -> "Esa hora no está libre (ya pasó o no deja el margen entre reservas)."
        else -> null
    }

    fun dentroDeHorario(c: CatalogoDto, canchaId: Long, inicio: Instant, fin: Instant): Boolean {
        val fecha = Fechas.local(inicio).date
        val todos = turnos(c, canchaId, fecha.plus(DatePeriod(days = -1))) + turnos(c, canchaId, fecha) + turnos(c, canchaId, fecha.plus(DatePeriod(days = 1)))
        return fusionar(todos).any { it.inicio <= inicio && fin <= it.fin }
    }

    /**
     * Ocupado entre [desde, hasta): reservas que ocupan (incluidas las registradas sin conexión), bloqueos y
     * [extra]: la ocupación que la app del cliente guardó en su última conexión (reservas de otros clientes).
     */
    fun ocupados(c: CatalogoDto, reservas: List<ReservaLocal>, canchaId: Long, desde: Instant, hasta: Instant, excluir: String? = null,
                 extra: List<Intervalo> = emptyList()): List<Intervalo> {
        val r = reservas.filter { it.dto.canchaId == canchaId && it.ocupa && it.dto.uuid != excluir && it.inicio < hasta && it.fin > desde }
            .map { Intervalo(it.inicio, it.fin) }
        val b = c.bloqueos.filter { !it.eliminado && (it.canchaId == null || it.canchaId == canchaId) }.mapNotNull {
            val a = Fechas.parse(it.inicio) ?: return@mapNotNull null
            val z = Fechas.parse(it.fin) ?: return@mapNotNull null
            if (a < hasta && z > desde) Intervalo(a, z) else null
        }
        val g = extra.filter { it.inicio < hasta && it.fin > desde }
        return (r + b + g).distinct().sortedBy { it.inicio }
    }

    fun choque(c: CatalogoDto, reservas: List<ReservaLocal>, canchaId: Long, inicio: Instant, fin: Instant, excluir: String? = null,
               extra: List<Intervalo> = emptyList()): Intervalo? {
        val m = (c.reglas.margenEntreReservasMin ?: 0).minutes
        return ocupados(c, reservas, canchaId, inicio - m - 1.days, fin + m + 1.days, excluir, extra).firstOrNull { it.inicio < fin + m && it.fin > inicio - m }
    }

    /**
     * Horarios ocupados de una cancha según la ocupación guardada en la última conexión (app del cliente).
     * [quitar]: el horario actual de la reserva que se está cambiando (para no chocar consigo misma).
     */
    fun intervalosGuardados(o: OcupacionDto?, canchaId: Long, quitar: Intervalo? = null): List<Intervalo> =
        o?.intervalos.orEmpty().filter { it.canchaId == canchaId }.mapNotNull { i ->
            val a = Fechas.parse(i.inicio) ?: return@mapNotNull null
            val b = Fechas.parse(i.fin) ?: return@mapNotNull null
            Intervalo(a, b).takeIf { it != quitar }
        }

    /** ¿La ocupación guardada cubre todo ese día? Si no, sin conexión no se sabe qué está libre. */
    fun cubre(o: OcupacionDto?, fecha: LocalDate): Boolean {
        val desde = Fechas.parse(o?.desde) ?: return false
        val hasta = Fechas.parse(o?.hasta) ?: return false
        return desde <= Fechas.instante(fecha, LocalTime(0, 0)) && Fechas.instante(fecha.plus(DatePeriod(days = 1)), LocalTime(0, 0)) <= hasta
    }

    fun libres(c: CatalogoDto, reservas: List<ReservaLocal>, canchaId: Long, fecha: LocalDate, ahora: Instant = Fechas.ahora(), excluir: String? = null,
               extra: List<Intervalo> = emptyList()): List<Intervalo> {
        val t = turnos(c, canchaId, fecha)
        if (t.isEmpty()) return emptyList()
        val m = (c.reglas.margenEntreReservasMin ?: 0).minutes
        val ocup = ocupados(c, reservas, canchaId, t.first().inicio - m, t.last().fin + m, excluir, extra)
        val libres = mutableListOf<Intervalo>()
        for (turno in t) {
            var cursor = maxOf(turno.inicio, ahora)
            for (o in ocup) {
                val s = o.inicio - m
                val e = o.fin + m
                if (e <= cursor || s >= turno.fin) continue
                if (s > cursor) libres += Intervalo(cursor, minOf(s, turno.fin))
                cursor = maxOf(cursor, e)
                if (cursor >= turno.fin) break
            }
            if (cursor < turno.fin) libres += Intervalo(cursor, turno.fin)
        }
        return libres
    }

    fun sugerencias(c: CatalogoDto, reservas: List<ReservaLocal>, canchaId: Long, fecha: LocalDate, duracionMin: Int,
                    preferida: Instant? = null, dias: Int = 7, limite: Int = 6, excluir: String? = null,
                    extra: List<Intervalo> = emptyList()): List<Intervalo> {
        if (duracionMin <= 0) return emptyList()
        val paso = maxOf(5, c.reglas.alinearInicioMin ?: c.reglas.pasoMin ?: PASO_SUGERENCIAS)
        val candidatos = mutableListOf<Pair<Long, Instant>>()
        for (i in 0 until dias) {
            val dia = fecha.plus(DatePeriod(days = i))
            for (l in libres(c, reservas, canchaId, dia, excluir = excluir, extra = extra)) {
                val local = Fechas.local(l.inicio)
                val resto = (local.hour * 60 + local.minute) % paso
                var ini = if (resto == 0 && local.second == 0) l.inicio
                          else Instant.fromEpochSeconds((l.inicio.epochSeconds / 60) * 60) + (paso - resto).minutes
                while (ini + duracionMin.minutes <= l.fin) {
                    val dist = preferida?.let { kotlin.math.abs((ini - it).inWholeMinutes) } ?: (ini - Fechas.instante(fecha, kotlinx.datetime.LocalTime(0, 0))).inWholeMinutes
                    candidatos += dist to ini
                    ini += paso.minutes
                }
            }
            if (preferida == null && candidatos.size >= limite) break
        }
        return candidatos.sortedWith(compareBy({ it.first }, { it.second })).take(limite).map { Intervalo(it.second, it.second + duracionMin.minutes) }
    }

    /**
     * Horas de inicio posibles para una duración dentro de los tramos libres, cada [paso] minutos
     * (alineadas al reloj: con paso 30 → 18:00, 18:30…). Sirve para elegir la hora tocando una ficha.
     */
    fun horasDeInicio(libres: List<Intervalo>, duracionMin: Int, paso: Int, ahora: Instant? = null): List<Instant> {
        if (duracionMin <= 0) return emptyList()
        val p = maxOf(5, paso)
        val horas = mutableListOf<Instant>()
        for (l in libres) {
            val local = Fechas.local(l.inicio)
            val resto = (local.hour * 60 + local.minute) % p
            var ini = if (resto == 0 && local.second == 0) l.inicio
                      else Instant.fromEpochSeconds((l.inicio.epochSeconds / 60) * 60) + (p - resto).minutes
            while (ini + duracionMin.minutes <= l.fin) {
                if (ahora == null || ini >= ahora) horas += ini
                ini += p.minutes
            }
        }
        return horas.distinct().sorted()
    }

    /** Precio según las tarifas descargadas (mismo criterio que el servidor). NULL = no se puede calcular. */
    fun precio(c: CatalogoDto, canchaId: Long, inicio: Instant, fin: Instant): Long? {
        val minutos = (fin - inicio).inWholeMinutes.toInt()
        if (minutos <= 0) return null
        val porMinuto = (0 until minutos).map { i ->
            val t = Fechas.local(inicio + i.minutes)
            val min = t.hour * 60 + t.minute
            val fechaTxt = t.date.toString()
            c.tarifas.filter { it.canchaId == canchaId && it.diaSemana == t.date.dayOfWeek.isoDayNumber
                    && (it.vigenteDesde == null || it.vigenteDesde <= fechaTxt) && (it.vigenteHasta == null || it.vigenteHasta >= fechaTxt) }
                .firstOrNull { f ->
                    val d = Fechas.leerHora(f.desde) ?: return@firstOrNull false
                    val h = Fechas.leerHora(f.hasta) ?: return@firstOrNull false
                    val hm = (h.hour * 60 + h.minute).let { if (it == 0) 1440 else it }
                    d.hour * 60 + d.minute <= min && min < hm
                }?.let { Dinero.aCentimos(it.precioHora) }
        }
        val regla = c.reglas.reglaCruceFranjas
        if (regla == "franja_inicio") return porMinuto.first()?.let { (it * minutos + 30) / 60 }
        if (porMinuto.any { it == null }) return null
        if (porMinuto.distinct().size > 1 && regla != "prorrateo") return null
        return (porMinuto.sumOf { it!! } + 30) / 60
    }

    private fun fusionar(lista: List<Intervalo>): List<Intervalo> {
        val res = mutableListOf<Intervalo>()
        for (i in lista.sortedBy { it.inicio }) {
            val ultimo = res.lastOrNull()
            if (ultimo != null && i.inicio <= ultimo.fin) res[res.lastIndex] = Intervalo(ultimo.inicio, maxOf(ultimo.fin, i.fin))
            else res += i
        }
        return res
    }
}
