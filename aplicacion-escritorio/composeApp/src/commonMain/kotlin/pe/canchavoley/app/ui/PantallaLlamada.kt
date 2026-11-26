package pe.canchavoley.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.ClienteDto
import pe.canchavoley.app.datos.FichaCliente
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Telefono

/**
 * Modo llamada: pensado para registrar una reserva mientras se habla por teléfono.
 * 1) número → 2) quién es y su historial → 3) día, hora y duración con horarios libres → 4) registrar.
 */
@Composable
fun PantallaLlamada(nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    var telefono by remember { mutableStateOf("") }
    var ficha by remember { mutableStateOf<FichaCliente?>(null) }
    var buscando by remember { mutableStateOf(false) }
    var nombreNuevo by remember { mutableStateOf("") }
    var cliente by remember { mutableStateOf<ClienteDto?>(null) }
    var registrada by remember { mutableStateOf<Pair<ReservaDto, Boolean>?>(null) }
    var pagando by remember { mutableStateOf<ReservaDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reiniciar() { telefono = ""; ficha = null; cliente = null; nombreNuevo = ""; registrada = null; error = null }
    fun buscar() {
        if (!Telefono.valido(telefono)) { error = "Escribe un número válido (9 dígitos)."; return }
        error = null; buscando = true
        scope.launch { val f = repo.fichaPorTelefono(telefono); ficha = f; cliente = f.cliente; buscando = false }
    }
    // Al completar los 9 dígitos de un celular se busca solo (no hace falta presionar «Buscar»).
    LaunchedEffect(telefono) {
        val digitos = telefono.filter { it.isDigit() }
        if (ficha == null && !buscando && (digitos.length == 9 || (digitos.length == 11 && digitos.startsWith("51")))) {
            delay(350)
            buscar()
        }
    }

    pagando?.let { r -> DialogoPago(r, onCerrar = { pagando = null }, onListo = { nav.mensaje(it); pagando = null }, tipos = listOf("adelanto", "pago")) }

    Pantalla {
        TituloPantalla("Modo llamada", "Registra una reserva mientras atiendes el teléfono") {
            BotonSecundario(onClick = ::reiniciar, icono = Iconos.agregar) { Text("Nueva llamada") }
        }
        val hecha = registrada
        if (hecha != null) {
            val (r, offline) = hecha
            Tarjeta(titulo = if (offline) "Reserva guardada sin conexión" else "Reserva registrada") {
                if (offline) Aviso("Se validará con el servidor al volver internet. Dile al cliente que su reserva queda por confirmar; si el horario ya estaba tomado, el sistema te avisará.")
                FilaDato("Cliente", r.clienteNombre ?: "—")
                FilaDato("Fecha y hora", Fechas.rango(r.inicio, r.fin))
                FilaDato("Estado", estiloEstado(r.estado, r.pendientePor).texto, destacado = true)
                FilaDato("Precio", r.precioFinal?.let { Dinero.soles(it) } ?: "por definir")
                r.adelantoRequerido?.let { FilaDato("Adelanto requerido", Dinero.soles(it)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotonPrincipal(onClick = { pagando = r }, icono = Iconos.dinero) { Text("Registrar adelanto o pago") }
                    BotonSecundario(onClick = { nav.abrirReserva(r.uuid) }) { Text("Ver detalle") }
                    BotonSecundario(onClick = ::reiniciar, icono = Iconos.agregar) { Text("Nueva llamada") }
                }
            }
            return@Pantalla
        }
        Tarjeta(titulo = "1. ¿Quién llama?", icono = Iconos.telefono) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Campo(telefono, { telefono = it; ficha = null; cliente = null }, "Número de celular", Modifier.weight(1f).widthIn(max = 320.dp),
                    telefono = true, error = error, icono = Iconos.buscar, alEnviar = ::buscar)
                BotonPrincipal(onClick = ::buscar, enabled = !buscando) { Text(if (buscando) "Buscando…" else "Buscar") }
            }
            val f = ficha
            if (f != null && f.cliente != null) {
                val c = f.cliente
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.nombre, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (c.esFrecuente) Etiqueta("Cliente frecuente", Colores.azulClaro, Colores.azul)
                    if (c.tieneCuenta) Etiqueta("Usa la app", Colores.verdeClaro, Colores.verde)
                }
                Text(Telefono.mostrar(c.telefono), color = Colores.gris)
                f.resumen?.let { s ->
                    Text("${s.reservas} reservas · ${s.horasJugadas} h jugadas · ${s.canceladas} canceladas · ${s.noAsistio} sin asistir", color = Colores.gris)
                    if (s.noAsistio >= 2) Aviso("Ojo: este cliente no asistió ${s.noAsistio} veces.")
                }
                c.notas?.takeIf { it.isNotBlank() }?.let { Aviso("Nota: $it", Colores.azulClaro, Colores.azul) }
                if (f.historial.isNotEmpty()) {
                    Text("Últimas reservas", fontWeight = FontWeight.SemiBold)
                    f.historial.take(4).forEach { r ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(Fechas.rango(r.inicio, r.fin)); EtiquetaEstado(r.estado, r.pendientePor)
                        }
                    }
                }
                if (!f.desdeElServidor) Text("Datos guardados en este equipo (sin conexión).", color = Colores.gris)
            } else if (f != null) {
                Text("Cliente nuevo (${Telefono.mostrar(f.telefono)})", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Campo(nombreNuevo, { nombreNuevo = it }, "Nombre del cliente", Modifier.weight(1f))
                    BotonPrincipal(enabled = nombreNuevo.isNotBlank(), onClick = {
                        scope.launch {
                            when (val r = repo.crearCliente(nombreNuevo, telefono, null)) {
                                is ResultadoAccion.Hecho -> { cliente = r.valor; ficha = f.copy(cliente = r.valor); r.aviso?.let(nav.mensaje) }
                                is ResultadoAccion.GuardadoSinConexion -> { cliente = r.valor; ficha = f.copy(cliente = r.valor); nav.mensaje("Cliente guardado sin conexión.") }
                                is ResultadoAccion.Fallo -> nav.mensaje(r.mensaje)
                            }
                        }
                    }) { Text("Guardar cliente") }
                }
            }
        }
        if (cliente != null) {
            Tarjeta(titulo = "2. Día, duración y hora", icono = Iconos.calendario) {
                FormularioReserva(cliente, esPersonal = true, onRegistrada = { r, off, serie -> registrada = r to off; serie?.let(nav.mensaje) })
            }
        }
    }
}
