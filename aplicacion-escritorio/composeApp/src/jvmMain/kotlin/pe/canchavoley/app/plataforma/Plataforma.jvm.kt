package pe.canchavoley.app.plataforma

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import pe.canchavoley.app.di.iniciarCanchaVoley
import pe.edu.upeu.prolech.db.AppDatabase
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

/**
 * Perfil de datos opcional (variable de entorno CANCHAVOLEY_PERFIL, solo letras, números y guiones):
 * permite abrir una versión de prueba con su propia base local, su propia sesión y su propio servidor,
 * sin tocar los datos de la versión instalada. Sin la variable, todo queda como siempre.
 */
fun perfilDatos(valor: String? = System.getenv("CANCHAVOLEY_PERFIL")): String? =
    valor?.trim()?.takeIf { it.isNotEmpty() && it.length <= 30 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

/** Carpeta de datos en Windows: %APPDATA%\CanchaVoley (con perfil: %APPDATA%\CanchaVoley-<perfil>). */
fun carpetaDatos(): File {
    val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
    val nombre = perfilDatos()?.let { "CanchaVoley-$it" } ?: "CanchaVoley"
    return File(base, nombre).apply { mkdirs() }
}

/** Nodo de preferencias donde se guarda el token cifrado (uno por perfil). */
fun nodoPreferencias(): String = perfilDatos()?.let { "canchavoley-$it" } ?: "canchavoley"

/** Escritorio: SQLite en %APPDATA%\CanchaVoley. El driver la crea y la migra si cambia el esquema. */
actual class DriverFactory(private val carpeta: File = carpetaDatos()) {
    actual fun createDriver(): SqlDriver {
        val archivo = File(carpeta, NOMBRE_BD)
        renombrarBaseAnterior(carpeta, archivo)
        return JdbcSqliteDriver("jdbc:sqlite:${archivo.absolutePath}", Properties(), AppDatabase.Schema)
    }
}

/** Las instalaciones de la etapa 0 crearon «prolech.db»: se renombra (con sus archivos auxiliares) una sola vez. */
internal fun renombrarBaseAnterior(carpeta: File, nueva: File) {
    val anterior = File(carpeta, NOMBRE_BD_ANTERIOR)
    if (nueva.exists() || !anterior.exists()) return
    if (anterior.renameTo(nueva)) {
        listOf("-journal", "-wal", "-shm").forEach { sufijo ->
            File(carpeta, NOMBRE_BD_ANTERIOR + sufijo).takeIf { it.exists() }?.renameTo(File(carpeta, NOMBRE_BD + sufijo))
        }
    }
}

/** ¿Responde el servidor? Abre y cierra una conexión TCP al host y puerto configurados. */
internal suspend fun servidorResponde(url: String = ApiConfig.BASE_URL, esperaMs: Int = 2_000): Boolean =
    withContext(Dispatchers.IO) {
        val (host, puerto) = hostYPuerto(url) ?: return@withContext false
        runCatching { Socket().use { it.connect(InetSocketAddress(host, puerto), esperaMs) } }.isSuccess
    }

actual fun platformModule(): Module = module {
    single<TokenStorage>(named(TOKEN_PLATAFORMA)) { TokenStorageEscritorio() }
    single { DriverFactory() }
    single<MonitorConectividad> { MonitorServidor(get(named(SCOPE_APP)), sondear = { servidorResponde() }) }
    // Windows: los avisos se ven en la pantalla «Notificaciones» (sin notificaciones del sistema).
    single<NotificadorSistema> { NotificadorNulo() }
}

/**
 * Arranque de la app de escritorio. La primera vez deja como servidor [servidorPorDefecto]
 * (en la PC del negocio el backend corre en la misma máquina).
 */
fun iniciarEscritorio(servidorPorDefecto: String, iniciarSincronizacion: Boolean = true) {
    iniciarCanchaVoley("escritorio", iniciarSincronizacion)
    val config = GlobalContext.get().get<ConfigLocal>()
    if (config.sinServidorGuardado() || config.servidor() == ApiConfig.SERVIDOR_POR_DEFECTO) {
        config.guardarServidor(servidorPorDefecto)
    }
}
