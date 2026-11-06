package com.gt8d.launcher.foreground

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.diag.DiagLog
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageStatsSnapshotTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun raw(pkg: String, elapsed: Long, cls: String? = null) =
        UsageStatsForegroundEvents.RawEvent(pkg, cls, UsageEvents.Event.ACTIVITY_RESUMED, 100_000 + elapsed)

    @Before fun before() {
        OwnResumeClock.reset()
        DiagLog.clearForTest()
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED,
        )
    }
    @After fun after() { OwnResumeClock.reset(); DiagLog.clearForTest() }

    private fun source() = UsageStatsForegroundEvents(context).apply {
        wallClock = { 120_000L }
        elapsedClock = { 20_000L }
    }

    @Test fun snapshotFreezesClocksWindowMarkAndReaderWithoutWorkerSideEffects() {
        OwnResumeClock.mark("Home", 10_000)
        val events = source()
        events.queryRawEvents = { begin, end ->
            assertEquals(107_000L, begin)
            assertEquals(121_000L, end)
            listOf(raw(context.packageName, 9200, "Home"), raw("maps", 11500))
        }
        val watcher = ForegroundWatcher(events)
        val query = watcher.prepareRead(12_000)
        OwnResumeClock.mark("Panel", 30_000)
        events.wallClock = { error("worker must not read current wall clock") }
        events.elapsedClock = { error("worker must not read current elapsed clock") }
        events.queryRawEvents = { _, _ -> error("reader must have been captured") }
        val result = query.read()
        assertEquals(listOf(ResumeEvent("maps", 12_300)), result.resumed)
        assertEquals("Panel" to 30_000L, OwnResumeClock.latest())
        assertTrue(DiagLog.dump().isEmpty())
        assertTrue(watcher.available())
        assertTrue(watcher.commitRead(result))
        assertEquals(1, DiagLog.dump().count { "skew=-800" in it })
    }

    @Test fun discardedOldSkewDoesNotChangeCorrectionOrLoggedSkew() {
        OwnResumeClock.mark("Home", 10_000)
        val events = source()
        events.queryRawEvents = { _, _ -> listOf(raw(context.packageName, 9200, "Home")) }
        val watcher = ForegroundWatcher(events)
        val old = watcher.prepareRead(12_000)
        events.queryRawEvents = { _, _ -> listOf(raw(context.packageName, 10500, "Home")) }
        assertEquals(emptySet<String>(), watcher.foregroundPkgsSince(12_000))
        val log = DiagLog.dump()
        assertFalse(watcher.commitRead(old.read()))
        assertEquals(log, DiagLog.dump())
        OwnResumeClock.reset()
        events.queryRawEvents = { _, _ -> listOf(raw("maps", 15_000)) }
        assertEquals(listOf(ResumeEvent("maps", 14_500)), events.resumedSince(14_000))
        // If discarded -800 changed loggedSkew, this repeated +500 would log again.
        OwnResumeClock.mark("Home", 10_000)
        events.queryRawEvents = { _, _ -> listOf(raw(context.packageName, 10500, "Home")) }
        watcher.foregroundPkgsSince(12_000)
        assertEquals(log, DiagLog.dump())
    }

    @Test fun snapshotRetainsPreviousSkewWhenLaterReadLearnsAnotherCorrection() {
        OwnResumeClock.mark("Home", 10_000)
        val events = source()
        events.queryRawEvents = { _, _ -> listOf(raw(context.packageName, 9200, "Home")) }
        events.resumedSince(12_000)
        OwnResumeClock.reset()
        events.queryRawEvents = { _, _ -> listOf(raw("maps", 14_500)) }
        val old = events.prepareRead(15_000)
        OwnResumeClock.mark("Home", 10_000)
        events.queryRawEvents = { _, _ -> listOf(raw(context.packageName, 10500, "Home")) }
        events.resumedSince(12_000)
        assertEquals(listOf(ResumeEvent("maps", 15_300)), old.read().resumed)
    }
}
