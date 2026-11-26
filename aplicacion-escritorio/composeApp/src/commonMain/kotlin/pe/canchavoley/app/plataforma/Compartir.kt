package pe.canchavoley.app.plataforma

import androidx.compose.runtime.Composable

/**
 * Compartir un texto con otras apps (WhatsApp, correo…). En Android abre el menú «Compartir»;
 * en Windows devuelve null y la pantalla ofrece «Copiar».
 */
@Composable
expect fun rememberCompartirTexto(): ((titulo: String, texto: String) -> Unit)?
