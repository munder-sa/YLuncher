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
        val events = mutableListOf<ResumeEvent>()
        val watcher = ForegroundWatcher(object : ForegroundEvents {
            override fun available() = true
            override fun resumedSince(sinceMs: Long) = events.filter {
                it.timeMs >= sinceMs && it.timeMs <= scheduler.nowMs()
            }
        })
        val warm = WarmTracker("own") { true }
        val selector = SplitModeSelector(object : SplitModeStore {
            override fun load() = SplitModeState()
            override fun save(state: SplitModeState) {}
        }) { false }
        val lastIndex = object : LastIndexStore { override var lastSetIndex = 1 }
        val homes = mutableListOf<Long>()
        val records = mutableListOf<String>()
        val splitCalls = mutableListOf<Pair<String, String>>()
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
                    return LaunchResult.Started
                }
            }, appPair = null,
            apps = object : ActivityLauncher {
                override fun canResolve(pkg: String, cls: String) = true
                override fun start(spec: LaunchSpec) {}
                override fun startApp(pkg: String, adjacent: Boolean): Boolean = throw AssertionError("direct launch")
                override fun isResizeable(pkg: String) = true
            }, watcher = watcher, warm = warm, curtain = curtain, scheduler = scheduler, clock = scheduler,
            ownPkg = "own", labelFor = { it }, startHome = { homes.add(scheduler.nowMs()) },
            messenger = object : Messenger {
                override fun showSplitUnavailable() = throw AssertionError("message")
                override fun showSwitchFailed() = throw AssertionError("message")
            },
        ))
        var readyAtOpen = 0
        val boot = BootSequence(scheduler, { false }, {
            readyAtOpen = runner.readyCount
            runner.openForBoot(lastIndex.lastSetIndex)
        }, { runner.readyCount > readyAtOpen }, { runner.abortBootToHome() }, { records.add(it) })

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
