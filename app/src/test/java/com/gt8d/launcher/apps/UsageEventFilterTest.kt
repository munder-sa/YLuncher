package com.gt8d.launcher.apps

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.foreground.OwnResumeClock
import com.gt8d.launcher.foreground.ResumeEvent
import com.gt8d.launcher.foreground.UsageStatsForegroundEvents
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageEventFilterTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(UsageStatsManager::class.java)

    @Before fun setUp() {
        OwnResumeClock.reset()
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName,
            AppOpsManager.MODE_ALLOWED,
        )
    }

    @After fun tearDown() = OwnResumeClock.reset()

    @Test fun queryRequestsOnlyResumedWithExactTimesAndNoPackageRestriction() {
        val query = usageResumedQuery(107_000L, 121_000L)

        assertEquals(107_000L, query.beginTimeMillis)
        assertEquals(121_000L, query.endTimeMillis)
        assertArrayEquals(intArrayOf(UsageEvents.Event.ACTIVITY_RESUMED), query.eventTypes)
        assertTrue(query.packageNames.isEmpty())
    }

    @Test fun historyOsQueryMatchesLegacyFilterForMixedEvents() {
        seedMixedEvents()
        val begin = 111_200L
        val history = UsageHistory(app).apply {
            wallClock = { 120_000L }
        }
        val end = history.wallClock()
        assertEquals(120_000L, end)
        val legacy = legacyRawEvents(begin, end)
            .filter { it.type == UsageEvents.Event.ACTIVITY_RESUMED }
            .map { UsageLaunch(it.pkg, it.wallMs) }
            .filter { it.wallMs >= begin && it.wallMs <= end }
            .sortedBy { it.wallMs }
        val expected = listOf(
            UsageLaunch("at-since", 111_200L),
            UsageLaunch("youtube", 111_500L),
            UsageLaunch("maps", 111_500L),
            UsageLaunch("unregistered", 113_000L),
            UsageLaunch("last", 119_999L),
        )

        assertEquals(expected, legacy)
        assertEquals(legacy, history.launchesSince(begin))
    }

    @Test fun foregroundOsQueryMatchesLegacyFilterAndOwnResumeSkewForMixedEvents() {
        seedMixedEvents()
        OwnResumeClock.mark("HomeActivity", 10_000L)
        val legacy = foreground().apply {
            queryRawEvents = { begin, end ->
                assertEquals(107_000L, begin)
                assertEquals(121_000L, end)
                legacyRawEvents(begin, end)
            }
        }.resumedSince(12_000L)
        val expected = listOf(
            ResumeEvent("at-since", 12_000L),
            ResumeEvent("youtube", 12_300L),
            ResumeEvent("maps", 12_300L),
            ResumeEvent("unregistered", 13_800L),
            ResumeEvent("last", 20_799L),
            ResumeEvent("history-end", 20_800L),
        )

        assertEquals(expected, legacy)
        assertEquals(legacy, foreground().resumedSince(12_000L))
    }

    private fun foreground() = UsageStatsForegroundEvents(app).apply {
        wallClock = { 120_000L }
        elapsedClock = { 20_000L }
    }

    private fun legacyRawEvents(begin: Long, end: Long): List<UsageStatsForegroundEvents.RawEvent> {
        val events = requireNotNull(manager.queryEvents(begin, end))
        return buildList {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                add(UsageStatsForegroundEvents.RawEvent(pkg, event.className, event.eventType, event.timeStamp))
            }
        }
    }

    private fun seedMixedEvents() {
        addEvent("outside-before", 106_999L)
        addEvent("query-begin", 107_000L)
        addEvent(app.packageName, 109_200L, className = "HomeActivity")
        // A non-RESUMED event closer to the mark must not replace the -800 ms skew.
        addEvent(app.packageName, 110_000L, UsageEvents.Event.ACTIVITY_PAUSED, "HomeActivity")
        addEvent("before-since", 111_199L)
        addEvent("at-since", 111_200L)
        addEvent("youtube", 111_500L)
        addEvent("maps", 111_500L)
        addEvent("youtube", 112_000L, UsageEvents.Event.ACTIVITY_PAUSED)
        addEvent("maps", 112_500L, UsageEvents.Event.ACTIVITY_STOPPED)
        addEvent("android", 112_700L, UsageEvents.Event.SCREEN_INTERACTIVE)
        addEvent("unregistered", 113_000L)
        addEvent("last", 119_999L)
        addEvent("history-end", 120_000L)
        addEvent("foreground-end", 121_000L)
        addEvent("outside-after", 121_001L)
    }

    private fun addEvent(
        pkg: String,
        wallMs: Long,
        type: Int = UsageEvents.Event.ACTIVITY_RESUMED,
        className: String = "Activity",
    ) {
        shadowOf(manager).addEvent(
            EventBuilder.buildEvent()
                .setPackage(pkg)
                .setClass(className)
                .setTimeStamp(wallMs)
                .setEventType(type)
                .build(),
        )
    }
}
