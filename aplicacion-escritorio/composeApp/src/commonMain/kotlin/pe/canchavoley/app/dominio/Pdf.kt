package pe.canchavoley.app.dominio

/**
 * PDF mínimo (versión 1.4) escrito en Kotlin puro, para comprobantes y cierres de caja.
 * Usa las fuentes estándar Helvetica y Helvetica-Bold (todo lector de PDF las trae, no se incrustan)
 * con la codificación WinAnsi, que incluye las letras del castellano (á, é, ñ, ¿, «»…).
 * Las coordenadas se dan desde la esquina SUPERIOR izquierda, en puntos (1 cm ≈ 28.35 pt).
 */
class DocumentoPdf(val ancho: Double = A4_ANCHO, val alto: Double = A4_ALTO, private val titulo: String = "") {
    private val paginas = mutableListOf<StringBuilder>()
    private var actual = StringBuilder()

    init { paginas += actual }

    fun nuevaPagina() { actual = StringBuilder(); paginas += actual }

    val numeroDePaginas: Int get() = paginas.size

    fun texto(x: Double, y: Double, texto: String, tam: Double = 10.0, negrita: Boolean = false, color: Color = Color.NEGRO) {
        if (texto.isEmpty()) return
        actual.append("BT ").append(color.relleno()).append(" /").append(if (negrita) "F2" else "F1").append(' ').append(n(tam)).append(" Tf ")
            .append(n(x)).append(' ').append(n(alto - y)).append(" Td <").append(hex(winAnsi(texto))).append("> Tj ET\n")
    }

    /** Texto alineado a la derecha: termina en [xDerecha]. */
    fun textoDerecha(xDerecha: Double, y: Double, texto: String, tam: Double = 10.0, negrita: Boolean = false, color: Color = Color.NEGRO) =
        this.texto(xDerecha - anchoTexto(texto, tam, negrita), y, texto, tam, negrita, color)

    fun textoCentrado(xCentro: Double, y: Double, texto: String, tam: Double = 10.0, negrita: Boolean = false, color: Color = Color.NEGRO) =
        this.texto(xCentro - anchoTexto(texto, tam, negrita) / 2, y, texto, tam, negrita, color)

    /**
     * Escribe [texto] en varias líneas que no pasan de [anchoMax]; devuelve la [y] siguiente a la última línea.
     */
    fun parrafo(x: Double, y: Double, anchoMax: Double, texto: String, tam: Double = 10.0, negrita: Boolean = false,
                color: Color = Color.NEGRO, interlineado: Double = tam * 1.35): Double {
        var yy = y
        partirEnLineas(texto, anchoMax, tam, negrita).forEach { linea -> this.texto(x, yy, linea, tam, negrita, color); yy += interlineado }
        return yy
    }

    fun linea(x1: Double, y1: Double, x2: Double, y2: Double, grosor: Double = 0.5, color: Color = Color.GRIS) {
        actual.append(color.trazo()).append(' ').append(n(grosor)).append(" w ")
            .append(n(x1)).append(' ').append(n(alto - y1)).append(" m ").append(n(x2)).append(' ').append(n(alto - y2)).append(" l S\n")
    }

    /** Rectángulo con su esquina superior izquierda en (x, y). */
    fun rectangulo(x: Double, y: Double, w: Double, h: Double, relleno: Color? = null, borde: Color? = null, grosor: Double = 0.5) {
        val op = when {
            relleno != null && borde != null -> "B"
            relleno != null -> "f"
            borde != null -> "S"
            else -> return
        }
        relleno?.let { actual.append(it.relleno()).append(' ') }
        borde?.let { actual.append(it.trazo()).append(' ').append(n(grosor)).append(" w ") }
        actual.append(n(x)).append(' ').append(n(alto - y - h)).append(' ').append(n(w)).append(' ').append(n(h)).append(" re ").append(op).append('\n')
    }

    /** Dibuja un código QR (módulos negros) en un cuadrado de [lado] puntos con su esquina superior izquierda en (x, y). */
    fun qr(x: Double, y: Double, lado: Double, qr: CodigoQr) {
        val tam = qr.tamano + 8                                // 4 módulos de margen blanco a cada lado
        val m = lado / tam
        rectangulo(x, y, lado, lado, relleno = Color.BLANCO)
        actual.append(Color.NEGRO.relleno()).append('\n')
        for (f in 0 until qr.tamano) for (c in 0 until qr.tamano) if (qr.oscuro(f, c)) {
            // un poco más grande para que no queden líneas finas entre módulos al ampliar
            actual.append(n(x + (c + 4) * m)).append(' ').append(n(alto - y - (f + 5) * m)).append(' ')
                .append(n(m + 0.05)).append(' ').append(n(m + 0.05)).append(" re\n")
        }
        actual.append("f\n")
    }

    fun bytes(): ByteArray {
        val salida = Salida()
        salida.ascii("%PDF-1.4\n")
        salida.bytes(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
        val desplazamientos = mutableListOf<Int>()
        fun objeto(cuerpo: String) { desplazamientos += salida.tamano; salida.ascii("${desplazamientos.size} 0 obj\n$cuerpo\nendobj\n") }

        val nPaginas = paginas.size
        // 1 catálogo, 2 páginas, 3 y 4 fuentes, luego (página, contenido) por cada página, al final la información
        val idsPagina = (0 until nPaginas).map { 5 + it * 2 }
        objeto("<< /Type /Catalog /Pages 2 0 R >>")
        objeto("<< /Type /Pages /Kids [${idsPagina.joinToString(" ") { "$it 0 R" }}] /Count $nPaginas >>")
        objeto("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        objeto("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        paginas.forEachIndexed { i, contenido ->
            val idContenido = idsPagina[i] + 1
            objeto("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(ancho)} ${n(alto)}] " +
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents $idContenido 0 R >>")
            val flujo = contenido.toString()
            objeto("<< /Length ${flujo.length} >>\nstream\n${flujo}endstream")
        }
        val idInfo = desplazamientos.size + 1
        objeto("<< /Title <FEFF${hexUtf16(titulo)}> /Producer (CanchaVoley) >>")

        val inicioXref = salida.tamano
        val xref = StringBuilder("xref\n0 ${desplazamientos.size + 1}\n0000000000 65535 f \n")
        desplazamientos.forEach { xref.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        salida.ascii(xref.toString())
        salida.ascii("trailer\n<< /Size ${desplazamientos.size + 1} /Root 1 0 R /Info $idInfo 0 R >>\nstartxref\n$inicioXref\n%%EOF\n")
        return salida.resultado()
    }

    data class Color(val r: Double, val g: Double, val b: Double) {
        fun relleno() = "${n(r)} ${n(g)} ${n(b)} rg"
        fun trazo() = "${n(r)} ${n(g)} ${n(b)} RG"

        companion object {
            val NEGRO = Color(0.0, 0.0, 0.0)
            val BLANCO = Color(1.0, 1.0, 1.0)
            val GRIS = Color(0.62, 0.65, 0.70)
            val GRIS_OSCURO = Color(0.33, 0.35, 0.39)
            val GRIS_CLARO = Color(0.95, 0.96, 0.97)
            val AZUL = Color(0x1D / 255.0, 0x4E / 255.0, 0x89 / 255.0)
            val AZUL_CLARO = Color(0.91, 0.94, 0.97)
            val VERDE = Color(0.10, 0.50, 0.25)
            val ROJO = Color(0.75, 0.15, 0.15)
        }
    }

    private class Salida {
        private val partes = mutableListOf<ByteArray>()
        var tamano = 0; private set
        fun bytes(b: ByteArray) { partes += b; tamano += b.size }
        fun ascii(s: String) = bytes(s.encodeToByteArray())       // todo lo que se escribe así es ASCII
        fun resultado(): ByteArray {
            val r = ByteArray(tamano); var i = 0
            partes.forEach { it.copyInto(r, i); i += it.size }
            return r
        }
    }

    companion object {
        const val A4_ANCHO = 595.28
        const val A4_ALTO = 841.89
        const val CM = 28.3465

        /** Número con hasta 2 decimales y punto decimal (como lo exige el formato PDF). */
        fun n(v: Double): String {
            val centesimas = kotlin.math.round(v * 100).toLong()
            val signo = if (centesimas < 0) "-" else ""
            val a = kotlin.math.abs(centesimas)
            val entero = a / 100
            val dec = a % 100
            return when {
                dec == 0L -> "$signo$entero"
                dec % 10 == 0L -> "$signo$entero.${dec / 10}"
                else -> "$signo$entero.${dec.toString().padStart(2, '0')}"
            }
        }

        private const val HEX = "0123456789ABCDEF"
        private fun hex(b: ByteArray): String = buildString(b.size * 2) { b.forEach { val v = it.toInt() and 0xFF; append(HEX[v shr 4]); append(HEX[v and 15]) } }
        private fun hexUtf16(s: String): String = buildString { s.forEach { val c = it.code; append(HEX[(c shr 12) and 15]); append(HEX[(c shr 8) and 15]); append(HEX[(c shr 4) and 15]); append(HEX[c and 15]) } }

        /** Convierte el texto a WinAnsi (Windows-1252); lo que no existe ahí se reemplaza por un equivalente o «?». */
        fun winAnsi(texto: String): ByteArray {
            val salida = ByteArray(texto.length)
            texto.forEachIndexed { i, ch ->
                val c = ch.code
                salida[i] = when {
                    c in 32..126 || c in 160..255 -> c
                    else -> ESPECIALES[ch] ?: when (ch) {
                        '−' -> '-'.code; ' ', ' ', ' ' -> 32; '✓', '✔' -> 'x'.code
                        '\t' -> 32; else -> '?'.code
                    }
                }.toByte()
            }
            return salida
        }

        private val ESPECIALES = mapOf(
            '€' to 0x80, '‚' to 0x82, '„' to 0x84, '…' to 0x85, '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94,
            '•' to 0x95, '–' to 0x96, '—' to 0x97, '™' to 0x99,
        )

        // Anchos de Helvetica y Helvetica-Bold (unidades de 1/1000 del tamaño), caracteres 32..126 (métricas AFM estándar).
        private val ANCHO_NORMAL = intArrayOf(
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
        )
        private val ANCHO_NEGRITA = intArrayOf(
            278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
            975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
            333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
            611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584,
        )
        // Letras con tilde y signos del castellano: mismo ancho que su letra base.
        private const val CON_TILDE = "áàâäãåéèêëíìîïóòôöõúùûüñçýÿÁÀÂÄÃÅÉÈÊËÍÌÎÏÓÒÔÖÕÚÙÛÜÑÇÝ"
        private const val BASE_TILDE = "aaaaaaeeeeiiiiooooouuuuncyyAAAAAAEEEEIIIIOOOOOUUUUNCY"

        fun anchoCaracter(ch: Char, negrita: Boolean): Int {
            val tabla = if (negrita) ANCHO_NEGRITA else ANCHO_NORMAL
            val c = ch.code
            if (c in 32..126) return tabla[c - 32]
            val k = CON_TILDE.indexOf(ch)
            if (k >= 0) return anchoCaracter(BASE_TILDE[k], negrita)
            return when (ch) {
                '¿' -> 611; '¡' -> 333; '«', '»' -> 556; '·' -> 278; '°' -> 400; 'º' -> 365; 'ª' -> 370
                '—' -> 1000; '–' -> 556; '…' -> 1000; '‘', '’' -> if (negrita) 278 else 222; '“', '”' -> if (negrita) 500 else 333
                '•' -> 350; '€' -> 556; '−' -> 333; else -> 556
            }
        }

        fun anchoTexto(texto: String, tam: Double, negrita: Boolean = false): Double =
            texto.sumOf { anchoCaracter(it, negrita) } * tam / 1000.0

        /** Parte el texto en líneas que entran en [anchoMax] (corta en espacios; una palabra muy larga se corta). */
        fun partirEnLineas(texto: String, anchoMax: Double, tam: Double, negrita: Boolean = false): List<String> {
            val lineas = mutableListOf<String>()
            texto.split('\n').forEach { parrafo ->
                var linea = ""
                parrafo.split(' ').forEach { palabra ->
                    val prueba = if (linea.isEmpty()) palabra else "$linea $palabra"
                    if (anchoTexto(prueba, tam, negrita) <= anchoMax) linea = prueba
                    else {
                        if (linea.isNotEmpty()) lineas += linea
                        var resto = palabra
                        while (anchoTexto(resto, tam, negrita) > anchoMax && resto.length > 1) {
                            var corte = resto.length - 1
                            while (corte > 1 && anchoTexto(resto.take(corte), tam, negrita) > anchoMax) corte--
                            lineas += resto.take(corte); resto = resto.drop(corte)
                        }
                        linea = resto
                    }
                }
                lineas += linea
            }
            return lineas
        }
    }
}
