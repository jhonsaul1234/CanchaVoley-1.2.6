package pe.canchavoley.app.plataforma

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Notificaciones del sistema (las que aparecen en la barra del celular aunque la app esté cerrada).
 * Android las muestra; en Windows los avisos se ven dentro de la app (pantalla «Notificaciones»).
 */
interface NotificadorSistema {
    /** true si esta plataforma muestra notificaciones del sistema. */
    val disponible: Boolean

    /**
     * Muestra (o reemplaza, si ya existe con ese [id]) una notificación; al tocarla abre la reserva, si la hay,
     * y marca como leído el aviso [avisoId] del servidor.
     */
    fun mostrar(id: Int, titulo: String, texto: String, reservaUuid: String?, avisoId: Long? = null)
}

/** Windows: sin notificaciones del sistema. */
class NotificadorNulo : NotificadorSistema {
    override val disponible: Boolean = false
    override fun mostrar(id: Int, titulo: String, texto: String, reservaUuid: String?, avisoId: Long?) = Unit
}

/** Notificación del celular que se tocó: reserva por abrir (null = pantalla de notificaciones) y aviso por marcar como leído. */
data class AvisoTocado(val reservaUuid: String?, val avisoId: Long?)

/**
 * Pedidos que llegan de fuera de la pantalla (por ejemplo, al tocar una notificación del celular):
 * la app los atiende al estar lista y los borra.
 */
object AccionesExternas {
    val avisoTocado = MutableStateFlow<AvisoTocado?>(null)
}
