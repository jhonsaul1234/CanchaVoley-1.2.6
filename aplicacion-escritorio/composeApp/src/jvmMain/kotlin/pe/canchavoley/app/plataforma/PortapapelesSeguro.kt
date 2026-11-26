@file:OptIn(ExperimentalComposeUiApi::class)

package pe.canchavoley.app.plataforma

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Portapapeles que no cierra la app (DEF-16). En Windows, si otro programa tiene abierto el portapapeles
 * en ese instante (por ejemplo BlueStacks sincronizándolo), Java lanza
 * `IllegalStateException("cannot open system clipboard")` y Compose cerraba la ventana.
 * Aquí se reintenta unas veces; si sigue ocupado, pegar o copiar simplemente no hace nada.
 * Si copiar falla, Java se olvida del texto que no llegó a Windows (DEF-19, [ContenidoPortapapelesJava]).
 */
class PortapapelesSeguro(
    private val base: Clipboard,
    private val intentos: Int = 6,
    private val esperaMs: Long = 40,
    private val alNoPoderCopiar: () -> Unit = { ContenidoPortapapelesJava.olvidar() },
) : Clipboard {

    override suspend fun getClipEntry(): ClipEntry? {
        repeat(intentos) { intento ->
            try {
                val entrada = base.getClipEntry() ?: return null
                return copiaDelTexto(entrada) ?: entrada
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (intento < intentos - 1) delay(esperaMs)
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        repeat(intentos) { intento ->
            try {
                base.setClipEntry(clipEntry)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (intento < intentos - 1) delay(esperaMs)
            } catch (e: Exception) {
                alNoPoderCopiar()
                return
            }
        }
        alNoPoderCopiar()
    }

    override val nativeClipboard: NativeClipboard
        get() = base.nativeClipboard

    /** El texto se lee ahora: un momento después el portapapeles podría volver a estar ocupado. */
    private fun copiaDelTexto(entrada: ClipEntry): ClipEntry? {
        val datos = entrada.asAwtTransferable ?: return null
        if (!datos.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        val texto = datos.getTransferData(DataFlavor.stringFlavor) as? String ?: return null
        return ClipEntry(StringSelection(texto))
    }
}
