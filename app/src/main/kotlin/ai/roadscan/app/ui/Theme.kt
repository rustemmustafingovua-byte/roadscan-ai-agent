package ai.roadscan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RoadScanColors = darkColorScheme(
    primary = Color(0xFFFF7A33),
    onPrimary = Color(0xFF1A0C04),
    primaryContainer = Color(0xFF3A1D0C),
    onPrimaryContainer = Color(0xFFFFD9C2),
    secondary = Color(0xFF7CD0B0),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE8EBEE),
    surface = Color(0xFF12161B),
    onSurface = Color(0xFFE8EBEE),
    surfaceVariant = Color(0xFF1B2128),
    onSurfaceVariant = Color(0xFFA9B4BF),
    error = Color(0xFFFF6B6B),
)

@Composable
fun RoadScanTheme(content: @Composable () -> Unit) {
    // Приложение всегда тёмное: экран в машине, ночные смены, меньше бликов.
    MaterialTheme(
        colorScheme = RoadScanColors,
        typography = Typography(),
        content = content,
    )
}
