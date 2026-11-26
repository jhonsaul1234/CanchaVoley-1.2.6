package pe.canchavoley.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.canchavoley.app.plataforma.ConfigLocal
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.SesionDto
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Telefono

/** Cliente (Android): consultar horarios libres y solicitar una reserva (queda por aprobar). */
@Composable
fun PantallaHorariosCliente(sesion: SesionDto, nav: Navegacion) {
    var enviada by remember { mutableStateOf<Pair<ReservaDto, Boolean>?>(null) }
    Pantalla {
        TituloPantalla("Reservar la cancha", "Elige el día, la duración y una hora libre")
        val e = enviada
        if (e != null) {
            Tarjeta(titulo = if (e.second) "Solicitud guardada sin conexión" else "Solicitud enviada", icono = if (e.second) Iconos.sinNube else Iconos.listo) {
                Text(Fechas.rango(e.first.inicio, e.first.fin), fontWeight = FontWeight.SemiBold)
                Aviso(if (e.second) "Quedó guardada en tu celular como pendiente de validación. Cuando vuelva la conexión se enviará sola y el servidor " +
                          "revisará que el horario siga libre. Aún no es una reserva confirmada."
                      else "Tu solicitud queda pendiente hasta que la administradora la apruebe. Te llegará una notificación.")
                BotonPrincipal(onClick = { nav.abrirReserva(e.first.uuid) }) { Text("Ver mi solicitud") }
                BotonTexto(onClick = { enviada = null }, icono = Iconos.agregar) { Text("Hacer otra solicitud") }
            }
            return@Pantalla
        }
        Tarjeta(titulo = "Tu solicitud", icono = Iconos.calendario) {
            Text("La administradora la revisa y te llega un aviso cuando la aprueba. Mientras tanto el horario queda apartado para ti.", color = Colores.gris)
            FormularioReserva(sesion.cliente, esPersonal = false, onRegistrada = { r, off, _ -> enviada = r to off })
        }
    }
}

@Composable
fun PantallaMisReservas(nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val reservas by repo.todasLasReservas().collectAsState(emptyList())
    val ahora = rememberReloj()
    Pantalla {
        TituloPantalla("Mis reservas", "Historial y próximas") {
            BotonPrincipal(onClick = { nav.ir(Destino.HORARIOS) }, icono = Iconos.agregar) { Text("Nueva solicitud") }
        }
        val proximas = reservas.filter { it.fin > ahora }.sortedBy { it.inicio }
        val pasadas = reservas.filter { it.fin <= ahora }
        Tarjeta(titulo = "Próximas", icono = Iconos.calendario) {
            if (proximas.isEmpty()) Vacio("No tienes reservas próximas.", Iconos.calendario)
            proximas.forEach { FilaReserva(it, nav, conFecha = true) }
        }
        Tarjeta(titulo = "Historial", icono = Iconos.lista) {
            if (pasadas.isEmpty()) Vacio("Aún no hay historial.")
            pasadas.forEach { FilaReserva(it, nav, conFecha = true) }
        }
    }
}

@Composable
fun PantallaCuenta(sesion: SesionDto) {
    val config = koinInject<ConfigLocal>()
    Pantalla {
        TituloPantalla("Mi cuenta")
        Tarjeta(titulo = "Tus datos", icono = Iconos.cuenta) {
            FilaDato("Nombre", sesion.usuario.nombre)
            FilaDato("Correo", sesion.usuario.email)
            sesion.cliente?.let { FilaDato("Celular", Telefono.mostrar(it.telefono)) }
            sesion.negocio?.let { FilaDato("Negocio", it.nombre) }
            FilaDato("Servidor", config.servidor())
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Aviso("Puedes consultar tus reservas sin internet; las solicitudes nuevas se envían solas al volver la conexión. " +
                "Para cambiar una reserva confirmada comunícate con el negocio.", Colores.azulClaro, Colores.azul)
        }
    }
}
