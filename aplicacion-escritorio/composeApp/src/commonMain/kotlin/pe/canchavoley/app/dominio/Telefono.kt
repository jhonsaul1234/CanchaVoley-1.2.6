package pe.canchavoley.app.dominio

/** Mismo formato que el servidor: solo dígitos y 51 delante de los celulares de 9 dígitos. */
object Telefono {
    fun normalizar(texto: String?): String {
        val d = texto.orEmpty().filter { it.isDigit() }
        return if (d.length == 9 && d.startsWith("9")) "51$d" else d
    }

    fun valido(texto: String?): Boolean = normalizar(texto).length in 7..15

    /** 51987654321 → 987 654 321 */
    fun mostrar(texto: String?): String {
        val d = normalizar(texto)
        val local = if (d.length == 11 && d.startsWith("51")) d.drop(2) else d
        return if (local.length == 9) "${local.take(3)} ${local.substring(3, 6)} ${local.takeLast(3)}" else local
    }
}
