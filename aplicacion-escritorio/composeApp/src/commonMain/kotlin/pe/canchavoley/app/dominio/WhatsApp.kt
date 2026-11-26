package pe.canchavoley.app.dominio

import io.ktor.http.encodeURLParameter
import pe.canchavoley.app.datos.ReservaDto

/**
 * Mensajes al cliente por WhatsApp con un enlace `wa.me`: abre WhatsApp (o WhatsApp Web) con el
 * texto ya escrito y la persona decide si lo envía. No usa cuentas, claves ni servicios de pago.
 * Los textos solo usan datos de la reserva y del negocio; no agregan políticas ni precios.
 */
object WhatsApp {
    /** Enlace con el número internacional (51 + celular). `null` si el número no sirve. */
    fun enlace(telefono: String?, texto: String? = null): String? {
        val numero = Telefono.normalizar(telefono)
        if (!Telefono.valido(numero)) return null
        val mensaje = texto?.trim()?.takeIf { it.isNotEmpty() } ?: return "https://wa.me/$numero"
        return "https://wa.me/$numero?text=${mensaje.encodeURLParameter()}"
    }

    /** Recordatorio de una reserva pendiente o confirmada. */
    fun recordatorio(negocio: String?, reserva: ReservaDto, cancha: String?): String = buildString {
        append(saludo(reserva))
        append("te recordamos tu reserva en ${lugar(negocio)}")
        cancha?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        append(": ${diaYHora(reserva)}.")
        if (reserva.estado == "pendiente") {
            append(if (reserva.pendientePor == "adelanto") " Para confirmarla falta el adelanto." else " Aún está pendiente de confirmación.")
        }
        Dinero.aCentimos(reserva.saldo)?.takeIf { it > 0 }?.let { append(" Saldo por pagar: ${Dinero.soles(it)}.") }
        reserva.codigoQr?.takeIf { it.isNotBlank() }?.let { append(" Código de tu reserva: $it.") }
    }

    /** Cuando la reserva fue rechazada (por ejemplo, el horario lo tomó otra persona antes). */
    fun otroHorario(negocio: String?, reserva: ReservaDto): String =
        "${saludo(reserva)}te escribimos de ${lugar(negocio)} por tu reserva del ${diaYHora(reserva)}: " +
            "ese horario no está disponible. ¿Te podemos ofrecer otro horario?"

    /** Para escribirle al cliente sobre su reserva (cancelada, ya jugada, etc.) sin recordarle una asistencia. */
    fun contacto(negocio: String?, reserva: ReservaDto): String =
        "${saludo(reserva)}te escribimos de ${lugar(negocio)} sobre tu reserva del ${diaYHora(reserva)}" +
            if (reserva.estado == "cancelada") " (cancelada)." else "."

    /** Del cliente al negocio (app del cliente): para coordinar el pago del adelanto de su reserva. */
    fun alNegocio(reserva: ReservaDto): String = buildString {
        append("Hola, ")
        reserva.clienteNombre?.trim()?.takeIf { it.isNotEmpty() }?.let { append("soy $it. ") }
        append("Escribo por mi reserva del ${diaYHora(reserva)}")
        append(if (reserva.estado == "pendiente" && reserva.pendientePor == "adelanto") ": quiero pagar el adelanto." else ".")
    }

    private fun saludo(r: ReservaDto): String =
        r.clienteNombre?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }?.let { "Hola $it, " } ?: "Hola, "

    private fun lugar(negocio: String?): String = negocio?.trim()?.takeIf { it.isNotEmpty() } ?: "la cancha"

    private fun diaYHora(r: ReservaDto): String {
        val fecha = Fechas.parse(r.inicio)?.let { Fechas.fechaLarga(Fechas.local(it).date) } ?: r.inicio
        return "$fecha de ${Fechas.hora(r.inicio)} a ${Fechas.hora(r.fin)}"
    }
}
