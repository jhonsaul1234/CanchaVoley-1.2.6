package pe.canchavoley.app.dominio

/** Reglas de navegación, sin Compose para poder probarlas: menú de cada rol y qué hace «atrás». */
object Navegacion {
    private val ADMIN = listOf("PANEL", "AGENDA", "LLAMADA", "CLIENTES", "CAJA", "REPORTES", "CONFIGURACION", "USUARIOS", "SINCRONIZACION", "NOTIFICACIONES")
    private val RECEPCION = listOf("PANEL", "AGENDA", "LLAMADA", "CLIENTES", "CAJA", "SINCRONIZACION", "NOTIFICACIONES")
    private val CLIENTE = listOf("HORARIOS", "MIS_RESERVAS", "NOTIFICACIONES", "CUENTA")

    /** Pantallas del menú según el rol. Un rol desconocido solo ve lo de un cliente. */
    fun destinos(rol: String): List<String> = when (rol) {
        "admin" -> ADMIN
        "recepcion" -> RECEPCION
        else -> CLIENTE
    }

    enum class Volver { CERRAR_DETALLE, IR_AL_INICIO }

    /** Qué hace «atrás»: cerrar el detalle abierto, volver a la primera pantalla o (null) salir de la app. */
    fun alVolver(actual: String, inicio: String, detalleAbierto: Boolean): Volver? = when {
        detalleAbierto -> Volver.CERRAR_DETALLE
        actual != inicio -> Volver.IR_AL_INICIO
        else -> null
    }
}
