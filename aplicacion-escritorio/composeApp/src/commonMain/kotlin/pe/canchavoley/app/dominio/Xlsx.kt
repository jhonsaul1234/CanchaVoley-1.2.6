package pe.canchavoley.app.dominio

/**
 * Libro de Excel (.xlsx) mínimo escrito en Kotlin puro: hojas con texto y números, cabeceras en
 * negrita, montos con dos decimales y anchos de columna. Un .xlsx es un ZIP con archivos XML
 * (Office Open XML); aquí se guardan sin comprimir, lo que Excel, LibreOffice y Google Sheets aceptan.
 */
class LibroExcel {
    sealed class Celda {
        data class Texto(val valor: String, val estilo: Estilo = Estilo.NORMAL) : Celda()
        data class Numero(val valor: Double, val estilo: Estilo = Estilo.NORMAL) : Celda()
        object Vacia : Celda()
    }

    /** Índices de cellXfs en styles.xml. */
    enum class Estilo(val id: Int) { NORMAL(0), CABECERA(1), MONTO(2), TITULO(3), NEGRITA(4), MONTO_NEGRITA(5) }

    class Hoja(val nombre: String) {
        val filas = mutableListOf<List<Celda>>()
        val anchos = mutableMapOf<Int, Double>()

        fun fila(vararg celdas: Celda) { filas.add(celdas.toList()) }
        fun fila(celdas: List<Celda>) { filas.add(celdas) }
        fun vacia() { filas.add(emptyList()) }
        fun cabecera(vararg titulos: String) { filas.add(titulos.map { Celda.Texto(it, Estilo.CABECERA) }) }
        fun anchos(vararg valores: Double) { valores.forEachIndexed { i, v -> anchos[i] = v } }
    }

    private val hojas = mutableListOf<Hoja>()

    fun hoja(nombre: String, llenar: Hoja.() -> Unit): Hoja {
        val usado = hojas.map { it.nombre.lowercase() }.toSet()
        var limpio = nombre.filterNot { it in "[]:*?/\\" }.take(31).ifBlank { "Hoja${hojas.size + 1}" }
        var k = 2
        while (limpio.lowercase() in usado) limpio = limpio.take(28) + " ($k)".also { k++ }
        return Hoja(limpio).apply(llenar).also { hojas += it }
    }

    fun bytes(): ByteArray {
        if (hojas.isEmpty()) hoja("Hoja1") {}
        val archivos = mutableListOf<Pair<String, ByteArray>>()
        fun xml(nombre: String, contenido: String) { archivos += nombre to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n$contenido").encodeToByteArray() }

        xml("[Content_Types].xml", buildString {
            append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            hojas.indices.forEach { append("<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>") }
            append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            append("</Types>")
        })
        xml("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>")
        xml("xl/workbook.xml", buildString {
            append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
            hojas.forEachIndexed { i, h -> append("<sheet name=\"${escapar(h.nombre)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>") }
            append("</sheets></workbook>")
        })
        xml("xl/_rels/workbook.xml.rels", buildString {
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            hojas.indices.forEach { append("<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>") }
            append("<Relationship Id=\"rId${hojas.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            append("</Relationships>")
        })
        xml("xl/styles.xml", ESTILOS)
        hojas.forEachIndexed { i, h -> xml("xl/worksheets/sheet${i + 1}.xml", hojaXml(h)) }
        return Zip.sinComprimir(archivos)
    }

    private fun hojaXml(h: Hoja): String = buildString {
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (h.anchos.isNotEmpty()) {
            append("<cols>")
            h.anchos.entries.sortedBy { it.key }.forEach { (col, ancho) -> append("<col min=\"${col + 1}\" max=\"${col + 1}\" width=\"${DocumentoPdf.n(ancho)}\" customWidth=\"1\"/>") }
            append("</cols>")
        }
        append("<sheetData>")
        h.filas.forEachIndexed { f, fila ->
            append("<row r=\"${f + 1}\">")
            fila.forEachIndexed { c, celda ->
                val ref = columna(c) + (f + 1)
                when (celda) {
                    is Celda.Texto -> if (celda.valor.isNotEmpty()) append("<c r=\"$ref\" t=\"inlineStr\" s=\"${celda.estilo.id}\"><is><t xml:space=\"preserve\">${escapar(celda.valor)}</t></is></c>")
                        else if (celda.estilo != Estilo.NORMAL) append("<c r=\"$ref\" s=\"${celda.estilo.id}\"/>")
                    is Celda.Numero -> append("<c r=\"$ref\" s=\"${celda.estilo.id}\"><v>${numero(celda.valor)}</v></c>")
                    Celda.Vacia -> Unit
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    companion object {
        /** A, B, …, Z, AA, AB… */
        fun columna(indice: Int): String {
            var n = indice + 1
            val sb = StringBuilder()
            while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
            return sb.toString()
        }

        fun escapar(s: String): String = buildString(s.length) {
            s.forEach { ch ->
                when {
                    ch == '&' -> append("&amp;"); ch == '<' -> append("&lt;"); ch == '>' -> append("&gt;")
                    ch == '"' -> append("&quot;"); ch == '\'' -> append("&apos;")
                    ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> append(' ')   // no permitidos en XML 1.0
                    else -> append(ch)
                }
            }
        }

        /** Número para el XML: punto decimal, sin notación científica para montos comunes. */
        fun numero(v: Double): String {
            if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
            val centesimas = kotlin.math.round(v * 100).toLong()
            return if (kotlin.math.abs(v * 100 - centesimas) < 1e-6) DocumentoPdf.n(centesimas / 100.0) else v.toString()
        }

        private const val ESTILOS = "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/></numFmts>" +
            "<fonts count=\"4\">" +
            "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"14\"/><color rgb=\"FF1D4E89\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "</fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1D4E89\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"6\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"164\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyNumberFormat=\"1\"/>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
    }
}

/** ZIP sin compresión (método «stored»), suficiente para .xlsx. */
object Zip {
    private val TABLA_CRC = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            t[n] = c
        }
    }

    fun crc32(datos: ByteArray): Int {
        var c = -1
        datos.forEach { b -> c = TABLA_CRC[(c xor b.toInt()) and 0xFF] xor (c ushr 8) }
        return c.inv()
    }

    fun sinComprimir(archivos: List<Pair<String, ByteArray>>): ByteArray {
        val salida = mutableListOf<Byte>()
        fun u16(v: Int) { salida += (v and 0xFF).toByte(); salida += ((v ushr 8) and 0xFF).toByte() }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
        fun bytes(b: ByteArray) { b.forEach { salida += it } }
        // Fecha fija (1 de enero de 2026, 00:00) en formato MS-DOS: el contenido no depende del reloj.
        val hora = 0
        val fecha = ((2026 - 1980) shl 9) or (1 shl 5) or 1
        val centrales = mutableListOf<Triple<ByteArray, Int, Pair<Int, Int>>>()   // nombre, desplazamiento, (crc, tamaño)
        archivos.forEach { (nombre, datos) ->
            val n = nombre.encodeToByteArray()
            val crc = crc32(datos)
            val desplazamiento = salida.size
            u32(0x04034b50); u16(20); u16(0x0800); u16(0); u16(hora); u16(fecha)
            u32(crc); u32(datos.size); u32(datos.size); u16(n.size); u16(0)
            bytes(n); bytes(datos)
            centrales += Triple(n, desplazamiento, crc to datos.size)
        }
        val inicioCentral = salida.size
        centrales.forEach { (n, desplazamiento, crcTam) ->
            u32(0x02014b50); u16(20); u16(20); u16(0x0800); u16(0); u16(hora); u16(fecha)
            u32(crcTam.first); u32(crcTam.second); u32(crcTam.second); u16(n.size); u16(0); u16(0); u16(0); u16(0); u32(0)
            u32(desplazamiento); bytes(n)
        }
        val tamCentral = salida.size - inicioCentral
        u32(0x06054b50); u16(0); u16(0); u16(centrales.size); u16(centrales.size); u32(tamCentral); u32(inicioCentral); u16(0)
        return salida.toByteArray()
    }
}
