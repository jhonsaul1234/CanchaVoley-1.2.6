package pe.canchavoley.app.dominio

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Instant

/** Semanas de lunes a domingo, para la vista semanal de la agenda. */
object Semana {
    fun lunes(fecha: LocalDate): LocalDate = fecha.minus(DatePeriod(days = fecha.dayOfWeek.isoDayNumber - 1))

    fun dias(lunes: LocalDate): List<LocalDate> = (0 until 7).map { lunes.plus(DatePeriod(days = it)) }

    /**
     * Los 7 días de la semana (aunque estén vacíos) con sus elementos, según el día (hora del
     * negocio) en que empieza cada uno y en orden de hora.
     */
    fun <T> agrupar(lunes: LocalDate, elementos: List<T>, inicio: (T) -> Instant): List<Pair<LocalDate, List<T>>> {
        val porDia = elementos.groupBy { Fechas.local(inicio(it)).date }
        return dias(lunes).map { dia -> dia to porDia[dia].orEmpty().sortedBy(inicio) }
    }

    /** «16 al 22 de noviembre», «30 de noviembre al 6 de diciembre» o, entre años, con el año. */
    fun titulo(lunes: LocalDate): String {
        val domingo = lunes.plus(DatePeriod(days = 6))
        val mesL = Fechas.nombreMes(lunes.month.ordinal + 1)
        val mesD = Fechas.nombreMes(domingo.month.ordinal + 1)
        return when {
            lunes.year != domingo.year -> "${lunes.day} de $mesL de ${lunes.year} al ${domingo.day} de $mesD de ${domingo.year}"
            lunes.month != domingo.month -> "${lunes.day} de $mesL al ${domingo.day} de $mesD"
            else -> "${lunes.day} al ${domingo.day} de $mesD"
        }
    }

    /** Cliente fijo: [fecha] y las semanas siguientes, el mismo día (entre 1 y [MAX_SEMANAS] fechas). */
    fun serie(fecha: LocalDate, semanas: Int): List<LocalDate> =
        (0 until semanas.coerceIn(1, MAX_SEMANAS)).map { fecha.plus(DatePeriod(days = 7 * it)) }

    const val MAX_SEMANAS = 12

    /** Resumen de una serie: cuántas se registraron y qué fechas no se pudieron (con el motivo). */
    fun resumenSerie(total: Int, registradas: Int, sinConexion: Int, fallidas: List<Pair<LocalDate, String>>): String = buildString {
        append("Cliente fijo: $registradas de $total reservas registradas")
        if (sinConexion > 0) append(" ($sinConexion sin conexión, por validar)")
        append(".")
        if (fallidas.isNotEmpty()) {
            append(" No se pudo: ")
            append(fallidas.joinToString("; ") { (dia, motivo) -> "${Fechas.fechaCorta(dia)} (${motivo.trim().trimEnd('.')})" })
            append(".")
        }
    }

    /** Primer y último día del mes de [fecha], sin pasar de [tope] (para no pedir días futuros). */
    fun mes(fecha: LocalDate, tope: LocalDate): Pair<LocalDate, LocalDate> {
        val primero = LocalDate(fecha.year, fecha.month, 1)
        val ultimo = primero.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        return primero to minOf(ultimo, tope)
    }
}
