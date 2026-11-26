package pe.canchavoley.app.plataforma

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection

/**
 * DEF-19: cuando Java no logra escribir en el portapapeles de Windows (otro programa lo tiene abierto),
 * igual se queda con ese texto como si fuera suyo y, desde entonces, al pegar en la app devuelve ese texto
 * en lugar de lo que de verdad hay en el portapapeles (por ejemplo, un número de operación copiado de otra
 * ventana). Después de un copiado fallido se olvida ese contenido para volver a leer el de Windows.
 *
 * Usa los campos `contents` y `owner` de `java.awt.datatransfer.Clipboard`: el lanzador de la app abre ese
 * paquete con `--add-opens java.datatransfer/java.awt.datatransfer=ALL-UNNAMED` (ver composeDesktopApp).
 */
object ContenidoPortapapelesJava {

    /** Olvida el contenido que Java cree tener. Devuelve false si no se pudo (por ejemplo, sin el --add-opens). */
    fun olvidar(portapapeles: Clipboard? = sistema()): Boolean {
        if (portapapeles == null) return false
        return try {
            val clase = Clipboard::class.java
            val contenido = clase.getDeclaredField("contents").apply { isAccessible = true }
            val dueno = clase.getDeclaredField("owner").apply { isAccessible = true }
            synchronized(portapapeles) {           // los métodos del portapapeles de Java usan este mismo bloqueo
                contenido.set(portapapeles, null)
                dueno.set(portapapeles, null)
            }
            true
        } catch (e: Exception) {                     // InaccessibleObjectException, NoSuchFieldException, SecurityException
            false
        }
    }

    /** Para la autoprueba: comprueba, con un portapapeles propio (no el de Windows), que se puede olvidar. */
    fun comprobar(): String {
        val prueba = Clipboard("autoprueba")
        prueba.setContents(StringSelection("x"), null)
        check(olvidar(prueba)) { "no se pudo acceder al contenido del portapapeles de Java (falta --add-opens)" }
        check(prueba.getContents(null) == null) { "el contenido no se olvidó" }
        return "después de un copiado fallido se vuelve a leer el portapapeles de Windows"
    }

    private fun sistema(): Clipboard? = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
}
