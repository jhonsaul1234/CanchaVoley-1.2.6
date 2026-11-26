package pe.canchavoley.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
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
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.dominio.Fechas

/** Estado de la conexión y de las operaciones guardadas sin internet, con reintento y descarte. */
@Composable
fun PantallaSincronizacion(nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val scope = rememberCoroutineScope()
    val enLinea by repo.enLinea.collectAsState()
    val estado by repo.estadoSync.collectAsState()
    val ops by repo.listaOperaciones().collectAsState(emptyList())
    var resultado by remember { mutableStateOf<String?>(null) }
    var descartar by remember { mutableStateOf<String?>(null) }

    descartar?.let { op ->
        DialogoConfirmar("Descartar operación",
            "Se quitará de la cola y NO se enviará al servidor. Úsalo solo si el servidor la rechaza siempre con el mismo error. " +
                "Si era una reserva nueva, quedará marcada como no enviada; si era un pago, se borrará de este equipo.",
            "Descartar", peligro = true,
            onConfirmar = { descartar = null; scope.launch { nav.mensaje(repo.descartarOperacion(op)) } },
            onCerrar = { descartar = null })
    }

    Pantalla {
        TituloPantalla("Sincronización", "Lo hecho sin internet se envía solo al volver la conexión") {
            BotonPrincipal(enabled = !estado.sincronizando, icono = Iconos.sincronizar,
                onClick = { scope.launch { resultado = repo.sincronizarAhora() } }) { Text(if (estado.sincronizando) "Sincronizando…" else "Sincronizar ahora") }
        }
        Tarjeta {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (enLinea) Iconos.nubeOk else Iconos.sinNube, null, Modifier.size(22.dp), tint = if (enLinea) Colores.verde else Colores.rojo)
                Text(if (enLinea) "Conectado con el servidor" else "Sin conexión con el servidor", fontWeight = FontWeight.SemiBold)
            }
            FilaDato("Última sincronización", estado.ultimaSync?.let { Fechas.fechaHora(it) } ?: "Nunca")
            FilaDato("Operaciones por enviar", "${ops.size}", destacado = ops.isNotEmpty())
            estado.error?.let { FilaDato("Último problema", it) }
            resultado?.let { Text(it, color = Colores.gris) }
        }
        Aviso("Cómo funciona: las reservas, clientes y pagos registrados sin internet se guardan en este equipo y quedan «sin sincronizar». " +
            "Al volver la conexión se envían en orden y el servidor los valida con las mismas reglas. Una reserva hecha sin conexión nunca se confirma " +
            "sola: si el horario ya estaba tomado, queda rechazada y el sistema te avisa para llamar al cliente. Reenviar una operación no la duplica.",
            Colores.azulClaro, Colores.azul, icono = Iconos.sincronizar)
        Tarjeta(titulo = "Operaciones pendientes", icono = Iconos.lista) {
            if (ops.isEmpty()) Vacio("No hay operaciones pendientes.", Iconos.nubeOk)
            ops.forEachIndexed { i, o ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}. ${textoOperacion(o.tipo)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if (o.ultimoError != null) Etiqueta("Con error", Colores.rojoClaro, Colores.rojo)
                    }
                    Text("Creada ${Fechas.fechaHora(o.creadaEn)}" + if (o.intentos > 0) " · ${o.intentos} intento(s)" else "", color = Colores.gris)
                    o.ultimoError?.let { Text(it, color = Colores.rojo) }
                    if (o.ultimoError != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BotonTexto(enabled = !estado.sincronizando, icono = Iconos.sincronizar,
                                onClick = { scope.launch { resultado = repo.sincronizarAhora() } }) { Text("Reintentar ahora") }
                            if (o.intentos >= 2) BotonTexto(icono = Iconos.borrar, color = Colores.rojo, onClick = { descartar = o.opUuid }) { Text("Descartar") }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp), color = Colores.grisClaro)
                }
            }
            if (ops.any { it.ultimoError != null }) {
                Text("Las operaciones se envían en orden: mientras la primera tenga error, las siguientes esperan.", color = Colores.gris, fontSize = 12.sp)
            }
        }
    }
}

fun textoOperacion(tipo: String) = when (tipo) {
    "crear_cliente" -> "Nuevo cliente"
    "actualizar_cliente" -> "Cambios de cliente"
    "crear_reserva" -> "Nueva reserva"
    "reprogramar_reserva" -> "Cambio de horario"
    "cancelar_reserva" -> "Cancelación"
    "aprobar_reserva" -> "Aprobación"
    "rechazar_reserva" -> "Rechazo"
    "registrar_pago" -> "Pago"
    "marcar_asistencia" -> "Asistencia"
    "editar_reserva" -> "Precio o notas de una reserva"
    else -> tipo
}
