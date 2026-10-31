package com.gt8d.launcher.ui.home

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

val PanelIconMirrored = SemanticsPropertyKey<Boolean>("PanelIconMirrored")

val PanelIconVector: ImageVector = ImageVector.Builder(
    name = "PanelIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(5f, 0f)
        horizontalLineTo(19f)
        quadTo(24f, 0f, 24f, 5f)
        verticalLineTo(19f)
        quadTo(24f, 24f, 19f, 24f)
        horizontalLineTo(5f)
        quadTo(0f, 24f, 0f, 19f)
        verticalLineTo(5f)
        quadTo(0f, 0f, 5f, 0f)
        close()
    }
    path(fill = null, stroke = SolidColor(Color(0xFF303036)), strokeLineWidth = 1.5f) {
        moveTo(7.5f, 4.5f)
        horizontalLineTo(16.5f)
        quadTo(18f, 4.5f, 18f, 6f)
        verticalLineTo(18f)
        quadTo(18f, 19.5f, 16.5f, 19.5f)
        horizontalLineTo(7.5f)
        quadTo(6f, 19.5f, 6f, 18f)
        verticalLineTo(6f)
        quadTo(6f, 4.5f, 7.5f, 4.5f)
        close()
        moveTo(14.5f, 4.5f)
        verticalLineTo(19.5f)
        moveTo(6f, 10.5f)
        horizontalLineTo(14.5f)
    }
    path(fill = SolidColor(Color(0xFF303036))) {
        moveTo(8.5f, 13f)
        horizontalLineTo(12f)
        quadTo(12.5f, 13f, 12.5f, 13.5f)
        verticalLineTo(16.5f)
        quadTo(12.5f, 17f, 12f, 17f)
        horizontalLineTo(8.5f)
        quadTo(8f, 17f, 8f, 16.5f)
        verticalLineTo(13.5f)
        quadTo(8f, 13f, 8.5f, 13f)
        close()
    }
}.build()

@Composable
fun PanelIcon(modifier: Modifier = Modifier, mirrored: Boolean = false) {
    Image(PanelIconVector, contentDescription = "パネル", modifier = modifier
        .then(if (mirrored) Modifier.graphicsLayer(scaleX = -1f) else Modifier)
        .semantics { this[PanelIconMirrored] = mirrored })
}
