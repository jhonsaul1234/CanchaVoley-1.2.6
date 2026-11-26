package pe.canchavoley.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import pe.canchavoley.app.dominio.Validaciones
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.Resultado
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.datos.SesionDto
import pe.canchavoley.app.datos.UsuarioPersonalDto

/** Cuentas del personal (administradora y recepción). Los clientes crean su cuenta desde la app. */
@Composable
fun PantallaUsuarios(sesion: SesionDto, nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    var lista by remember { mutableStateOf<List<UsuarioPersonalDto>?>(null) }
    var recarga by remember { mutableIntStateOf(0) }
    var nombre by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var rol by remember { mutableStateOf("recepcion") }
    var claveDe by remember { mutableStateOf<UsuarioPersonalDto?>(null) }
    var desactivar by remember { mutableStateOf<UsuarioPersonalDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recarga) { lista = (repo.usuarios() as? Resultado.Ok)?.valor }

    claveDe?.let { u ->
        DialogoTexto("Nueva contraseña para ${u.nombre}", "Contraseña (mínimo 8 caracteres)", "Cambiar", minimo = 8,
            onCerrar = { claveDe = null }, onConfirmar = { c -> claveDe = null; scope.launch { resultado(repo.actualizarUsuario(u.id, mapOf("password" to c)), nav) { recarga++ } } })
    }
    desactivar?.let { u ->
        DialogoConfirmar("Desactivar cuenta", "${u.nombre} ya no podrá entrar al sistema (sus registros se conservan). Puedes volver a activarla cuando quieras.",
            "Desactivar", peligro = true, onCerrar = { desactivar = null },
            onConfirmar = { desactivar = null; scope.launch { resultado(repo.actualizarUsuario(u.id, mapOf("activo" to false)), nav) { recarga++ } } })
    }
    Pantalla {
        TituloPantalla("Usuarios", "Personal con acceso al sistema")
        Tarjeta(titulo = "Nueva cuenta", icono = Iconos.agregar) {
            FilaAdaptable { fila ->
                Campo(nombre, { nombre = it }, "Nombre", if (fila) Modifier.weight(1f) else Modifier.fillMaxWidth(), icono = Iconos.persona)
                Campo(email, { email = it.trim() }, "Correo", if (fila) Modifier.weight(1f) else Modifier.fillMaxWidth())
            }
            Campo(clave, { clave = it }, "Contraseña inicial", clave = true, ayuda = "Mínimo 8 caracteres; la persona puede pedir que se la cambies")
            Opciones(listOf("recepcion" to "Recepción", "admin" to "Administradora"), rol, { rol = it })
            error?.let { Aviso(it, Colores.rojoClaro, Colores.rojo) }
            BotonPrincipal(icono = Iconos.listo, onClick = {
                error = when {
                    nombre.trim().length < 3 -> "Escribe el nombre completo."
                    else -> Validaciones.correo(email) ?: Validaciones.contrasena(clave)
                }
                if (error != null) return@BotonPrincipal
                scope.launch { resultado(repo.crearUsuario(mapOf("nombre" to nombre.trim(), "email" to email, "password" to clave, "rol" to rol)), nav) { nombre = ""; email = ""; clave = ""; recarga++ } }
            }) { Text("Crear cuenta") }
        }
        Tarjeta(titulo = "Personal", icono = Iconos.personas) {
            val l = lista
            if (l == null) Aviso("Necesitas conexión para administrar usuarios.", Colores.azulGrisClaro, Colores.gris)
            l?.forEach { u ->
                Column {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(u.nombre, fontWeight = FontWeight.SemiBold)
                                Text("${u.email} · ${textoRol(u.rol)}", color = Colores.gris)
                            }
                            if (!u.activo) Etiqueta("Desactivada", Colores.grisClaro, Colores.gris)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (u.id != sesion.usuario.id) {
                                if (u.activo) BotonTexto(onClick = { desactivar = u }, color = Colores.rojo) { Text("Desactivar") }
                                else BotonTexto(onClick = { scope.launch { resultado(repo.actualizarUsuario(u.id, mapOf("activo" to true)), nav) { recarga++ } } }) { Text("Activar") }
                            }
                            BotonTexto(onClick = { claveDe = u }, icono = Iconos.editar) { Text("Cambiar contraseña") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private suspend fun resultado(r: ResultadoAccion<*>, nav: Navegacion, ok: () -> Unit) {
    when (r) {
        is ResultadoAccion.Fallo -> nav.mensaje(r.mensaje)
        else -> { nav.mensaje("Guardado."); ok() }
    }
}
