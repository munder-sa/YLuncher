package com.gt8d.launcher.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val heightPx = with(density) { maxHeight.toPx() }
    BoxWithConstraints(modifier) {
        fun clockStyle(size: Float) = TextStyle(
            color = Color.White,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
            lineHeight = TextUnit.Unspecified,
        )
        fun measureClock(style: TextStyle): androidx.compose.ui.text.TextLayoutResult {
            val natural = measurer.measure(
                text = "00:00",
                style = style,
                softWrap = false,
                maxLines = 1,
                density = density,
                constraints = Constraints(),
            )
            val current = measurer.measure(
                text = text,
                style = style,
                softWrap = false,
                maxLines = 1,
                density = density,
                constraints = Constraints(),
            )
            val width = maxOf(natural.size.width, current.size.width)
            // BasicText receives a maximum width, rather than fixed text constraints.
            return measurer.measure(
                text = text,
                style = style,
                softWrap = false,
                maxLines = 1,
                density = density,
                constraints = Constraints(maxWidth = width),
            )
        }
        var candidate = maxSp
        var selectedStyle = clockStyle(minSp)
        var selectedLayout = measureClock(selectedStyle)
        while (candidate >= minSp) {
            val style = clockStyle(candidate)
            val result = measureClock(style)
            if (!result.hasVisualOverflow && result.size.width <= constraints.maxWidth &&
                result.size.height <= heightPx) {
                selectedStyle = style
                selectedLayout = result
                break
            }
            candidate -= 2f
        }
        BasicText(
            text = text,
            style = selectedStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(with(density) { selectedLayout.size.width.toDp() }).testTag("clock"),
        )
    }
}
