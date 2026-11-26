package pe.canchavoley.app.ui

import pe.canchavoley.app.plataforma.RegistroFallos
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.GestorSesion
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.SesionDto
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Navegacion as ReglasNavegacion
import pe.canchavoley.app.plataforma.AccionesExternas
import pe.canchavoley.app.plataforma.EstadoSesion

enum class Destino(val titulo: String, val corto: String, val grupo: String) {
    PANEL("Panel principal", "Panel", "Operación"),
    AGENDA("Calendario de reservas", "Agenda", "Operación"),
    LLAMADA("Modo llamada", "Llamada", "Operación"),
    CLIENTES("Clientes", "Clientes", "Operación"),
    CAJA("Pagos y caja", "Caja", "Operación"),
    REPORTES("Reportes", "Reportes", "Gestión"),
    CONFIGURACION("Tarifas y horarios", "Config.", "Gestión"),
    USUARIOS("Usuarios", "Usuarios", "Gestión"),
    SINCRONIZACION("Sincronización", "Sync", "Sistema"),
    NOTIFICACIONES("Notificaciones", "Avisos", "Sistema"),
    HORARIOS("Reservar la cancha", "Reservar", "Cliente"),
    MIS_RESERVAS("Mis reservas", "Mis reservas", "Cliente"),
    CUENTA("Mi cuenta", "Cuenta", "Cliente");

    val icono: ImageVector get() = when (this) {
        PANEL -> Iconos.inicio
        AGENDA -> Iconos.calendario
        LLAMADA -> Iconos.telefono
        CLIENTES -> Iconos.personas
        CAJA -> Iconos.dinero
        REPORTES -> Iconos.grafico
        CONFIGURACION -> Iconos.ajustes
        USUARIOS -> Iconos.cuenta
        SINCRONIZACION -> Iconos.sincronizar
        NOTIFICACIONES -> Iconos.campana
        HORARIOS -> Iconos.calendario
        MIS_RESERVAS -> Iconos.lista
        CUENTA -> Iconos.cuenta
    }
}

/** Menú según el rol (las reglas viven en dominio/Navegacion.kt y tienen pruebas). */
fun destinosPara(s: SesionDto): List<Destino> = ReglasNavegacion.destinos(s.usuario.rol).map { Destino.valueOf(it) }

/** Raíz de CanchaVoley (escritorio Windows y Android). */
@Composable
fun CanchaVoleyApp() {
    TemaCanchaVoley {
        val gestor = koinInject<GestorSesion>()
        val repo = koinInject<Repositorio>()
        val estadoSesion = koinInject<EstadoSesion>()
        val sesion by gestor.sesion.collectAsState()
        val vencida by estadoSesion.vencida.collectAsState()
        var listo by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            try {
                gestor.restaurar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Si no se puede recuperar la sesión guardada, se muestra el acceso en vez de cerrar la app.
                RegistroFallos.anotar(e, "recuperar la sesión al abrir")
            }
            listo = true
        }
        LaunchedEffect(vencida) { if (vencida) gestor.cerrarLocal() }
        LaunchedEffect(sesion?.usuario?.id) { if (sesion != null) repo.sincronizarAhora() }

        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalAncho provides (maxWidth >= 840.dp)) {
                    when {
                        !listo -> Cargando("Abriendo CanchaVoley…")
                        sesion == null -> PantallaAcceso(sesionVencida = vencida)
                        else -> Principal(sesion!!)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
private fun Principal(sesion: SesionDto) {
    val repo = koinInject<Repositorio>()
    val gestor = koinInject<GestorSesion>()
    val scope = rememberCoroutineScope()
    val ancho = LocalAncho.current
    val destinos = remember(sesion) { destinosPara(sesion) }
    var destino by remember(sesion.usuario.id) { mutableStateOf(destinos.first()) }
    var reservaAbierta by remember { mutableStateOf<String?>(null) }
    var confirmarSalida by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val pendientes by repo.operacionesPendientes().collectAsState(0L)
    val noLeidas by repo.noLeidas().collectAsState(0)

    LaunchedEffect(Unit) { repo.avisos.collect { snackbar.showSnackbar("${it.titulo}: ${it.mensaje}") } }

    // Se tocó una notificación del celular: se abre su reserva (o la lista de avisos) y el aviso queda leído.
    val avisoTocado by AccionesExternas.avisoTocado.collectAsState()
    LaunchedEffect(avisoTocado) {
        val t = avisoTocado ?: return@LaunchedEffect
        t.avisoId?.let { id -> scope.launch { repo.leerNotificacion(id) } }
        val uuid = t.reservaUuid
        if (uuid != null) reservaAbierta = uuid
        else if (Destino.NOTIFICACIONES in destinos) { destino = Destino.NOTIFICACIONES; reservaAbierta = null }
        AccionesExternas.avisoTocado.value = null
    }

    val nav = Navegacion(
        abrirReserva = { reservaAbierta = it },
        ir = { destino = it; reservaAbierta = null },
        mensaje = { texto -> scope.launch { snackbar.showSnackbar(texto) } },
    )

    // Botón «atrás» de Android (y Esc en la PC): cierra el detalle y luego vuelve a la primera pantalla.
    val alVolver = ReglasNavegacion.alVolver(destino.name, destinos.first().name, reservaAbierta != null)
    BackHandler(enabled = alVolver != null) {
        when (alVolver) {
            ReglasNavegacion.Volver.CERRAR_DETALLE -> reservaAbierta = null
            ReglasNavegacion.Volver.IR_AL_INICIO -> destino = destinos.first()
            null -> Unit
        }
    }

    if (confirmarSalida) {
        AlertDialog(onDismissRequest = { confirmarSalida = false }, title = { Text("Cerrar sesión") },
            text = { Text(if (pendientes > 0) "Hay $pendientes operaciones sin sincronizar. Se conservarán en este equipo y se enviarán cuando vuelvas a entrar con esta cuenta." else "¿Cerrar la sesión en este equipo?") },
            confirmButton = { BotonPrincipal(onClick = { confirmarSalida = false; scope.launch { gestor.cerrarSesion() } }) { Text("Cerrar sesión") } },
            dismissButton = { BotonTexto(onClick = { confirmarSalida = false }) { Text("Volver") } })
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { relleno ->
        Column(Modifier.fillMaxSize().padding(relleno)) {
            BarraSuperior(sesion, pendientes, ancho, noLeidas, onSalir = { confirmarSalida = true }, onSync = { destino = Destino.SINCRONIZACION; reservaAbierta = null },
                onAvisos = { destino = Destino.NOTIFICACIONES; reservaAbierta = null })
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (ancho) {
                    MenuLateral(destinos, destino, pendientes, noLeidas) { destino = it; reservaAbierta = null }
                    VerticalDivider(color = Colores.borde)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val abierta = reservaAbierta
                    if (abierta != null) PantallaDetalleReserva(abierta, sesion, nav, onVolver = { reservaAbierta = null })
                    else Contenido(destino, sesion, nav)
                }
            }
            if (!ancho) MenuInferior(destinos, destino, noLeidas) { destino = it; reservaAbierta = null }
        }
    }
}

data class Navegacion(val abrirReserva: (String) -> Unit, val ir: (Destino) -> Unit, val mensaje: (String) -> Unit)

@Composable
private fun Contenido(d: Destino, sesion: SesionDto, nav: Navegacion) {
    when (d) {
        Destino.PANEL -> PantallaPanel(sesion, nav)
        Destino.AGENDA -> PantallaAgenda(sesion, nav)
        Destino.LLAMADA -> PantallaLlamada(nav)
        Destino.CLIENTES -> PantallaClientes(nav)
        Destino.CAJA -> PantallaCaja(sesion, nav)
        Destino.REPORTES -> PantallaReportes(nav)
        Destino.CONFIGURACION -> PantallaConfiguracion(nav)
        Destino.USUARIOS -> PantallaUsuarios(sesion, nav)
        Destino.SINCRONIZACION -> PantallaSincronizacion(nav)
        Destino.NOTIFICACIONES -> PantallaNotificaciones(nav)
        Destino.HORARIOS -> PantallaHorariosCliente(sesion, nav)
        Destino.MIS_RESERVAS -> PantallaMisReservas(nav)
        Destino.CUENTA -> PantallaCuenta(sesion)
    }
}

@Composable
private fun BarraSuperior(sesion: SesionDto, pendientes: Long, ancho: Boolean, noLeidas: Int, onSalir: () -> Unit, onSync: () -> Unit, onAvisos: () -> Unit) {
    val repo = koinInject<Repositorio>()
    val enLinea by repo.enLinea.collectAsState()
    val estado by repo.estadoSync.collectAsState()
    Row(Modifier.fillMaxWidth().background(Colores.azul).padding(horizontal = if (ancho) 20.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Logo(if (ancho) 34.dp else 30.dp)
        Column(Modifier.weight(1f)) {
            Text(sesion.negocio?.nombre?.takeIf { it.isNotBlank() } ?: "CanchaVoley", color = Color.White, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (ancho) 17.sp else 15.sp)
            Text("${sesion.usuario.nombre} · ${textoRol(sesion.usuario.rol)}", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Estado de la conexión: toca para ver la sincronización.
        Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onSync)
            .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (enLinea) Iconos.nubeOk else Iconos.sinNube, null, Modifier.size(18.dp),
                tint = if (enLinea) Color(0xFF9BE7A9) else Color(0xFFFFC2B8))
            if (ancho) {
                Text(when {
                    estado.sincronizando -> "Sincronizando…"
                    !enLinea -> "Sin conexión"
                    else -> "En línea"
                }, color = Color.White, fontSize = 13.sp)
            }
            if (pendientes > 0) Badge(containerColor = Colores.amarillo) { Text("$pendientes", color = Color.Black) }
        }
        if (ancho) estado.ultimaSync?.let { Text("Últ. sync ${Fechas.hora(it)}", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp) }
        // Avisos sin leer (aprobaciones, pagos, confirmaciones…): toca para verlos.
        IconButton(onClick = onAvisos) {
            BadgedBox(badge = { if (noLeidas > 0) Badge(containerColor = Colores.amarillo) { Text(textoContador(noLeidas), color = Color.Black) } }) {
                Icon(Iconos.campana, if (noLeidas > 0) "$noLeidas notificaciones sin leer" else "Notificaciones", tint = Color.White)
            }
        }
        IconButton(onClick = onSalir) { Icon(Iconos.salir, "Cerrar sesión", tint = Color.White) }
    }
}

/** Contador corto para las insignias («9+» si son muchas). */
private fun textoContador(n: Int): String = if (n > 9) "9+" else n.toString()

fun textoRol(rol: String) = when (rol) { "admin" -> "Administradora"; "recepcion" -> "Recepción"; "cliente" -> "Cliente"; else -> rol }

@Composable
private fun MenuLateral(destinos: List<Destino>, actual: Destino, pendientes: Long, noLeidas: Int, onElegir: (Destino) -> Unit) {
    Column(Modifier.width(228.dp).fillMaxHeight().background(Color.White).verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var grupoAnterior: String? = null
        destinos.forEach { d ->
            if (d.grupo != grupoAnterior && destinos.map { it.grupo }.distinct().size > 1) {
                if (grupoAnterior != null) Spacer(Modifier.height(8.dp))
                Text(d.grupo.uppercase(), Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp), color = Colores.gris,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                grupoAnterior = d.grupo
            }
            val activo = d == actual
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (activo) Colores.azulClaro else Color.Transparent)
                .clickable { onElegir(d) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(d.icono, null, Modifier.size(20.dp), tint = if (activo) Colores.azul else Colores.gris)
                Text(d.titulo, Modifier.weight(1f), color = if (activo) Colores.azulOscuro else Colores.texto,
                    fontWeight = if (activo) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d == Destino.SINCRONIZACION && pendientes > 0) Badge(containerColor = Colores.amarillo) { Text("$pendientes", color = Color.Black) }
                if (d == Destino.NOTIFICACIONES && noLeidas > 0) Badge(containerColor = Colores.azul) { Text(textoContador(noLeidas), color = Color.White) }
            }
        }
    }
}

@Composable
private fun MenuInferior(destinos: List<Destino>, actual: Destino, noLeidas: Int, onElegir: (Destino) -> Unit) {
    var verMas by remember { mutableStateOf(false) }
    val principales = destinos.take(if (destinos.size > 5) 4 else destinos.size)
    val resto = destinos.drop(principales.size)
    if (verMas) {
        AlertDialog(onDismissRequest = { verMas = false }, title = { Text("Más opciones") }, text = {
            Column {
                resto.forEach { d ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { verMas = false; onElegir(d) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(d.icono, null, Modifier.size(22.dp), tint = if (d == actual) Colores.azul else Colores.gris)
                        Text(d.titulo, Modifier.weight(1f), fontWeight = if (d == actual) FontWeight.SemiBold else FontWeight.Normal)
                        if (d == Destino.NOTIFICACIONES && noLeidas > 0) Badge(containerColor = Colores.azul) { Text(textoContador(noLeidas), color = Color.White) }
                    }
                    HorizontalDivider(color = Colores.grisClaro)
                }
            }
        }, confirmButton = { BotonTexto(onClick = { verMas = false }) { Text("Cerrar") } })
    }
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        val colores = NavigationBarItemDefaults.colors(selectedIconColor = Colores.azul, selectedTextColor = Colores.azulOscuro,
            indicatorColor = Colores.azulClaro, unselectedIconColor = Colores.gris, unselectedTextColor = Colores.gris)
        principales.forEach { d ->
            NavigationBarItem(selected = d == actual, onClick = { onElegir(d) }, colors = colores,
                icon = {
                    BadgedBox(badge = { if (d == Destino.NOTIFICACIONES && noLeidas > 0) Badge { Text(textoContador(noLeidas)) } }) { Icon(d.icono, d.titulo) }
                }, label = { Text(d.corto, maxLines = 1, fontSize = 11.sp) })
        }
        if (resto.isNotEmpty()) {
            NavigationBarItem(selected = actual in resto, onClick = { verMas = true }, colors = colores,
                icon = { BadgedBox(badge = { if (Destino.NOTIFICACIONES in resto && noLeidas > 0) Badge() }) { Icon(Iconos.mas, "Más") } },
                label = { Text("Más", fontSize = 11.sp) })
        }
    }
}
