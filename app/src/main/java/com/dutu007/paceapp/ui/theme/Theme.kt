package com.dutu007.paceapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PaceColors = lightColorScheme(
    primary = Color(0xFF0F4C5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EEF3),
    onPrimaryContainer = Color(0xFF072A33),
    secondary = Color(0xFF2D6A73),
    onSecondary = Color.White,
    background = Color(0xFFF6F8F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EFF2),
    onSurface = Color(0xFF152026),
    onSurfaceVariant = Color(0xFF53646D),
    outline = Color(0xFFD0DCE0),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun PaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PaceColors,
        content = content,
    )
}
