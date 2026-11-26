@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.EventoDto
import pe.canchavoley.app.datos.NegocioDto
import pe.canchavoley.app.datos.PagoDto
import pe.canchavoley.app.datos.PagoInformadoDto
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.datos.SesionDto
import pe.canchavoley.app.dominio.Comprobante
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.Documentos
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.LecturaQr
import pe.canchavoley.app.dominio.ReglasReserva
import pe.canchavoley.app.dominio.Telefono
import pe.canchavoley.app.dominio.TipoWhatsApp
import pe.canchavoley.app.dominio.Validaciones
import pe.canchavoley.app.dominio.WhatsApp
import pe.canchavoley.app.plataforma.MENSAJE_PORTAPAPELES_OCUPADO
import pe.canchavoley.app.plataforma.copiarTexto
import pe.canchavoley.app.plataforma.rememberCompartirTexto
import pe.canchavoley.app.plataforma.rememberGuardarArchivo

/**
 * Detalle de una reserva: estado, pagos y comprobantes, QR, historial y las acciones que permiten su estado
 * y el rol (reglas en dominio/ReglasReserva.kt, las mismas que vuelve a validar el servidor).
 */
@Composable
fun PantallaDetalleReserva(uuid: String, sesion: SesionDto, nav: Navegacion, onVolver: () -> Unit) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    val local by repo.reserva(uuid).collectAsState(null)
    val pagosLocales by repo.pagos(uuid).collectAsState(emptyList())
    var eventos by remember { mutableStateOf<List<EventoDto>>(emptyList()) }
    var informados by remember { mutableStateOf<List<PagoInformadoDto>>(emptyList()) }
    var dialogo by remember { mutableStateOf<String?>(null) }
    var prellenado by remember { mutableStateOf<PagoPrellenado?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val catalogo by repo.catalogo().collectAsState(null)
    val compartir = rememberCompartirTexto()
    val guardarArchivo = rememberGuardarArchivo()
    @Suppress("DEPRECATION") val portapapeles = LocalClipboardManager.current
    val abrirEnlace = LocalUriHandler.current
    // Reloj de la pantalla: habilita «No asistió» al cumplirse la tolerancia sin tener que salir y volver.
    val ahora = rememberReloj()

    /** Historial y pagos informados (con conexión); la reserva en sí se actualiza sola desde la copia local. */
    suspend fun recargar() {
        repo.detalle(uuid)?.let { eventos = it.eventos.orEmpty(); informados = it.pagosInformados.orEmpty() }
    }
    // Se vuelve a pedir cuando la sincronización trae un cambio de la reserva (otro equipo aprobó, cobró,
    // canceló o verificó un pago informado); sin esto el historial y los pagos informados quedaban como al abrir.
    // Las listas que solo trae el detalle no cuentan como cambio (evita pedirlo dos veces seguidas).
    val cambioDeReserva = local?.dto?.copy(pagos = null, eventos = null, pagosInformados = null)
    LaunchedEffect(uuid, cambioDeReserva) { recargar() }

    fun ejecutar(accion: String, datos: Map<String, Any?>) {
        ocupado = true
        scope.launch {
            when (val r = repo.accionReserva(uuid, accion, datos)) {
                is ResultadoAccion.Hecho -> { nav.mensaje("Listo."); recargar() }
                is ResultadoAccion.GuardadoSinConexion -> nav.mensaje("Guardado sin conexión: se enviará al volver internet.")
                is ResultadoAccion.Fallo -> nav.mensaje(r.mensaje)
            }
            ocupado = false
        }
    }

    fun abrir(enlace: String) {
        runCatching { abrirEnlace.openUri(enlace) }.onFailure { nav.mensaje("No se pudo abrir WhatsApp ni el navegador.") }
    }

    val r = local?.dto
    val reglas = catalogo?.reglas
    val nombreCancha = r?.let { res -> catalogo?.canchas?.firstOrNull { it.id == res.canchaId }?.nombre }
    fun textoComprobante(): String = r?.let { res ->
        Comprobante.texto(catalogo?.negocio, res, pagosLocales.map { it.dto }, nombreCancha)
    }.orEmpty()
    Pantalla {
        TituloPantalla("Reserva", r?.let { Fechas.rango(it.inicio, it.fin) }) { BotonSecundario(onClick = onVolver, icono = Iconos.atras) { Text("Volver") } }
        if (r == null) { Cargando(); return@Pantalla }
        val sinSync = local?.sinSincronizar == true
        val esCliente = !sesion.esPersonal
        val a = ReglasReserva.acciones(r, sesion.esPersonal, sesion.esAdmin, ahora, reglas)
        val pagado = ReglasReserva.pagado(r)
        val devuelto = ReglasReserva.devuelto(pagosLocales.map { it.dto })
        val vencido = ReglasReserva.plazoVencido(r, ahora)
        val esperaAdelanto = r.estado == "pendiente" && r.pendientePor == "adelanto"

        when {
            r.estado == "por_sincronizar" -> Aviso(
                if (esCliente) "Solicitud guardada en tu celular, pendiente de validación: se enviará cuando vuelva la conexión y el servidor " +
                    "comprobará que el horario siga libre. Todavía no es una reserva."
                else "Registrada sin conexión: el servidor aún no la valida y el horario podría estar tomado. No la confirmes al cliente hasta que se sincronice.",
                Colores.azulGrisClaro, Colores.gris, icono = Iconos.sinNube)
            sinSync -> Aviso("Hay un cambio guardado sin conexión que se enviará al volver internet.", Colores.azulGrisClaro, Colores.gris, icono = Iconos.sinNube)
            r.estado == "rechazada" -> Aviso("Rechazada: ${r.motivoEstado ?: "sin motivo"}. " + if (sesion.esPersonal) "Llama al cliente para ofrecerle otro horario." else "", Colores.rojoClaro, Colores.rojo)
            r.estado == "cancelada" -> Aviso(textoCancelada(r, pagado, devuelto, esCliente, sesion.esAdmin, reglas), Colores.grisClaro, Colores.gris)
            r.estado == "pendiente" && r.pendientePor == "aprobacion" -> Aviso(if (esCliente) "Tu solicitud está pendiente de aprobación por la administradora." else "Solicitud hecha desde la app: la administradora debe aprobarla o rechazarla.")
            esperaAdelanto && vencido -> Aviso(
                "Venció el plazo del adelanto" + (r.adelantoVenceEn?.let { " (${Fechas.fechaHora(it)})" } ?: "") + ". " +
                    if (esCliente) "Tu horario ya no está apartado y puede tomarlo otra persona. Si ya pagaste, informa tu pago o comunícate con el negocio."
                    else "El horario ya no está apartado y otra persona puede tomarlo: la solicitud se cancelará sola cuando se registre otra reserva en la cancha. " +
                        "Si el cliente paga antes, registra el pago y la reserva se confirma.",
                Colores.rojoClaro, Colores.rojo)
            esperaAdelanto -> Aviso(
                if (esCliente) "Para confirmar tu reserva paga el adelanto de ${Dinero.soles(r.adelantoRequerido)}" +
                    (r.adelantoVenceEn?.let { " antes del ${Fechas.fechaHora(it)}" } ?: "") + ". Abajo te mostramos cómo."
                else "Falta el adelanto de ${Dinero.soles(r.adelantoRequerido)}" + (r.adelantoVenceEn?.let { " (plazo: ${Fechas.fechaHora(it)})" } ?: "") + "." +
                    if (r.pagoInformado != null) " El cliente informó un pago: verifícalo abajo." else "")
            sesion.esPersonal && r.estadoPago == "saldo_a_favor" -> Aviso("El cliente pagó más que el precio. " +
                if (sesion.esAdmin) "Si corresponde, registra la devolución de la diferencia." else "Avísale a la administradora: ella registra las devoluciones.")
        }
        local?.errorSync?.let { Aviso("Último aviso del servidor: $it", Colores.rojoClaro, Colores.rojo) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tarjeta(Modifier.widthIn(min = 300.dp, max = 520.dp), titulo = r.clienteNombre ?: "Cliente") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EtiquetaEstado(r.estado, r.pendientePor, sinSync, vencido)
                    r.asistencia?.let { Etiqueta(if (it == "asistio") "Asistió" else "No asistió", Colores.azulClaro, Colores.azul) }
                }
                if (sesion.esPersonal) FilaDato("Teléfono", Telefono.mostrar(r.clienteTelefono))
                FilaDato("Cancha", nombreCancha ?: "—")
                FilaDato("Fecha", Fechas.fechaLarga(Fechas.local(Fechas.parse(r.inicio)!!).date).replaceFirstChar { it.uppercase() })
                FilaDato("Horario", "${Fechas.hora(r.inicio)}–${Fechas.hora(r.fin)} (${Fechas.duracion(r.duracionMin)})")
                FilaDato("Origen", textoOrigen(r.origen))
                r.notas?.takeIf { it.isNotBlank() }?.let { FilaDato("Notas", it) }
            }
            Tarjeta(Modifier.widthIn(min = 260.dp, max = 400.dp), titulo = "Pagos") {
                FilaDato("Precio", r.precioFinal?.let { Dinero.soles(it) + if (r.precioManual) " (acordado)" else "" } ?: "Por definir", destacado = true)
                r.adelantoRequerido?.let { FilaDato("Adelanto requerido", Dinero.soles(it)) }
                if (esperaAdelanto) r.adelantoVenceEn?.let { FilaDato(if (vencido) "Plazo (vencido)" else "Pagar antes de", Fechas.fechaHora(it)) }
                FilaDato("Pagado", Dinero.soles(pagado))
                if (r.estado == "cancelada" || r.estado == "rechazada") FilaDato("A favor del cliente", Dinero.soles(pagado.coerceAtLeast(0)), destacado = true)
                else FilaDato("Saldo", r.saldo?.let { Dinero.soles(it) } ?: "—", destacado = true)
                FilaDato("Estado del pago",
                    if (!ReglasReserva.activa(r) && pagado <= 0 && devuelto > 0) "Devuelto (${Dinero.soles(devuelto)})" else textoEstadoPago(r.estadoPago))
            }
            if (r.estado == "confirmada" && r.codigoQr != null) {
                Tarjeta(titulo = "Código QR") {
                    ImagenQr(LecturaQr.contenido(r.codigoQr), 180.dp)
                    Text(r.codigoQr, fontSize = 12.sp, color = Colores.gris)
                    if (esCliente) Text("Muéstralo al llegar a la cancha.", color = Colores.gris)
                }
            }
        }

        // Cliente: cómo pagar el adelanto (los datos los publica la administradora; nada se inventa aquí).
        if (a.comoPagar) {
            val negocio = catalogo?.negocio
            TarjetaComoPagar(r, negocio, vencido, a.informarPago,
                onCopiar = { texto -> nav.mensaje(if (copiarTexto(portapapeles, texto)) "Número copiado: pégalo en tu app de pagos." else MENSAJE_PORTAPAPELES_OCUPADO) },
                onInformar = { dialogo = "informar" },
                onEscribir = WhatsApp.enlace(negocio?.telefono, WhatsApp.alNegocio(r))?.let { enlace -> { abrir(enlace) } })
        }
        // Personal: pago que informó el cliente, por verificar en la cuenta del negocio.
        if (sesion.esPersonal) r.pagoInformado?.let { p ->
            TarjetaPagoInformado(p, puedeRegistrar = a.tiposPago.any { it != "devolucion" }, ocupado = ocupado,
                onRegistrar = {
                    val tipo = if ("adelanto" in a.tiposPago && esperaAdelanto) "adelanto" else "pago"
                    prellenado = PagoPrellenado(tipo, p.metodo, p.monto, p.numeroOperacion)
                    dialogo = "pago"
                },
                onDescartar = { dialogo = "descartar:${p.uuid}" })
        }

        // ── Acciones ────────────────────────────────────────────────────────
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.aprobarORechazar) {
                BotonPrincipal(enabled = !ocupado, icono = Iconos.listo, colorFondo = Colores.verde,
                    onClick = { if (r.precioFinal == null) dialogo = "aprobar_precio" else dialogo = "aprobar" }) { Text("Aprobar") }
                BotonSecundario(enabled = !ocupado, icono = Iconos.cerrar, onClick = { dialogo = "rechazar" }) { Text("Rechazar") }
            }
            if (a.registrarPago) {
                BotonPrincipal(enabled = !ocupado, icono = Iconos.dinero, onClick = { prellenado = null; dialogo = "pago" }) {
                    Text(if (a.soloDevolucion) "Registrar devolución" else "Registrar pago")
                }
            }
            if (a.llegoCliente) {
                BotonSecundario(enabled = !ocupado, icono = Iconos.listo, onClick = { ejecutar("asistencia", mapOf("asistio" to true)) }) {
                    Text(if (a.llegoTarde) "Llegó tarde" else "Llegó el cliente")
                }
            }
            if (a.noAsistio) {
                BotonSecundario(enabled = !ocupado && a.noAsistioDesde == null, onClick = { dialogo = "no_asistio" }) { Text("No asistió") }
            }
            if (a.corregirAsistencia) {
                BotonSecundario(enabled = !ocupado, icono = Iconos.editar, onClick = { dialogo = "corregir_asistencia" }) { Text("Corregir asistencia") }
            }
            if (a.editarNotas) {
                BotonSecundario(enabled = !ocupado, icono = Iconos.editar, onClick = { dialogo = "editar" }) { Text(if (a.editarPrecio) "Precio y notas" else "Notas") }
            }
            if (a.cambiarHorario) {
                BotonSecundario(enabled = !ocupado, icono = Iconos.reloj, onClick = { dialogo = "reprogramar" }) { Text("Cambiar horario") }
            }
            a.whatsapp?.let { tipo ->
                // WhatsApp con el mensaje ya escrito (enlace wa.me); la persona decide si lo envía.
                val negocio = catalogo?.negocio?.nombre
                val texto = when (tipo) {
                    TipoWhatsApp.RECORDATORIO -> WhatsApp.recordatorio(negocio, r, nombreCancha)
                    TipoWhatsApp.OTRO_HORARIO -> WhatsApp.otroHorario(negocio, r)
                    TipoWhatsApp.CONTACTO -> WhatsApp.contacto(negocio, r)
                }
                WhatsApp.enlace(r.clienteTelefono, texto)?.let { enlace ->
                    BotonSecundario(icono = Iconos.telefono, onClick = { abrir(enlace) }) {
                        Text(when (tipo) {
                            TipoWhatsApp.RECORDATORIO -> "Recordar por WhatsApp"
                            TipoWhatsApp.OTRO_HORARIO -> "Ofrecer otro horario"
                            TipoWhatsApp.CONTACTO -> "Escribir por WhatsApp"
                        })
                    }
                }
            }
            if (a.cancelar) {
                BotonTexto(enabled = !ocupado, icono = Iconos.borrar, color = Colores.rojo, onClick = { dialogo = "cancelar" }) { Text(if (esCliente) "Cancelar reserva" else "Cancelar") }
            }
        }
        // Por qué no aparecen ciertas acciones (para no dejar al personal adivinando).
        if (sesion.esPersonal) {
            val notas = buildList {
                a.noAsistioDesde?.let { add("«No asistió» se podrá marcar desde las ${Fechas.hora(it)} (hora de inicio más la tolerancia del negocio).") }
                if (r.asistencia != null) add("Con la asistencia registrada ya no se cambia el horario ni se cancela. " +
                    if (sesion.esAdmin) "Si hubo un error, usa «Corregir asistencia» (pide motivo)." else "Si hubo un error, la administradora puede corregirla.")
                if (!ReglasReserva.activa(r) && r.estado != "por_sincronizar") add(
                    when {
                        pagado > 0 -> "En una reserva ${r.estado} no se registran pagos nuevos; " +
                            (if (sesion.esAdmin) "solo la devolución de lo cobrado." else "solo la administradora registra la devolución de lo cobrado.")
                        devuelto > 0 -> "En una reserva ${r.estado} no se registran pagos nuevos y lo cobrado ya se devolvió: no queda nada pendiente."
                        else -> "En una reserva ${r.estado} sin pagos no hay nada que cobrar ni devolver."
                    })
                if (ReglasReserva.activa(r) && ReglasReserva.saldoPendiente(r) == 0L && !a.registrarPago) add("La reserva ya está pagada por completo.")
            }
            notas.forEach { Text(it, fontSize = 12.sp, color = Colores.gris) }
        }

        Tarjeta(titulo = "Comprobantes de pago", icono = Iconos.dinero, accion = {
            // Comprobante en PDF: en Windows se guarda en Documentos\CanchaVoley y se abre; en Android se comparte.
            BotonTexto(onClick = {
                scope.launch {
                    val pdf = Documentos.comprobantePdf(catalogo?.negocio, r, pagosLocales.map { it.dto }, nombreCancha, Fechas.ahora())
                    nav.mensaje(guardarArchivo(Documentos.nombreComprobante(r), Documentos.MIME_PDF, pdf))
                }
            }, icono = Iconos.descargar) { Text("PDF") }
            if (compartir != null) BotonTexto(onClick = { compartir("Comprobante de reserva", textoComprobante()) }, icono = Iconos.compartir) { Text("Compartir") }
            else BotonTexto(onClick = { nav.mensaje(if (copiarTexto(portapapeles, textoComprobante())) "Comprobante copiado: pégalo en WhatsApp o en un correo." else MENSAJE_PORTAPAPELES_OCUPADO) },
                icono = Iconos.copiar) { Text("Copiar") }
        }) {
            val pagos = pagosLocales.map { it.dto to it.estadoSync }
            if (pagos.isEmpty()) Vacio("Sin pagos registrados.")
            pagos.forEach { (p, est) ->
                FilaPago(p, est == "PENDIENTE", sesion.esAdmin && p.estado == "valido" && est != "PENDIENTE") {
                    // Mismo criterio que el servidor: un cobro que ya se devolvió no se anula antes que su devolución.
                    if (ReglasReserva.anulacionDejaNegativo(r, p)) nav.mensaje("No se puede anular este cobro: ese dinero ya se devolvió al cliente. " +
                        "Si la devolución fue un error, anúlala primero.")
                    else dialogo = "anular:${p.uuid}"
                }
            }
            Text("Comprobante interno del negocio; no reemplaza un comprobante electrónico de SUNAT.", fontSize = 12.sp, color = Colores.gris)
        }
        if (informados.isNotEmpty()) {
            Tarjeta(titulo = if (esCliente) "Pagos que informaste" else "Pagos informados por el cliente") {
                informados.forEach { FilaPagoInformado(it) }
                Text("Un pago informado es solo una referencia: cuenta cuando el negocio lo verifica y lo registra.", fontSize = 12.sp, color = Colores.gris)
            }
        }
        if (eventos.isNotEmpty()) {
            Tarjeta(titulo = "Historial") {
                eventos.forEach { e ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(Fechas.fechaHora(e.fecha), Modifier.widthIn(min = 120.dp), color = Colores.gris)
                        Column(Modifier.weight(1f)) {
                            Text(e.descripcion)
                            e.usuario?.let { Text(it, fontSize = 12.sp, color = Colores.gris) }
                        }
                    }
                }
            }
        }
    }

    // ── Diálogos ────────────────────────────────────────────────────────────
    val d = dialogo
    if (d == null || r == null) return
    val acciones = ReglasReserva.acciones(r, sesion.esPersonal, sesion.esAdmin, ahora, reglas)
    val pagadoAhora = ReglasReserva.pagado(r)
    when {
        d == "pago" -> DialogoPago(r, tipos = acciones.tiposPago.ifEmpty { listOf("pago") }, reglas = reglas, prellenado = prellenado,
            onCerrar = { dialogo = null; prellenado = null },
            onListo = { nav.mensaje(it); dialogo = null; prellenado = null; scope.launch { recargar() } })
        d == "rechazar" -> DialogoTexto("Rechazar solicitud", "Motivo (se le enviará al cliente)", "Rechazar",
            onConfirmar = { dialogo = null; ejecutar("rechazar", mapOf("motivo" to it)) }, onCerrar = { dialogo = null })
        d == "cancelar" -> DialogoTexto("Cancelar reserva", "Motivo", "Cancelar reserva",
            onConfirmar = { dialogo = null; ejecutar("cancelar", mapOf("motivo" to it)) }, onCerrar = { dialogo = null },
            explicacion = if (pagadoAhora > 0) {
                val regla = ReglasReserva.textoReglaDevolucion(reglas)
                if (sesion.esPersonal) "Lo ya cobrado (${Dinero.soles(pagadoAhora)}) quedará a favor del cliente" + (regla?.let { "; regla del negocio: $it" } ?: "") +
                    ". La devolución la registra la administradora."
                else ReglasReserva.textoPagadoAlCancelar(pagadoAhora, reglas)
            } else null)
        d == "aprobar" -> DialogoConfirmar("Aprobar solicitud", "Se confirmará la reserva de ${r.clienteNombre ?: "el cliente"} (${Fechas.rango(r.inicio, r.fin)}) y se le avisará.",
            "Aprobar", onConfirmar = { dialogo = null; ejecutar("aprobar", emptyMap()) }, onCerrar = { dialogo = null })
        d == "no_asistio" -> DialogoConfirmar("Marcar que no asistió",
            "Quedará en el historial del cliente (cuenta para «no asistió» en su ficha). Si llega dentro de su horario podrás cambiarlo con «Llegó tarde».",
            "Marcar", onConfirmar = { dialogo = null; ejecutar("asistencia", mapOf("asistio" to false)) }, onCerrar = { dialogo = null })
        d == "corregir_asistencia" -> DialogoCorregirAsistencia(r, onCerrar = { dialogo = null }) { asistio, motivo ->
            dialogo = null
            ejecutar("asistencia", mapOf("asistio" to asistio, "corregir" to true, "motivo" to motivo))
        }
        d == "editar" -> DialogoEditarReserva(r, acciones.editarPrecio, acciones.precioConMotivo, sesion.esAdmin, onCerrar = { dialogo = null }) { datos ->
            dialogo = null; ejecutar("editar", datos)
        }
        d == "aprobar_precio" -> DialogoTexto("Aprobar solicitud", "Precio acordado (S/)", "Aprobar", minimo = 1,
            explicacion = "No hay tarifa configurada para este horario. Indica el precio.",
            onConfirmar = { p -> dialogo = null; Dinero.aCentimos(p)?.let { ejecutar("aprobar", mapOf("precio_final" to Dinero.aTexto(it))) } ?: nav.mensaje("Precio inválido") },
            onCerrar = { dialogo = null })
        d == "reprogramar" -> DialogoReprogramar(r, onCerrar = { dialogo = null }) { ini, fin -> dialogo = null; ejecutar("reprogramar", mapOf("inicio" to ini, "fin" to fin)) }
        d == "informar" -> DialogoInformarPago(r, catalogo?.negocio, onCerrar = { dialogo = null }) { mensaje ->
            dialogo = null; nav.mensaje(mensaje); scope.launch { recargar() }
        }
        d.startsWith("descartar:") -> DialogoTexto("Descartar pago informado", "Motivo (se le enviará al cliente)", "Descartar",
            explicacion = "Úsalo si el pago no aparece en la cuenta del negocio. El cliente recibirá un aviso con el motivo y podrá informar otro pago.",
            onCerrar = { dialogo = null },
            onConfirmar = { motivo ->
                dialogo = null
                scope.launch {
                    when (val res = repo.descartarPagoInformado(d.removePrefix("descartar:"), motivo)) {
                        is ResultadoAccion.Fallo -> nav.mensaje(res.mensaje)
                        else -> { nav.mensaje("Pago informado descartado: se avisó al cliente."); recargar() }
                    }
                }
            })
        d.startsWith("anular:") -> DialogoTexto("Anular pago", "Motivo de la anulación", "Anular pago", onCerrar = { dialogo = null },
            onConfirmar = { motivo ->
                dialogo = null
                scope.launch {
                    when (val res = repo.anularPago(d.removePrefix("anular:"), uuid, motivo)) {
                        is ResultadoAccion.Fallo -> nav.mensaje(res.mensaje)
                        else -> { nav.mensaje("Pago anulado."); recargar() }
                    }
                }
            })
    }
}

/** Aviso de una reserva cancelada: qué pasa con lo cobrado (sin inventar políticas: solo las reglas configuradas). */
internal fun textoCancelada(r: ReservaDto, pagado: Long, devuelto: Long, esCliente: Boolean, esAdmin: Boolean, reglas: ReglasDto?): String {
    val motivo = r.motivoEstado?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
    val regla = ReglasReserva.textoReglaDevolucion(reglas)
    val detalle = when {
        pagado <= 0 && devuelto > 0 ->
            if (esCliente) " Se te devolvió ${Dinero.soles(devuelto)}." else " Ya se devolvió lo cobrado (${Dinero.soles(devuelto)}): no queda nada por cobrar ni devolver."
        pagado <= 0 -> if (esCliente) "" else " No tiene pagos: no hay nada que cobrar ni devolver."
        esCliente && devuelto > 0 -> " Se te devolvió ${Dinero.soles(devuelto)}; el resto (${Dinero.soles(pagado)}) lo coordina el negocio contigo."
        esCliente -> " " + ReglasReserva.textoPagadoAlCancelar(pagado, reglas)
        else -> " Tiene ${Dinero.soles(pagado)} cobrados a favor del cliente" +
            (if (devuelto > 0) " (ya se devolvieron ${Dinero.soles(devuelto)})" else "") +
            (regla?.let { " (regla del negocio: $it)" } ?: "") + ". " +
            if (esAdmin) "Si corresponde, registra la devolución." else "La devolución la registra la administradora."
    }
    return "Cancelada$motivo.$detalle"
}

/** Cliente: monto y plazo del adelanto, número de Yape/Plin que publicó el negocio y cómo informar el pago. */
@Composable
private fun TarjetaComoPagar(r: ReservaDto, negocio: NegocioDto?, vencido: Boolean, puedeInformar: Boolean,
                             onCopiar: (String) -> Unit, onInformar: () -> Unit, onEscribir: (() -> Unit)?) {
    Tarjeta(Modifier.widthIn(max = 640.dp).fillMaxWidth(), titulo = "Cómo pagar tu adelanto", icono = Iconos.dinero) {
        FilaDato("Adelanto por pagar", Dinero.soles(r.adelantoRequerido), destacado = true)
        r.adelantoVenceEn?.let { FilaDato(if (vencido) "El plazo venció el" else "Págalo antes del", Fechas.fechaHora(it)) }
        if (negocio?.tieneDatosDePago == true) {
            negocio.pagoYape?.takeIf { it.isNotBlank() }?.let { NumeroDePago("Yape", it, onCopiar) }
            negocio.pagoPlin?.takeIf { it.isNotBlank() }?.let { NumeroDePago("Plin", it, onCopiar) }
            negocio.pagoTitular?.takeIf { it.isNotBlank() }?.let { FilaDato("A nombre de", it) }
            negocio.pagoInstrucciones?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Text("1. Paga el adelanto desde tu app de Yape o Plin al número indicado.\n" +
                "2. Toca «Ya pagué» y escribe el número de operación de tu constancia.\n" +
                "3. El negocio comprueba que el pago llegó a su cuenta y lo registra: tu reserva queda confirmada y te llega un aviso.",
                color = Colores.gris)
        } else {
            Aviso("El negocio todavía no publicó en la app su número de Yape o Plin. Comunícate con el negocio para pagar tu adelanto.",
                Colores.grisClaro, Colores.gris)
        }
        r.pagoInformado?.let { p ->
            Aviso("Informaste un pago por ${textoMetodo(p.metodo)} de ${Dinero.soles(p.monto)} (op. ${p.numeroOperacion})" +
                (p.informadoEn?.let { " el ${Fechas.fechaHora(it)}" } ?: "") + ". El negocio lo está verificando.",
                Colores.azulClaro, Colores.azul, icono = Iconos.reloj)
        }
        Text("Informar tu pago no confirma la reserva por sí solo: el negocio primero comprueba que el dinero llegó.", fontSize = 12.sp, color = Colores.gris)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (puedeInformar) BotonPrincipal(onClick = onInformar, icono = Iconos.listo) { Text("Ya pagué: informar mi pago") }
            if (onEscribir != null) BotonSecundario(onClick = onEscribir, icono = Iconos.telefono) { Text("Escribir al negocio") }
        }
    }
}

@Composable
private fun NumeroDePago(metodo: String, numero: String, onCopiar: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(metodo, color = Colores.gris, fontSize = 12.sp)
            Text(Telefono.mostrar(numero), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        BotonTexto(onClick = { onCopiar(numero.filter { it.isDigit() }) }, icono = Iconos.copiar) { Text("Copiar") }
    }
}

/** Personal: el pago que informó el cliente, con lo que hay que comprobar antes de registrarlo. */
@Composable
private fun TarjetaPagoInformado(p: PagoInformadoDto, puedeRegistrar: Boolean, ocupado: Boolean, onRegistrar: () -> Unit, onDescartar: () -> Unit) {
    Tarjeta(Modifier.widthIn(max = 640.dp).fillMaxWidth(), titulo = "Pago informado por el cliente", icono = Iconos.dinero,
        accion = { Etiqueta("Por verificar", Colores.ambarClaro, Colores.ambar) }) {
        FilaDato("Método", textoMetodo(p.metodo))
        FilaDato("N.º de operación", p.numeroOperacion)
        FilaDato("Monto informado", Dinero.soles(p.monto), destacado = true)
        p.informadoEn?.let { FilaDato("Informado el", Fechas.fechaHora(it)) }
        p.nota?.takeIf { it.isNotBlank() }?.let { FilaDato("Nota del cliente", it) }
        Aviso("Antes de registrarlo, comprueba en la app de ${textoMetodo(p.metodo)} del negocio que el pago llegó con ese monto y ese número de operación. " +
            "El número o la captura que envía el cliente no bastan como prueba.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (puedeRegistrar) BotonPrincipal(enabled = !ocupado, icono = Iconos.listo, colorFondo = Colores.verde, onClick = onRegistrar) {
                Text("Ya lo verifiqué: registrar pago")
            }
            BotonSecundario(enabled = !ocupado, icono = Iconos.cerrar, onClick = onDescartar) { Text("No lo encuentro: descartar") }
        }
    }
}

@Composable
private fun FilaPagoInformado(p: PagoInformadoDto) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${textoMetodo(p.metodo)} · op. ${p.numeroOperacion}", fontWeight = FontWeight.Medium)
                Text(listOfNotNull(p.informadoEn?.let { "Informado el ${Fechas.fechaHora(it)}" },
                    p.revisadoPor?.let { "revisó $it" }).joinToString(" · "), fontSize = 12.sp, color = Colores.gris)
                if (p.estado == "descartado") p.motivoDescarte?.let { Text("Motivo: $it", fontSize = 12.sp, color = Colores.rojo) }
                if (p.estado == "verificado") p.comprobante?.let { Text("Registrado con el comprobante $it", fontSize = 12.sp, color = Colores.verde) }
            }
            Text(Dinero.soles(p.monto), fontWeight = FontWeight.Bold)
            when (p.estado) {
                "verificado" -> Etiqueta("Verificado", Colores.verdeClaro, Colores.verde)
                "descartado" -> Etiqueta("No verificado", Colores.rojoClaro, Colores.rojo)
                else -> Etiqueta("Por verificar", Colores.ambarClaro, Colores.ambar)
            }
        }
        HorizontalDivider()
    }
}

/** Cliente: datos de la constancia de su pago. Es una referencia: el personal la verifica antes de registrarla. */
@Composable
private fun DialogoInformarPago(r: ReservaDto, negocio: NegocioDto?, onCerrar: () -> Unit, onListo: (String) -> Unit) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    val saldo = ReglasReserva.saldoPendiente(r)
    val metodos = listOfNotNull("yape".takeIf { !negocio?.pagoYape.isNullOrBlank() }, "plin".takeIf { !negocio?.pagoPlin.isNullOrBlank() })
        .ifEmpty { listOf("yape", "plin") }
    val sugerido = Dinero.aCentimos(r.adelantoRequerido)?.let { a -> if (saldo != null) minOf(a, saldo) else a }
    var metodo by remember { mutableStateOf(metodos.singleOrNull()) }
    var numero by remember { mutableStateOf("") }
    var monto by remember { mutableStateOf(sugerido?.let { Dinero.aTexto(it) }.orEmpty()) }
    var nota by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var enviando by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCerrar, title = { Text("Informar mi pago") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Escribe los datos de la constancia de tu pago. El negocio los comprobará en su cuenta antes de confirmar tu reserva.", color = Colores.gris)
            Text("¿Por dónde pagaste?", fontWeight = FontWeight.Medium)
            Opciones(metodos.map { it to textoMetodo(it) }, metodo, { metodo = it; error = null })
            Campo(numero, { numero = it.take(40); error = null }, "N.º de operación", ayuda = "Aparece en la constancia de Yape o Plin")
            Campo(monto, { monto = it; error = null }, "Monto que pagaste (S/)", numerico = true, icono = Iconos.dinero)
            Campo(nota, { nota = it.take(255) }, "Nota para el negocio (opcional)")
            error?.let { Aviso(it, Colores.rojoClaro, Colores.rojo) }
        }
    }, confirmButton = {
        BotonPrincipal(enabled = !enviando, onClick = {
            error = Validaciones.pagoInformado(metodo, numero, monto, saldo)
            val m = metodo
            if (error != null || m == null) return@BotonPrincipal
            enviando = true
            scope.launch {
                val res = repo.informarPago(r.uuid, m, numero, Dinero.aTexto(Dinero.aCentimos(monto)!!), nota)
                enviando = false
                when (res) {
                    is ResultadoAccion.Hecho -> onListo("Listo: el negocio verificará tu pago y te avisará cuando tu reserva quede confirmada.")
                    is ResultadoAccion.GuardadoSinConexion -> onListo("Se enviará al volver la conexión.")
                    is ResultadoAccion.Fallo -> error = res.mensaje
                }
            }
        }) { Text(if (enviando) "Enviando…" else "Enviar") }
    }, dismissButton = { BotonTexto(onClick = onCerrar) { Text("Cancelar") } })
}

@Composable
private fun FilaPago(p: PagoDto, sinSync: Boolean, puedeAnular: Boolean, onAnular: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${p.numeroComprobante ?: "Sin número aún"} · ${when (p.tipo) { "adelanto" -> "Adelanto"; "devolucion" -> "Devolución"; else -> "Pago" }} · ${textoMetodo(p.metodo)}", fontWeight = FontWeight.Medium)
                Text(listOfNotNull(Fechas.fechaHora(p.pagadoEn), p.numeroOperacion?.let { "op. $it" }, p.registradoPor).joinToString(" · "), fontSize = 12.sp, color = Colores.gris)
            }
            Text((if (p.tipo == "devolucion") "−" else "") + Dinero.soles(p.monto), fontWeight = FontWeight.Bold)
            when {
                sinSync -> Etiqueta("Sin sincronizar", Colores.azulGrisClaro, Colores.gris)
                p.estado == "anulado" -> Etiqueta("Anulado", Colores.rojoClaro, Colores.rojo)
                puedeAnular -> BotonTexto(onClick = onAnular, color = Colores.rojo) { Text("Anular") }
            }
        }
        HorizontalDivider()
    }
}

/** Solo la administradora: cambia «Asistió»/«No asistió» ya registrado, con motivo (queda en el historial). */
@Composable
private fun DialogoCorregirAsistencia(r: ReservaDto, onCerrar: () -> Unit, onGuardar: (Boolean, String) -> Unit) {
    var asistio by remember { mutableStateOf(r.asistencia != "asistio") }
    var motivo by remember { mutableStateOf("") }
    val actual = if (r.asistencia == "asistio") "Asistió" else "No asistió"
    AlertDialog(onDismissRequest = onCerrar, title = { Text("Corregir asistencia") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ahora figura «$actual». La corrección queda en el historial con tu nombre y el motivo.")
            Opciones(listOf(true to "Asistió", false to "No asistió"), asistio, { asistio = it })
            Campo(motivo, { motivo = it }, "Motivo de la corrección", lineas = 2)
        }
    }, confirmButton = {
        val cambia = (if (asistio) "asistio" else "no_asistio") != r.asistencia
        BotonPrincipal(enabled = cambia && motivo.trim().length >= 3, onClick = { onGuardar(asistio, motivo.trim()) }) { Text("Corregir") }
    }, dismissButton = { BotonTexto(onClick = onCerrar) { Text("Cancelar") } })
}

@Composable
private fun DialogoReprogramar(r: ReservaDto, onCerrar: () -> Unit, onConfirmar: (String, String) -> Unit) {
    val repo = koinInject<Repositorio>()
    val inicioActual = Fechas.parse(r.inicio)!!
    var fecha by remember { mutableStateOf(Fechas.local(inicioActual).date) }
    var hora by remember { mutableStateOf(Fechas.hora(inicioActual)) }
    var duracion by remember { mutableStateOf(r.duracionMin.toString()) }
    var sugerencias by remember { mutableStateOf<List<pe.canchavoley.app.dominio.Intervalo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fecha, duracion) {
        sugerencias = repo.sugerencias(r.canchaId, fecha, duracion.toIntOrNull() ?: r.duracionMin, Fechas.leerHora(hora), excluir = r.uuid)
    }
    AlertDialog(onDismissRequest = onCerrar, title = { Text("Cambiar horario") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectorFecha(fecha, { fecha = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Campo(hora, { hora = it }, "Inicio (HH:MM)", Modifier.weight(1f))
                Campo(duracion, { duracion = it.filter { c -> c.isDigit() } }, "Duración (min)", Modifier.weight(1f), numerico = true)
            }
            ListaSugerencias(sugerencias) { s -> fecha = Fechas.local(s.inicio).date; hora = Fechas.hora(s.inicio); duracion = s.minutos.toString(); error = null }
            error?.let { Aviso(it, Colores.rojoClaro, Colores.rojo) }
        }
    }, confirmButton = {
        BotonPrincipal(onClick = {
            val h = Fechas.leerHora(hora)
            val d = duracion.toIntOrNull()
            error = when {
                h == null -> "La hora de inicio no es válida (ejemplo: 18:30)."
                d == null || d < 5 -> "Escribe la duración en minutos."
                Fechas.instante(fecha, h) < Fechas.ahora() -> "Esa hora ya pasó: elige otra."
                else -> null
            }
            if (error != null || h == null || d == null) return@BotonPrincipal
            val ini = Fechas.instante(fecha, h)
            onConfirmar(Fechas.iso(ini), Fechas.iso(ini.plus(kotlin.time.Duration.parse("${d}m"))))
        }) { Text("Guardar cambio") }
    }, dismissButton = { BotonTexto(onClick = onCerrar) { Text("Volver") } })
}

/**
 * Notas (todo el personal, en cualquier estado) y precio acordado (solo la administradora y solo en reservas
 * activas). Si ya hay pagos o asistencia, cambiar el precio pide motivo y no puede quedar por debajo de lo cobrado.
 */
@Composable
private fun DialogoEditarReserva(r: ReservaDto, puedePrecio: Boolean, pideMotivo: Boolean, esAdmin: Boolean,
                                 onCerrar: () -> Unit, onGuardar: (Map<String, Any?>) -> Unit) {
    var precio by remember { mutableStateOf(r.precioFinal.orEmpty()) }
    var notas by remember { mutableStateOf(r.notas.orEmpty()) }
    var motivo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pagado = ReglasReserva.pagado(r)
    val cambiaPrecio = puedePrecio && precio.isNotBlank() && Dinero.aCentimos(precio) != Dinero.aCentimos(r.precioFinal)
    AlertDialog(onDismissRequest = onCerrar, title = { Text(if (puedePrecio) "Precio y notas" else "Notas de la reserva") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (puedePrecio) {
                Campo(precio, { precio = it; error = null }, "Precio acordado (S/)", numerico = true, icono = Iconos.dinero,
                    ayuda = r.precioCalculado?.let { "Según tarifa: ${Dinero.soles(it)}" } ?: "Sin tarifa para este horario")
                if (pagado > 0) Text("Ya se cobró ${Dinero.soles(pagado)}: el precio no puede quedar por debajo de eso.", color = Colores.gris, fontSize = 12.sp)
                if (cambiaPrecio && pideMotivo) Campo(motivo, { motivo = it; error = null }, "Motivo del cambio de precio (obligatorio)", lineas = 2)
            } else {
                FilaDato("Precio", r.precioFinal?.let { Dinero.soles(it) } ?: "Por definir")
                Text(if (esAdmin) "El precio solo se cambia en reservas pendientes o confirmadas." else "Solo la administradora cambia el precio.",
                    color = Colores.gris, fontSize = 12.sp)
            }
            Campo(notas, { notas = it }, "Notas", lineas = 3)
            error?.let { Aviso(it, Colores.rojoClaro, Colores.rojo) }
        }
    }, confirmButton = {
        BotonPrincipal(onClick = {
            val datos = buildMap<String, Any?> {
                if (cambiaPrecio) {
                    val c = Dinero.aCentimos(precio)
                    if (c == null || c < 0) { error = "El precio no es válido."; return@BotonPrincipal }
                    if (c < pagado) { error = "El precio no puede ser menor que lo ya cobrado (${Dinero.soles(pagado)})."; return@BotonPrincipal }
                    if (pideMotivo && motivo.trim().length < 3) { error = "Escribe el motivo del cambio de precio."; return@BotonPrincipal }
                    put("precio_final", Dinero.aTexto(c))
                    if (pideMotivo) put("motivo", motivo.trim())
                }
                if (notas.trim() != r.notas.orEmpty()) put("notas", notas.trim())
            }
            if (datos.isEmpty()) onCerrar() else onGuardar(datos)
        }) { Text("Guardar") }
    }, dismissButton = { BotonTexto(onClick = onCerrar) { Text("Cancelar") } })
}
