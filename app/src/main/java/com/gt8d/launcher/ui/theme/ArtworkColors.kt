package com.gt8d.launcher.ui.theme

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Gradient start (light), end (dark), and middle (accent); names retain two-argument compatibility. */
data class ArtworkColors(val light: Int, val dark: Int, val accent: Int = light)

private data class Hsv(val hue: Double, val saturation: Double, val value: Double)
private data class ArtworkCandidate(val bucket: Int, val hsv: Hsv, val score: Double)

/** Pure pixel calculation; bitmap sampling and worker dispatch belong to the caller. */
fun extractArtworkColors(argb: IntArray): ArtworkColors? {
    // Alpha numerators are exact integer weights, equivalent to weighting by A/255.
    val weights = LongArray(4096)
    val reds = LongArray(4096)
    val greens = LongArray(4096)
    val blues = LongArray(4096)
    for (pixel in argb) {
        val a = pixel ushr 24
        if (a < 128) continue
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        val max = maxOf(r,g,b)
        val min = minOf(r,g,b)
        if (max-min < 24 || max < 32 || min > 224) continue
        val bucket = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
        weights[bucket] += a.toLong()
        reds[bucket] += r.toLong()*a
        greens[bucket] += g.toLong()*a
        blues[bucket] += b.toLong()*a
    }
    var best = 0
    for (bucket in 1 until weights.size) {
        if (weights[bucket] > weights[best]) best = bucket
    }
    val total = weights.sum()
    if (total == 0L) return null
    fun candidate(bucket: Int): ArtworkCandidate {
        val weight = weights[bucket]
        fun mean(sums: LongArray) = (sums[bucket].toDouble() / weight.toDouble()).roundToInt()
        val hsv = toHsv(opaqueRgb(mean(reds), mean(greens), mean(blues)))
        val score = sqrt(weight.toDouble() / total.toDouble()) * (0.2 + 0.8 * hsv.saturation)
        return ArtworkCandidate(bucket, hsv, score)
    }
    val candidates = weights.indices.filter { weights[it] > 0L && weights[it] * 100L >= total }
        .map(::candidate).ifEmpty { listOf(candidate(best)) }
        .sortedWith(compareByDescending<ArtworkCandidate> { it.score }.thenBy { it.bucket })
    val selected = ArrayList<Hsv>(3)
    for (candidate in candidates) {
        if (selected.all {
                val distance = abs(it.hue - candidate.hsv.hue)
                minOf(distance, 360.0 - distance) >= 35.0
            }) {
            selected.add(candidate.hsv)
            if (selected.size == 3) break
        }
    }
    val colors = selected.map(::readableArtworkColor)
    return when (colors.size) {
        1 -> singleArtworkColors(colors[0])
        2 -> ArtworkColors(colors[0], colors[1], compositeSrgb(colors[1], 0.5f, colors[0]))
        else -> ArtworkColors(colors[0], colors[2], colors[1])
    }
}

fun darkenArtworkColor(rgb: Int): ArtworkColors = singleArtworkColors(readableArtworkColor(toHsv(rgb)))

private fun singleArtworkColors(color: Int) =
    ArtworkColors(color, scaleRgb(color, 0.6), scaleRgb(color, 0.8))

private fun toHsv(rgb: Int): Hsv {
    val r = (rgb ushr 16 and 255) / 255.0
    val g = (rgb ushr 8 and 255) / 255.0
    val b = (rgb and 255) / 255.0
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    val hue = when {
        delta == 0.0 -> 0.0
        max == r -> 60.0 * ((g - b) / delta)
        max == g -> 60.0 * ((b - r) / delta + 2.0)
        else -> 60.0 * ((r - g) / delta + 4.0)
    }
    return Hsv(if (hue < 0.0) hue + 360.0 else hue, if (max == 0.0) 0.0 else delta / max, max)
}

private fun readableArtworkColor(hsv: Hsv): Int {
    // Direct neutral input stays neutral, and even very dark gray is never brightened.
    val saturation = if (hsv.saturation == 0.0) 0.0 else hsv.saturation.coerceIn(0.55, 0.90)
    val value = if (saturation == 0.0) minOf(hsv.value, 0.45 + 0.15 * hsv.value)
        else 0.45 + 0.15 * hsv.value
    val chroma = value * saturation
    val x = chroma * (1.0 - abs((hsv.hue / 60.0) % 2.0 - 1.0))
    val offset = value - chroma
    val (r, g, b) = when (hsv.hue) {
        in 0.0..<60.0 -> Triple(chroma, x, 0.0)
        in 60.0..<120.0 -> Triple(x, chroma, 0.0)
        in 120.0..<180.0 -> Triple(0.0, chroma, x)
        in 180.0..<240.0 -> Triple(0.0, x, chroma)
        in 240.0..<300.0 -> Triple(x, 0.0, chroma)
        else -> Triple(chroma, 0.0, x)
    }
    val adjusted = opaqueRgb(((r + offset) * 255.0).roundToInt(),
        ((g + offset) * 255.0).roundToInt(), ((b + offset) * 255.0).roundToInt())
    var low = 0.0
    var high = 1.0
    var accepted = opaqueRgb(0, 0, 0)
    repeat(16) {
        val scale = (low + high) / 2.0
        val rounded = scaleRgb(adjusted, scale)
        if (isReadableArtworkColor(rounded)) {
            low = scale
            accepted = rounded
        } else {
            high = scale
        }
    }
    // Check rounded bytes, including the full-scale endpoint. Retain the last proven safe value.
    return if (isReadableArtworkColor(adjusted)) adjusted else accepted
}

private fun isReadableArtworkColor(rgb: Int): Boolean =
    relativeLuminance(rgb) <= 0.045 &&
        contrastRatio(0xFFFFFFFF.toInt(), rgb) >= 4.65 &&
        contrastRatio(0xFFB0B0BB.toInt(), rgb) >= 4.65 &&
        contrastRatio(compositeSrgb(0xFFFFFFFF.toInt(), 0.62f, rgb), rgb) >= 4.65

private fun scaleRgb(rgb: Int, scale: Double): Int = opaqueRgb(
    ((rgb ushr 16 and 255) * scale).roundToInt(),
    ((rgb ushr 8 and 255) * scale).roundToInt(),
    ((rgb and 255) * scale).roundToInt(),
)

private fun relativeLuminance(rgb: Int): Double {
    fun linear(shift: Int): Double {
        val s = (rgb ushr shift and 255) / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
}

/** Explicit alpha blends RGB channels in sRGB, rounding to opaque 8-bit output. */
fun compositeSrgb(fg: Int, alpha: Float, bg: Int): Int {
    require(alpha.isFinite() && alpha in 0f..1f)
    fun channel(shift: Int) = (alpha*(fg ushr shift and 255) +
        (1f-alpha)*(bg ushr shift and 255)).roundToInt()
    return opaqueRgb(channel(16),channel(8),channel(0))
}

fun contrastRatio(fg: Int, bg: Int): Double {
    val a = relativeLuminance(fg)
    val b = relativeLuminance(bg)
    return (maxOf(a,b)+0.05)/(minOf(a,b)+0.05)
}

private fun opaqueRgb(r: Int, g: Int, b: Int): Int =
    (255 shl 24) or (r shl 16) or (g shl 8) or b
