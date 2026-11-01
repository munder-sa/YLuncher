package com.gt8d.launcher.ui.panel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val SetAddIcon: ImageVector = ImageVector.Builder(
    name = "SetAddIcon", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    // SetIcon scaled to 75%, keeping its two rounded panes and their ratio.
    path(fill = SolidColor(Color.White)) {
        moveTo(3f, 3f); lineTo(9f, 3f); quadTo(10.5f, 3f, 10.5f, 4.5f)
        lineTo(10.5f, 13.5f); quadTo(10.5f, 15f, 9f, 15f)
        lineTo(3f, 15f); quadTo(1.5f, 15f, 1.5f, 13.5f)
        lineTo(1.5f, 4.5f); quadTo(1.5f, 3f, 3f, 3f); close()
        moveTo(13.5f, 3f); lineTo(16.5f, 3f); quadTo(18f, 3f, 18f, 4.5f)
        lineTo(18f, 13.5f); quadTo(18f, 15f, 16.5f, 15f)
        lineTo(13.5f, 15f); quadTo(12f, 15f, 12f, 13.5f)
        lineTo(12f, 4.5f); quadTo(12f, 3f, 13.5f, 3f); close()
    }
    path(stroke = SolidColor(Color.White), strokeLineWidth = 2f) {
        moveTo(14f, 18f); lineTo(22f, 18f)
        moveTo(18f, 14f); lineTo(18f, 22f)
    }
}.build()
