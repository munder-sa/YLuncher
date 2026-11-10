package com.gt8d.launcher.ui.panel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

fun cellSignalIcon(bars: Int, slashed: Boolean = false): ImageVector = ImageVector.Builder(
    name = "CellSignalIcon", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    repeat(4) { index ->
        val left = 2.5f + index * 5f
        val right = left + 3.6f
        val top = 21f - (6f + index * 4f)
        val bottom = 21f
        val radius = 0.8f
        path(fill = SolidColor(Color.White), fillAlpha = if (index < bars) 1f else 0.28f) {
            moveTo(left + radius, top)
            lineTo(right - radius, top); quadTo(right, top, right, top + radius)
            lineTo(right, bottom - radius); quadTo(right, bottom, right - radius, bottom)
            lineTo(left + radius, bottom); quadTo(left, bottom, left, bottom - radius)
            lineTo(left, top + radius); quadTo(left, top, left + radius, top)
            close()
        }
    }
    if (slashed) {
        path(stroke = SolidColor(Color(0xFFFF7A6B)), strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round) {
            moveTo(3f, 3f); lineTo(21f, 21f)
        }
    }
}.build()
