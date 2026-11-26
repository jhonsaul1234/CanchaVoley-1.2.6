package pe.canchavoley.app.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import pe.canchavoley.app.datos.MotorSync

/**
 * Con la app cerrada, Android ejecuta este trabajo cada ~15 minutos (cuando hay red): sincroniza y, al
 * terminar, se revisan los avisos y se muestran las notificaciones nuevas (ver RevisorAvisos).
 * Android puede demorarlo para ahorrar batería (modo reposo); los avisos al instante necesitarían
 * notificaciones push (Firebase Cloud Messaging), que requieren credenciales del negocio.
 */
class TrabajoAvisos : JobService() {
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val koin = GlobalContext.getOrNull() ?: return false
        ambito.launch {
            try {
                koin.get<MotorSync>().sincronizar()
            } catch (_: Exception) {
                // Sin conexión o error pasajero: se intenta de nuevo en la siguiente vuelta.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        ambito.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 261101

        /** Programa el trabajo periódico (una sola vez; Android lo conserva aunque se reinicie el celular). */
        fun programar(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.allPendingJobs.any { it.id == ID }) return
            val info = JobInfo.Builder(ID, ComponentName(context, TrabajoAvisos::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(info) }
        }
    }
}
