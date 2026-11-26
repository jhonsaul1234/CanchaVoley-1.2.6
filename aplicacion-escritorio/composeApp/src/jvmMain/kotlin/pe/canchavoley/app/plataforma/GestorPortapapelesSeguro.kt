@file:Suppress("DEPRECATION")

package pe.canchavoley.app.plataforma

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * DEF-16 (botones «Copiar»): las pantallas de caja, reportes y detalle de la reserva copian con el
 * `ClipboardManager` clásico de Compose. En Windows, si otro programa (por ejemplo BlueStacks) tiene
 * abierto el portapapeles en ese instante, Java lanza `IllegalStateException("cannot open system clipboard")`.
 * Aquí se reintenta unas veces; si sigue ocupado se avisa con [PortapapelesOcupado], que [copiarTexto]
 * convierte en un mensaje para el usuario en vez de cerrar la ventana. Además, Java se olvida del texto que
 * no llegó a Windows, para que pegar en la app no lo devuelva (DEF-19, [ContenidoPortapapelesJava]).
 */
class GestorPortapapelesSeguro(
    private val base: ClipboardManager,
    private val intentos: Int = 6,
    private val esperaMs: Long = 40,
    private val esperar: (Long) -> Unit = { Thread.sleep(it) },
    private val alNoPoderCopiar: () -> Unit = { ContenidoPortapapelesJava.olvidar() },
) : ClipboardManager {

    override fun setText(annotatedString: AnnotatedString) {
        var ultimo: IllegalStateException? = null
        repeat(intentos) { intento ->
            try {
                base.setText(annotatedString)
                return
            } catch (e: IllegalStateException) {
                ultimo = e
                if (intento < intentos - 1) esperar(esperaMs)
            }
        }
        alNoPoderCopiar()
        throw PortapapelesOcupado(ultimo)
    }

    override fun getText(): AnnotatedString? = try { base.getText() } catch (e: Exception) { null }

    override fun hasText(): Boolean = try { base.hasText() } catch (e: Exception) { false }

    override fun getClip(): ClipEntry? = try { base.getClip() } catch (e: Exception) { null }

    override fun setClip(clipEntry: ClipEntry?) {
        try { base.setClip(clipEntry) } catch (e: Exception) { alNoPoderCopiar() }
    }
}
