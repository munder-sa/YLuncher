package com.gt8d.launcher.sets

import com.gt8d.launcher.notice.Notice

import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.curtain.CurtainController
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.CurtainView
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.foreground.ForegroundEvents
import com.gt8d.launcher.foreground.ForegroundEventsUnavailableException
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import com.gt8d.launcher.split.PanelPlacement

class SetRunnerTest {
    @Test fun cancelOpening_clearsStatePendingAndRetry_withoutFailureOrNotice() {
        val e = TestEnv()
        e.runner.open(2)
        e.runner.next()
        e.runner.cancelOpening()
        assertEquals(1, e.startHomeCount)
        assertEquals(SetNavigator.HOME, e.runner.currentIndex)
        assertEquals(SetNavigator.HOME, e.lastIndex.lastSetIndex)
        assertNull(e.runner.opening.value)
        assertNull(e.runner.lastOpenedPair)
        assertNull(e.runner.lastOpenedAtMs)
        assertNull(e.runner.lastReadyAtMs)
        e.scheduler.advanceBy(10000)
        assertEquals(1, e.vendor.calls.size)
        assertEquals(0, e.selector.state.consecutiveFailures)
        assertTrue(e.messenger.notices.isEmpty())
        assertEquals(listOf(HideReason.Cancelled), e.curtainView.hiddenReasons)
    }

    @Test fun cancelGuard_watchesActualLeftRightPanelAndVendor_notUnrelatedOrPending() {
        for ((index, pkg, expected) in listOf(
            Triple(2, "youtube", 2), Triple(2, "applemusic", 2),
            Triple(1, "com.gt8d.launcher.panel", 2),
            Triple(2, com.gt8d.launcher.split.VendorBridge.VENDOR_PKG, 2),
            Triple(2, "unrelated", 1), Triple(2, "maps", 1),
        )) {
            val e = TestEnv()
            e.runner.open(index)
            e.runner.next()
            e.runner.cancelOpening()
            e.scheduler.advanceBy(1500)
            e.events.events.add(ResumeEvent(pkg, e.scheduler.nowMs()))
            e.scheduler.advanceBy(500)
            assertEquals("pkg=$pkg", expected, e.startHomeCount)
            e.scheduler.advanceBy(5000)
            assertEquals(expected, e.startHomeCount)
            assertEquals(1, e.vendor.calls.size)
        }
    }

    @Test fun cancelGuard_fixedDeadlineAndAtMostTwoAdditionalHomes() {
        val e = TestEnv()
        e.runner.open(2)
        e.runner.cancelOpening()
        repeat(4) {
            e.scheduler.advanceBy(500)
            e.events.events.add(ResumeEvent("youtube", e.scheduler.nowMs()))
            e.scheduler.advanceBy(300)
        }
        assertEquals(3, e.startHomeCount)
        val late = TestEnv()
        late.runner.open(2)
        late.runner.cancelOpening()
        late.scheduler.advanceBy(4500)
        late.events.events.add(ResumeEvent("youtube", 4500))
        late.scheduler.advanceBy(300)
        assertEquals(2, late.startHomeCount)
        late.scheduler.advanceBy(300)
        late.events.events.add(ResumeEvent("applemusic", late.scheduler.nowMs()))
        late.scheduler.advanceBy(1000)
        assertEquals(2, late.startHomeCount)
    }

    @Test fun cancelGuard_deadlineStartsAtCancellation_andQueryFailureStopsGuard() {
        for (queryFails in listOf(false, true)) {
            val e = leftPanelEnv()
            e.runner.open(1)
            e.scheduler.advanceBy(1000)
            e.runner.cancelOpening()
            if (queryFails) {
                e.events.queryFails = true
                e.scheduler.advanceBy(200)
                e.events.queryFails = false
            }
            e.scheduler.advanceBy(4000)
            e.events.events.add(ResumeEvent("com.gt8d.launcher.panel", e.scheduler.nowMs()))
            e.scheduler.advanceBy(200)
            assertEquals(if (queryFails) 1 else 2, e.startHomeCount)
            e.scheduler.advanceBy(1000)
            e.events.events.add(ResumeEvent("y", e.scheduler.nowMs()))
            e.scheduler.advanceBy(1000)
            assertEquals(if (queryFails) 1 else 2, e.startHomeCount)
        }
    }

    @Test fun cancelGuard_stopsWhenMonitoringUnavailableInitiallyOrDuringGuard() {
        for (initially in listOf(true, false)) {
            val e = TestEnv(watcherAvailable = !initially)
            e.runner.open(2)
            e.runner.cancelOpening()
            e.events.isAvailable = false
            e.scheduler.advanceBy(200)
            e.events.isAvailable = true
            e.events.events.add(ResumeEvent("youtube", e.scheduler.nowMs()))
            e.scheduler.advanceBy(5000)
            assertEquals(1, e.startHomeCount)
        }
    }

    @Test fun cancelGuard_allSixOperationsStopIt_evenBeforeNullConfigReturn() {
        val operations = listOf<(SetRunner) -> Unit>(
            { it.open(1) }, { it.next() }, { it.prev() }, { it.home() },
            { it.openForBoot(1) }, { it.openForLink("youtube", "maps", {}, {}) },
        )
        for (operation in operations) for (loading in listOf(false, true)) {
            val e = TestEnv()
            e.runner.open(2)
            e.runner.cancelOpening()
            e.loading = loading
            operation(e.runner)
            val homesAfterOperation = e.startHomeCount
            e.events.events.add(ResumeEvent("youtube", 100))
            e.scheduler.advanceBy(2000)
            assertEquals(homesAfterOperation, e.startHomeCount)
        }
    }

    @Test fun cancel_thenNextOpensNormally_andOldAppDoesNotReturnHome() {
        val e = TestEnv()
        e.runner.open(2)
        e.runner.cancelOpening()
        e.runner.next()
        assertEquals(1, e.runner.currentIndex)
        assertEquals(2, e.vendor.calls.size)
        e.events.events.add(ResumeEvent("applemusic", 100))
        e.scheduler.advanceBy(200)
        assertEquals(1, e.startHomeCount)
        resumePanelApp(e, "youtube")
        assertEquals(1, e.runner.readyCount)
        assertNull(e.runner.opening.value)
    }

    @Test fun cancelLink_doesNotInvokeEitherCallback() {
        val e = TestEnv()
        var ready = 0
        var fallback = 0
        assertTrue(e.runner.openForLink("youtube", "maps", { ready++ }, { fallback++ }))
        e.runner.cancelOpening()
        e.scheduler.advanceBy(10000)
        assertEquals(0, ready)
        assertEquals(0, fallback)
        assertTrue(e.messenger.notices.isEmpty())
    }

    @Test fun target_cancellableOnlyForExistingSets() {
        val e = TestEnv()
        assertFalse(e.runner.targetFor(SetNavigator.HOME).cancellable)
        assertFalse(e.runner.targetFor(99).cancellable)
        assertTrue(e.runner.targetFor(1).cancellable)
    }
    @Test fun unsupported_namesFirstUnsupportedApp_andReportsFullscreenFailure() {
        for (unsupported in listOf(setOf("youtube"), setOf("applemusic"), setOf("youtube", "applemusic"))) {
            for (started in listOf(true, false)) {
                val e = TestEnv()
                e.apps.unresizeablePkgs.addAll(unsupported)
                e.apps.startAppResult = started
                e.runner.open(2)
                val label = if ("youtube" in unsupported) "youtube" else "applemusic"
                assertEquals(listOf(if (started) Notice.SplitUnsupported(label) else Notice.OpenFailed("youtube")), e.messenger.notices)
                assertEquals(listOf(StartAppCall("youtube", false)), e.apps.startAppCalls)
            }
        }
    }

    @Test fun leftOnlyAndLaunchFailure_reportFullscreenResult() {
        for (leftOnly in listOf(true, false)) for (started in listOf(true, false)) {
            val e = TestEnv()
            if (leftOnly) e.selector.onFailure(FailReason.EntryMissing, countable = true)
            else e.vendor.result = LaunchResult.Failed(FailReason.Exception)
            e.apps.startAppResult = started
            e.runner.open(2)
            assertEquals(listOf(if (started) Notice.SplitFailed("youtube") else Notice.OpenFailed("youtube")), e.messenger.notices)
            assertEquals(listOf(StartAppCall("youtube", false)), e.apps.startAppCalls)
        }
    }

    @Test fun missingApps_notifyOnlyFirst_inSlotCheckOrder() {
        for (panel in listOf("none", "left", "right")) {
            for (missing in listOf(setOf("app"), setOf("other"), setOf("app", "other"))) {
                val e = if (panel == "left") leftPanelEnv() else TestEnv()
                val app = if (panel == "left") "y" else "youtube"
                val other = if (panel == "none") "applemusic" else "com.gt8d.launcher.panel"
                if ("app" in missing) e.installed.remove(app)
                if ("other" in missing) e.installed.remove(other)
                e.runner.open(if (panel == "none") 2 else 1)
                val label = if ("app" in missing) app else if (panel == "none") other else "YLuncher パネル"
                assertEquals(listOf(Notice.AppMissing(label)), e.messenger.notices)
            }
        }
    }

    @Test fun missingApp_usesLabelOrPackageWhenLabelBlank() {
        for (label in listOf("動画アプリ", "")) {
            val e = TestEnv()
            e.labels["youtube"] = label
            e.installed.remove("youtube")
            e.runner.open(2)
            assertEquals(listOf(Notice.AppMissing(if (label.isBlank()) "youtube" else label)), e.messenger.notices)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun placement_wiring() = runTest {
        val e = TestEnv(scope = this)
        e.runner.open(1); resumePanelApp(e, "youtube")
        e.runner.onPanelPlacementChanged(PanelPlacement.Left); advanceUntilIdle()
        assertEquals(Slot.Panel, e.config.screenSets.first().left)
        assertEquals("com.gt8d.launcher.panel" to "youtube", e.runner.lastOpenedPair)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun panelLaunched_forwardsPartner() = runTest {
        val e = TestEnv(scope = this)
        e.runner.open(1); resumePanelApp(e, "youtube")
        e.runner.panelLaunched("maps")
        e.runner.onPanelPlacementChanged(PanelPlacement.Left); advanceUntilIdle()
        assertEquals(Slot.App("youtube"), e.config.screenSets.first().left)
        assertEquals("maps" to "com.gt8d.launcher.panel", e.runner.lastOpenedPair)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun session_beginOnReady_endOnHomeNextLinkFailSafety() = runTest {
        for (action in listOf<(TestEnv) -> Unit>(
            { it.runner.home() }, { it.runner.open(2) },
            { it.runner.openForLink("youtube", "maps", {}, {}) },
            { it.vendor.result = LaunchResult.Failed(FailReason.EntryMissing); it.runner.open(1) },
            { it.runner.open(1); it.scheduler.advanceBy(4000) },
        )) {
            val e = TestEnv(scope = this)
            e.config = e.config.copy(screenSets = e.config.screenSets.mapIndexed { i, s ->
                if (i == 0) ScreenSet(s.id, Slot.Panel, Slot.App("youtube"), SplitRatio.R4_6) else s
            })
            e.runner.open(1)
            e.runner.onPanelPlacementChanged(PanelPlacement.Right); advanceUntilIdle()
            assertEquals(Slot.Panel, e.config.screenSets.first().left)
            resumePanelApp(e, "youtube"); advanceUntilIdle()
            assertEquals(Slot.Panel, e.config.screenSets.first().right)
            e.events.events.clear()
            action(e)
            e.runner.onPanelPlacementChanged(PanelPlacement.Left); advanceUntilIdle()
            assertEquals(Slot.Panel, e.config.screenSets.first().right)
        }
    }
    private fun leftPanelEnv() = TestEnv().apply {
        config = config.copy(screenSets = listOf(ScreenSet("s", Slot.Panel, Slot.App("y"), SplitRatio.R4_6)))
        installed.add("y")
    }

    private fun resumePanelApp(e: TestEnv, pkg: String) {
        e.events.events.add(ResumeEvent(pkg, e.scheduler.nowMs()))
        e.runner.panelResumedInSplit()
        e.scheduler.advanceBy(200)
    }

    @Test fun leftPanel_passesPanelHostOnLeft() {
        val e = leftPanelEnv()
        e.runner.open(1)
        assertEquals(listOf(SplitCall("com.gt8d.launcher.panel", "y", SplitRatio.R4_6)), e.vendor.calls)
        assertEquals("com.gt8d.launcher.panel" to "y", e.runner.lastOpenedPair)
        assertEquals(Opening("com.gt8d.launcher.panel", "y", 0), e.runner.opening.value)
    }

    @Test fun leftPanel_waitBoth_readyWithPanelSignal() {
        val e = leftPanelEnv()
        e.runner.open(1)
        resumePanelApp(e, "y")
        assertEquals(1, e.runner.readyCount)
        assertEquals(listOf(HideReason.Ready), e.curtainView.hiddenReasons)
        assertFalse(e.curtain.isShowing)
    }

    @Test fun leftPanel_appResumeAlone_notReady() {
        val e = leftPanelEnv()
        e.runner.open(1)
        e.events.events.add(ResumeEvent("y", 0))
        e.scheduler.advanceBy(200)
        assertEquals(0, e.runner.readyCount)
        assertTrue(e.curtain.isShowing)
    }

    @Test fun leftPanel_panelSignalAlone_notReady() {
        val e = leftPanelEnv()
        e.runner.open(1)
        e.runner.panelResumedInSplit()
        e.scheduler.advanceBy(200)
        assertEquals(0, e.runner.readyCount)
        assertTrue(e.curtain.isShowing)
    }

    @Test fun leftPanel_retrySkipped_whenProgress() {
        val e = leftPanelEnv()
        e.runner.open(1)
        e.events.events.add(ResumeEvent("y", 100))
        e.scheduler.advanceBy(3000)
        assertEquals(1, e.vendor.calls.size)
        assertEquals(0, e.runner.readyCount)
    }

    @Test fun leftPanel_retry_preservesSidesAndRatio() {
        val e = leftPanelEnv()
        e.runner.open(1)
        e.scheduler.advanceBy(3000)
        assertEquals(List(2) { SplitCall("com.gt8d.launcher.panel", "y", SplitRatio.R4_6) }, e.vendor.calls)
    }

    @Test fun leftPanel_appMissing_goesHome() {
        val e = leftPanelEnv()
        e.installed.remove("y")
        e.runner.open(1)
        assertEquals(1, e.startHomeCount)
        assertTrue(e.apps.startAppCalls.isEmpty())
        assertTrue(e.vendor.calls.isEmpty())
    }

    @Test fun missingApp_homeVisible_completesReadyBeforeSafety() {
        for (leftPanel in listOf(true, false)) {
            val e = TestEnv(homeVisible = { true })
            e.config = e.config.copy(screenSets = listOf(ScreenSet("missing",
                if (leftPanel) Slot.Panel else Slot.App("missingLeft"), Slot.App("missingRight"), SplitRatio.R5_5)))
            e.runner.open(1)
            assertEquals(1, e.startHomeCount)
            assertEquals(listOf(HideReason.Ready), e.curtainView.hiddenReasons)
            assertFalse(e.curtain.isShowing)
            assertNull(e.runner.opening.value)
            assertEquals(listOf(Notice.AppMissing(if (leftPanel) "missingRight" else "missingLeft")), e.messenger.notices)
            e.scheduler.advanceBy(4000)
            assertEquals(listOf(HideReason.Ready), e.curtainView.hiddenReasons)
        }
    }

    @Test fun missingApp_homeNotVisible_waitsForForeground() {
        for (leftPanel in listOf(true, false)) {
            val e = TestEnv(homeVisible = { false })
            e.config = e.config.copy(screenSets = listOf(ScreenSet("missing",
                if (leftPanel) Slot.Panel else Slot.App("missingLeft"), Slot.App("missingRight"), SplitRatio.R5_5)))
            e.runner.open(1)
            assertEquals(1, e.startHomeCount)
            e.scheduler.advanceBy(200)
            assertTrue(e.curtain.isShowing)
            assertTrue(e.curtainView.hiddenReasons.isEmpty())
            e.events.events.add(ResumeEvent(e.ownPkg, 250))
            e.scheduler.advanceBy(200)
            assertEquals(listOf(HideReason.Ready), e.curtainView.hiddenReasons)
            assertNull(e.runner.opening.value)
            assertEquals(listOf(Notice.AppMissing(if (leftPanel) "missingRight" else "missingLeft")), e.messenger.notices)
        }
    }

    @Test fun missingApp_homeVisible_bootCompletesWithoutCurtain() {
        for (leftPanel in listOf(true, false)) {
            val e = TestEnv(homeVisible = { true })
            e.config = e.config.copy(screenSets = listOf(ScreenSet("missing",
                if (leftPanel) Slot.Panel else Slot.App("missingLeft"), Slot.App("missingRight"), SplitRatio.R5_5)))
            e.runner.openForBoot(1)
            assertEquals(1, e.startHomeCount)
            assertNull(e.runner.opening.value)
            assertTrue(e.curtainView.shownTargets.isEmpty())
            assertEquals(listOf(Notice.AppMissing(if (leftPanel) "missingRight" else "missingLeft")), e.messenger.notices)
        }
    }

    @Test fun leftPanel_panelHostMissing_opensAppFullscreen() {
        val e = leftPanelEnv()
        e.installed.remove("com.gt8d.launcher.panel")
        e.runner.open(1)
        assertEquals(listOf(StartAppCall("y", false)), e.apps.startAppCalls)
        assertTrue(e.vendor.calls.isEmpty())
    }

    @Test fun leftPanel_splitUnavailable_opensAppFullscreen() {
        val e = leftPanelEnv()
        e.selector.onFailure(FailReason.EntryMissing, countable = true)
        e.runner.open(1)
        assertEquals(listOf(StartAppCall("y", false)), e.apps.startAppCalls)
        assertEquals(1, e.messenger.splitUnavailableCount)
        assertEquals(listOf(Notice.SplitFailed("y")), e.messenger.notices)
        assertTrue(e.vendor.calls.isEmpty())
    }

    @Test fun leftPanel_startFailed_opensAppFullscreen() {
        val e = leftPanelEnv()
        e.vendor.result = LaunchResult.Failed(FailReason.EntryMissing)
        e.runner.open(1)
        assertEquals(listOf(StartAppCall("y", false)), e.apps.startAppCalls)
        assertEquals(listOf(HideReason.Failed), e.curtainView.hiddenReasons)
    }

    @Test fun leftPanel_panelHostResizeExempt() {
        val e = leftPanelEnv()
        e.apps.unresizeablePkgs.add("com.gt8d.launcher.panel")
        e.runner.open(1)
        assertEquals(1, e.vendor.calls.size)
        assertTrue(e.apps.startAppCalls.isEmpty())
    }

    @Test fun leftPanel_unresizeableApp_opensAppFullscreen() {
        val e = leftPanelEnv()
        e.apps.unresizeablePkgs.add("y")
        e.runner.open(1)
        assertEquals(listOf(StartAppCall("y", false)), e.apps.startAppCalls)
        assertTrue(e.vendor.calls.isEmpty())
    }

    @Test fun curtainInput_leftPanel() {
        val e = leftPanelEnv()
        e.runner.open(1)
        val target = e.curtainView.shownTargets.single()
        assertEquals("1  パネル | y", target.setName)
        assertEquals(com.gt8d.launcher.curtain.SlotPreview.Panel, target.left)
        assertEquals(com.gt8d.launcher.curtain.SlotPreview.App("y", "y"), target.right)
    }

    @Test fun curtainAndBootTarget_useNumberedSetLabel() {
        val e = TestEnv()
        assertEquals("1  youtube | パネル", e.runner.targetFor(1).setName)
        assertEquals("2  youtube | applemusic", e.runner.targetFor(2).setName)
        assertEquals("ホーム", e.runner.targetFor(0).setName)
        e.runner.open(1)
        assertEquals("1  youtube | パネル", e.curtainView.shownTargets.single().setName)
    }

    @Test fun panelLaunched_updatesPartner_leftAndRight() {
        val left = leftPanelEnv()
        left.runner.open(1)
        left.runner.panelLaunched("maps")
        assertEquals("com.gt8d.launcher.panel" to "y", left.runner.lastOpenedPair)
        resumePanelApp(left, "y")
        left.runner.panelLaunched("maps")
        assertEquals("com.gt8d.launcher.panel" to "maps", left.runner.lastOpenedPair)
        val right = TestEnv()
        right.runner.open(1)
        resumePanelApp(right, "youtube")
        right.runner.panelLaunched("maps")
        assertEquals("maps" to "com.gt8d.launcher.panel", right.runner.lastOpenedPair)
        right.runner.home()
        right.runner.panelLaunched("y")
        assertNull(right.runner.lastOpenedPair)
        val apps = TestEnv()
        apps.runner.open(2)
        apps.runner.panelLaunched("maps")
        assertEquals("youtube" to "applemusic", apps.runner.lastOpenedPair)
    }

    @Test fun panelLaunched_ignoredAfterFallback() {
        val e = leftPanelEnv()
        e.runner.open(1)
        resumePanelApp(e, "y")
        e.vendor.result = LaunchResult.Failed(FailReason.EntryMissing)
        e.runner.open(1)
        e.runner.panelLaunched("maps")
        assertEquals("com.gt8d.launcher.panel" to "y", e.runner.lastOpenedPair)
    }

    @Test fun leftPanel_bootReady_allowsPanelLaunched() {
        val e = leftPanelEnv()
        e.runner.openForBoot(1)
        resumePanelApp(e, "y")
        e.runner.panelLaunched("maps")
        assertEquals("com.gt8d.launcher.panel" to "maps", e.runner.lastOpenedPair)
        assertTrue(e.curtainView.shownTargets.isEmpty())
        assertNull(e.runner.opening.value)
    }

    @Test fun leftPanel_hostResumeAlone_doesNotSkipRetry() {
        val e = leftPanelEnv()
        e.runner.open(1)
        e.events.events.add(ResumeEvent("com.gt8d.launcher.panel", 100))
        e.scheduler.advanceBy(3000)
        assertEquals(2, e.vendor.calls.size)
        assertEquals(0, e.runner.readyCount)
    }

    @Test fun panelLaunched_ignoredAfterSafety() {
        val e = leftPanelEnv()
        e.runner.open(1)
        e.scheduler.advanceBy(4000)
        e.runner.panelLaunched("maps")
        assertEquals("com.gt8d.launcher.panel" to "y", e.runner.lastOpenedPair)
        assertEquals(listOf(HideReason.Safety), e.curtainView.hiddenReasons)
    }

    @Test fun currentIndexFlow_followsOpenAndHome() {
        val env = TestEnv(initialLastSetIndex = 2)
        assertEquals(2, env.runner.currentIndexFlow.value)
        env.runner.open(1)
        assertEquals(1, env.runner.currentIndexFlow.value)
        env.events.events.add(ResumeEvent("youtube", 100L))
        env.runner.panelResumedInSplit()
        env.scheduler.advanceBy(200L)
        env.runner.home()
        assertEquals(0, env.runner.currentIndexFlow.value)
        env.runner.followIndex(3)
        assertEquals(3, env.runner.currentIndexFlow.value)
    }
    @Test fun panelSet_passesPanelHostToVendor() {
        val env = TestEnv()
        env.runner.open(1)
        assertEquals(listOf(SplitCall("youtube", "com.gt8d.launcher.panel", SplitRatio.R6_4)), env.vendor.calls)
        assertEquals(Opening("youtube", "com.gt8d.launcher.panel", 0L), env.runner.opening.value)
        env.scheduler.advanceBy(3000L)
        assertEquals(2, env.vendor.calls.size)
        assertEquals("com.gt8d.launcher.panel", env.vendor.calls.last().right)
    }

    @Test fun panelSet_hostMissing_opensLeftOnly_withoutVendor() {
        val env = TestEnv()
        env.installed.remove("com.gt8d.launcher.panel")
        env.runner.open(1)
        assertTrue(env.vendor.calls.isEmpty())
        assertTrue(env.appPair.calls.isEmpty())
        assertEquals(listOf(StartAppCall("youtube", false)), env.apps.startAppCalls)
        env.scheduler.advanceBy(4000L)
        assertEquals(0, env.selector.state.consecutiveFailures)
        assertEquals(0, env.messenger.splitUnavailableCount)
        assertEquals(0, env.messenger.switchFailedCount)
        assertEquals(listOf(Notice.AppMissing("YLuncher パネル")), env.messenger.notices)
    }

    @Test fun panelSet_lastOpenedPair_rightIsHost() {
        val env = TestEnv()
        env.runner.open(1)
        assertEquals("youtube" to "com.gt8d.launcher.panel", env.runner.lastOpenedPair)
    }

    @Test
    fun retrySkipped_whenOwnScreenResumedDuringOpening() {
        val e = TestEnv()
        e.runner.open(1)
        e.scheduler.advanceBy(1000)
        e.runner.ownScreenResumed()
        e.scheduler.advanceBy(2000)
        assertEquals(1, e.vendor.calls.size)
        assertTrue(e.curtain.isShowing)
        e.scheduler.advanceBy(999)
        assertTrue(e.curtain.isShowing)
        e.scheduler.advanceBy(1)
        assertFalse(e.curtain.isShowing)
        assertEquals(listOf(HideReason.Safety), e.curtainView.hiddenReasons)
    }

    @Test
    fun retrySkipped_whenStockLauncherResumed() {
        val e = TestEnv()
        e.runner.open(2)
        e.scheduler.advanceBy(1500)
        e.events.events.add(ResumeEvent("com.android.launcher3", 1500))
        e.scheduler.advanceBy(1500)
        assertEquals(1, e.vendor.calls.size)
    }

    @Test
    fun retrySkipped_whenLeftResumed() {
        val e = TestEnv()
        e.runner.open(2)
        e.scheduler.advanceBy(500)
        e.events.events.add(ResumeEvent("youtube", 500))
        e.scheduler.advanceBy(2500)
        assertEquals(1, e.vendor.calls.size)
    }

    @Test
    fun retryHappens_whenNothingSeen() {
        val e = TestEnv()
        e.runner.open(2)
        e.scheduler.advanceBy(2999)
        assertEquals(1, e.vendor.calls.size)
        e.scheduler.advanceBy(1)
        assertEquals(2, e.vendor.calls.size)
    }

    @Test
    fun ownScreenResumed_outsideOpening_isIgnored() {
        val e = TestEnv()
        e.runner.ownScreenResumed()
        e.runner.open(2)
        e.scheduler.advanceBy(3000)
        assertEquals(2, e.vendor.calls.size)
    }

    @Test
    fun ownScreenSeen_resetsOnNextOpen() {
        val e = TestEnv()
        e.runner.open(1)
        e.scheduler.advanceBy(1000)
        e.runner.ownScreenResumed()
        e.scheduler.advanceBy(2000)
        assertEquals(1, e.vendor.calls.size)
        e.scheduler.advanceBy(1000)
        assertNull(e.runner.opening.value)
        e.runner.open(2)
        assertEquals(2, e.vendor.calls.size)
        e.scheduler.advanceBy(3000)
        assertEquals(3, e.vendor.calls.size)
    }

    @Test fun followIndex_updatesCurrentAndSaved_withoutOpening() {
        val e = TestEnv()
        e.runner.followIndex(2)
        assertEquals(2, e.runner.currentIndex)
        assertEquals(2, e.lastIndex.lastSetIndex)
        assertTrue(e.vendor.calls.isEmpty())
        assertTrue(e.appPair.calls.isEmpty())
        assertTrue(e.apps.startAppCalls.isEmpty())
        assertTrue(e.curtainView.shownTargets.isEmpty())
        assertEquals(0, e.startHomeCount)
        assertNull(e.runner.opening.value)
    }
    @Test
    fun abortBootToHome_duringNormalOpening_doesNothing() {
        val e = TestEnv()
        e.runner.open(2)
        e.runner.open(3)
        val opening = e.runner.opening.value
        e.runner.abortBootToHome()
        assertEquals(opening, e.runner.opening.value)
        assertEquals(2, e.runner.currentIndex)
        assertEquals(2, e.lastIndex.lastSetIndex)
        assertEquals(0, e.startHomeCount)
        assertEquals(1, e.curtainView.updatedTargets.size)
        completeBootSetTwo(e)
        assertEquals(3, e.runner.currentIndex)
        assertEquals(2, e.vendor.calls.size)
        e.scheduler.advanceBy(3000)
        assertEquals(3, e.vendor.calls.size)
    }

    @Test
    fun abortBootToHome_duringLinkOpening_doesNothing() {
        val e = TestEnv()
        var fallback = 0
        assertTrue(e.runner.openForLink("youtube", "com.android.chrome", onReady = {}) { fallback++ })
        e.runner.abortBootToHome()
        e.events.events.add(ResumeEvent("youtube", 100))
        e.events.events.add(ResumeEvent("com.android.chrome", 120))
        e.scheduler.advanceBy(200)
        assertEquals(1, e.runner.readyCount)
        assertEquals(0, fallback)
        assertEquals(0, e.startHomeCount)
        assertEquals(listOf(HideReason.Ready), e.curtainView.hiddenReasons)
        e.scheduler.advanceBy(20000)
        assertEquals(1, e.vendor.calls.size)
    }

    @Test
    fun abortBootToHome_afterBootReady_startsHomeWithoutCurtain() {
        val e = TestEnv()
        e.runner.openForBoot(2)
        completeBootSetTwo(e)
        assertNull(e.runner.opening.value)
        assertEquals(1, e.runner.readyCount)
        e.runner.abortBootToHome()
        assertEquals(1, e.startHomeCount)
        assertEquals(0, e.runner.currentIndex)
        assertEquals(0, e.lastIndex.lastSetIndex)
        assertNull(e.runner.lastOpenedPair)
        assertNull(e.runner.lastOpenedAtMs)
        assertEquals(1, e.runner.readyCount)
        assertTrue(e.warm.isWarm("youtube"))
        assertTrue(e.warm.isWarm("applemusic"))
        assertTrue(e.curtainView.shownTargets.isEmpty())
        assertTrue(e.curtainView.hiddenReasons.isEmpty())
    }

    @Test
    fun abortBootToHome_cancelsSingleWait() {
        val e = TestEnv()
        e.installed.remove("applemusic")
        e.runner.openForBoot(2)
        e.runner.abortBootToHome()
        assertNull(e.runner.opening.value)
        assertEquals(1, e.startHomeCount)
        e.scheduler.advanceBy(100)
        e.events.events.add(ResumeEvent("youtube", 100))
        e.runner.panelResumedInSplit()
        e.scheduler.advanceBy(20000)
        assertEquals(0, e.runner.readyCount)
        assertEquals(1, e.startHomeCount)
    }

    @Test
    fun abortBootToHome_thenNormalOpen_canComplete() {
        val e = TestEnv()
        e.runner.openForBoot(2)
        e.runner.abortBootToHome()
        e.runner.open(1)
        e.events.events.add(ResumeEvent("youtube", 100))
        e.runner.panelResumedInSplit()
        e.scheduler.advanceBy(200)
        assertEquals(1, e.runner.readyCount)
        assertEquals(1, e.runner.currentIndex)
        assertNull(e.runner.opening.value)
        assertEquals(listOf(HideReason.Ready), e.curtainView.hiddenReasons)
    }

    private fun assertLoadingRejected(operation: (TestEnv) -> Unit) {
        val e = TestEnv(initialLastSetIndex = 4, loading = true)
        operation(e)
        fun unchanged() {
            assertEquals(4, e.runner.currentIndex)
            assertEquals(4, e.lastIndex.lastSetIndex)
            assertNull(e.runner.opening.value)
            assertNull(e.runner.lastOpenedPair)
            assertNull(e.runner.lastOpenedAtMs)
            assertEquals(0, e.vendor.calls.size)
            assertEquals(0, e.appPair.calls.size)
            assertEquals(0, e.apps.startAppCalls.size)
            assertEquals(0, e.startHomeCount)
            assertEquals(0, e.curtainView.shownTargets.size)
            assertEquals(0, e.curtainView.updatedTargets.size)
            assertEquals(0, e.curtainView.hiddenReasons.size)
            assertEquals(0, e.runner.readyCount)
            assertEquals(SplitModeState(), e.selector.state)
            assertFalse(e.warm.isWarm("youtube"))
            assertEquals(0, e.messenger.splitUnavailableCount)
            assertEquals(0, e.messenger.switchFailedCount)
            assertTrue(e.messenger.notices.isEmpty())
        }
        unchanged()
        e.scheduler.advanceBy(20000)
        unchanged()
        val target = e.runner.targetFor(1)
        assertEquals(1, target.count)
        assertEquals(0, target.position)
        e.loading = false
        e.scheduler.advanceBy(20000)
        unchanged()
        e.runner.open(3)
        assertEquals(SplitCall("youtube", "maps", SplitRatio.R5_5), e.vendor.calls.single())
    }

    @Test fun loadingConfig_open_doesNothing() {
        listOf(1, 0).forEach { index -> assertLoadingRejected { it.runner.open(index) } }
    }
    @Test fun loadingConfig_next_doesNothing() = assertLoadingRejected { it.runner.next() }
    @Test fun loadingConfig_prev_doesNothing() = assertLoadingRejected { it.runner.prev() }
    @Test fun loadingConfig_home_doesNothing() = assertLoadingRejected { it.runner.home() }
    @Test fun loadingConfig_openForBoot_doesNothing() {
        listOf(1, 0).forEach { index -> assertLoadingRejected { it.runner.openForBoot(index) } }
    }
    @Test fun loadingConfig_openForLink_returnsFalseWithoutCallback() = assertLoadingRejected {
        var fallback = 0
        assertFalse(it.runner.openForLink("youtube", "com.android.chrome", onReady = {}) { fallback++ })
        assertEquals(0, fallback)
    }
    @Test fun loadingConfig_construction_preservesSavedIndex() = assertLoadingRejected {}
    @Test fun loadedConfig_rejectedOperationsAreNotReplayed() = assertLoadingRejected {
        it.runner.open(1)
        it.runner.next()
        it.runner.prev()
        it.runner.home()
        it.runner.openForBoot(2)
        assertFalse(it.runner.openForLink("youtube", "com.android.chrome", onReady = {}) { throw AssertionError("fallback") })
    }
    @Test fun loadingConfig_doesNotReplacePendingRequest() {
        val e = TestEnv()
        e.runner.open(2)
        e.runner.open(3)
        e.loading = true
        e.runner.open(1)
        e.runner.next()
        e.runner.prev()
        e.runner.home()
        e.runner.openForBoot(0)
        assertFalse(e.runner.openForLink("youtube", "com.android.chrome", onReady = {}) { throw AssertionError("fallback") })
        assertEquals(1, e.curtainView.updatedTargets.size)
        e.loading = false
        completeBootSetTwo(e)
        assertEquals(3, e.runner.currentIndex)
        assertEquals(SplitCall("youtube", "maps", SplitRatio.R5_5), e.vendor.calls.last())
    }

    @Test
    fun directLaunchSuccess_withoutUsageAccess_makesNextOpenWarm() {
        // 右のパネル(中継)は「本体のパネルの画面が生きている」ときだけ暖かい(Task 056)。このテストは左の暖かさを確かめるため、パネルは生きている前提にする。
        val savedPanelAlive = com.gt8d.launcher.AppGraph.panelAlive
        com.gt8d.launcher.AppGraph.panelAlive = true
        try {
        val env = TestEnv(watcherAvailable = false)
        env.selector.onFailure(FailReason.EntryMissing, countable = true)
        env.runner.open(1)
        env.scheduler.advanceBy(3499)
        assertTrue(env.curtain.isShowing)
        env.scheduler.advanceBy(1)
        assertFalse(env.curtain.isShowing)
        assertEquals(listOf(HideReason.Timed), env.curtainView.hiddenReasons)
        env.runner.open(1)
        env.scheduler.advanceBy(799)
        assertTrue(env.curtain.isShowing)
        env.scheduler.advanceBy(1)
        assertFalse(env.curtain.isShowing)
        assertEquals(listOf(HideReason.Timed, HideReason.Timed), env.curtainView.hiddenReasons)
        assertEquals(2, env.apps.startAppCalls.size)
        assertTrue(env.warm.isWarm("youtube"))
        assertEquals(0, env.runner.readyCount)
        assertEquals(0, env.selector.state.consecutiveFailures)
        } finally {
            com.gt8d.launcher.AppGraph.panelAlive = savedPanelAlive
        }
    }

    @Test
    fun directLaunchFailure_withoutUsageAccess_keepsNextOpenCold() {
        val env = TestEnv(watcherAvailable = false)
        env.apps.startAppResult = false
        env.selector.onFailure(FailReason.EntryMissing, countable = true)
        repeat(2) { operation ->
            env.runner.open(1)
            env.scheduler.advanceBy(3499)
            assertTrue(env.curtain.isShowing)
            env.scheduler.advanceBy(1)
            assertFalse(env.curtain.isShowing)
            assertEquals(List(operation + 1) { HideReason.Timed }, env.curtainView.hiddenReasons)
            assertFalse(env.warm.isWarm("youtube"))
        }
        assertEquals(2, env.apps.startAppCalls.size)
        assertEquals(0, env.runner.readyCount)
        assertEquals(0, env.selector.state.consecutiveFailures)
    }

    private fun completeBootSetTwo(env: TestEnv) {
        env.events.events.add(ResumeEvent("youtube", 100))
        env.events.events.add(ResumeEvent("applemusic", 120))
        env.scheduler.advanceBy(200)
    }

    @Test
    fun pendingBootSet_preservesNoCurtainAndUncountedTimeout() {
        val env = TestEnv()
        env.runner.openForBoot(2)
        env.runner.openForBoot(3)
        completeBootSetTwo(env)
        assertEquals(3, env.runner.currentIndex)
        assertEquals(SplitCall("youtube", "maps", SplitRatio.R5_5), env.vendor.calls.last())
        assertEquals(0, env.curtainView.shownTargets.size)
        env.scheduler.advanceBy(9999)
        assertNotNull(env.runner.opening.value)
        env.scheduler.advanceBy(1)
        assertNull(env.runner.opening.value)
        assertEquals(0, env.selector.state.consecutiveFailures)
    }

    @Test
    fun pendingBootHome_startsHomeWithoutCurtain() {
        val env = TestEnv()
        env.runner.openForBoot(2)
        env.runner.openForBoot(0)
        completeBootSetTwo(env)
        assertEquals(1, env.startHomeCount)
        assertEquals(0, env.runner.currentIndex)
        assertEquals(0, env.lastIndex.lastSetIndex)
        assertNull(env.runner.lastOpenedPair)
        assertNull(env.runner.lastOpenedAtMs)
        assertEquals(0, env.curtainView.shownTargets.size)
    }

    @Test
    fun pendingLatestRequest_preservesItsOwnRoute() {
        val normal = TestEnv()
        normal.runner.openForBoot(2)
        normal.runner.openForBoot(1)
        normal.runner.open(3)
        completeBootSetTwo(normal)
        assertEquals(3, normal.runner.currentIndex)
        assertEquals(1, normal.curtainView.shownTargets.size)
        assertTrue(normal.curtain.isShowing)

        val boot = TestEnv()
        boot.runner.openForBoot(2)
        boot.runner.open(1)
        boot.runner.openForBoot(3)
        completeBootSetTwo(boot)
        assertEquals(3, boot.runner.currentIndex)
        assertEquals(0, boot.curtainView.shownTargets.size)
        assertFalse(boot.curtain.isShowing)
    }

    @Test
    fun nextAfterPendingBoot_usesPendingIndexAndNormalRoute() {
        val env = TestEnv()
        env.runner.openForBoot(1)
        env.runner.openForBoot(2)
        env.runner.next()
        env.events.events.add(ResumeEvent("youtube", 100))
        env.runner.panelResumedInSplit()
        env.scheduler.advanceBy(200)
        assertEquals(3, env.runner.currentIndex)
        assertEquals(SplitCall("youtube", "maps", SplitRatio.R5_5), env.vendor.calls.last())
        assertEquals(1, env.curtainView.shownTargets.size)
        assertTrue(env.curtain.isShowing)
    }

    @Test
    fun retryEntryMissing_disablesVendorImmediately() {
        val env = TestEnv()
        env.runner.open(2)
        env.scheduler.advanceBy(2999)
        env.vendor.result = LaunchResult.Failed(FailReason.EntryMissing)
        env.scheduler.advanceBy(1)
        assertEquals(2, env.vendor.calls.size)
        assertFalse(env.selector.state.vendorUsable)
        assertEquals(FailReason.EntryMissing, env.selector.state.lastFailure)
        assertEquals(listOf(StartAppCall("youtube", false)), env.apps.startAppCalls)
        assertEquals(1, env.messenger.splitUnavailableCount)
        assertEquals(listOf(Notice.SplitFailed("youtube")), env.messenger.notices)
        assertNull(env.runner.opening.value)
    }

    @Test
    fun retryException_countsOnce_andCancelsTimeout() {
        val env = TestEnv()
        env.runner.open(2)
        env.scheduler.advanceBy(2999)
        env.vendor.result = LaunchResult.Failed(FailReason.Exception)
        env.scheduler.advanceBy(1)
        assertEquals(1, env.selector.state.consecutiveFailures)
        assertEquals(FailReason.Exception, env.selector.state.lastFailure)
        assertEquals(listOf(HideReason.Failed), env.curtainView.hiddenReasons)
        assertNull(env.runner.opening.value)
        env.scheduler.advanceBy(7000)
        assertEquals(1, env.selector.state.consecutiveFailures)
        assertEquals(2, env.vendor.calls.size)
        assertEquals(0, env.messenger.switchFailedCount)
    }

    @Test
    fun bootRetryException_countsKnownFailureWithoutCurtain() {
        val env = TestEnv()
        env.runner.openForBoot(2)
        env.scheduler.advanceBy(2999)
        env.vendor.result = LaunchResult.Failed(FailReason.Exception)
        env.scheduler.advanceBy(1)
        assertEquals(1, env.selector.state.consecutiveFailures)
        assertEquals(FailReason.Exception, env.selector.state.lastFailure)
        assertEquals(listOf(StartAppCall("youtube", false)), env.apps.startAppCalls)
        assertNull(env.runner.opening.value)
        assertEquals(0, env.curtainView.shownTargets.size)
        env.scheduler.advanceBy(7000)
        assertEquals(1, env.selector.state.consecutiveFailures)
        assertEquals(FailReason.Exception, env.selector.state.lastFailure)
        assertEquals(2, env.vendor.calls.size)
    }

    @Test
    fun linkRetryFailure_callsFallbackOnce() {
        val env = TestEnv()
        var calls = 0
        env.runner.openForLink("youtube", "com.android.chrome", onReady = {}) { calls++ }
        env.scheduler.advanceBy(2999)
        env.vendor.result = LaunchResult.Failed(FailReason.EntryMissing)
        env.scheduler.advanceBy(1)
        assertEquals(1, calls)
        assertNull(env.runner.opening.value)
        assertEquals(listOf(HideReason.Failed), env.curtainView.hiddenReasons)
        env.scheduler.advanceBy(7000)
        assertEquals(1, calls)
    }

    @Test
    fun queryFailure_threeOperations_doNotDisableVendor() {
        val env = TestEnv()
        env.events.queryFails = true
        repeat(3) { operation ->
            env.runner.open(2)
            env.scheduler.advanceBy(3499)
            assertNotNull(env.runner.opening.value)
            env.scheduler.advanceBy(1)
            assertNull(env.runner.opening.value)
            assertEquals(operation + 1, env.vendor.calls.size)
            assertEquals(List(operation + 1) { HideReason.Timed }, env.curtainView.hiddenReasons)
            assertEquals(0, env.selector.state.consecutiveFailures)
            assertTrue(env.selector.state.vendorUsable)
            assertEquals(0, env.messenger.switchFailedCount)
            assertEquals(0, env.runner.readyCount)
        }
    }

    @Test
    fun queryFailureDuringWait_stopsRetryAndTimeoutCounting() {
        val env = TestEnv()
        env.runner.open(2)
        env.scheduler.advanceBy(1000)
        env.events.queryFails = true
        env.scheduler.advanceBy(2000)
        assertEquals(1, env.vendor.calls.size)
        env.scheduler.advanceBy(499)
        assertNotNull(env.runner.opening.value)
        env.scheduler.advanceBy(1)
        assertNull(env.runner.opening.value)
        assertFalse(env.curtain.isShowing)
        assertEquals(listOf(HideReason.Ready), env.curtainView.hiddenReasons)
        env.scheduler.advanceBy(6500)
        assertEquals(0, env.selector.state.consecutiveFailures)
        assertEquals(0, env.runner.readyCount)
        assertEquals(0, env.messenger.switchFailedCount)
    }

    @Test
    fun linkQueryFailureDuringWait_callsFallbackOnce() {
        val env = TestEnv()
        var calls = 0
        env.runner.openForLink("youtube", "com.android.chrome", onReady = {}) { calls++ }
        env.scheduler.advanceBy(1000)
        env.events.queryFails = true
        env.scheduler.advanceBy(2499)
        assertEquals(0, calls)
        env.scheduler.advanceBy(1)
        assertEquals(1, calls)
        assertNull(env.runner.opening.value)
        env.scheduler.advanceBy(6500)
        assertEquals(1, calls)
        assertEquals(0, env.selector.state.consecutiveFailures)
        assertEquals(0, env.runner.readyCount)
    }

    @Test
    fun futureResumeEvents_doNotCompleteBeforeTheirTime() {
        val env = TestEnv()
        env.runner.open(2)
        env.events.events.add(ResumeEvent("youtube", 250L))
        env.events.events.add(ResumeEvent("applemusic", 280L))
        env.scheduler.advanceBy(200L)
        assertEquals(0, env.runner.readyCount)
        assertNotNull(env.runner.opening.value)
        env.scheduler.advanceBy(200L)
        assertEquals(1, env.runner.readyCount)
        assertNull(env.runner.opening.value)
    }

    @Test
    fun openForLink_opensVendorWithBrowserRight_keepsLastIndex() {
        val env = TestEnv(initialLastSetIndex = 2)
        env.installed.add("org.videolan.vlc")
        env.scheduler.advanceBy(123)
        val index = env.runner.currentIndex
        val last = env.lastIndex.lastSetIndex
        assertTrue(env.runner.openForLink("org.videolan.vlc", "com.android.chrome", onReady = {}) {})
        assertEquals(listOf(SplitCall("org.videolan.vlc", "com.android.chrome", SplitRatio.R5_5)), env.vendor.calls)
        assertTrue(env.curtain.isShowing)
        assertEquals(index, env.runner.currentIndex)
        assertEquals(last, env.lastIndex.lastSetIndex)
        assertEquals("org.videolan.vlc" to "com.android.chrome", env.runner.lastOpenedPair)
        assertEquals(123L, env.runner.lastOpenedAtMs)
    }

    @Test
    fun openForLink_whileOpening_ignored() {
        val env = TestEnv()
        env.installed.add("org.videolan.vlc")
        var calls = 0
        env.runner.open(1)
        assertFalse(env.runner.openForLink("org.videolan.vlc", "com.android.chrome", onReady = {}) { calls++ })
        assertEquals(1, env.vendor.calls.size)
        assertEquals(0, calls)
    }

    @Test
    fun openForLink_launchFailed_callsNotSplitOnce() {
        val env = TestEnv()
        env.installed.add("org.videolan.vlc")
        env.vendor.result = LaunchResult.Failed(FailReason.EntryMissing)
        var calls = 0
        assertTrue(env.runner.openForLink("org.videolan.vlc", "com.android.chrome", onReady = {}) { calls++ })
        env.scheduler.advanceBy(10000)
        assertEquals(1, calls)
    }

    @Test
    fun openForLink_timeout_callsNotSplitOnce() {
        val env = TestEnv()
        env.installed.add("org.videolan.vlc")
        var calls = 0
        env.runner.openForLink("org.videolan.vlc", "com.android.chrome", onReady = {}) { calls++ }
        env.scheduler.advanceBy(4000)
        assertEquals(listOf(HideReason.Safety), env.curtainView.hiddenReasons)
        assertEquals(1, calls)
        env.scheduler.advanceBy(5000)
        assertEquals(1, calls)
    }

    @Test
    fun openForLink_ready_doesNotCallNotSplit() {
        val env = TestEnv()
        env.installed.add("org.videolan.vlc")
        var calls = 0
        env.runner.openForLink("org.videolan.vlc", "com.android.chrome", onReady = {}) { calls++ }
        env.events.events.add(ResumeEvent("org.videolan.vlc", 100))
        env.events.events.add(ResumeEvent("com.android.chrome", 120))
        env.scheduler.advanceBy(200)
        assertFalse(env.curtain.isShowing)
        assertEquals(listOf(HideReason.Ready), env.curtainView.hiddenReasons)
        env.scheduler.advanceBy(5000)
        assertEquals(0, calls)
    }

    @Test
    fun open_afterLinkSuccess_noStaleFallback() {
        val env = TestEnv()
        env.installed.add("org.videolan.vlc")
        var calls = 0
        env.runner.openForLink("org.videolan.vlc", "com.android.chrome", onReady = {}) { calls++ }
        env.events.events.add(ResumeEvent("org.videolan.vlc", 100))
        env.events.events.add(ResumeEvent("com.android.chrome", 120))
        env.scheduler.advanceBy(200)
        assertFalse(env.curtain.isShowing)
        env.vendor.result = LaunchResult.Failed(FailReason.EntryMissing)
        env.runner.open(1)
        assertEquals(2, env.vendor.calls.size)
        assertEquals(0, calls)
    }

    @Test
    fun lastOpenedAtMs_clearedByHome() {
        val env = TestEnv()
        env.scheduler.advanceBy(123)
        env.runner.open(1)
        assertEquals(123L, env.runner.lastOpenedAtMs)
        env.events.events.add(ResumeEvent("youtube", 150))
        env.runner.panelResumedInSplit()
        env.scheduler.advanceBy(200)
        assertNull(env.runner.opening.value)
        env.runner.home()
        assertNull(env.runner.lastOpenedAtMs)
    }


    @Test fun link_ready_callsOnReadyOnce() {
        val e = TestEnv()
        var ready = 0; var failed = 0
        assertTrue(e.runner.openForLink("youtube", "com.android.chrome", { ready++ }, { failed++ }))
        assertEquals(0, ready)
        e.events.events.add(ResumeEvent("youtube", 100))
        e.events.events.add(ResumeEvent("com.android.chrome", 120))
        e.scheduler.advanceBy(200)
        assertEquals(listOf(HideReason.Ready), e.curtainView.hiddenReasons)
        assertEquals(1, ready); assertEquals(0, failed)
        e.scheduler.advanceBy(10000)
        assertEquals(1, ready); assertEquals(0, failed)
    }
    @Test fun link_timed_callsOnReadyOnce() {
        val e = TestEnv(watcherAvailable = false)
        var ready = 0; var failed = 0
        e.runner.openForLink("youtube", "com.android.chrome", { ready++ }, { failed++ })
        e.scheduler.advanceBy(3499)
        assertEquals(0, ready)
        e.scheduler.advanceBy(1)
        assertEquals(listOf(HideReason.Timed), e.curtainView.hiddenReasons)
        assertEquals(1, ready); assertEquals(0, failed)
        e.scheduler.advanceBy(10000)
        assertEquals(1, ready); assertEquals(0, failed)
    }
    @Test fun link_safety_callsNotSplitOnce() {
        val e = TestEnv()
        var ready = 0; var failed = 0
        e.runner.openForLink("youtube", "com.android.chrome", { ready++ }, { failed++ })
        e.scheduler.advanceBy(4000)
        assertEquals(listOf(HideReason.Safety), e.curtainView.hiddenReasons)
        assertEquals(0, ready); assertEquals(1, failed)
        e.scheduler.advanceBy(10000)
        assertEquals(0, ready); assertEquals(1, failed)
    }
    @Test fun link_overridden_callsNeither() {
        listOf<(SetRunner) -> Unit>({ it.next() }, { it.prev() }, { it.home() },
            { it.open(0) }, { it.openForBoot(0) }).forEach { override ->
            val e = TestEnv()
            var ready = 0; var failed = 0
            e.runner.openForLink("youtube", "com.android.chrome", { ready++ }, { failed++ })
            override(e.runner)
            e.scheduler.advanceBy(10000)
            assertEquals(0, ready); assertEquals(0, failed)
        }
    }
    @Test fun link_secondLink_cancelsFirstCallbacks() {
        val e = TestEnv()
        var calls = 0
        e.runner.openForLink("youtube", "com.android.chrome", { calls++ }, { calls++ })
        assertFalse(e.runner.openForLink("maps", "com.android.chrome", { calls++ }, { calls++ }))
        e.events.events.add(ResumeEvent("youtube", 100))
        e.events.events.add(ResumeEvent("com.android.chrome", 120))
        e.scheduler.advanceBy(10000)
        assertEquals(0, calls)
        assertEquals(1, e.vendor.calls.size)
    }
    @Test fun link_singleLaunchFallback_callsNotSplitOnly() {
        val e = TestEnv()
        var ready = 0; var failed = 0
        e.runner.openForLink("missing", "com.android.chrome", { ready++ }, { failed++ })
        assertEquals(0, ready); assertEquals(1, failed)
        assertEquals(listOf(StartAppCall("com.android.chrome", false)), e.apps.startAppCalls)
        e.scheduler.advanceBy(10000)
        assertEquals(0, ready); assertEquals(1, failed)
    }
    @Test fun link_monitoringUnavailableFromStart_timed_callsOnReady() {
        val e = TestEnv()
        e.events.queryFails = true
        var ready = 0; var failed = 0
        e.runner.openForLink("youtube", "com.android.chrome", { ready++ }, { failed++ })
        e.scheduler.advanceBy(3500)
        assertEquals(listOf(HideReason.Timed), e.curtainView.hiddenReasons)
        assertEquals(1, ready); assertEquals(0, failed)
    }
    @Test fun link_rightIsBrowserInVendorCall() {
        val e = TestEnv()
        e.runner.openForLink("youtube", "com.brave.browser", {}, {})
        assertEquals(listOf(SplitCall("youtube", "com.brave.browser", SplitRatio.R5_5)), e.vendor.calls)
        assertEquals(com.gt8d.launcher.curtain.SlotPreview.App("com.brave.browser", "com.brave.browser"),
            e.curtainView.shownTargets.single().right)
        assertEquals(0, e.runner.currentIndex)
        assertEquals(0, e.runner.currentIndexFlow.value)
    }

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
        var startAppResult = true
        val startAppCalls = mutableListOf<StartAppCall>()
        val unresizeablePkgs = mutableSetOf<String>()

        override fun canResolve(pkg: String, cls: String): Boolean = true
        override fun start(spec: LaunchSpec) {}
        override fun startApp(pkg: String, adjacent: Boolean): Boolean {
            startAppCalls.add(StartAppCall(pkg, adjacent))
            return startAppResult
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
        private val nowMs: () -> Long,
        var isAvailable: Boolean = true,
    ) : ForegroundEvents {
        val events = mutableListOf<ResumeEvent>()
        var queryFails = false
        override fun available(): Boolean = isAvailable
        override fun resumedSince(sinceMs: Long): List<ResumeEvent> {
            if (queryFails) throw ForegroundEventsUnavailableException()
            return events.filter { it.timeMs >= sinceMs && it.timeMs <= nowMs() }
        }
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
        val notices = mutableListOf<Notice>()
        val splitUnavailableCount get() = notices.count { it is Notice.SplitFailed || it is Notice.SplitUnsupported }
        val switchFailedCount get() = notices.count { it == Notice.Slow }
        override fun show(notice: Notice) { notices.add(notice) }
    }

    private class TestEnv(
        initialLastSetIndex: Int = 0,
        watcherAvailable: Boolean = true,
        ownTaskExists: () -> Boolean = { true },
        var loading: Boolean = false,
        scope: CoroutineScope? = null,
        homeVisible: () -> Boolean = { false },
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
        var installed = mutableSetOf("youtube", "applemusic", "maps", ownPkg, "com.gt8d.launcher.panel")
        val lastIndex = MemoryLastIndexStore(initialLastSetIndex)
        val store = MemorySplitModeStore()
        var dReady = false
        val selector = SplitModeSelector(store) { dReady }
        val vendor = FakeSplitLauncher()
        val appPair = FakeSplitLauncher()
        val apps = FakeActivityLauncher()
        val scheduler = FakeScheduler()
        val events = FakeForegroundEvents(nowMs = { scheduler.nowMs() }, isAvailable = watcherAvailable)
        val watcher = ForegroundWatcher(events)
        val warm = WarmTracker(ownPkg, ownTaskExists)
        val curtainView = FakeCurtainView()
        val curtain = CurtainController(scheduler, curtainView)
        var startHomeCount = 0
        val messenger = FakeMessenger()
        val labels = mutableMapOf<String, String>()

        val runner = SetRunner(
            SetRunnerDeps(
                config = { if (loading) null else config },
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
                panelHostPkg = "com.gt8d.launcher.panel",
                labelFor = { labels[it] ?: it },
                startHome = { startHomeCount++ },
                messenger = messenger,
                homeVisible = homeVisible,
                scope = scope,
                updateConfig = { f -> config = f(config) },
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
            listOf(SplitCall("youtube", "com.gt8d.launcher.panel", SplitRatio.R6_4)),
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
                SplitCall("youtube", "com.gt8d.launcher.panel", SplitRatio.R6_4),
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
        assertEquals(listOf(Notice.SplitFailed("youtube")), env.messenger.notices)
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
        assertEquals(listOf(Notice.SplitUnsupported("applemusic")), env.messenger.notices)
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
        assertEquals(listOf(Notice.Slow), envAvailable.messenger.notices)

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

    @Test
    fun readyCount_incrementsWhenSplitReady() {
        val env = TestEnv()
        assertEquals(0, env.runner.readyCount)

        env.runner.open(2)
        assertEquals(0, env.runner.readyCount)

        env.events.events.add(ResumeEvent("youtube", 250L))
        env.events.events.add(ResumeEvent("applemusic", 280L))
        env.scheduler.advanceBy(200L)

        assertEquals(0, env.runner.readyCount)
        assertNotNull(env.runner.opening.value)
        env.scheduler.advanceBy(199L)
        assertEquals(0, env.runner.readyCount)
        env.scheduler.advanceBy(1L)
        assertEquals(1, env.runner.readyCount)
        assertNull(env.runner.opening.value)
    }

    @Test
    fun lastReadyAt_setOnReady_clearedOnNextOpen() {
        val env = TestEnv()
        assertNull(env.runner.lastReadyAtMs)
        env.runner.open(2)
        env.events.events.add(ResumeEvent("youtube", 100L))
        env.events.events.add(ResumeEvent("applemusic", 120L))
        env.scheduler.advanceBy(200L)
        assertEquals(200L, env.runner.lastReadyAtMs)

        env.runner.open(1)
        assertNull(env.runner.lastReadyAtMs)
        resumePanelApp(env, "youtube")
        assertEquals(400L, env.runner.lastReadyAtMs)
    }
}
