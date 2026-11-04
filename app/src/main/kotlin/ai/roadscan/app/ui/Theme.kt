package ai.roadscan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Оформление RoadScan.
 *
 * Приложение всегда тёмное: экран в машине, ночные смены, меньше бликов и
 * ниже потребление OLED-матрицы. Палитра построена вокруг оранжевого акцента
 * (дорожные работы, разметка) и статусов «норма / внимание / критично».
 */
private val RoadScanColors = darkColorScheme(
    primary = Color(0xFFFF7A33),
    onPrimary = Color(0xFF1A0C04),
    primaryContainer = Color(0xFF3A1D0C),
    onPrimaryContainer = Color(0xFFFFD9C2),
    secondary = Color(0xFF7CD0B0),
    onSecondary = Color(0xFF06231B),
    secondaryContainer = Color(0xFF16342B),
    onSecondaryContainer = Color(0xFFB8F0DC),
    tertiary = Color(0xFF6BA8FF),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE8EBEE),
    surface = Color(0xFF12161B),
    onSurface = Color(0xFFE8EBEE),
    surfaceVariant = Color(0xFF1B2128),
    onSurfaceVariant = Color(0xFFA9B4BF),
    surfaceContainerLowest = Color(0xFF0E1216),
    surfaceContainerLow = Color(0xFF161B21),
    surfaceContainer = Color(0xFF1A2027),
    surfaceContainerHigh = Color(0xFF222932),
    surfaceContainerHighest = Color(0xFF2A323C),
    outline = Color(0xFF3A444F),
    outlineVariant = Color(0xFF262D35),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0808),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xCC05070A),
)

/**
 * Типографика под плотный технический интерфейс: подписи мельче и плотнее
 * по трекингу, чтобы в HUD помещалось больше данных без мельтешения.
 */
private val RoadScanTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 0.8.sp,
    ),
)

/**
 * Единые отступы и радиусы.
 *
 * Раньше размеры подбирались вручную в каждом экране, из-за чего карточки
 * разъезжались. Теперь шаг — кратно 4 dp, радиус карточек одинаковый.
 */
data class RsSpacing(
    val xxs: androidx.compose.ui.unit.Dp = 2.dp,
    val xs: androidx.compose.ui.unit.Dp = 4.dp,
    val sm: androidx.compose.ui.unit.Dp = 8.dp,
    val md: androidx.compose.ui.unit.Dp = 12.dp,
    val lg: androidx.compose.ui.unit.Dp = 16.dp,
    val xl: androidx.compose.ui.unit.Dp = 24.dp,
    val xxl: androidx.compose.ui.unit.Dp = 32.dp,
    val cardRadius: androidx.compose.ui.unit.Dp = 16.dp,
    val chipRadius: androidx.compose.ui.unit.Dp = 12.dp,
)

val LocalRsSpacing = staticCompositionLocalOf { RsSpacing() }

object RsTokens {
    /** Панели HUD поверх видеокадра. */
    val panelBg = Color(0xF50A0E12)
    val panelBorder = Color(0xFF2B333C)

    /** Акцент — оранжевый разметки/дорожных работ. */
    val accent = Color(0xFFFF7A33)

    /** Статусы: норма / внимание / критично. */
    val ok = Color(0xFF7CD0B0)
    val warn = Color(0xFFFFC857)
    val bad = Color(0xFFFF6B6B)
    val info = Color(0xFF6BA8FF)

    /** Приглушённый текст для второстепенных подписей. */
    val muted = Color(0xFF8A97A3)

    /** Цвета типов дефектов в отчётах и оверлеях. */
    val defectPothole = Color(0xFFFF6B4A)
    val defectCrack = Color(0xFFFFC857)
    val defectRutting = Color(0xFF6BA8FF)
    val defectOther = Color(0xFFA9B4BF)
}

@Composable
fun RoadScanTheme(content: @Composable () -> Unit) {
    // Приложение всегда тёмное: экран в машине, ночные смены, меньше бликов.
    CompositionLocalProvider(LocalRsSpacing provides RsSpacing()) {
        MaterialTheme(
            colorScheme = RoadScanColors,
            typography = RoadScanTypography,
            content = content,
        )
    }
}
