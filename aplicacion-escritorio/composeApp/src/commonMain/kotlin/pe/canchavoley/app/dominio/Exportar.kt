package pe.canchavoley.app.dominio

import kotlinx.datetime.LocalDate
import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.ReporteOcupacionDto
import pe.canchavoley.app.datos.comoListaDeEnteros
import pe.canchavoley.app.datos.comoMapaDeEnteros

/**
 * Tablas para pegar en Excel o en Google Sheets: columnas separadas por tabulaciones y filas por
 * saltos de línea (es lo que Excel reconoce al pegar con Ctrl+V). Los montos van sin «S/» y con
 * punto decimal, para que se puedan sumar.
 */
object Exportar {
    /** Une las filas; en cada celda se quitan tabulaciones y saltos de línea para no romper la tabla. */
    fun tabla(filas: List<List<String>>): String =
        filas.joinToString("\n") { fila -> fila.joinToString("\t") { it.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim() } }

    private fun numero(valor: Double): String =
        if (valor == kotlin.math.floor(valor)) valor.toLong().toString() else ((kotlin.math.round(valor * 10) / 10.0)).toString()

    private fun monto(texto: String?): String = Dinero.aCentimos(texto)?.let { Dinero.aTexto(it) } ?: "0.00"

    /** Reporte de ocupación e ingresos en bloques: resumen, por día, por hora, origen y clientes. */
    fun reporte(r: ReporteOcupacionDto, negocio: String?): String {
        val filas = mutableListOf<List<String>>()
        filas += listOf("Reporte de ${negocio?.takeIf { it.isNotBlank() } ?: "CanchaVoley"}", "Del ${r.desde} al ${r.hasta}")
        filas += emptyList<String>()
        filas += listOf("Indicador", "Valor")
        filas += listOf("Ocupación (%)", r.ocupacionPct?.let { numero(it) } ?: "")
        filas += listOf("Horas reservadas", numero(r.horasReservadas))
        filas += listOf("Horas de atención", numero(r.horasAbiertas))
        filas += listOf("Ingresos netos (S/)", monto(r.ingresosTotal))
        listOf("efectivo" to "Efectivo (S/)", "yape" to "Yape (S/)", "plin" to "Plin (S/)").forEach { (k, n) -> filas += listOf(n, monto(r.ingresos[k])) }
        filas += listOf("Demanda no atendida (rechazadas o canceladas)", r.demandaNoAtendida.toString())
        filas += listOf("Horas pico", r.horasPico.joinToString(", ") { "${Fechas.dos(it)}:00" })
        filas += emptyList<String>()
        filas += listOf("Fecha", "Ocupación (%)", "Horas reservadas", "Horas de atención")
        r.porDia.forEach { d -> filas += listOf(d.fecha, d.ocupacionPct?.let { numero(it) } ?: "cerrado", numero(d.horasReservadas), numero(d.horasAbiertas)) }
        val minutos = r.minutosPorHora.comoListaDeEnteros()
        if (minutos.any { it > 0 }) {
            filas += emptyList<String>()
            filas += listOf("Hora", "Minutos reservados")
            minutos.withIndex().filter { it.value > 0 }.forEach { (h, m) -> filas += listOf("${Fechas.dos(h)}:00", m.toString()) }
        }
        val origen = r.reservasPorOrigen.comoMapaDeEnteros()
        if (origen.isNotEmpty()) {
            filas += emptyList<String>()
            filas += listOf("Origen de la reserva", "Reservas")
            origen.forEach { (k, v) -> filas += listOf(k, v.toString()) }
        }
        if (r.clientesFrecuentes.isNotEmpty()) {
            filas += emptyList<String>()
            filas += listOf("Cliente frecuente", "Reservas", "Horas")
            r.clientesFrecuentes.forEach { filas += listOf(it.cliente ?: "", it.reservas.toString(), numero(it.horas)) }
        }
        return tabla(filas)
    }

    /** Movimientos de caja de un día; las devoluciones en negativo y los anulados marcados (no suman). */
    fun movimientosDeCaja(fecha: LocalDate, pagos: List<PagoDto>): String {
        val filas = mutableListOf(listOf("Fecha", "Hora", "Cliente", "Tipo", "Método", "Monto (S/)", "N.° de operación", "Comprobante", "Registrado por", "Estado"))
        pagos.forEach { p ->
            val centimos = (Dinero.aCentimos(p.monto) ?: 0) * if (p.tipo == "devolucion") -1 else 1
            filas += listOf(
                fecha.toString(), Fechas.hora(p.pagadoEn), p.cliente ?: "", p.tipo, p.metodo, Dinero.aTexto(centimos),
                p.numeroOperacion ?: "", p.numeroComprobante ?: "", p.registradoPor ?: "", if (p.estado == "anulado") "anulado" else "válido",
            )
        }
        return tabla(filas)
    }
}
