package pe.canchavoley.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import pe.canchavoley.app.plataforma.rememberCompartirTexto
import pe.canchavoley.app.plataforma.rememberGuardarArchivo
import pe.canchavoley.app.dominio.Documentos
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import pe.canchavoley.app.dominio.Comprobante
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.SesionDto
import pe.canchavoley.app.datos.VistaCaja
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.plataforma.copiarTexto
import pe.canchavoley.app.plataforma.MENSAJE_PORTAPAPELES_OCUPADO

/** Pagos y caja del día (efectivo, Yape y Plin). */
@Composable
fun PantallaCaja(sesion: SesionDto, nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    var fecha by remember { mutableStateOf(Fechas.hoy()) }
    var caja by remember { mutableStateOf<VistaCaja?>(null) }
    val catalogo by repo.catalogo().collectAsState(null)
    val compartir = rememberCompartirTexto()
    val guardarArchivo = rememberGuardarArchivo()
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION") val portapapeles = LocalClipboardManager.current
    val pendientes by repo.operacionesPendientes().collectAsState(0L)
    LaunchedEffect(fecha, pendientes) { caja = repo.caja(fecha) }

    Pantalla {
        TituloPantalla("Pagos y caja", "Los pagos se registran desde el detalle de cada reserva") { SelectorFecha(fecha, { fecha = it }) }
        val c = caja ?: run { Cargando(); return@Pantalla }
        if (!c.desdeElServidor) Aviso("Sin conexión: caja calculada con los pagos guardados en este equipo.", Colores.azulGrisClaro, Colores.gris)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Indicador("Total del día", Dinero.soles(c.total), color = Colores.verde, modifier = Modifier.widthIn(min = 200.dp), icono = Iconos.dinero)
            Indicador("Efectivo", Dinero.soles(c.totales["efectivo"] ?: 0), modifier = Modifier.widthIn(min = 160.dp))
            Indicador("Yape", Dinero.soles(c.totales["yape"] ?: 0), modifier = Modifier.widthIn(min = 160.dp))
            Indicador("Plin", Dinero.soles(c.totales["plin"] ?: 0), modifier = Modifier.widthIn(min = 160.dp))
        }
        if (c.porUsuario.isNotEmpty()) {
            Tarjeta(titulo = "Cobrado por", icono = Iconos.cuenta) {
                c.porUsuario.forEach { (nombre, centimos) -> FilaDato(nombre, Dinero.soles(centimos)) }
            }
        }
        Tarjeta(titulo = "Movimientos (${c.pagos.size})", icono = Iconos.lista, accion = {
            val negocio = catalogo?.negocio?.nombre
            val texto = { Comprobante.cierreDeCaja(negocio, c.fecha, c.totales, c.total, c.pagos, c.porUsuario) }
            if (compartir != null) BotonTexto(onClick = { compartir("Cierre de caja", texto()) }, icono = Iconos.compartir) { Text("Compartir cierre") }
            else BotonTexto(onClick = { nav.mensaje(if (copiarTexto(portapapeles, texto())) "Cierre de caja copiado." else MENSAJE_PORTAPAPELES_OCUPADO) }, icono = Iconos.copiar) { Text("Copiar cierre") }
            // Cierre en PDF y movimientos en Excel (Windows: Documentos\CanchaVoley; Android: compartir)
            BotonTexto(onClick = {
                scope.launch {
                    val pdf = Documentos.cierreDeCajaPdf(negocio, c.fecha, c.totales, c.total, c.pagos, c.porUsuario, Fechas.ahora())
                    nav.mensaje(guardarArchivo(Documentos.nombreArchivo("Cierre de caja ${c.fecha}", "pdf"), Documentos.MIME_PDF, pdf))
                }
            }, icono = Iconos.descargar) { Text("PDF") }
            BotonTexto(onClick = {
                scope.launch {
                    val xlsx = Documentos.cajaExcel(negocio, c.fecha, c.totales, c.total, c.pagos, c.porUsuario)
                    nav.mensaje(guardarArchivo(Documentos.nombreArchivo("Caja ${c.fecha}", "xlsx"), Documentos.MIME_XLSX, xlsx))
                }
            }, icono = Iconos.descargar) { Text("Excel") }
        }) {
            if (c.pagos.isEmpty()) Vacio("No hay pagos este día.", Iconos.dinero)
            c.pagos.forEach { p ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(Fechas.hora(p.pagadoEn), color = Colores.gris)
                        Column(Modifier.weight(1f)) {
                            Text("${p.cliente ?: "Reserva"} · ${p.tipo.replaceFirstChar { it.uppercase() }} · ${textoMetodo(p.metodo)}", fontWeight = FontWeight.Medium)
                            Text(listOfNotNull(p.numeroComprobante, p.numeroOperacion?.let { "op. $it" }, p.registradoPor).joinToString(" · "), color = Colores.gris)
                        }
                        if (p.estado == "anulado") Etiqueta("Anulado", Colores.rojoClaro, Colores.rojo)
                        Text((if (p.tipo == "devolucion") "−" else "") + Dinero.soles(p.monto), fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = Colores.grisClaro)
                }
            }
        }
    }
}
