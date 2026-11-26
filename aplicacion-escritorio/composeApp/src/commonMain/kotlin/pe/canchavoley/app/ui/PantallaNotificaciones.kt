package pe.canchavoley.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.NotificacionCvDto
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.Resultado
import pe.canchavoley.app.dominio.Fechas

/**
 * Avisos de la cuenta (aprobaciones, rechazos, pagos, confirmaciones, pagos informados…). Se ven también sin
 * conexión (última copia descargada). Los no leídos van resaltados; al tocar uno se marca como leído y, si es
 * de una reserva, se abre su detalle.
 */
@Composable
fun PantallaNotificaciones(nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    val datos by repo.notificacionesGuardadas().collectAsState(null)
    var actualizado by remember { mutableStateOf<Boolean?>(null) }
    var recarga by remember { mutableIntStateOf(0) }
    LaunchedEffect(recarga) { actualizado = repo.notificaciones().second }

    Pantalla {
        TituloPantalla("Notificaciones", datos?.let { if (it.noLeidas > 0) "${it.noLeidas} sin leer" else "Todo leído" }) {
            BotonSecundario(enabled = (datos?.noLeidas ?: 0) > 0, onClick = {
                scope.launch {
                    if (repo.leerNotificaciones() is Resultado.Ok) recarga++
                    else nav.mensaje("Para marcar todas como leídas necesitas conexión con el servidor.")
                }
            }, icono = Iconos.listo) { Text("Marcar todas como leídas") }
        }
        if (actualizado == false) {
            Aviso("Sin conexión: se muestran los avisos guardados en este equipo." +
                " Los nuevos llegarán al volver la conexión.", Colores.azulGrisClaro, Colores.gris, icono = Iconos.sinNube)
        }
        val d = datos
        if (d == null && actualizado == null) Cargando()
        Tarjeta {
            if (d == null || d.notificaciones.isEmpty()) {
                if (actualizado != null) Vacio("No tienes notificaciones.", Iconos.campana)
            } else {
                d.notificaciones.forEach { n ->
                    FilaAviso(n) {
                        scope.launch { if (!n.leida) repo.leerNotificacion(n.id) }
                        n.reservaUuid?.let { nav.abrirReserva(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilaAviso(n: NotificacionCvDto, onTocar: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (n.leida) Color.Transparent else Colores.azulClaro.copy(alpha = 0.55f))
        .clickable(onClick = onTocar).padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!n.leida) Box(Modifier.size(9.dp).clip(CircleShape).background(Colores.azul))
            Text(n.titulo, Modifier.weight(1f), fontWeight = if (n.leida) FontWeight.Normal else FontWeight.Bold)
            if (!n.leida) Etiqueta("Nuevo", Colores.azul, Color.White)
        }
        Text(n.mensaje, color = if (n.leida) Colores.gris else Colores.texto)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Fechas.fechaHora(n.fecha), color = Colores.gris, fontSize = 12.sp)
            if (n.reservaUuid != null) Text("Toca para ver la reserva", color = Colores.azul, fontSize = 12.sp)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 2.dp), color = Colores.grisClaro)
}
