package com.gt8d.launcher.ui.home

import org.junit.Assert.*
import org.junit.Test

class InterludeUpdatePlanTest {
    @Test fun fractionalThirdsUseTheFirstIntegerWithADifferentRenderedCount() {
        assertEquals(334L, nextInterludeBoundary(0, 1000, 0))
        assertEquals(334L, nextInterludeBoundary(0, 1000, 333))
        assertEquals(667L, nextInterludeBoundary(0, 1000, 334))
        assertEquals(1000L, nextInterludeBoundary(0, 1000, 667))
        assertEquals(14L, nextInterludeBoundary(10, 20, 0))
        assertEquals(17L, nextInterludeBoundary(10, 20, 14))
    }

    @Test fun shortEmptyReversedAndNegativeIntervalsMatchExhaustiveDrawingSamples() {
        for (start in -8L..8L) for (end in -8L..20L) for (position in 0L..22L) {
            val expected = if (end <= start || position >= end) null else
                (position + 1L..end).firstOrNull {
                    interludeLitDots(start, end, it) != interludeLitDots(start, end, position)
                }
            assertEquals("start=$start end=$end position=$position", expected,
                nextInterludeBoundary(start, end, position))
        }
        assertEquals(1L, nextInterludeBoundary(0, 1, 0))
        assertEquals(3, interludeLitDots(0, 1, 1))
        assertEquals(1000L, nextInterludeBoundary(-1000, 2000, 0))
    }

    @Test fun invalidPositionAndTerminalCountsDoNotSchedule() {
        assertNull(nextInterludeBoundary(0, 3000, -1))
        assertNull(nextInterludeBoundary(0, 3000, 3000))
        assertNull(nextInterludeBoundary(0, 3000, Long.MAX_VALUE))
        assertNull(nextInterludeBoundary(1000, 1000, 0))
        assertNull(nextInterludeBoundary(1000, 999, 0))
    }

    @Test fun largeValuesKeepDoubleRoundingInsteadOfRationalThirds() {
        val end = Long.MAX_VALUE
        for (start in listOf(end - 8192L, end - 1024L, end - 1L)) {
            // A linear oracle checks even the values that collapse to the same Double.
            val changes = mutableListOf<Long>()
            var previous = interludeLitDots(start, end, start)
            var position = start
            while (position < end) {
                position++
                val lit = interludeLitDots(start, end, position)
                if (lit != previous) changes += position
                previous = lit
            }
            position = start
            while (true) {
                assertEquals("start=$start position=$position", changes.firstOrNull { it > position },
                    nextInterludeBoundary(start, end, position))
                if (position == end) break
                position++
            }
        }
        assertNull(nextInterludeBoundary(end - 1L, end, end - 1L))
    }

    @Test fun fullLongRangeDoesNotOverflowAndAlwaysFindsTheFirstChange() {
        for (start in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE / 2L)) {
            var position = 0L
            var changes = 0
            while (true) {
                val boundary = nextInterludeBoundary(start, Long.MAX_VALUE, position) ?: break
                assertTrue(boundary > position)
                val lit = interludeLitDots(start, Long.MAX_VALUE, position)
                assertEquals(lit, interludeLitDots(start, Long.MAX_VALUE, boundary - 1L))
                assertTrue(interludeLitDots(start, Long.MAX_VALUE, boundary) > lit)
                position = boundary
                changes++
                assertTrue(changes <= 3)
            }
            assertTrue(changes > 0)
            assertEquals(3, interludeLitDots(start, Long.MAX_VALUE, position))
        }
    }

    @Test fun finitePositiveSpeedsConvertPlaybackMillisecondsToDelayMilliseconds() {
        for ((speed, delayMs) in listOf(0.5f to 2000L, 1f to 1000L, 2f to 500L)) {
            assertEquals(InterludeUpdatePlan.Boundary(1000, delayMs),
                planInterludeUpdate(0, 3000, 0, speed, null))
        }
        assertEquals(InterludeUpdatePlan.Boundary(334, 167), planInterludeUpdate(0, 1000, 0, 2f, null))
        assertEquals(InterludeUpdatePlan.Boundary(334, 167), planInterludeUpdate(0, 1000, 1, 2f, null))
    }

    @Test fun zeroSpeedWaitsAndUnsupportedSpeedsUseFrameInterpolation() {
        for (speed in listOf(0f, -0f)) {
            assertEquals(InterludeUpdatePlan.Idle, planInterludeUpdate(0, 3000, 0, speed, null))
        }
        for (speed in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(InterludeUpdatePlan.EveryFrame, planInterludeUpdate(0, 3000, 1500, speed, null))
        }
    }

    @Test fun extremeSpeedsSaturateDelayAndNeverCreateZeroMillisecondRetries() {
        assertEquals(InterludeUpdatePlan.Boundary(1000, Long.MAX_VALUE),
            planInterludeUpdate(0, 3000, 0, Float.MIN_VALUE, null))
        assertEquals(InterludeUpdatePlan.Boundary(1000, 1),
            planInterludeUpdate(0, 3000, 999, Float.MAX_VALUE, null))
        assertEquals(InterludeUpdatePlan.Boundary(1000, 2),
            planInterludeUpdate(0, 3000, 999, 0.5f, null))
    }

    @Test fun durationClampStopsUnreachableBoundariesButAllowsTheEndpointOnce() {
        assertEquals(InterludeUpdatePlan.Idle, planInterludeUpdate(0, 3000, 0, 1f, 999))
        assertEquals(InterludeUpdatePlan.Boundary(1000, 1000), planInterludeUpdate(0, 3000, 0, 1f, 1000))
        assertEquals(InterludeUpdatePlan.Idle, planInterludeUpdate(0, 3000, 1000, 1f, 1000))
        assertEquals(InterludeUpdatePlan.Idle, planInterludeUpdate(0, 3000, 3000, 1f, 3000))
        for (duration in listOf(null, 0L, -1L)) {
            assertEquals(InterludeUpdatePlan.Boundary(1000, 1000),
                planInterludeUpdate(0, 3000, 0, 1f, duration))
        }
    }
}
