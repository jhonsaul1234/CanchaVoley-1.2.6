package pe.canchavoley.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Se muestra al abrir la app si la vez anterior ocurrió un error inesperado. El informe queda solo en
 * este equipo: la usuaria decide si lo comparte con quien da soporte. Sus datos guardados no se pierden.
 */
@Composable
fun PantallaFalloAnterior(
    informe: String,
    textoCompartir: String,
    onCompartir: () -> Unit,
    onContinuar: () -> Unit,
) {
    TemaCanchaVoley {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("La aplicación encontró un error inesperado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Colores.azul)
                Text("Tus datos guardados no se perdieron. Si el problema se repite, envía este informe a quien da soporte a la aplicación. " +
                    "El informe no incluye contraseñas.", color = Colores.gris)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotonPrincipal(onClick = onContinuar) { Text("Continuar") }
                    BotonSecundario(onClick = onCompartir) { Text(textoCompartir) }
                }
                SelectionContainer(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .border(1.dp, Colores.borde, RoundedCornerShape(10.dp))
                        .background(Colores.grisClaro, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(informe, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}
