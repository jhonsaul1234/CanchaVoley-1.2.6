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
import pe.canchavoley.app.dominio.DisponibilidadLocal
import pe.canchavoley.app.dominio.DocumentoPdf
import pe.canchavoley.app.dominio.Documentos
import pe.canchavoley.app.dominio.Intervalo
import pe.canchavoley.app.dominio.LibroExcel
import pe.canchavoley.app.dominio.Zip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Comprobantes en PDF y archivos de Excel generados en Kotlin puro, y el aviso inmediato de una hora
 * escrita a mano. Todos los datos son FICTICIOS.
 */
class DocumentosTest {

    // ── Utilidades de prueba ────────────────────────────────────────────────

    private fun latin1(b: ByteArray) = buildString(b.size) { b.forEach { append((it.toInt() and 0xFF).toChar()) } }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i) or (u16(b, i + 2) shl 16)

    /** Lee un ZIP sin compresión, comprobando el CRC de cada archivo. */
    private fun leerZip(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val archivos = LinkedHashMap<String, ByteArray>()
        var i = 0
        while (i + 30 <= zip.size && u32(zip, i) == 0x04034b50) {
            assertEquals(0, u16(zip, i + 8), "método: sin compresión")
            val crc = u32(zip, i + 14)
            val tam = u32(zip, i + 18)
            val largoNombre = u16(zip, i + 26)
            val extra = u16(zip, i + 28)
            val nombre = zip.copyOfRange(i + 30, i + 30 + largoNombre).decodeToString()
            val inicio = i + 30 + largoNombre + extra
            val datos = zip.copyOfRange(inicio, inicio + tam)
            assertEquals(crc, Zip.crc32(datos), "CRC de $nombre")
            archivos[nombre] = datos
            i = inicio + tam
        }
        assertEquals(0x02014b50, u32(zip, i), "directorio central después de los archivos")
        return archivos
    }

    private val reserva = ReservaDto(
        uuid = "e67681a3-2dd9-4f61-a70a-0a5df85ca163", canchaId = 1, clienteNombre = "Pedro Ccama (prueba)", clienteTelefono = "51900000050",
        inicio = "2026-11-19T10:00:00-05:00", fin = "2026-11-19T12:00:00-05:00", duracionMin = 120, estado = "confirmada",
        precioFinal = "60.00", adelantoRequerido = "30.00", totalPagado = "60.00", saldo = "0.00", estadoPago = "pagado",
        codigoQr = "FV2RUYC6PHCMOSTJZUM2",
    )
    private val pagos = listOf(
        PagoDto(uuid = "p1", tipo = "adelanto", metodo = "yape", monto = "30.00", numeroOperacion = "PRUEBA0001",
            pagadoEn = "2026-11-18T23:33:13-05:00", numeroComprobante = "CV-000010", registradoPor = "Recepción (demo)", cliente = "Pedro Ccama (prueba)"),
        PagoDto(uuid = "p2", tipo = "pago", metodo = "efectivo", monto = "30.00",
            pagadoEn = "2026-11-18T23:34:39-05:00", numeroComprobante = "CV-000011", registradoPor = "Recepción (demo)", cliente = "Pedro Ccama (prueba)"),
    )
    private val emitido = Instant.parse("2026-11-19T04:50:00Z")

    // ── PDF ─────────────────────────────────────────────────────────────────

    @Test
    fun pdf_bien_formado_con_tabla_de_referencias_correcta() {
        val pdf = DocumentoPdf(titulo = "Prueba")
        pdf.texto(40.0, 40.0, "Reserva de Pedro «prueba» — S/ 60.00 · ¿señal? ñ")
        pdf.linea(40.0, 50.0, 200.0, 50.0)
        pdf.nuevaPagina()
        pdf.textoDerecha(500.0, 40.0, "Página 2", 12.0, negrita = true)
        val s = latin1(pdf.bytes())
        assertTrue(s.startsWith("%PDF-1.4\n"))
        assertTrue(s.endsWith("%%EOF\n"))
        assertTrue(s.contains("/Count 2"))
        val inicioXref = Regex("startxref\\s+(\\d+)").find(s)!!.groupValues[1].toInt()
        assertTrue(s.substring(inicioXref).startsWith("xref\n"))
        val desplazamientos = Regex("(\\d{10}) 00000 n ").findAll(s.substring(inicioXref)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(9, desplazamientos.size)              // catálogo, páginas, 2 fuentes, 2 x (página + contenido), información
        desplazamientos.forEachIndexed { i, d -> assertTrue(s.substring(d).startsWith("${i + 1} 0 obj"), "objeto ${i + 1}") }
        // El largo declarado de cada contenido coincide con el contenido real
        Regex("<< /Length (\\d+) >>\nstream\n").findAll(s).forEach { m ->
            val largo = m.groupValues[1].toInt()
            assertTrue(s.substring(m.range.last + 1 + largo).startsWith("endstream"))
        }
    }

    @Test
    fun texto_en_winansi_y_ancho_de_helvetica() {
        val b = DocumentoPdf.winAnsi("ñÑáé«»—·−")
        assertEquals(listOf(0xF1, 0xD1, 0xE1, 0xE9, 0xAB, 0xBB, 0x97, 0xB7, '-'.code), b.map { it.toInt() and 0xFF })
        assertEquals(20.56, DocumentoPdf.anchoTexto("Hola", 10.0), 0.001)                  // 722 + 556 + 222 + 556
        assertEquals(DocumentoPdf.anchoTexto("Peru", 10.0), DocumentoPdf.anchoTexto("Perú", 10.0), 0.001)
        assertEquals(listOf("uno dos", "tres"), DocumentoPdf.partirEnLineas("uno dos tres", DocumentoPdf.anchoTexto("uno dos", 10.0) + 1, 10.0))
        assertEquals("1.5", DocumentoPdf.n(1.5)); assertEquals("-2", DocumentoPdf.n(-2.0)); assertEquals("0.05", DocumentoPdf.n(0.049))
    }

    @Test
    fun comprobante_en_pdf_con_pagos_y_codigo() {
        val bytes = Documentos.comprobantePdf(NegocioDto(nombre = "Cancha de Vóley DEMO", telefono = "900000000"), reserva, pagos, "Cancha 1 (demo)", emitido)
        val s = latin1(bytes)
        assertTrue(s.startsWith("%PDF-1.4"))
        // El texto va en hexadecimal WinAnsi: se buscan los números de comprobante y el código de la reserva
        fun hex(t: String) = DocumentoPdf.winAnsi(t).joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
        listOf("CV-000010", "CV-000011", "FV2RUYC6PHCMOSTJZUM2", "Pedro Ccama (prueba)", "S/ 0.00").forEach { assertTrue(s.contains(hex(it)), it) }
        assertEquals("Comprobante 2026-11-19 10-00 Pedro Ccama (prueba).pdf", Documentos.nombreComprobante(reserva))
    }

    @Test
    fun cierre_de_caja_largo_pasa_a_otra_pagina() {
        val muchos = (1..60).map { i -> pagos[1].copy(uuid = "m$i", numeroComprobante = "CV-${i.toString().padStart(6, '0')}") }
        val s = latin1(Documentos.cierreDeCajaPdf("Cancha de Vóley DEMO", LocalDate(2026, 11, 18), mapOf("efectivo" to 180000L), 180000L, muchos, emptyMap(), emitido))
        assertTrue(Regex("/Count (\\d+)").find(s)!!.groupValues[1].toInt() >= 2)
    }

    // ── Excel ───────────────────────────────────────────────────────────────

    @Test
    fun crc32_y_columnas_de_excel() {
        assertEquals(0xCBF43926.toInt(), Zip.crc32("123456789".encodeToByteArray()))      // valor de control del estándar
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA", "ZZ", "AAA"), listOf(0, 25, 26, 51, 52, 701, 702).map { LibroExcel.columna(it) })
    }

    @Test
    fun libro_de_excel_valido_con_texto_escapado_y_montos() {
        val libro = LibroExcel()
        libro.hoja("Movimientos") {
            cabecera("Cliente", "Monto (S/)")
            fila(LibroExcel.Celda.Texto("Pedro & Cía <prueba>"), LibroExcel.Celda.Numero(60.0, LibroExcel.Estilo.MONTO))
            fila(LibroExcel.Celda.Texto("Ana"), LibroExcel.Celda.Numero(30.5, LibroExcel.Estilo.MONTO))
        }
        libro.hoja("Movimientos") { }                                                         // nombre repetido: se renombra
        val archivos = leerZip(libro.bytes())
        assertEquals(listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"), archivos.keys.toList())
        val hoja = archivos.getValue("xl/worksheets/sheet1.xml").decodeToString()
        assertTrue(hoja.contains("Pedro &amp; Cía &lt;prueba&gt;"))
        assertTrue(hoja.contains("<c r=\"B2\" s=\"2\"><v>60</v></c>"))
        assertTrue(hoja.contains("<c r=\"B3\" s=\"2\"><v>30.5</v></c>"))
        val libroXml = archivos.getValue("xl/workbook.xml").decodeToString()
        assertTrue(libroXml.contains("name=\"Movimientos\"") && libroXml.contains("name=\"Movimientos (2)\""))
    }

    @Test
    fun caja_y_reporte_en_excel() {
        val caja = leerZip(Documentos.cajaExcel("Cancha DEMO", LocalDate(2026, 11, 18), mapOf("yape" to 3000L, "efectivo" to 3000L), 6000L, pagos, mapOf("Recepción (demo)" to 6000L)))
        val movimientos = caja.getValue("xl/worksheets/sheet1.xml").decodeToString()
        assertTrue(movimientos.contains("PRUEBA0001") && movimientos.contains("CV-000011"))
        assertTrue(caja.getValue("xl/worksheets/sheet2.xml").decodeToString().contains("<v>60</v>"))   // total del día
        val r = ReporteOcupacionDto(
            desde = "2026-11-10", hasta = "2026-11-16", horasAbiertas = 98.0, horasReservadas = 30.5, ocupacionPct = 31.12,
            porDia = listOf(DiaOcupacionDto("2026-11-10", 14.0, 6.0, 42.86), DiaOcupacionDto("2026-11-11", 0.0, 0.0, null)),
            minutosPorHora = JsonArray((0..23).map { JsonPrimitive(if (it == 18) 180 else 0) }), horasPico = listOf(18),
            reservasPorOrigen = buildJsonObject { put("llamada", 3) }, ingresos = mapOf("efectivo" to "100.00"), ingresosTotal = "100.00",
            clientesFrecuentes = listOf(ClienteFrecuenteDto("Ana Pérez", 4, 6.0)),
        )
        val reporte = leerZip(Documentos.reporteExcel(r, "Cancha DEMO"))
        assertEquals(4, reporte.keys.count { it.startsWith("xl/worksheets/") })
        assertTrue(reporte.getValue("xl/worksheets/sheet2.xml").decodeToString().contains("cerrado"))
    }

    // ── Aviso de una hora escrita a mano (mejora CAM-01) ─────────────────────

    @Test
    fun aviso_inmediato_para_una_hora_escrita_que_no_esta_libre() {
        fun t(h: Int, m: Int = 0) = Instant.parse("2026-11-19T${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:00-05:00")
        val turnos = listOf(Intervalo(t(8), t(23)))
        val ocupados = listOf(Intervalo(t(18), t(20)))
        val libres = listOf(Intervalo(t(8), t(18)), Intervalo(t(20), t(23)))
        assertNull(DisponibilidadLocal.avisoHoraEscrita(turnos, ocupados, libres, t(10), t(12)))
        assertNotNull(DisponibilidadLocal.avisoHoraEscrita(turnos, ocupados, libres, t(19), t(20)))!!.let { assertTrue(it.contains("cruza")) }
        assertNotNull(DisponibilidadLocal.avisoHoraEscrita(turnos, ocupados, libres, t(7), t(8)))!!.let { assertTrue(it.contains("fuera del horario")) }
        assertNull(DisponibilidadLocal.avisoHoraEscrita(emptyList(), emptyList(), emptyList(), t(7), t(8)))   // sin horario: decide el servidor
    }
}
