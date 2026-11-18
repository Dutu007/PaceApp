package com.dutu007.paceapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object PacePalette {
    val Background = Color(0xFFF3F6F8)
    val Card = Color(0xFFFFFFFF)
    val CardBorder = Color(0xFFE1E8EE)
    val Primary = Color(0xFF0E5A6F)
    val PrimarySoft = Color(0xFFE0F2F7)
    val AccentAmber = Color(0xFFD97706)
    val AccentAmberSoft = Color(0xFFFEF3C7)
    val Success = Color(0xFF059669)
    val SuccessSoft = Color(0xFFD1FAE5)
    val CodeBg = Color(0xFF18222D)
    val CodeText = Color(0xFFE6EDF3)
    val TextPrimary = Color(0xFF131C24)
    val TextSecondary = Color(0xFF5A6B78)
    val Muted = Color(0xFFEBF0F4)
}

private val PaceColors = lightColorScheme(
    primary = PacePalette.Primary,
    onPrimary = Color.White,
    primaryContainer = PacePalette.PrimarySoft,
    onPrimaryContainer = Color(0xFF073642),
    secondary = Color(0xFF3E6875),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EEF2),
    onSecondaryContainer = Color(0xFF1A3A44),
    background = PacePalette.Background,
    onBackground = PacePalette.TextPrimary,
    surface = PacePalette.Card,
    onSurface = PacePalette.TextPrimary,
    surfaceVariant = PacePalette.Muted,
    onSurfaceVariant = PacePalette.TextSecondary,
    outline = PacePalette.CardBorder,
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF6B1414),
)

private val PaceTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PaceColors,
        typography = PaceTypography,
        content = content,
    )
}
