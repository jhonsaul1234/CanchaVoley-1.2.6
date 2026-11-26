package pe.canchavoley.app.plataforma

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import pe.edu.upeu.prolech.db.AppDatabase

/** Crea el driver SQLite de cada plataforma (Windows: %APPDATA%\CanchaVoley; Android: datos de la app). */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

/** Nombre de la base local. Las instalaciones de la etapa 0 usaban [NOMBRE_BD_ANTERIOR]; se renombra sola. */
const val NOMBRE_BD = "canchavoley.db"
const val NOMBRE_BD_ANTERIOR = "prolech.db"

/** Piezas propias de cada plataforma: TokenStorage cifrado, DriverFactory y MonitorConectividad. */
expect fun platformModule(): Module

const val SCOPE_APP = "scope_app"

/** Infraestructura común: ámbito de la app, cliente HTTP, base local, configuración y sesión. */
val moduloBase = module {
    single(named(SCOPE_APP)) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { TokenEnCache(get<TokenStorage>(named(TOKEN_PLATAFORMA))) }
    single<TokenStorage> { get<TokenEnCache>() }
    single { EstadoSesion() }
    single {
        val tokens = get<TokenEnCache>()
        val estado = get<EstadoSesion>()
        val scope = get<CoroutineScope>(named(SCOPE_APP))
        HttpClientFactory.create(
            tokenProvider = { tokens.actual },
            // 401 en cualquier llamada: se olvida el token y se pide entrar de nuevo (datos locales intactos)
            alSesionVencida = {
                estado.marcarVencida()
                scope.launch { tokens.clearToken() }
            },
        )
    }
    single { AppDatabase(get<DriverFactory>().createDriver()) }
    single { ConfigLocal(get()) }
}

/** Calificador del TokenStorage cifrado de la plataforma (el resto de la app usa [TokenEnCache]). */
const val TOKEN_PLATAFORMA = "token_plataforma"
