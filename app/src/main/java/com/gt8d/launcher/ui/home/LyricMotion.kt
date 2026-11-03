package com.gt8d.launcher.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import com.gt8d.launcher.ui.theme.LyricsVisualStyle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs

fun lineDelayMs(row: Int, current: Int): Int =
    (abs(row.toLong() - current.toLong()) * LyricsVisualStyle.StaggerStepMs)
        .coerceAtMost(LyricsVisualStyle.StaggerMaxMs.toLong()).toInt()

fun visibleMotionRows(startTops: List<Float>, targetTops: List<Float>, heights: List<Int>, viewportHeight: Int): Set<Int> {
    if (startTops.size != heights.size || targetTops.size != heights.size || viewportHeight <= 0) return emptySet()
    fun intersects(top: Float, height: Int) = top.isFinite() && height > 0 &&
        top < viewportHeight && top.toDouble() + height > 0
    return heights.indices.filterTo(mutableSetOf()) {
        intersects(startTops[it], heights[it]) || intersects(targetTops[it], heights[it])
    }
}

fun canStaggerWithoutOverlap(startTops: List<Float>, targetTops: List<Float>, heights: List<Int>, delaysMs: List<Int>): Boolean {
    val n = heights.size
    if (n == 0 || startTops.size != n || targetTops.size != n || delaysMs.size != n ||
        startTops.any { !it.isFinite() } || targetTops.any { !it.isFinite() } ||
        heights.any { it <= 0 } || delaysMs.any { it < 0 }) return false
    return (0 until n - 1).all { a ->
        val b = a + 1
        val da = targetTops[a].toDouble() - startTops[a]
        val db = targetTops[b].toDouble() - startTops[b]
        val gap = targetTops[b].toDouble() - targetTops[a] - heights[a]
        val dt = abs(delaysMs[a].toLong() - delaysMs[b]).toDouble()
        gap - abs(db - da) - minOf(abs(da), abs(db)) * minOf(1.0, 4 * dt / LyricsVisualStyle.ScrollMs) >= 0
    }
}

internal data class LyricMotionSample(val top: Float, val scale: Float)

/** One finite clock shared by visible rows. Only layer blocks read its snapshot state. */
internal class LyricMotion {
    internal class Timeline(val durationMs: Int) {
        val elapsed = mutableFloatStateOf(0f)
    }

    internal class Row(
        val targetTop: Float,
        private val start: LyricMotionSample,
        private val targetScale: Float,
        val delayMs: Int,
        val animated: Boolean,
        private val timeline: Timeline,
    ) {
        // Cached layer output, deliberately not snapshot state: measure never reads animation state.
        var displayed = start
            private set

        fun sample(): LyricMotionSample {
            if (!animated) return displayed
            val elapsed = timeline.elapsed.floatValue
            fun fraction(time: Float, duration: Int): Float =
                FastOutSlowInEasing.transform((time / duration).coerceIn(0f, 1f))
            val move = fraction(elapsed - delayMs, LyricsVisualStyle.ScrollMs)
            val scale = fraction(elapsed, LyricsVisualStyle.ScaleMs)
            displayed = LyricMotionSample(
                start.top + (targetTop - start.top) * move,
                start.scale + (targetScale - start.scale) * scale,
            )
            return displayed
        }
    }

    var rows: List<Row> = emptyList()
        private set
    private var index: Int? = null
    private var geometry: Triple<List<Int>, Int, Int>? = null
    private var targets: List<Float> = emptyList()
    private var timeline = Timeline(0)
    private val requests = Channel<Timeline>(Channel.CONFLATED)
    val running: Boolean get() = timeline.elapsed.floatValue < timeline.durationMs

    /** Called only when measuring changed geometry/index, never writes snapshot state. */
    fun prepare(current: Int, targetTops: List<Float>, heights: List<Int>, viewportHeight: Int, viewportWidth: Int) {
        val nextGeometry = Triple(heights.toList(), viewportHeight, viewportWidth)
        if (current == index && geometry == nextGeometry && targets == targetTops) return
        val animate = index != null && index!! >= 0 && current == index!! + 1 &&
            geometry == nextGeometry && rows.size == heights.size
        val starts = targetTops.indices.map { i ->
            if (animate) rows[i].displayed else LyricMotionSample(targetTops[i], if (i == current) 1f else LyricsVisualStyle.InactiveScale)
        }
        val visible = if (animate) visibleMotionRows(starts.map { it.top }, targetTops, heights, viewportHeight) + current else emptySet()
        val proposed = targetTops.indices.map { if (it in visible) lineDelayMs(it, current) else 0 }
        val delays = if (canStaggerWithoutOverlap(starts.map { it.top }, targetTops, heights, proposed)) proposed else List(heights.size) { 0 }
        val duration = if (animate) LyricsVisualStyle.ScrollMs + (visible.maxOfOrNull { delays[it] } ?: 0) else 0
        val nextTimeline = Timeline(duration)
        rows = targetTops.indices.map { i ->
            val scale = if (i == current) 1f else LyricsVisualStyle.InactiveScale
            val active = i in visible
            Row(targetTops[i], if (active) starts[i] else LyricMotionSample(targetTops[i], scale), scale,
                if (active) delays[i] else 0, active, nextTimeline)
        }
        index = current
        geometry = nextGeometry
        targets = targetTops.toList()
        timeline = nextTimeline
        requests.trySend(nextTimeline)
    }

    // Also allows exact same-time boundary tests without frame quantization.
    fun advance(elapsedMs: Float) { timeline.elapsed.floatValue = elapsedMs.coerceIn(0f, timeline.durationMs.toFloat()) }

    suspend fun run(onFrame: () -> Unit) {
        requests.receiveAsFlow().collectLatest { next ->
            if (next.durationMs > 0) {
                // Compose's Animatable applies the device duration scale to movement AND waiting.
                Animatable(0f).animateTo(next.durationMs.toFloat(), tween(next.durationMs, easing = LinearEasing)) {
                    next.elapsed.floatValue = value
                    onFrame()
                }
            }
        }
    }
}
