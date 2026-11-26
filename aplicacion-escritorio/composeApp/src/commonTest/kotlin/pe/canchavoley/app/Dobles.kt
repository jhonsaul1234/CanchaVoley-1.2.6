package pe.canchavoley.app

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.MutableStateFlow
import pe.canchavoley.app.plataforma.MonitorConectividad
import pe.canchavoley.app.plataforma.TokenStorage

/** SQLite en memoria con el esquema completo (cada plataforma de pruebas lo implementa). */
expect fun crearDriverEnMemoria(): SqlDriver

/** Simula el Modo Avión: basta con cambiar [enLinea].value. */
class MonitorConectividadFalso(inicial: Boolean) : MonitorConectividad {
    override val enLinea = MutableStateFlow(inicial)
    var revisiones = 0
    override fun revisarAhora() { revisiones++ }
}

/** Almacén de token en memoria que cuenta las lecturas (para comprobar la caché). */
class TokenFalso(var token: String? = null) : TokenStorage {
    var lecturas = 0
    override suspend fun saveToken(token: String) { this.token = token }
    override suspend fun getToken(): String? { lecturas++; return token }
    override suspend fun clearToken() { token = null }
}
