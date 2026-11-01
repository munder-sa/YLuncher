package com.gt8d.launcher.foreground

import android.content.Context
import android.content.ContextWrapper
import android.app.usage.UsageEvents
import org.junit.Before
import org.junit.After
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageStatsForegroundEventsTest {
    @Before fun resetClockBefore() = OwnResumeClock.reset()
    @After fun resetClockAfter() = OwnResumeClock.reset()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun raw(pkg: String, time: Long, className: String? = null) =
        UsageStatsForegroundEvents.RawEvent(pkg, className, UsageEvents.Event.ACTIVITY_RESUMED, 100_000L + time)

    private fun source(rawEvents: List<UsageStatsForegroundEvents.RawEvent>) =
        UsageStatsForegroundEvents(context).apply {
            wallClock = { 120_000L }
            elapsedClock = { 20_000L }
            queryRawEvents = { begin, end ->
                rawEvents.filter { it.wallMs in begin..end }
            }
        }

    @Test fun delayedClock_recoversPostLaunchResume() {
        val rawEvents = listOf(raw(context.packageName, 9200, "HomeActivity"), raw("youtube", 11500))
        assertEquals(emptyList<ResumeEvent>(), source(rawEvents).resumedSince(12000))
        OwnResumeClock.mark("HomeActivity", 10000)
        val events = source(rawEvents)
        events.queryRawEvents = { begin, end ->
            assertEquals(107000L, begin)
            assertEquals(121000L, end)
            rawEvents
        }
        assertEquals(listOf(ResumeEvent("youtube", 12300)), events.resumedSince(12000))
    }

    @Test fun advancedClock_excludesPreLaunchResume() {
        OwnResumeClock.mark("HomeActivity", 10000)
        val events = source(listOf(raw(context.packageName, 10500, "HomeActivity"), raw("youtube", 12300)))
        assertEquals(emptyList<ResumeEvent>(), events.resumedSince(12000))
    }

    @Test fun unmatchedOrDistantMark_doesNotCorrect() {
        OwnResumeClock.mark("HomeActivity", 10000)
        val candidates = listOf(
            raw(context.packageName, 9200, "PanelActivity"),
            raw("other", 9200, "HomeActivity"),
            raw(context.packageName, 13001, "HomeActivity"),
            raw(context.packageName, 6999, "HomeActivity"),
        )
        for (candidate in candidates) {
            assertEquals(listOf(ResumeEvent("youtube", 12300)),
                source(listOf(candidate, raw("youtube", 12300))).resumedSince(12000)
                    .filter { it.pkg == "youtube" })
        }
    }

    @Test fun learnedSkew_isRetainedOnNextQuery() {
        OwnResumeClock.mark("HomeActivity", 10000)
        val events = source(listOf(raw(context.packageName, 9200, "HomeActivity"), raw("youtube", 11500)))
        assertEquals(listOf(ResumeEvent("youtube", 12300)), events.resumedSince(12000))
        events.queryRawEvents = { _, _ -> listOf(raw("maps", 14500)) }
        assertEquals(listOf(ResumeEvent("maps", 15300)), events.resumedSince(15000))
    }

    @Test
    fun queryFailure_isNotReportedAsEmptySuccess() {
        val cause = IllegalStateException("query failed")
        val events = UsageStatsForegroundEvents(ApplicationProvider.getApplicationContext()) { throw cause }
        val error = assertThrows(ForegroundEventsUnavailableException::class.java) {
            events.resumedSince(0)
        }
        assertSame(cause, error.cause)
    }

    @Test
    fun successfulEmptyQuery_remainsEmptySuccess() {
        val events = UsageStatsForegroundEvents(ApplicationProvider.getApplicationContext()) { emptyList() }
        assertEquals(emptyList<ResumeEvent>(), events.resumedSince(0))
    }

    @Test
    fun missingUsageStatsService_isUnavailable() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.USAGE_STATS_SERVICE) null else super.getSystemService(name)
        }
        assertThrows(ForegroundEventsUnavailableException::class.java) {
            UsageStatsForegroundEvents(context).resumedSince(0)
        }
    }
}
