package pe.canchavoley.app

import pe.canchavoley.app.plataforma.Autoprueba
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La autoprueba del instalador («CanchaVoley.exe --autoprueba»). Aquí corre en la JVM de desarrollo;
 * el dibujo con Skia se omite porque las pruebas no tienen la librería gráfica de Windows.
 */
class AutopruebaTest {

    @Test
    fun la_autoprueba_pasa_en_la_jvm_de_desarrollo() {
        val resultados = Autoprueba.ejecutar(incluirDibujo = false)
        val informe = Autoprueba.informe(resultados)
        assertTrue(resultados.all { it.ok }, informe)
        val nombres = resultados.map { it.nombre }
        listOf("Base local SQLite", "Preferencias del usuario", "Cifrado del token (DPAPI)",
            "Conexiones seguras (TLS)", "Cliente HTTP y JSON", "Zona horaria").forEach { assertContains(nombres, it) }
        assertContains(informe, "Resultado: todo correcto")
    }

    @Test
    fun el_informe_senala_cada_falla() {
        val texto = Autoprueba.informe(listOf(
            Autoprueba.Resultado("Base local SQLite", true, "bien"),
            Autoprueba.Resultado("Cliente HTTP y JSON", false, "java.lang.NoClassDefFoundError: java/sql/Driver"),
        ))
        assertContains(texto, "[OK]    Base local SQLite: bien")
        assertContains(texto, "[FALLA] Cliente HTTP y JSON: java.lang.NoClassDefFoundError")
        assertContains(texto, "Resultado: 1 comprobación(es) con falla")
    }

    @Test
    fun desde_la_linea_de_comandos_guarda_el_informe_y_devuelve_cero() {
        val carpeta = Files.createTempDirectory("autoprueba-test").toFile()
        try {
            val archivo = File(carpeta, "informe.txt")
            val codigo = Autoprueba.desdeLineaDeComandos(listOf("--autoprueba", archivo.absolutePath, "--sin-dibujo"))
            val texto = archivo.readText()
            assertEquals(0, codigo, texto)
            assertContains(texto, "[OK]    Base local SQLite")
            assertTrue("Dibujo de pantallas" !in texto)
        } finally {
            carpeta.deleteRecursively()
        }
    }
}
