package pe.canchavoley.app.plataforma

import androidx.compose.runtime.Composable

/** En Windows el QR se lee con un lector USB (funciona como teclado) o se escribe el código. */
@Composable
actual fun rememberLectorQr(alLeer: (String) -> Unit): (() -> Unit)? = null
