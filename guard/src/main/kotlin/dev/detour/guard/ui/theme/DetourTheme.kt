package dev.detour.guard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// Muted, calm palette: no bright "reward" colors anywhere.
private val Light = lightColorScheme(
    primary = Color(0xFF3E5F5A),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF66706D),
    background = Color(0xFFF6F5F2),
    onBackground = Color(0xFF1E2120),
    surface = Color(0xFFF6F5F2),
    onSurface = Color(0xFF1E2120),
    surfaceVariant = Color(0xFFE4E5E1),
    onSurfaceVariant = Color(0xFF4A504E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA6C7C0),
    onPrimary = Color(0xFF14302C),
    secondary = Color(0xFFB4BCB9),
    background = Color(0xFF141716),
    onBackground = Color(0xFFE2E4E1),
    surface = Color(0xFF141716),
    onSurface = Color(0xFFE2E4E1),
    surfaceVariant = Color(0xFF2B302E),
    onSurfaceVariant = Color(0xFFC0C7C4),
)

private val CalmTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
)

@Composable
fun DetourTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = CalmTypography,
        content = content,
    )
}
