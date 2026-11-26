package pe.canchavoley.app.datos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Json común de la app (tolerante a campos nuevos del servidor). */
val JsonCv = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

/** Convierte mapas/listas/primitivos de Kotlin a JSON (para cuerpos de peticiones simples). */
fun Json.Default.aJson(valor: Any?): JsonElement = when (valor) {
    null -> JsonNull
    is JsonElement -> valor
    is String -> JsonPrimitive(valor)
    is Number -> JsonPrimitive(valor)
    is Boolean -> JsonPrimitive(valor)
    is Map<*, *> -> JsonObject(valor.entries.associate { (k, v) -> k.toString() to aJson(v) })
    is Iterable<*> -> JsonArray(valor.map { aJson(it) })
    else -> JsonPrimitive(valor.toString())
}

fun objetoJson(vararg pares: Pair<String, Any?>): JsonObject = Json.aJson(mapOf(*pares)) as JsonObject

/** Mapa {"clave": número} del servidor (llega como [] si está vacío). */
fun JsonElement?.comoMapaDeEnteros(): Map<String, Int> = when (this) {
    is JsonObject -> mapValues { (_, v) -> (v as? JsonPrimitive)?.intOrNull ?: 0 }
    else -> emptyMap()
}

fun JsonElement?.comoMapaDeTextos(): Map<String, String> = when (this) {
    is JsonObject -> mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
    else -> emptyMap()
}

fun JsonElement?.comoListaDeEnteros(): List<Int> = when (this) {
    is JsonArray -> map { (it as? JsonPrimitive)?.intOrNull ?: 0 }
    is JsonObject -> entries.sortedBy { it.key.toIntOrNull() ?: 0 }.map { (it.value as? JsonPrimitive)?.intOrNull ?: 0 }
    else -> emptyList()
}

fun JsonObject.texto(clave: String): String? = (this[clave] as? JsonPrimitive)?.contentOrNull

fun JsonElement.textoPlano(): String? = (this as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
