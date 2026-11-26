package pe.canchavoley.app.android

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level
import pe.canchavoley.app.di.iniciarCanchaVoley
import pe.canchavoley.app.plataforma.RegistroFallos
import java.io.File

class CanchaVoleyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        prepararRegistroFallos()
        // Si la vez anterior hubo un error, la sincronización espera a que la usuaria vea el informe.
        iniciar(sincronizar = informePendiente() == null)
        // Avisos con la app cerrada: trabajo periódico de Android (cada ~15 minutos, cuando hay red).
        runCatching { TrabajoAvisos.programar(this) }
    }

    /** Arranca la app (Koin, sesión guardada y sincronización). @return el error, si no se pudo. */
    fun iniciar(sincronizar: Boolean): Throwable? = runCatching {
        iniciarCanchaVoley("android", iniciarSincronizacion = sincronizar) {
            androidLogger(Level.ERROR)
            androidContext(this@CanchaVoleyApplication)
        }
    }.exceptionOrNull()?.also { RegistroFallos.anotar(it, "inicio de la app") }

    fun archivoInforme(): File = File(filesDir, RegistroFallos.ARCHIVO)

    fun informePendiente(): String? = archivoInforme().takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun descartarInforme() {
        runCatching { archivoInforme().delete() }
    }

    /** Todo error no controlado deja un informe en los datos privados de la app (no sale del celular). */
    private fun prepararRegistroFallos() {
        RegistroFallos.version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        RegistroFallos.guardar = { texto -> archivoInforme().writeText(texto) }
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
            RegistroFallos.anotar(error, "hilo ${hilo.name}")
            anterior?.uncaughtException(hilo, error)
        }
    }
}
