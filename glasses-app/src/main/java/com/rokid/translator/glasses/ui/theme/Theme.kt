package com.rokid.translator.glasses.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GlassesColorScheme = darkColorScheme(
    primary = Color(0xFF4CAF50),
    secondary = Color(0xFF81C784),
    background = Color.Black,
    surface = Color(0xFF1A1A1A),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun RokidGlassesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GlassesColorScheme, content = content)
}
