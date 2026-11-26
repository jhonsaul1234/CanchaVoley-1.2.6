package pe.canchavoley.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pe.canchavoley.app.datos.AlmacenLocal
import pe.edu.upeu.prolech.db.AppDatabase
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La base SQLite que ya existe en una PC o un celular (versión 5, la de la etapa 0) debe
 * actualizarse sola a la versión 6 al abrir la app, creando las tablas de CanchaVoley con la
 * misma forma que una instalación nueva y sin tocar las tablas que ya tenía.
 */
class MigracionBaseLocalTest {

    private val tablasCv = listOf("CvDato", "CvCliente", "CvReserva", "CvPago", "CvOperacion")

    private fun url(f: File) = "jdbc:sqlite:${f.absolutePath}"

    private fun normalizar(sql: String) = sql.replace("IF NOT EXISTS ", "")
        .replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")").trim()

    private fun esquemaCv(d: SqlDriver): List<Pair<String, String>> = d.executeQuery(
        null,
        "SELECT name, sql FROM sqlite_master WHERE tbl_name LIKE 'Cv%' AND sql IS NOT NULL ORDER BY name",
        { c ->
            val filas = mutableListOf<Pair<String, String>>()
            while (c.next().value) filas += c.getString(0)!! to normalizar(c.getString(1)!!)
            QueryResult.Value(filas)
        },
        0,
    ).value

    private fun version(d: SqlDriver): Long =
        d.executeQuery(null, "PRAGMA user_version", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value

    private fun cantidadTablas(d: SqlDriver): Long = d.executeQuery(
        null, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'Cv%' AND name NOT LIKE 'sqlite_%'",
        { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0,
    ).value

    @Test
    fun la_version_del_esquema_es_6() {
        assertEquals(6L, AppDatabase.Schema.version)
    }

    @Test
    fun una_base_de_la_version_5_se_actualiza_con_las_tablas_de_canchavoley() {
        val archivo = File.createTempFile("cv-migracion", ".db").apply { delete(); deleteOnExit() }

        // 1) Base "antigua": las tablas de ProLech sin las de CanchaVoley y con user_version = 5.
        val esperado: List<Pair<String, String>>
        val tablasAntiguas: Long
        JdbcSqliteDriver(url(archivo)).use { d ->
            AppDatabase.Schema.create(d)
            esperado = esquemaCv(d)
            tablasCv.forEach { d.execute(null, "DROP TABLE $it", 0) }
            d.execute(null, "PRAGMA user_version = 5", 0)
            tablasAntiguas = cantidadTablas(d)
            assertTrue(esquemaCv(d).isEmpty())
        }
        assertEquals(tablasCv.size, esperado.count { it.second.startsWith("CREATE TABLE") })

        // 2) Abrirla como lo hace la app de escritorio (DriverFactory.jvm.kt): aplica 5.sqm.
        JdbcSqliteDriver(url(archivo), Properties(), AppDatabase.Schema).use { d ->
            assertEquals(6L, version(d))
            assertEquals(esperado, esquemaCv(d), "5.sqm debe crear lo mismo que CanchaVoley.sq")
            assertEquals(tablasAntiguas, cantidadTablas(d), "no se pierde ninguna tabla anterior")

            val almacen = AlmacenLocal(AppDatabase(d))
            almacen.guardarDato("prueba", "1")
            assertEquals("1", almacen.dato("prueba"))
            almacen.encolar("crear_reserva", "r-1", buildJsonObject { put("uuid", "r-1") })
            assertEquals(listOf("crear_reserva"), almacen.operaciones().map { it.tipo })
        }

        // 3) Abrirla de nuevo no vuelve a migrar ni pierde la cola pendiente.
        JdbcSqliteDriver(url(archivo), Properties(), AppDatabase.Schema).use { d ->
            assertEquals(6L, version(d))
            assertEquals(1L, AlmacenLocal(AppDatabase(d)).cantidadOperaciones())
        }
    }
}
