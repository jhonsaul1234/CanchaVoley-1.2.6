package pe.canchavoley.app

import pe.canchavoley.app.plataforma.ContenidoPortapapelesJava
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEF-19: si copiar falla porque otro programa tiene el portapapeles, Java se quedaba con ese texto como
 * propio y, al pegar en la app, lo devolvía en lugar de lo que había en el portapapeles de Windows.
 */
class ContenidoPortapapelesJavaTest {

    /** Se comporta como el portapapeles de Java en Windows: guarda el texto y recién después falla al escribirlo. */
    private class PortapapelesQueFallaAlEscribir : Clipboard("prueba") {
        override fun setContents(contents: Transferable, owner: ClipboardOwner?) {
            this.contents = contents
            this.owner = owner
            throw IllegalStateException("cannot open system clipboard")
        }
    }

    @Test
    fun tras_un_copiado_fallido_java_devolvia_el_texto_que_no_llego_a_windows() {
        val portapapeles = PortapapelesQueFallaAlEscribir()
        runCatching { portapapeles.setContents(StringSelection("Cierre de caja"), null) }
        // Sin la corrección, «pegar» devuelve el texto que nunca se copió.
        assertEquals("Cierre de caja", portapapeles.getContents(null)?.getTransferData(DataFlavor.stringFlavor))

        assertTrue(ContenidoPortapapelesJava.olvidar(portapapeles))
        assertNull(portapapeles.getContents(null))      // vuelve a leerse el portapapeles de Windows
    }

    @Test
    fun la_autoprueba_comprueba_que_el_lanzador_abre_el_paquete_del_portapapeles() {
        assertContains(ContenidoPortapapelesJava.comprobar(), "portapapeles de Windows")
    }
}
