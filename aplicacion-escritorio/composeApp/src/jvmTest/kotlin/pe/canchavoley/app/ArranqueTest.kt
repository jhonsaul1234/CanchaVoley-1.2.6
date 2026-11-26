package pe.canchavoley.app

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import pe.canchavoley.app.datos.AlmacenLocal
import pe.canchavoley.app.datos.ApiCancha
import pe.canchavoley.app.datos.GestorSesion
import pe.canchavoley.app.datos.MotorSync
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.di.moduloCanchaVoley
import pe.canchavoley.app.plataforma.ConfigLocal
import pe.canchavoley.app.plataforma.EstadoSesion
import pe.canchavoley.app.plataforma.MonitorConectividad
import pe.canchavoley.app.plataforma.SCOPE_APP
import pe.canchavoley.app.plataforma.TOKEN_PLATAFORMA
import pe.canchavoley.app.plataforma.TokenEnCache
import pe.canchavoley.app.plataforma.TokenStorage
import pe.canchavoley.app.plataforma.moduloBase
import pe.canchavoley.app.plataforma.platformModule
import pe.edu.upeu.prolech.db.AppDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Arranque de la app con los módulos REALES de Koin (los mismos de iniciarCanchaVoley): si falta
 * una pieza, la app se cerraría al abrirse. Solo se reemplazan la base (en memoria), el token
 * (sin tocar las preferencias del equipo) y el monitor del servidor (sin conexiones reales).
 */
class ArranqueTest {

    @Test
    fun todas_las_piezas_de_la_app_se_resuelven_con_los_modulos_reales() {
        val token = TokenFalso("token-guardado")
        val pruebas = module {
            single<TokenStorage>(named(TOKEN_PLATAFORMA)) { token }
            single { AppDatabase(crearDriverEnMemoria()) }
            single<MonitorConectividad> { MonitorConectividadFalso(false) }
        }
        val app = koinApplication {
            allowOverride(true)
            modules(moduloBase, platformModule(), moduloCanchaVoley, pruebas)
        }
        val koin = app.koin
        try {
            listOf(Repositorio::class, MotorSync::class, GestorSesion::class, AlmacenLocal::class, ApiCancha::class,
                ConfigLocal::class, EstadoSesion::class, HttpClient::class, TokenEnCache::class).forEach { tipo ->
                koin.get<Any>(tipo)
            }
            // El resto de la app usa la caché del token, que lee el almacén cifrado una sola vez.
            assertSame(koin.get<TokenEnCache>(), koin.get<TokenStorage>())
            runBlocking { koin.get<TokenEnCache>().cargar() }
            assertEquals("token-guardado", koin.get<TokenEnCache>().actual)
            assertEquals(1, token.lecturas)
            assertTrue(koin.get<Repositorio>() === koin.get<Repositorio>(), "Repositorio debe ser único")
        } finally {
            koin.get<CoroutineScope>(named(SCOPE_APP)).cancel()
            app.close()
        }
    }
}
