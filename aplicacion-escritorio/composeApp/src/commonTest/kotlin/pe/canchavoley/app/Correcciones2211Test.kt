@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.canchavoley.app.datos.AlmacenLocal
import pe.canchavoley.app.datos.ApiCancha
import pe.canchavoley.app.datos.BloqueoDto
import pe.canchavoley.app.datos.CatalogoDto
import pe.canchavoley.app.datos.ClienteDto
import pe.canchavoley.app.datos.GestorSesion
import pe.canchavoley.app.datos.IntervaloOcupadoDto
import pe.canchavoley.app.datos.JsonCv
import pe.canchavoley.app.datos.MotorSync
import pe.canchavoley.app.datos.NotificacionCvDto
import pe.canchavoley.app.datos.NotificacionesCvDto
import pe.canchavoley.app.datos.NuevaReserva
import pe.canchavoley.app.datos.OcupacionDto
import pe.canchavoley.app.datos.PagoInformadoDto
import pe.canchavoley.app.datos.ReglasDto
import pe.canchavoley.app.datos.Repositorio
import pe.canchavoley.app.datos.ReservaDto
import pe.canchavoley.app.datos.ResultadoAccion
import pe.canchavoley.app.datos.RevisorAvisos
import pe.canchavoley.app.datos.SesionDto
import pe.canchavoley.app.datos.UsuarioSesionDto
import pe.canchavoley.app.datos.conBloqueosCombinados
import pe.canchavoley.app.dominio.AvisosSistema
import pe.canchavoley.app.dominio.DisponibilidadLocal
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.dominio.Intervalo
import pe.canchavoley.app.dominio.ReglasReserva
import pe.canchavoley.app.dominio.TipoWhatsApp
import pe.canchavoley.app.dominio.Validaciones
import pe.canchavoley.app.dominio.WhatsApp
import pe.canchavoley.app.plataforma.EstadoSesion
import pe.canchavoley.app.plataforma.HttpClientFactory
import pe.canchavoley.app.plataforma.NotificadorSistema
import pe.edu.upeu.prolech.db.AppDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Correcciones del 22/11/2026 (pruebas reales con un Xiaomi): disponibilidad sin conexión, acciones según el
 * estado de la reserva, pagos en canceladas, pago del adelanto informado por el cliente y avisos del celular.
 * Todos los datos son FICTICIOS.
 */
class Correcciones2211Test {
    private fun reserva(estado: String = "confirmada", ini: Int = 10, fin: Int = 12, pendientePor: String? = null, asistencia: String? = null,
                        precio: String? = "40.00", pagado: String = "0.00", adelanto: String? = null, venceEn: String? = null,
                        informado: PagoInformadoDto? = null) =
        ReservaDto(uuid = "res-$ini", canchaId = 1, inicio = Fechas.iso(t(ini)), fin = Fechas.iso(t(fin)), estado = estado,
            pendientePor = pendientePor, asistencia = asistencia, precioFinal = precio, totalPagado = pagado,
            adelantoRequerido = adelanto, adelantoVenceEn = venceEn, pagoInformado = informado,
            clienteNombre = "Cliente Prueba", clienteTelefono = "51900000001")

    private val reglas = ReglasDto(toleranciaNoShowMin = 15)

    // ── Problema 5: «Registrar pago» en reservas canceladas ────────────────────
    @Test fun cancelada_sin_pagos_no_ofrece_registrar_pago_a_nadie() {
        val r = reserva("cancelada")
        assertFalse(ReglasReserva.acciones(r, esPersonal = true, esAdmin = true, ahora = t(8), reglas = reglas).registrarPago)
        assertFalse(ReglasReserva.acciones(r, esPersonal = true, esAdmin = false, ahora = t(8), reglas = reglas).registrarPago)
    }

    @Test fun cancelada_con_adelanto_solo_permite_devolucion_y_solo_a_la_administradora() {
        val r = reserva("cancelada", pagado = "20.00")
        val admin = ReglasReserva.acciones(r, true, true, t(8), reglas)
        assertEquals(listOf("devolucion"), admin.tiposPago)
        assertTrue(admin.soloDevolucion)
        assertFalse(ReglasReserva.acciones(r, true, false, t(8), reglas).registrarPago)      // recepción
    }

    @Test fun devolucion_nunca_mayor_que_lo_cobrado_y_con_motivo() {
        assertNotNull(Validaciones.pago("devolucion", "25", "efectivo", "", pagado = 2000, motivo = "Canceló a tiempo"))
        assertNotNull(Validaciones.pago("devolucion", "10", "efectivo", "", pagado = 0, motivo = "Canceló a tiempo"))
        assertNotNull(Validaciones.pago("devolucion", "10", "efectivo", "", pagado = 2000, motivo = ""))
        assertNull(Validaciones.pago("devolucion", "20", "efectivo", "", pagado = 2000, motivo = "Canceló a tiempo"))
    }

    @Test fun un_cobro_no_puede_pasar_del_saldo_pendiente() {
        assertNotNull(Validaciones.pago("pago", "30", "efectivo", "", saldo = 2000))
        assertNotNull(Validaciones.pago("pago", "5", "efectivo", "", saldo = 0))
        assertNull(Validaciones.pago("pago", "20", "efectivo", "", saldo = 2000))
    }

    @Test fun pagada_por_completo_ya_no_ofrece_cobros() {
        val r = reserva(pagado = "40.00")
        assertTrue(ReglasReserva.acciones(r, true, false, t(8), reglas).tiposPago.isEmpty())
        assertEquals(listOf("devolucion"), ReglasReserva.acciones(r, true, true, t(8), reglas).tiposPago)
    }

    @Test fun devolucion_sugerida_sigue_la_regla_configurada_sin_inventar() {
        val cancelada = reserva("cancelada", pagado = "20.00")
        assertEquals(2000L, ReglasReserva.devolucionSugerida(cancelada, ReglasDto(cancelacionDevolucion = "total")))
        assertEquals(1000L, ReglasReserva.devolucionSugerida(cancelada, ReglasDto(cancelacionDevolucion = "parcial", cancelacionPorcentaje = "50")))
        assertEquals(0L, ReglasReserva.devolucionSugerida(cancelada, ReglasDto(cancelacionDevolucion = "ninguna")))
        assertEquals(2000L, ReglasReserva.devolucionSugerida(reserva("rechazada", pagado = "20.00"), ReglasDto(cancelacionDevolucion = "ninguna")))
        assertEquals(500L, ReglasReserva.devolucionSugerida(reserva(pagado = "45.00"), null))              // cobrado de más
        assertEquals(0L, ReglasReserva.devolucionSugerida(reserva("cancelada"), null))
        assertNull(ReglasReserva.textoReglaDevolucion(null))
    }

    // ── Problema 2: acciones contradictorias después de «Asistió» ──────────────
    @Test fun tras_asistio_no_se_ofrece_no_asistio_ni_cambiar_horario_ni_cancelar() {
        val r = reserva(asistencia = "asistio")
        val a = ReglasReserva.acciones(r, true, false, t(11), reglas)
        assertFalse(a.noAsistio); assertFalse(a.llegoCliente); assertFalse(a.cambiarHorario); assertFalse(a.cancelar)
        assertFalse(a.corregirAsistencia)                                   // recepción no corrige
        assertTrue(a.editarNotas)                                           // las notas sí
        assertTrue(a.registrarPago)                                         // el saldo pendiente se sigue cobrando
        assertEquals(TipoWhatsApp.CONTACTO, a.whatsapp)                     // no «recordar» una reserva ya jugada
        val admin = ReglasReserva.acciones(r, true, true, t(11), reglas)
        assertTrue(admin.corregirAsistencia)
        assertTrue(admin.editarPrecio && admin.precioConMotivo)
    }

    @Test fun no_asistio_solo_desde_la_tolerancia_y_llego_tarde_dentro_del_horario() {
        val r = reserva()
        assertEquals(t(10, 15), ReglasReserva.acciones(r, true, false, t(10, 5), reglas).noAsistioDesde)
        assertNull(ReglasReserva.acciones(r, true, false, t(10, 20), reglas).noAsistioDesde)
        val marcada = reserva(asistencia = "no_asistio")
        assertTrue(ReglasReserva.acciones(marcada, true, false, t(11), reglas).llegoTarde)
        assertFalse(ReglasReserva.acciones(marcada, true, false, t(12, 30), reglas).llegoCliente)   // ya terminó
    }

    @Test fun registrada_sin_conexion_no_ofrece_acciones_hasta_que_el_servidor_la_valide() {
        val a = ReglasReserva.acciones(reserva("por_sincronizar"), true, true, t(8), reglas)
        assertFalse(a.registrarPago || a.cancelar || a.cambiarHorario || a.editarNotas || a.llegoCliente)
    }

    // ── Problema 4: cómo paga el cliente su adelanto ───────────────────────────
    @Test fun el_cliente_ve_como_pagar_y_puede_informar_su_pago_una_vez() {
        val r = reserva("pendiente", pendientePor = "adelanto", adelanto = "20.00", venceEn = Fechas.iso(t(9)))
        val a = ReglasReserva.acciones(r, esPersonal = false, esAdmin = false, ahora = t(8), reglas = reglas)
        assertTrue(a.comoPagar && a.informarPago && a.cancelar)
        assertFalse(a.registrarPago)                                        // el cliente nunca registra pagos
        val informado = PagoInformadoDto(uuid = "pi-1", metodo = "yape", numeroOperacion = "12345678", monto = "20.00")
        assertFalse(ReglasReserva.acciones(r.copy(pagoInformado = informado), false, false, t(8), reglas).informarPago)
        assertTrue(ReglasReserva.plazoVencido(r, t(9, 1)))
        assertFalse(ReglasReserva.plazoVencido(r, t(8, 59)))
    }

    @Test fun validacion_del_pago_informado() {
        assertNotNull(Validaciones.pagoInformado(null, "12345678", "20", 2000))
        assertNotNull(Validaciones.pagoInformado("yape", "12", "20", 2000))
        assertNotNull(Validaciones.pagoInformado("yape", "12345678", "50", 2000))
        assertNull(Validaciones.pagoInformado("plin", "12345678", "20", 2000))
    }

    @Test fun mensajes_de_whatsapp_sin_datos_inventados() {
        val r = reserva("cancelada")
        assertTrue(WhatsApp.contacto("Cancha de prueba", r).contains("(cancelada)"))
        val alNegocio = WhatsApp.alNegocio(reserva("pendiente", pendientePor = "adelanto"))
        assertTrue(alNegocio.contains("quiero pagar el adelanto"))
        assertFalse(alNegocio.contains("S/"))
    }

    // ── Problema 1: disponibilidad sin conexión ────────────────────────────────
    private val ocupacion = OcupacionDto(generadoEn = Fechas.iso(t(7)), desde = Fechas.iso(t(0)), hasta = Fechas.iso(t(0) + (3 * 24 * 60).minutes),
        intervalos = listOf(IntervaloOcupadoDto(1, Fechas.iso(t(10)), Fechas.iso(t(12)), "reserva"),
            IntervaloOcupadoDto(2, Fechas.iso(t(14)), Fechas.iso(t(15)), "reserva")))

    @Test fun la_ocupacion_guardada_quita_los_horarios_de_otras_personas() {
        val extra = DisponibilidadLocal.intervalosGuardados(ocupacion, 1)
        assertEquals(listOf(Intervalo(t(10), t(12))), extra)                   // solo la cancha pedida
        val libres = DisponibilidadLocal.libres(catalogoDePrueba(), emptyList(), 1, DIA, ahora = t(0), extra = extra)
        assertEquals(listOf(8 to 10, 12 to 22), libres.map { Fechas.local(it.inicio).hour to Fechas.local(it.fin).hour })
        assertNotNull(DisponibilidadLocal.choque(catalogoDePrueba(), emptyList(), 1, t(11), t(14, 30), extra = extra))
        assertTrue(DisponibilidadLocal.intervalosGuardados(ocupacion, 1, quitar = Intervalo(t(10), t(12))).isEmpty())
        assertTrue(DisponibilidadLocal.cubre(ocupacion, DIA))
        assertFalse(DisponibilidadLocal.cubre(ocupacion, DIA.plusDias(5)))
        assertFalse(DisponibilidadLocal.cubre(null, DIA))
    }

    @Test fun los_cierres_de_una_descarga_parcial_se_suman_a_los_guardados() {
        val guardados = listOf(BloqueoDto(1, 1, Fechas.iso(t(8)), Fechas.iso(t(9)), "Mantenimiento"))
        val parcial = catalogoDePrueba().copy(bloqueos = listOf(BloqueoDto(2, 1, Fechas.iso(t(20)), Fechas.iso(t(21)), "Evento")))
        assertEquals(listOf(1L, 2L), parcial.conBloqueosCombinados(guardados).bloqueos.map { it.id })
        val quitado = catalogoDePrueba().copy(bloqueos = listOf(BloqueoDto(1, 1, Fechas.iso(t(8)), Fechas.iso(t(9)), "Mantenimiento", eliminado = true)))
        assertTrue(quitado.conBloqueosCombinados(guardados).bloqueos.isEmpty())
    }

    // ── Problema 3: avisos del celular ─────────────────────────────────────────
    private fun aviso(id: Long, leida: Boolean = false) = NotificacionCvDto(id = id, titulo = "Aviso $id", mensaje = "Mensaje $id", leida = leida, reservaUuid = "res-10")

    @Test fun avisos_nuevos_sin_repetir_y_sin_llenar_el_celular_al_instalar() {
        val lista = listOf(aviso(1), aviso(2), aviso(3, leida = true))
        assertTrue(AvisosSistema.nuevos(lista, null).isEmpty())                       // primera vez: punto de partida
        assertEquals(3L, AvisosSistema.ultimoVisto(lista, null))
        assertEquals(listOf(2L), AvisosSistema.nuevos(lista, 1).map { it.id })       // el 3 ya está leído
        assertTrue(AvisosSistema.nuevos(lista, 3).isEmpty())
        assertEquals(5, AvisosSistema.nuevos((1L..20L).map { aviso(it) }, 0).size)
        val conLeidas = AvisosSistema.conLeidas(NotificacionesCvDto(2, lista), setOf(1L, 3L))
        assertEquals(1, conLeidas.noLeidas)
        assertTrue(conLeidas.notificaciones.first { it.id == 1L }.leida)
    }

    @Test fun recordatorio_del_plazo_solo_una_vez_y_no_si_ya_informo_su_pago() {
        val r = reserva("pendiente", pendientePor = "adelanto", adelanto = "20.00", venceEn = Fechas.iso(t(9)))
        assertEquals(listOf(r.uuid), AvisosSistema.adelantosPorVencer(listOf(r), t(8, 30), emptySet()).map { it.uuid })
        assertTrue(AvisosSistema.adelantosPorVencer(listOf(r), t(7), emptySet()).isEmpty())             // falta más de 1 h
        assertTrue(AvisosSistema.adelantosPorVencer(listOf(r), t(8, 30), setOf(r.uuid)).isEmpty())      // ya recordado
        val informado = r.copy(pagoInformado = PagoInformadoDto(uuid = "pi", metodo = "yape", numeroOperacion = "1234", monto = "20.00"))
        assertTrue(AvisosSistema.adelantosPorVencer(listOf(informado), t(8, 30), emptySet()).isEmpty())
        assertTrue(AvisosSistema.idRecordatorio(r.uuid) >= 0x40000000)
    }
}

private fun kotlinx.datetime.LocalDate.plusDias(n: Int) = Fechas.local(Fechas.instante(this, kotlinx.datetime.LocalTime(12, 0)) + (n * 24 * 60).minutes).date

/** Servidor falso para la app del cliente: entrega la ocupación de las canchas y los avisos. */
private class ServidorCliente {
    var conectado = true
    var ocupacion: OcupacionDto? = null
    var avisos = NotificacionesCvDto()
    var decidir: (JsonObject) -> String = { op -> """{"op_uuid":${op["op_uuid"]},"resultado":"aplicada"}""" }
    val operaciones = mutableListOf<String>()
    val leidas = mutableListOf<Long>()

    val motor = MockEngine { req ->
        if (!conectado) throw IOException("sin red")
        val ruta = req.url.encodedPath
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            ruta.endsWith("/api/cv/sync/operaciones") -> {
                val cuerpo = JsonCv.parseToJsonElement((req.body as io.ktor.http.content.TextContent).text).jsonObject
                val res = (cuerpo["operaciones"] as kotlinx.serialization.json.JsonArray).map { op ->
                    operaciones += op.jsonObject["op_uuid"]!!.jsonPrimitive.content
                    decidir(op.jsonObject)
                }
                respond("""{"resultados":[${res.joinToString(",")}]}""", HttpStatusCode.OK, json)
            }
            ruta.endsWith("/api/cv/sync/cambios") -> {
                val ocup = ocupacion?.let { ""","ocupacion":${JsonCv.encodeToString(OcupacionDto.serializer(), it)}""" }.orEmpty()
                respond("""{"servidor_ahora":"2030-06-04T07:00:00-05:00","cursor":"2030-06-04T07:00:00-05:00","catalogo":${JsonCv.encodeToString(CatalogoDto.serializer(), catalogoDePrueba())},"clientes":[],"reservas":[],"pagos":[]$ocup}""",
                    HttpStatusCode.OK, json)
            }
            ruta.endsWith("/api/cv/notificaciones") && req.method == HttpMethod.Get ->
                respond(JsonCv.encodeToString(NotificacionesCvDto.serializer(), avisos), HttpStatusCode.OK, json)
            ruta.matches(Regex(".*/api/cv/notificaciones/\\d+/leer")) -> {
                leidas += ruta.substringAfter("/notificaciones/").substringBefore("/leer").toLong()
                respond("""{"actualizadas":1}""", HttpStatusCode.OK, json)
            }
            else -> respondError(HttpStatusCode.NotFound)
        }
    }
}

private class NotificadorFalso : NotificadorSistema {
    override val disponible = true
    val mostradas = mutableListOf<Triple<String, String?, Long?>>()
    override fun mostrar(id: Int, titulo: String, texto: String, reservaUuid: String?, avisoId: Long?) {
        mostradas += Triple(titulo, reservaUuid, avisoId)
    }
}

/** App del cliente con el servidor falso: sin conexión usa la ocupación guardada y avisa sin repetir. */
class AppClienteSinConexionTest {
    private val servidor = ServidorCliente()
    private val monitor = MonitorConectividadFalso(true)
    private val almacen = AlmacenLocal(AppDatabase(crearDriverEnMemoria())).apply {
        guardarCatalogo(catalogoDePrueba())
        guardarSesion(SesionDto(usuario = UsuarioSesionDto(7, "Cliente Prueba", "cliente@prueba.test", "cliente"), esPersonal = false, esAdmin = false,
            cliente = ClienteDto(uuid = "cli-propio", nombre = "Cliente Prueba", telefono = "51900000002")))
    }
    private val api = ApiCancha(HttpClientFactory.create(tokenProvider = { "token-prueba" }, motor = servidor.motor)) { "http://servidor" }
    private val gestor = GestorSesion(api, almacen, TokenFalso("token-prueba"), EstadoSesion(), "prueba")
    private val notificador = NotificadorFalso()
    private val revisor = RevisorAvisos(api, almacen, notificador, esCliente = { true })
    private val motorSync = MotorSync(api, almacen, monitor, CoroutineScope(SupervisorJob()), sesionActiva = { true }, alSesionVencida = {},
        alSincronizar = { revisor.revisar() })
    private val repo = Repositorio(api, almacen, monitor, motorSync, gestor)

    private suspend fun prepararSinConexion() {
        gestor.restaurar()
        servidor.ocupacion = OcupacionDto(generadoEn = "2030-06-04T07:00:00-05:00", desde = Fechas.iso(t(0)), hasta = "2030-06-07T00:00:00-05:00",
            intervalos = listOf(IntervaloOcupadoDto(1, Fechas.iso(t(10)), Fechas.iso(t(12)), "reserva")))   // la reserva de otra persona
        motorSync.sincronizar()
        monitor.enLinea.value = false                                   // Modo Avión
        servidor.conectado = false
    }

    @Test fun sin_conexion_los_horarios_de_otras_personas_no_aparecen_libres() = runTest {
        prepararSinConexion()
        val d = repo.disponibilidad(1, DIA)
        assertFalse(d.desdeElServidor)
        assertTrue(d.conDatosDeOcupacion)
        assertEquals(Fechas.parse("2030-06-04T07:00:00-05:00"), d.actualizadaEn)
        assertTrue(d.libres.none { it.inicio < t(12) && it.fin > t(10) })
        assertTrue(d.ocupados.any { it.inicio == t(10) && it.fin == t(12) })
    }

    @Test fun sin_conexion_no_deja_pedir_un_horario_ya_ocupado_segun_lo_guardado() = runTest {
        prepararSinConexion()
        val r = repo.crearReserva(NuevaReserva(1, null, t(11), t(14, 30)))
        assertIs<ResultadoAccion.Fallo>(r)
        assertTrue(r.mensaje.contains("ya está ocupado"))
        assertTrue(r.sugerencias.none { it.inicio < t(12) && it.fin > t(10) })
        assertEquals(0, almacen.cantidadOperaciones())
    }

    @Test fun la_solicitud_sin_conexion_queda_pendiente_y_si_otro_tomo_el_horario_el_servidor_la_rechaza_sin_duplicar() = runTest {
        prepararSinConexion()
        val r = repo.crearReserva(NuevaReserva(1, null, t(16), t(17)))
        assertIs<ResultadoAccion.GuardadoSinConexion<ReservaDto>>(r)
        assertEquals("por_sincronizar", almacen.reserva(r.valor.uuid)!!.dto.estado)
        // Mientras tanto otra persona tomó ese horario: al volver la conexión el servidor la rechaza.
        servidor.decidir = { op ->
            val d = op["datos"]!!.jsonObject
            """{"op_uuid":${op["op_uuid"]},"resultado":"rechazada","codigo":409,"mensaje":"Ese horario ya está ocupado.","entidad_tipo":"reserva","entidad":{"uuid":${d["uuid"]},"cancha_id":1,"inicio":${d["inicio"]},"fin":${d["fin"]},"estado":"rechazada"}}"""
        }
        servidor.conectado = true
        monitor.enLinea.value = true
        motorSync.sincronizar()
        motorSync.sincronizar()
        assertEquals("rechazada", almacen.reserva(r.valor.uuid)!!.dto.estado)
        assertEquals(0, almacen.cantidadOperaciones())
        assertEquals(1, servidor.operaciones.size)                       // se envió una sola vez
    }

    @Test fun los_avisos_se_muestran_una_sola_vez_y_los_leidos_sin_conexion_se_envian_despues() = runTest {
        gestor.restaurar()
        servidor.avisos = NotificacionesCvDto(1, listOf(aviso(1)))
        motorSync.sincronizar()
        assertTrue(notificador.mostradas.isEmpty())                       // primera vez: solo punto de partida
        servidor.avisos = NotificacionesCvDto(2, listOf(aviso(2), aviso(1)))
        motorSync.sincronizar()
        motorSync.sincronizar()
        assertEquals(listOf<Triple<String, String?, Long?>>(Triple("Solicitud aprobada", "res-1", 2L)), notificador.mostradas.toList())   // sin duplicados

        servidor.conectado = false                                         // se abre el aviso sin conexión
        repo.leerNotificacion(2)
        assertEquals(1, almacen.notificaciones()!!.noLeidas)
        assertEquals(setOf(2L), almacen.lecturasPendientes())

        servidor.conectado = true
        servidor.avisos = NotificacionesCvDto(1, listOf(aviso(2).copy(leida = true), aviso(1)))
        motorSync.sincronizar()
        assertEquals(listOf(2L), servidor.leidas)
        assertTrue(almacen.lecturasPendientes().isEmpty())
    }

    private fun aviso(id: Long) = NotificacionCvDto(id = id, titulo = "Solicitud aprobada", mensaje = "Tu reserva está confirmada.",
        tipo = "reserva_aprobada", reservaUuid = "res-1")
}
