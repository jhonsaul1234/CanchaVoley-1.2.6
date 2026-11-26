@file:Suppress("DEPRECATION")

package pe.canchavoley.app.plataforma

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** El portapapeles siguió ocupado por otro programa después de reintentar (DEF-16). */
class PortapapelesOcupado(causa: Throwable? = null) : IllegalStateException("El portapapeles está ocupado por otro programa.", causa)

const val MENSAJE_PORTAPAPELES_OCUPADO = "No se pudo copiar: otro programa está usando el portapapeles. Inténtalo de nuevo."

/**
 * Copia [texto] al portapapeles sin cerrar la app si falla (DEF-16).
 * Devuelve `true` si se copió; si no, la pantalla muestra [MENSAJE_PORTAPAPELES_OCUPADO].
 */
fun copiarTexto(portapapeles: ClipboardManager, texto: String): Boolean =
    try {
        portapapeles.setText(AnnotatedString(texto))
        true
    } catch (e: Exception) {
        false
    }
