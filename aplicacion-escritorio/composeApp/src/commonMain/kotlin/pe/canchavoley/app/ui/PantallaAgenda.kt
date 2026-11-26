@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import pe.canchavoley.app.dominio.Semana
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlinx.datetime.LocalTime
import kotlinx.datetime.DatePeriod
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.ClienteDto
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ReservaLocal
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.datos.SesionDto
import pe.canchavoley.app.datos.VistaDisponibilidad
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.ReglasReserva
import pe.canchavoley.app.dominio.Intervalo
import pe.canchavoley.app.dominio.Telefono
import pe.canchavoley.app.dominio.Validaciones
import kotlin.time.Instant

/** Calendario del día: horario de atención con reservas, cierres y tiempo libre, en orden. */
@Composable
fun PantallaAgenda(sesion: SesionDto, nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val catalogo by repo.catalogo().collectAsState(repo.catalogoActual())
    val canchas = catalogo?.canchas?.filter { it.activa }.orEmpty()
    var canchaId by remember { mutableStateOf(canchas.firstOrNull()?.id) }
    var fecha by remember { mutableStateOf(Fechas.hoy()) }
    var semanal by remember { mutableStateOf(false) }
    val reservas by repo.reservasDelDia(fecha).collectAsState(emptyList())
    var vista by remember { mutableStateOf<VistaDisponibilidad?>(null) }
    var nueva by remember { mutableStateOf<Intervalo?>(null) }

    LaunchedEffect(canchas.firstOrNull()?.id) { if (canchaId == null) canchaId = canchas.firstOrNull()?.id }
    LaunchedEffect(canchaId, fecha, reservas.size, reservas.map { it.dto.estado }) { canchaId?.let { vista = repo.disponibilidad(it, fecha) } }

    Pantalla {
        TituloPantalla("Calendario de reservas", if (!semanal && vista?.desdeElServidor == false) "Mostrando los datos guardados en este equipo" else null) {
            if (semanal) SelectorSemana(fecha) { fecha = it } else SelectorFecha(fecha, { fecha = it; nueva = null })
        }
        Opciones(listOf(false to "Día", true to "Semana"), semanal, { semanal = it; nueva = null })
        if (canchas.size > 1) Opciones(canchas.map { it.id to it.nombre }, canchaId, { canchaId = it; nueva = null })
        if (semanal) {
            VistaSemanal(Semana.lunes(fecha), canchaId, nav) { dia -> fecha = dia; semanal = false }
            return@Pantalla
        }
        val v = vista
        if (v == null) { Cargando(); return@Pantalla }
        if (!v.horarioConfigurado) { Aviso("La cancha no tiene horario de atención configurado."); return@Pantalla }
        if (v.turnos.isEmpty()) { Vacio("Cerrado este día.", Iconos.calendario); return@Pantalla }

        val delDia = reservas.filter { it.dto.canchaId == canchaId }
        val cierres = catalogo?.bloqueos.orEmpty().filter { !it.eliminado && (it.canchaId == null || it.canchaId == canchaId) }
        fun motivoCierre(inicio: Instant, fin: Instant): String? = cierres.firstOrNull { b ->
            val bi = Fechas.parse(b.inicio)
            val bf = Fechas.parse(b.fin)
            bi != null && bf != null && bi < fin && inicio < bf
        }?.motivo

        // Línea de tiempo del día: libres, reservas y cierres en orden.
        data class Bloque(val inicio: Instant, val fin: Instant, val reserva: ReservaLocal?, val libre: Boolean)
        val bloques = buildList {
            v.libres.forEach { add(Bloque(it.inicio, it.fin, null, true)) }
            delDia.filter { it.ocupa }.forEach { add(Bloque(it.inicio, it.fin, it, false)) }
            v.ocupados.filter { o -> delDia.none { it.ocupa && it.inicio == o.inicio && it.fin == o.fin } && v.libres.none { it.inicio == o.inicio } }
                .forEach { add(Bloque(it.inicio, it.fin, null, false)) }
        }.sortedBy { it.inicio }

        Tarjeta(titulo = v.turnos.joinToString("  ·  ") { "Atención ${Fechas.hora(it.inicio)}–${Fechas.hora(it.fin)}" }, icono = Iconos.reloj) {
            if (sesion.esPersonal) Text("Toca un espacio libre para reservarlo o una reserva para ver su detalle.", color = Colores.gris)
            bloques.forEach { b ->
                val (fondo, color) = when {
                    b.libre -> Colores.verdeClaro to Colores.verde
                    b.reserva == null -> Colores.grisClaro to Colores.gris
                    else -> estiloEstado(b.reserva.dto.estado, b.reserva.dto.pendientePor).let { it.fondo to it.color }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(fondo)
                    .clickable(enabled = b.libre && sesion.esPersonal || b.reserva != null) {
                        if (b.reserva != null) nav.abrirReserva(b.reserva.dto.uuid)
                        else nueva = if (nueva?.inicio == b.inicio) null else Intervalo(b.inicio, b.fin)
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(64.dp)) {
                        Text(Fechas.hora(b.inicio), fontWeight = FontWeight.Bold, color = color)
                        Text(Fechas.hora(b.fin), color = color)
                    }
                    Column(Modifier.weight(1f)) {
                        when {
                            b.libre -> Text("Libre · ${Fechas.duracion(((b.fin - b.inicio).inWholeMinutes).toInt())}", color = color, fontWeight = FontWeight.SemiBold)
                            b.reserva == null -> {
                                Text("Cerrado", color = color, fontWeight = FontWeight.SemiBold)
                                motivoCierre(b.inicio, b.fin)?.let { Text(it, color = Colores.gris) }
                            }
                            else -> {
                                TextoCorto(b.reserva.dto.clienteNombre ?: "Cliente", peso = FontWeight.SemiBold, color = Colores.texto)
                                Text("${Fechas.duracion(b.reserva.dto.duracionMin)} · ${b.reserva.dto.precioFinal?.let { Dinero.soles(it) } ?: "precio por definir"} · ${textoEstadoPago(b.reserva.dto.estadoPago)}",
                                    color = Colores.gris)
                            }
                        }
                    }
                    if (b.reserva != null) EtiquetaEstado(b.reserva.dto.estado, b.reserva.dto.pendientePor, b.reserva.sinSincronizar,
                        ReglasReserva.plazoVencido(b.reserva.dto, Fechas.ahora()))
                    else if (b.libre && sesion.esPersonal) Icon(Iconos.agregar, "Reservar", Modifier.size(22.dp), tint = color)
                }
                // El formulario aparece justo debajo del espacio tocado (en el celular no hay que subir).
                val nuevaSel = nueva
                if (b.libre && nuevaSel != null && nuevaSel.inicio == b.inicio) {
                    Tarjeta(titulo = "Nueva reserva desde las ${Fechas.hora(nuevaSel.inicio)}",
                        accion = { BotonTexto(onClick = { nueva = null }, icono = Iconos.cerrar) { Text("Cerrar") } }) {
                        SelectorClienteYReserva(fecha, Fechas.hora(nuevaSel.inicio), canchaId, nav) { nueva = null }
                    }
                }
            }
        }
        val otras = delDia.filter { !it.ocupa }
        if (otras.isNotEmpty()) {
            Tarjeta(titulo = "Canceladas o rechazadas este día") { otras.forEach { FilaReserva(it, nav) } }
        }
    }
}

/** Semana anterior / siguiente y «Esta semana». */
@Composable
private fun SelectorSemana(fecha: LocalDate, onCambio: (LocalDate) -> Unit) {
    val lunes = Semana.lunes(fecha)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BotonIcono(Iconos.izquierda, "Semana anterior", onClick = { onCambio(fecha.minus(DatePeriod(days = 7))) })
        Text(Semana.titulo(lunes), Modifier.widthIn(min = 150.dp).padding(horizontal = 4.dp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        BotonIcono(Iconos.derecha, "Semana siguiente", onClick = { onCambio(fecha.plus(DatePeriod(days = 7))) })
        if (lunes != Semana.lunes(Fechas.hoy())) BotonTexto(onClick = { onCambio(Fechas.hoy()) }) { Text("Esta semana") }
    }
}

/**
 * Vista semanal (lunes a domingo) con las reservas activas guardadas en el equipo; un toque abre
 * la reserva y «Ver día» abre ese día en el calendario.
 */
@Composable
private fun VistaSemanal(lunes: LocalDate, canchaId: Long?, nav: Navegacion, alElegirDia: (LocalDate) -> Unit) {
    val repo = koinInject<Repositorio>()
    val flujo = remember(lunes) {
        repo.reservasEntre(Fechas.instante(lunes, LocalTime(0, 0)), Fechas.instante(lunes.plus(DatePeriod(days = 7)), LocalTime(0, 0)))
    }
    val reservas by flujo.collectAsState(emptyList())
    val activas = reservas.filter { it.ocupa && (canchaId == null || it.dto.canchaId == canchaId) }
    val hoy = Fechas.hoy()
    Text("${activas.size} reserva(s) · ${Fechas.duracion(activas.sumOf { it.dto.duracionMin })} reservadas en la semana", color = Colores.gris)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Semana.agrupar(lunes, activas) { it.inicio }.forEach { (dia, delDia) ->
            val titulo = Fechas.nombreDia(dia.dayOfWeek).replaceFirstChar { it.uppercase() } + " ${dia.day}" + if (dia == hoy) " · hoy" else ""
            Tarjeta(Modifier.widthIn(min = 250.dp, max = 360.dp), titulo = titulo,
                accion = { BotonTexto(onClick = { alElegirDia(dia) }, icono = Iconos.calendario) { Text("Ver día") } }) {
                if (delDia.isEmpty()) Text("Sin reservas", color = Colores.gris)
                delDia.forEach { r ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { nav.abrirReserva(r.dto.uuid) }.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${Fechas.hora(r.inicio)}–${Fechas.hora(r.fin)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            EtiquetaEstado(r.dto.estado, r.dto.pendientePor, r.sinSincronizar, ReglasReserva.plazoVencido(r.dto, Fechas.ahora()))
                        }
                        TextoCorto(r.dto.clienteNombre ?: "Cliente", color = Colores.gris)
                    }
                }
                if (delDia.isNotEmpty()) Text("${delDia.size} · ${Fechas.duracion(delDia.sumOf { it.dto.duracionMin })}", color = Colores.gris, fontSize = 13.sp)
            }
        }
    }
}

/** Buscar o registrar al cliente por teléfono y luego el formulario de reserva (desde la agenda). */
@Composable
fun SelectorClienteYReserva(fecha: LocalDate, hora: String, canchaId: Long?, nav: Navegacion, onListo: () -> Unit) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    var telefono by remember { mutableStateOf("") }
    var nombre by remember { mutableStateOf("") }
    var cliente by remember { mutableStateOf<ClienteDto?>(null) }
    var buscado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun buscar() {
        error = if (Telefono.valido(telefono)) null else "Escribe un celular válido (9 dígitos)."
        if (error != null) return
        scope.launch { val f = repo.fichaPorTelefono(telefono); cliente = f.cliente; buscado = true }
    }
    val c = cliente
    if (c == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Campo(telefono, { telefono = it; buscado = false; error = null }, "Celular del cliente", Modifier.weight(1f), telefono = true,
                icono = Iconos.telefono, error = error, alEnviar = ::buscar)
            BotonSecundario(onClick = ::buscar, icono = Iconos.buscar) { Text("Buscar") }
        }
        if (buscado) {
            Text("Cliente nuevo: escribe su nombre", color = Colores.gris)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Campo(nombre, { nombre = it }, "Nombre", Modifier.weight(1f), icono = Iconos.persona)
                BotonPrincipal(enabled = nombre.isNotBlank(), onClick = {
                    error = Validaciones.cliente(nombre, telefono)
                    if (error != null) return@BotonPrincipal
                    scope.launch {
                        when (val r = repo.crearCliente(nombre, telefono, null)) {
                            is ResultadoAccion.Hecho -> cliente = r.valor
                            is ResultadoAccion.GuardadoSinConexion -> cliente = r.valor
                            is ResultadoAccion.Fallo -> nav.mensaje(r.mensaje)
                        }
                    }
                }) { Text("Guardar") }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Iconos.persona, null, Modifier.size(20.dp), tint = Colores.azul)
            Text(c.nombre, fontWeight = FontWeight.SemiBold)
        }
        FormularioReserva(c, esPersonal = true, fechaInicial = fecha, horaInicial = hora, canchaInicial = canchaId, onRegistrada = { r, off, serie ->
            nav.mensaje(serie ?: if (off) "Reserva guardada sin conexión (por validar)." else "Reserva registrada: ${estiloEstado(r.estado, r.pendientePor).texto}.")
            onListo()
        })
    }
}
