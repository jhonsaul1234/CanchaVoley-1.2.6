@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.dominio

import kotlinx.datetime.LocalDate
import pe.canchavoley.app.datos.NegocioDto
import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.ReporteOcupacionDto
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.comoListaDeEnteros
import pe.canchavoley.app.datos.comoMapaDeEnteros
import pe.canchavoley.app.dominio.DocumentoPdf.Color
import pe.canchavoley.app.dominio.LibroExcel.Celda
import pe.canchavoley.app.dominio.LibroExcel.Estilo
import kotlin.time.Instant

/**
 * Archivos que se guardan o comparten: comprobante de la reserva y cierre de caja en PDF, y la caja
 * y los reportes en Excel. Los montos se calculan con los mismos datos que muestra la pantalla.
 */
object Documentos {
    const val MIME_PDF = "application/pdf"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val NOTA_SUNAT = "Comprobante interno del negocio; no reemplaza una boleta o factura electrónica."

    fun textoEstadoReserva(estado: String, pendientePor: String?): String = when {
        estado == "por_sincronizar" -> "Sin sincronizar (por confirmar)"
        estado == "confirmada" -> "Confirmada"
        estado == "pendiente" && pendientePor == "aprobacion" -> "Por aprobar"
        estado == "pendiente" && pendientePor == "adelanto" -> "Falta adelanto"
        estado == "pendiente" -> "Pendiente"
        estado == "cancelada" -> "Cancelada"
        estado == "rechazada" -> "Rechazada"
        else -> estado
    }

    fun textoOrigen(origen: String?): String = when (origen) {
        "llamada" -> "Llamada"; "presencial" -> "Presencial"; "whatsapp" -> "WhatsApp"; "app" -> "App del cliente"; else -> "Otro"
    }

    private fun tipoPago(t: String) = when (t) { "adelanto" -> "Adelanto"; "devolucion" -> "Devolución"; else -> "Pago" }
    private fun metodoPago(m: String) = when (m) { "yape" -> "Yape"; "plin" -> "Plin"; "efectivo" -> "Efectivo"; else -> m }
    private fun signo(p: PagoDto) = if (p.tipo == "devolucion") "−" else ""
    private fun nombreNegocio(n: String?) = n?.takeIf { it.isNotBlank() } ?: "CanchaVoley"

    /** Nombre de archivo válido en Windows y Android (sin \ / : * ? " < > |). */
    fun nombreArchivo(base: String, extension: String): String {
        val limpio = base.map { if (it in "\\/:*?\"<>|" || it.code < 32) ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().take(90).ifBlank { "CanchaVoley" }
        return "$limpio.$extension"
    }

    fun nombreComprobante(reserva: ReservaDto): String {
        val inicio = Fechas.parse(reserva.inicio)?.let { Fechas.local(it) }
        val cuando = inicio?.let { "${it.date} ${Fechas.dos(it.hour)}-${Fechas.dos(it.minute)}" } ?: ""
        return nombreArchivo("Comprobante $cuando ${reserva.clienteNombre ?: ""}", "pdf")
    }

    // ── Comprobante de la reserva (PDF, tamaño A5) ─────────────────────────────

    fun comprobantePdf(negocio: NegocioDto?, reserva: ReservaDto, pagos: List<PagoDto>, cancha: String?, emitido: Instant): ByteArray {
        val pdf = DocumentoPdf(A5_ANCHO, A5_ALTO, "Comprobante de reserva")
        val w = pdf.ancho
        val m = 28.0
        val nombre = nombreNegocio(negocio?.nombre)

        pdf.rectangulo(0.0, 0.0, w, 62.0, relleno = Color.AZUL)
        pdf.texto(m, 28.0, nombre, 15.0, negrita = true, color = Color.BLANCO)
        pdf.texto(m, 47.0, "Comprobante interno de reserva", 9.5, color = Color(0.85, 0.90, 0.97))
        var y = 80.0
        val contacto = listOfNotNull(negocio?.telefono?.takeIf { it.isNotBlank() }?.let { "Tel. ${Telefono.mostrar(it)}" },
            negocio?.direccion?.takeIf { it.isNotBlank() }).joinToString(" · ")
        if (contacto.isNotEmpty()) { y = pdf.parrafo(m, y, w - 2 * m, contacto, 8.5, color = Color.GRIS_OSCURO) }
        val e = Fechas.local(emitido)
        pdf.texto(m, y, "Emitido: ${Fechas.fechaLarga(e.date)}, ${Fechas.hora(emitido)}", 8.5, color = Color.GRIS_OSCURO)
        y += 22.0

        // Datos de la reserva (izquierda) y QR (derecha)
        pdf.texto(m, y, "Reserva", 11.0, negrita = true, color = Color.AZUL)
        pdf.linea(m, y + 5, w - m, y + 5, 0.6, Color.AZUL)
        val yQr = y + 14
        y += 22.0
        val inicio = Fechas.parse(reserva.inicio)
        val fecha = inicio?.let { Fechas.fechaLarga(Fechas.local(it).date).replaceFirstChar { c -> c.uppercase() } } ?: reserva.inicio
        val filas = listOfNotNull(
            "Cliente" to (reserva.clienteNombre ?: "—"),
            reserva.clienteTelefono?.let { "Teléfono" to Telefono.mostrar(it) },
            cancha?.let { "Cancha" to it },
            "Fecha" to fecha,
            "Horario" to "${Fechas.hora(reserva.inicio)}–${Fechas.hora(reserva.fin)} (${Fechas.duracion(reserva.duracionMin)})",
            "Estado" to textoEstadoReserva(reserva.estado, reserva.pendientePor),
            reserva.codigoQr?.let { "Código" to it },
        )
        val qr = reserva.codigoQr?.let { runCatching { CodigoQr.codificar(LecturaQr.contenido(it)) }.getOrNull() }
        val ladoQr = 104.0
        val anchoValor = (if (qr != null) w - m - ladoQr - 8 else w - m) - (m + 66)
        filas.forEach { (etiqueta, valor) ->
            pdf.texto(m, y, etiqueta, 9.0, color = Color.GRIS_OSCURO)
            y = pdf.parrafo(m + 66, y, anchoValor, valor, 9.5, negrita = true, interlineado = 12.5) + 2.5
        }
        if (qr != null) {
            pdf.qr(w - m - ladoQr, yQr, ladoQr, qr)
            pdf.textoCentrado(w - m - ladoQr / 2, yQr + ladoQr + 9, "Muéstralo al llegar", 7.5, color = Color.GRIS_OSCURO)
            y = maxOf(y, yQr + ladoQr + 16)
        }
        y += 10.0

        // Pagos
        pdf.texto(m, y, "Pagos", 11.0, negrita = true, color = Color.AZUL)
        pdf.linea(m, y + 5, w - m, y + 5, 0.6, Color.AZUL)
        y += 14.0
        val validos = pagos.filter { it.estado == "valido" }.sortedBy { it.pagadoEn ?: "" }
        val anulados = pagos.size - validos.size
        val cols = doubleArrayOf(m, m + 62, m + 150)
        fun cabecera() {
            pdf.rectangulo(m, y, w - 2 * m, 16.0, relleno = Color.GRIS_CLARO)
            pdf.texto(cols[0] + 3, y + 11, "Comprobante", 8.0, negrita = true)
            pdf.texto(cols[1] + 3, y + 11, "Fecha", 8.0, negrita = true)
            pdf.texto(cols[2] + 3, y + 11, "Detalle", 8.0, negrita = true)
            pdf.textoDerecha(w - m - 3, y + 11, "Monto", 8.0, negrita = true)
            y += 16.0
        }
        if (validos.isEmpty()) {
            pdf.texto(m, y + 8, "Sin pagos registrados.", 9.0, color = Color.GRIS_OSCURO); y += 18.0
        } else {
            cabecera()
            validos.forEach { p ->
                if (y > pdf.alto - 120) { pdf.nuevaPagina(); y = 40.0; cabecera() }
                val detalle = listOfNotNull(tipoPago(p.tipo), metodoPago(p.metodo), p.numeroOperacion?.let { "op. $it" }).joinToString(" · ")
                pdf.texto(cols[0] + 3, y + 11, p.numeroComprobante ?: "—", 8.5)
                pdf.texto(cols[1] + 3, y + 11, Fechas.fechaHora(p.pagadoEn), 8.5)
                val anchoDetalle = (w - m - 60) - (cols[2] + 3)
                pdf.texto(cols[2] + 3, y + 11, recortar(detalle, anchoDetalle, 8.5), 8.5)
                pdf.textoDerecha(w - m - 3, y + 11, signo(p) + Dinero.soles(p.monto), 8.5, negrita = true)
                pdf.linea(m, y + 16, w - m, y + 16, 0.3, Color.GRIS)
                y += 16.0
            }
        }
        if (anulados > 0) { pdf.texto(m, y + 10, "Pagos anulados (no suman): $anulados", 8.0, color = Color.GRIS_OSCURO); y += 14.0 }
        y += 10.0
        listOfNotNull(
            reserva.precioFinal?.let { "Precio" to Dinero.soles(it) },
            reserva.totalPagado?.let { "Pagado" to Dinero.soles(it) },
            reserva.saldo?.let { "Saldo" to Dinero.soles(it) },
        ).forEachIndexed { i, (k, v) ->
            val negrita = k == "Saldo"
            pdf.textoDerecha(w - m - 80, y + 10, k, 9.5, negrita = negrita, color = if (negrita) Color.NEGRO else Color.GRIS_OSCURO)
            pdf.textoDerecha(w - m - 3, y + 10, v, 10.0, negrita = true, color = if (negrita) Color.AZUL else Color.NEGRO)
            y += 15.0
        }

        val yPie = pdf.alto - 44
        pdf.linea(m, yPie, w - m, yPie, 0.4, Color.GRIS)
        pdf.parrafo(m, yPie + 12, w - 2 * m, NOTA_SUNAT, 7.5, color = Color.GRIS_OSCURO, interlineado = 9.5)
        pdf.texto(m, yPie + 31, "Generado con CanchaVoley", 7.5, color = Color.GRIS)
        return pdf.bytes()
    }

    /** Recorta con «…» para que entre en [ancho] puntos. */
    private fun recortar(texto: String, ancho: Double, tam: Double, negrita: Boolean = false): String {
        if (DocumentoPdf.anchoTexto(texto, tam, negrita) <= ancho) return texto
        var t = texto
        while (t.length > 1 && DocumentoPdf.anchoTexto("$t…", tam, negrita) > ancho) t = t.dropLast(1)
        return "$t…"
    }

    // ── Cierre de caja (PDF A4) ────────────────────────────────────────────────

    fun cierreDeCajaPdf(negocio: String?, fecha: LocalDate, totales: Map<String, Long>, total: Long, pagos: List<PagoDto>,
                        porUsuario: Map<String, Long>, emitido: Instant): ByteArray {
        val pdf = DocumentoPdf(titulo = "Cierre de caja ${fecha}")
        val w = pdf.ancho
        val m = 42.0
        pdf.rectangulo(0.0, 0.0, w, 70.0, relleno = Color.AZUL)
        pdf.texto(m, 32.0, nombreNegocio(negocio), 17.0, negrita = true, color = Color.BLANCO)
        pdf.texto(m, 53.0, "Cierre de caja del ${Fechas.fechaLarga(fecha)}", 11.0, color = Color(0.85, 0.90, 0.97))
        pdf.texto(m, 92.0, "Emitido: ${Fechas.fechaLarga(Fechas.local(emitido).date)}, ${Fechas.hora(emitido)}", 9.0, color = Color.GRIS_OSCURO)

        // Totales por método
        var x = m
        val anchoCaja = (w - 2 * m - 3 * 10) / 4
        listOf("Efectivo" to (totales["efectivo"] ?: 0L), "Yape" to (totales["yape"] ?: 0L), "Plin" to (totales["plin"] ?: 0L), "Total del día" to total)
            .forEachIndexed { i, (nombre, centimos) ->
                val esTotal = i == 3
                pdf.rectangulo(x, 108.0, anchoCaja, 52.0, relleno = if (esTotal) Color.AZUL_CLARO else Color.GRIS_CLARO)
                pdf.texto(x + 10, 126.0, nombre, 9.5, color = Color.GRIS_OSCURO)
                pdf.texto(x + 10, 148.0, Dinero.soles(centimos), 14.0, negrita = true, color = if (esTotal) Color.AZUL else Color.NEGRO)
                x += anchoCaja + 10
            }
        var y = 182.0
        if (porUsuario.isNotEmpty()) {
            pdf.texto(m, y, "Cobrado por", 11.0, negrita = true, color = Color.AZUL); y += 16.0
            porUsuario.forEach { (nombre, centimos) ->
                pdf.texto(m, y, nombre, 9.5); pdf.textoDerecha(m + 260, y, Dinero.soles(centimos), 9.5, negrita = true); y += 14.0
            }
            y += 8.0
        }
        val validos = pagos.filter { it.estado == "valido" }
        val nAnulados = pagos.size - validos.size
        val resumenMovimientos = "${validos.size} " + (if (validos.size == 1) "válido" else "válidos") +
            (if (nAnulados > 0) ", $nAnulados " + (if (nAnulados == 1) "anulado" else "anulados") else "")
        pdf.texto(m, y, "Movimientos ($resumenMovimientos)", 11.0, negrita = true, color = Color.AZUL)
        y += 10.0
        val cols = doubleArrayOf(m, m + 36, m + 156, m + 306, m + 368)
        fun cabecera() {
            pdf.rectangulo(m, y, w - 2 * m, 18.0, relleno = Color.GRIS_CLARO)
            listOf("Hora", "Cliente", "Tipo · método", "Comprobante", "Registró").forEachIndexed { k, t -> pdf.texto(cols[k] + 3, y + 12.5, t, 8.5, negrita = true) }
            pdf.textoDerecha(w - m - 3, y + 12.5, "Monto", 8.5, negrita = true)
            y += 18.0
        }
        if (pagos.isEmpty()) {
            pdf.texto(m, y + 14, "No hay pagos este día.", 9.5, color = Color.GRIS_OSCURO)
        } else {
            cabecera()
            pagos.sortedBy { it.pagadoEn ?: "" }.forEach { p ->
                if (y > pdf.alto - 70) { pdf.nuevaPagina(); y = 50.0; cabecera() }
                val anulado = p.estado == "anulado"
                val color = if (anulado) Color.GRIS else Color.NEGRO
                pdf.texto(cols[0] + 3, y + 12, Fechas.hora(p.pagadoEn), 8.5, color = color)
                pdf.texto(cols[1] + 3, y + 12, recortar(p.cliente ?: "Reserva", cols[2] - cols[1] - 6, 8.5), 8.5, color = color)
                val detalle = "${tipoPago(p.tipo)} · ${metodoPago(p.metodo)}" + (p.numeroOperacion?.let { " · op. $it" } ?: "")
                pdf.texto(cols[2] + 3, y + 12, recortar(detalle, cols[3] - cols[2] - 6, 8.5), 8.5, color = color)
                pdf.texto(cols[3] + 3, y + 12, p.numeroComprobante ?: "—", 8.5, color = color)
                pdf.texto(cols[4] + 3, y + 12, recortar(p.registradoPor ?: "", (w - m - 60) - cols[4] - 6, 8.5), 8.5, color = color)
                pdf.textoDerecha(w - m - 3, y + 12, if (anulado) "anulado" else signo(p) + Dinero.soles(p.monto), 8.5, negrita = !anulado, color = color)
                pdf.linea(m, y + 17, w - m, y + 17, 0.3, Color.GRIS)
                y += 17.0
            }
        }
        val yPie = pdf.alto - 40
        pdf.linea(m, yPie, w - m, yPie, 0.4, Color.GRIS)
        pdf.texto(m, yPie + 13, "Las devoluciones restan y los pagos anulados no suman. Generado con CanchaVoley.", 8.0, color = Color.GRIS_OSCURO)
        return pdf.bytes()
    }

    // ── Caja en Excel ─────────────────────────────────────────────────────────

    fun cajaExcel(negocio: String?, fecha: LocalDate, totales: Map<String, Long>, total: Long, pagos: List<PagoDto>, porUsuario: Map<String, Long>): ByteArray {
        val libro = LibroExcel()
        libro.hoja("Movimientos") {
            fila(Celda.Texto("${nombreNegocio(negocio)} · caja del ${Fechas.fechaLarga(fecha)}", Estilo.TITULO))
            vacia()
            cabecera("Fecha", "Hora", "Cliente", "Tipo", "Método", "Monto (S/)", "N.° de operación", "Comprobante", "Registrado por", "Estado")
            pagos.sortedBy { it.pagadoEn ?: "" }.forEach { p ->
                val centimos = (Dinero.aCentimos(p.monto) ?: 0L) * if (p.tipo == "devolucion") -1 else 1
                fila(
                    Celda.Texto(fecha.toString()), Celda.Texto(Fechas.hora(p.pagadoEn)), Celda.Texto(p.cliente ?: ""),
                    Celda.Texto(tipoPago(p.tipo)), Celda.Texto(metodoPago(p.metodo)), Celda.Numero(centimos / 100.0, Estilo.MONTO),
                    Celda.Texto(p.numeroOperacion ?: ""), Celda.Texto(p.numeroComprobante ?: ""), Celda.Texto(p.registradoPor ?: ""),
                    Celda.Texto(if (p.estado == "anulado") "Anulado (no suma)" else "Válido"),
                )
            }
            anchos(12.0, 8.0, 28.0, 12.0, 11.0, 12.0, 18.0, 14.0, 22.0, 18.0)
        }
        libro.hoja("Resumen") {
            fila(Celda.Texto("Resumen de caja del ${Fechas.fechaLarga(fecha)}", Estilo.TITULO))
            vacia()
            cabecera("Método", "Total (S/)")
            listOf("efectivo" to "Efectivo", "yape" to "Yape", "plin" to "Plin").forEach { (k, n) ->
                fila(Celda.Texto(n), Celda.Numero((totales[k] ?: 0L) / 100.0, Estilo.MONTO))
            }
            fila(Celda.Texto("Total del día", Estilo.NEGRITA), Celda.Numero(total / 100.0, Estilo.MONTO_NEGRITA))
            if (porUsuario.isNotEmpty()) {
                vacia()
                cabecera("Cobrado por", "Total (S/)")
                porUsuario.forEach { (nombre, centimos) -> fila(Celda.Texto(nombre), Celda.Numero(centimos / 100.0, Estilo.MONTO)) }
            }
            vacia()
            fila(Celda.Texto("Las devoluciones restan y los pagos anulados no suman."))
            anchos(26.0, 14.0)
        }
        return libro.bytes()
    }

    // ── Reporte en Excel ──────────────────────────────────────────────────────

    fun reporteExcel(r: ReporteOcupacionDto, negocio: String?): ByteArray {
        val libro = LibroExcel()
        fun monto(texto: String?) = Celda.Numero((Dinero.aCentimos(texto) ?: 0L) / 100.0, Estilo.MONTO)
        libro.hoja("Resumen") {
            fila(Celda.Texto("Reporte de ${nombreNegocio(negocio)}", Estilo.TITULO))
            fila(Celda.Texto("Del ${r.desde} al ${r.hasta}"))
            vacia()
            cabecera("Indicador", "Valor")
            fila(Celda.Texto("Ocupación (%)"), r.ocupacionPct?.let { Celda.Numero(it) } ?: Celda.Texto("—"))
            fila(Celda.Texto("Horas reservadas"), Celda.Numero(r.horasReservadas))
            fila(Celda.Texto("Horas de atención"), Celda.Numero(r.horasAbiertas))
            fila(Celda.Texto("Ingresos netos (S/)", Estilo.NEGRITA), monto(r.ingresosTotal).copy(estilo = Estilo.MONTO_NEGRITA))
            listOf("efectivo" to "Efectivo (S/)", "yape" to "Yape (S/)", "plin" to "Plin (S/)").forEach { (k, n) -> fila(Celda.Texto(n), monto(r.ingresos[k])) }
            fila(Celda.Texto("Demanda no atendida (rechazadas o canceladas)"), Celda.Numero(r.demandaNoAtendida.toDouble()))
            fila(Celda.Texto("Horas pico"), Celda.Texto(r.horasPico.joinToString(", ") { "${Fechas.dos(it)}:00" }.ifBlank { "—" }))
            if (r.sinHorario.isNotEmpty()) fila(Celda.Texto("Canchas sin horario configurado"), Celda.Texto(r.sinHorario.joinToString()))
            anchos(44.0, 18.0)
        }
        libro.hoja("Por día") {
            cabecera("Fecha", "Ocupación (%)", "Horas reservadas", "Horas de atención")
            r.porDia.forEach { d ->
                fila(Celda.Texto(d.fecha), d.ocupacionPct?.let { Celda.Numero(it) } ?: Celda.Texto("cerrado"),
                    Celda.Numero(d.horasReservadas), Celda.Numero(d.horasAbiertas))
            }
            anchos(14.0, 15.0, 17.0, 18.0)
        }
        val minutos = r.minutosPorHora.comoListaDeEnteros()
        libro.hoja("Por hora") {
            cabecera("Hora", "Minutos reservados", "Horas reservadas")
            minutos.withIndex().filter { it.value > 0 }.forEach { (h, mins) ->
                fila(Celda.Texto("${Fechas.dos(h)}:00"), Celda.Numero(mins.toDouble()), Celda.Numero(mins / 60.0, Estilo.MONTO))
            }
            anchos(10.0, 20.0, 18.0)
        }
        val origen = r.reservasPorOrigen.comoMapaDeEnteros()
        if (origen.isNotEmpty() || r.clientesFrecuentes.isNotEmpty()) {
            libro.hoja("Origen y clientes") {
                if (origen.isNotEmpty()) {
                    cabecera("Origen de la reserva", "Reservas")
                    origen.forEach { (k, v) -> fila(Celda.Texto(textoOrigen(k)), Celda.Numero(v.toDouble())) }
                    vacia()
                }
                if (r.clientesFrecuentes.isNotEmpty()) {
                    cabecera("Cliente frecuente", "Reservas", "Horas")
                    r.clientesFrecuentes.forEach { fila(Celda.Texto(it.cliente ?: ""), Celda.Numero(it.reservas.toDouble()), Celda.Numero(it.horas)) }
                }
                anchos(30.0, 12.0, 10.0)
            }
        }
        return libro.bytes()
    }

    const val A5_ANCHO = 419.53
    const val A5_ALTO = 595.28
}
