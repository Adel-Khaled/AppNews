package com.smartnews.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartnews.domain.model.ThemeMode

// Editorial palette: ink navy, newsprint paper, a single signal red for "live" / breaking.
val Ink = Color(0xFF0E1A2B)
val InkSoft = Color(0xFF22324A)
val Paper = Color(0xFFF7F5F0)
val Signal = Color(0xFFE5484D)
val Accent = Color(0xFF2F6FEB)
val AiTint = Color(0xFF7C5CFF)

private val Light = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4F2), onPrimaryContainer = Ink,
    secondary = Accent, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3ECFF), onSecondaryContainer = Color(0xFF0B2A66),
    tertiary = AiTint, tertiaryContainer = Color(0xFFEDE7FF), onTertiaryContainer = Color(0xFF2A1A73),
    error = Signal,
    background = Paper, onBackground = Color(0xFF15181D),
    surface = Paper, onSurface = Color(0xFF15181D),
    surfaceVariant = Color(0xFFE9E6DF), onSurfaceVariant = Color(0xFF4A4F57),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainer = Color(0xFFF1EEE8), surfaceContainerHigh = Color(0xFFEAE7E0),
    outline = Color(0xFFBDB8AE), outlineVariant = Color(0xFFDAD5CB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBFD0EE), onPrimary = Ink,
    primaryContainer = InkSoft, onPrimaryContainer = Color(0xFFDCE4F2),
    secondary = Color(0xFF8DB2FF), onSecondary = Color(0xFF0B2A66),
    secondaryContainer = Color(0xFF1C3566), onSecondaryContainer = Color(0xFFDCE6FF),
    tertiary = Color(0xFFB9A8FF), tertiaryContainer = Color(0xFF3A2C80), onTertiaryContainer = Color(0xFFEDE7FF),
    error = Color(0xFFFF6B6F),
    background = Color(0xFF0B0F15), onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF0B0F15), onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1E252F), onSurfaceVariant = Color(0xFFA9B0BB),
    surfaceContainerLowest = Color(0xFF070A0E), surfaceContainerLow = Color(0xFF11161D),
    surfaceContainer = Color(0xFF151B23), surfaceContainerHigh = Color(0xFF1B222C),
    outline = Color(0xFF3B4452), outlineVariant = Color(0xFF29313C),
)

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun SmartNewsTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes, content = content)
}
