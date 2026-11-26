@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pe.canchavoley.app.datos.ClienteFrecuenteDto
import pe.canchavoley.app.datos.DiaOcupacionDto
import pe.canchavoley.app.datos.NegocioDto
import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.ReporteOcupacionDto
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.dominio.Documentos
import pe.canchavoley.app.plataforma.archivoLibre
import pe.canchavoley.app.plataforma.guardarDocumento
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Escribe un comprobante, un cierre de caja y dos libros de Excel de prueba (datos FICTICIOS) en
 * build/documentos-prueba, para revisarlos fuera de Gradle (lector de PDF, Excel o LibreOffice).
 */
class DocumentosArchivoTest {
    private val carpeta = File("build/documentos-prueba").apply { mkdirs() }

    @Test
    fun escribe_los_documentos_de_prueba() {
        val reserva = ReservaDto(
            uuid = "e67681a3-2dd9-4f61-a70a-0a5df85ca163", canchaId = 1, clienteNombre = "Pedro Ccama (prueba)", clienteTelefono = "51900000050",
            inicio = "2026-11-19T10:00:00-05:00", fin = "2026-11-19T12:00:00-05:00", duracionMin = 120, estado = "confirmada",
            precioFinal = "60.00", adelantoRequerido = "30.00", totalPagado = "60.00", saldo = "0.00", estadoPago = "pagado",
            codigoQr = "FV2RUYC6PHCMOSTJZUM2",
        )
        val pagos = listOf(
            PagoDto(uuid = "p1", tipo = "adelanto", metodo = "yape", monto = "30.00", numeroOperacion = "PRUEBA0001",
                pagadoEn = "2026-11-18T23:33:13-05:00", numeroComprobante = "CV-000010", registradoPor = "Recepción (demo)", cliente = "Pedro Ccama (prueba)"),
            PagoDto(uuid = "p2", tipo = "pago", metodo = "efectivo", monto = "30.00",
                pagadoEn = "2026-11-18T23:34:39-05:00", numeroComprobante = "CV-000011", registradoPor = "Recepción (demo)", cliente = "Pedro Ccama (prueba)"),
            PagoDto(uuid = "p3", tipo = "pago", metodo = "plin", monto = "15.00", numeroOperacion = "PLIN-77", estado = "anulado",
                pagadoEn = "2026-11-18T22:10:00-05:00", numeroComprobante = "CV-000009", registradoPor = "Administradora (demo)", cliente = "Ana Quispe (demo)"),
        )
        val emitido = Instant.parse("2026-11-19T04:50:00Z")
        val negocio = NegocioDto(nombre = "Cancha de Vóley DEMO", telefono = "900000000", direccion = "Dirección ficticia de demostración")
        File(carpeta, "comprobante.pdf").writeBytes(Documentos.comprobantePdf(negocio, reserva, pagos, "Cancha 1 (demo)", emitido))
        File(carpeta, "cierre-caja.pdf").writeBytes(Documentos.cierreDeCajaPdf(negocio.nombre, LocalDate(2026, 11, 18),
            mapOf("efectivo" to 3000L, "yape" to 3000L, "plin" to 0L), 6000L, pagos, mapOf("Recepción (demo)" to 6000L), emitido))
        File(carpeta, "caja.xlsx").writeBytes(Documentos.cajaExcel(negocio.nombre, LocalDate(2026, 11, 18),
            mapOf("efectivo" to 3000L, "yape" to 3000L), 6000L, pagos, mapOf("Recepción (demo)" to 6000L)))
        val r = ReporteOcupacionDto(
            desde = "2026-11-10", hasta = "2026-11-16", horasAbiertas = 98.0, horasReservadas = 30.5, ocupacionPct = 31.12,
            porDia = listOf(DiaOcupacionDto("2026-11-10", 14.0, 6.0, 42.86), DiaOcupacionDto("2026-11-11", 0.0, 0.0, null)),
            minutosPorHora = JsonArray((0..23).map { JsonPrimitive(if (it == 18) 180 else if (it == 19) 90 else 0) }), horasPico = listOf(18, 19),
            reservasPorOrigen = buildJsonObject { put("llamada", 3); put("whatsapp", 2) }, demandaNoAtendida = 2,
            ingresos = mapOf("efectivo" to "100.00", "yape" to "1150.5", "plin" to "0.00"), ingresosTotal = "1250.50",
            clientesFrecuentes = listOf(ClienteFrecuenteDto("Ana Pérez (demo)", 4, 6.0)),
        )
        File(carpeta, "reporte.xlsx").writeBytes(Documentos.reporteExcel(r, negocio.nombre))
        listOf("comprobante.pdf", "cierre-caja.pdf", "caja.xlsx", "reporte.xlsx").forEach { assertTrue(File(carpeta, it).length() > 500, it) }
    }

    @Test
    fun guardar_no_pisa_archivos_anteriores() {
        val destino = File(carpeta, "guardar").apply { deleteRecursively(); mkdirs() }
        val a = guardarDocumento("Caja 2026-11-18.xlsx", byteArrayOf(1), destino)
        val b = guardarDocumento("Caja 2026-11-18.xlsx", byteArrayOf(2), destino)
        assertEquals("Caja 2026-11-18.xlsx", a.name)
        assertEquals("Caja 2026-11-18 (2).xlsx", b.name)
        assertEquals("Caja 2026-11-18 (3).xlsx", archivoLibre(destino, "Caja 2026-11-18.xlsx").name)
    }
}
