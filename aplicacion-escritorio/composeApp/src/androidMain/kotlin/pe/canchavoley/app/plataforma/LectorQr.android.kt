package pe.canchavoley.app.plataforma

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Android: abre la cámara con el lector de ZXing (funciona sin internet). El permiso de cámara
 * lo pide el propio lector la primera vez.
 */
@Composable
actual fun rememberLectorQr(alLeer: (String) -> Unit): (() -> Unit)? {
    val alLeerActual by rememberUpdatedState(alLeer)
    val lanzador = rememberLauncherForActivityResult(ScanContract()) { resultado ->
        val texto = resultado.contents
        if (!texto.isNullOrBlank()) alLeerActual(texto)
    }
    return remember(lanzador) {
        {
            lanzador.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Apunta al código QR de la reserva")
                    setBeepEnabled(true)
                    setOrientationLocked(false)
                }
            )
        }
    }
}
