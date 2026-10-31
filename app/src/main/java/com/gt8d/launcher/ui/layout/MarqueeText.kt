package com.gt8d.launcher.ui.layout

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.theme.Dims

val MarqueeOverflow = SemanticsPropertyKey<Boolean>("MarqueeOverflow")

@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    fontSize: TextUnit = Dims.BodyText,
    color: Color = Color.White,
    fontWeight: FontWeight? = null,
    restartKey: Any? = text,
) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontSize = fontSize, fontWeight = fontWeight)
    val naturalWidth = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
    BoxWithConstraints(modifier.clipToBounds()) {
        val overflowing = naturalWidth > with(LocalDensity.current) { maxWidth.toPx() }
        val fade = if (overflowing) Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White, Color.Transparent),
                        startX = (size.width - 16.dp.toPx()).coerceAtLeast(0f),
                        endX = size.width,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            } else Modifier
        androidx.compose.foundation.layout.Box(fade.semantics { this[MarqueeOverflow] = overflowing }) {
            key(restartKey) {
                Text(
                    text = text,
                    modifier = Modifier.basicMarquee(
                        iterations = 1,
                        animationMode = MarqueeAnimationMode.Immediately,
                        initialDelayMillis = 3000,
                        repeatDelayMillis = 3000,
                        velocity = 24.dp,
                        spacing = MarqueeSpacing(32.dp),
                    ).then(if (tag != null) Modifier.testTag(tag) else Modifier)
                        .semantics { this[OneLineKey] = true },
                    fontSize = fontSize,
                    color = color,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
