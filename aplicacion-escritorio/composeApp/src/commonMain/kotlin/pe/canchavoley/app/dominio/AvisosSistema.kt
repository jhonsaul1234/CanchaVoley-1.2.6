@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.dominio

import pe.canchavoley.app.datos.NotificacionCvDto
import pe.canchavoley.app.datos.NotificacionesCvDto
import pe.canchavoley.app.datos.ReservaDto
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Qué avisos se muestran como notificación del celular (Android), sin repetirlos: reglas puras, con pruebas.
 * El historial completo sigue en la pantalla «Notificaciones» (viene del servidor).
 */
object AvisosSistema {
    /** Como máximo se muestran estas notificaciones juntas (el resto queda en la pantalla de avisos). */
    const val MAXIMO_JUNTAS = 5

    /**
     * Avisos nuevos para mostrar: no leídos y con id mayor que el último ya mostrado. La primera vez
     * ([ultimoMostrado] null) no se muestra nada: solo se toma como punto de partida, para no llenar el
     * celular con avisos antiguos al instalar la app o al entrar con otra cuenta.
     */
    fun nuevos(lista: List<NotificacionCvDto>, ultimoMostrado: Long?, maximo: Int = MAXIMO_JUNTAS): List<NotificacionCvDto> {
        if (ultimoMostrado == null) return emptyList()
        return lista.filter { !it.leida && it.id > ultimoMostrado }.sortedBy { it.id }.takeLast(maximo)
    }

    /** Nuevo punto de partida: el mayor id visto (nunca retrocede). */
    fun ultimoVisto(lista: List<NotificacionCvDto>, anterior: Long?): Long? =
        listOfNotNull(anterior, lista.maxOfOrNull { it.id }).maxOrNull()

    /**
     * Copia de los avisos con los [ids] marcados como leídos (abiertos en este equipo y aún no enviados al
     * servidor): el contador de «sin leer» baja en lo que corresponde y nunca queda negativo.
     */
    fun conLeidas(n: NotificacionesCvDto, ids: Set<Long>): NotificacionesCvDto {
        val aMarcar = n.notificaciones.count { !it.leida && it.id in ids }
        if (aMarcar == 0) return n
        return n.copy(noLeidas = (n.noLeidas - aMarcar).coerceAtLeast(0),
            notificaciones = n.notificaciones.map { if (it.id in ids) it.copy(leida = true) else it })
    }

    /**
     * Reservas del cliente que esperan el adelanto y cuyo plazo vence dentro de [margen] (y aún no venció),
     * a las que todavía no se les recordó.
     */
    fun adelantosPorVencer(reservas: List<ReservaDto>, ahora: Instant, yaRecordadas: Set<String>, margen: Duration = 60.minutes): List<ReservaDto> =
        reservas.filter { r ->
            val vence = Fechas.parse(r.adelantoVenceEn)
            r.estado == "pendiente" && r.pendientePor == "adelanto" && r.uuid !in yaRecordadas &&
                r.pagoInformado == null &&                      // ya informó su pago: falta que el negocio lo verifique
                vence != null && vence > ahora && vence - ahora <= margen
        }

    fun textoRecordatorio(r: ReservaDto): String {
        val vence = Fechas.parse(r.adelantoVenceEn)?.let { Fechas.hora(it) } ?: "—"
        return "Paga el adelanto de ${Dinero.soles(r.adelantoRequerido ?: "0")} antes de las $vence para confirmar tu reserva del " +
            "${Fechas.rango(r.inicio, r.fin)}. Abre la reserva para ver cómo pagar."
    }

    /** Id estable de la notificación del recordatorio de una reserva (no choca con los ids de los avisos del servidor). */
    fun idRecordatorio(reservaUuid: String): Int = (reservaUuid.hashCode() and 0x3fffffff) or 0x40000000
}
