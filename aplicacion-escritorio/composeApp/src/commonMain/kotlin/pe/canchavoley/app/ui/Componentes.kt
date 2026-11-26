@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import pe.canchavoley.app.datos.ReservaLocal
import pe.canchavoley.app.dominio.CodigoQr
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Intervalo
import kotlin.time.Instant

/** true en pantallas anchas (PC o tablet en horizontal): menú lateral y formularios en columnas. */
val LocalAncho = compositionLocalOf { true }

private val formaControl = RoundedCornerShape(10.dp)

// ── Estados con color ────────────────────────────────────────────────────────

data class EstiloEstado(val texto: String, val fondo: Color, val color: Color)

fun estiloEstado(estado: String, pendientePor: String? = null, sinSincronizar: Boolean = false, plazoVencido: Boolean = false): EstiloEstado = when {
    // Registrada sin conexión: el servidor todavía no la valida (nunca se muestra como confirmada).
    estado == ReservaLocal.ESTADO_POR_SINCRONIZAR -> EstiloEstado("Por validar · sin conexión", Colores.azulGrisClaro, Color(0xFF37474F))
    estado == "pendiente" && pendientePor == "adelanto" && plazoVencido -> EstiloEstado("Plazo del adelanto vencido", Colores.rojoClaro, Colores.rojo)
    estado == "confirmada" -> EstiloEstado(if (sinSincronizar) "Confirmada · cambio pendiente" else "Confirmada", Colores.verdeClaro, Colores.verde)
    estado == "pendiente" && pendientePor == "aprobacion" -> EstiloEstado("Por aprobar", Colores.ambarClaro, Colores.ambar)
    estado == "pendiente" && pendientePor == "adelanto" -> EstiloEstado("Falta adelanto", Colores.ambarClaro, Colores.ambar)
    estado == "pendiente" -> EstiloEstado("Pendiente", Colores.ambarClaro, Colores.ambar)
    estado == "cancelada" -> EstiloEstado("Cancelada", Colores.grisClaro, Colores.gris)
    estado == "rechazada" -> EstiloEstado("Rechazada", Colores.rojoClaro, Colores.rojo)
    else -> EstiloEstado(estado, Colores.grisClaro, Colores.gris)
}

@Composable
fun Etiqueta(texto: String, fondo: Color, color: Color, modifier: Modifier = Modifier) {
    Text(texto, modifier.clip(RoundedCornerShape(50)).background(fondo).padding(horizontal = 10.dp, vertical = 3.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
fun EtiquetaEstado(estado: String, pendientePor: String? = null, sinSincronizar: Boolean = false, plazoVencido: Boolean = false) {
    val e = estiloEstado(estado, pendientePor, sinSincronizar, plazoVencido)
    Etiqueta(e.texto, e.fondo, e.color)
}

fun textoEstadoPago(e: String?): String = when (e) {
    "sin_pago" -> "Sin pago"
    "con_adelanto" -> "Con adelanto"
    "pagado" -> "Pagado"
    "saldo_a_favor" -> "Saldo a favor"
    "sin_precio" -> "Precio por definir"
    else -> "—"
}

fun textoOrigen(o: String?): String = pe.canchavoley.app.dominio.Documentos.textoOrigen(o)

fun textoMetodo(m: String) = when (m) { "efectivo" -> "Efectivo"; "yape" -> "Yape"; "plin" -> "Plin"; else -> m }

// ── Contenedores ─────────────────────────────────────────────────────────────

@Composable
fun Tarjeta(modifier: Modifier = Modifier, titulo: String? = null, icono: ImageVector? = null,
            accion: (@Composable RowScope.() -> Unit)? = null, contenido: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, Colores.borde)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (titulo != null || accion != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (icono != null) Icon(icono, null, Modifier.size(20.dp), tint = Colores.azul)
                    if (titulo != null) Text(titulo, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    accion?.invoke(this)
                }
            }
            contenido()
        }
    }
}

@Composable
fun Indicador(titulo: String, valor: String, detalle: String? = null, color: Color = Colores.azul, modifier: Modifier = Modifier,
              icono: ImageVector? = null) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, Colores.borde)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icono != null) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icono, null, Modifier.size(22.dp), tint = color)
                }
            }
            Column {
                Text(titulo, style = MaterialTheme.typography.labelMedium, color = Colores.gris)
                Text(valor, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
                if (detalle != null) Text(detalle, style = MaterialTheme.typography.bodySmall, color = Colores.gris)
            }
        }
    }
}

@Composable
fun FilaDato(etiqueta: String, valor: String, destacado: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(etiqueta, color = Colores.gris, style = MaterialTheme.typography.bodyMedium)
        Text(valor, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (destacado) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
fun Cargando(texto: String = "Cargando…") {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(texto, color = Colores.gris)
        }
    }
}

@Composable
fun Vacio(texto: String, icono: ImageVector? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icono != null) Icon(icono, null, Modifier.size(32.dp), tint = Colores.borde)
        Text(texto, color = Colores.gris, textAlign = TextAlign.Center)
    }
}

@Composable
fun Aviso(texto: String, fondo: Color = Colores.ambarClaro, color: Color = Colores.ambar, modifier: Modifier = Modifier,
          icono: ImageVector? = Iconos.alerta) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(fondo).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icono != null) Icon(icono, null, Modifier.size(18.dp), tint = color)
        Text(texto, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun TituloPantalla(titulo: String, subtitulo: String? = null, acciones: @Composable RowScope.() -> Unit = {}) {
    val ancho = LocalAncho.current
    val cabecera: @Composable () -> Unit = {
        Column {
            Text(titulo, style = MaterialTheme.typography.headlineSmall)
            if (subtitulo != null) Text(subtitulo, color = Colores.gris, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (ancho) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { cabecera() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = acciones)
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cabecera()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = acciones)
            }
        }
    }
}

/** Contenido de cada pantalla: con desplazamiento, márgenes según el ancho y un ancho máximo en la PC. */
@Composable
fun Pantalla(contenido: @Composable ColumnScope.() -> Unit) {
    val ancho = LocalAncho.current
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1180.dp).fillMaxWidth().padding(if (ancho) 24.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = contenido)
    }
}

/** Fila en la PC y columna en el celular (formularios con varios campos). */
@Composable
fun FilaAdaptable(modifier: Modifier = Modifier, anchoMinimo: Dp = 560.dp, contenido: @Composable (fila: Boolean) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fila = maxWidth >= anchoMinimo
        if (fila) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) { contenido(true) }
        else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { contenido(false) }
    }
}

// ── Botones ──────────────────────────────────────────────────────────────────

@Composable
fun BotonPrincipal(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icono: ImageVector? = null,
                   colorFondo: Color = Colores.azul, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.heightIn(min = 42.dp), enabled = enabled, shape = formaControl,
        colors = ButtonDefaults.buttonColors(containerColor = colorFondo),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        if (icono != null) { Icon(icono, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        content()
    }
}

@Composable
fun BotonSecundario(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icono: ImageVector? = null,
                    content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier.heightIn(min = 42.dp), enabled = enabled, shape = formaControl,
        border = BorderStroke(1.dp, if (enabled) Color(0xFFC9D1DC) else Colores.borde),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        if (icono != null) { Icon(icono, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        content()
    }
}

/** Botón cuadrado solo con ícono (con borde). */
@Composable
fun BotonIcono(icono: ImageVector, descripcion: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
               color: Color = Colores.azul) {
    OutlinedIconButton(onClick = onClick, modifier = modifier.size(42.dp), enabled = enabled, shape = formaControl,
        border = BorderStroke(1.dp, Color(0xFFC9D1DC))) {
        Icon(icono, descripcion, Modifier.size(20.dp), tint = if (enabled) color else Colores.borde)
    }
}

@Composable
fun BotonTexto(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icono: ImageVector? = null,
               color: Color = Colores.azul, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier, enabled = enabled, shape = formaControl,
        colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        if (icono != null) { Icon(icono, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        content()
    }
}

// ── Campos ───────────────────────────────────────────────────────────────────

@Composable
fun Campo(valor: String, onCambio: (String) -> Unit, etiqueta: String, modifier: Modifier = Modifier.fillMaxWidth(),
          numerico: Boolean = false, telefono: Boolean = false, clave: Boolean = false, error: String? = null,
          lineas: Int = 1, soloLectura: Boolean = false, ayuda: String? = null, icono: ImageVector? = null,
          alEnviar: (() -> Unit)? = null, verClave: Boolean = false) {
    OutlinedTextField(
        value = valor, onValueChange = onCambio, label = { Text(etiqueta) }, modifier = modifier,
        singleLine = lineas == 1, minLines = lineas, readOnly = soloLectura, isError = error != null,
        shape = formaControl,
        leadingIcon = icono?.let { { Icon(it, null, Modifier.size(20.dp), tint = Colores.gris) } },
        supportingText = (error ?: ayuda)?.let { { Text(it) } },
        visualTransformation = if (clave && !verClave) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                clave -> KeyboardType.Password; telefono -> KeyboardType.Phone; numerico -> KeyboardType.Decimal; else -> KeyboardType.Text
            },
            imeAction = if (alEnviar != null) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { alEnviar?.invoke() }),
    )
}

@Composable
fun <T> Opciones(opciones: List<Pair<T, String>>, seleccion: T?, onSeleccion: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        opciones.forEach { (valor, texto) ->
            FilterChip(selected = valor == seleccion, onClick = { onSeleccion(valor) }, label = { Text(texto) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Colores.azulClaro, selectedLabelColor = Colores.azulOscuro))
        }
    }
}

// ── Fecha y hora ─────────────────────────────────────────────────────────────

@Composable
fun SelectorFecha(fecha: LocalDate, onCambio: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BotonIcono(Iconos.izquierda, "Día anterior", onClick = { onCambio(fecha.plus(DatePeriod(days = -1))) })
        Column(Modifier.padding(horizontal = 8.dp).widthIn(min = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fechas.nombreDia(fecha.dayOfWeek).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
            Text("${fecha.day} de ${Fechas.nombreMes(fecha.month.ordinal + 1)}", color = Colores.gris, fontSize = 13.sp)
        }
        BotonIcono(Iconos.derecha, "Día siguiente", onClick = { onCambio(fecha.plus(DatePeriod(days = 1))) })
        if (fecha != Fechas.hoy()) BotonTexto(onClick = { onCambio(Fechas.hoy()) }) { Text("Hoy") }
    }
}

/** Hora actual que se actualiza cada minuto (para «hoy», «próximas», etc. con la app abierta muchas horas). */
@Composable
fun rememberReloj(): Instant {
    val ahora by produceState(Fechas.ahora()) {
        while (true) {
            delay(30_000)
            value = Fechas.ahora()
        }
    }
    return ahora
}

// ── Intervalos sugeridos ─────────────────────────────────────────────────────

@Composable
fun ListaSugerencias(sugerencias: List<Intervalo>, onElegir: (Intervalo) -> Unit) {
    if (sugerencias.isEmpty()) return
    Text("Horarios libres sugeridos", style = MaterialTheme.typography.labelLarge, color = Colores.gris)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sugerencias.forEach { s ->
            val dia = Fechas.local(s.inicio).date
            val etiqueta = (if (dia == Fechas.hoy()) "" else Fechas.fechaCorta(dia) + " ") + "${Fechas.hora(s.inicio)}–${Fechas.hora(s.fin)}"
            Ficha(etiqueta, seleccionada = false) { onElegir(s) }
        }
    }
}

/**
 * Hora de inicio para tocar. Verde = libre según el servidor. [sinVerificar] (sin conexión): ámbar, porque
 * es libre solo según los datos guardados y el servidor la confirmará al sincronizar.
 */
@Composable
fun Ficha(texto: String, seleccionada: Boolean, sinVerificar: Boolean = false, onClick: () -> Unit) {
    val fuerte = if (sinVerificar) Colores.ambar else Colores.verde
    val claro = if (sinVerificar) Colores.ambarClaro else Colores.verdeClaro
    Text(texto,
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (seleccionada) fuerte else claro)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (seleccionada) Color.White else fuerte, fontWeight = FontWeight.SemiBold)
}

// ── QR y logo ────────────────────────────────────────────────────────────────

@Composable
fun ImagenQr(texto: String, tamano: Dp = 200.dp) {
    val qr = remember(texto) { runCatching { CodigoQr.codificar(texto) }.getOrNull() } ?: return
    Canvas(Modifier.size(tamano).background(Color.White).border(1.dp, Colores.grisClaro)) {
        val total = qr.tamano + 8
        val celda = size.minDimension / total
        for (f in 0 until qr.tamano) for (c in 0 until qr.tamano) {
            if (qr.oscuro(f, c)) drawRect(Color.Black, Offset((c + 4) * celda, (f + 4) * celda), Size(celda + 0.5f, celda + 0.5f))
        }
    }
}

/** Logo provisional: un balón de vóley dibujado (no depende de emojis ni de imágenes). */
@Composable
fun Logo(tamano: Dp = 40.dp, trazo: Color = Color.White, relleno: Color = Color.Transparent) {
    Canvas(Modifier.size(tamano)) { dibujarPelota(trazo, relleno) }
}

/**
 * La pelota del logo, dibujada en una cuadrícula de 48 que ocupa [lado] desde [origen]. La usan
 * [Logo] y el ícono de la ventana de Windows (mismo dibujo que los íconos del instalador y del APK).
 */
fun DrawScope.dibujarPelota(trazo: Color, relleno: Color = Color.Transparent, lado: Float = size.minDimension,
                            origen: Offset = Offset((size.width - lado) / 2f, (size.height - lado) / 2f)) {
    val e = lado / 48f
    fun p(x: Float, y: Float) = Offset(origen.x + x * e, origen.y + y * e)
    val grosor = 2.8f * e
    drawCircle(relleno, radius = 19f * e, center = p(24f, 24f))
    drawCircle(trazo, radius = 19f * e, center = p(24f, 24f), style = Stroke(grosor))
    listOf(
        floatArrayOf(13.29f, 20.10f, 19.33f, 6.57f, 33.50f, 7.55f),
        floatArrayOf(32.73f, 16.67f, 41.43f, 28.67f, 33.50f, 40.45f),
        floatArrayOf(25.98f, 35.23f, 11.24f, 36.76f, 5.00f, 24.00f),
    ).forEach { c ->
        val centro = p(24f, 24f)
        val (a, b, d) = listOf(p(c[0], c[1]), p(c[2], c[3]), p(c[4], c[5]))
        val camino = Path().apply { moveTo(centro.x, centro.y); cubicTo(a.x, a.y, b.x, b.y, d.x, d.y) }
        drawPath(camino, trazo, style = Stroke(grosor, cap = StrokeCap.Round))
    }
}

// ── Diálogos ─────────────────────────────────────────────────────────────────

@Composable
fun DialogoTexto(titulo: String, etiqueta: String, confirmar: String, onConfirmar: (String) -> Unit, onCerrar: () -> Unit,
                 minimo: Int = 3, explicacion: String? = null, inicial: String = "", numerico: Boolean = false) {
    var texto by remember { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (explicacion != null) Text(explicacion)
                Campo(texto, { texto = it }, etiqueta, lineas = if (numerico) 1 else 2, numerico = numerico)
                if (minimo > 1 && texto.trim().length in 1 until minimo) Text("Mínimo $minimo caracteres.", color = Colores.gris, fontSize = 12.sp)
            }
        },
        confirmButton = { BotonPrincipal(onClick = { onConfirmar(texto.trim()) }, enabled = texto.trim().length >= minimo) { Text(confirmar) } },
        dismissButton = { BotonTexto(onClick = onCerrar) { Text("Volver") } },
    )
}

@Composable
fun DialogoConfirmar(titulo: String, mensaje: String, confirmar: String, onConfirmar: () -> Unit, onCerrar: () -> Unit, peligro: Boolean = false) {
    AlertDialog(onDismissRequest = onCerrar, title = { Text(titulo) }, text = { Text(mensaje) },
        confirmButton = { BotonPrincipal(onClick = onConfirmar, colorFondo = if (peligro) Colores.rojo else Colores.azul) { Text(confirmar) } },
        dismissButton = { BotonTexto(onClick = onCerrar) { Text("Cancelar") } })
}

@Composable
fun PuntoEstado(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun TextoCorto(texto: String, modifier: Modifier = Modifier, peso: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified) {
    Text(texto, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = peso, color = color)
}

@Composable
fun Separacion(alto: Dp = 12.dp) = Spacer(Modifier.height(alto))
