package pe.canchavoley.app.plataforma

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notificaciones del sistema en Android (aparecen en la barra del celular aunque la app esté cerrada o
 * el celular bloqueado). Al tocar una se abre la app en la reserva correspondiente.
 */
class NotificadorAndroid(private val context: Context) : NotificadorSistema {
    override val disponible: Boolean = true

    init {
        crearCanal(context)
    }

    override fun mostrar(id: Int, titulo: String, texto: String, reservaUuid: String?, avisoId: Long?) {
        if (!permitido(context)) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_ABRIR, reservaUuid.orEmpty())
            if (avisoId != null) putExtra(EXTRA_AVISO, avisoId)
        } ?: return
        val alTocar = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val aviso = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(icono(context))
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(alTocar)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, aviso) }
    }

    companion object {
        const val CANAL = "reservas"
        /** Extra del Intent: uuid de la reserva por abrir ("" = pantalla de notificaciones). */
        const val EXTRA_ABRIR = "canchavoley.abrir"
        /** Extra del Intent: id del aviso del servidor que se marca como leído al tocar la notificación. */
        const val EXTRA_AVISO = "canchavoley.aviso"

        /** Android 13 o más pide permiso para mostrar notificaciones. */
        fun permitido(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun crearCanal(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CANAL) != null) return
            nm.createNotificationChannel(NotificationChannel(CANAL, "Reservas y pagos", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Aprobación o rechazo de solicitudes, pagos y confirmación de reservas"
            })
        }

        @Suppress("DiscouragedApi")
        private fun icono(context: Context): Int =
            context.resources.getIdentifier("ic_notificacion", "drawable", context.packageName).takeIf { it != 0 }
                ?: context.applicationInfo.icon
    }
}
