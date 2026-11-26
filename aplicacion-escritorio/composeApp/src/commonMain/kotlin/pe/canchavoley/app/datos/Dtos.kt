package pe.canchavoley.app.datos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// DTOs de la API de CanchaVoley (/api/cv). Fechas en ISO 8601 con desfase; dinero como texto "60.00".

@Serializable
data class NegocioDto(
    val id: Long = 0,
    val nombre: String = "",
    val telefono: String? = null,
    val direccion: String? = null,
    @SerialName("zona_horaria") val zonaHoraria: String = "America/Lima",
    val moneda: String = "PEN",
    /** Cuándo se creó el negocio en el servidor: cambia solo si la base se vuelve a crear. */
    @SerialName("creado_en") val creadoEn: String? = null,
    /** Datos de pago para el adelanto que publica la administradora (null = aún no los publicó). */
    @SerialName("pago_yape") val pagoYape: String? = null,
    @SerialName("pago_plin") val pagoPlin: String? = null,
    @SerialName("pago_titular") val pagoTitular: String? = null,
    @SerialName("pago_instrucciones") val pagoInstrucciones: String? = null,
) {
    val tieneDatosDePago: Boolean get() = !pagoYape.isNullOrBlank() || !pagoPlin.isNullOrBlank()
}

@Serializable
data class CanchaDto(
    val id: Long,
    val nombre: String,
    val deporte: String = "voley",
    val descripcion: String? = null,
    val activa: Boolean = true,
    val orden: Int = 1,
)

@Serializable
data class HorarioDto(
    val id: Long = 0,
    @SerialName("cancha_id") val canchaId: Long,
    @SerialName("dia_semana") val diaSemana: Int,
    val abre: String,
    val cierra: String,
)

@Serializable
data class TarifaDto(
    val id: Long = 0,
    @SerialName("cancha_id") val canchaId: Long,
    @SerialName("dia_semana") val diaSemana: Int,
    val desde: String,
    val hasta: String,
    @SerialName("precio_hora") val precioHora: String,
    @SerialName("vigente_desde") val vigenteDesde: String? = null,
    @SerialName("vigente_hasta") val vigenteHasta: String? = null,
)

@Serializable
data class ReglasDto(
    @SerialName("duracion_minima_min") val duracionMinimaMin: Int? = null,
    @SerialName("duracion_maxima_min") val duracionMaximaMin: Int? = null,
    @SerialName("paso_min") val pasoMin: Int? = null,
    @SerialName("alinear_inicio_min") val alinearInicioMin: Int? = null,
    @SerialName("margen_entre_reservas_min") val margenEntreReservasMin: Int? = null,
    @SerialName("anticipacion_minima_min") val anticipacionMinimaMin: Int? = null,
    @SerialName("anticipacion_maxima_dias") val anticipacionMaximaDias: Int? = null,
    @SerialName("regla_cruce_franjas") val reglaCruceFranjas: String? = null,
    @SerialName("adelanto_tipo") val adelantoTipo: String? = null,
    @SerialName("adelanto_valor") val adelantoValor: String? = null,
    @SerialName("adelanto_plazo_min") val adelantoPlazoMin: Int? = null,
    @SerialName("adelanto_obligatorio") val adelantoObligatorio: Boolean? = null,
    @SerialName("cancelacion_limite_horas") val cancelacionLimiteHoras: Int? = null,
    @SerialName("cancelacion_devolucion") val cancelacionDevolucion: String? = null,
    @SerialName("cancelacion_porcentaje") val cancelacionPorcentaje: String? = null,
    @SerialName("reprogramacion_limite_horas") val reprogramacionLimiteHoras: Int? = null,
    @SerialName("tolerancia_no_show_min") val toleranciaNoShowMin: Int? = null,
)

@Serializable
data class BloqueoDto(
    val id: Long,
    @SerialName("cancha_id") val canchaId: Long? = null,
    val inicio: String,
    val fin: String,
    val motivo: String,
    val eliminado: Boolean = false,
)

@Serializable
data class CatalogoDto(
    val negocio: NegocioDto = NegocioDto(),
    val canchas: List<CanchaDto> = emptyList(),
    val horarios: List<HorarioDto> = emptyList(),
    val tarifas: List<TarifaDto> = emptyList(),
    val reglas: ReglasDto = ReglasDto(),
    val bloqueos: List<BloqueoDto> = emptyList(),
)

@Serializable
data class ClienteDto(
    val id: Long = 0,
    val uuid: String,
    val nombre: String,
    val telefono: String,
    @SerialName("telefono_alterno") val telefonoAlterno: String? = null,
    val notas: String? = null,
    @SerialName("es_frecuente") val esFrecuente: Boolean = false,
    @SerialName("tiene_cuenta") val tieneCuenta: Boolean = false,
    val eliminado: Boolean = false,
)

@Serializable
data class PagoDto(
    val id: Long = 0,
    val uuid: String,
    @SerialName("reserva_id") val reservaId: Long = 0,
    @SerialName("reserva_uuid") val reservaUuid: String? = null,
    val tipo: String,
    val metodo: String,
    val monto: String,
    @SerialName("numero_operacion") val numeroOperacion: String? = null,
    @SerialName("pagado_en") val pagadoEn: String? = null,
    val estado: String = "valido",
    @SerialName("motivo_anulacion") val motivoAnulacion: String? = null,
    @SerialName("numero_comprobante") val numeroComprobante: String? = null,
    @SerialName("registrado_por") val registradoPor: String? = null,
    @SerialName("creado_offline") val creadoOffline: Boolean = false,
    val cliente: String? = null,
)

/** Pago que el cliente informó desde la app: solo una referencia hasta que el personal lo verifica. */
@Serializable
data class PagoInformadoDto(
    val uuid: String,
    val metodo: String,
    @SerialName("numero_operacion") val numeroOperacion: String,
    val monto: String,
    val nota: String? = null,
    /** pendiente | verificado | descartado */
    val estado: String = "pendiente",
    @SerialName("informado_en") val informadoEn: String? = null,
    @SerialName("revisado_en") val revisadoEn: String? = null,
    @SerialName("revisado_por") val revisadoPor: String? = null,
    @SerialName("motivo_descarte") val motivoDescarte: String? = null,
    val comprobante: String? = null,
)

@Serializable
data class EventoDto(
    val tipo: String,
    val descripcion: String,
    val usuario: String? = null,
    val fecha: String? = null,
)

@Serializable
data class ReservaDto(
    val id: Long = 0,
    val uuid: String,
    @SerialName("cancha_id") val canchaId: Long,
    @SerialName("cliente_id") val clienteId: Long = 0,
    @SerialName("cliente_uuid") val clienteUuid: String? = null,
    @SerialName("cliente_nombre") val clienteNombre: String? = null,
    @SerialName("cliente_telefono") val clienteTelefono: String? = null,
    val inicio: String,
    val fin: String,
    @SerialName("duracion_min") val duracionMin: Int = 0,
    val estado: String,
    @SerialName("pendiente_por") val pendientePor: String? = null,
    val asistencia: String? = null,
    val origen: String = "llamada",
    @SerialName("precio_calculado") val precioCalculado: String? = null,
    @SerialName("precio_final") val precioFinal: String? = null,
    @SerialName("precio_manual") val precioManual: Boolean = false,
    @SerialName("adelanto_requerido") val adelantoRequerido: String? = null,
    @SerialName("adelanto_vence_en") val adelantoVenceEn: String? = null,
    @SerialName("total_pagado") val totalPagado: String? = null,
    val saldo: String? = null,
    @SerialName("estado_pago") val estadoPago: String? = null,
    /** Pendiente del adelanto con el plazo ya vencido (lo calcula el servidor). */
    @SerialName("adelanto_vencido") val adelantoVencido: Boolean = false,
    /** Último pago informado por el cliente que falta verificar. */
    @SerialName("pago_informado") val pagoInformado: PagoInformadoDto? = null,
    @SerialName("codigo_qr") val codigoQr: String? = null,
    @SerialName("motivo_estado") val motivoEstado: String? = null,
    @SerialName("creada_offline") val creadaOffline: Boolean = false,
    val notas: String? = null,
    val pagos: List<PagoDto>? = null,
    val eventos: List<EventoDto>? = null,
    @SerialName("pagos_informados") val pagosInformados: List<PagoInformadoDto>? = null,
)

@Serializable
data class UsuarioSesionDto(
    val id: Long,
    val nombre: String,
    val email: String,
    val rol: String,
    val roles: List<String> = emptyList(),
)

@Serializable
data class SesionDto(
    val token: String? = null,
    val usuario: UsuarioSesionDto,
    @SerialName("es_personal") val esPersonal: Boolean,
    @SerialName("es_admin") val esAdmin: Boolean,
    val cliente: ClienteDto? = null,
    val negocio: NegocioDto? = null,
)

@Serializable
data class LoginRespuestaDto(val token: String)

@Serializable
data class IntervaloDto(val inicio: String, val fin: String, val tipo: String? = null, val id: Long? = null)

@Serializable
data class DisponibilidadDto(
    val fecha: String,
    @SerialName("cancha_id") val canchaId: Long,
    @SerialName("horario_configurado") val horarioConfigurado: Boolean = true,
    val turnos: List<IntervaloDto> = emptyList(),
    val ocupados: List<IntervaloDto> = emptyList(),
    val libres: List<IntervaloDto> = emptyList(),
    @SerialName("margen_min") val margenMin: Int = 0,
)

@Serializable
data class SugerenciasDto(val sugerencias: List<IntervaloDto> = emptyList())

@Serializable
data class ConflictoDto(
    val tipo: String? = null,
    val inicio: String? = null,
    val fin: String? = null,
    val estado: String? = null,
    val motivo: String? = null,
    val cliente: String? = null,
)

@Serializable
data class ErrorApiDto(
    val message: String? = null,
    val falta: String? = null,
    val conflicto: ConflictoDto? = null,
    val sugerencias: List<IntervaloDto> = emptyList(),
    val duplicado: String? = null,
    val errors: Map<String, List<String>>? = null,
)

/** Un horario ocupado (reserva o cierre), sin datos del cliente. */
@Serializable
data class IntervaloOcupadoDto(
    @SerialName("cancha_id") val canchaId: Long,
    val inicio: String,
    val fin: String,
    val tipo: String? = null,
)

/**
 * Ocupación de las canchas que la app del cliente guarda para consultar horarios sin conexión
 * (llega completa en cada descarga; desde el 22/11/2026).
 */
@Serializable
data class OcupacionDto(
    @SerialName("generado_en") val generadoEn: String,
    val desde: String,
    val hasta: String,
    val intervalos: List<IntervaloOcupadoDto> = emptyList(),
)

@Serializable
data class CambiosDto(
    @SerialName("servidor_ahora") val servidorAhora: String,
    val cursor: String,
    @SerialName("carga_completa") val cargaCompleta: Boolean = false,
    val catalogo: CatalogoDto,
    val clientes: List<ClienteDto> = emptyList(),
    val reservas: List<ReservaDto> = emptyList(),
    val pagos: List<PagoDto> = emptyList(),
    val ocupacion: OcupacionDto? = null,
)

/**
 * Las descargas incrementales traen solo los cierres que cambiaron desde la última vez: se combinan con
 * los guardados (antes se reemplazaban y, sin conexión, se perdían los cierres ya conocidos).
 */
fun CatalogoDto.conBloqueosCombinados(guardados: List<BloqueoDto>): CatalogoDto {
    val porId = LinkedHashMap<Long, BloqueoDto>()
    guardados.forEach { porId[it.id] = it }
    bloqueos.forEach { porId[it.id] = it }
    return copy(bloqueos = porId.values.filter { !it.eliminado }.sortedBy { it.inicio })
}

@Serializable
data class OperacionDto(
    @SerialName("op_uuid") val opUuid: String,
    val tipo: String,
    @SerialName("entidad_uuid") val entidadUuid: String? = null,
    val datos: JsonObject,
    @SerialName("creada_en") val creadaEn: String,
)

@Serializable
data class LoteOperacionesDto(
    @SerialName("dispositivo_uuid") val dispositivoUuid: String,
    val operaciones: List<OperacionDto>,
)

@Serializable
data class ResultadoOperacionDto(
    @SerialName("op_uuid") val opUuid: String,
    val resultado: String,
    val codigo: Int = 200,
    val mensaje: String? = null,
    @SerialName("entidad_tipo") val entidadTipo: String? = null,
    val entidad: JsonObject? = null,
    val advertencia: String? = null,
    val reserva: ReservaDto? = null,
    val sugerencias: List<IntervaloDto> = emptyList(),
    val repetida: Boolean = false,
    @SerialName("unido_a_existente") val unidoAExistente: Boolean = false,
)

@Serializable
data class RespuestaLoteDto(val resultados: List<ResultadoOperacionDto>)

@Serializable
data class ResumenClienteDto(
    val reservas: Int = 0,
    val confirmadas: Int = 0,
    val canceladas: Int = 0,
    @SerialName("no_asistio") val noAsistio: Int = 0,
    @SerialName("horas_jugadas") val horasJugadas: Double = 0.0,
)

@Serializable
data class FichaClienteDto(
    val encontrado: Boolean = true,
    val telefono: String? = null,
    val cliente: ClienteDto? = null,
    val resumen: ResumenClienteDto? = null,
    val historial: List<ReservaDto> = emptyList(),
)

@Serializable
data class RespuestaPagoDto(val pago: PagoDto, val reserva: ReservaDto, val advertencia: String? = null)

@Serializable
data class CajaDto(
    val fecha: String,
    val totales: Map<String, String> = emptyMap(),
    val total: String = "0.00",
    @SerialName("por_usuario") val porUsuario: JsonElement? = null,
    val pagos: List<PagoDto> = emptyList(),
)

@Serializable
data class DiaOcupacionDto(
    val fecha: String,
    @SerialName("horas_abiertas") val horasAbiertas: Double = 0.0,
    @SerialName("horas_reservadas") val horasReservadas: Double = 0.0,
    @SerialName("ocupacion_pct") val ocupacionPct: Double? = null,
)

@Serializable
data class ClienteFrecuenteDto(val cliente: String? = null, val reservas: Int = 0, val horas: Double = 0.0)

@Serializable
data class ReporteOcupacionDto(
    val desde: String,
    val hasta: String,
    @SerialName("horas_abiertas") val horasAbiertas: Double = 0.0,
    @SerialName("horas_reservadas") val horasReservadas: Double = 0.0,
    @SerialName("ocupacion_pct") val ocupacionPct: Double? = null,
    @SerialName("por_dia") val porDia: List<DiaOcupacionDto> = emptyList(),
    @SerialName("minutos_por_hora") val minutosPorHora: JsonElement? = null,
    @SerialName("horas_pico") val horasPico: List<Int> = emptyList(),
    @SerialName("reservas_por_estado") val reservasPorEstado: JsonElement? = null,
    @SerialName("reservas_por_origen") val reservasPorOrigen: JsonElement? = null,
    @SerialName("demanda_no_atendida") val demandaNoAtendida: Int = 0,
    val ingresos: Map<String, String> = emptyMap(),
    @SerialName("ingresos_total") val ingresosTotal: String = "0.00",
    @SerialName("clientes_frecuentes") val clientesFrecuentes: List<ClienteFrecuenteDto> = emptyList(),
    @SerialName("sin_horario") val sinHorario: List<String> = emptyList(),
)

@Serializable
data class UsuarioPersonalDto(val id: Long, val nombre: String, val email: String, val rol: String, val activo: Boolean)

@Serializable
data class NotificacionCvDto(
    val id: Long,
    val titulo: String,
    val mensaje: String,
    val tipo: String? = null,
    val leida: Boolean = false,
    val fecha: String? = null,
    /** Reserva a la que se refiere (para abrir su detalle al tocar el aviso). */
    @SerialName("reserva_uuid") val reservaUuid: String? = null,
)

@Serializable
data class NotificacionesCvDto(
    @SerialName("no_leidas") val noLeidas: Int = 0,
    val notificaciones: List<NotificacionCvDto> = emptyList(),
)

@Serializable
data class RespuestaPagoInformadoDto(
    @SerialName("pago_informado") val pagoInformado: PagoInformadoDto,
    val reserva: ReservaDto,
)

@Serializable
data class BloqueoCreadoDto(
    val bloqueo: BloqueoDto,
    @SerialName("reservas_afectadas") val reservasAfectadas: List<ReservaDto> = emptyList(),
)
