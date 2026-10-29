package com.gt8d.launcher.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWatcherTest {
    @Test
    fun recordAppLaunch_successMarksPackageWithoutUsageAccess() {
        val tracker = WarmTracker("own") { false }
        tracker.recordAppLaunch("a", true)
        assertTrue(tracker.isWarm("a"))
    }

    @Test
    fun recordAppLaunch_failureDoesNotMarkPackage() {
        val tracker = WarmTracker("own") { false }
        tracker.recordAppLaunch("a", false)
        assertFalse(tracker.isWarm("a"))
    }

    @Test
    fun recordAppLaunch_ownPackageStillDependsOnOwnTask() {
        var ownTaskExists = false
        val tracker = WarmTracker("own") { ownTaskExists }
        tracker.recordAppLaunch("own", true)
        assertFalse(tracker.isWarm("own"))
        ownTaskExists = true
        assertTrue(tracker.isWarm("own"))
    }

    @Test
    fun queryFailure_marksWatcherUnavailable_andReturnsNoEvidence() {
        val events = FakeForegroundEvents(queryFails = true)
        val watcher = ForegroundWatcher(events)
        assertEquals(emptySet<String>(), watcher.foregroundPkgsSince(0))
        assertFalse(watcher.available())
        assertFalse(watcher.bothResumed("a", "b", 0))
        assertFalse(watcher.available())
        assertNull(watcher.lastForegroundSince(0))
        assertFalse(watcher.available())
    }

    @Test
    fun queryRecovery_restoresAvailability() {
        val events = FakeForegroundEvents(events = listOf(ResumeEvent("a", 100)), queryFails = true)
        val watcher = ForegroundWatcher(events)
        assertEquals(emptySet<String>(), watcher.foregroundPkgsSince(0))
        assertFalse(watcher.available())
        events.queryFails = false
        assertEquals(setOf("a"), watcher.foregroundPkgsSince(0))
        assertTrue(watcher.available())
    }

    @Test
    fun successfulEmptyQuery_keepsWatcherAvailable() {
        val watcher = ForegroundWatcher(FakeForegroundEvents())
        assertEquals(emptySet<String>(), watcher.foregroundPkgsSince(0))
        assertTrue(watcher.available())
    }

    @Test
    fun foregroundPkgsSince_collectsAllAfterSince() {
        val watcher = ForegroundWatcher(FakeForegroundEvents(events = listOf(
            ResumeEvent("x", 999), ResumeEvent("a", 1000),
            ResumeEvent("c", 1100), ResumeEvent("a", 1200),
        )))
        assertEquals(setOf("a", "c"), watcher.foregroundPkgsSince(1000))
    }

    @Test
    fun foregroundPkgsSince_unavailable_empty() {
        val watcher = ForegroundWatcher(FakeForegroundEvents(
            available = false, events = listOf(ResumeEvent("a", 1000)),
        ))
        assertEquals(emptySet<String>(), watcher.foregroundPkgsSince(1000))
    }

    private class FakeForegroundEvents(
        private val available: Boolean = true,
        private val events: List<ResumeEvent> = emptyList(),
        var queryFails: Boolean = false,
    ) : ForegroundEvents {
        override fun available(): Boolean = available
        override fun resumedSince(sinceMs: Long): List<ResumeEvent> {
            if (queryFails) throw ForegroundEventsUnavailableException()
            return events
        }
    }

    @Test
    fun bothResumed_trueOnlyWhenBothAfterSince() {
        val bothAfter = ForegroundWatcher(
            FakeForegroundEvents(
                available = true,
                events = listOf(
                    ResumeEvent("a", 1100L),
                    ResumeEvent("b", 1200L),
                ),
            ),
        )
        assertTrue(bothAfter.bothResumed("a", "b", 1000L))

        val onlyOne = ForegroundWatcher(
            FakeForegroundEvents(
                available = true,
                events = listOf(
                    ResumeEvent("a", 1100L),
                ),
            ),
        )
        assertFalse(onlyOne.bothResumed("a", "b", 1000L))

        val oneBeforeSince = ForegroundWatcher(
            FakeForegroundEvents(
                available = true,
                events = listOf(
                    ResumeEvent("a", 900L),
                    ResumeEvent("b", 1200L),
                ),
            ),
        )
        assertFalse(oneBeforeSince.bothResumed("a", "b", 1000L))
    }

    @Test
    fun unavailable_bothResumedFalse_andLastNull() {
        val watcher = ForegroundWatcher(
            FakeForegroundEvents(
                available = false,
                events = listOf(
                    ResumeEvent("a", 1100L),
                    ResumeEvent("b", 1200L),
                ),
            ),
        )
        assertFalse(watcher.available())
        assertFalse(watcher.bothResumed("a", "b", 1000L))
        assertNull(watcher.lastForegroundSince(1000L))
    }

    @Test
    fun lastForegroundSince_returnsLatest() {
        val watcher = ForegroundWatcher(
            FakeForegroundEvents(
                available = true,
                events = listOf(
                    ResumeEvent("a", 1100L),
                    ResumeEvent("b", 1300L),
                    ResumeEvent("c", 1200L),
                ),
            ),
        )
        assertEquals("b", watcher.lastForegroundSince(1000L))
        assertNull(watcher.lastForegroundSince(1400L))
    }

    @Test
    fun warm_bothMarked_800() {
        val tracker = WarmTracker(ownPkg = "com.gt8d.launcher", ownTaskExists = { false })
        tracker.markForeground("a")
        tracker.markForeground("b")

        assertTrue(tracker.isWarm("a"))
        assertTrue(tracker.isWarm("b"))
        assertEquals(800L, tracker.expectedDelayMs("a", "b"))
    }

    @Test
    fun cold_oneUnmarked_3500() {
        val tracker = WarmTracker(ownPkg = "com.gt8d.launcher", ownTaskExists = { false })
        tracker.markForeground("a")

        assertTrue(tracker.isWarm("a"))
        assertFalse(tracker.isWarm("b"))
        assertEquals(3500L, tracker.expectedDelayMs("a", "b"))
        assertEquals(3500L, tracker.expectedDelayMs("b", "a"))
    }

    @Test
    fun ownPkg_warmWhenOwnTaskExists() {
        val own = "com.gt8d.launcher"
        val tracker = WarmTracker(ownPkg = own, ownTaskExists = { true })
        tracker.markForeground("x")

        assertTrue(tracker.isWarm(own))
        assertEquals(800L, tracker.expectedDelayMs("x", own))
    }

    @Test
    fun ownPkg_coldWhenNoTask_3500() {
        val own = "com.gt8d.launcher"
        val tracker = WarmTracker(ownPkg = own, ownTaskExists = { false })
        tracker.markForeground("x")
        tracker.markForeground(own)

        assertFalse(tracker.isWarm(own))
        assertEquals(3500L, tracker.expectedDelayMs("x", own))
    }
}
