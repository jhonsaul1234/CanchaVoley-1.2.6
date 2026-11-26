package pe.canchavoley.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import pe.canchavoley.app.plataforma.rememberCompartirTexto
import pe.canchavoley.app.plataforma.rememberGuardarArchivo
import pe.canchavoley.app.dominio.Documentos
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import pe.canchavoley.app.dominio.Semana
import pe.canchavoley.app.dominio.Exportar
import kotlinx.datetime.plus
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.collectAsState
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.ReporteOcupacionDto
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.Resultado
import pe.canchavoley.app.datos.comoListaDeEnteros
import pe.canchavoley.app.datos.comoMapaDeEnteros
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.plataforma.copiarTexto
import pe.canchavoley.app.plataforma.MENSAJE_PORTAPAPELES_OCUPADO

/** Estadísticas de ocupación, ingresos, horas pico y demanda no atendida (administradora). */
@Composable
fun PantallaReportes(nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val catalogo by repo.catalogo().collectAsState(repo.catalogoActual())
    val compartir = rememberCompartirTexto()
    val guardarArchivo = rememberGuardarArchivo()
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION") val portapapeles = LocalClipboardManager.current
    val hoy = remember { Fechas.hoy() }
    val mesActual = remember { LocalDate(hoy.year, hoy.month, 1) }
    var periodo by remember { mutableStateOf("7") }
    var mes by remember { mutableStateOf(mesActual) }
    var reporte by remember { mutableStateOf<ReporteOcupacionDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(periodo, mes) {
        val (desde, hasta) = when (periodo) {
            "7" -> hoy.minus(DatePeriod(days = 6)) to hoy
            "30" -> hoy.minus(DatePeriod(days = 29)) to hoy
            else -> Semana.mes(mes, hoy)
        }
        reporte = null; error = null
        when (val r = repo.reporte(desde, hasta)) {
            is Resultado.Ok -> reporte = r.valor
            is Resultado.Error -> error = r.mensaje
            Resultado.SinConexion -> error = "Los reportes se calculan en el servidor: necesitas conexión."
        }
    }
    Pantalla {
        TituloPantalla("Reportes", reporte?.let { "Del ${it.desde} al ${it.hasta}" }) {
            reporte?.let { r ->
                val texto = { Exportar.reporte(r, catalogo?.negocio?.nombre) }
                BotonTexto(onClick = {
                    scope.launch {
                        val xlsx = Documentos.reporteExcel(r, catalogo?.negocio?.nombre)
                        nav.mensaje(guardarArchivo(Documentos.nombreArchivo("Reporte ${r.desde} al ${r.hasta}", "xlsx"), Documentos.MIME_XLSX, xlsx))
                    }
                }, icono = Iconos.descargar) { Text("Excel") }
                if (compartir != null) BotonTexto(onClick = { compartir("Reporte del ${r.desde} al ${r.hasta}", texto()) }, icono = Iconos.compartir) { Text("Compartir") }
                else BotonTexto(onClick = { nav.mensaje(if (copiarTexto(portapapeles, texto())) "Reporte copiado: pégalo en Excel con Ctrl+V." else MENSAJE_PORTAPAPELES_OCUPADO) },
                    icono = Iconos.copiar) { Text("Copiar") }
            }
        }
        Opciones(listOf("7" to "Últimos 7 días", "30" to "Últimos 30 días", "mes" to "Por mes"), periodo, { periodo = it })
        if (periodo == "mes") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BotonIcono(Iconos.izquierda, "Mes anterior", onClick = { mes = mes.minus(DatePeriod(months = 1)) })
                Text("${Fechas.nombreMes(mes.month.ordinal + 1).replaceFirstChar { it.uppercase() }} de ${mes.year}",
                    Modifier.widthIn(min = 150.dp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                BotonIcono(Iconos.derecha, "Mes siguiente", enabled = mes < mesActual, onClick = { mes = mes.plus(DatePeriod(months = 1)) })
                if (mes != mesActual) BotonTexto(onClick = { mes = mesActual }) { Text("Este mes") }
            }
        }
        error?.let { Aviso(it, Colores.azulGrisClaro, Colores.gris) }
        val r = reporte ?: run { if (error == null) Cargando(); return@Pantalla }
        if (r.sinHorario.isNotEmpty()) Aviso("Sin horario configurado: ${r.sinHorario.joinToString()}. La ocupación necesita el horario de atención.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Indicador("Ocupación", r.ocupacionPct?.let { "$it%" } ?: "—", "${r.horasReservadas} h de ${r.horasAbiertas} h", modifier = Modifier.widthIn(min = 170.dp))
            Indicador("Ingresos", Dinero.soles(r.ingresosTotal), "neto de devoluciones", Colores.verde, Modifier.widthIn(min = 170.dp))
            Indicador("Horas pico", r.horasPico.joinToString { "${it}:00" }.ifBlank { "—" }, "más reservadas", modifier = Modifier.widthIn(min = 170.dp))
            Indicador("Demanda no atendida", "${r.demandaNoAtendida}", "rechazadas o canceladas", Colores.ambar, Modifier.widthIn(min = 170.dp))
        }
        Tarjeta(titulo = "Ocupación por día") {
            r.porDia.forEach { d -> Barra(d.fecha.substring(5), (d.ocupacionPct ?: 0.0) / 100.0, d.ocupacionPct?.let { "$it%" } ?: "cerrado") }
        }
        Tarjeta(titulo = "Horas más reservadas") {
            val minutos = r.minutosPorHora.comoListaDeEnteros()
            val max = (minutos.maxOrNull() ?: 0).coerceAtLeast(1)
            minutos.withIndex().filter { it.value > 0 }.forEach { (h, m) -> Barra("${Fechas.dos(h)}:00", m.toDouble() / max, Fechas.duracion(m)) }
            if (minutos.all { it == 0 }) Vacio("Sin reservas confirmadas en el periodo.")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tarjeta(Modifier.widthIn(min = 260.dp), titulo = "Ingresos por método") {
                listOf("efectivo", "yape", "plin").forEach { m -> FilaDato(textoMetodo(m), Dinero.soles(r.ingresos[m] ?: "0")) }
            }
            Tarjeta(Modifier.widthIn(min = 260.dp), titulo = "Cómo llegan las reservas") {
                r.reservasPorOrigen.comoMapaDeEnteros().forEach { (k, v) -> FilaDato(textoOrigen(k), "$v") }
            }
            Tarjeta(Modifier.widthIn(min = 260.dp), titulo = "Clientes frecuentes") {
                if (r.clientesFrecuentes.isEmpty()) Vacio("Sin datos.")
                r.clientesFrecuentes.forEach { FilaDato(it.cliente ?: "—", "${it.reservas} reservas · ${it.horas} h") }
            }
        }
    }
}

@Composable
private fun Barra(etiqueta: String, fraccion: Double, valor: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(etiqueta, Modifier.width(60.dp), fontSize = 13.sp, color = Colores.gris)
        Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(Colores.grisClaro)) {
            Box(Modifier.fillMaxWidth(fraccion.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(Colores.azul))
        }
        Text(valor, Modifier.width(90.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
