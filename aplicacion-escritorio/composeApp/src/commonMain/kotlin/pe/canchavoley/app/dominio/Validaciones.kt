package pe.canchavoley.app.dominio

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import pe.canchavoley.app.plataforma.ApiConfig
import kotlin.time.Instant

/**
 * Validaciones de los formularios (sin Compose, para poder probarlas). Cada función devuelve el
 * mensaje que verá el usuario, o null si los datos están bien. El servidor vuelve a validar todo.
 */
object Validaciones {

    fun correo(email: String): String? =
        if (Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())) null else "Escribe un correo válido (ejemplo: nombre@correo.com)."

    fun inicioSesion(email: String, clave: String): String? = when {
        email.isBlank() || clave.isBlank() -> "Escribe tu correo y tu contraseña."
        else -> correo(email)
    }

    fun contrasena(clave: String): String? = if (clave.length >= 8) null else "La contraseña debe tener al menos 8 caracteres."

    fun registroCliente(nombre: String, email: String, clave: String, telefono: String): String? = when {
        nombre.trim().length < 3 -> "Escribe tu nombre completo."
        !Telefono.valido(telefono) -> "Escribe un número de celular válido."
        else -> correo(email) ?: contrasena(clave)
    }

    fun servidor(url: String): String? = when {
        url.isBlank() -> "Escribe la dirección del servidor."
        !ApiConfig.esValida(url) -> "Dirección no válida. Ejemplo: http://192.168.1.10:8001"
        else -> null
    }

    fun cliente(nombre: String, telefono: String): String? = when {
        nombre.trim().length < 2 -> "Escribe el nombre del cliente."
        !Telefono.valido(telefono) -> "Escribe un celular válido (9 dígitos)."
        else -> null
    }

    /** Monto en soles: mayor que cero y con hasta dos decimales. */
    fun monto(texto: String): String? {
        val c = Dinero.aCentimos(texto) ?: return "Escribe un monto válido (ejemplo: 25 o 25.50)."
        return when {
            c <= 0 -> "El monto debe ser mayor que cero."
            c > 9_999_900 -> "El monto es demasiado alto."
            else -> null
        }
    }

    fun numeroOperacion(metodo: String, numero: String): String? = when {
        metodo == "efectivo" || numero.isBlank() -> null
        !Regex("^[A-Za-z0-9-]{4,40}$").matches(numero.trim()) -> "El número de operación solo lleva letras, números o guiones (4 a 40)."
        else -> null
    }

    /**
     * Pago, adelanto o devolución antes de enviarlo (las mismas reglas que valida el servidor):
     * la devolución lleva motivo y no pasa de lo cobrado; un cobro no pasa del saldo pendiente.
     */
    fun pago(tipo: String, monto: String, metodo: String, numero: String, pagado: Long? = null,
             saldo: Long? = null, motivo: String? = null): String? =
        monto(monto) ?: numeroOperacion(metodo, numero) ?: run {
            val c = Dinero.aCentimos(monto)!!
            when {
                tipo == "devolucion" && pagado != null && pagado <= 0 -> "Esta reserva no tiene pagos que devolver."
                tipo == "devolucion" && pagado != null && c > pagado -> "La devolución no puede ser mayor que lo cobrado (${Dinero.soles(pagado)})."
                tipo == "devolucion" && motivo != null && motivo.trim().length < 3 -> "Escribe el motivo de la devolución."
                tipo != "devolucion" && saldo != null && saldo <= 0 -> "Esta reserva ya está pagada por completo."
                tipo != "devolucion" && saldo != null && c > saldo -> "El monto supera el saldo pendiente (${Dinero.soles(saldo)})."
                else -> null
            }
        }

    /** Pago que informa el cliente desde la app (método, número de operación y monto). */
    fun pagoInformado(metodo: String?, numero: String, monto: String, saldo: Long?): String? = when {
        metodo !in listOf("yape", "plin") -> "Elige si pagaste por Yape o por Plin."
        numero.trim().length < 4 -> "Escribe el número de operación que aparece en la constancia de tu pago."
        numero.trim().length > 40 -> "El número de operación es demasiado largo."
        else -> monto(monto) ?: run {
            val c = Dinero.aCentimos(monto)!!
            if (saldo != null && c > saldo) "El monto supera lo que falta pagar (${Dinero.soles(saldo)})." else null
        }
    }

    /** Datos de una reserva nueva antes de enviarla. */
    fun reserva(hora: String, duracionMin: Int?, inicio: Instant?, ahora: Instant, conCliente: Boolean, precio: String?, esPersonal: Boolean): String? = when {
        esPersonal && !conCliente -> "Primero identifica o registra al cliente."
        hora.isBlank() -> "Elige la hora de inicio."
        Fechas.leerHora(hora) == null -> "La hora de inicio no es válida (ejemplo: 18:30)."
        duracionMin == null || duracionMin < 5 -> "Elige una duración válida."
        duracionMin > 24 * 60 -> "La duración no puede pasar de 24 horas."
        inicio != null && inicio < ahora -> "Esa hora ya pasó: elige otra."
        esPersonal && !precio.isNullOrBlank() && Dinero.aCentimos(precio) == null -> "El precio no es válido."
        else -> null
    }

    /** Turnos de un día: horas válidas, apertura distinta del cierre y sin superponerse. */
    fun turnos(turnos: List<Pair<String, String>>): String? {
        val horas = turnos.map { (a, c) -> Fechas.leerHora(a) to Fechas.leerHora(c) }
        if (horas.any { it.first == null || it.second == null }) return "Revisa las horas (formato HH:MM, por ejemplo 08:00)."
        if (horas.any { it.first == it.second }) return "Un turno no puede abrir y cerrar a la misma hora."
        val tramos = horas.map { (a, c) -> minutos(a!!) to minutos(c!!).let { fin -> if (fin <= minutos(a)) fin + 24 * 60 else fin } }.sortedBy { it.first }
        if (tramos.zipWithNext().any { (x, y) -> y.first < x.second }) return "Dos turnos del mismo día se superponen."
        return null
    }

    /** Franjas de precio: al menos un día, horas válidas y precio mayor que cero; sin superponerse el mismo día. */
    fun franjas(franjas: List<Triple<Set<Int>, Pair<String, String>, String>>): String? {
        if (franjas.any { it.first.isEmpty() }) return "Marca al menos un día en cada franja."
        for ((_, horas, precio) in franjas) {
            if (Fechas.leerHora(horas.first) == null || Fechas.leerHora(horas.second) == null) return "Revisa las horas de las franjas (HH:MM)."
            if (horas.first == horas.second) return "Una franja no puede empezar y terminar a la misma hora."
            if ((Dinero.aCentimos(precio) ?: 0) <= 0) return "El precio por hora debe ser mayor que cero."
        }
        for (dia in 1..7) {
            val tramos = franjas.filter { dia in it.first }.map { (_, h, _) ->
                val a = minutos(Fechas.leerHora(h.first)!!)
                val b = minutos(Fechas.leerHora(h.second)!!).let { if (it <= a) it + 24 * 60 else it }
                a to b
            }.sortedBy { it.first }
            if (tramos.zipWithNext().any { (x, y) -> y.first < x.second }) return "Hay dos franjas que se superponen el ${Fechas.nombreDia(dia).lowercase()}."
        }
        return null
    }

    /** Fecha escrita como AAAA-MM-DD. */
    fun fecha(texto: String): LocalDate? = runCatching { LocalDate.parse(texto.trim()) }.getOrNull()

    /** Cierre de la cancha: con motivo; «hasta» 00:00 = medianoche; si es todo el día no hacen falta horas. */
    fun cierre(desde: String, hasta: String, todoElDia: Boolean, motivo: String): String? = when {
        motivo.trim().length < 3 -> "Escribe el motivo del cierre."
        todoElDia -> null
        Fechas.leerHora(desde) == null || Fechas.leerHora(hasta) == null -> "Escribe las horas del cierre (HH:MM)."
        Fechas.leerHora(hasta) != LocalTime(0, 0) && minutos(Fechas.leerHora(hasta)!!) <= minutos(Fechas.leerHora(desde)!!) ->
            "La hora final debe ser posterior a la inicial (use 00:00 para la medianoche)."
        else -> null
    }

    private fun minutos(h: LocalTime) = h.hour * 60 + h.minute
}
