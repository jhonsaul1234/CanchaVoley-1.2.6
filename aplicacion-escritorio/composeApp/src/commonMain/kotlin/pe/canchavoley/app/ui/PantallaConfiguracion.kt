@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.datetime.plus
import kotlinx.datetime.LocalTime
import pe.canchavoley.app.datos.BloqueoDto
import pe.canchavoley.app.dominio.Validaciones
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import pe.canchavoley.app.datos.CatalogoDto
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.dominio.Dinero
import pe.canchavoley.app.dominio.Fechas

/**
 * Configuración del negocio (solo la administradora): datos, canchas, horario de atención, tarifas,
 * reglas de reserva y cierres. Aquí se cargan los datos REALES del negocio; el sistema no trae valores inventados.
 */
@Composable
fun PantallaConfiguracion(nav: Navegacion) {
    val repo = koinInject<Repositorio>()
    val catalogo by repo.catalogo().collectAsState(null)
    var seccion by remember { mutableStateOf("horario") }
    LaunchedEffect(Unit) { repo.refrescarCatalogo() }
    val cat = catalogo ?: CatalogoDto()
    var canchaId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(cat.canchas.firstOrNull()?.id) { if (canchaId == null) canchaId = cat.canchas.firstOrNull()?.id }

    Pantalla {
        TituloPantalla("Tarifas y horarios", "Datos reales del negocio · requiere conexión para guardar")
        Opciones(listOf("horario" to "Horario de atención", "tarifas" to "Tarifas", "reglas" to "Reglas de reserva",
            "bloqueos" to "Cierres", "canchas" to "Canchas", "negocio" to "Negocio"), seccion, { seccion = it })
        if (seccion in listOf("horario", "tarifas") && cat.canchas.size > 1) {
            Opciones(cat.canchas.map { it.id to it.nombre }, canchaId, { canchaId = it })
        }
        val id = canchaId
        when (seccion) {
            "horario" -> if (id != null) EditorHorario(cat, id, nav) else Vacio("Primero registra una cancha.")
            "tarifas" -> if (id != null) EditorTarifas(cat, id, nav) else Vacio("Primero registra una cancha.")
            "reglas" -> EditorReglas(cat.reglas, nav)
            "bloqueos" -> EditorBloqueos(cat, nav)
            "canchas" -> EditorCanchas(cat, nav)
            "negocio" -> EditorNegocio(cat, nav)
        }
    }
}

private suspend fun avisar(r: ResultadoAccion<*>, nav: Navegacion, ok: String) {
    when (r) {
        is ResultadoAccion.Fallo -> nav.mensaje(r.mensaje)
        else -> nav.mensaje(ok)
    }
}

private data class Turno(val dia: Int, var abre: String, var cierra: String)

@Composable
private fun EditorHorario(cat: CatalogoDto, canchaId: Long, nav: Navegacion) {
    val scope = rememberCoroutineScope()
    val repo = koinInject<Repositorio>()
    val turnos = remember(canchaId, cat.horarios) {
        mutableStateListOf<Turno>().apply { cat.horarios.filter { it.canchaId == canchaId }.forEach { add(Turno(it.diaSemana, it.abre, it.cierra)) } }
    }
    var version by remember { mutableStateOf(0) }
    Tarjeta(titulo = "Horario de atención por día", icono = Iconos.reloj) {
        Text("Agrega uno o varios turnos por día (p. ej. mañana y noche). Un día sin turnos queda cerrado. Si cierra pasada la medianoche, escribe la hora de cierre del día siguiente (p. ej. 01:00).", color = Colores.gris)
        key(version) {
            (1..7).forEach { dia ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(Fechas.nombreDia(dia), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if (turnos.none { it.dia == dia }) Etiqueta("Cerrado", Colores.grisClaro, Colores.gris)
                        BotonTexto(onClick = { turnos.add(Turno(dia, "", "")); version++ }, icono = Iconos.agregar) { Text("Turno") }
                    }
                    turnos.filter { it.dia == dia }.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            var a by remember { mutableStateOf(t.abre) }
                            var c by remember { mutableStateOf(t.cierra) }
                            Campo(a, { a = it; t.abre = it }, "Abre", Modifier.weight(1f).widthIn(max = 160.dp))
                            Campo(c, { c = it; t.cierra = it }, "Cierra", Modifier.weight(1f).widthIn(max = 160.dp))
                            BotonIcono(Iconos.cerrar, "Quitar turno", onClick = { turnos.remove(t); version++ }, color = Colores.rojo)
                        }
                    }
                }
                HorizontalDivider(color = Colores.grisClaro)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSecundario(onClick = {
                val lunes = turnos.filter { it.dia == 1 }
                if (lunes.isEmpty()) nav.mensaje("Primero completa el lunes.") else {
                    turnos.removeAll { it.dia != 1 }
                    (2..7).forEach { d -> lunes.forEach { turnos.add(Turno(d, it.abre, it.cierra)) } }
                    version++
                }
            }, icono = Iconos.copiar) { Text("Copiar el lunes a todos los días") }
            BotonPrincipal(icono = Iconos.listo, onClick = {
                val error = (1..7).firstNotNullOfOrNull { d ->
                    Validaciones.turnos(turnos.filter { it.dia == d }.map { it.abre to it.cierra })?.let { "${Fechas.nombreDia(d)}: $it" }
                }
                if (error != null) { nav.mensaje(error); return@BotonPrincipal }
                scope.launch {
                    avisar(repo.guardarHorarios(canchaId, turnos.map { mapOf("dia_semana" to it.dia, "abre" to norm(it.abre), "cierra" to norm(it.cierra)) }), nav, "Horario guardado.")
                }
            }) { Text("Guardar horario") }
        }
    }
}

private fun norm(h: String) = Fechas.leerHora(h)?.let { "${Fechas.dos(it.hour)}:${Fechas.dos(it.minute)}" } ?: h

private data class Franja(val id: Int, val dias: MutableSet<Int>, var desde: String, var hasta: String, var precio: String)

@Composable
private fun EditorTarifas(cat: CatalogoDto, canchaId: Long, nav: Navegacion) {
    val scope = rememberCoroutineScope()
    val repo = koinInject<Repositorio>()
    val franjas = remember(canchaId, cat.tarifas) {
        // Agrupa las tarifas vigentes con el mismo horario y precio en una sola franja de varios días.
        val grupos = cat.tarifas.filter { it.canchaId == canchaId && it.vigenteHasta == null }.groupBy { Triple(it.desde, it.hasta, it.precioHora) }
        mutableStateListOf<Franja>().apply {
            grupos.entries.forEachIndexed { i, (k, v) -> add(Franja(i, v.map { it.diaSemana }.toMutableSet(), k.first, k.second, k.third)) }
        }
    }
    var version by remember { mutableStateOf(0) }
    var vigenteDesde by remember { mutableStateOf(Fechas.hoy().toString()) }
    Tarjeta(titulo = "Tarifas por franja horaria (S/ por hora)", icono = Iconos.dinero) {
        Text("Cada franja indica los días, el horario y el precio por hora. Use 00:00 como «hasta» para la medianoche. Las reservas ya hechas conservan su precio.", color = Colores.gris)
        if (cat.reglas.reglaCruceFranjas == null) Aviso("Falta definir cómo se cobra una reserva que cruza dos franjas (en «Reglas de reserva»). Mientras tanto, en esos casos se pedirá el precio acordado.", Colores.azulClaro, Colores.azul)
        if (franjas.isEmpty()) Vacio("Aún no hay tarifas: agrega la primera franja.", Iconos.dinero)
        key(version) {
            franjas.forEachIndexed { i, f ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Franja ${i + 1}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        BotonTexto(onClick = { franjas.remove(f); version++ }, icono = Iconos.borrar, color = Colores.rojo) { Text("Quitar") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        (1..7).forEach { d ->
                            var marcado by remember { mutableStateOf(d in f.dias) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(marcado, { marcado = it; if (it) f.dias.add(d) else f.dias.remove(d) })
                                Text(Fechas.nombreDia(d).take(3))
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        var de by remember { mutableStateOf(f.desde) }
                        var ha by remember { mutableStateOf(f.hasta) }
                        var pr by remember { mutableStateOf(f.precio) }
                        Campo(de, { de = it; f.desde = it }, "Desde", Modifier.width(120.dp))
                        Campo(ha, { ha = it; f.hasta = it }, "Hasta", Modifier.width(120.dp))
                        Campo(pr, { pr = it; f.precio = it }, "S/ por hora", Modifier.width(150.dp), numerico = true)
                    }
                    HorizontalDivider(color = Colores.grisClaro)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSecundario(onClick = { franjas.add(Franja((franjas.maxOfOrNull { it.id } ?: 0) + 1, mutableSetOf(), "", "", "")); version++ }, icono = Iconos.agregar) { Text("Franja") }
            Campo(vigenteDesde, { vigenteDesde = it }, "Vigente desde (AAAA-MM-DD)", Modifier.width(250.dp),
                error = if (vigenteDesde.isNotBlank() && Validaciones.fecha(vigenteDesde) == null) "Ejemplo: 2026-12-01" else null)
            BotonPrincipal(icono = Iconos.listo, onClick = {
                val error = Validaciones.franjas(franjas.map { Triple(it.dias.toSet(), it.desde to it.hasta, it.precio) })
                    ?: if (vigenteDesde.isNotBlank() && Validaciones.fecha(vigenteDesde) == null) "La fecha «vigente desde» no es válida (AAAA-MM-DD)." else null
                if (error != null) { nav.mensaje(error); return@BotonPrincipal }
                val lista = franjas.flatMap { f -> f.dias.map { d -> Triple(d, f, Dinero.aCentimos(f.precio)!!) } }
                scope.launch {
                    avisar(repo.guardarTarifas(canchaId, lista.map { (d, f, c) ->
                        mapOf("dia_semana" to d, "desde" to norm(f.desde), "hasta" to norm(f.hasta), "precio_hora" to Dinero.aTexto(c))
                    }, vigenteDesde.takeIf { it.isNotBlank() }), nav, "Tarifas guardadas.")
                }
            }) { Text("Guardar tarifas") }
        }
    }
}

@Composable
private fun EditorReglas(reglas: ReglasDto, nav: Navegacion) {
    val scope = rememberCoroutineScope()
    val repo = koinInject<Repositorio>()
    fun t(v: Any?) = v?.toString().orEmpty()
    val c = remember(reglas) { mutableStateOf(mapOf(
        "duracion_minima_min" to t(reglas.duracionMinimaMin), "duracion_maxima_min" to t(reglas.duracionMaximaMin),
        "paso_min" to t(reglas.pasoMin), "alinear_inicio_min" to t(reglas.alinearInicioMin),
        "margen_entre_reservas_min" to t(reglas.margenEntreReservasMin), "anticipacion_minima_min" to t(reglas.anticipacionMinimaMin),
        "anticipacion_maxima_dias" to t(reglas.anticipacionMaximaDias), "adelanto_valor" to t(reglas.adelantoValor),
        "adelanto_plazo_min" to t(reglas.adelantoPlazoMin), "cancelacion_limite_horas" to t(reglas.cancelacionLimiteHoras),
        "cancelacion_porcentaje" to t(reglas.cancelacionPorcentaje), "reprogramacion_limite_horas" to t(reglas.reprogramacionLimiteHoras),
        "tolerancia_no_show_min" to t(reglas.toleranciaNoShowMin),
    )) }
    var cruce by remember(reglas) { mutableStateOf(reglas.reglaCruceFranjas) }
    var adelantoTipo by remember(reglas) { mutableStateOf(reglas.adelantoTipo) }
    var obligatorio by remember(reglas) { mutableStateOf(reglas.adelantoObligatorio) }
    var devolucion by remember(reglas) { mutableStateOf(reglas.cancelacionDevolucion) }
    @Composable fun campo(clave: String, etiqueta: String, ayuda: String? = null) =
        Campo(c.value[clave].orEmpty(), { v -> c.value = c.value + (clave to v.filter { it.isDigit() || it == '.' }) }, etiqueta, Modifier.widthIn(min = 220.dp, max = 300.dp), numerico = true, ayuda = ayuda)

    Tarjeta(titulo = "Reglas de reserva") {
        Aviso("Deja vacío lo que el negocio todavía no ha decidido: el sistema no supone ningún valor. Por ejemplo, sin adelanto configurado no se exige adelanto.", Colores.azulClaro, Colores.azul)
        Text("Duración y horarios", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            campo("duracion_minima_min", "Duración mínima (min)"); campo("duracion_maxima_min", "Duración máxima (min)")
            campo("paso_min", "Múltiplo de duración (min)", "p. ej. que sea de 30 en 30"); campo("alinear_inicio_min", "Inicio cada (min)", "p. ej. en punto = 60")
            campo("margen_entre_reservas_min", "Margen entre reservas (min)")
            campo("anticipacion_minima_min", "Anticipación mínima (min)", "solo solicitudes de la app"); campo("anticipacion_maxima_dias", "Anticipación máxima (días)", "solo solicitudes de la app")
        }
        Text("Cobro si una reserva cruza dos franjas de precio", fontWeight = FontWeight.SemiBold)
        Opciones(listOf<Pair<String?, String>>(null to "Sin definir", "prorrateo" to "Proporcional a cada franja", "franja_inicio" to "Precio de la franja de inicio"), cruce, { cruce = it })
        Text("Adelanto", fontWeight = FontWeight.SemiBold)
        Opciones(listOf<Pair<String?, String>>(null to "Sin adelanto definido", "monto" to "Monto fijo (S/)", "porcentaje" to "Porcentaje del precio"), adelantoTipo, { adelantoTipo = it })
        if (adelantoTipo != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                campo("adelanto_valor", if (adelantoTipo == "porcentaje") "Porcentaje (%)" else "Monto (S/)")
                campo("adelanto_plazo_min", "Plazo para pagarlo (min)", "vacío = sin plazo")
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(obligatorio == true, { obligatorio = it }); Text("Sin adelanto la reserva queda pendiente (no se confirma)") }
        }
        Text("Cancelaciones y faltas", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            campo("cancelacion_limite_horas", "El cliente cancela desde la app hasta (h antes)", "vacío = debe llamar al negocio")
            campo("reprogramacion_limite_horas", "Cambio de hora hasta (h antes)")
            campo("tolerancia_no_show_min", "Tolerancia de llegada (min)")
        }
        Opciones(listOf<Pair<String?, String>>(null to "Devolución sin definir", "total" to "Total", "parcial" to "Parcial", "ninguna" to "Ninguna", "credito" to "Como crédito"), devolucion, { devolucion = it })
        if (devolucion == "parcial") campo("cancelacion_porcentaje", "Porcentaje que se devuelve (%)")
        BotonPrincipal(onClick = {
            fun n(k: String): Any? = c.value[k]?.takeIf { it.isNotBlank() }?.let { v -> if (k in listOf("adelanto_valor", "cancelacion_porcentaje")) v else v.toIntOrNull() }
            val datos = mapOf(
                "duracion_minima_min" to n("duracion_minima_min"), "duracion_maxima_min" to n("duracion_maxima_min"), "paso_min" to n("paso_min"),
                "alinear_inicio_min" to n("alinear_inicio_min"), "margen_entre_reservas_min" to n("margen_entre_reservas_min"),
                "anticipacion_minima_min" to n("anticipacion_minima_min"), "anticipacion_maxima_dias" to n("anticipacion_maxima_dias"),
                "regla_cruce_franjas" to cruce, "adelanto_tipo" to adelantoTipo,
                "adelanto_valor" to if (adelantoTipo == null) null else n("adelanto_valor"),
                "adelanto_plazo_min" to if (adelantoTipo == null) null else n("adelanto_plazo_min"),
                "adelanto_obligatorio" to if (adelantoTipo == null) null else (obligatorio == true),
                "cancelacion_limite_horas" to n("cancelacion_limite_horas"), "cancelacion_devolucion" to devolucion,
                "cancelacion_porcentaje" to if (devolucion == "parcial") n("cancelacion_porcentaje") else null,
                "reprogramacion_limite_horas" to n("reprogramacion_limite_horas"), "tolerancia_no_show_min" to n("tolerancia_no_show_min"),
            )
            scope.launch { avisar(repo.guardarReglas(datos), nav, "Reglas guardadas.") }
        }, icono = Iconos.listo) { Text("Guardar reglas") }
    }
}

@Composable
private fun EditorBloqueos(cat: CatalogoDto, nav: Navegacion) {
    val scope = rememberCoroutineScope()
    val repo = koinInject<Repositorio>()
    var canchaId by remember { mutableStateOf<Long?>(null) }
    var fecha by remember { mutableStateOf(Fechas.hoy()) }
    var todoElDia by remember { mutableStateOf(false) }
    var desde by remember { mutableStateOf("") }
    var hasta by remember { mutableStateOf("") }
    var motivo by remember { mutableStateOf("") }
    var quitar by remember { mutableStateOf<BloqueoDto?>(null) }
    quitar?.let { b ->
        DialogoConfirmar("Quitar cierre", "La cancha volverá a estar disponible en ese horario (${Fechas.rango(b.inicio, b.fin)}).", "Quitar cierre",
            onConfirmar = { quitar = null; scope.launch { avisar(repo.eliminarBloqueo(b.id), nav, "Cierre eliminado.") } }, onCerrar = { quitar = null })
    }
    Tarjeta(titulo = "Cerrar la cancha (mantenimiento, feriado, evento)", icono = Iconos.alerta) {
        Opciones(listOf<Pair<Long?, String>>(null to "Todo el local") + cat.canchas.map { it.id to it.nombre }, canchaId, { canchaId = it })
        SelectorFecha(fecha, { fecha = it })
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(todoElDia, { todoElDia = it }); Text("Todo el día") }
        if (!todoElDia) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Campo(desde, { desde = it }, "Desde (HH:MM)", Modifier.width(150.dp))
                Campo(hasta, { hasta = it }, "Hasta (HH:MM)", Modifier.width(150.dp), ayuda = "00:00 = medianoche")
            }
        }
        Campo(motivo, { motivo = it }, "Motivo")
        BotonPrincipal(icono = Iconos.listo, onClick = {
            Validaciones.cierre(desde, hasta, todoElDia, motivo)?.let { nav.mensaje(it); return@BotonPrincipal }
            val manana = fecha.plus(kotlinx.datetime.DatePeriod(days = 1))
            val inicio = if (todoElDia) Fechas.instante(fecha, LocalTime(0, 0)) else Fechas.instante(fecha, Fechas.leerHora(desde)!!)
            val finHora = Fechas.leerHora(hasta)
            val fin = when {
                todoElDia || finHora == LocalTime(0, 0) -> Fechas.instante(manana, LocalTime(0, 0))
                else -> Fechas.instante(fecha, finHora!!)
            }
            scope.launch {
                when (val r = repo.crearBloqueo(mapOf("cancha_id" to canchaId, "inicio" to Fechas.iso(inicio), "fin" to Fechas.iso(fin), "motivo" to motivo.trim()))) {
                    is ResultadoAccion.Hecho -> {
                        nav.mensaje(if (r.valor.reservasAfectadas.isEmpty()) "Cierre registrado." else "Cierre registrado. Hay ${r.valor.reservasAfectadas.size} reserva(s) en ese horario: avisa a los clientes.")
                        motivo = ""; desde = ""; hasta = ""
                    }
                    is ResultadoAccion.Fallo -> nav.mensaje(r.mensaje)
                    else -> Unit
                }
            }
        }) { Text("Registrar cierre") }
    }
    Tarjeta(titulo = "Cierres registrados", icono = Iconos.lista) {
        val activos = cat.bloqueos.filter { !it.eliminado }.sortedBy { it.inicio }
        if (activos.isEmpty()) Vacio("No hay cierres.")
        activos.forEach { b ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Fechas.rango(b.inicio, b.fin)} · ${cat.canchas.firstOrNull { it.id == b.canchaId }?.nombre ?: "Todo el local"}", fontWeight = FontWeight.Medium)
                    Text(b.motivo, color = Colores.gris)
                }
                BotonTexto(onClick = { quitar = b }, icono = Iconos.borrar, color = Colores.rojo) { Text("Quitar") }
            }
            HorizontalDivider(color = Colores.grisClaro)
        }
    }
}

@Composable
private fun EditorCanchas(cat: CatalogoDto, nav: Navegacion) {
    val scope = rememberCoroutineScope()
    val repo = koinInject<Repositorio>()
    var nueva by remember { mutableStateOf("") }
    Tarjeta(titulo = "Canchas") {
        Text("Hoy el negocio tiene una cancha; el sistema ya está preparado para agregar más.", color = Colores.gris)
        cat.canchas.forEach { c ->
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var nombre by remember(c.id) { mutableStateOf(c.nombre) }
                Campo(nombre, { nombre = it }, "Nombre", Modifier.widthIn(min = 200.dp, max = 360.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(c.activa, { activa -> scope.launch { avisar(repo.actualizarCancha(c.id, mapOf("activa" to activa)), nav, if (activa) "Cancha activada." else "Cancha desactivada.") } })
                    Text("Activa")
                }
                BotonSecundario(onClick = { scope.launch { avisar(repo.actualizarCancha(c.id, mapOf("nombre" to nombre)), nav, "Nombre guardado.") } }) { Text("Guardar") }
                if (cat.canchas.size > 1) {
                    val otra = cat.canchas.first { it.id != c.id }
                    BotonTexto(onClick = { scope.launch { avisar(repo.copiarConfiguracion(c.id, otra.id), nav, "Configuración copiada de ${otra.nombre}.") } }, icono = Iconos.copiar) { Text("Copiar horario y tarifas de ${otra.nombre}") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Campo(nueva, { nueva = it }, "Nueva cancha", Modifier.weight(1f))
            BotonPrincipal(enabled = nueva.isNotBlank(), icono = Iconos.agregar, onClick = { scope.launch { avisar(repo.crearCancha(nueva), nav, "Cancha agregada."); nueva = "" } }) { Text("Agregar") }
        }
    }
}

@Composable
private fun EditorNegocio(cat: CatalogoDto, nav: Navegacion) {
    val scope = rememberCoroutineScope()
    val repo = koinInject<Repositorio>()
    var nombre by remember(cat.negocio) { mutableStateOf(cat.negocio.nombre) }
    var telefono by remember(cat.negocio) { mutableStateOf(cat.negocio.telefono.orEmpty()) }
    var direccion by remember(cat.negocio) { mutableStateOf(cat.negocio.direccion.orEmpty()) }
    var yape by remember(cat.negocio) { mutableStateOf(cat.negocio.pagoYape.orEmpty()) }
    var plin by remember(cat.negocio) { mutableStateOf(cat.negocio.pagoPlin.orEmpty()) }
    var titular by remember(cat.negocio) { mutableStateOf(cat.negocio.pagoTitular.orEmpty()) }
    var instrucciones by remember(cat.negocio) { mutableStateOf(cat.negocio.pagoInstrucciones.orEmpty()) }
    fun numeroValido(t: String) = t.isBlank() || t.filter { it.isDigit() }.length in 6..15
    Tarjeta(titulo = "Datos del negocio") {
        Campo(nombre, { nombre = it }, "Nombre del negocio")
        Campo(telefono, { telefono = it }, "Teléfono", telefono = true)
        Campo(direccion, { direccion = it }, "Dirección")
        Text("Datos para que el cliente pague su adelanto", fontWeight = FontWeight.SemiBold)
        Text("Se muestran en la app del cliente cuando su reserva espera el adelanto. Escribe solo datos reales del negocio; " +
            "deja vacío lo que no uses. El pago que informe el cliente se verifica siempre en la cuenta antes de registrarlo.", color = Colores.gris)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Campo(yape, { yape = it.filter { c -> c.isDigit() || c == ' ' }.take(20) }, "Número de Yape", Modifier.widthIn(min = 220.dp, max = 300.dp), telefono = true,
                error = if (numeroValido(yape)) null else "Revisa el número")
            Campo(plin, { plin = it.filter { c -> c.isDigit() || c == ' ' }.take(20) }, "Número de Plin", Modifier.widthIn(min = 220.dp, max = 300.dp), telefono = true,
                error = if (numeroValido(plin)) null else "Revisa el número")
        }
        Campo(titular, { titular = it.take(120) }, "Titular de la cuenta (nombre que ve el cliente al pagar)")
        Campo(instrucciones, { instrucciones = it.take(500) }, "Indicaciones para el cliente (opcional)", lineas = 3,
            ayuda = "Por ejemplo: «Envía la captura de tu pago por WhatsApp»")
        BotonPrincipal(enabled = nombre.isNotBlank() && numeroValido(yape) && numeroValido(plin), icono = Iconos.listo, onClick = {
            scope.launch {
                avisar(repo.guardarNegocio(mapOf("nombre" to nombre, "telefono" to telefono.ifBlank { null }, "direccion" to direccion.ifBlank { null },
                    "pago_yape" to yape.trim().ifBlank { null }, "pago_plin" to plin.trim().ifBlank { null },
                    "pago_titular" to titular.trim().ifBlank { null }, "pago_instrucciones" to instrucciones.trim().ifBlank { null })), nav, "Datos guardados.")
            }
        }) { Text("Guardar") }
    }
}

@Composable
private fun key(k: Any, contenido: @Composable () -> Unit) = androidx.compose.runtime.key(k) { contenido() }
