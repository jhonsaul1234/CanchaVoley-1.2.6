@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.dominio

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Fechas del negocio. El servidor guarda la hora de Lima (Perú no cambia de hora: siempre UTC−5) y
 * la API envía ISO 8601 con desfase. En la app se trabaja con [Instant] y se muestra en la zona del negocio.
 */
object Fechas {
    var zona: TimeZone = TimeZone.of("America/Lima")

    fun ahora(): Instant = Clock.System.now()

    fun hoy(): LocalDate = ahora().toLocalDateTime(zona).date

    fun parse(iso: String?): Instant? = try { iso?.let { Instant.parse(it) } } catch (_: Exception) { null }

    fun local(instante: Instant): LocalDateTime = instante.toLocalDateTime(zona)

    fun instante(fecha: LocalDate, hora: LocalTime): Instant = fecha.atTime(hora).toInstant(zona)

    /** ISO 8601 con el desfase de la zona del negocio, p. ej. 2026-12-02T10:00:00-05:00 */
    fun iso(instante: Instant): String {
        val l = local(instante)
        val desfase = instante.offsetIn(zona).totalSeconds
        val signo = if (desfase < 0) "-" else "+"
        val abs = kotlin.math.abs(desfase)
        val hh = (abs / 3600).toString().padStart(2, '0')
        val mm = ((abs % 3600) / 60).toString().padStart(2, '0')
        return "${l.date}T${dos(l.hour)}:${dos(l.minute)}:${dos(l.second)}$signo$hh:$mm"
    }

    fun hora(instante: Instant): String = local(instante).let { "${dos(it.hour)}:${dos(it.minute)}" }

    fun hora(iso: String?): String = parse(iso)?.let { hora(it) } ?: "--:--"

    fun fechaCorta(fecha: LocalDate): String = "${nombreDia(fecha.dayOfWeek).take(3)} ${fecha.day} ${nombreMes(fecha.month.ordinal + 1).take(3)}"

    fun fechaLarga(fecha: LocalDate): String = "${nombreDia(fecha.dayOfWeek)} ${fecha.day} de ${nombreMes(fecha.month.ordinal + 1)} de ${fecha.year}"

    fun fechaHora(iso: String?): String = parse(iso)?.let { val l = local(it); "${fechaCorta(l.date)} ${hora(it)}" } ?: "—"

    fun rango(inicioIso: String, finIso: String): String {
        val a = parse(inicioIso) ?: return "—"
        val b = parse(finIso) ?: return "—"
        return "${fechaCorta(local(a).date)} · ${hora(a)}–${hora(b)}"
    }

    fun duracion(minutos: Int): String = when {
        minutos % 60 == 0 -> "${minutos / 60} h"
        minutos > 60 -> "${minutos / 60} h ${minutos % 60} min"
        else -> "$minutos min"
    }

    fun nombreDia(dia: DayOfWeek): String = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")[dia.isoDayNumber - 1]

    fun nombreDia(isoDia: Int): String = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")[isoDia - 1]

    fun nombreMes(mes: Int): String = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
        "septiembre", "octubre", "noviembre", "diciembre")[mes - 1]

    /** "18:30" → LocalTime, o null si no es válido. */
    fun leerHora(texto: String): LocalTime? {
        val p = texto.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) LocalTime(h, m) else null
    }

    fun dos(n: Int) = n.toString().padStart(2, '0')
}
