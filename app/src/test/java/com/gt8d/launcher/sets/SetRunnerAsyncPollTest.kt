package com.gt8d.launcher.sets

import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.curtain.CurtainController
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.CurtainView
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.foreground.*
import com.gt8d.launcher.notice.Notice
import com.gt8d.launcher.split.*
import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.*
import org.junit.Test

class SetRunnerAsyncPollTest {
    private class HeldPoll : ForegroundPoll {
        var launches = 0
        var work: (() -> ForegroundWatcher.ReadResult)? = null
        var deliver: ((ForegroundWatcher.ReadResult) -> Unit)? = null
        var result: ForegroundWatcher.ReadResult? = null
        override fun launch(read: () -> ForegroundWatcher.ReadResult, onComplete: (ForegroundWatcher.ReadResult) -> Unit) {
            check(deliver == null) { "overlapping poll" }
            launches++
            work = read
            deliver = onComplete
        }
        fun read() { result = checkNotNull(work).invoke(); work = null }
        fun complete() {
            if (work != null) read()
            val callback = checkNotNull(deliver)
            deliver = null
            callback(checkNotNull(result))
            result = null
        }
    }

    private class Events : ForegroundEvents {
        var permitted = true
        var fails = false
        var values = emptyList<ResumeEvent>()
        var commits = 0
        var reads = 0
        override fun available() = permitted
        override fun resumedSince(sinceMs: Long): List<ResumeEvent> {
            reads++
            if (fails) throw ForegroundEventsUnavailableException()
            return values.filter { it.timeMs >= sinceMs }
        }
        override fun commitRead(result: ForegroundReadResult) { commits++ }
    }

    private class Env {
        val clock = FakeScheduler()
        val events = Events()
        val watcher = ForegroundWatcher(events)
        val poll = HeldPoll()
        val warm = WarmTracker("own") { false }
        val hidden = mutableListOf<HideReason>()
        val curtain = CurtainController(clock, object : CurtainView {
            override fun show(target: CurtainTarget) {}
            override fun update(target: CurtainTarget) {}
            override fun showProgress() {}
            override fun hide(reason: HideReason) { hidden.add(reason) }
        })
        val stock = object : StockLauncherWarmupState {
            override val bootId = "boot"
            override var warmedBootId: String? = bootId
            override var retryWarmupBootId: String? = null
        }
        val selector = SplitModeSelector(object : SplitModeStore {
            var value = SplitModeState()
            override fun load() = value
            override fun save(state: SplitModeState) { value = state }
        }) { false }
        val launches = mutableListOf<Long>()
        var homes = 0
        val runner = SetRunner(SetRunnerDeps(
            config = { LauncherConfig(
                screenSets = listOf(
                    ScreenSet("one", Slot.App("a"), Slot.App("b"), SplitRatio.R5_5),
                    ScreenSet("two", Slot.App("c"), Slot.App("d"), SplitRatio.R5_5),
                ), homeApps = emptyList(), panelApps = emptyList(), dockApps = emptyList(), idleApp = "a",
            ) },
            installed = { setOf("a", "b", "c", "d") },
            lastIndex = object : LastIndexStore { override var lastSetIndex = 0 },
            selector = selector,
            vendor = object : SplitLauncher {
                override fun open(left: String, right: String, ratio: SplitRatio): LaunchResult {
                    launches.add(clock.nowMs())
                    return LaunchResult.Started
                }
            },
            appPair = null,
            apps = object : ActivityLauncher {
                override fun canResolve(pkg: String, cls: String) = true
                override fun start(spec: LaunchSpec) {}
                override fun startApp(pkg: String, adjacent: Boolean) = true
                override fun isResizeable(pkg: String) = true
            },
            watcher = watcher, warm = warm,
            stockLauncherWarmup = StockLauncherWarmup(clock, clock, watcher, stock) { true },
            curtain = curtain, scheduler = clock, clock = clock, ownPkg = "own",
            labelFor = { it }, startHome = { homes++ },
            messenger = object : Messenger { override fun show(notice: Notice) {} },
            foregroundPoll = poll,
        ))
        fun start() { runner.open(1); clock.advanceBy(200) }
        fun both() { events.values = listOf(ResumeEvent("a", 100), ResumeEvent("b", 100)) }
    }

    @Test fun pendingPollDoesNotBlockRetryOrSafetyAndDoesNotQueueMoreReads() {
        val e = Env(); e.start()
        assertEquals(1, e.events.reads) // Only the initial synchronous probe.
        e.clock.advanceBy(2800)
        assertEquals(listOf(0L, 3000L), e.launches)
        assertEquals(3, e.events.reads) // retry readiness + progress are synchronous.
        assertEquals(1, e.poll.launches)
        e.clock.advanceBy(999)
        assertTrue(e.curtain.isShowing)
        e.clock.advanceBy(1)
        assertEquals(listOf(HideReason.Safety), e.hidden)
        assertEquals(4, e.events.reads) // Safety has its own fresh progress query.
        assertEquals("boot", e.stock.retryWarmupBootId)
        assertEquals(1, e.selector.state.consecutiveFailures)
        val commits = e.events.commits
        e.both(); e.poll.complete()
        assertEquals(commits, e.events.commits)
        assertEquals(0, e.runner.readyCount)
        assertFalse(e.warm.isWarm("a"))
        assertTrue(e.watcher.available())
    }

    @Test fun retryCommitWinsOverOldFailureAndNextPollWaits200AfterCompletion() {
        val e = Env(); e.start()
        e.events.fails = true; e.poll.read()
        assertTrue(e.watcher.available())
        e.events.fails = false
        e.clock.advanceBy(2800)
        val commits = e.events.commits
        e.poll.complete()
        assertTrue(e.watcher.available())
        assertEquals(commits, e.events.commits)
        e.clock.advanceBy(199)
        assertEquals(1, e.poll.launches)
        e.clock.advanceBy(1)
        assertEquals(2, e.poll.launches)
        e.both(); e.poll.complete()
        assertEquals(1, e.runner.readyCount)
        assertEquals(3200L, e.runner.lastReadyAtMs)
    }

    @Test fun retryFailureCannotBeRepairedByOldSuccessfulPoll() {
        val e = Env(); e.start(); e.both(); e.poll.read()
        e.events.fails = true
        e.clock.advanceBy(2800)
        assertFalse(e.watcher.available())
        val commits = e.events.commits
        e.poll.complete()
        assertFalse(e.watcher.available())
        assertEquals(commits, e.events.commits)
        assertEquals(0, e.runner.readyCount)
        e.clock.advanceBy(500)
        assertNull(e.runner.opening.value)
        assertEquals(listOf(HideReason.Ready), e.hidden)
        assertEquals(1, e.poll.launches)
    }

    @Test fun cancelledOldSuccessAndFailureCannotAffectNewSetAndOnlyOneWorkerExists() {
        for (fails in listOf(false, true)) {
            val e = Env(); e.start(); e.both(); e.events.fails = fails; e.poll.read()
            e.runner.cancelOpening()
            e.events.fails = false; e.events.values = emptyList()
            e.runner.open(2)
            val opening = e.runner.opening.value
            val commits = e.events.commits
            e.clock.advanceBy(1000)
            assertEquals(1, e.poll.launches)
            e.poll.complete()
            assertEquals(commits, e.events.commits)
            assertTrue(e.watcher.available())
            assertSame(opening, e.runner.opening.value)
            assertEquals(0, e.runner.readyCount)
            assertFalse(e.warm.isWarm("a"))
            e.clock.advanceBy(199); assertEquals(1, e.poll.launches)
            e.clock.advanceBy(1); assertEquals(2, e.poll.launches)
            e.events.values = listOf(ResumeEvent("c", 1300), ResumeEvent("d", 1300))
            e.poll.complete()
            assertEquals(1, e.runner.readyCount)
            assertEquals(2, e.runner.currentIndex)
        }
    }

    @Test fun cancellationAloneDiscardsResultBeforeAnyNewerQuery() {
        for (fails in listOf(false, true)) {
            val e = Env(); e.start(); e.both(); e.events.fails = fails; e.poll.read()
            val commits = e.events.commits
            val reads = e.events.reads
            e.runner.cancelOpening()
            assertEquals(reads, e.events.reads)
            e.poll.complete()
            assertEquals(commits, e.events.commits)
            assertTrue(e.watcher.available())
            assertEquals(listOf(HideReason.Cancelled), e.hidden)
            assertNull(e.runner.opening.value)
            assertEquals(0, e.runner.readyCount)
            assertFalse(e.warm.isWarm("a"))
            assertEquals(1, e.homes)
        }
    }

    @Test fun homeAndNextSetInvalidateInflightReadButKeepDeferredOpenBehavior() {
        for (home in listOf(false, true)) {
            val e = Env(); e.start(); e.both(); e.poll.read()
            if (home) e.runner.home() else e.runner.next()
            val commits = e.events.commits
            e.poll.complete()
            assertEquals(commits, e.events.commits)
            assertEquals(0, e.runner.readyCount)
            assertEquals(1, e.runner.currentIndex)
            e.clock.advanceBy(200)
            e.poll.complete()
            assertEquals(1, e.runner.readyCount)
            assertEquals(if (home) 0 else 2, e.runner.currentIndex)
        }
    }

    @Test fun permissionRevokedBeforeDeliveryDiscardsSuccessAndNextOpeningRecovers() {
        val e = Env(); e.start(); e.both(); e.poll.read()
        e.events.permitted = false
        val commits = e.events.commits
        e.poll.complete()
        assertEquals(commits, e.events.commits)
        assertEquals(0, e.runner.readyCount)
        assertFalse(e.watcher.available())
        e.clock.advanceBy(3300)
        assertEquals(listOf(HideReason.Ready), e.hidden)
        e.events.permitted = true
        e.runner.open(2)
        assertTrue(e.watcher.available())
        e.clock.advanceBy(200)
        e.events.values = listOf(ResumeEvent("c", 3600), ResumeEvent("d", 3600))
        e.poll.complete()
        assertEquals(1, e.runner.readyCount)
    }

    @Test fun safetyUsesFreshProgressInsteadOfOldPollEvidence() {
        val e = Env(); e.start(); e.both(); e.poll.read()
        e.events.values = emptyList()
        e.clock.advanceBy(3800)
        assertEquals(listOf(HideReason.Safety), e.hidden)
        assertEquals(1, e.selector.state.consecutiveFailures)
        e.poll.complete()
        assertEquals(0, e.runner.readyCount)
    }

    @Test fun safetySeesProgressThatArrivedWhilePollWasPending() {
        val e = Env(); e.start(); e.poll.read()
        e.clock.advanceBy(2800)
        e.events.values = listOf(ResumeEvent("a", 3500))
        e.clock.advanceBy(1000)
        assertEquals(listOf(HideReason.Safety), e.hidden)
        assertEquals(0, e.selector.state.consecutiveFailures)
        assertEquals("boot", e.stock.retryWarmupBootId)
        val commits = e.events.commits
        e.poll.complete()
        assertEquals(commits, e.events.commits)
    }

    @Test fun bootTimeoutInvalidatesInflightRead() {
        val e = Env()
        e.runner.openForBoot(1); e.clock.advanceBy(200); e.both(); e.poll.read()
        e.clock.advanceBy(9800)
        assertNull(e.runner.opening.value)
        val commits = e.events.commits
        e.poll.complete()
        assertEquals(commits, e.events.commits)
        assertEquals(0, e.runner.readyCount)
        assertTrue(e.watcher.available())
    }

    @Test fun repeatedCancellationKeepsOnlyLatestOpeningWaitingForWorker() {
        val e = Env(); e.start(); e.poll.read()
        repeat(10) {
            e.runner.cancelOpening()
            e.runner.open(if (it % 2 == 0) 2 else 1)
            e.clock.advanceBy(200)
            assertEquals(1, e.poll.launches)
        }
        val commits = e.events.commits
        e.poll.complete()
        assertEquals(commits, e.events.commits)
        e.clock.advanceBy(199)
        assertEquals(1, e.poll.launches)
        e.clock.advanceBy(1)
        assertEquals(2, e.poll.launches)
        e.events.values = listOf(ResumeEvent("a", 2300), ResumeEvent("b", 2300))
        e.poll.complete()
        assertEquals(1, e.runner.readyCount)
        assertEquals(1, e.runner.currentIndex)
    }
}
