package com.gt8d.launcher.ui.home

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pixel geometry; styleKey must identify every text-style/font-resolver input to measurement. */
internal data class LyricWindowGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
    val styleKey: String,
) {
    init {
        require(widthPx >= 0 && heightPx >= 0)
        require(density.isFinite() && density > 0 && fontScale.isFinite() && fontScale > 0)
    }
    val gapPx: Int get() = (16 * density).roundToInt()
}

internal data class LyricRowKey(val lyricsGeneration: Long, val row: Int)

internal data class LyricMeasurementKey(
    val line: LyricRowKey,
    val widthPx: Int,
    val density: Float,
    val fontScale: Float,
    val styleKey: String,
    val intro: Boolean,
) {
    val row: Int get() = line.row
}

internal data class LyricWindowRow(
    val measurementKey: LyricMeasurementKey,
    val heightPx: Int,
    val startTop: Float,
    val targetTop: Float,
)

/**
 * A prepared, generation-consistent snapshot. rows is both the construction set and the entire
 * height cache; no TextLayoutResult/Placeable or off-window cache survives preparation.
 * The caller publishes this value outside measure/layout, before displaying its destination.
 */
internal class LyricWindowPlan internal constructor(
    val lyricsGeneration: Long,
    val generation: Long,
    val lineCount: Int,
    val current: Int,
    val geometry: LyricWindowGeometry,
    val intro: Boolean,
    val rows: Map<Int, LyricWindowRow>,
    val animated: Boolean,
    private val settledRows: Map<Int, LyricWindowRow>,
) {
    val anchor: Int? get() = if (lineCount == 0) null else current.coerceAtLeast(0)

    /**
     * Completion comes from the actual movement (800ms + <=100ms delay) AND fade (500ms)
     * jobs, after duration scaling. No wall-clock timeout, frame polling or snapshot write here.
     * A stale generation cannot shrink a retargeted window. Repeated completion is a no-op.
     */
    fun complete(generation: Long, movementFinished: Boolean, fadeFinished: Boolean): LyricWindowPlan {
        if (!animated || generation != this.generation || !movementFinished || !fadeFinished) return this
        return LyricWindowPlan(lyricsGeneration, generation, lineCount, current, geometry, intro,
            settledRows, false, settledRows)
    }
}

/**
 * Local preparation only, on index/lyrics/geometry changes, never on animation frames.
 * measureHeight returns the real unscaled row height with rendering's width, 22sp Bold,
 * maxLines=2, ellipsis and intro representation. It must return a positive height synchronously
 * for this key; async results must be generation-checked by the caller before invoking this.
 * Replacing lyrics (including same-count replacement) requires a new lyricsGeneration.
 * generation is a monotonically increasing preparation token, including across song changes.
 *
 * During retargeting displayedTops must contain every retained row's last layer output; a
 * settled plan may omit it. Newly entering rows extend the old target geometry. Their motion
 * and any per-row delay stay within the endpoint envelope, even for nonmonotonic staggering.
 * Contiguous boundary guard rows allow neighbour overlap checks without distant measurements.
 */
internal fun planLyricWindow(
    lyricsGeneration: Long,
    generation: Long,
    lineCount: Int,
    current: Int,
    geometry: LyricWindowGeometry,
    previous: LyricWindowPlan? = null,
    displayedTops: Map<Int, Float> = emptyMap(),
    animationScale: Float = 1f,
    intro: Boolean = false,
    measureHeight: (LyricMeasurementKey) -> Int,
): LyricWindowPlan {
    require(lineCount >= 0 && current >= -1 && (current < lineCount || current == -1))
    require(animationScale.isFinite() && animationScale >= 0)
    require(previous == null || generation > previous.generation)
    val compatible = previous != null && previous.lyricsGeneration == lyricsGeneration &&
        previous.lineCount == lineCount && previous.geometry == geometry && previous.intro == intro
    val animated = compatible && previous!!.current >= 0 && current == previous.current + 1 && animationScale > 0
    if (animated && previous!!.animated) require(displayedTops.keys.containsAll(previous.rows.keys))
    if (animated) require(previous!!.rows.keys.all { (displayedTops[it] ?: previous.rows.getValue(it).targetTop).isFinite() })
    if (lineCount == 0) return LyricWindowPlan(lyricsGeneration, generation, 0, current, geometry,
        intro, emptyMap(), false, emptyMap())

    val anchor = current.coerceAtLeast(0)
    val gap = geometry.gapPx
    val cache = mutableMapOf<Int, Pair<LyricMeasurementKey, Int>>()
    fun height(i: Int): Int = cache.getOrPut(i) {
        val key = LyricMeasurementKey(LyricRowKey(lyricsGeneration, i), geometry.widthPx,
            geometry.density, geometry.fontScale, geometry.styleKey, intro && i == 0)
        val retained = previous?.takeIf { it.geometry == geometry && it.lineCount == lineCount }
            ?.rows?.get(i)?.takeIf { it.measurementKey == key }
        key to (retained?.heightPx ?: measureHeight(key)).also { require(it > 0) }
    }.second

    // Real heights, not a prefix sum from the start of the song.
    val tops = sortedMapOf(anchor to (geometry.heightPx / 2f - height(anchor) / 2f))
    fun prepend() {
        val i = tops.firstKey() - 1
        tops[i] = tops.getValue(i + 1) - gap - height(i)
    }
    fun append() {
        val i = tops.lastKey() + 1
        tops[i] = tops.getValue(i - 1) + height(i - 1) + gap
        height(i)
    }
    if (animated) {
        while (tops.firstKey() > previous!!.rows.keys.min()) prepend()
        while (tops.lastKey() < previous!!.rows.keys.max()) append()
    }
    val offset = if (animated) {
        val oldAnchor = previous!!.anchor!!
        previous.rows.getValue(oldAnchor).targetTop - tops.getValue(oldAnchor)
    } else 0f
    val distance = if (animated) maxOf(abs(offset), previous!!.rows.maxOf { (i, r) ->
        abs((displayedTops[i] ?: r.targetTop) - tops.getValue(i))
    }) else 0f

    fun expand(d: Float) {
        // M is monotone: newly discovered taller rows enlarge it, and both sides are revisited.
        var margin = cache.values.maxOf { it.second }.toFloat() + gap
        do {
            val oldSize = tops.size
            if (tops.firstKey() > 0 && tops.getValue(tops.firstKey()) > -d - margin) prepend()
            if (tops.lastKey() < lineCount - 1 &&
                tops.getValue(tops.lastKey()) + height(tops.lastKey()) < geometry.heightPx + d + margin) append()
            margin = maxOf(margin, cache.values.maxOf { it.second }.toFloat() + gap)
        } while (tops.size != oldSize)
    }
    expand(distance)
    val rows = tops.mapValues { (i, target) ->
        val (key, h) = cache.getValue(i)
        val start = if (animated) displayedTops[i] ?: previous!!.rows[i]?.targetTop ?: (target + offset) else target
        LyricWindowRow(key, h, start, target)
    }.toMap()

    // Prepare the smaller arrival window now; completion needs no measurement or state writes.
    val settled = if (animated) {
        tops.clear()
        tops[anchor] = rows.getValue(anchor).targetTop
        // Use only locally visited heights for the arrival margin, not old retained outliers.
        var margin = height(anchor).toFloat() + gap
        do {
            val oldSize = tops.size
            if (tops.firstKey() > 0 && tops.getValue(tops.firstKey()) > -margin) prepend()
            if (tops.lastKey() < lineCount - 1 &&
                tops.getValue(tops.lastKey()) + height(tops.lastKey()) < geometry.heightPx + margin) append()
            margin = maxOf(margin, tops.keys.maxOf { height(it) }.toFloat() + gap)
        } while (tops.size != oldSize)
        tops.mapValues { (i, _) -> rows.getValue(i).let { it.copy(startTop = it.targetTop) } }.toMap()
    } else rows
    return LyricWindowPlan(lyricsGeneration, generation, lineCount, current, geometry, intro, rows, animated, settled)
}
