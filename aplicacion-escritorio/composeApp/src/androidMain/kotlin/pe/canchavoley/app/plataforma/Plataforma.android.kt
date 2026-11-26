package pe.canchavoley.app.plataforma

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import pe.edu.upeu.prolech.db.AppDatabase
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Android: SQLite en los datos privados de la app. */
actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        val nueva = context.getDatabasePath(NOMBRE_BD)
        val anterior = context.getDatabasePath(NOMBRE_BD_ANTERIOR)
        if (!nueva.exists() && anterior.exists() && anterior.renameTo(nueva)) {
            listOf("-journal", "-wal", "-shm").forEach { sufijo ->
                File(anterior.path + sufijo).takeIf { it.exists() }?.renameTo(File(nueva.path + sufijo))
            }
        }
        return AndroidSqliteDriver(AppDatabase.Schema, context, NOMBRE_BD)
    }
}

/** ¿Responde el servidor? Abre y cierra una conexión TCP al host y puerto configurados. */
private suspend fun servidorResponde(url: String = ApiConfig.BASE_URL): Boolean = withContext(Dispatchers.IO) {
    val (host, puerto) = hostYPuerto(url) ?: return@withContext false
    runCatching { Socket().use { it.connect(InetSocketAddress(host, puerto), 2_000) } }.isSuccess
}

/**
 * En línea = el servidor del negocio responde. Además de revisar cada cierto tiempo, revisa de
 * inmediato cuando Android avisa un cambio de red (Wi-Fi ↔ datos, avión, etc.).
 */
class MonitorConectividadAndroid(context: Context, scope: CoroutineScope) : MonitorConectividad {
    private val monitor = MonitorServidor(scope, sondear = { servidorResponde() })
    override val enLinea: StateFlow<Boolean> = monitor.enLinea
    override fun revisarAhora() = monitor.revisarAhora()

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = monitor.revisarAhora()
                override fun onLost(network: Network) = monitor.revisarAhora()
                override fun onCapabilitiesChanged(network: Network, capacidades: NetworkCapabilities) = monitor.revisarAhora()
            })
        }
    }
}

actual fun platformModule(): Module = module {
    single<TokenStorage>(named(TOKEN_PLATAFORMA)) { TokenStorageAndroid(androidContext()) }
    single { DriverFactory(androidContext()) }
    single<MonitorConectividad> { MonitorConectividadAndroid(androidContext(), get(named(SCOPE_APP))) }
    // Notificaciones del sistema (barra del celular) para los avisos de reservas y pagos.
    single<NotificadorSistema> { NotificadorAndroid(androidContext()) }
}
