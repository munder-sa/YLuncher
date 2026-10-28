package com.gt8d.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Dims {
    val MinTouch = 72.dp
    val BodyText = 20.sp
}

object Palette {
    val Background0 = Color(0xFF0B0B0F)
    val Background1 = Color(0xFF17171D)
    val Panel = Color(0xFF2E2E38)
    val Accent = Color(0xFF8FE63B)
}

private val DarkColorScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Background0,
    background = Palette.Background0,
    onBackground = Color.White,
    surface = Palette.Panel,
    onSurface = Color.White,
    surfaceVariant = Palette.Background1,
    onSurfaceVariant = Color.White,
)

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
