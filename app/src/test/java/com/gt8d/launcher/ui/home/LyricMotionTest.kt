package com.gt8d.launcher.ui.home

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricMotionTest {
    @Test fun delayByDistanceIsCapped() {
        assertEquals(listOf(100, 100, 50, 0, 50, 100, 100), (0..6).map { lineDelayMs(it, 3) })
    }

    @Test fun visibilityUsesStartOrDestination() {
        assertEquals(setOf(0, 1, 4, 5), visibleMotionRows(
            listOf(-20f, 230f, -20f, 240f, -100f, 20f),
            listOf(-20f, 230f, -20f, 240f, 20f, 300f),
            listOf(30, 30, 20, 20, 20, 20), 240))
    }

    private fun motion(): LyricMotion = LyricMotion().also {
        it.prepare(0, listOf(100f, 148f, 196f, 244f), listOf(32, 32, 32, 32), 400, 300)
        it.prepare(1, listOf(52f, 100f, 148f, 196f), listOf(32, 32, 32, 32), 400, 300)
    }

    @Test fun currentStartsWithoutDelay() {
        val m = motion()
        assertEquals(0, m.rows[1].delayMs)
        m.advance(1f)
        assertTrue(m.rows[1].sample().top < 148f)
    }

    @Test fun neighborsWait50AndFarRows100() {
        val m = motion()
        m.advance(49f)
        assertEquals(100f, m.rows[0].sample().top, 0f)
        m.advance(50f)
        assertEquals(100f, m.rows[0].sample().top, 0f)
        m.advance(51f)
        assertTrue(m.rows[0].sample().top < 100f)
        m.advance(99f)
        assertEquals(244f, m.rows[3].sample().top, 0f)
        m.advance(100f)
        assertEquals(244f, m.rows[3].sample().top, 0f)
        m.advance(101f)
        assertTrue(m.rows[3].sample().top < 244f)
    }

    @Test fun allRowsSettleBy900ms() {
        val m = motion()
        m.advance(900f)
        m.rows.forEach { assertEquals(it.targetTop, it.sample().top, 0.5f) }
        assertFalse(m.running)
    }

    @Test fun retargetKeepsEveryVisibleRowContinuous() {
        for (time in listOf(25f, 300f)) {
            val m = motion()
            m.advance(time)
            val before = m.rows.map { it.sample() }
            m.prepare(2, listOf(4f, 52f, 100f, 148f), listOf(32, 32, 32, 32), 400, 300)
            m.rows.forEachIndexed { i, row -> assertEquals(before[i].top, row.sample().top, 0.5f) }
        }
    }

    @Test fun unsafeSpacingDisablesStagger() {
        assertTrue(canStaggerWithoutOverlap(listOf(0f, 48f), listOf(-48f, 0f), listOf(32, 32), listOf(50, 0)))
        assertFalse(canStaggerWithoutOverlap(listOf(0f, 48f), listOf(-80f, -32f), listOf(32, 32), listOf(50, 0)))
        assertFalse(canStaggerWithoutOverlap(listOf(Float.NaN), listOf(0f), listOf(32), listOf(0)))
        assertFalse(canStaggerWithoutOverlap(listOf(0f), listOf(0f), listOf(-1), listOf(0)))
        val m = LyricMotion()
        m.prepare(0, listOf(0f, 48f), listOf(32, 32), 400, 300)
        m.prepare(1, listOf(-80f, -32f), listOf(32, 32), 400, 300)
        assertTrue(m.rows.all { it.delayMs == 0 })
        for (t in 0..800 step 10) {
            m.advance(t.toFloat())
            assertTrue(m.rows[1].sample().top - m.rows[0].sample().top >= 32f)
        }
    }

    @Test fun jumpCancelsPendingDelays() {
        for (index in listOf(3, 0)) {
            val m = motion()
            m.advance(25f)
            m.rows.forEach { it.sample() }
            m.prepare(index, listOf(-20f, 28f, 76f, 124f), listOf(32, 32, 32, 32), 400, 300)
            assertFalse(m.running)
            m.advance(1000f)
            m.rows.forEach { assertEquals(it.targetTop, it.sample().top, 0f) }
        }
    }

    @Test fun trackAndSizeChangesSnap() {
        val m = motion()
        m.prepare(2, listOf(0f, 48f, 96f, 144f), listOf(32, 32, 32, 32), 240, 300)
        assertFalse(m.running)
        m.rows.forEach { assertEquals(it.targetTop, it.sample().top, 0f) }
        val newTrack = LyricMotion()
        newTrack.prepare(1, listOf(0f), listOf(32), 240, 300)
        assertFalse(newTrack.running)
    }

    @Test fun disabledAnimationSnapsIncludingDelay() = runTest {
        val m = motion()
        val frames = BroadcastFrameClock()
        val scale = object : MotionDurationScale { override val scaleFactor = 0f }
        backgroundScope.launch(frames + scale) { m.run {} }
        runCurrent()
        frames.sendFrame(0L)
        runCurrent()
        assertFalse(m.running)
        m.rows.forEach { assertEquals(it.targetTop, it.sample().top, 0f) }
    }

    @Test fun durationScaleAppliesToWaitingAndMovement() = runTest {
        val m = motion()
        val frames = BroadcastFrameClock()
        val scale = object : MotionDurationScale { override val scaleFactor = 2f }
        backgroundScope.launch(frames + scale) { m.run {} }
        runCurrent()
        frames.sendFrame(0L)
        runCurrent()
        frames.sendFrame(98_000_000L)
        runCurrent()
        assertEquals(100f, m.rows[0].sample().top, 0f)
        frames.sendFrame(102_000_000L)
        runCurrent()
        assertTrue(m.rows[0].sample().top < 100f)
        frames.sendFrame(1_800_000_000L)
        runCurrent()
        assertFalse(m.running)
        m.rows.forEach { assertEquals(it.targetTop, it.sample().top, 0.5f) }
    }

    @Test fun scaleCurrentAndOthers() {
        val m = motion()
        m.advance(500f)
        m.rows.forEachIndexed { i, row -> assertEquals(if (i == 1) 1f else 0.97f, row.sample().scale, 0f) }
    }

    @Test fun scaleRetargetIsContinuous() {
        val m = motion()
        m.advance(300f)
        val before = m.rows.map { it.sample().scale }
        m.prepare(2, listOf(4f, 52f, 100f, 148f), listOf(32, 32, 32, 32), 400, 300)
        m.rows.forEachIndexed { i, row -> assertEquals(before[i], row.sample().scale, 0f) }
    }

    @Test fun offscreenRowsHaveNoAnimationJobs() {
        val m = LyricMotion()
        m.prepare(0, listOf(100f, 148f, 1000f), listOf(32, 32, 32), 240, 300)
        m.prepare(1, listOf(52f, 100f, 952f), listOf(32, 32, 32), 240, 300)
        assertFalse(m.rows[2].animated)
        assertEquals(0, m.rows[2].delayMs)
        assertEquals(952f, m.rows[2].sample().top, 0f)
    }
}
