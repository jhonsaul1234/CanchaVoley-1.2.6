@file:OptIn(kotlin.time.ExperimentalTime::class)

package pe.canchavoley.app.datos

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pe.canchavoley.app.dominio.AvisosSistema
import pe.canchavoley.app.dominio.Fechas
import pe.canchavoley.app.plataforma.NotificadorSistema
import kotlin.time.Duration.Companion.days

/**
 * Después de cada sincronización (con la app abierta, cada minuto; con la app cerrada, cada ~15 minutos
 * desde un trabajo en segundo plano de Android): descarga los avisos, guarda una copia para verlos sin
 * conexión y muestra como notificación del celular solo los nuevos (nunca dos veces el mismo).
 * Al cliente también le recuerda, una sola vez, que el plazo de su adelanto está por vencer.
 */
class RevisorAvisos(
    private val api: ApiCancha,
    private val almacen: AlmacenLocal,
    private val notificador: NotificadorSistema,
    private val esCliente: () -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun revisar() = mutex.withLock {
        // Avisos abiertos sin conexión: primero se marcan como leídos en el servidor.
        val pendientes = almacen.lecturasPendientes()
        if (pendientes.isNotEmpty()) {
            almacen.quitarLecturasPendientes(pendientes.filter { api.leerNotificacion(it) !is Resultado.SinConexion }.toSet())
        }
        val r = api.notificaciones()
        if (r is Resultado.Ok) {
            val datos = r.valor
            almacen.guardarNotificaciones(datos)
            val anterior = almacen.dato(AlmacenLocal.ULTIMO_AVISO_MOSTRADO)?.toLongOrNull()
            if (notificador.disponible) {
                AvisosSistema.nuevos(datos.notificaciones, anterior).forEach { n ->
                    notificador.mostrar(idNotificacion(n.id), n.titulo, n.mensaje, n.reservaUuid, avisoId = n.id)
                }
            }
            AvisosSistema.ultimoVisto(datos.notificaciones, anterior)?.let { almacen.guardarDato(AlmacenLocal.ULTIMO_AVISO_MOSTRADO, it.toString()) }
        }
        if (notificador.disponible && esCliente()) recordarAdelantos()
    }

    /** Recordatorio local (funciona aunque el servidor no responda): el plazo del adelanto vence dentro de una hora. */
    private fun recordarAdelantos() {
        val recordadas = almacen.dato(AlmacenLocal.RECORDATORIOS_ADELANTO).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val ahora = Fechas.ahora()
        val reservas = almacen.reservasEntre(ahora, ahora + 31.days).map { it.dto }
        val porVencer = AvisosSistema.adelantosPorVencer(reservas, ahora, recordadas)
        porVencer.forEach { r ->
            notificador.mostrar(AvisosSistema.idRecordatorio(r.uuid), "Tu plazo para pagar el adelanto vence pronto", AvisosSistema.textoRecordatorio(r), r.uuid)
        }
        if (porVencer.isNotEmpty()) {
            // Solo se guardan las de los últimos días para que la lista no crezca sin fin.
            val vigentes = reservas.map { it.uuid }.toSet()
            val nuevas = (recordadas.filter { it in vigentes } + porVencer.map { it.uuid }).distinct().takeLast(50)
            almacen.guardarDato(AlmacenLocal.RECORDATORIOS_ADELANTO, nuevas.joinToString(","))
        }
    }

    private fun idNotificacion(id: Long): Int = (id % 0x3fffffff).toInt()
}
