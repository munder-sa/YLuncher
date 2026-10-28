package com.gt8d.launcher.edge

import kotlin.math.abs
import kotlin.math.hypot

enum class Side { Left, Right }
enum class EdgeAction { Next, Prev, Home, None }

object EdgeGesture {
    private const val SWIPE_MIN_DP = 48f
    private const val SWIPE_MAX_SLOPE = 0.5f
    private const val LONG_PRESS_MAX_MOVE_DP = 16f

    fun classify(
        side: Side,
        downX: Float,
        downY: Float,
        upX: Float,
        upY: Float,
        durationMs: Long,
        longPressMs: Int,
        density: Float,
    ): EdgeAction {
        val safeDensity = if (density > 0f) density else 1f
        val dx = upX - downX
        val dy = upY - downY
        val inwardPx = when (side) {
            Side.Left -> dx
            Side.Right -> -dx
        }
        val inwardDp = inwardPx / safeDensity
        if (inwardDp >= SWIPE_MIN_DP && abs(dy) < inwardPx * SWIPE_MAX_SLOPE) {
            return when (side) {
                Side.Left -> EdgeAction.Next
                Side.Right -> EdgeAction.Prev
            }
        }
        val distanceDp = hypot(dx, dy) / safeDensity
        if (distanceDp < LONG_PRESS_MAX_MOVE_DP && durationMs >= longPressMs) {
            return EdgeAction.Home
        }
        return EdgeAction.None
    }
}
