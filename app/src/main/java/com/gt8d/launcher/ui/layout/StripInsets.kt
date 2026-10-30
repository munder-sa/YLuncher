package com.gt8d.launcher.ui.layout

import android.graphics.Rect
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.edge.EdgeStripService
import com.gt8d.launcher.edge.StripBounds
import kotlin.math.roundToInt

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

fun stripOverlapPx(window: PixelRect, left: PixelRect?, right: PixelRect?): Pair<Int, Int> {
    fun overlap(strip: PixelRect?): Int {
        if (strip == null || window.top >= strip.bottom || strip.top >= window.bottom) return 0
        return (minOf(window.right, strip.right) - maxOf(window.left, strip.left)).coerceAtLeast(0)
    }
    return overlap(left) to overlap(right)
}

private fun Rect.pixels() = PixelRect(left, top, right, bottom)

fun stripOverlapPx(window: Rect, strips: StripBounds): Pair<Int, Int> =
    stripOverlapPx(window.pixels(), strips.left?.pixels(), strips.right?.pixels())

fun Modifier.stripSafePadding(): Modifier = composed {
    val strips by EdgeStripService.stripBounds.collectAsState()
    var window by remember { mutableStateOf(Rect()) }
    val view = LocalView.current
    val density = LocalDensity.current
    val padding = stripOverlapPx(window, strips)
    LaunchedEffect(window, strips, padding) {
        DiagLog.d("Layout", "strip window=$window left=${strips.left} right=${strips.right} pad=$padding")
    }
    this.onGloballyPositioned { coordinates ->
        val origin = IntArray(2)
        view.rootView.getLocationOnScreen(origin)
        val bounds = coordinates.boundsInWindow()
        window = Rect(
            bounds.left.roundToInt() + origin[0], bounds.top.roundToInt() + origin[1],
            bounds.right.roundToInt() + origin[0], bounds.bottom.roundToInt() + origin[1],
        )
    }.absolutePadding(
        left = with(density) { padding.first.toDp() },
        right = with(density) { padding.second.toDp() },
    )
}
