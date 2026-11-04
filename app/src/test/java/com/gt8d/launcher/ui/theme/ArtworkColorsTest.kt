package com.gt8d.launcher.ui.theme

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

class ArtworkColorsTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()
    // HSV(0/240, .9, .6), rounded before the readability adjustment.
    private val redStart by lazy { ArtworkColorReference.brightest(0xFF990F0F.toInt()) }
    private val blueStart by lazy { ArtworkColorReference.brightest(0xFF0F0F99.toInt()) }
    private fun single(start: Int) = ArtworkColors(start,
        ArtworkColorReference.mix(0xFF000000.toInt(), start, 0.6),
        ArtworkColorReference.mix(0xFF000000.toInt(), start, 0.8))
    private fun pair(start: Int, end: Int) = ArtworkColors(start, end,
        ArtworkColorReference.mix(start, end, 0.5))
    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun transparentPixelsIgnored() {
        assertNull(extractArtworkColors(intArrayOf(argb(127, 255, 0, 0))))
        assertEquals(single(redStart),
            extractArtworkColors(intArrayOf(argb(128, 255, 0, 0))))
    }

    @Test fun neutralAndExtremePixelsIgnored() {
        listOf(Triple(100, 100, 77), Triple(31, 0, 0), Triple(255, 225, 225)).forEach { (r,g,b) ->
            assertNull(extractArtworkColors(intArrayOf(argb(255,r,g,b))))
        }
        listOf(Triple(100, 100, 76), Triple(32, 0, 0), Triple(255, 224, 224)).forEach { (r,g,b) ->
            val colors = requireNotNull(extractArtworkColors(intArrayOf(argb(255,r,g,b))))
            ArtworkColorReference.assertHue(ArtworkColorReference.hue(argb(255,r,g,b)), colors.light)
            assertEquals(single(colors.light), colors)
            ArtworkColorReference.assertReadable(colors.light, 4.65)
        }
    }

    @Test fun alphaWeightsHistogram() {
        assertEquals(pair(blueStart, redStart), extractArtworkColors(intArrayOf(
            argb(128,255,0,0), argb(255,0,0,255))))
        assertEquals(pair(redStart, blueStart), extractArtworkColors(intArrayOf(
            argb(255,255,0,0), argb(128,0,0,255))))
    }

    @Test fun dominantBucketAndTieAreDeterministic() {
        assertEquals(pair(redStart, blueStart), extractArtworkColors(intArrayOf(red,blue,red)))
        assertEquals(pair(blueStart, redStart), extractArtworkColors(intArrayOf(red,blue)))
        assertEquals(pair(blueStart, redStart), extractArtworkColors(intArrayOf(blue,red)))
    }

    @Test fun representativeUsesWeightedMean() {
        // (64*255 + 79*128)/383 = 69.013...; rounding gives 69, not 68 or 70.
        // HSV adjustment of (255,69,0) gives rounded (153,53,15).
        assertEquals(single(ArtworkColorReference.brightest(0xFF99350F.toInt())),
            extractArtworkColors(intArrayOf(argb(255,255,64,0), argb(128,255,79,0))))
        // Exact .5 rounds upward: mean (64,18,0), adjusted RGB (124,44,12).
        assertEquals(single(ArtworkColorReference.brightest(0xFF7C2C0C.toInt())),
            extractArtworkColors(intArrayOf(argb(255,64,16,0), argb(255,64,19,0))))
    }

    @Test fun emptyBlackWhiteFallsBack() {
        listOf(intArrayOf(), intArrayOf(0x00FF0000),
            intArrayOf(0xFF000000.toInt(),0xFFFFFFFF.toInt())).forEach {
            assertNull(extractArtworkColors(it))
        }
    }

    @Test fun redDarkeningIsBounded() {
        assertEquals(single(redStart), darkenArtworkColor(red))
        assertVibrantSingle(red, 0.0)
    }

    @Test fun blueDarkeningPreservesHueAndSaturation() {
        assertEquals(single(blueStart), darkenArtworkColor(blue))
        assertVibrantSingle(blue, 240.0)
    }

    private fun assertVibrantSingle(input: Int, hue: Double) {
        val colors = darkenArtworkColor(input)
        val stops = ArtworkColorReference.stops(colors)
        stops.forEach {
            ArtworkColorReference.assertHue(hue, it)
            assertTrue(ArtworkColorReference.saturation(it) >= 0.55)
            assertTrue(ArtworkColorReference.channels(it).max() > 56)
        }
        assertTrue(ArtworkColorReference.luminance(colors.light) > ArtworkColorReference.luminance(colors.accent))
        assertTrue(ArtworkColorReference.luminance(colors.accent) > ArtworkColorReference.luminance(colors.dark))
    }

    @Test fun allBoundaryColorsAndInterpolationsRemainReadable() {
        val boundaries = listOf(0,1,15,16,23,24,31,32,224,225,254,255)
        var minimum = Double.POSITIVE_INFINITY
        for (r in boundaries) for (g in boundaries) for (b in boundaries) {
            val colors = darkenArtworkColor(argb(255,r,g,b))
            for (stop in ArtworkColorReference.stops(colors)) {
                assertEquals(255, stop ushr 24)
                assertTrue(ArtworkColorReference.luminance(stop) <= 0.045)
                ArtworkColorReference.assertReadable(stop, 4.65)
            }
            for (background in ArtworkColorReference.interpolations(colors)) {
                minimum = minOf(minimum, ArtworkColorReference.assertReadable(background, 4.5,
                    "input=$r,$g,$b"))
            }
        }
        println("Boundary palette minimum contrast: $minimum")
    }

    @Test fun directBlackAndGraysStayNeutral() {
        val black = 0xFF000000.toInt()
        assertEquals(ArtworkColors(black, black, black), darkenArtworkColor(black))
        for (gray in listOf(1, 16, 32, 100, 224, 255)) {
            val colors = darkenArtworkColor(argb(255, gray, gray, gray))
            ArtworkColorReference.stops(colors).forEach {
                assertEquals(0.0, ArtworkColorReference.saturation(it), 0.0)
                assertTrue(ArtworkColorReference.channels(it).max() <= gray)
            }
        }
    }

    @Test fun saturatedMinorityOutscoresLargeLowSaturationArea() {
        // 80% grayish green S=.2 scores .322; 20% pure red scores .447.
        val pixels = IntArray(100) { if (it < 80) 0xFF80A080.toInt() else red }
        val colors = requireNotNull(extractArtworkColors(pixels))
        ArtworkColorReference.assertHue(0.0, colors.light)
        ArtworkColorReference.assertHue(120.0, colors.dark)
        assertEquals(pair(colors.light, colors.dark), colors)
    }

    @Test fun lessThanOnePercentNoiseIsExcludedAndBoundaryIsIncluded() {
        // Only red is eligible at 199:1; blue is eligible at exactly 99:1.
        assertEquals(single(redStart), extractArtworkColors(IntArray(200) { if (it == 0) blue else red }))
        assertEquals(pair(redStart, blueStart), extractArtworkColors(IntArray(100) { if (it == 0) blue else red }))
        // Two half-alpha blue pixels are still below 1% of the total alpha weight.
        assertEquals(single(redStart), extractArtworkColors(IntArray(102) {
            if (it < 2) argb(128,0,0,255) else red
        }))
    }

    @Test fun hueSeparationIncludes35DegreesAndWrapsAtRed() {
        // Red dominates. (240,140,0) is exactly 35 degrees; 136 is 34 degrees.
        val near = argb(255,240,136,0)
        val boundary = argb(255,240,140,0)
        val below = requireNotNull(extractArtworkColors(intArrayOf(red, red, near)))
        assertEquals(single(redStart), below)
        val at = requireNotNull(extractArtworkColors(intArrayOf(red, red, boundary)))
        ArtworkColorReference.assertHue(0.0, at.light)
        ArtworkColorReference.assertHue(35.0, at.dark)
        assertEquals(pair(at.light, at.dark), at)
        assertEquals(single(redStart), extractArtworkColors(intArrayOf(red, red, argb(255,240,0,136))))
    }

    @Test fun hueSeparationIsCheckedAgainstEverySelectedColor() {
        // Equal saturation: red first (3 pixels), green second (2), orange third (1).
        // Orange is 30 degrees from red and 90 from green, so it must be rejected.
        val colors = requireNotNull(extractArtworkColors(intArrayOf(red, red, red, green, green, 0xFFFF8000.toInt())))
        ArtworkColorReference.assertHue(0.0, colors.light)
        ArtworkColorReference.assertHue(120.0, colors.dark)
        assertEquals(pair(colors.light, colors.dark), colors)
    }

    @Test fun allCandidatesBelowThresholdRestoreMostFrequentBucket() {
        // Over 200 distinct valid buckets, each <1%. Smallest tied bucket is blue (0,0,32).
        val pixels = (0..15).flatMap { r -> (2..17).map { value ->
            argb(255,r * 16, (value / 16) * 16, (value % 16) * 16)
        } }.toIntArray()
        // Exclude neutral/dark entries explicitly so every generated pixel contributes.
        val valid = pixels.filter { p ->
            val c = ArtworkColorReference.channels(p)
            c.max() - c.min() >= 24 && c.max() >= 32
        }.toIntArray()
        assertTrue(valid.size > 200)
        val expectedBlue = single(ArtworkColorReference.brightest(0xFF0C0C78.toInt()))
        assertEquals(expectedBlue, extractArtworkColors(valid))
        assertEquals(expectedBlue, extractArtworkColors(valid.reversedArray()))
        // Add one copy: this bucket wins the fallback by weight, still below 1% of the total.
        val more = valid + intArrayOf(valid.last())
        assertTrue(2.0 / more.size < 0.01)
        // Last pixel is (240,16,16): adjusted HSV rounds to (151,15,15).
        val expectedRed = single(ArtworkColorReference.brightest(0xFF970F0F.toInt()))
        assertEquals(expectedRed, extractArtworkColors(more))
        assertEquals(expectedRed, extractArtworkColors(more.reversedArray()))
    }

    @Test fun threePrimariesAndMaximumThreeColorsKeepScoreOrder() {
        // Equal scores sort by bucket: blue, green, red.
        val colors = requireNotNull(extractArtworkColors(intArrayOf(red, green, blue)))
        listOf(240.0, 120.0, 0.0).zip(ArtworkColorReference.stops(colors)).forEach { (h, c) ->
            ArtworkColorReference.assertHue(h, c)
        }
        assertEquals(3, ArtworkColorReference.stops(colors).distinct().size)
        assertEquals(colors, extractArtworkColors(intArrayOf(blue, red, green)))
        // Four equal saturated colors sort blue, green, red, yellow; yellow cannot add a stop.
        assertEquals(colors, extractArtworkColors(intArrayOf(red, green, blue, 0xFFFFFF00.toInt())))
    }

    @Test fun twoArgumentInjectionKeepsStartAsMiddle() {
        assertEquals(red, ArtworkColors(red, blue).accent)
    }
}

/** Independent test oracle: no production color conversion, compositing or contrast calls. */
internal object ArtworkColorReference {
    fun channels(rgb: Int) = listOf(16, 8, 0).map { rgb ushr it and 255 }
    fun mix(a: Int, b: Int, t: Double): Int {
        val parts = channels(a).zip(channels(b)).map { (x, y) -> floor(x + (y - x) * t + 0.5).toInt() }
        return (255 shl 24) or (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
    }
    fun luminance(rgb: Int): Double = channels(rgb).zip(listOf(0.2126, 0.7152, 0.0722)).sumOf { (c, w) ->
        val s = c / 255.0
        w * if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    fun ratio(fg: Int, bg: Int): Double {
        val a = luminance(fg) + 0.05
        val b = luminance(bg) + 0.05
        return maxOf(a / b, b / a)
    }
    fun assertReadable(bg: Int, threshold: Double = 4.5, context: String = ""): Double {
        val white = 0xFFFFFFFF.toInt()
        val minimum = listOf(white, 0xFFB0B0BB.toInt(), mix(bg, white, 0.62)).minOf { ratio(it, bg) }
        assertTrue("$context bg=${Integer.toHexString(bg)} minimum=$minimum", minimum >= threshold)
        return minimum
    }
    fun brightest(adjusted: Int): Int {
        // Exhaustively enumerate the fixed-point multiplier grid, independently of binary search.
        return (0..65536).asSequence().map { mix(0xFF000000.toInt(), adjusted, it / 65536.0) }
            .distinct().filter { color ->
                luminance(color) <= 0.045 && listOf(0xFFFFFFFF.toInt(), 0xFFB0B0BB.toInt(),
                    mix(color, 0xFFFFFFFF.toInt(), 0.62)).all { ratio(it, color) >= 4.65 }
            }.last()
    }
    fun saturation(rgb: Int): Double {
        val c = channels(rgb)
        return if (c.max() == 0) 0.0 else (c.max() - c.min()).toDouble() / c.max()
    }
    fun hue(rgb: Int): Double {
        val c = channels(rgb)
        val max = c.max()
        val d = (max - c.min()).toDouble()
        if (d == 0.0) return 0.0
        val sector = when (c.indexOf(max)) {
            0 -> (c[1] - c[2]) / d
            1 -> 2.0 + (c[2] - c[0]) / d
            else -> 4.0 + (c[0] - c[1]) / d
        }
        return (sector * 60.0 + 360.0) % 360.0
    }
    fun assertHue(expected: Double, color: Int) {
        val difference = abs(expected - hue(color))
        assertTrue("expected hue=$expected actual=${hue(color)} rgb=${Integer.toHexString(color)}",
            minOf(difference, 360.0 - difference) <= 1.0)
    }
    fun stops(colors: ArtworkColors) = listOf(colors.light, colors.accent, colors.dark)
    fun interpolations(colors: ArtworkColors): List<Int> = stops(colors).zipWithNext().flatMap { (a, b) ->
        (0..256).map { mix(a, b, it / 256.0) }
    }
    fun assertGradientPixel(colors: ArtworkColors, actual: Int, x: Int, y: Int, width: Int, height: Int) {
        val t = ((x + 0.5) * width + (y + 0.5) * height) /
            (width.toDouble() * width + height.toDouble() * height)
        val expected = if (t <= 0.5) mix(colors.light, colors.accent, t * 2.0)
            else mix(colors.accent, colors.dark, (t - 0.5) * 2.0)
        channels(expected).zip(channels(actual)).forEach { (a, b) ->
            // Raster interpolation can differ from the reference by one 8-bit rounding step.
            assertTrue("pixel=$x,$y expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}",
                abs(a - b) <= 1)
        }
        assertReadable(actual)
    }
}
