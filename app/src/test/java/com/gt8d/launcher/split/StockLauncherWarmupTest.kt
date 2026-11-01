package com.gt8d.launcher.split

import com.gt8d.launcher.foreground.*
import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.provider.Settings
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StockLauncherWarmupTest {
    private class State : StockLauncherWarmupState {
        override var bootId = "count:1"
        override var warmedBootId: String? = null
        override var retryWarmupBootId: String? = null
    }
    private class Env {
        val scheduler = FakeScheduler()
        val state = State()
        var available = true
        var queryFails = false
        var queries = 0
        var succeeds = true
        val starts = mutableListOf<Long>()
        val finishes = mutableListOf<Long>()
        val events = mutableListOf<ResumeEvent>()
        val watcher = ForegroundWatcher(object : ForegroundEvents {
            override fun available() = available
            override fun resumedSince(sinceMs: Long): List<ResumeEvent> {
                queries++
                if (queryFails) throw ForegroundEventsUnavailableException()
                return events.filter { it.timeMs <= scheduler.nowMs() }
            }
        })
        fun create() = StockLauncherWarmup(scheduler, scheduler, watcher, state, {
            starts.add(scheduler.nowMs()); succeeds
        })
        val warmup = create()
        fun start(delay: Long = 200L) = warmup.start(delay) { finishes.add(scheduler.nowMs()) }
        fun resume(at: Long) { events.add(ResumeEvent("com.android.launcher3", at)) }
    }

    @Test fun bootId_usesBootCount() {
        assertEquals("count:7", AndroidStockLauncherWarmupState.identifyBoot({ 7 }, { error("wall") }, { error("elapsed") }))
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 9)
        assertEquals("count:9", AndroidStockLauncherWarmupState(app).bootId)
    }
    @Test fun bootId_unavailableFallsBackToRoundedBootTime() {
        for (count in listOf<() -> Int>({ -1 }, { -5 }, { throw IllegalStateException() })) {
            assertEquals("time:1235", AndroidStockLauncherWarmupState.identifyBoot(count, { 1234999 }, { 300 }))
        }
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, Settings.Global.BOOT_COUNT, null)
        assertTrue(AndroidStockLauncherWarmupState(app).bootId.startsWith("time:"))
    }
    @Test fun state_survivesRecreationWithinBoot() {
        val app = RuntimeEnvironment.getApplication()
        val state = AndroidStockLauncherWarmupState(app)
        state.recordWarmed()
        val restored = AndroidStockLauncherWarmupState(app)
        assertEquals(state.bootId, restored.warmedBootId)
        assertNull(restored.retryWarmupBootId)
    }
    @Test fun state_differentBootRequiresWarmup() {
        val e = Env(); e.state.warmedBootId = "count:0"
        assertTrue(e.warmup.needsWarmup())
        e.state.warmedBootId = "time:1"
        assertTrue(e.warmup.needsWarmup())
    }
    @Test fun state_previousBootRetryDoesNotForceWarmup() {
        val e = Env(); e.state.warmedBootId = e.state.bootId; e.state.retryWarmupBootId = "count:0"
        assertFalse(e.warmup.needsWarmup())
    }
    @Test fun state_commitFailureKeepsProcessState() {
        val app = RuntimeEnvironment.getApplication()
        val backing = app.getSharedPreferences("commit_failure_test", Context.MODE_PRIVATE)
        var commits = 0
        val preferences = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun commit(): Boolean { commits++; return false }
                }
            }
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        val state = AndroidStockLauncherWarmupState(context)
        state.retryWarmupBootId = state.bootId
        assertEquals(state.bootId, state.retryWarmupBootId)
        state.recordWarmed()
        assertEquals(2, commits)
        assertEquals(state.bootId, state.warmedBootId)
        assertNull(state.retryWarmupBootId)
        assertNull(backing.getString("warmed_boot_id", null))
    }
    @Test fun needsWarmup_falseOnlyWhenWarmedAndNoRetry() {
        val e = Env(); assertTrue(e.warmup.needsWarmup())
        e.state.warmedBootId = e.state.bootId; assertFalse(e.warmup.needsWarmup())
        e.warmup.requestRewarm(); assertTrue(e.warmup.needsWarmup())
    }
    @Test fun startsOnceAfterCoverDelay() {
        val e = Env(); e.start(); e.scheduler.advanceBy(199); assertTrue(e.starts.isEmpty())
        e.scheduler.advanceBy(1); assertEquals(listOf(200L), e.starts)
        e.scheduler.advanceBy(2000); assertEquals(listOf(200L), e.starts)
    }
    @Test fun boot_startsWithoutCoverDelay() {
        val e = Env(); e.start(0); assertTrue(e.starts.isEmpty()); assertTrue(e.finishes.isEmpty())
        e.scheduler.advanceBy(0); assertEquals(listOf(0L), e.starts)
    }
    @Test fun freshResume_waits300ms() {
        val e = Env(); e.start(); e.resume(201); e.scheduler.advanceBy(599)
        assertTrue(e.finishes.isEmpty()); e.scheduler.advanceBy(1)
        assertEquals(listOf(600L), e.finishes); assertFalse(e.warmup.needsWarmup())
    }
    @Test fun oldResume_doesNotCompleteWarmup() {
        val e = Env(); e.resume(199); e.start(); e.scheduler.advanceBy(1499)
        assertTrue(e.finishes.isEmpty()); e.scheduler.advanceBy(1); assertEquals(listOf(1500L), e.finishes)
    }
    @Test fun lateResume_doesNotExtend1500msLimit() {
        val e = Env(); e.start(); e.resume(1399); e.scheduler.advanceBy(1500)
        assertEquals(listOf(1500L), e.finishes); e.scheduler.advanceBy(1000); assertEquals(1, e.finishes.size)
    }
    @Test fun noResume_finishesAt1500ms() {
        val e = Env(); e.start(); e.scheduler.advanceBy(1499); assertTrue(e.finishes.isEmpty())
        e.scheduler.advanceBy(1); assertEquals(listOf(1500L), e.finishes)
    }
    @Test fun successfulStartWithoutResume_recordsWarmed() {
        val e = Env(); e.start(); e.scheduler.advanceBy(1500)
        assertEquals(e.state.bootId, e.state.warmedBootId); assertFalse(e.warmup.needsWarmup())
    }
    @Test fun failedStart_keepsWarmupRequired() {
        val e = Env(); e.succeeds = false; e.start(); e.scheduler.advanceBy(1500)
        assertTrue(e.warmup.needsWarmup()); assertEquals(listOf(1500L), e.finishes); assertEquals(0, e.queries)
    }
    @Test fun unavailableOrQueryFailure_finishesAtLimit() {
        for (failure in listOf(false, true)) {
            val e = Env(); e.available = failure; e.queryFails = failure; e.start()
            e.scheduler.advanceBy(1499); val queries = e.queries
            assertEquals(if (failure) 1 else 0, queries)
            assertTrue(e.finishes.isEmpty()); e.scheduler.advanceBy(1001)
            assertEquals(listOf(1500L), e.finishes); assertEquals(queries, e.queries)
        }
    }
    @Test fun cancel_doesNotCompleteOrRecordWarmed() {
        for (at in listOf(199L, 250L, 400L)) {
            val e = Env(); e.resume(201); val cancel = e.start(); e.scheduler.advanceBy(at)
            cancel.cancel(); cancel.cancel(); e.scheduler.advanceBy(3000)
            assertTrue(e.finishes.isEmpty()); assertNull(e.state.warmedBootId)
        }
    }
    @Test fun restart_invalidatesPreviousCompletion() {
        val e = Env(); e.start(); e.scheduler.advanceBy(400); e.start()
        e.scheduler.advanceBy(1100); assertTrue(e.finishes.isEmpty())
        e.scheduler.advanceBy(400); assertEquals(listOf(1900L), e.finishes)
    }
    @Test fun deadlineAndPoll_finishOnlyOnce() {
        val e = Env(); e.start(0); e.resume(1200); e.scheduler.advanceBy(3000)
        assertEquals(listOf(1500L), e.finishes)
    }
    @Test fun requestRewarm_survivesRecreationAndClearsOnWarmup() {
        val e = Env(); e.state.warmedBootId = e.state.bootId; e.warmup.requestRewarm()
        val recreated = e.create(); assertTrue(recreated.needsWarmup())
        recreated.start(0) {}; e.scheduler.advanceBy(1500)
        assertNull(e.state.retryWarmupBootId); assertFalse(recreated.needsWarmup())
        val app = RuntimeEnvironment.getApplication(); val state = AndroidStockLauncherWarmupState(app)
        state.retryWarmupBootId = state.bootId
        assertEquals(state.bootId, AndroidStockLauncherWarmupState(app).retryWarmupBootId)
    }
    @Test fun failedOrCancelledWarmup_keepsRewarmRequest() {
        for (cancel in listOf(false, true)) {
            val e = Env(); e.state.warmedBootId = e.state.bootId; e.warmup.requestRewarm()
            e.succeeds = cancel; val handle = e.start()
            if (cancel) { e.scheduler.advanceBy(250); handle.cancel() }
            e.scheduler.advanceBy(2000)
            assertEquals(e.state.bootId, e.state.retryWarmupBootId); assertTrue(e.warmup.needsWarmup())
        }
    }
}
