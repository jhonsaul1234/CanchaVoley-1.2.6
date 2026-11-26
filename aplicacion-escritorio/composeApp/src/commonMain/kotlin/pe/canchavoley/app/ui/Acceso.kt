package pe.canchavoley.app.ui

import pe.canchavoley.app.plataforma.RegistroFallos
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.GestorSesion
import pe.canchavoley.app.datos.Resultado
import pe.canchavoley.app.dominio.Validaciones
import pe.canchavoley.app.plataforma.ConfigLocal
import pe.canchavoley.app.plataforma.MonitorConectividad

/** Inicio de sesión (personal y clientes) y registro de clientes desde la app. */
@Composable
fun PantallaAcceso(sesionVencida: Boolean = false) {
    val gestor = koinInject<GestorSesion>()
    val config = koinInject<ConfigLocal>()
    val monitor = koinInject<MonitorConectividad>()
    val scope = rememberCoroutineScope()
    var registro by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var verClave by remember { mutableStateOf(false) }
    var nombre by remember { mutableStateOf("") }
    var telefono by remember { mutableStateOf("") }
    var servidor by remember { mutableStateOf(config.servidor()) }
    var verServidor by remember { mutableStateOf(false) }
    var ocupado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun enviar() {
        if (ocupado) return
        error = if (registro) Validaciones.registroCliente(nombre, email, clave, telefono) else Validaciones.inicioSesion(email, clave)
        if (error == null) error = Validaciones.servidor(servidor)
        if (error != null) return
        config.guardarServidor(servidor)
        monitor.revisarAhora()
        ocupado = true
        scope.launch {
            val r = try {
                if (registro) gestor.registrar(nombre, email, clave, telefono) else gestor.iniciar(email, clave)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RegistroFallos.anotar(e, if (registro) "crear cuenta" else "iniciar sesión")
                Resultado.Error(0, "No se pudo completar por un error inesperado: " + (e.message ?: e::class.simpleName ?: "desconocido"))
            }
            ocupado = false
            error = when (r) {
                is Resultado.Ok -> null
                is Resultado.Error -> r.mensaje
                Resultado.SinConexion -> "No hay conexión con el servidor (${config.servidor()}). Revisa la red Wi-Fi o la dirección del servidor."
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Logo(64.dp, trazo = Colores.azul, relleno = Colores.amarillo)
                Text("CanchaVoley", fontSize = 30.sp, style = MaterialTheme.typography.headlineSmall, color = Colores.azul)
                Text("Reservas de la cancha · con y sin internet", color = Colores.gris, textAlign = TextAlign.Center)
            }
            if (sesionVencida) {
                Aviso("Tu sesión terminó (se cerró en otro equipo o venció). Vuelve a entrar: lo guardado en este equipo se conserva.",
                    Colores.azulClaro, Colores.azul)
            }
            Tarjeta(titulo = if (registro) "Crear cuenta de cliente" else "Iniciar sesión") {
                if (registro) {
                    Campo(nombre, { nombre = it }, "Nombre completo", icono = Iconos.persona)
                    Campo(telefono, { telefono = it }, "Celular", telefono = true, icono = Iconos.telefono,
                        ayuda = "Con este número te identifica el negocio")
                }
                Campo(email, { email = it.trim() }, "Correo")
                Campo(clave, { clave = it }, "Contraseña", clave = true, verClave = verClave,
                    ayuda = if (registro) "Mínimo 8 caracteres" else null, alEnviar = ::enviar)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(verClave, { verClave = it })
                    Text("Mostrar contraseña", color = Colores.gris)
                }
                error?.let { Aviso(it, Colores.rojoClaro, Colores.rojo) }
                BotonPrincipal(onClick = ::enviar, enabled = !ocupado, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ocupado) "Un momento…" else if (registro) "Crear cuenta" else "Entrar")
                }
                BotonTexto(onClick = { registro = !registro; error = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (registro) "Ya tengo cuenta: iniciar sesión" else "¿Eres cliente? Crea tu cuenta")
                }
            }
            BotonTexto(onClick = { verServidor = !verServidor }, icono = Iconos.ajustes, color = Colores.gris) {
                Text(if (verServidor) "Ocultar servidor" else "Servidor: ${config.servidor()}")
            }
            if (verServidor) {
                Campo(servidor, { servidor = it }, "Dirección del servidor", error = Validaciones.servidor(servidor).takeIf { servidor.isNotBlank() },
                    ayuda = "En el celular usa la IP de la PC del negocio, p. ej. http://192.168.1.10:8001")
            }
            Text("Si no cierras sesión, la app abre directamente aunque no haya internet y envía lo pendiente al reconectarse.",
                color = Colores.gris, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
