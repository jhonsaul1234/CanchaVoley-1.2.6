@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.ReservaLocal
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.plataforma.rememberLectorQr
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.datos.SesionDto
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.DisponibilidadLocal
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.ReglasReserva

/** Panel principal del personal: lo de hoy, solicitudes por aprobar, caja y verificación por QR. */
@Composable
fun PantallaPanel(sesion: SesionDto, nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    val ahora = rememberReloj()
    val hoy = Fechas.local(ahora).date          // se actualiza solo si la app queda abierta pasada la medianoche
    val catalogo by repo.catalogo().collectAsState(null)
    val desde = remember(hoy) { Fechas.instante(hoy, LocalTime(0, 0)) }
    val hasta = remember(hoy) { Fechas.instante(hoy.plus(DatePeriod(days = 30)), LocalTime(0, 0)) }
    val reservas by remember(desde, hasta) { repo.reservasEntre(desde, hasta) }.collectAsState(emptyList())
    val pendientes by repo.operacionesPendientes().collectAsState(0L)
    var cajaHoy by remember { mutableStateOf<Long?>(null) }
    var codigo by remember { mutableStateOf("") }

    // La caja se recalcula al cambiar cualquier reserva (p. ej. su estado de pago) o la cola de envío.
    LaunchedEffect(hoy, reservas, pendientes) { cajaHoy = repo.caja(hoy).total }

    val manana = Fechas.instante(hoy.plus(DatePeriod(days = 1)), LocalTime(0, 0))
    val deHoy = reservas.filter { it.inicio < manana && it.ocupa }
    val porAprobar = reservas.filter { it.dto.estado == "pendiente" && it.dto.pendientePor == "aprobacion" }
    val sinSync = reservas.filter { it.sinSincronizar }
    val cat = catalogo
    val minutosAbiertos = cat?.canchas?.filter { it.activa }?.sumOf { c -> DisponibilidadLocal.turnos(cat, c.id, hoy).sumOf { it.minutos } } ?: 0
    val minutosReservados = deHoy.filter { it.dto.estado == "confirmada" }.sumOf { it.dto.duracionMin }

    Pantalla {
        TituloPantalla("Hola, ${sesion.usuario.nombre.substringBefore(' ')}", Fechas.fechaLarga(hoy).replaceFirstChar { it.uppercase() }) {
            BotonPrincipal(onClick = { nav.ir(Destino.LLAMADA) }, icono = Iconos.telefono) { Text("Nueva reserva") }
        }
        if (cat != null && cat.canchas.isNotEmpty() && cat.horarios.isEmpty()) {
            Aviso(if (sesion.esAdmin) "Falta configurar el horario de atención: sin él no se pueden registrar reservas. Ve a «Tarifas y horarios»."
                  else "La administradora aún no configuró el horario de atención.")
        }
        if (cat != null && cat.horarios.isNotEmpty() && cat.tarifas.isEmpty()) {
            Aviso("No hay tarifas configuradas: al registrar una reserva se pedirá el precio acordado.", Colores.azulClaro, Colores.azul)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Indicador("Reservas de hoy", "${deHoy.size}", "${Fechas.duracion(deHoy.sumOf { it.dto.duracionMin })} en total", modifier = Modifier.widthIn(min = 200.dp), icono = Iconos.calendario)
            Indicador("Por aprobar", "${porAprobar.size}", "solicitudes desde la app", if (porAprobar.isEmpty()) Colores.gris else Colores.ambar, Modifier.widthIn(min = 200.dp), icono = Iconos.campana)
            Indicador("Caja de hoy", cajaHoy?.let { Dinero.soles(it) } ?: "—", "efectivo + Yape + Plin", Colores.verde, Modifier.widthIn(min = 200.dp), icono = Iconos.dinero)
            Indicador("Ocupación de hoy", if (minutosAbiertos > 0) "${minutosReservados * 100 / minutosAbiertos}%" else "—",
                if (minutosAbiertos > 0) "${Fechas.duracion(minutosReservados)} de ${Fechas.duracion(minutosAbiertos)}" else "sin horario", modifier = Modifier.widthIn(min = 200.dp), icono = Iconos.grafico)
        }
        if (sinSync.isNotEmpty()) {
            Aviso("${sinSync.size} reserva(s) registradas sin conexión esperan la validación del servidor. No las confirmes al cliente hasta que se sincronicen.",
                Colores.azulGrisClaro, Colores.gris)
        }
        val verificar: (String) -> Unit = { texto ->
            scope.launch {
                when (val r = repo.verificarQr(texto)) {
                    is ResultadoAccion.Hecho -> { codigo = ""; nav.abrirReserva(r.valor.uuid) }
                    is ResultadoAccion.GuardadoSinConexion -> { codigo = ""; nav.mensaje("Verificado con los datos guardados (sin conexión)."); nav.abrirReserva(r.valor.uuid) }
                    is ResultadoAccion.Fallo -> nav.mensaje(r.mensaje)
                }
            }
        }
        val leerConCamara = rememberLectorQr { texto -> codigo = texto; verificar(texto) }
        Tarjeta(titulo = "Llegada del cliente: verificar código QR", icono = Iconos.qr) {
            Text(if (leerConCamara != null) "Escanea el QR con la cámara o escribe el código de la reserva."
                 else "Escanea el QR con el lector USB (o escribe el código) y presiona Verificar.", color = Colores.gris)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Campo(codigo, { codigo = it }, "Código de la reserva", Modifier.weight(1f), alEnviar = { if (codigo.isNotBlank()) verificar(codigo) })
                BotonSecundario(enabled = codigo.isNotBlank(), onClick = { verificar(codigo) }) { Text("Verificar") }
            }
            if (leerConCamara != null) {
                BotonPrincipal(onClick = leerConCamara, modifier = Modifier.fillMaxWidth(), icono = Iconos.qr) { Text("Escanear QR con la cámara") }
            }
        }
        if (porAprobar.isNotEmpty()) {
            Tarjeta(titulo = "Solicitudes por aprobar (${porAprobar.size})", icono = Iconos.campana) {
                porAprobar.forEach { FilaReserva(it, nav) }
                if (!sesion.esAdmin) Text("Solo la administradora aprueba o rechaza solicitudes.", color = Colores.gris)
            }
        }
        Tarjeta(titulo = "Próximas reservas", icono = Iconos.lista, accion = { BotonTexto(onClick = { nav.ir(Destino.AGENDA) }, icono = Iconos.calendario) { Text("Ver agenda") } }) {
            val proximas = reservas.filter { it.ocupa && it.fin > ahora }.take(12)
            if (proximas.isEmpty()) Vacio("No hay reservas próximas.", Iconos.calendario) else proximas.forEach { FilaReserva(it, nav, conFecha = true) }
        }
    }
}

@Composable
fun FilaReserva(r: ReservaLocal, nav: Navegacion, conFecha: Boolean = false) {
    Column(Modifier.fillMaxWidth().clickable { nav.abrirReserva(r.dto.uuid) }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.widthIn(min = 110.dp)) {
                if (conFecha) Text(Fechas.fechaCorta(Fechas.local(r.inicio).date), color = Colores.gris)
                Text("${Fechas.hora(r.inicio)}–${Fechas.hora(r.fin)}", fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                TextoCorto(r.dto.clienteNombre ?: "Cliente", peso = FontWeight.Medium)
                Text("${Fechas.duracion(r.dto.duracionMin)} · ${textoOrigen(r.dto.origen)} · ${r.dto.precioFinal?.let { Dinero.soles(it) } ?: "precio por definir"}" +
                    (r.dto.estadoPago?.let { " · " + textoEstadoPago(it) } ?: ""), color = Colores.gris)
            }
            EtiquetaEstado(r.dto.estado, r.dto.pendientePor, r.sinSincronizar, ReglasReserva.plazoVencido(r.dto, Fechas.ahora()))
        }
        HorizontalDivider()
    }
}
