package pe.canchavoley.app.plataforma

import androidx.compose.runtime.Composable

/**
 * Lector de códigos QR con la cámara. Devuelve la acción que abre la cámara, o null si este
 * equipo no tiene lector de cámara (en Windows se usa un lector USB o se escribe el código).
 * [alLeer] recibe el texto leído.
 */
@Composable
expect fun rememberLectorQr(alLeer: (String) -> Unit): (() -> Unit)?
