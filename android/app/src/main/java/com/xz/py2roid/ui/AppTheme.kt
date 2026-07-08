package com.xz.py2roid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Py2roidColorScheme = darkColorScheme(
    primary = Color(0xFF4CAF50),
    onPrimary = Color.White,
    surface = Color(0xFF1A1A1A),
    onSurface = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surfaceContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFF2196F3),
    error = Color(0xFFF44336)
)

private val Py2roidTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Light),
)

@Composable
fun Py2roidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Py2roidColorScheme,
        typography = Py2roidTypography,
        content = content
    )
}
