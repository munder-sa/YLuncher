package com.gt8d.launcher.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun FitClock(
    text: String,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    maxSp: Float = 96f,
    minSp: Float = 40f,
) {
    val density = LocalDensity.current
    val heightPx = with(density) { maxHeight.toPx() }
    BoxWithConstraints(modifier) {
        val baseStyle = TextStyle(
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
            lineHeight = TextUnit.Unspecified,
        )
        val cache = rememberClockLayoutCache("fit", baseStyle, constraints, maxHeight, minSp, maxSp)
        val (selectedStyle, selectedWidth) = remember(cache, text) {
            fun clockStyle(size: Float) = baseStyle.copy(fontSize = size.sp)
            fun measureClock(style: TextStyle): ClockMeasurement {
                val natural = cache.template(style)
                val current = cache.measure(text, style)
                val width = maxOf(natural.size.width, current.size.width)
                // BasicText receives a maximum width, rather than fixed text constraints.
                return cache.measure(text, style, Constraints(maxWidth = width))
            }
            var candidate = maxSp
            var style = clockStyle(minSp)
            var layout = measureClock(style)
            while (candidate >= minSp) {
                val candidateStyle = clockStyle(candidate)
                val result = measureClock(candidateStyle)
                if (!result.hasVisualOverflow && result.size.width <= constraints.maxWidth &&
                    result.size.height <= heightPx) {
                    style = candidateStyle
                    layout = result
                    break
                }
                candidate -= 2f
            }
            style to layout.size.width
        }
        BasicText(
            text = text,
            style = selectedStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(with(density) { selectedWidth.toDp() }).testTag("clock"),
        )
    }
}
