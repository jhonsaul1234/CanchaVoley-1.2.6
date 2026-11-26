package pe.canchavoley.app.dominio

import pe.canchavoley.app.datos.NegocioDto
import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.ReservaDto

/** Texto del comprobante interno de una reserva, para copiarlo o compartirlo (WhatsApp, correo). */
object Comprobante {
    fun texto(negocio: NegocioDto?, reserva: ReservaDto, pagos: List<PagoDto>, cancha: String?): String = buildString {
        val nombreNegocio = negocio?.nombre?.takeIf { it.isNotBlank() } ?: "CanchaVoley"
        appendLine("$nombreNegocio — comprobante interno")
        listOfNotNull(negocio?.telefono?.takeIf { it.isNotBlank() }?.let { "Tel. ${Telefono.mostrar(it)}" }, negocio?.direccion?.takeIf { it.isNotBlank() })
            .takeIf { it.isNotEmpty() }?.let { appendLine(it.joinToString(" · ")) }
        appendLine()
        val inicio = Fechas.parse(reserva.inicio)
        val fecha = inicio?.let { Fechas.fechaLarga(Fechas.local(it).date).replaceFirstChar { c -> c.uppercase() } } ?: reserva.inicio
        appendLine("Reserva: $fecha, ${Fechas.hora(reserva.inicio)}–${Fechas.hora(reserva.fin)} (${Fechas.duracion(reserva.duracionMin)})")
        cancha?.let { appendLine("Cancha: $it") }
        reserva.clienteNombre?.let { appendLine("Cliente: $it" + (reserva.clienteTelefono?.let { t -> " (${Telefono.mostrar(t)})" } ?: "")) }
        reserva.precioFinal?.let { appendLine("Precio: ${Dinero.soles(it)}") }
        val validos = pagos.filter { it.estado == "valido" }
        if (validos.isNotEmpty()) {
            appendLine("Pagos:")
            validos.forEach { p ->
                val tipo = when (p.tipo) { "adelanto" -> "Adelanto"; "devolucion" -> "Devolución"; else -> "Pago" }
                val metodo = when (p.metodo) { "yape" -> "Yape"; "plin" -> "Plin"; else -> "Efectivo" }
                appendLine("  " + listOfNotNull(p.numeroComprobante, Fechas.fechaHora(p.pagadoEn), tipo, metodo,
                    p.numeroOperacion?.let { "op. $it" }, (if (p.tipo == "devolucion") "−" else "") + Dinero.soles(p.monto)).joinToString(" · "))
            }
        }
        val estado = listOfNotNull(reserva.totalPagado?.let { "Pagado: ${Dinero.soles(it)}" }, reserva.saldo?.let { "Saldo: ${Dinero.soles(it)}" })
        if (estado.isNotEmpty()) appendLine(estado.joinToString(" · "))
        reserva.codigoQr?.let { appendLine("Código de la reserva: $it") }
        append("Comprobante interno del negocio; no reemplaza una boleta o factura electrónica.")
    }

    /** Resumen del día para el cierre de caja (arqueo): totales por método y movimientos. */
    fun cierreDeCaja(negocio: String?, fecha: kotlinx.datetime.LocalDate, totales: Map<String, Long>, total: Long, pagos: List<PagoDto>,
                     porUsuario: Map<String, Long> = emptyMap()): String = buildString {
        appendLine("${negocio?.takeIf { it.isNotBlank() } ?: "CanchaVoley"} — caja del ${Fechas.fechaLarga(fecha)}")
        listOf("efectivo" to "Efectivo", "yape" to "Yape", "plin" to "Plin").forEach { (k, n) -> appendLine("$n: ${Dinero.soles(totales[k] ?: 0)}") }
        appendLine("Total: ${Dinero.soles(total)}")
        if (porUsuario.isNotEmpty()) {
            appendLine("Cobrado por: " + porUsuario.entries.joinToString(" · ") { (nombre, c) -> "$nombre ${Dinero.soles(c)}" })
        }
        val validos = pagos.filter { it.estado == "valido" }
        appendLine("Movimientos: ${validos.size}" + (pagos.size - validos.size).let { if (it > 0) " (+$it anulados)" else "" })
        validos.forEach { p ->
            val signo = if (p.tipo == "devolucion") "−" else ""
            appendLine("  ${Fechas.hora(p.pagadoEn)} · ${p.cliente ?: "Reserva"} · ${p.metodo} · $signo${Dinero.soles(p.monto)}" + (p.numeroComprobante?.let { " · $it" } ?: ""))
        }
    }
}
