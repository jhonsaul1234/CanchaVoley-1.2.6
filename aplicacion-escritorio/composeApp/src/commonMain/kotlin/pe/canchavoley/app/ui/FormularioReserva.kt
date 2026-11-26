package pe.canchavoley.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.CatalogoDto
import pe.canchavoley.app.datos.ClienteDto
import pe.canchavoley.app.datos.NuevaReserva
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.datos.VistaDisponibilidad
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.DisponibilidadLocal
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Intervalo
import pe.canchavoley.app.dominio.ReglasReserva
import pe.canchavoley.app.dominio.Semana
import pe.canchavoley.app.dominio.Validaciones
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Duraciones que se ofrecen: las de las reglas del negocio si están configuradas; si no, atajos comunes (se puede escribir otra). */
fun duracionesOfrecidas(cat: CatalogoDto?): List<Int> {
    val r = cat?.reglas
    val min = r?.duracionMinimaMin
    val paso = r?.pasoMin
    if (min != null && paso != null && paso > 0) {
        val max = r.duracionMaximaMin ?: (min + paso * 5)
        return generateSequence(min) { it + paso }.takeWhile { it <= max }.take(8).toList()
    }
    return listOf(60, 90, 120, 180)
}

/** Cada cuántos minutos se ofrecen horas de inicio: regla «inicio cada», si no «múltiplo de duración», si no 30. */
fun pasoDeInicio(cat: CatalogoDto?): Int = cat?.reglas?.alinearInicioMin ?: cat?.reglas?.pasoMin ?: 30

/**
 * Desde qué momento se ofrecen horas de inicio. El personal registra desde ahora; el cliente (app) solo ve
 * horas que respetan la anticipación mínima de las reglas del negocio, la misma que valida el servidor (DEF-18).
 */
fun inicioMinimoOfrecido(ahora: Instant, cat: CatalogoDto?, esPersonal: Boolean): Instant =
    if (esPersonal) ahora else ahora + (cat?.reglas?.anticipacionMinimaMin ?: 0).coerceAtLeast(0).minutes

/**
 * Formulario para registrar una reserva (modo llamada, agenda y app del cliente). Primero la duración,
 * luego se toca una de las horas libres (o se escribe otra). Calcula el precio con las tarifas
 * descargadas y, si hay choque, ofrece horarios alternativos.
 */
@Composable
fun FormularioReserva(
    cliente: ClienteDto?,
    esPersonal: Boolean,
    fechaInicial: LocalDate = Fechas.hoy(),
    horaInicial: String = "",
    canchaInicial: Long? = null,
    /** La reserva registrada (la primera, si es una serie), si quedó sin conexión y el resumen de la serie. */
    onRegistrada: (ReservaDto, sinConexion: Boolean, resumenSerie: String?) -> Unit,
) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    val ahora = rememberReloj()
    val catalogo by repo.catalogo().collectAsState(repo.catalogoActual())
    val canchas = catalogo?.canchas?.filter { it.activa }.orEmpty()
    var canchaId by remember { mutableStateOf(canchaInicial ?: canchas.firstOrNull()?.id) }
    var fecha by remember { mutableStateOf(fechaInicial) }
    var hora by remember { mutableStateOf(horaInicial) }
    val opcionesDuracion = remember(catalogo) { duracionesOfrecidas(catalogo) }
    var duracion by remember { mutableStateOf(opcionesDuracion.firstOrNull { it == 60 } ?: opcionesDuracion.first()) }
    var duracionTexto by remember { mutableStateOf("") }
    var precio by remember { mutableStateOf("") }
    var precioTocado by remember { mutableStateOf(false) }
    var origen by remember { mutableStateOf("llamada") }
    var notas by remember { mutableStateOf("") }
    var disponibilidad by remember { mutableStateOf<VistaDisponibilidad?>(null) }
    var sugerencias by remember { mutableStateOf<List<Intervalo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var enviando by remember { mutableStateOf(false) }
    var semanas by remember { mutableStateOf(1) }          // cliente fijo: misma hora cada semana
    var progreso by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(canchas.firstOrNull()?.id) { if (canchaId == null) canchaId = canchas.firstOrNull()?.id }
    LaunchedEffect(canchaId, fecha) { canchaId?.let { disponibilidad = repo.disponibilidad(it, fecha) } }

    val durMin = if (duracionTexto.isNotBlank()) duracionTexto.toIntOrNull() else duracion
    val horaLocal = Fechas.leerHora(hora)
    val inicio = horaLocal?.let { Fechas.instante(fecha, it) }
    val fin = if (inicio != null && durMin != null) inicio + durMin.minutes else null
    val estimado = if (inicio != null && fin != null && canchaId != null) repo.precioEstimado(canchaId!!, inicio, fin) else null
    LaunchedEffect(estimado) { if (!precioTocado) precio = estimado?.let { Dinero.aTexto(it) } ?: "" }
    val desde = inicioMinimoOfrecido(ahora, catalogo, esPersonal)
    val horasLibres = remember(disponibilidad, durMin, desde) {
        disponibilidad?.libres?.let { DisponibilidadLocal.horasDeInicio(it, durMin ?: 0, pasoDeInicio(catalogo), desde) }.orEmpty()
    }
    // Hay espacio libre, pero todo cae dentro de la anticipación mínima que exige el negocio (solo app del cliente).
    val soloFaltaAnticipacion = !esPersonal && horasLibres.isEmpty() && desde > ahora &&
        disponibilidad?.libres?.let { DisponibilidadLocal.horasDeInicio(it, durMin ?: 0, pasoDeInicio(catalogo), ahora) }.orEmpty().isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (canchas.size > 1) {
            Text("Cancha", style = MaterialTheme.typography.labelLarge, color = Colores.gris)
            Opciones(canchas.map { it.id to it.nombre }, canchaId, { canchaId = it; hora = "" })
        }
        SelectorFecha(fecha, { fecha = it; hora = ""; sugerencias = emptyList(); error = null })

        Text("Duración", style = MaterialTheme.typography.labelLarge, color = Colores.gris)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Opciones(opcionesDuracion.map { it to Fechas.duracion(it) }, if (duracionTexto.isBlank()) duracion else null,
                { duracion = it; duracionTexto = "" })
            Campo(duracionTexto, { duracionTexto = it.filter { c -> c.isDigit() }.take(4) }, "Otra (min)", Modifier.width(130.dp), numerico = true)
        }

        disponibilidad?.let { d ->
            // Sin conexión: los horarios son los guardados en el equipo y lo «libre» no está confirmado.
            if (!d.desdeElServidor) {
                val cuando = d.actualizadaEn?.let { " (actualizados el ${Fechas.fechaHora(Fechas.iso(it))})" }.orEmpty()
                Aviso(
                    "Sin conexión: " + (if (d.conDatosDeOcupacion) "horarios según los datos guardados$cuando. Las horas en ámbar no están confirmadas; "
                        else "no hay datos guardados de este día, así que cualquier hora puede estar ocupada; ") +
                        (if (esPersonal) "la reserva quedará por validar y el servidor la revisará al volver la conexión."
                         else "tu solicitud quedará pendiente y el servidor la validará al volver la conexión."),
                    Colores.azulGrisClaro, Colores.gris, icono = Iconos.sinNube)
            }
            when {
                !d.horarioConfigurado -> Aviso("La cancha no tiene horario de atención configurado.")
                horasLibres.isEmpty() -> Aviso(when {
                        soloFaltaAnticipacion -> "Las solicitudes se hacen con al menos ${Fechas.duracion(catalogo?.reglas?.anticipacionMinimaMin ?: 0)} de anticipación: elige una hora más tarde u otro día."
                        d.libres.isEmpty() -> "No quedan horarios libres este día."
                        else -> "No hay un espacio libre de ${Fechas.duracion(durMin ?: 0)} este día: prueba otra duración u otro día."
                    }, Colores.grisClaro, Colores.gris)
                else -> {
                    Text("Hora de inicio" + if (d.desdeElServidor) "" else " (sin verificar: según los datos guardados en este equipo)",
                        style = MaterialTheme.typography.labelLarge, color = Colores.gris)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        horasLibres.forEach { h ->
                            val texto = Fechas.hora(h)
                            Ficha(texto, seleccionada = texto == hora, sinVerificar = !d.desdeElServidor) { hora = texto; error = null; sugerencias = emptyList() }
                        }
                    }
                }
            }
        }
        Campo(hora, { hora = it; error = null }, "Hora de inicio (HH:MM)", Modifier.widthIn(max = 260.dp).fillMaxWidth(), icono = Iconos.reloj,
            error = if (hora.isNotBlank() && horaLocal == null) "Ejemplo: 18:30" else null,
            ayuda = "Toca una hora libre o escribe otra")
        // Hora escrita a mano que no está libre: se avisa de inmediato (el servidor igual valida al registrar).
        val d = disponibilidad
        val avisoHora = if (d != null && inicio != null && fin != null && horasLibres.none { Fechas.hora(it) == hora.trim() })
            DisponibilidadLocal.avisoHoraEscrita(d.turnos, d.ocupados, d.libres, inicio, fin) else null
        avisoHora?.let {
            Aviso((if (d?.desdeElServidor == false) "Según los datos guardados en este equipo: " else "") + it +
                " Elige una de las horas libres o cambia la duración.")
        }

        if (inicio != null && fin != null) {
            Aviso("${Fechas.fechaCorta(fecha)} de ${Fechas.hora(inicio)} a ${Fechas.hora(fin)} (${Fechas.duracion(durMin ?: 0)})" +
                (estimado?.let { " · ${Dinero.soles(it)} según tarifa" } ?: ""), Colores.azulClaro, Colores.azulOscuro, icono = Iconos.calendario)
        }
        if (esPersonal) {
            FilaAdaptable { fila ->
                Campo(precio, { precio = it; precioTocado = true }, "Precio acordado (S/)", if (fila) Modifier.width(230.dp) else Modifier.fillMaxWidth(),
                    numerico = true, icono = Iconos.dinero,
                    ayuda = if (estimado != null) "Según tarifa: ${Dinero.soles(estimado)}" else "Sin tarifa para este horario: escribe el precio acordado")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("¿Cómo llegó la reserva?", style = MaterialTheme.typography.labelLarge, color = Colores.gris)
                    Opciones(listOf("llamada" to "Llamada", "presencial" to "Presencial", "whatsapp" to "WhatsApp", "otro" to "Otro"), origen, { origen = it })
                }
            }
        }
        if (esPersonal) {
            Text("Repetir (cliente fijo)", style = MaterialTheme.typography.labelLarge, color = Colores.gris)
            Opciones(listOf(1 to "Solo esta vez", 4 to "4 semanas", 8 to "8 semanas", Semana.MAX_SEMANAS to "${Semana.MAX_SEMANAS} semanas"), semanas, { semanas = it })
            if (semanas > 1) {
                val fechas = Semana.serie(fecha, semanas)
                Text("Mismo día y hora cada semana, del ${Fechas.fechaCorta(fechas.first())} al ${Fechas.fechaCorta(fechas.last())}. " +
                    "Cada fecha se valida por separado: si una está ocupada, las demás se registran igual.", color = Colores.gris)
            }
        }
        Campo(notas, { notas = it }, "Notas (opcional)")
        error?.let { Aviso(it, Colores.rojoClaro, Colores.rojo) }
        ListaSugerencias(sugerencias) { s ->
            fecha = Fechas.local(s.inicio).date; hora = Fechas.hora(s.inicio)
            duracionTexto = ""; duracion = s.minutos; error = null; sugerencias = emptyList()
        }
        BotonPrincipal(
            enabled = !enviando && canchaId != null,
            modifier = Modifier.fillMaxWidth(),
            icono = Iconos.listo,
            onClick = {
                error = Validaciones.reserva(hora, durMin, inicio, Fechas.ahora(), cliente != null, precio, esPersonal)
                if (error != null) return@BotonPrincipal
                val precioFinal = if (esPersonal) precio.takeIf { it.isNotBlank() }?.let { Dinero.aCentimos(it) }?.let { Dinero.aTexto(it) } else null
                enviando = true
                // Se fijan los datos al tocar el botón: cambiar el formulario mientras se registra la serie no la altera.
                val total = if (esPersonal) semanas else 1
                val fechaSerie = fecha
                val horaSerie = horaLocal!!
                val minutosSerie = durMin!!
                val canchaSerie = canchaId!!
                val precioSerie = precioFinal?.takeIf { precioTocado || estimado == null }
                val origenSerie = origen
                val notasSerie = notas.takeIf { it.isNotBlank() }
                scope.launch {
                    fun nueva(ini: kotlin.time.Instant) = NuevaReserva(canchaSerie, cliente?.uuid, ini, ini + minutosSerie.minutes,
                        origenSerie, precioSerie, notasSerie)
                    if (total > 1) progreso = "Registrando 1 de $total…"
                    val r = repo.crearReserva(nueva(inicio!!))
                    val primera = when (r) {
                        is ResultadoAccion.Hecho -> r.valor
                        is ResultadoAccion.GuardadoSinConexion -> r.valor
                        is ResultadoAccion.Fallo -> {
                            enviando = false; progreso = null; error = r.mensaje; sugerencias = r.sugerencias
                            return@launch
                        }
                    }
                    var resumen: String? = null
                    if (total > 1) {
                        var registradas = 1
                        var sinConexion = if (r is ResultadoAccion.GuardadoSinConexion) 1 else 0
                        val fallidas = mutableListOf<Pair<LocalDate, String>>()
                        Semana.serie(fechaSerie, total).drop(1).forEachIndexed { i, dia ->
                            progreso = "Registrando ${i + 2} de $total…"
                            when (val rk = repo.crearReserva(nueva(Fechas.instante(dia, horaSerie)))) {
                                is ResultadoAccion.Hecho -> registradas++
                                is ResultadoAccion.GuardadoSinConexion -> { registradas++; sinConexion++ }
                                is ResultadoAccion.Fallo -> fallidas += dia to rk.mensaje
                            }
                        }
                        resumen = Semana.resumenSerie(total, registradas, sinConexion, fallidas)
                    }
                    enviando = false; progreso = null
                    onRegistrada(primera, r is ResultadoAccion.GuardadoSinConexion, resumen)
                }
            },
        ) {
            Text(progreso ?: if (enviando) "Registrando…" else if (!esPersonal) "Enviar solicitud"
                else if (semanas > 1) "Registrar $semanas reservas" else "Registrar reserva")
        }
        if (cliente == null && esPersonal) Text("Primero identifica o registra al cliente.", color = Colores.gris)
    }
}

/** Datos con los que se abre «Registrar pago» a partir de un pago que informó el cliente (ya verificado por el personal). */
data class PagoPrellenado(val tipo: String, val metodo: String, val monto: String, val numero: String)

/**
 * Registrar un adelanto, un pago o una devolución (efectivo, Yape o Plin). [tipos] son los movimientos que permite
 * el estado de la reserva (ver dominio/ReglasReserva.kt): una reserva cancelada solo admite la devolución de lo
 * cobrado y solo la administradora la registra. El servidor vuelve a validar todo.
 */
@Composable
fun DialogoPago(reserva: ReservaDto, onCerrar: () -> Unit, onListo: (String) -> Unit,
                tipos: List<String> = listOf("adelanto", "pago"), reglas: ReglasDto? = null, prellenado: PagoPrellenado? = null) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    val pagado = ReglasReserva.pagado(reserva)
    val saldo = ReglasReserva.saldoPendiente(reserva)
    fun montoSugerido(tipo: String): String = when (tipo) {
        "adelanto" -> Dinero.aCentimos(reserva.adelantoRequerido)?.let { a -> Dinero.aTexto(if (saldo != null) minOf(a, saldo) else a) }.orEmpty()
        "devolucion" -> ReglasReserva.devolucionSugerida(reserva, reglas).takeIf { it > 0 }?.let { Dinero.aTexto(it) }.orEmpty()
        else -> saldo?.takeIf { it > 0 }?.let { Dinero.aTexto(it) }.orEmpty()
    }
    val tipoInicial = prellenado?.tipo?.takeIf { it in tipos } ?: when {
        "adelanto" in tipos && reserva.estadoPago in setOf("sin_pago", "sin_precio") && reserva.adelantoRequerido != null -> "adelanto"
        "pago" in tipos -> "pago"
        else -> tipos.firstOrNull() ?: "pago"
    }
    var tipo by remember { mutableStateOf(tipoInicial) }
    var metodo by remember { mutableStateOf(prellenado?.metodo ?: "efectivo") }
    var monto by remember { mutableStateOf(prellenado?.monto ?: montoSugerido(tipoInicial)) }
    var montoTocado by remember { mutableStateOf(prellenado != null) }
    var numero by remember { mutableStateOf(prellenado?.numero.orEmpty()) }
    var observacion by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var enviando by remember { mutableStateOf(false) }
    val nombres = mapOf("adelanto" to "Adelanto", "pago" to "Pago", "devolucion" to "Devolución")

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (tipos == listOf("devolucion")) "Registrar devolución" else "Registrar pago") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${reserva.clienteNombre ?: "Cliente"} · ${Fechas.rango(reserva.inicio, reserva.fin)}", color = Colores.gris)
                val resumen = buildList {
                    reserva.precioFinal?.let { add("Precio ${Dinero.soles(it)}") }
                    add("cobrado ${Dinero.soles(pagado)}")
                    when {
                        saldo != null -> add("falta ${Dinero.soles(saldo)}")
                        !ReglasReserva.activa(reserva) && pagado > 0 -> add("a favor del cliente ${Dinero.soles(pagado)}")
                    }
                }.joinToString(" · ").replaceFirstChar { it.uppercase() }
                Text(resumen, fontWeight = FontWeight.Medium)
                // Al cambiar cualquier dato se borra el aviso del intento anterior (defecto DEF-01).
                if (tipos.size > 1) Opciones(tipos.map { it to (nombres[it] ?: it) }, tipo,
                    { tipo = it; error = null; if (!montoTocado) monto = montoSugerido(it) })
                if (tipo == "devolucion") {
                    val regla = ReglasReserva.textoReglaDevolucion(reglas)
                    val sugerida = ReglasReserva.devolucionSugerida(reserva, reglas)
                    Aviso(buildString {
                        append("Se devuelve como máximo lo cobrado (${Dinero.soles(pagado)}).")
                        if (!ReglasReserva.activa(reserva) && regla != null) append(" Regla del negocio al cancelar: $regla.")
                        if (sugerida > 0) append(" Sugerido: ${Dinero.soles(sugerida)}.")
                    }, Colores.azulClaro, Colores.azul, icono = null)
                }
                if (prellenado != null) Aviso("Datos del pago que informó el cliente. Regístralo solo si ya comprobaste en la cuenta del negocio que el dinero llegó.")
                Opciones(listOf("efectivo" to "Efectivo", "yape" to "Yape", "plin" to "Plin"), metodo, { metodo = it; error = null })
                Campo(monto, { monto = it; montoTocado = true; error = null }, "Monto (S/)", numerico = true, icono = Iconos.dinero)
                if (metodo != "efectivo") Campo(numero, { numero = it.trim(); error = null }, "N.º de operación de ${textoMetodo(metodo)}",
                    ayuda = "Evita registrar dos veces la misma transferencia")
                Campo(observacion, { observacion = it; error = null },
                    if (tipo == "devolucion") "Motivo de la devolución (obligatorio)" else "Observación (opcional)")
                error?.let { Aviso(it, Colores.rojoClaro, Colores.rojo) }
            }
        },
        confirmButton = {
            BotonPrincipal(enabled = !enviando, onClick = {
                error = Validaciones.pago(tipo, monto, metodo, numero, pagado,
                    saldo = if (tipo == "devolucion") null else saldo,
                    motivo = if (tipo == "devolucion") observacion else null)
                if (error != null) return@BotonPrincipal
                enviando = true
                scope.launch {
                    val r = repo.registrarPago(reserva.uuid, tipo, metodo, Dinero.aTexto(Dinero.aCentimos(monto)!!), numero, observacion)
                    enviando = false
                    when (r) {
                        is ResultadoAccion.Hecho -> onListo(r.aviso ?: "${if (tipo == "devolucion") "Devolución registrada" else "${nombres[tipo] ?: "Pago"} registrado"} " +
                            "(comprobante ${r.valor.numeroComprobante ?: "—"}).")
                        is ResultadoAccion.GuardadoSinConexion -> onListo("Guardado sin conexión: se enviará al volver internet.")
                        is ResultadoAccion.Fallo -> error = r.mensaje
                    }
                }
            }) { Text(if (enviando) "Guardando…" else "Registrar") }
        },
        dismissButton = { BotonTexto(onClick = onCerrar) { Text("Cancelar") } },
    )
}
