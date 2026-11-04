package com.gt8d.launcher.ui.theme

import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class LyricsVisualStyleTest {
    private val white = 0xFFFFFFFF.toInt()
    private fun mix(a: Int, b: Int, t: Float): Int {
        var result = 0xFF000000.toInt()
        for (shift in listOf(16,8,0)) {
            result = result or (((a ushr shift and 255) * (1-t) +
                (b ushr shift and 255) * t).roundToInt() shl shift)
        }
        return result
    }
    private fun backgrounds(): List<Int> {
        val singles = listOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(),
            0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFF00.toInt()).map(::darkenArtworkColor)
        // High/low saturation, filter boundaries, unequal area and opposing hues.
        val pixels = listOf(
            intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
            intArrayOf(0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFF00.toInt()),
            intArrayOf(0xFF80A080.toInt(), 0xFFA08080.toInt(), 0xFF8080A0.toInt()),
            intArrayOf(0xFF200000.toInt(), 0xFF002000.toInt(), 0xFF000020.toInt()),
            intArrayOf(0xFFFFE0E0.toInt(), 0xFFE0FFE0.toInt(), 0xFFE0E0FF.toInt()),
            intArrayOf(0xFF64644C.toInt(), 0xFF644C64.toInt(), 0xFF4C6464.toInt()),
            intArrayOf(0xFFF08C00.toInt(), 0xFF00F08C.toInt(), 0xFF8C00F0.toInt()),
            IntArray(100) { if (it < 80) 0xFF80A080.toInt() else 0xFFFF0000.toInt() },
        )
        val colors = singles + pixels.map { requireNotNull(extractArtworkColors(it)) }
        return (colors.flatMap(ArtworkColorReference::interpolations) +
            listOf(Palette.Panel.toArgb(),0xFF383838.toInt())).distinct()
    }
    // Independent reference calculation includes rounded 8-bit compositing.
    private fun referenceRatio(fg: Int, bg: Int): Double {
        fun luminance(color: Int) = listOf(16 to 0.2126,8 to 0.7152,0 to 0.0722).sumOf { (s,w) ->
            val v = (color ushr s and 255) / 255.0
            w * if (v <= 0.04045) v/12.92 else ((v+0.055)/1.055).pow(2.4)
        }
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a,b)+0.05)/(minOf(a,b)+0.05)
    }

    @Test fun primaryContrastAtLeast45() {
        var minimum = Double.POSITIVE_INFINITY
        backgrounds().forEach { bg ->
            listOf(white,Palette.Muted.toArgb(),compositeSrgb(white,LyricsVisualStyle.NextAlpha,bg),
                compositeSrgb(white,LyricsVisualStyle.WordBaseAlpha,bg)).forEach { fg ->
                val ratio = contrastRatio(fg,bg)
                assertEquals(referenceRatio(fg,bg),ratio,1e-12)
                assertTrue("background=${Integer.toHexString(bg)} ratio=$ratio",ratio >= 4.5)
                minimum = minOf(minimum,ratio)
            }
            for (alpha in listOf(LyricsVisualStyle.NextAlpha, LyricsVisualStyle.WordBaseAlpha)) {
                val reference = mix(bg, white, alpha)
                assertEquals(reference, compositeSrgb(white, alpha, bg))
                assertTrue(referenceRatio(reference, bg) >= 4.5)
            }
        }
        println("Primary minimum contrast: $minimum")
    }

    @Test fun alphaIsCompositedBeforeLuminance() {
        val bg = 0xFF383838.toInt()
        assertEquals(bg,compositeSrgb(white,0f,bg))
        assertEquals(1.0,contrastRatio(compositeSrgb(white,0f,bg),bg),0.0)
        assertEquals(white,compositeSrgb(white,1f,bg))
        assertEquals(0xFFB3B3B3.toInt(),compositeSrgb(white,0.62f,bg))
        assertEquals(referenceRatio(white,bg),contrastRatio(white,bg),1e-12)
        assertEquals(21.0,contrastRatio(white,0xFF000000.toInt()),1e-12)
    }

    @Test fun fadeNeverBelowNextContrast() {
        var minimum = Double.POSITIVE_INFINITY
        var layeredMinimum = Double.POSITIVE_INFINITY
        backgrounds().forEach { bg ->
            val base = contrastRatio(compositeSrgb(white,LyricsVisualStyle.NextAlpha,bg),bg)
            for (ms in 0..LyricsVisualStyle.FadeMs) {
                val alpha = LyricsVisualStyle.NextAlpha + (1f-LyricsVisualStyle.NextAlpha)*ms/LyricsVisualStyle.FadeMs
                val ratio = contrastRatio(compositeSrgb(white,alpha,bg),bg)
                assertEquals(mix(bg, white, alpha), compositeSrgb(white, alpha, bg))
                assertEquals(referenceRatio(mix(bg, white, alpha), bg), ratio, 1e-12)
                assertTrue(ratio >= base)
                assertTrue(ratio >= 4.5)
                minimum = minOf(minimum, ratio)
                // Filled words drawn over the unfilled base must also remain readable.
                val layered = compositeSrgb(white,ms.toFloat()/LyricsVisualStyle.FadeMs,
                    compositeSrgb(white,LyricsVisualStyle.WordBaseAlpha,bg))
                val referenceLayered = mix(mix(bg, white, LyricsVisualStyle.WordBaseAlpha), white,
                    ms.toFloat() / LyricsVisualStyle.FadeMs)
                assertEquals(referenceLayered, layered)
                val layeredRatio = referenceRatio(referenceLayered,bg)
                assertTrue(layeredRatio >= base)
                assertTrue(layeredRatio >= 4.5)
                layeredMinimum = minOf(layeredMinimum, layeredRatio)
            }
        }
        println("Fade minimum contrast: $minimum; layered white minimum contrast: $layeredMinimum")
    }

    @Test fun distantAndEdgeFadeRatiosAreReportedSeparately() {
        val bg = 0xFF383838.toInt()
        listOf(0.45f,0.35f,0.25f,0.62f*0.5f,0f).forEach { alpha ->
            val color = compositeSrgb(white,alpha,bg)
            val ratio = contrastRatio(color,bg)
            assertEquals(referenceRatio(color,bg),ratio,1e-12)
            assertTrue(ratio < 4.5)
            println("Excluded alpha=$alpha color=${Integer.toHexString(color)} contrast=$ratio")
        }
    }

    @Test fun alphaTargetsByRole() {
        val expected = listOf(0.25f,0.35f,0.45f,1f,0.62f,0.35f,0.25f)
        expected.forEachIndexed { index, alpha -> assertEquals(alpha,lineTargetAlpha(index,3,false),0f) }
        assertEquals(0.62f,lineTargetAlpha(3,3,true),0f)
        expected.forEachIndexed { index, alpha ->
            if (index != 3) assertEquals(alpha,lineTargetAlpha(index,3,true),0f)
        }
        assertEquals(0.97f,LyricsVisualStyle.InactiveScale,0f)
        assertEquals(800,LyricsVisualStyle.ScrollMs)
        assertEquals(500,LyricsVisualStyle.FadeMs)
        assertEquals(500,LyricsVisualStyle.ScaleMs)
        assertEquals(50,LyricsVisualStyle.StaggerStepMs)
        assertEquals(100,LyricsVisualStyle.StaggerMaxMs)
        assertEquals(32,LyricsVisualStyle.SampleMaxSide)
    }

    @Test fun beforeFirstTargets() {
        listOf(0.62f,0.45f,0.35f,0.25f).forEachIndexed { index, alpha ->
            assertEquals(alpha,lineTargetAlpha(index,-1,false),0f)
            assertEquals(alpha,lineTargetAlpha(index,-1,true),0f)
        }
    }
}
