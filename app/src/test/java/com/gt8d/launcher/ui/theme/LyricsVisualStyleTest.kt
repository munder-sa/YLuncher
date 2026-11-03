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
        val colors = listOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(),
            0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFF00.toInt()).map(::darkenArtworkColor)
        return colors.flatMap { c -> listOf(0f,0.25f,0.5f,0.75f,1f).map { mix(c.light,c.dark,it) } } +
            listOf(Palette.Panel.toArgb(),0xFF383838.toInt())
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
                assertTrue("ratio=$ratio",ratio >= 4.5)
                minimum = minOf(minimum,ratio)
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
        backgrounds().forEach { bg ->
            val base = contrastRatio(compositeSrgb(white,LyricsVisualStyle.NextAlpha,bg),bg)
            for (ms in 0..LyricsVisualStyle.FadeMs) {
                val alpha = LyricsVisualStyle.NextAlpha + (1f-LyricsVisualStyle.NextAlpha)*ms/LyricsVisualStyle.FadeMs
                val ratio = contrastRatio(compositeSrgb(white,alpha,bg),bg)
                assertTrue(ratio >= base)
                assertTrue(ratio >= 4.5)
                // Filled words drawn over the unfilled base must also remain readable.
                val layered = compositeSrgb(white,ms.toFloat()/LyricsVisualStyle.FadeMs,
                    compositeSrgb(white,LyricsVisualStyle.WordBaseAlpha,bg))
                assertTrue(contrastRatio(layered,bg) >= base)
            }
        }
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
