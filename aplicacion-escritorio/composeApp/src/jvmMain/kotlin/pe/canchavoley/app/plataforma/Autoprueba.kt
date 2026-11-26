package pe.canchavoley.app.plataforma

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import org.jetbrains.skia.EncodedImageFormat
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.ui.Colores
import pe.canchavoley.app.ui.Logo
import pe.canchavoley.app.ui.TemaCanchaVoley
import pe.edu.upeu.prolech.db.AppDatabase
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Properties
import java.util.prefs.Preferences
import javax.crypto.KeyAgreement
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Autoprueba sin ventana: `CanchaVoley.exe --autoprueba [archivo-del-informe]`.
 *
 * El instalador lleva un Java recortado (solo los módulos que la app usa, para que pese menos).
 * `scripts/compilar.ps1` ejecuta esta prueba con ese mismo Java justo después de compilar y así se
 * sabe, antes de instalar, que no falta nada: base local SQLite, preferencias, cifrado del token
 * (DPAPI), conexiones seguras, cliente HTTP con JSON, zona horaria y dibujo de pantallas.
 *
 * No toca los datos reales: la base se crea en una carpeta temporal que se borra al terminar, las
 * preferencias solo se leen y el cifrado se prueba con un texto de prueba.
 */
object Autoprueba {
    data class Resultado(val nombre: String, val ok: Boolean, val detalle: String)

    fun ejecutar(incluirDibujo: Boolean = true): List<Resultado> = buildList {
        add(comprobar("Java") { "${System.getProperty("java.runtime.version")} · ${ModuleLayer.boot().modules().size} módulos" })
        add(comprobar("Base local SQLite") { baseLocal() })
        add(comprobar("Preferencias del usuario") { preferencias() })
        add(comprobar("Cifrado del token (DPAPI)") { cifrado() })
        add(comprobar("Conexiones seguras (TLS)") { tls() })
        add(comprobar("Cliente HTTP y JSON") { clienteHttp() })
        add(comprobar("Zona horaria") { zonaHoraria() })
        add(comprobar("Portapapeles") { ContenidoPortapapelesJava.comprobar() })
        if (incluirDibujo) add(comprobar("Dibujo de pantallas") { dibujo() })
    }

    fun informe(resultados: List<Resultado>): String = buildString {
        appendLine("CanchaVoley · autoprueba")
        resultados.forEach { appendLine("${if (it.ok) "[OK]   " else "[FALLA]"} ${it.nombre}: ${it.detalle}") }
        val fallas = resultados.count { !it.ok }
        appendLine(if (fallas == 0) "Resultado: todo correcto" else "Resultado: $fallas comprobación(es) con falla")
    }

    /**
     * Para `main`: ejecuta, muestra el informe, lo guarda en el archivo indicado (o en la carpeta
     * temporal) y devuelve el código de salida: 0 si todo pasó, 1 si algo falló.
     * `--sin-dibujo` omite la prueba de dibujo (solo para equipos sin la librería gráfica de Windows).
     */
    fun desdeLineaDeComandos(args: List<String>): Int {
        val archivo = args.firstOrNull { !it.startsWith("--") }?.let(::File)
            ?: File(System.getProperty("java.io.tmpdir"), "canchavoley-autoprueba.txt")
        val resultados = ejecutar(incluirDibujo = "--sin-dibujo" !in args)
        val texto = informe(resultados)
        print(texto)
        runCatching { archivo.writeText(texto) }
        return if (resultados.all { it.ok }) 0 else 1
    }

    // Throwable a propósito: si falta un módulo de Java llega como NoClassDefFoundError o
    // UnsatisfiedLinkError, y eso es justo lo que la autoprueba debe informar.
    private inline fun comprobar(nombre: String, prueba: () -> String): Resultado =
        try {
            Resultado(nombre, true, prueba())
        } catch (e: Throwable) {
            Resultado(nombre, false, "${e.javaClass.name}: ${e.message}")
        }

    private fun baseLocal(): String {
        val carpeta = Files.createTempDirectory("canchavoley-autoprueba").toFile()
        try {
            val archivo = File(carpeta, NOMBRE_BD)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${archivo.absolutePath}", Properties(), AppDatabase.Schema)
            try {
                val bd = AppDatabase(driver)
                bd.configQueries.guardarConfig("autoprueba", "ñandú")
                val leido = bd.configQueries.obtenerConfig("autoprueba").executeAsOne()
                check(leido == "ñandú") { "se guardó «ñandú» y se leyó «$leido»" }
            } finally {
                driver.close()
            }
            return "esquema v${AppDatabase.Schema.version} creado, escrito y leído"
        } finally {
            carpeta.deleteRecursively()
        }
    }

    private fun preferencias(): String {
        val hayDatos = Preferences.userRoot().nodeExists("canchavoley")
        return if (hayDatos) "lectura correcta (la app ya guardó datos)" else "lectura correcta"
    }

    private fun cifrado(): String {
        if (!esWindows()) return "omitida: DPAPI solo existe en Windows"
        val original = "autoprueba-${System.nanoTime()}".encodeToByteArray()
        val cifrado = CifradorDpapi.cifrar(original)
        check(!cifrado.contentEquals(original)) { "el texto no cambió al cifrarlo" }
        check(CifradorDpapi.descifrar(cifrado).contentEquals(original)) { "el texto descifrado no coincide" }
        return "cifrar y descifrar correcto"
    }

    private fun tls(): String {
        val contexto = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        val cifrados = contexto.createSSLEngine().enabledCipherSuites
        check(cifrados.any { "ECDHE" in it }) { "no hay cifrados ECDHE (falta el proveedor de curvas elípticas)" }
        KeyAgreement.getInstance("ECDH")
        KeyPairGenerator.getInstance("X25519")
        val protocolos = contexto.supportedSSLParameters.protocols
        check("TLSv1.3" in protocolos || "TLSv1.2" in protocolos) { "sin TLS 1.2 ni 1.3" }
        val fabrica = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        fabrica.init(null as KeyStore?)
        val raices = fabrica.trustManagers.filterIsInstance<X509TrustManager>().sumOf { it.acceptedIssuers.size }
        check(raices > 0) { "no hay certificados raíz" }
        return "${protocolos.filter { it.startsWith("TLSv1.") }.max()}, ${cifrados.size} cifrados, $raices certificados raíz"
    }

    @Serializable
    private data class RespuestaPrueba(val ok: Boolean, val texto: String)

    /** Un servidor HTTP mínimo en 127.0.0.1 responde un JSON y el cliente real de la app lo lee. */
    private fun clienteHttp(): String {
        val cuerpo = """{"ok":true,"texto":"Cancha N° 1 · ñandú","campo_nuevo":1}""".encodeToByteArray()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { servidor ->
            servidor.soTimeout = 10_000
            val hilo = thread(isDaemon = true, name = "autoprueba-http") {
                runCatching {
                    servidor.accept().use { conexion ->
                        conexion.soTimeout = 10_000
                        val entrada = conexion.getInputStream().bufferedReader()
                        while (true) {
                            val linea = entrada.readLine() ?: break
                            if (linea.isEmpty()) break
                        }
                        val cabecera = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: ${cuerpo.size}\r\nConnection: close\r\n\r\n"
                        conexion.getOutputStream().apply {
                            write(cabecera.encodeToByteArray())
                            write(cuerpo)
                            flush()
                        }
                    }
                }
            }
            val cliente = HttpClientFactory.create(tokenProvider = { "token-de-prueba" })
            val motor = cliente.engine.javaClass.simpleName
            try {
                val respuesta: RespuestaPrueba = runBlocking {
                    withTimeout(15_000) { cliente.get("http://127.0.0.1:${servidor.localPort}/api/prueba").body() }
                }
                check(respuesta.ok && respuesta.texto == "Cancha N° 1 · ñandú") { "respuesta inesperada: $respuesta" }
            } finally {
                cliente.close()
                hilo.join(2_000)
            }
            return "petición y lectura de JSON correctas ($motor)"
        }
    }

    private fun zonaHoraria(): String {
        val ahora = Fechas.local(Fechas.ahora())
        val minuto = ahora.minute.toString().padStart(2, '0')
        return "${Fechas.zona.id}: ${ahora.date} ${ahora.hour}:$minuto · equipo: ${TimeZone.currentSystemDefault().id}"
    }

    /** Dibuja el logo y un texto con tildes sin abrir ventana (carga la librería gráfica Skia). */
    private fun dibujo(): String {
        val escena = ImageComposeScene(width = 320, height = 160) {
            TemaCanchaVoley {
                Column(Modifier.background(Colores.azulOscuro).padding(12.dp)) {
                    Logo(64.dp)
                    Text("CanchaVoley · ñ á é í ó ú")
                }
            }
        }
        try {
            val imagen = escena.render()
            val png = imagen.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("no se pudo convertir a PNG")
            check(png.size > 200) { "imagen vacía" }
            return "pantalla de prueba dibujada (${imagen.width}×${imagen.height})"
        } finally {
            escena.close()
        }
    }
}
