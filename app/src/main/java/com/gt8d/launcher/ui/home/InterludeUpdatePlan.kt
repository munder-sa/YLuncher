package com.gt8d.launcher.ui.home

import kotlin.math.ceil

internal sealed interface InterludeUpdatePlan {
    data object Idle : InterludeUpdatePlan
    data object EveryFrame : InterludeUpdatePlan
    data class Boundary(val positionMs: Long, val delayMs: Long) : InterludeUpdatePlan
}

/** First integer playback position whose rendered count differs from the current count. */
internal fun nextInterludeBoundary(startMs: Long, endMs: Long, positionMs: Long): Long? {
    if (endMs <= startMs || positionMs < 0L || positionMs >= endMs) return null
    val lit = interludeLitDots(startMs, endMs, positionMs)
    if (interludeLitDots(startMs, endMs, endMs) == lit) return null

    // Use the drawing formula as the predicate, including its Double rounding (and NaN).
    // The nonnegative search range also keeps subtraction and midpoint addition in Long.
    var low = positionMs + 1L
    var high = endMs
    while (low < high) {
        val middle = low + (high - low) / 2L
        if (interludeLitDots(startMs, endMs, middle) == lit) low = middle + 1L
        else high = middle
    }
    return low
}

internal fun planInterludeUpdate(
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    speed: Float,
    durationMs: Long?,
): InterludeUpdatePlan {
    // These speeds need the same positionAt interpolation as the old frame loop.
    if (!speed.isFinite() || speed < 0f) return InterludeUpdatePlan.EveryFrame
    if (speed == 0f) return InterludeUpdatePlan.Idle
    val boundary = nextInterludeBoundary(startMs, endMs, positionMs) ?: return InterludeUpdatePlan.Idle
    if (durationMs != null && durationMs > 0L && boundary > durationMs) return InterludeUpdatePlan.Idle
    val delayMs = ceil((boundary - positionMs).toDouble() / speed.toDouble()).toLong().coerceAtLeast(1L)
    return InterludeUpdatePlan.Boundary(boundary, delayMs)
}
