package pe.canchavoley.app.dominio

/** Dinero en céntimos (sin decimales flotantes). La API usa texto "60.00". */
object Dinero {
    fun aCentimos(texto: String?): Long? {
        val t = texto?.trim()?.replace(",", ".") ?: return null
        if (t.isEmpty()) return null
        val negativo = t.startsWith("-")
        val limpio = t.removePrefix("-")
        val partes = limpio.split(".")
        if (partes.size > 2 || partes.any { p -> p.any { !it.isDigit() } }) return null
        val enteros = partes[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val dec = (partes.getOrNull(1) ?: "").padEnd(2, '0').take(2).toLong()
        val c = enteros * 100 + dec
        return if (negativo) -c else c
    }

    fun aTexto(centimos: Long): String {
        val signo = if (centimos < 0) "-" else ""
        val abs = kotlin.math.abs(centimos)
        return "$signo${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    /** "S/ 60.00" */
    fun soles(texto: String?): String = texto?.let { aCentimos(it) }?.let { "S/ ${aTexto(it)}" } ?: "—"

    fun soles(centimos: Long): String = "S/ ${aTexto(centimos)}"
}
