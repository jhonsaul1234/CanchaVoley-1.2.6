package pe.canchavoley.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import pe.canchavoley.app.dominio.Validaciones
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.ClienteDto
import pe.canchavoley.app.datos.FichaCliente
import pe.canchavoley.app.dominio.WhatsApp
import androidx.compose.ui.platform.LocalUriHandler
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Telefono

/** Clientes: búsqueda por nombre o teléfono, ficha con historial y edición. */
@Composable
fun PantallaClientes(nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    var texto by remember { mutableStateOf("") }
    var lista by remember { mutableStateOf<List<ClienteDto>>(emptyList()) }
    var ficha by remember { mutableStateOf<FichaCliente?>(null) }
    val abrirEnlace = LocalUriHandler.current
    var nuevo by remember { mutableStateOf(false) }
    var recarga by remember { mutableStateOf(0) }

    LaunchedEffect(texto, recarga) { delay(250); lista = repo.buscarClientes(texto) }

    Pantalla {
        val f = ficha
        if (f != null) {
            // Ficha del cliente a pantalla completa (en el celular no queda escondida arriba de la lista).
            TituloPantalla(f.cliente?.nombre ?: "Cliente", f.cliente?.let { Telefono.mostrar(it.telefono) }) {
                f.cliente?.telefono?.let { WhatsApp.enlace(it) }?.let { enlace ->
                    BotonSecundario(onClick = {
                        runCatching { abrirEnlace.openUri(enlace) }.onFailure { nav.mensaje("No se pudo abrir WhatsApp ni el navegador.") }
                    }, icono = Iconos.telefono) { Text("WhatsApp") }
                }
                BotonSecundario(onClick = { ficha = null; recarga++ }, icono = Iconos.atras) { Text("Volver a la lista") }
            }
            f.cliente?.let { c ->
                Tarjeta(titulo = "Datos", icono = Iconos.persona) {
                    EditorCliente(c, onGuardado = { nav.mensaje("Cambios guardados."); ficha = f.copy(cliente = it) }, onCancelar = { ficha = null })
                }
            }
            f.resumen?.let { s ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Indicador("Reservas", "${s.reservas}", modifier = Modifier.widthIn(min = 150.dp), icono = Iconos.calendario)
                    Indicador("Horas jugadas", "${s.horasJugadas}", modifier = Modifier.widthIn(min = 150.dp), icono = Iconos.reloj)
                    Indicador("Canceladas", "${s.canceladas}", color = Colores.gris, modifier = Modifier.widthIn(min = 150.dp))
                    Indicador("No asistió", "${s.noAsistio}", color = if (s.noAsistio >= 2) Colores.ambar else Colores.gris, modifier = Modifier.widthIn(min = 150.dp))
                }
            }
            Tarjeta(titulo = "Historial de reservas", icono = Iconos.lista) {
                if (f.historial.isEmpty()) Vacio("Sin reservas.")
                f.historial.forEach { r ->
                    Column(Modifier.fillMaxWidth().clickable { nav.abrirReserva(r.uuid) }) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(Fechas.rango(r.inicio, r.fin), Modifier.weight(1f)); EtiquetaEstado(r.estado, r.pendientePor)
                        }
                        HorizontalDivider(color = Colores.grisClaro)
                    }
                }
                if (!f.desdeElServidor) Text("Datos guardados en este equipo (sin conexión).", color = Colores.gris)
            }
            return@Pantalla
        }

        TituloPantalla("Clientes", "${lista.size} encontrados") {
            BotonPrincipal(onClick = { nuevo = true }, icono = Iconos.agregar) { Text("Nuevo cliente") }
        }
        if (nuevo) {
            Tarjeta(titulo = "Nuevo cliente", icono = Iconos.persona) {
                EditorCliente(null, onGuardado = { nuevo = false; texto = it.nombre; recarga++; nav.mensaje("Cliente guardado.") }, onCancelar = { nuevo = false })
            }
        }
        Campo(texto, { texto = it }, "Buscar por nombre o teléfono", icono = Iconos.buscar)
        Tarjeta {
            if (lista.isEmpty()) Vacio(if (texto.isBlank()) "Aún no hay clientes en este equipo." else "Sin resultados.", Iconos.personas)
            lista.forEach { c ->
                Column(Modifier.fillMaxWidth().clickable { scope.launch { ficha = repo.fichaPorTelefono(c.telefono) } }) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Iconos.cuenta, null, Modifier.size(32.dp), tint = Colores.azul.copy(alpha = 0.35f))
                        Column(Modifier.weight(1f)) {
                            TextoCorto(c.nombre, peso = FontWeight.SemiBold)
                            Text(Telefono.mostrar(c.telefono), color = Colores.gris)
                        }
                        if (c.esFrecuente) Etiqueta("Frecuente", Colores.azulClaro, Colores.azul)
                        if (c.tieneCuenta) Etiqueta("Usa la app", Colores.verdeClaro, Colores.verde)
                    }
                    HorizontalDivider(color = Colores.grisClaro)
                }
            }
        }
    }
}

@Composable
fun EditorCliente(cliente: ClienteDto?, onGuardado: (ClienteDto) -> Unit, onCancelar: () -> Unit) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    var nombre by remember(cliente) { mutableStateOf(cliente?.nombre.orEmpty()) }
    var telefono by remember(cliente) { mutableStateOf(cliente?.telefono?.let { Telefono.mostrar(it) }.orEmpty()) }
    var notas by remember(cliente) { mutableStateOf(cliente?.notas.orEmpty()) }
    var frecuente by remember(cliente) { mutableStateOf(cliente?.esFrecuente ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var guardando by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilaAdaptable { fila ->
            Campo(nombre, { nombre = it }, "Nombre", if (fila) Modifier.weight(1f) else Modifier.fillMaxWidth(), icono = Iconos.persona)
            Campo(telefono, { telefono = it }, "Celular", if (fila) Modifier.weight(1f) else Modifier.fillMaxWidth(), telefono = true, icono = Iconos.telefono)
        }
        Campo(notas, { notas = it }, "Notas (p. ej. prefiere los sábados)")
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(frecuente, { frecuente = it }); Text("Cliente frecuente") }
        error?.let { Aviso(it, Colores.rojoClaro, Colores.rojo) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonPrincipal(enabled = !guardando, icono = Iconos.listo, onClick = {
                error = Validaciones.cliente(nombre, telefono)
                if (error != null) return@BotonPrincipal
                guardando = true
                scope.launch {
                    val r = if (cliente == null) repo.crearCliente(nombre, telefono, notas.ifBlank { null }, frecuente)
                            else repo.actualizarCliente(cliente, nombre, telefono, notas.ifBlank { null }, frecuente)
                    guardando = false
                    when (r) {
                        is ResultadoAccion.Hecho -> onGuardado(r.valor)
                        is ResultadoAccion.GuardadoSinConexion -> onGuardado(r.valor)
                        is ResultadoAccion.Fallo -> error = r.mensaje
                    }
                }
            }) { Text(if (guardando) "Guardando…" else "Guardar") }
            BotonSecundario(onClick = onCancelar) { Text("Cancelar") }
        }
    }
}
