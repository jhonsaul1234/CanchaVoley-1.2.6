package pe.canchavoley.app.datos

import pe.canchavoley.app.plataforma.EstadoSesion
import pe.canchavoley.app.plataforma.TokenStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sesión de CanchaVoley: inicio de sesión, registro de clientes y datos del usuario (funciona sin internet). */
class GestorSesion(
    private val api: ApiCancha,
    private val almacen: AlmacenLocal,
    private val tokens: TokenStorage,
    private val estadoSesion: EstadoSesion,
    val plataforma: String,
) {
    private val _sesion = MutableStateFlow<SesionDto?>(null)
    val sesion: StateFlow<SesionDto?> = _sesion.asStateFlow()

    suspend fun hayToken(): Boolean = !tokens.getToken().isNullOrBlank()

    /** Al abrir la app: con token y sesión guardados se entra directo (también sin internet). */
    suspend fun restaurar(): SesionDto? {
        if (!hayToken()) return null
        val guardada = almacen.sesion() ?: return null
        _sesion.value = guardada
        when (val r = api.sesion()) {
            is Resultado.Ok -> guardar(r.valor)
            is Resultado.Error -> if (r.codigo == 401) { cerrarLocal(); return null }
            Resultado.SinConexion -> Unit
        }
        return _sesion.value
    }

    suspend fun iniciar(email: String, password: String): Resultado<SesionDto> {
        val correo = email.trim().lowercase()
        val anterior = almacen.dato(AlmacenLocal.ULTIMO_USUARIO)
        if (anterior != null && anterior != correo && almacen.cantidadOperaciones() > 0) {
            return Resultado.Error(409, "Hay operaciones sin sincronizar de la cuenta $anterior. Inicia sesión con esa cuenta (con internet) para enviarlas antes de cambiar de usuario.")
        }
        val token = when (val r = api.login(correo, password, plataforma)) {
            is Resultado.Ok -> r.valor
            is Resultado.Error -> return if (r.codigo == 422) Resultado.Error(422, "Correo o contraseña incorrectos.") else r
            Resultado.SinConexion -> return Resultado.SinConexion
        }
        tokens.saveToken(token)
        return when (val s = api.sesion()) {
            is Resultado.Ok -> {
                if (s.valor.usuario.rol !in listOf("admin", "recepcion", "cliente")) {
                    tokens.clearToken()
                    Resultado.Error(403, "Esta cuenta no tiene acceso a CanchaVoley.")
                } else {
                    val otraCuenta = anterior != null && anterior != correo
                    if (otraCuenta || otraBaseDelServidor(s.valor)) almacen.vaciarDatosDeNegocio()
                    almacen.guardarDato(AlmacenLocal.ULTIMO_USUARIO, correo)
                    s.valor.negocio?.creadoEn?.let { almacen.guardarDato(AlmacenLocal.MARCA_SERVIDOR, it) }
                    estadoSesion.restablecer()
                    guardar(s.valor)
                    Resultado.Ok(s.valor)
                }
            }
            is Resultado.Error -> { tokens.clearToken(); s }
            Resultado.SinConexion -> { tokens.clearToken(); Resultado.SinConexion }
        }
    }

    suspend fun registrar(nombre: String, email: String, password: String, telefono: String): Resultado<SesionDto> {
        val correo = email.trim().lowercase()
        if (almacen.cantidadOperaciones() > 0) {
            return Resultado.Error(409, "Hay operaciones sin sincronizar en este equipo. Sincroniza antes de crear otra cuenta.")
        }
        return when (val r = api.registro(nombre.trim(), correo, password, telefono, plataforma)) {
            is Resultado.Ok -> {
                val token = r.valor.token ?: return Resultado.Error(500, "El servidor no devolvió la sesión.")
                if (almacen.dato(AlmacenLocal.ULTIMO_USUARIO) != correo) almacen.vaciarDatosDeNegocio()
                tokens.saveToken(token)
                almacen.guardarDato(AlmacenLocal.ULTIMO_USUARIO, correo)
                estadoSesion.restablecer()
                guardar(r.valor)
                Resultado.Ok(r.valor)
            }
            else -> r
        }
    }

    suspend fun cerrarSesion() {
        api.logout()
        cerrarLocal()
    }

    /** Se olvida el token; la copia local y las operaciones pendientes se conservan para esta cuenta. */
    suspend fun cerrarLocal() {
        tokens.clearToken()
        _sesion.value = null
    }

    /**
     * La base del servidor se volvió a crear (por ejemplo, se reinició la base de demostración) o es otro
     * servidor: la copia local tiene reservas que allá ya no existen y hay que descargarla de nuevo.
     * Nunca se borra si quedan operaciones sin enviar (se ven y se pueden descartar en Sincronización).
     */
    private fun otraBaseDelServidor(s: SesionDto): Boolean {
        val marca = s.negocio?.creadoEn ?: return false
        val local = almacen.dato(AlmacenLocal.MARCA_SERVIDOR) ?: return false
        return marca != local && almacen.cantidadOperaciones() == 0L
    }

    private fun guardar(s: SesionDto) {
        almacen.guardarSesion(s)
        _sesion.value = s.copy(token = null)
    }
}
