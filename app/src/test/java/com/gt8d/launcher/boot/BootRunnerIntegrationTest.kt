package com.gt8d.launcher.boot

import com.gt8d.launcher.config.*
import com.gt8d.launcher.curtain.*
import com.gt8d.launcher.foreground.*
import com.gt8d.launcher.sets.*
import com.gt8d.launcher.split.*
import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.*
import org.junit.Test

class BootRunnerIntegrationTest {
    @Test fun warmupAndSplit_completeBeforeOriginalBootDeadline() {
        val e = Env(); e.stockState.warmedBootId = null
        e.boot.start(); e.scheduler.advanceBy(3000)
        assertTrue(e.splitCalls.isEmpty()); assertTrue(e.curtainCalls.isEmpty())
        assertEquals(listOf(3000L), e.stockStarts)
        e.scheduler.advanceBy(1499); assertTrue(e.splitCalls.isEmpty())
        e.scheduler.advanceBy(1); assertEquals(listOf(4500L), e.splitTimes)
        e.events.add(ResumeEvent("left", 4600)); e.scheduler.advanceBy(3500)
        assertEquals(1, e.splitCalls.size)
        e.events.add(ResumeEvent("right", 8001)); e.scheduler.advanceBy(300)
        assertEquals(BootPhase.Done, e.boot.phase.value); assertEquals(1, e.runner.readyCount)
        assertTrue(e.homes.isEmpty()); assertTrue(e.curtainCalls.isEmpty())
        e.scheduler.advanceBy(10000); assertTrue(e.homes.isEmpty())
    }

    @Test fun abortDuringWarmup_cancelsLateVendorRequest() {
        val e = Env(); e.stockState.warmedBootId = null
        e.boot.start(); e.scheduler.advanceBy(3200); e.runner.abortBootToHome()
        e.scheduler.advanceBy(2000)
        assertEquals(listOf(3200L), e.homes); assertTrue(e.splitCalls.isEmpty())
        assertNull(e.stockState.warmedBootId); assertNull(e.stockState.retryWarmupBootId)
        assertNull(e.runner.opening.value); assertEquals(0, e.runner.readyCount)
        assertTrue(e.curtainCalls.isEmpty())
    }

    @Test fun timeout_keepsOriginal10000DeadlineWithoutCurtain() {
        val e = Env(); e.stockState.warmedBootId = null
        e.boot.start(); e.scheduler.advanceBy(4500)
        e.runner.openForBoot(2); e.runner.open(3)
        e.scheduler.advanceBy(5499); assertTrue(e.homes.isEmpty())
        e.scheduler.advanceBy(1); e.assertHome(BootPhase.Done)
        assertEquals(listOf("boot_timeout"), e.records)
        val calls = e.splitCalls.size
        e.events.add(ResumeEvent("left", 10001)); e.events.add(ResumeEvent("right", 10001))
        e.scheduler.advanceBy(20000)
        e.assertHome(BootPhase.Done); assertEquals(calls, e.splitCalls.size)
    }

    @Test fun noProgressBootTimeout_requestsRewarmForNextVendor() {
        val e = Env(); e.stockState.warmedBootId = null
        e.boot.start(); e.scheduler.advanceBy(10000)
        assertEquals(e.stockState.bootId, e.stockState.retryWarmupBootId)
        e.assertHome(BootPhase.Done)
        val count = e.splitCalls.size; e.runner.openForBoot(1)
        assertEquals(count, e.splitCalls.size); e.scheduler.advanceBy(1500)
        assertEquals(count + 1, e.splitCalls.size)
        assertEquals(listOf(3000L, 10000L), e.stockStarts)
        assertNull(e.stockState.retryWarmupBootId)
        // Also cover SetRunner's own wait limit without BootSequence.
        val internal = Env(); internal.runner.openForBoot(1)
        internal.scheduler.advanceBy(10000)
        assertEquals(internal.stockState.bootId, internal.stockState.retryWarmupBootId)
        assertEquals(0, internal.selector.state.consecutiveFailures)
        assertTrue(internal.curtainCalls.isEmpty())
    }

    @Test fun progressOrUnavailableBootTimeout_requestsRewarmOnlyWithMonitoring() {
        for (kind in listOf("progress", "unavailable", "queryFailure", "cancel")) {
            val e = Env(); e.stockState.warmedBootId = null
            e.boot.start(); e.scheduler.advanceBy(4500)
            when (kind) {
                "progress" -> e.events.add(ResumeEvent("left", 4600))
                "unavailable" -> e.available = false
                "queryFailure" -> e.queryFails = true
                else -> e.boot.stayHome()
            }
            e.scheduler.advanceBy(5500)
            if (kind == "progress") assertEquals(e.stockState.bootId, e.stockState.retryWarmupBootId)
            else assertNull(e.stockState.retryWarmupBootId)
            e.assertHome(if (kind == "cancel") BootPhase.StayedHome else BootPhase.Done)
        }
    }

    @Test fun bootRetry_remains3000msAfterSplitRequest() {
        val e = Env(); e.stockState.warmedBootId = null
        e.boot.start(); e.scheduler.advanceBy(7499)
        assertEquals(listOf(4500L), e.splitTimes)
        e.scheduler.advanceBy(1); assertEquals(listOf(4500L, 7500L), e.splitTimes)
        assertEquals(listOf(3000L), e.stockStarts); assertTrue(e.curtainCalls.isEmpty())
    }

    @Test
    fun timeout_startsHomeAt10000_andIgnoresLateSuccess() {
        val e = Env()
        e.startOpening()
        e.scheduler.advanceBy(100)
        e.events.add(ResumeEvent("left", e.scheduler.nowMs()))
        e.scheduler.advanceBy(6899)
        assertEquals(emptyList<Long>(), e.homes)
        assertEquals(BootPhase.Opening, e.boot.phase.value)
        e.scheduler.advanceBy(1)
        e.assertHome(BootPhase.Done)
        assertEquals(listOf("boot_timeout"), e.records)
        val calls = e.splitCalls.size
        val state = e.selector.state
        e.scheduler.advanceBy(200)
        e.events.add(ResumeEvent("right", e.scheduler.nowMs()))
        e.runner.panelResumedInSplit()
        e.scheduler.advanceBy(19800)
        e.assertHome(BootPhase.Done)
        assertEquals(calls, e.splitCalls.size)
        assertEquals(state, e.selector.state)
    }

    @Test
    fun timeout_discardsPendingNormalAndBootRequests() {
        val e = Env()
        e.startOpening()
        e.runner.openForBoot(2)
        e.runner.open(3)
        e.scheduler.advanceBy(7000)
        e.assertHome(BootPhase.Done)
        val calls = e.splitCalls.size
        e.scheduler.advanceBy(20000)
        e.assertHome(BootPhase.Done)
        assertEquals(calls, e.splitCalls.size)
        assertTrue(e.splitCalls.all { it == "left" to "right" })
    }

    @Test
    fun cancelDuringOpening_startsHomeAtOriginalDeadline() {
        val e = Env()
        e.startOpening()
        e.boot.stayHome()
        e.scheduler.advanceBy(6999)
        assertTrue(e.homes.isEmpty())
        e.scheduler.advanceBy(1)
        e.assertHome(BootPhase.StayedHome)
        assertEquals(listOf("boot_cancel"), e.records)
        e.scheduler.advanceBy(20000)
        e.assertHome(BootPhase.StayedHome)
    }

    private class Env {
        val scheduler = FakeScheduler()
        var available = true
        var queryFails = false
        val events = mutableListOf<ResumeEvent>()
        val watcher = ForegroundWatcher(object : ForegroundEvents {
            override fun available() = available
            override fun resumedSince(sinceMs: Long): List<ResumeEvent> {
                if (queryFails) throw ForegroundEventsUnavailableException()
                return events.filter { it.timeMs >= sinceMs && it.timeMs <= scheduler.nowMs() }
            }
        })
        val warm = WarmTracker("own") { true }
        val stockState = object : StockLauncherWarmupState {
            override val bootId = "count:1"
            override var warmedBootId: String? = bootId
            override var retryWarmupBootId: String? = null
        }
        val stockStarts = mutableListOf<Long>()
        val stockWarmup = StockLauncherWarmup(scheduler, scheduler, watcher, stockState, {
            stockStarts.add(scheduler.nowMs()); true
        })
        val selector = SplitModeSelector(object : SplitModeStore {
            override fun load() = SplitModeState()
            override fun save(state: SplitModeState) {}
        }) { false }
        val lastIndex = object : LastIndexStore { override var lastSetIndex = 1 }
        val homes = mutableListOf<Long>()
        val records = mutableListOf<String>()
        val splitCalls = mutableListOf<Pair<String, String>>()
        val splitTimes = mutableListOf<Long>()
        val curtainCalls = mutableListOf<String>()
        val curtain = CurtainController(scheduler, object : CurtainView {
            override fun show(target: CurtainTarget) { curtainCalls.add("show") }
            override fun update(target: CurtainTarget) { curtainCalls.add("update") }
            override fun showProgress() { curtainCalls.add("progress") }
            override fun hide(reason: HideReason) { curtainCalls.add("hide") }
        })
        val cfg = LauncherConfig(
            screenSets = listOf("left" to "right", "second" to "right", "third" to "right").mapIndexed { i, p ->
                ScreenSet("s$i", Slot.App(p.first), Slot.App(p.second), SplitRatio.R6_4)
            },
            homeApps = emptyList(), panelApps = emptyList(), dockApps = emptyList(), idleApp = "right",
        )
        val runner = SetRunner(SetRunnerDeps(
            config = { cfg }, installed = { setOf("left", "right", "second", "third", "own") },
            lastIndex = lastIndex, selector = selector,
            vendor = object : SplitLauncher {
                override fun open(left: String, right: String, ratio: SplitRatio): LaunchResult {
                    splitCalls.add(left to right)
                    splitTimes.add(scheduler.nowMs())
                    return LaunchResult.Started
                }
            }, appPair = null,
            apps = object : ActivityLauncher {
                override fun canResolve(pkg: String, cls: String) = true
                override fun start(spec: LaunchSpec) {}
                override fun startApp(pkg: String, adjacent: Boolean): Boolean = throw AssertionError("direct launch")
                override fun isResizeable(pkg: String) = true
            }, watcher = watcher, warm = warm, stockLauncherWarmup = stockWarmup,
            curtain = curtain, scheduler = scheduler, clock = scheduler,
            ownPkg = "own", labelFor = { it }, startHome = { homes.add(scheduler.nowMs()) },
            messenger = object : Messenger {
                override fun show(notice: com.gt8d.launcher.notice.Notice) = throw AssertionError("message")
            },
        ))
        var readyAtOpen = 0
        var deadlineReached = false
        val boot = BootSequence(scheduler, { false }, {
            readyAtOpen = runner.readyCount
            runner.openForBoot(lastIndex.lastSetIndex)
        }, { runner.readyCount > readyAtOpen }, { runner.abortBootToHome(timedOut = deadlineReached) }, {
            if (it == "boot_timeout") deadlineReached = true
            records.add(it)
        })

        fun startOpening() {
            boot.start()
            scheduler.advanceBy(3000)
            assertEquals(BootPhase.Opening, boot.phase.value)
            assertEquals(listOf("left" to "right"), splitCalls)
        }

        fun assertHome(phase: BootPhase) {
            assertEquals(listOf(10000L), homes)
            assertNull(runner.opening.value)
            assertEquals(0, runner.currentIndex)
            assertEquals(0, lastIndex.lastSetIndex)
            assertNull(runner.lastOpenedPair)
            assertNull(runner.lastOpenedAtMs)
            assertEquals(phase, boot.phase.value)
            assertEquals(0, runner.readyCount)
            assertFalse(warm.isWarm("left"))
            assertFalse(warm.isWarm("right"))
            assertEquals(0, selector.state.consecutiveFailures)
            assertTrue(curtainCalls.isEmpty())
        }
    }
}
