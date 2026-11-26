package pe.canchavoley.app.di

import pe.canchavoley.app.plataforma.RegistroFallos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import pe.canchavoley.app.datos.AlmacenLocal
import pe.canchavoley.app.datos.ApiCancha
import pe.canchavoley.app.datos.GestorSesion
import pe.canchavoley.app.datos.MotorSync
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.RevisorAvisos
import pe.canchavoley.app.plataforma.ConfigLocal
import pe.canchavoley.app.plataforma.EstadoSesion
import pe.canchavoley.app.plataforma.SCOPE_APP
import pe.canchavoley.app.plataforma.TokenEnCache
import pe.canchavoley.app.plataforma.moduloBase
import pe.canchavoley.app.plataforma.platformModule

/** Nombre del equipo para la API (escritorio / android): cada uno tiene su propia sesión. */
object PlataformaCv {
    var nombre: String = "escritorio"
}

/** Módulo de CanchaVoley: datos locales, API, sesión, sincronización y repositorio. */
val moduloCanchaVoley = module {
    single { AlmacenLocal(get()) }
    single { ApiCancha(get()) }
    single { GestorSesion(api = get(), almacen = get(), tokens = get(), estadoSesion = get(), plataforma = PlataformaCv.nombre) }
    single {
        val tokens = get<TokenEnCache>()
        val estado = get<EstadoSesion>()
        val scope = get<CoroutineScope>(named(SCOPE_APP))
        val koin = getKoin()
        MotorSync(
            api = get(), almacen = get(), monitor = get(), scope = scope,
            sesionActiva = { !tokens.actual.isNullOrBlank() },
            alSesionVencida = { estado.marcarVencida(); scope.launch { tokens.clearToken() } },
            alSincronizar = { koin.get<RevisorAvisos>().revisar() },
        )
    }
    single {
        val sesion = get<GestorSesion>()
        RevisorAvisos(api = get(), almacen = get(), notificador = get(), esCliente = { sesion.sesion.value?.esPersonal == false })
    }
    single { Repositorio(api = get(), almacen = get(), monitor = get(), motor = get(), sesion = get()) }
}

/** Arranque común de CanchaVoley (escritorio y Android). */
fun iniciarCanchaVoley(plataforma: String, iniciarSincronizacion: Boolean = true, appDeclaration: KoinAppDeclaration = {}) {
    if (GlobalContext.getOrNull() != null) return
    PlataformaCv.nombre = plataforma
    val koin = startKoin {
        appDeclaration()
        modules(moduloBase, platformModule(), moduloCanchaVoley)
    }.koin
    koin.get<ConfigLocal>().aplicarServidorGuardado()
    // El token se descifra una sola vez al iniciar; las peticiones usan la copia en memoria.
    runBlocking { runCatching { koin.get<TokenEnCache>().cargar() }.onFailure { RegistroFallos.anotar(it, "leer la sesión guardada") } }
    if (iniciarSincronizacion) koin.get<MotorSync>().iniciar()
}

/** Inicia la sincronización automática si quedó pendiente (por ejemplo, después de mostrar un informe de error). */
fun iniciarSincronizacionCanchaVoley() {
    GlobalContext.getOrNull()?.get<MotorSync>()?.iniciar()
}
