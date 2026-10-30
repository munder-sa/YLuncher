package com.gt8d.launcher.ui.layout

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.gt8d.launcher.ui.theme.Palette

fun Modifier.launcherBackground(): Modifier =
    background(Brush.verticalGradient(listOf(Palette.WallTop, Palette.WallBottom)))
