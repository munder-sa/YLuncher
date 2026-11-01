package com.gt8d.launcher.sets

import com.gt8d.launcher.config.*
import com.gt8d.launcher.split.PanelPlacement
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PanelSwapSaverTest {
    @Test fun redirectPartner_savesOriginalPackage_andDifferentPanelAppSuppressesSave() = runTest {
        for (changed in listOf(false, true)) {
            val source = ScreenSet("redirect", Slot.Panel, Slot.App("local.aibox.gptlive"), SplitRatio.R6_4)
            var cfg = LauncherConfig(screenSets = listOf(source), homeApps = emptyList(), panelApps = emptyList(), dockApps = emptyList(), idleApp = "yt")
            val saver = PanelSwapSaver(this, { cfg }, { f -> cfg = f(cfg) }, {})
            saver.begin(source, "com.openai.chatgpt")
            if (changed) saver.partnerChanged("maps")
            saver.placementChanged(PanelPlacement.Right)
            advanceUntilIdle()
            assertEquals(listOf(if (changed) source else source.swapped()), cfg.screenSets)
        }
    }
    private val original = ScreenSet("s1", Slot.App("yt"), Slot.Panel, SplitRatio.R6_4)
    private val swapped = ScreenSet("s1", Slot.Panel, Slot.App("yt"), SplitRatio.R4_6, null)
    private inner class Env(scope: CoroutineScope) {
        var config = LauncherConfig(screenSets = listOf(original), homeApps = emptyList(),
            panelApps = emptyList(), dockApps = emptyList(), idleApp = "yt")
        var saveDelay = 0L
        var attempts = 0
        var fail = false
        val saved = mutableListOf<ScreenSet>()
        val saver = PanelSwapSaver(scope, { config }, { f ->
            attempts++
            delay(saveDelay)
            if (fail) throw IllegalStateException("write")
            config = f(config)
        }, { saved.add(it) })
        fun begin() = saver.begin(original, "yt")
        fun left() = saver.placementChanged(PanelPlacement.Left)
        fun right() = saver.placementChanged(PanelPlacement.Right)
    }
    private fun TestScope.env() = Env(CoroutineScope(coroutineContext + StandardTestDispatcher(testScheduler)))

    @Test fun swap_settled_savesSwapped() = runTest {
        val e = env(); e.begin(); e.left()
        advanceTimeBy(499); runCurrent(); assertEquals(0, e.attempts)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(swapped), e.config.screenSets); assertEquals(listOf(swapped), e.saved)
    }
    @Test fun swap_beforeSettle_ignored() = runTest {
        val e = env(); e.begin(); e.left(); advanceTimeBy(300); e.right()
        advanceTimeBy(1000); runCurrent(); assertEquals(0, e.attempts)
    }
    @Test fun swap_twice_withSlowSave_convergesToFinal() = runTest {
        val e = env(); e.saveDelay = 2000; e.begin(); e.left()
        advanceTimeBy(500); runCurrent(); assertEquals(1, e.attempts)
        e.right(); advanceUntilIdle()
        assertEquals(listOf(original), e.config.screenSets)
        assertEquals(listOf(swapped, original), e.saved)
    }
    @Test fun noSave_outsideSession() = runTest {
        val e = env(); e.left(); advanceUntilIdle(); assertEquals(0, e.attempts)
        e.begin(); e.saver.end("home"); e.left(); advanceUntilIdle(); assertEquals(0, e.attempts)
    }
    @Test fun noSave_afterEndSafety() = runTest {
        val e = env(); e.begin(); e.saver.end("safety"); e.left(); advanceUntilIdle()
        assertEquals(0, e.attempts)
    }
    @Test fun noSave_afterPartnerChanged() = runTest {
        val e = env(); e.begin(); e.saver.partnerChanged("maps"); e.left(); advanceUntilIdle()
        assertEquals(0, e.attempts)
    }
    @Test fun noSave_whenSetEdited() = runTest {
        val e = env(); e.begin(); e.left()
        val edited = original.copy(ratio = SplitRatio.R5_5)
        e.config = e.config.copy(screenSets = listOf(edited)); advanceUntilIdle()
        e.right(); advanceUntilIdle(); e.left(); advanceUntilIdle()
        assertEquals(listOf(edited), e.config.screenSets); assertEquals(1, e.attempts); assertEquals(0, e.saved.size)
    }
    @Test fun noSave_whenSetDeleted() = runTest {
        val e = env(); e.begin(); e.left(); e.config = e.config.copy(screenSets = emptyList())
        advanceUntilIdle(); e.right(); advanceUntilIdle(); e.left(); advanceUntilIdle()
        assertEquals(emptyList<ScreenSet>(), e.config.screenSets); assertEquals(1, e.attempts)
        assertEquals(0, e.saved.size)
    }
    @Test fun noSave_fullOrNull() = runTest {
        for (p in listOf(PanelPlacement.Full, null)) {
            val e = env(); e.begin(); e.left(); advanceTimeBy(300); e.saver.placementChanged(p)
            advanceUntilIdle(); assertEquals(0, e.attempts)
            e.left(); advanceUntilIdle(); assertEquals(if (p == null) 1 else 0, e.saved.size)
        }
    }
    @Test fun save_usesIdAfterReorder() = runTest {
        val e = env(); val other = original.copy(id = "s2")
        e.config = e.config.copy(screenSets = listOf(original, other)); e.begin(); e.left()
        e.config = e.config.copy(screenSets = listOf(other, original)); advanceUntilIdle()
        assertEquals(listOf(other, swapped), e.config.screenSets)
    }
    @Test fun slowSave_generationGuardsEndBeginAndPartner() = runTest {
        for (action in listOf<(Env) -> Unit>(
            { it.saver.end("home") },
            { it.saver.begin(original.copy(id = "s2"), "yt") },
            { it.saver.partnerChanged("maps") },
        )) {
            val e = env(); e.saveDelay = 2000; e.begin(); e.left()
            advanceTimeBy(500); runCurrent(); assertEquals(1, e.attempts)
            e.saver.placementChanged(null); action(e); advanceUntilIdle()
            assertEquals(listOf(original), e.config.screenSets); assertEquals(0, e.saved.size)
        }
    }
    @Test fun begin_usesLastPlacement() = runTest {
        val e = env(); e.left(); e.begin(); advanceTimeBy(500); runCurrent()
        assertEquals(listOf(swapped), e.saved)
    }
    @Test fun slowSave_returnWaitsRemainingSettle() = runTest {
        val e = env(); e.saveDelay = 2000; e.begin(); e.left()
        advanceTimeBy(2400); runCurrent(); e.right()
        advanceTimeBy(100); runCurrent(); assertEquals(1, e.attempts)
        advanceTimeBy(399); runCurrent(); assertEquals(1, e.attempts)
        advanceTimeBy(1); runCurrent(); assertEquals(2, e.attempts)
        advanceUntilIdle(); assertEquals(listOf(original), e.config.screenSets)
    }
    @Test fun cancellation_doesNotSaveOrNotify() = runTest {
        val job = Job(coroutineContext[Job])
        val e = Env(CoroutineScope(coroutineContext + job + StandardTestDispatcher(testScheduler)))
        e.saveDelay = 2000; e.begin(); e.left(); advanceTimeBy(500); runCurrent()
        job.cancel(); advanceUntilIdle()
        assertEquals(listOf(original), e.config.screenSets); assertEquals(0, e.saved.size)
    }
    @Test fun error_doesNotEscapeOrNotify() = runTest {
        val e = env(); e.fail = true; e.begin(); e.left(); advanceUntilIdle()
        assertEquals(listOf(original), e.config.screenSets); assertEquals(0, e.saved.size)
    }
}
