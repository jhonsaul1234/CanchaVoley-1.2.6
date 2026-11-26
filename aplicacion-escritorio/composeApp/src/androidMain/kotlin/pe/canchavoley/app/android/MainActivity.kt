package pe.canchavoley.app.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.koin.core.context.GlobalContext
import pe.canchavoley.app.di.iniciarSincronizacionCanchaVoley
import pe.canchavoley.app.plataforma.AccionesExternas
import pe.canchavoley.app.plataforma.AvisoTocado
import pe.canchavoley.app.plataforma.NotificadorAndroid
import pe.canchavoley.app.plataforma.rememberCompartirTexto
import pe.canchavoley.app.ui.CanchaVoleyApp
import pe.canchavoley.app.ui.PantallaFalloAnterior

class MainActivity : ComponentActivity() {
    private val pedirPermisoAvisos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo al abrir de verdad (no al girar la pantalla, que vuelve a entregar el mismo Intent).
        if (savedInstanceState == null) atenderAviso(intent)
        // Android 13 o más: se pide permiso para mostrar las notificaciones de reservas y pagos.
        if (Build.VERSION.SDK_INT >= 33 && !NotificadorAndroid.permitido(this)) {
            runCatching { pedirPermisoAvisos.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        val app = application as CanchaVoleyApplication
        val inicial = app.informePendiente()
            ?: if (GlobalContext.getOrNull() == null) "No se pudo iniciar la aplicación." else null
        setContent {
            var informe by remember { mutableStateOf(inicial) }
            val compartir = rememberCompartirTexto()
            val actual = informe
            if (actual == null) {
                CanchaVoleyApp()
            } else {
                PantallaFalloAnterior(
                    informe = actual,
                    textoCompartir = "Compartir informe",
                    onCompartir = { compartir?.invoke("Informe de error de CanchaVoley", actual) },
                    onContinuar = {
                        app.descartarInforme()
                        if (GlobalContext.getOrNull() == null) {
                            val fallo = app.iniciar(sincronizar = true)
                            informe = if (fallo == null) null else (app.informePendiente() ?: "No se pudo iniciar la aplicación: ${fallo.message}")
                        } else {
                            iniciarSincronizacionCanchaVoley()
                            informe = null
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        atenderAviso(intent)
    }

    /** Se tocó una notificación: se abre su reserva (o la pantalla de notificaciones) y el aviso queda leído. */
    private fun atenderAviso(intent: Intent?) {
        if (intent == null || !intent.hasExtra(NotificadorAndroid.EXTRA_ABRIR)) return
        val reserva = intent.getStringExtra(NotificadorAndroid.EXTRA_ABRIR).orEmpty()
        val aviso = intent.getLongExtra(NotificadorAndroid.EXTRA_AVISO, -1L).takeIf { it >= 0 }
        intent.removeExtra(NotificadorAndroid.EXTRA_ABRIR)
        intent.removeExtra(NotificadorAndroid.EXTRA_AVISO)
        AccionesExternas.avisoTocado.value = AvisoTocado(reserva.ifEmpty { null }, aviso)
    }
}
