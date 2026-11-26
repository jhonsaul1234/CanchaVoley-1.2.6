@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.dominio

import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.ReservaDto
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Qué mensaje de WhatsApp corresponde según el estado de la reserva. */
enum class TipoWhatsApp { RECORDATORIO, OTRO_HORARIO, CONTACTO }

/**
 * Acciones que se ofrecen en el detalle de una reserva. Son las mismas reglas que valida el servidor
 * desde el 22/11/2026: la app solo muestra lo que corresponde y el servidor vuelve a validar y decide.
 */
data class AccionesReserva(
    val aprobarORechazar: Boolean = false,
    /** Movimientos que se pueden registrar (adelanto, pago, devolucion). Vacío = no se muestra «Registrar pago». */
    val tiposPago: List<String> = emptyList(),
    /** «Llegó el cliente» (o «Llegó tarde» si estaba marcado «No asistió» y su horario no terminó). */
    val llegoCliente: Boolean = false,
    val llegoTarde: Boolean = false,
    /** «No asistió» se muestra; si [noAsistioDesde] no es null, todavía no se puede marcar. */
    val noAsistio: Boolean = false,
    val noAsistioDesde: Instant? = null,
    /** Solo la administradora, con motivo. */
    val corregirAsistencia: Boolean = false,
    val editarNotas: Boolean = false,
    val editarPrecio: Boolean = false,
    /** El cambio de precio pide motivo (ya hay pagos o asistencia registrada). */
    val precioConMotivo: Boolean = false,
    val cambiarHorario: Boolean = false,
    val cancelar: Boolean = false,
    val whatsapp: TipoWhatsApp? = null,
    /** Cliente: mostrar cómo pagar el adelanto. */
    val comoPagar: Boolean = false,
    /** Cliente: puede informar un pago (no tiene otro pendiente de verificar). */
    val informarPago: Boolean = false,
) {
    val registrarPago: Boolean get() = tiposPago.isNotEmpty()
    val soloDevolucion: Boolean get() = tiposPago == listOf("devolucion")
}

object ReglasReserva {
    private val ACTIVAS = setOf("pendiente", "confirmada")

    fun activa(r: ReservaDto): Boolean = r.estado in ACTIVAS

    /** Céntimos cobrados (neto: adelantos y pagos menos devoluciones). */
    fun pagado(r: ReservaDto): Long = Dinero.aCentimos(r.totalPagado) ?: 0L

    /** Céntimos que faltan pagar; null si la reserva no tiene precio o no está activa. */
    fun saldoPendiente(r: ReservaDto): Long? {
        if (!activa(r)) return null
        val precio = Dinero.aCentimos(r.precioFinal) ?: return null
        return (precio - pagado(r)).coerceAtLeast(0)
    }

    /** Pendiente del adelanto con el plazo vencido (el horario puede liberarse si otra persona lo pide). */
    fun plazoVencido(r: ReservaDto, ahora: Instant): Boolean =
        r.estado == "pendiente" && r.pendientePor == "adelanto" &&
            (r.adelantoVencido || (Fechas.parse(r.adelantoVenceEn)?.let { it < ahora } ?: false))

    /** Desde cuándo se puede marcar «No asistió»: hora de inicio más la tolerancia de las reglas del negocio. */
    fun noAsistioDesde(r: ReservaDto, reglas: ReglasDto?): Instant? =
        Fechas.parse(r.inicio)?.plus((reglas?.toleranciaNoShowMin ?: 0).coerceAtLeast(0).minutes)

    fun acciones(r: ReservaDto, esPersonal: Boolean, esAdmin: Boolean, ahora: Instant, reglas: ReglasDto?): AccionesReserva {
        // Registrada sin conexión y aún no validada por el servidor: solo se consulta.
        if (r.estado == "por_sincronizar") return AccionesReserva()
        val activa = activa(r)
        val terminada = Fechas.parse(r.fin)?.let { it <= ahora } ?: false
        val sinAsistencia = r.asistencia == null

        if (!esPersonal) {
            val esperaAdelanto = r.estado == "pendiente" && r.pendientePor == "adelanto"
            return AccionesReserva(
                cambiarHorario = activa && sinAsistencia && r.pendientePor == "aprobacion",
                cancelar = activa && sinAsistencia && !terminada,
                comoPagar = esperaAdelanto,
                informarPago = esperaAdelanto && r.pagoInformado == null,
            )
        }

        val pagado = pagado(r)
        val saldo = saldoPendiente(r)
        val tipos = buildList {
            if (activa && (saldo == null || saldo > 0)) { add("adelanto"); add("pago") }
            if (esAdmin && pagado > 0) add("devolucion")
        }
        val confirmada = r.estado == "confirmada"
        val llegoTarde = confirmada && r.asistencia == "no_asistio" && !terminada
        return AccionesReserva(
            aprobarORechazar = esAdmin && r.estado == "pendiente" && r.pendientePor == "aprobacion",
            tiposPago = tipos,
            llegoCliente = confirmada && (sinAsistencia || llegoTarde),
            llegoTarde = llegoTarde,
            noAsistio = confirmada && sinAsistencia,
            noAsistioDesde = if (confirmada && sinAsistencia) noAsistioDesde(r, reglas)?.takeIf { it > ahora } else null,
            corregirAsistencia = esAdmin && confirmada && !sinAsistencia,
            editarNotas = true,
            editarPrecio = esAdmin && activa,
            precioConMotivo = pagado > 0 || !sinAsistencia,
            cambiarHorario = activa && sinAsistencia,
            cancelar = activa && sinAsistencia,
            whatsapp = when {
                // Recordatorio solo de una reserva ya aceptada; una solicitud por aprobar se conversa con el cliente.
                activa && sinAsistencia && !terminada && r.pendientePor != "aprobacion" -> TipoWhatsApp.RECORDATORIO
                r.estado == "rechazada" -> TipoWhatsApp.OTRO_HORARIO
                else -> TipoWhatsApp.CONTACTO
            },
        )
    }

    /**
     * Devolución que sugieren las reglas del negocio (la administradora decide y nunca puede pasar de lo
     * cobrado). Reserva activa: lo cobrado de más. Cancelada o rechazada: según «devolución al cancelar».
     */
    fun devolucionSugerida(r: ReservaDto, reglas: ReglasDto?): Long {
        val pagado = pagado(r)
        if (pagado <= 0) return 0
        if (activa(r)) {
            val precio = Dinero.aCentimos(r.precioFinal) ?: return 0
            return (pagado - precio).coerceAtLeast(0)
        }
        if (r.estado == "rechazada") return pagado          // el negocio no aceptó la reserva
        return when (reglas?.cancelacionDevolucion) {
            "parcial" -> devolucionPorRegla(pagado, reglas)
            "ninguna", "credito" -> 0
            else -> pagado                                       // "total" o sin configurar
        }
    }

    /** Texto de la regla de devolución al cancelar (null si no está configurada). */
    fun textoReglaDevolucion(reglas: ReglasDto?): String? = when (reglas?.cancelacionDevolucion) {
        "total" -> "se devuelve todo lo pagado"
        "parcial" -> "se devuelve el ${porcentaje(reglas)} % de lo pagado"
        "ninguna" -> "no se devuelve lo pagado"
        "credito" -> "lo pagado queda como crédito para otra reserva"
        else -> null
    }

    private fun porcentaje(reglas: ReglasDto?): String =
        reglas?.cancelacionPorcentaje?.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "?"

    /**
     * Cliente: qué pasa con lo que pagó si cancela (o ya canceló), según la regla que configuró la administradora.
     * Sin regla configurada no se promete nada: se le pide comunicarse con el negocio.
     */
    fun textoPagadoAlCancelar(pagado: Long, reglas: ReglasDto?): String {
        val monto = Dinero.soles(pagado)
        return when (reglas?.cancelacionDevolucion) {
            "credito" -> "Lo que pagaste ($monto) queda como crédito a tu favor para otra reserva."
            "ninguna" -> "Según la política del negocio, lo que pagaste ($monto) no se devuelve."
            "total" -> "Según la política del negocio, se te devuelve todo lo que pagaste ($monto). El negocio coordinará la devolución contigo."
            "parcial" -> "Según la política del negocio, se te devuelve el ${porcentaje(reglas)} % de lo que pagaste " +
                "(${Dinero.soles(devolucionPorRegla(pagado, reglas))} de $monto). El negocio coordinará la devolución contigo."
            else -> "Lo que pagaste ($monto) queda a tu favor. Comunícate con el negocio para acordar la devolución o usarlo en otra reserva."
        }
    }

    private fun devolucionPorRegla(pagado: Long, reglas: ReglasDto?): Long {
        val pct = reglas?.cancelacionPorcentaje?.toDoubleOrNull() ?: 100.0
        return (pagado * pct / 100.0).toLong().coerceIn(0, pagado)
    }

    /** Céntimos ya devueltos al cliente (devoluciones válidas de sus comprobantes). */
    fun devuelto(pagos: List<PagoDto>): Long =
        pagos.filter { it.tipo == "devolucion" && it.estado == "valido" }.sumOf { Dinero.aCentimos(it.monto) ?: 0L }

    /**
     * Anular un cobro (adelanto o pago) que ya se devolvió dejaría lo devuelto por encima de lo cobrado.
     * El servidor lo rechaza (409); la app lo explica antes de pedir el motivo.
     */
    fun anulacionDejaNegativo(r: ReservaDto, pago: PagoDto): Boolean =
        pago.tipo != "devolucion" && pago.estado == "valido" && pagado(r) - (Dinero.aCentimos(pago.monto) ?: 0L) < 0
}
