package pe.canchavoley.app.dominio

/** Contenido de los QR de las reservas y lectura tolerante de lo que entrega un lector. */
object LecturaQr {
    const val PREFIJO = "CANCHAVOLEY"

    /** Texto que lleva el QR de una reserva. */
    fun contenido(codigo: String): String = "$PREFIJO:$codigo"

    /**
     * Código de la reserva a partir de lo leído con la cámara, un lector USB o tecleado.
     * Ignora mayúsculas, espacios y el prefijo «CANCHAVOLEY:», también cuando un lector USB
     * configurado con otra distribución de teclado cambia los dos puntos por otro signo (p. ej. «Ñ»).
     * Devuelve null si no parece un código de CanchaVoley (por ejemplo, el QR de una página web).
     */
    fun codigoDeReserva(texto: String): String? {
        val mayus = texto.trim().uppercase()
        if (mayus.contains("://")) return null
        var t = mayus.filterNot { it.isWhitespace() }
        if (t.startsWith(PREFIJO)) {
            t = t.removePrefix(PREFIJO)
            if (t.isNotEmpty() && !t[0].esAlfanumericoAscii()) t = t.substring(1)
        }
        val limpio = t.filter { it.esAlfanumericoAscii() }
        return limpio.takeIf { it.length in 6..32 }
    }

    private fun Char.esAlfanumericoAscii() = this in 'A'..'Z' || this in '0'..'9'
}
