package pe.canchavoley.app

import pe.canchavoley.app.plataforma.RegistroFallos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegistroFallosTest {

    @Test
    fun el_informe_dice_version_plataforma_donde_y_la_traza() {
        val texto = RegistroFallos.texto(IllegalStateException("prueba de error"), "sincronización", plataforma = "android", version = "9.9.9")
        assertTrue(texto.startsWith("CanchaVoley 9.9.9 · android"))
        assertTrue(texto.contains("Dónde: sincronización"))
        assertTrue(texto.contains("IllegalStateException"))
        assertTrue(texto.contains("prueba de error"))
    }

    @Test
    fun un_informe_muy_largo_se_recorta() {
        val texto = RegistroFallos.texto(RuntimeException("x".repeat(50_000)), "prueba", plataforma = "jvm", version = "1")
        assertTrue(texto.length <= RegistroFallos.MAXIMO_CARACTERES + 30)
        assertTrue(texto.endsWith("(informe recortado)"))
    }

    @Test
    fun anotar_guarda_el_informe_y_nunca_lanza_aunque_no_se_pueda_guardar() {
        val antes = RegistroFallos.guardar
        val guardados = mutableListOf<String>()
        try {
            RegistroFallos.guardar = { guardados += it }
            RegistroFallos.anotar(RuntimeException("uno"), "prueba")
            assertEquals(1, guardados.size)
            RegistroFallos.guardar = { error("disco lleno") }
            RegistroFallos.anotar(RuntimeException("dos"), "prueba")
        } finally {
            RegistroFallos.guardar = antes
        }
    }
}
