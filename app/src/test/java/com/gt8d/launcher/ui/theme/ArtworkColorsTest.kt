package com.gt8d.launcher.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ArtworkColorsTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun transparentPixelsIgnored() {
        assertNull(extractArtworkColors(intArrayOf(argb(127, 255, 0, 0))))
        assertEquals(ArtworkColors(0xFF381818.toInt(), 0xFF1C0C0C.toInt()),
            extractArtworkColors(intArrayOf(argb(128, 255, 0, 0))))
    }

    @Test fun neutralAndExtremePixelsIgnored() {
        listOf(Triple(100, 100, 77), Triple(31, 0, 0), Triple(255, 225, 225)).forEach { (r,g,b) ->
            assertNull(extractArtworkColors(intArrayOf(argb(255,r,g,b))))
        }
        listOf(Triple(100, 100, 76), Triple(32, 0, 0), Triple(255, 224, 224)).forEach { (r,g,b) ->
            assertEquals(darkenArtworkColor(argb(255,r,g,b)),
                extractArtworkColors(intArrayOf(argb(255,r,g,b))))
        }
    }

    @Test fun alphaWeightsHistogram() {
        assertEquals(darkenArtworkColor(0xFF0000FF.toInt()), extractArtworkColors(intArrayOf(
            argb(128,255,0,0), argb(255,0,0,255))))
        assertEquals(darkenArtworkColor(0xFFFF0000.toInt()), extractArtworkColors(intArrayOf(
            argb(255,255,0,0), argb(128,0,0,255))))
    }

    @Test fun dominantBucketAndTieAreDeterministic() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        assertEquals(darkenArtworkColor(red), extractArtworkColors(intArrayOf(red,blue,red)))
        assertEquals(darkenArtworkColor(blue), extractArtworkColors(intArrayOf(red,blue)))
        assertEquals(darkenArtworkColor(blue), extractArtworkColors(intArrayOf(blue,red)))
    }

    @Test fun representativeUsesWeightedMean() {
        // (64*255 + 79*128)/383 = 69.013...; rounding gives 69, not 68 or 70.
        assertEquals(ArtworkColors(0xFF382118.toInt(), 0xFF1C100C.toInt()),
            extractArtworkColors(intArrayOf(argb(255,255,64,0), argb(128,255,79,0))))
        // Exact .5 rounds upward: green 17.5 -> 18 -> dark green 17.
        assertEquals(ArtworkColors(0xFF382118.toInt(), 0xFF1C110C.toInt()),
            extractArtworkColors(intArrayOf(argb(255,64,16,0), argb(255,64,19,0))))
    }

    @Test fun emptyBlackWhiteFallsBack() {
        listOf(intArrayOf(), intArrayOf(0x00FF0000),
            intArrayOf(0xFF000000.toInt(),0xFFFFFFFF.toInt())).forEach {
            assertNull(extractArtworkColors(it))
        }
    }

    @Test fun redDarkeningIsBounded() {
        assertEquals(ArtworkColors(0xFF381818.toInt(),0xFF1C0C0C.toInt()),
            darkenArtworkColor(0xFFFF0000.toInt()))
    }

    @Test fun allChannelsAtMost56() {
        val boundaries = listOf(0,1,15,16,23,24,31,32,224,225,254,255)
        for (r in boundaries) for (g in boundaries) for (b in boundaries) {
            val colors = darkenArtworkColor(argb(255,r,g,b))
            for (shift in listOf(16,8,0)) {
                assertTrue((colors.light ushr shift and 255) in 24..56)
                assertTrue((colors.dark ushr shift and 255) in 12..28)
            }
            assertEquals(255, colors.light ushr 24)
            assertEquals(255, colors.dark ushr 24)
        }
    }
}
