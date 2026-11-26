package pe.canchavoley.app.plataforma

import androidx.compose.runtime.Composable

/**
 * Guarda un archivo generado por la app (comprobante en PDF, caja o reporte en Excel).
 * Windows: lo guarda en Documentos\CanchaVoley y lo abre con el programa predeterminado.
 * Android: lo deja listo en la app y abre «Compartir» (WhatsApp, correo, Drive…).
 * Devuelve el mensaje para la persona (dónde quedó o qué pasó).
 */
@Composable
expect fun rememberGuardarArchivo(): suspend (nombre: String, tipoMime: String, contenido: ByteArray) -> String
