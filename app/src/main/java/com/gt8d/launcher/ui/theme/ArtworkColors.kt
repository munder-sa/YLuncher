package com.gt8d.launcher.ui.theme

import kotlin.math.pow
import kotlin.math.roundToInt

data class ArtworkColors(val light: Int, val dark: Int)

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
    val weight = weights[best]
    if (weight == 0L) return null
    fun mean(sums: LongArray) = (sums[best].toDouble()/weight).roundToInt()
    return darkenArtworkColor(opaqueRgb(mean(reds),mean(greens),mean(blues)))
}

fun darkenArtworkColor(rgb: Int): ArtworkColors {
    val r = rgb ushr 16 and 255
    val g = rgb ushr 8 and 255
    val b = rgb and 255
    // Black is excluded during extraction; direct black input uses the lower endpoints.
    val max = maxOf(r,g,b).coerceAtLeast(1)
    fun endpoint(base: Int, range: Int) = opaqueRgb(
        (base+range*r.toDouble()/max).roundToInt(),
        (base+range*g.toDouble()/max).roundToInt(),
        (base+range*b.toDouble()/max).roundToInt(),
    )
    return ArtworkColors(endpoint(24,32),endpoint(12,16))
}

/** Explicit alpha blends RGB channels in sRGB, rounding to opaque 8-bit output. */
fun compositeSrgb(fg: Int, alpha: Float, bg: Int): Int {
    require(alpha.isFinite() && alpha in 0f..1f)
    fun channel(shift: Int) = (alpha*(fg ushr shift and 255) +
        (1f-alpha)*(bg ushr shift and 255)).roundToInt()
    return opaqueRgb(channel(16),channel(8),channel(0))
}

fun contrastRatio(fg: Int, bg: Int): Double {
    fun luminance(rgb: Int): Double {
        fun linear(shift: Int): Double {
            val s = (rgb ushr shift and 255)/255.0
            return if (s <= 0.04045) s/12.92 else ((s+0.055)/1.055).pow(2.4)
        }
        return 0.2126*linear(16)+0.7152*linear(8)+0.0722*linear(0)
    }
    val a = luminance(fg)
    val b = luminance(bg)
    return (maxOf(a,b)+0.05)/(minOf(a,b)+0.05)
}

private fun opaqueRgb(r: Int, g: Int, b: Int): Int =
    (255 shl 24) or (r shl 16) or (g shl 8) or b
