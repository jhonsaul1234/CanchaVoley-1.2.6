package pe.canchavoley.app.plataforma

import pe.canchavoley.app.di.PlataformaCv
import pe.canchavoley.app.dominio.Fechas

/**
 * Informe del último error inesperado. Se guarda solo en este equipo (no se envía a ningún servidor):
 * al volver a abrir la app se muestra para que la usuaria pueda compartirlo con quien da soporte.
 * Cada plataforma indica dónde guardarlo ([guardar]); sin plataforma configurada no hace nada.
 */
object RegistroFallos {
    const val ARCHIVO = "ultimo_error.txt"
    const val MAXIMO_CARACTERES = 24_000

    /** Versión de la app que se anota en el informe (la pone cada plataforma al arrancar). */
    var version: String = "?"

    /** Dónde se guarda el informe (archivo en la carpeta de datos de la app). */
    var guardar: (String) -> Unit = {}

    /** Texto del informe: versión, plataforma, fecha, dónde ocurrió y la traza del error (recortada). */
    fun texto(error: Throwable, donde: String, plataforma: String = PlataformaCv.nombre, version: String = this.version): String {
        val fecha = runCatching { Fechas.iso(Fechas.ahora()) }.getOrElse { "?" }
        val traza = runCatching { error.stackTraceToString() }.getOrElse { error.toString() }
        val cuerpo = buildString {
            appendLine("CanchaVoley $version · $plataforma")
            appendLine("Fecha: $fecha")
            appendLine("Dónde: $donde")
            appendLine()
            append(traza)
        }
        return if (cuerpo.length <= MAXIMO_CARACTERES) cuerpo else cuerpo.take(MAXIMO_CARACTERES) + "\n… (informe recortado)"
    }

    /** Anota el error; si no se puede guardar, no hace nada (nunca debe causar otro error). */
    fun anotar(error: Throwable, donde: String) {
        runCatching { guardar(texto(error, donde)) }
    }
}
