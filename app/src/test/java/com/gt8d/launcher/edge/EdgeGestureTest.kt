package com.gt8d.launcher.edge

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeGestureTest {

    private val density = 1.5f
    private val longPressMs = 1000

    private fun dp(value: Float): Float = value * density

    @Test
    fun classify_leftInward_isNext() {
        val action = EdgeGesture.classify(
            side = Side.Left,
            downX = 0f,
            downY = dp(100f),
            upX = dp(80f),
            upY = dp(105f),
            durationMs = 200L,
            longPressMs = longPressMs,
            density = density,
        )
        assertEquals(EdgeAction.Next, action)
    }

    @Test
    fun classify_rightInward_isPrev() {
        val action = EdgeGesture.classify(
            side = Side.Right,
            downX = dp(1000f),
            downY = dp(100f),
            upX = dp(920f),
            upY = dp(105f),
            durationMs = 200L,
            longPressMs = longPressMs,
            density = density,
        )
        assertEquals(EdgeAction.Prev, action)
    }

    @Test
    fun classify_longPress_isHome() {
        val action = EdgeGesture.classify(
            side = Side.Left,
            downX = 0f,
            downY = dp(100f),
            upX = dp(4f),
            upY = dp(100f),
            durationMs = 1100L,
            longPressMs = longPressMs,
            density = density,
        )
        assertEquals(EdgeAction.Home, action)
    }

    @Test
    fun classify_tapOrDiagonal_isNone() {
        // 4dp, 100ms -> None
        assertEquals(
            EdgeAction.None,
            EdgeGesture.classify(
                side = Side.Left,
                downX = 0f,
                downY = dp(100f),
                upX = dp(4f),
                upY = dp(100f),
                durationMs = 100L,
                longPressMs = longPressMs,
                density = density,
            ),
        )

        // 内向き 60dp・縦 50dp -> None
        assertEquals(
            EdgeAction.None,
            EdgeGesture.classify(
                side = Side.Left,
                downX = 0f,
                downY = dp(100f),
                upX = dp(60f),
                upY = dp(150f),
                durationMs = 200L,
                longPressMs = longPressMs,
                density = density,
            ),
        )

        // 外向き 60dp -> None
        assertEquals(
            EdgeAction.None,
            EdgeGesture.classify(
                side = Side.Left,
                downX = dp(60f),
                downY = dp(100f),
                upX = 0f,
                upY = dp(100f),
                durationMs = 200L,
                longPressMs = longPressMs,
                density = density,
            ),
        )
    }
}
