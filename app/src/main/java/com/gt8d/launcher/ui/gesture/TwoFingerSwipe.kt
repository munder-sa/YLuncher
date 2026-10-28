package com.gt8d.launcher.ui.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import com.gt8d.launcher.edge.EdgeAction
import kotlin.math.abs

object TwoFingerSwipe {
    const val MIN_DP = 80f
    const val MAX_SLOPE = 0.5f

    fun classify(maxPointers: Int, dxPx: Float, dyPx: Float, density: Float): EdgeAction {
        if (maxPointers != 2) return EdgeAction.None
        val safeDensity = if (density <= 0f) 1f else density
        val absDx = abs(dxPx)
        val absDy = abs(dyPx)
        if (absDx / safeDensity >= MIN_DP && absDy < absDx * MAX_SLOPE) {
            return when {
                dxPx < 0f -> EdgeAction.Next
                dxPx > 0f -> EdgeAction.Prev
                else -> EdgeAction.None
            }
        }
        return EdgeAction.None
    }
}

fun Modifier.twoFingerSwipe(onAction: (EdgeAction) -> Unit): Modifier = pointerInput(Unit) {
    val currentDensity = density
    awaitEachGesture {
        var maxPointers = 0
        val startPositions = LinkedHashMap<PointerId, Offset>()
        val lastPositions = HashMap<PointerId, Offset>()

        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount > maxPointers) {
                maxPointers = pressedCount
            }
            for (change in event.changes) {
                if (change.id !in startPositions && startPositions.size < 2) {
                    startPositions[change.id] = change.position
                }
                if (change.id in startPositions) {
                    lastPositions[change.id] = change.position
                }
            }
        } while (event.changes.any { it.pressed })

        if (startPositions.size == 2) {
            var sumDx = 0f
            var sumDy = 0f
            for ((id, start) in startPositions) {
                val end = lastPositions[id] ?: start
                sumDx += end.x - start.x
                sumDy += end.y - start.y
            }
            val action = TwoFingerSwipe.classify(
                maxPointers = maxPointers,
                dxPx = sumDx / 2f,
                dyPx = sumDy / 2f,
                density = currentDensity,
            )
            if (action != EdgeAction.None) {
                onAction(action)
            }
        }
    }
}
