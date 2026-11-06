package com.gt8d.launcher.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

internal fun interface ClockLayoutObserver {
    fun onMeasure(owner: String, text: String)
}

internal val LocalClockLayoutObserver = staticCompositionLocalOf<ClockLayoutObserver?> { null }

internal data class ClockMeasurement(val size: IntSize, val hasVisualOverflow: Boolean)

/** One geometry's bounded template cache; actual times are never accumulated. */
internal class ClockLayoutCache(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val direction: LayoutDirection,
    private val resolver: FontFamily.Resolver,
    private val observer: ClockLayoutObserver?,
    private val owner: String,
) {
    private val templates = mutableMapOf<Pair<TextStyle, Constraints>, ClockMeasurement>()

    fun template(style: TextStyle, constraints: Constraints = Constraints()): ClockMeasurement {
        val key = style to constraints
        return templates[key] ?: measure("00:00", style, constraints).also {
            if (templates.size < 128) templates[key] = it
        }
    }

    fun measure(text: String, style: TextStyle, constraints: Constraints = Constraints()): ClockMeasurement {
        observer?.onMeasure(owner, text)
        val result = measurer.measure(text, style, softWrap = false, maxLines = 1,
            constraints = constraints, density = density, layoutDirection = direction,
            fontFamilyResolver = resolver)
        return ClockMeasurement(result.size, result.hasVisualOverflow)
    }
}

@Composable
internal fun rememberClockLayoutCache(
    owner: String,
    style: TextStyle,
    constraints: Constraints,
    maxHeight: Dp,
    minSp: Float,
    maxSp: Float,
): ClockLayoutCache {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    // Reading the resolved State also invalidates on asynchronous font completion.
    val font = resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All).value
    val observer = LocalClockLayoutObserver.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    return remember(owner, style, constraints, maxHeight, minSp, maxSp,
        density.density, density.fontScale, direction, resolver, font, measurer, observer) {
        ClockLayoutCache(measurer, density, direction, resolver, observer, owner)
    }
}
