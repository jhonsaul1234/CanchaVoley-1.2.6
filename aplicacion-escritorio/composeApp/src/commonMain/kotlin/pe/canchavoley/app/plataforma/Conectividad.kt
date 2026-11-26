package pe.canchavoley.app.plataforma

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Indica en tiempo real si el servidor del negocio es alcanzable desde este equipo. */
interface MonitorConectividad {
    val enLinea: StateFlow<Boolean>
    fun hayConexion(): Boolean = enLinea.value
    /** Pide revisar ya (por ejemplo al cambiar de red o de servidor). */
    fun revisarAhora() {}
}

/**
 * «En línea» = el servidor de CanchaVoley responde, no solo «hay internet»: en el local el
 * servidor suele estar en la red Wi-Fi aunque no haya internet, y con datos móviles puede haber
 * internet sin llegar a la PC del negocio.
 *
 * Revisa cada [intervaloMs] mientras está en línea y, sin conexión, con espera creciente hasta
 * [intervaloMaximoMs] (ahorra batería). [revisarAhora] adelanta la siguiente revisión.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorServidor(
    scope: CoroutineScope,
    private val sondear: suspend () -> Boolean,
    private val intervaloMs: Long = 15_000,
    private val intervaloMaximoMs: Long = 60_000,
) : MonitorConectividad {
    private val _enLinea = MutableStateFlow(false)
    override val enLinea: StateFlow<Boolean> = _enLinea.asStateFlow()
    private val avisos = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            var espera = intervaloMs
            while (isActive) {
                val ok = runCatching { sondear() }.getOrDefault(false)
                _enLinea.value = ok
                espera = if (ok) intervaloMs else minOf(espera * 2, intervaloMaximoMs).coerceAtLeast(intervaloMs)
                var forzado = false
                select<Unit> {
                    avisos.onReceive { forzado = true }
                    onTimeout(espera) {}
                }
                if (forzado) {
                    espera = intervaloMs
                    delay(PAUSA_ENTRE_AVISOS_MS)   // varios avisos seguidos (cambios de red) = una sola revisión
                    avisos.tryReceive()
                }
            }
        }
    }

    override fun revisarAhora() {
        avisos.trySend(Unit)
    }

    private companion object {
        const val PAUSA_ENTRE_AVISOS_MS = 1_000L
    }
}

/** Host y puerto de una URL http(s), o null si no se puede interpretar. */
fun hostYPuerto(url: String): Pair<String, Int>? {
    val n = ApiConfig.normalizar(url)
    val https = n.startsWith("https://")
    val autoridad = n.substringAfter("://").substringBefore('/')
    val host = autoridad.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
    val puerto = autoridad.substringAfter(':', "").toIntOrNull() ?: if (https) 443 else 80
    return host to puerto
}
