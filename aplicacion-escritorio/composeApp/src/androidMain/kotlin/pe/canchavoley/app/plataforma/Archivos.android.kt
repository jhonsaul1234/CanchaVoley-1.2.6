package pe.canchavoley.app.plataforma

import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberGuardarArchivo(): suspend (nombre: String, tipoMime: String, contenido: ByteArray) -> String {
    val contexto = LocalContext.current
    return remember<suspend (String, String, ByteArray) -> String>(contexto) {
        { nombre: String, tipoMime: String, contenido: ByteArray ->
            val archivo = withContext(Dispatchers.IO) {
                // Carpeta propia de la app (no necesita permisos); se borran los archivos de días anteriores.
                val carpeta = File(contexto.cacheDir, "exportados").apply { mkdirs() }
                val limite = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                carpeta.listFiles()?.filter { it.lastModified() < limite }?.forEach { it.delete() }
                File(carpeta, nombre).apply { writeBytes(contenido) }
            }
            runCatching {
                // La autoridad se declara en el manifiesto de :composeAndroidApp (FileProvider «.archivos»).
                val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".archivos", archivo)
                val envio = Intent(Intent.ACTION_SEND).apply {
                    type = tipoMime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, nombre)
                    clipData = ClipData.newRawUri(nombre, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                contexto.startActivity(Intent.createChooser(envio, nombre)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                "Elige con qué app compartir «$nombre»."
            }.getOrElse { "No se pudo compartir el archivo: ${it.message ?: it::class.simpleName}" }
        }
    }
}
