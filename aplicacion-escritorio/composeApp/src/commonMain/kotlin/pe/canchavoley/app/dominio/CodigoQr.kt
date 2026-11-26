package pe.canchavoley.app.dominio

/**
 * Generador de códigos QR en Kotlin puro (modo byte, corrección de errores nivel M, versiones 1–6,
 * hasta 106 bytes). Sigue el estándar ISO/IEC 18004; la estructura se basa en el generador de
 * referencia de Nayuki (licencia MIT). Se escribió aquí porque el proyecto no tiene una biblioteca de QR
 * disponible sin internet. Se verifica en las pruebas decodificando el resultado con OpenCV.
 */
class CodigoQr private constructor(val version: Int, val mascara: Int, private val modulos: Array<BooleanArray>) {
    val tamano: Int get() = modulos.size
    fun oscuro(fila: Int, columna: Int): Boolean = modulos[fila][columna]

    companion object {
        private val ECC_POR_BLOQUE = intArrayOf(-1, 10, 16, 26, 18, 24, 16)   // nivel M
        private val BLOQUES = intArrayOf(-1, 1, 1, 1, 2, 2, 4)                 // nivel M
        private const val BITS_FORMATO_M = 0

        fun codificar(texto: String): CodigoQr {
            val datos = texto.encodeToByteArray()
            val version = (1..6).firstOrNull { v -> 4 + 8 + datos.size * 8 <= capacidadDatos(v) * 8 }
                ?: throw IllegalArgumentException("Texto demasiado largo para el QR (${datos.size} bytes).")

            // Flujo de bits: modo byte (0100) + longitud (8 bits) + datos + terminador + relleno
            val bits = ArrayList<Boolean>()
            fun agregar(valor: Int, cantidad: Int) { for (i in cantidad - 1 downTo 0) bits += ((valor ushr i) and 1) == 1 }
            agregar(0b0100, 4)
            agregar(datos.size, 8)
            datos.forEach { agregar(it.toInt() and 0xFF, 8) }
            val capacidadBits = capacidadDatos(version) * 8
            agregar(0, minOf(4, capacidadBits - bits.size))
            agregar(0, (8 - bits.size % 8) % 8)
            var relleno = 0xEC
            while (bits.size < capacidadBits) { agregar(relleno, 8); relleno = relleno xor 0xEC xor 0x11 }
            val palabras = ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) }.toByte() }

            val todas = intercalarConCorreccion(palabras, version)
            val tam = 17 + 4 * version
            val mod = Array(tam) { BooleanArray(tam) }
            val funcion = Array(tam) { BooleanArray(tam) }
            dibujarPatrones(mod, funcion, version)
            colocarDatos(mod, funcion, todas)

            var mejor: Array<BooleanArray>? = null
            var mejorMascara = 0
            var mejorPenalidad = Int.MAX_VALUE
            for (m in 0 until 8) {
                val copia = Array(tam) { mod[it].copyOf() }
                aplicarMascara(copia, funcion, m)
                dibujarFormato(copia, funcion, m)
                val p = penalidad(copia)
                if (p < mejorPenalidad) { mejorPenalidad = p; mejor = copia; mejorMascara = m }
            }
            return CodigoQr(version, mejorMascara, mejor!!)
        }

        private fun modulosDeDatos(v: Int): Int {
            var r = (16 * v + 128) * v + 64
            if (v >= 2) { val n = v / 7 + 2; r -= (25 * n - 10) * n - 55 }
            return r
        }

        private fun capacidadDatos(v: Int): Int = modulosDeDatos(v) / 8 - ECC_POR_BLOQUE[v] * BLOQUES[v]

        private fun intercalarConCorreccion(datos: ByteArray, v: Int): ByteArray {
            val nBloques = BLOQUES[v]
            val ecc = ECC_POR_BLOQUE[v]
            val brutas = modulosDeDatos(v) / 8
            val cortos = nBloques - brutas % nBloques
            val largoCorto = brutas / nBloques
            val divisor = divisorRs(ecc)
            val bloques = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until nBloques) {
                val n = largoCorto - ecc + if (i < cortos) 0 else 1
                val dat = datos.copyOfRange(k, k + n)
                k += n
                val bloque = dat.copyOf(largoCorto + 1)
                val r = restoRs(dat, divisor)
                r.copyInto(bloque, bloque.size - ecc)
                bloques += bloque
            }
            val res = ByteArray(brutas)
            var p = 0
            for (i in 0 until bloques[0].size) for (j in bloques.indices) {
                if (i != largoCorto - ecc || j >= cortos) res[p++] = bloques[j][i]
            }
            return res
        }

        private fun multiplicar(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun divisorRs(grado: Int): ByteArray {
            val r = ByteArray(grado)
            r[grado - 1] = 1
            var raiz = 1
            for (i in 0 until grado) {
                for (j in r.indices) {
                    r[j] = multiplicar(r[j].toInt() and 0xFF, raiz).toByte()
                    if (j + 1 < r.size) r[j] = (r[j].toInt() xor r[j + 1].toInt()).toByte()
                }
                raiz = multiplicar(raiz, 0x02)
            }
            return r
        }

        private fun restoRs(datos: ByteArray, divisor: ByteArray): ByteArray {
            val r = ByteArray(divisor.size)
            for (b in datos) {
                val factor = (b.toInt() xor r[0].toInt()) and 0xFF
                r.copyInto(r, 0, 1, r.size)
                r[r.size - 1] = 0
                for (i in r.indices) r[i] = (r[i].toInt() xor multiplicar(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return r
        }

        private fun poner(m: Array<BooleanArray>, f: Array<BooleanArray>, x: Int, y: Int, oscuro: Boolean) {
            m[y][x] = oscuro
            f[y][x] = true
        }

        private fun dibujarPatrones(m: Array<BooleanArray>, f: Array<BooleanArray>, v: Int) {
            val tam = m.size
            for (i in 0 until tam) { poner(m, f, 6, i, i % 2 == 0); poner(m, f, i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, tam - 4 to 3, 3 to tam - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until tam && y in 0 until tam) poner(m, f, x, y, d != 2 && d != 4)
                }
            }
            if (v >= 2) {
                val c = tam - 7
                for (dy in -2..2) for (dx in -2..2) poner(m, f, c + dx, c + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
            dibujarFormato(m, f, 0)
        }

        private fun dibujarFormato(m: Array<BooleanArray>, f: Array<BooleanArray>, mascara: Int) {
            val datos = (BITS_FORMATO_M shl 3) or mascara
            var rem = datos
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((datos shl 10) or rem) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            val tam = m.size
            for (i in 0..5) poner(m, f, 8, i, bit(i))
            poner(m, f, 8, 7, bit(6))
            poner(m, f, 8, 8, bit(7))
            poner(m, f, 7, 8, bit(8))
            for (i in 9 until 15) poner(m, f, 14 - i, 8, bit(i))
            for (i in 0 until 8) poner(m, f, tam - 1 - i, 8, bit(i))
            for (i in 8 until 15) poner(m, f, 8, tam - 15 + i, bit(i))
            poner(m, f, 8, tam - 8, true)
        }

        private fun colocarDatos(m: Array<BooleanArray>, f: Array<BooleanArray>, datos: ByteArray) {
            val tam = m.size
            var i = 0
            var derecha = tam - 1
            while (derecha >= 1) {
                if (derecha == 6) derecha = 5
                for (vert in 0 until tam) for (j in 0..1) {
                    val x = derecha - j
                    val subiendo = ((derecha + 1) and 2) == 0
                    val y = if (subiendo) tam - 1 - vert else vert
                    if (!f[y][x] && i < datos.size * 8) {
                        m[y][x] = ((datos[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                derecha -= 2
            }
        }

        private fun aplicarMascara(m: Array<BooleanArray>, f: Array<BooleanArray>, mascara: Int) {
            for (y in m.indices) for (x in m.indices) {
                if (f[y][x]) continue
                val invertir = when (mascara) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invertir) m[y][x] = !m[y][x]
            }
        }

        /** Penalidad simplificada (rachas, bloques 2×2, patrones parecidos a buscadores y balance). */
        private fun penalidad(m: Array<BooleanArray>): Int {
            val tam = m.size
            var p = 0
            for (eje in 0..1) for (a in 0 until tam) {
                var racha = 1
                for (b in 1 until tam) {
                    val actual = if (eje == 0) m[a][b] else m[b][a]
                    val previo = if (eje == 0) m[a][b - 1] else m[b - 1][a]
                    if (actual == previo) { racha++; if (racha == 5) p += 3 else if (racha > 5) p++ } else racha = 1
                }
                for (b in 0..tam - 11) {
                    val v = BooleanArray(11) { if (eje == 0) m[a][b + it] else m[b + it][a] }
                    val patron = booleanArrayOf(true, false, true, true, true, false, true)
                    val claros = BooleanArray(4)
                    if ((v.copyOfRange(0, 7).contentEquals(patron) && v.copyOfRange(7, 11).contentEquals(claros)) ||
                        (v.copyOfRange(0, 4).contentEquals(claros) && v.copyOfRange(4, 11).contentEquals(patron))) p += 40
                }
            }
            for (y in 0 until tam - 1) for (x in 0 until tam - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
            }
            val oscuros = m.sumOf { fila -> fila.count { it } }
            val total = tam * tam
            val k = (kotlin.math.abs(oscuros * 20 - total * 10) + total - 1) / total - 1
            p += maxOf(0, k) * 10
            return p
        }
    }
}
