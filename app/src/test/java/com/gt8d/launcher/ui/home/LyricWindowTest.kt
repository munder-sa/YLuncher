package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricWindowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val geometry = LyricWindowGeometry(300, 240, 1f, 1f, "22sp-bold")
    private fun heights(n: Int) = List(n) { if (it % 3 == 0) 54 else 27 }
    private fun plan(
        hs: List<Int>, current: Int, previous: LyricWindowPlan? = null,
        displayed: Map<Int, Float> = emptyMap(), g: LyricWindowGeometry = geometry,
        song: Long = 1, token: Long = (previous?.generation ?: 0) + 1,
        scale: Float = 1f, intro: Boolean = false, measured: (Int) -> Unit = {},
    ) = planLyricWindow(song, token, hs.size, current, g, previous, displayed, scale, intro) {
        measured(it.row)
        hs[it.row]
    }

    // Deliberately enumerates the whole song, independently of the local planner.
    private fun targets(hs: List<Int>, current: Int, height: Int): List<Float> {
        val anchor = current.coerceAtLeast(0)
        val origin = height / 2f - hs.take(anchor).sumOf { it + 16 } - hs[anchor] / 2f
        var y = origin
        return hs.map { h -> y.also { y += h + 16 } }
    }
    private fun assertCoverage(p: LyricWindowPlan, hs: List<Int>, starts: List<Float>? = null) {
        val ends = targets(hs, p.current, p.geometry.heightPx)
        val d = starts?.indices?.maxOf { abs(starts[it] - ends[it]) } ?: 0f
        val m = hs.max() + 16f
        hs.indices.forEach { i ->
            val lo = minOf(starts?.get(i) ?: ends[i], ends[i])
            val hi = maxOf(starts?.get(i) ?: ends[i], ends[i]) + hs[i]
            if (lo < p.geometry.heightPx && hi > 0) assertTrue("missing path row $i", i in p.rows)
            if (ends[i] < p.geometry.heightPx + d + m && ends[i] + hs[i] > -d - m) {
                assertTrue("missing expanded row $i", i in p.rows)
            }
        }
        p.rows.forEach { (i, r) -> assertEquals(ends[i], r.targetTop, 0.001f) }
    }

    @Test fun mixedHeightsAndShortRowsMatchFullReference() {
        for (hs in listOf(heights(1000), List(1000) { 2 })) {
            val a = plan(hs, 500)
            assertCoverage(a, hs)
            val b = plan(hs, 501, a)
            assertCoverage(b, hs, targets(hs, 500, 240))
            assertTrue(b.rows.keys.containsAll(a.rows.keys))
        }
    }

    @Test fun integratedMotionRetainsMixedHeightRowsAcrossRapidRetargets() {
        val hs = heights(1000)
        val motion = LyricMotion()
        var previous = plan(hs, 500)
        motion.prepare(previous) {}
        for (current in 501..505) {
            val before = motion.windowRows.mapValues { it.value.sample() }
            val next = plan(hs, current, previous, motion.displayedTops())
            motion.prepare(next) {}
            before.forEach { (i, sample) ->
                assertEquals(sample.top, motion.windowRows.getValue(i).sample().top, 0.5f)
            }
            for (elapsed in listOf(25f, 100f, 300f)) {
                motion.advance(elapsed)
                val samples = motion.windowRows.mapValues { it.value.sample() }
                samples.keys.sorted().zipWithNext().forEach { (a, b) ->
                    assertTrue("overlap rows=$a,$b current=$current time=$elapsed",
                        samples.getValue(b).top - samples.getValue(a).top >= hs[a] - 0.001f)
                }
            }
            assertTrue(next.rows.keys.containsAll(previous.rows.keys))
            assertSame(next, next.complete(previous.generation, true, true))
            previous = next
        }
        motion.advance(900f)
        motion.windowRows.values.forEach { assertEquals(it.targetTop, it.sample().top, 0.5f) }
        val completed = previous.complete(previous.generation, true, true)
        motion.retainWindowRows(completed.rows.keys)
        assertEquals(completed.rows.keys, motion.windowRows.keys)
        assertTrue(completed.rows.size < previous.rows.size)
    }

    @Test fun firstLastBeforeFirstAndEmpty() {
        val hs = heights(30)
        for (i in listOf(-1, 0, 29)) assertCoverage(plan(hs, i), hs)
        assertFalse(plan(hs, 0, plan(hs, -1)).animated)
        assertTrue(plan(emptyList(), -1).rows.isEmpty())
        assertEquals(null, plan(emptyList(), -1).anchor)
    }

    @Test fun viewportIncludesMaskAnd120dpBoundary() {
        for (h in listOf(0, 119, 120, 121, 240)) {
            val p = plan(heights(100), 50, g = geometry.copy(heightPx = h))
            assertCoverage(p, heights(100))
            assertEquals(h / 2f, p.rows.getValue(50).let { it.targetTop + it.heightPx / 2f }, 0f)
        }
    }

    @Test fun longSongDoesNotMeasureDistantRows() {
        val counts = listOf(1000, 100000).map { n ->
            val measured = mutableListOf<Int>()
            val p = plan(heights(n), 500, measured = { measured += it })
            assertEquals(p.rows.keys, measured.toSet())
            assertTrue(measured.all { abs(it - 500) < 30 })
            measured.size
        }
        assertEquals(counts[0], counts[1])
    }

    @Test fun retainedRowsAreNotRemeasuredAndOnlyEnteringRowsAreMeasured() {
        val hs = heights(1000)
        val a = plan(hs, 500)
        val measured = mutableListOf<Int>()
        val b = plan(hs, 501, a, measured = { measured += it })
        assertTrue(measured.isNotEmpty())
        assertEquals(b.rows.keys - a.rows.keys, measured.toSet())
        assertEquals(measured.size, measured.toSet().size)
    }

    @Test fun movementAndFadeMustBothFinishAndOldCompletionIsIgnored() {
        val hs = heights(1000)
        val b = plan(hs, 501, plan(hs, 500))
        assertSame(b, b.complete(b.generation, movementFinished = false, fadeFinished = true))
        assertSame(b, b.complete(b.generation, movementFinished = true, fadeFinished = false))
        assertSame(b, b.complete(b.generation - 1, true, true))
        val done = b.complete(b.generation, true, true)
        assertFalse(done.animated)
        assertCoverage(done, hs)
        assertTrue(done.rows.size < b.rows.size)
        assertSame(done, done.complete(done.generation, true, true))
    }

    @Test fun staggeredPathsRemainPresentThrough900msAndFade500ms() {
        val hs = heights(1000)
        val a = plan(hs, 500)
        val b = plan(hs, 501, a)
        val starts = targets(hs, 500, 240)
        val ends = targets(hs, 501, 240)
        for (t in 0..900 step 10) hs.indices.forEach { i ->
            val fraction = ((t - lineDelayMs(i, 501)) / 800f).coerceIn(0f, 1f)
            val y = starts[i] + (ends[i] - starts[i]) * fraction
            if (y < 240 && y + hs[i] > 0) assertTrue(i in b.rows)
        }
        assertSame(b, b.complete(b.generation, false, true))
    }

    @Test fun retargetUsesDisplayedCoordinatesAndKeepsAllOldRows() {
        val hs = heights(1000)
        var p = plan(hs, 500)
        for (current in 501..505) {
            val displayed = p.rows.mapValues { (i, r) ->
                r.startTop + (r.targetTop - r.startTop) * if (i % 2 == 0) 0.2f else 0.8f
            }
            val next = plan(hs, current, p, displayed)
            assertTrue(next.rows.keys.containsAll(p.rows.keys))
            displayed.forEach { (i, top) -> assertEquals(top, next.rows.getValue(i).startTop, 0f) }
            val fullStarts = targets(hs, p.current, 240).mapIndexed { i, top -> displayed[i] ?: top }
            assertCoverage(next, hs, fullStarts)
            assertSame(next, next.complete(p.generation, true, true))
            p = next
        }
    }

    @Test fun jumpsBackwardsTrackAndGeometryChangesSnapAndInvalidateCache() {
        val hs = heights(1000)
        val a = plan(hs, 500)
        val b = plan(hs, 501, a)
        for (i in listOf(503, 499)) {
            val p = plan(hs, i, b)
            assertFalse(p.animated)
            assertCoverage(p, hs)
            assertTrue(p.rows.values.all { it.startTop == it.targetTop })
        }
        val variants = listOf(
            geometry.copy(widthPx = 200), geometry.copy(density = 2f),
            geometry.copy(fontScale = 1.5f), geometry.copy(styleKey = "other"),
            geometry.copy(heightPx = 120),
        )
        variants.forEach { g ->
            val measured = mutableSetOf<Int>()
            val p = plan(hs, 502, b, g = g, measured = { measured += it })
            assertFalse(p.animated)
            assertEquals(p.rows.keys, measured)
        }
        val measured = mutableSetOf<Int>()
        val song = plan(hs, 502, b, song = 2, measured = { measured += it })
        assertFalse(song.animated)
        assertEquals(song.rows.keys, measured)
    }

    @Test fun zeroAnimationScaleSnapsAndIntroHasDistinctMeasurementKey() {
        val hs = heights(100)
        val a = plan(hs, 0)
        assertFalse(plan(hs, 1, a, scale = 0f).animated)
        val measured = mutableSetOf<Int>()
        val b = plan(hs, 0, a, intro = true, measured = { measured += it })
        assertTrue(0 in measured)
        assertTrue(b.rows.getValue(0).measurementKey.intro)
    }

    @Test fun newlyDiscoveredTallRowExpandsMarginAgain() {
        val hs = List(1000) { if (it == 504) 180 else 20 }
        assertCoverage(plan(hs, 500), hs)
    }

    @Test fun completionDropsCacheAndSeekMeasuresOnlyItsLocalDestination() {
        val hs = heights(1000)
        val moving = plan(hs, 501, plan(hs, 500))
        val done = moving.complete(moving.generation, true, true)
        val measured = mutableSetOf<Int>()
        val distant = plan(hs, 900, done, measured = { measured += it })
        assertEquals(distant.rows.keys, measured)
        assertTrue(measured.all { abs(it - 900) < 30 })
        measured.clear()
        val back = plan(hs, 500, distant, measured = { measured += it })
        assertEquals(back.rows.keys, measured)
        assertFalse(back.animated)
    }

    @Test fun stalePreparationAndMissingActiveLayerSamplesAreRejected() {
        val hs = heights(100)
        val a = plan(hs, 50)
        assertThrows(IllegalArgumentException::class.java) { plan(hs, 51, a, token = a.generation) }
        val b = plan(hs, 51, a)
        assertThrows(IllegalArgumentException::class.java) { plan(hs, 52, b) }
        assertThrows(IllegalArgumentException::class.java) {
            plan(hs, 52, b, b.rows.mapValues { Float.NaN })
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun composeTextMeasurementMatchesRenderedTwoLineText() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val measured = IntArray(4)
        val rendered = IntArray(4)
        try {
            controller.get().setContent {
                Column {
                    listOf(120 to 1f, 300 to 1f, 120 to 1.5f, 300 to 1.5f).forEachIndexed { i, (width, scale) ->
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                            val measurer = rememberTextMeasurer()
                            val style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            val text = "折り返して表示する長い歌詞\n省略される続きの歌詞"
                            val result = measurer.measure(text, style, overflow = TextOverflow.Ellipsis,
                                maxLines = 2, constraints = Constraints(maxWidth = width))
                            SideEffect { measured[i] = result.size.height }
                            Text(text, Modifier.width(with(LocalDensity.current) { width.toDp() }),
                                style = style, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                onTextLayout = { rendered[i] = it.size.height })
                        }
                    }
                }
            }
            controller.visible()
            rule.waitForIdle()
            assertTrue(measured.all { it > 0 })
            assertArrayEquals(measured, rendered)
            assertTrue(measured[2] > measured[0])
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
