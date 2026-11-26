@file:OptIn(ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package pe.canchavoley.escritorio

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import pe.canchavoley.app.di.iniciarSincronizacionCanchaVoley
import pe.canchavoley.app.plataforma.Autoprueba
import pe.canchavoley.app.plataforma.GestorPortapapelesSeguro
import pe.canchavoley.app.plataforma.InstanciaUnica
import pe.canchavoley.app.plataforma.PortapapelesSeguro
import pe.canchavoley.app.plataforma.RegistroFallos
import pe.canchavoley.app.plataforma.carpetaDatos
import pe.canchavoley.app.plataforma.perfilDatos
import pe.canchavoley.app.plataforma.guardarDocumento
import pe.canchavoley.app.plataforma.iniciarEscritorio
import pe.canchavoley.app.ui.CanchaVoleyApp
import pe.canchavoley.app.ui.Colores
import pe.canchavoley.app.ui.PantallaFalloAnterior
import pe.canchavoley.app.ui.dibujarPelota
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** En escritorio el backend Laravel corre en esta misma PC: php artisan serve --port=8001 */
private const val SERVIDOR_ESCRITORIO = "http://127.0.0.1:8001"

/**
 * Servidor por defecto: el de esta PC; una copia de prueba (perfil CANCHAVOLEY_PERFIL) puede indicar otro
 * con CANCHAVOLEY_SERVIDOR (por ejemplo http://127.0.0.1:8002) sin tocar la configuración de la instalada.
 */
private fun servidorPorDefecto(): String =
    System.getenv("CANCHAVOLEY_SERVIDOR")?.trim()?.takeIf { perfilDatos() != null && it.startsWith("http") } ?: SERVIDOR_ESCRITORIO

/** Bloqueo de «una sola copia abierta»: se guarda aquí para que viva tanto como la app. */
private var instancia: InstanciaUnica? = null

fun main(args: Array<String>) {
    // «CanchaVoley.exe --autoprueba [informe.txt]»: comprueba el Java del instalador sin abrir ventana.
    if ("--autoprueba" in args) exitProcess(Autoprueba.desdeLineaDeComandos(args.toList()))

    // Una sola copia abierta: dos ventanas usarían la misma base local y la misma cola de sincronización.
    val unica = InstanciaUnica(carpetaDatos()).also { instancia = it }
    if (!unica.reservar()) {
        JOptionPane.showMessageDialog(
            null,
            "CanchaVoley ya está abierto en esta PC.\nBúsquelo en la barra de tareas.",
            "CanchaVoley",
            JOptionPane.INFORMATION_MESSAGE,
        )
        exitProcess(0)
    }

    prepararRegistroFallos()
    val pendiente = informePendiente()
    // Si la vez anterior hubo un error, la sincronización espera a que la usuaria vea el informe.
    runCatching { iniciarEscritorio(servidorPorDefecto = servidorPorDefecto(), iniciarSincronizacion = pendiente == null) }
        .onFailure { error ->
            RegistroFallos.anotar(error, "inicio de la app")
            JOptionPane.showMessageDialog(
                null,
                "CanchaVoley no pudo iniciar: ${error.message}\nEl informe quedó en ${archivoInforme().path}",
                "CanchaVoley",
                JOptionPane.ERROR_MESSAGE,
            )
            exitProcess(1)
        }

    application {
        val estado = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides ManejadorDeErrores) {
            val titulo = "CanchaVoley · Gestión de reservas" + (perfilDatos()?.let { " · copia de prueba ($it)" } ?: "")
            Window(onCloseRequest = ::exitApplication, title = titulo, state = estado, icon = IconoVentana) {
                window.minimumSize = java.awt.Dimension(900, 600)
                // DEF-16: pegar o copiar cuando otro programa tiene ocupado el portapapeles ya no cierra la app.
                val base = LocalClipboard.current
                val portapapeles = remember(base) { PortapapelesSeguro(base) }
                val baseClasico = LocalClipboardManager.current
                val portapapelesClasico = remember(baseClasico) { GestorPortapapelesSeguro(baseClasico) }
                CompositionLocalProvider(LocalClipboard provides portapapeles, LocalClipboardManager provides portapapelesClasico) {
                    var informe by remember { mutableStateOf(pendiente) }
                    val actual = informe
                    if (actual == null) {
                        CanchaVoleyApp()
                    } else {
                        PantallaFalloAnterior(
                            informe = actual,
                            textoCompartir = "Guardar informe en Documentos",
                            onCompartir = { guardarInforme(actual) },
                            onContinuar = {
                                descartarInforme()
                                iniciarSincronizacionCanchaVoley()
                                informe = null
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun archivoInforme(): File = File(carpetaDatos(), RegistroFallos.ARCHIVO)

private fun informePendiente(): String? = archivoInforme().takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

private fun descartarInforme() {
    runCatching { archivoInforme().delete() }
}

/** Todo error no controlado deja un informe en la carpeta de datos de la app (no sale de la PC). */
private fun prepararRegistroFallos() {
    RegistroFallos.version = System.getProperty("jpackage.app-version") ?: "desarrollo"
    RegistroFallos.guardar = { texto -> archivoInforme().writeText(texto) }
    val anterior = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
        RegistroFallos.anotar(error, "hilo ${hilo.name}")
        if (anterior != null) anterior.uncaughtException(hilo, error) else error.printStackTrace()
    }
}

private fun guardarInforme(texto: String) {
    val mensaje = runCatching { "Informe guardado en:\n" + guardarDocumento("Informe de error CanchaVoley.txt", texto.encodeToByteArray()).path }
        .getOrElse { "No se pudo guardar el informe: ${it.message}" }
    JOptionPane.showMessageDialog(null, mensaje, "CanchaVoley", JOptionPane.INFORMATION_MESSAGE)
}

/**
 * Error inesperado en la ventana: se guarda el informe y se avisa en español antes de cerrar
 * (al abrir de nuevo se muestra el informe). Reemplaza el aviso en inglés de Compose.
 */
private object ManejadorDeErrores : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window) = WindowExceptionHandler { error ->
        RegistroFallos.anotar(error, "ventana")
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                window.takeIf { it.isDisplayable },
                "CanchaVoley encontró un error inesperado y se cerrará.\n" +
                    "Tus datos guardados no se pierden. Al abrirla de nuevo verás el informe del error.",
                "CanchaVoley",
                JOptionPane.ERROR_MESSAGE,
            )
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        }
    }
}

/** Ícono de la ventana y de la barra de tareas: la pelota del logo en blanco sobre el azul de la marca. */
private object IconoVentana : Painter() {
    override val intrinsicSize = Size(256f, 256f)
    override fun DrawScope.onDraw() {
        drawRoundRect(Colores.azul, cornerRadius = CornerRadius(size.minDimension * 0.22f))
        dibujarPelota(Color.White, lado = size.minDimension * 0.91f)
    }
}
