package pe.canchavoley.app.plataforma

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/** Carpeta Documentos real de Windows (también si está en OneDrive); en otros sistemas, ~/Documents o ~. */
fun carpetaDocumentos(): File {
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    val ruta = if (windows) runCatching { Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Documents) }.getOrNull() else null
    return ruta?.let { File(it) } ?: File(System.getProperty("user.home"), "Documents").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
}

/** Un nombre que no pisa un archivo anterior: «Caja 2026-11-19.xlsx», «Caja 2026-11-19 (2).xlsx»… */
fun archivoLibre(carpeta: File, nombre: String): File {
    val punto = nombre.lastIndexOf('.')
    val base = if (punto > 0) nombre.substring(0, punto) else nombre
    val ext = if (punto > 0) nombre.substring(punto) else ""
    var archivo = File(carpeta, nombre)
    var n = 2
    while (archivo.exists()) { archivo = File(carpeta, "$base ($n)$ext"); n++ }
    return archivo
}

/** Guarda en [carpeta] (por defecto Documentos\CanchaVoley) y devuelve el archivo creado. */
fun guardarDocumento(nombre: String, contenido: ByteArray, carpeta: File = File(carpetaDocumentos(), "CanchaVoley")): File {
    carpeta.mkdirs()
    return archivoLibre(carpeta, nombre).apply { writeBytes(contenido) }
}

@Composable
actual fun rememberGuardarArchivo(): suspend (nombre: String, tipoMime: String, contenido: ByteArray) -> String = remember<suspend (String, String, ByteArray) -> String> {
    { nombre: String, _: String, contenido: ByteArray ->
        withContext(Dispatchers.IO) {
            val archivo = runCatching { guardarDocumento(nombre, contenido) }.getOrElse {
                return@withContext "No se pudo guardar el archivo: ${it.message ?: it::class.simpleName}"
            }
            val abierto = runCatching {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { Desktop.getDesktop().open(archivo); true } else false
            }.getOrDefault(false)
            "Guardado en ${archivo.absolutePath}" + if (abierto) "" else " (ábrelo desde esa carpeta)"
        }
    }
}
