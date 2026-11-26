package pe.canchavoley.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Paleta de CanchaVoley (azul y amarillo de un balón de vóley). Son colores provisionales: el logo y
 * los colores reales del negocio se cambian solo aquí.
 */
object Colores {
    val azul = Color(0xFF1D4E89)
    val azulOscuro = Color(0xFF143861)
    val azulClaro = Color(0xFFE6EEF8)
    val amarillo = Color(0xFFF2B705)
    val verde = Color(0xFF1E7B3A)
    val verdeClaro = Color(0xFFE3F4E8)
    val ambar = Color(0xFF9A5B00)
    val ambarClaro = Color(0xFFFFF2D9)
    val rojo = Color(0xFFC62828)
    val rojoClaro = Color(0xFFFCE8E8)
    val texto = Color(0xFF1B1F24)
    val gris = Color(0xFF5E6570)
    val grisClaro = Color(0xFFEEF0F3)
    val borde = Color(0xFFE1E5EB)
    val azulGrisClaro = Color(0xFFE8ECF2)
    val fondo = Color(0xFFF4F6F9)
}

private val esquema = lightColorScheme(
    primary = Colores.azul,
    onPrimary = Color.White,
    primaryContainer = Colores.azulClaro,
    onPrimaryContainer = Colores.azulOscuro,
    secondary = Colores.amarillo,
    onSecondary = Color(0xFF2B2100),
    secondaryContainer = Color(0xFFFFF4CC),
    onSecondaryContainer = Color(0xFF3A2C00),
    tertiary = Colores.verde,
    background = Colores.fondo,
    onBackground = Colores.texto,
    surface = Color.White,
    onSurface = Colores.texto,
    surfaceVariant = Color(0xFFEFF2F6),
    onSurfaceVariant = Colores.gris,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFF3F5F8),
    surfaceContainerHigh = Color(0xFFEDF0F4),
    outline = Color(0xFFC3CAD4),
    outlineVariant = Colores.borde,
    error = Colores.rojo,
)

private val tipografia = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

private val formas = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TemaCanchaVoley(contenido: @Composable () -> Unit) {
    MaterialTheme(colorScheme = esquema, typography = tipografia, shapes = formas, content = contenido)
}
