package com.gt8d.launcher.sets

import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.curtain.CurtainController
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.CurtainView
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.foreground.ForegroundEvents
import com.gt8d.launcher.foreground.ForegroundWatcher
import com.gt8d.launcher.foreground.ResumeEvent
import com.gt8d.launcher.foreground.WarmTracker
import com.gt8d.launcher.split.ActivityLauncher
import com.gt8d.launcher.split.FailReason
import com.gt8d.launcher.split.LaunchResult
import com.gt8d.launcher.split.LaunchSpec
import com.gt8d.launcher.split.SplitLauncher
import com.gt8d.launcher.split.SplitMode
import com.gt8d.launcher.split.SplitModeSelector
import com.gt8d.launcher.split.SplitModeState
import com.gt8d.launcher.split.SplitModeStore
import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetRunnerTest {

    private data class SplitCall(
        val left: String,
        val right: String,
        val ratio: SplitRatio,
    )

    private data class StartAppCall(
        val pkg: String,
        val adjacent: Boolean,
    )

    private class FakeSplitLauncher(
        var result: LaunchResult = LaunchResult.Started,
    ) : SplitLauncher {
        val calls = mutableListOf<SplitCall>()
        override fun open(left: String, right: String, ratio: SplitRatio): LaunchResult {
            calls.add(SplitCall(left, right, ratio))
            return result
        }
    }

    private class FakeActivityLauncher : ActivityLauncher {
        val startAppCalls = mutableListOf<StartAppCall>()
        val unresizeablePkgs = mutableSetOf<String>()

        override fun canResolve(pkg: String, cls: String): Boolean = true
        override fun start(spec: LaunchSpec) {}
        override fun startApp(pkg: String, adjacent: Boolean): Boolean {
            startAppCalls.add(StartAppCall(pkg, adjacent))
            return true
        }
        override fun isResizeable(pkg: String): Boolean = pkg !in unresizeablePkgs
    }

    private class FakeCurtainView : CurtainView {
        val shownTargets = mutableListOf<CurtainTarget>()
        val updatedTargets = mutableListOf<CurtainTarget>()
        var showProgressCount = 0
        val hiddenReasons = mutableListOf<HideReason>()

        override fun show(target: CurtainTarget) {
            shownTargets.add(target)
        }
        override fun update(target: CurtainTarget) {
            updatedTargets.add(target)
        }
        override fun showProgress() {
            showProgressCount++
        }
        override fun hide(reason: HideReason) {
            hiddenReasons.add(reason)
        }
    }

    private class FakeForegroundEvents(
        var isAvailable: Boolean = true,
    ) : ForegroundEvents {
        val events = mutableListOf<ResumeEvent>()
        override fun available(): Boolean = isAvailable
        override fun resumedSince(sinceMs: Long): List<ResumeEvent> = events.toList()
    }

    private class MemorySplitModeStore(
        var state: SplitModeState = SplitModeState(),
    ) : SplitModeStore {
        override fun load(): SplitModeState = state
        override fun save(state: SplitModeState) {
            this.state = state
        }
    }

    private class MemoryLastIndexStore(
        override var lastSetIndex: Int = 0,
    ) : LastIndexStore

    private class FakeMessenger : Messenger {
        var splitUnavailableCount = 0
        var switchFailedCount = 0
        override fun showSplitUnavailable() {
            splitUnavailableCount++
        }
        override fun showSwitchFailed() {
            switchFailedCount++
        }
    }

    private class TestEnv(
        initialLastSetIndex: Int = 0,
        watcherAvailable: Boolean = true,
        ownTaskExists: () -> Boolean = { true },
    ) {
        val ownPkg = "com.gt8d.launcher"
        var config = LauncherConfig(
            screenSets = listOf(
                ScreenSet("set1", Slot.App("youtube"), Slot.Panel, SplitRatio.R6_4),
                ScreenSet("set2", Slot.App("youtube"), Slot.App("applemusic"), SplitRatio.R6_4),
                ScreenSet("set3", Slot.App("youtube"), Slot.App("maps"), SplitRatio.R5_5),
            ),
            homeApps = emptyList(),
            panelApps = emptyList(),
            dockApps = emptyList(),
            idleApp = "applemusic",
        )
        var installed = mutableSetOf("youtube", "applemusic", "maps", ownPkg)
        val lastIndex = MemoryLastIndexStore(initialLastSetIndex)
        val store = MemorySplitModeStore()
        var dReady = false
        val selector = SplitModeSelector(store) { dReady }
        val vendor = FakeSplitLauncher()
        val appPair = FakeSplitLauncher()
        val apps = FakeActivityLauncher()
        val events = FakeForegroundEvents(watcherAvailable)
        val watcher = ForegroundWatcher(events)
        val warm = WarmTracker(ownPkg, ownTaskExists)
        val scheduler = FakeScheduler()
        val curtainView = FakeCurtainView()
        val curtain = CurtainController(scheduler, curtainView)
        var startHomeCount = 0
        val messenger = FakeMessenger()

        val runner = SetRunner(
            SetRunnerDeps(
                config = { config },
                installed = { installed },
                lastIndex = lastIndex,
                selector = selector,
                vendor = vendor,
                appPair = appPair,
                apps = apps,
                watcher = watcher,
                warm = warm,
                curtain = curtain,
                scheduler = scheduler,
                clock = scheduler,
                ownPkg = ownPkg,
                labelFor = { it },
                startHome = { startHomeCount++ },
                messenger = messenger,
            ),
        )
    }

    @Test
    fun next_showsCurtainAndCallsVendor_withVendorRatio() {
        val env = TestEnv(initialLastSetIndex = 1)
        assertEquals(1, env.runner.currentIndex)

        env.runner.next()

        assertEquals(
            listOf(SplitCall("youtube", "applemusic", SplitRatio.R6_4)),
            env.vendor.calls,
        )
        assertEquals(2, env.lastIndex.lastSetIndex)
        assertEquals(2, env.runner.currentIndex)
        assertTrue(env.curtain.isShowing)
        assertEquals(1, env.curtainView.shownTargets.size)
    }

    @Test
    fun panelSet_passesOwnPkgAsRight() {
        val env = TestEnv()

        env.runner.open(1)

        assertEquals(
            listOf(SplitCall("youtube", "com.gt8d.launcher", SplitRatio.R6_4)),
            env.vendor.calls,
        )
    }

    @Test
    fun rapidSwipes_onlyLastTargetLaunched_noOverlap() {
        val env = TestEnv()

        env.runner.open(1)
        assertEquals(1, env.vendor.calls.size)

        env.runner.next() // -> 2
        env.runner.next() // -> 3
        env.runner.prev() // -> 2

        assertEquals(1, env.vendor.calls.size)
        assertEquals(3, env.curtainView.updatedTargets.size)
        assertEquals(2, env.curtainView.updatedTargets[0].position)
        assertEquals(3, env.curtainView.updatedTargets[1].position)
        assertEquals(2, env.curtainView.updatedTargets[2].position)

        env.events.events.add(ResumeEvent("youtube", 100L))
        env.runner.panelResumedInSplit()
        env.scheduler.advanceBy(200L)

        assertEquals(
            listOf(
                SplitCall("youtube", "com.gt8d.launcher", SplitRatio.R6_4),
                SplitCall("youtube", "applemusic", SplitRatio.R6_4),
            ),
            env.vendor.calls,
        )
    }

    @Test
    fun entryMissing_opensLeftOnlyAndShowsMessage() {
        val env = TestEnv()
        env.vendor.result = LaunchResult.Failed(FailReason.EntryMissing)

        env.runner.open(2)

        assertEquals(listOf(StartAppCall("youtube", false)), env.apps.startAppCalls)
        assertEquals(1, env.messenger.splitUnavailableCount)
        assertEquals(SplitMode.LeftOnly, env.selector.current())
        assertNull(env.runner.opening.value)
        assertFalse(env.curtain.isShowing)
    }

    @Test
    fun leftUninstalled_opensRightAlone() {
        val env = TestEnv()
        env.installed.remove("youtube")

        env.runner.open(2)

        assertEquals(0, env.vendor.calls.size)
        assertEquals(listOf(StartAppCall("applemusic", false)), env.apps.startAppCalls)
    }

    @Test
    fun rightUninstalled_opensLeftAlone() {
        val env = TestEnv()
        env.installed.remove("applemusic")

        env.runner.open(2)

        assertEquals(0, env.vendor.calls.size)
        assertEquals(listOf(StartAppCall("youtube", false)), env.apps.startAppCalls)
    }

    @Test
    fun unresizeableApp_opensLeftOnly() {
        val env = TestEnv()
        env.apps.unresizeablePkgs.add("applemusic")

        env.runner.open(2)

        assertEquals(0, env.vendor.calls.size)
        assertEquals(listOf(StartAppCall("youtube", false)), env.apps.startAppCalls)
        assertEquals(1, env.messenger.splitUnavailableCount)
    }

    @Test
    fun bothResumed_hidesCurtain_andRecordsSuccess() {
        val env = TestEnv()
        env.selector.onFailure(FailReason.Timeout, countable = true)
        assertEquals(1, env.selector.state.consecutiveFailures)

        env.runner.open(2)
        env.scheduler.advanceBy(300L)
        assertTrue(env.curtain.isShowing)

        env.events.events.add(ResumeEvent("youtube", 250L))
        env.events.events.add(ResumeEvent("applemusic", 280L))
        env.scheduler.advanceBy(200L)

        assertFalse(env.curtain.isShowing)
        assertEquals(listOf(HideReason.Ready), env.curtainView.hiddenReasons)
        assertNull(env.runner.opening.value)
        assertEquals(0, env.selector.state.consecutiveFailures)
    }

    @Test
    fun notReady_retriesOnceAt3000_noCurtainExtension() {
        val env = TestEnv()

        env.runner.open(2)
        assertEquals(1, env.vendor.calls.size)

        env.scheduler.advanceBy(2999L)
        assertEquals(1, env.vendor.calls.size)
        assertTrue(env.curtain.isShowing)

        env.scheduler.advanceBy(1L) // 3000ms
        assertEquals(2, env.vendor.calls.size)
        assertTrue(env.curtain.isShowing)

        env.scheduler.advanceBy(999L) // 3999ms
        assertTrue(env.curtain.isShowing)

        env.scheduler.advanceBy(1L) // 4000ms
        assertFalse(env.curtain.isShowing)
        assertEquals(listOf(HideReason.Safety), env.curtainView.hiddenReasons)

        env.scheduler.advanceBy(5000L)
        assertEquals(2, env.vendor.calls.size)
    }

    @Test
    fun safety_recordsCountableFailureOnlyWhenWatcherAvailable() {
        val envAvailable = TestEnv(watcherAvailable = true)
        envAvailable.runner.open(2)
        envAvailable.scheduler.advanceBy(4000L)
        assertEquals(listOf(HideReason.Safety), envAvailable.curtainView.hiddenReasons)
        assertEquals(1, envAvailable.selector.state.consecutiveFailures)
        assertEquals(1, envAvailable.messenger.switchFailedCount)

        val envUnavailable = TestEnv(watcherAvailable = false)
        envUnavailable.runner.open(2)
        envUnavailable.scheduler.advanceBy(3500L)
        assertFalse(envUnavailable.curtain.isShowing)
        assertEquals(listOf(HideReason.Timed), envUnavailable.curtainView.hiddenReasons)
        assertEquals(0, envUnavailable.selector.state.consecutiveFailures)
        assertEquals(0, envUnavailable.messenger.switchFailedCount)
    }

    @Test
    fun watcherUnavailable_timedHide_clearsOpening_andRunsPendingTarget() {
        val env = TestEnv(watcherAvailable = false)
        env.warm.markForeground("youtube")
        env.warm.markForeground("applemusic")
        env.warm.markForeground("maps")

        env.runner.open(2)
        assertEquals(1, env.vendor.calls.size)
        assertNotNull(env.runner.opening.value)

        env.runner.next() // -> pending = 3
        assertEquals(1, env.vendor.calls.size)

        env.scheduler.advanceBy(800L)
        assertEquals(
            listOf(
                SplitCall("youtube", "applemusic", SplitRatio.R6_4),
                SplitCall("youtube", "maps", SplitRatio.R5_5),
            ),
            env.vendor.calls,
        )
        assertEquals(0, env.selector.state.consecutiveFailures)

        env.scheduler.advanceBy(800L)
        assertNull(env.runner.opening.value)
        assertEquals(0, env.selector.state.consecutiveFailures)
    }

    @Test
    fun openingFlag_setDuringOpen_clearedAfter() {
        val env = TestEnv()
        assertNull(env.runner.opening.value)

        env.runner.open(2)
        assertEquals(Opening("youtube", "applemusic", 0L), env.runner.opening.value)

        env.events.events.add(ResumeEvent("youtube", 100L))
        env.events.events.add(ResumeEvent("applemusic", 120L))
        env.scheduler.advanceBy(200L)

        assertNull(env.runner.opening.value)
    }

    @Test
    fun home_startsHome_andHidesCurtain() {
        val env = TestEnv(initialLastSetIndex = 2)

        env.runner.home()

        assertEquals(1, env.startHomeCount)
        assertEquals(listOf(HideReason.Ready), env.curtainView.hiddenReasons)
        assertFalse(env.curtain.isShowing)
        assertEquals(0, env.lastIndex.lastSetIndex)
        assertEquals(0, env.runner.currentIndex)
        assertNull(env.runner.lastOpenedPair)
    }

    @Test
    fun openForBoot_doesNotShowCurtain() {
        val env = TestEnv()

        env.runner.openForBoot(2)

        assertEquals(0, env.curtainView.shownTargets.size)
        assertFalse(env.curtain.isShowing)
        assertEquals(
            listOf(SplitCall("youtube", "applemusic", SplitRatio.R6_4)),
            env.vendor.calls,
        )
        assertNotNull(env.runner.opening.value)

        env.scheduler.advanceBy(9999L)
        assertNotNull(env.runner.opening.value)

        env.scheduler.advanceBy(1L) // 10000ms
        assertNull(env.runner.opening.value)
        assertEquals(0, env.selector.state.consecutiveFailures)
    }
}
