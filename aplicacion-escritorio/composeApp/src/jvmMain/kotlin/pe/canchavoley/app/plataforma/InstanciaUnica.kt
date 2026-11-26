package pe.canchavoley.app.plataforma

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * Una sola ventana de CanchaVoley por usuario de Windows: dos copias abiertas a la vez compartirían la
 * misma base local y la misma cola de sincronización. Usa un bloqueo de archivo del sistema operativo,
 * que Windows libera solo cuando la app se cierra (también si termina de forma inesperada).
 */
class InstanciaUnica(private val carpeta: File) {
    private var canal: FileChannel? = null
    private var bloqueo: FileLock? = null

    /**
     * true si esta es la única copia abierta; el bloqueo dura hasta [liberar] o hasta que el proceso termine.
     * Si no se puede crear el archivo de bloqueo (permisos, disco), no impide abrir la app.
     */
    fun reservar(): Boolean {
        if (bloqueo != null) return true
        val c = runCatching {
            carpeta.mkdirs()
            FileChannel.open(File(carpeta, ARCHIVO).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }.getOrElse { return true }
        val b = try {
            c.tryLock()
        } catch (_: OverlappingFileLockException) {
            null                                    // otra copia en este mismo proceso (solo en pruebas)
        } catch (_: Exception) {
            runCatching { c.close() }
            return true
        }
        if (b == null) {
            runCatching { c.close() }
            return false
        }
        canal = c
        bloqueo = b
        return true
    }

    fun liberar() {
        runCatching { bloqueo?.release() }
        runCatching { canal?.close() }
        bloqueo = null
        canal = null
    }

    companion object {
        const val ARCHIVO = "instancia.lock"
    }
}
