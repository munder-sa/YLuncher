package com.gt8d.launcher.curtain

import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurtainControllerTest {

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

    private fun sampleTarget(name: String = "セット1"): CurtainTarget = CurtainTarget(
        setName = name,
        left = SlotPreview.App("com.google.android.youtube", "YouTube"),
        right = SlotPreview.Panel,
        ratio = SplitRatio.R6_4,
        position = 0,
        count = 3,
    )

    @Test
    fun safety_hidesAt4000_evenWithoutReady() {
        val scheduler = FakeScheduler()
        val view = FakeCurtainView()
        val controller = CurtainController(scheduler, view)
        val hiddenCallbacks = mutableListOf<HideReason>()
        controller.onHidden = { hiddenCallbacks.add(it) }

        controller.show(sampleTarget(), expectedMs = 3500L, watcherAvailable = true)
        assertTrue(controller.isShowing)

        scheduler.advanceBy(3999L)
        assertTrue(controller.isShowing)
        assertTrue(view.hiddenReasons.isEmpty())
        assertTrue(hiddenCallbacks.isEmpty())

        scheduler.advanceBy(1L)
        assertFalse(controller.isShowing)
        assertEquals(listOf(HideReason.Safety), view.hiddenReasons)
        assertEquals(listOf(HideReason.Safety), hiddenCallbacks)
    }

    @Test
    fun update_doesNotExtendSafety() {
        val scheduler = FakeScheduler()
        val view = FakeCurtainView()
        val controller = CurtainController(scheduler, view)
        val hiddenCallbacks = mutableListOf<HideReason>()
        controller.onHidden = { hiddenCallbacks.add(it) }

        controller.show(sampleTarget("セット1"), expectedMs = 3500L, watcherAvailable = true)
        scheduler.advanceBy(3000L)
        assertTrue(controller.isShowing)

        controller.update(sampleTarget("セット2"))
        assertEquals(1, view.updatedTargets.size)
        assertEquals("セット2", view.updatedTargets.single().setName)

        scheduler.advanceBy(1000L)
        assertFalse(controller.isShowing)
        assertEquals(listOf(HideReason.Safety), view.hiddenReasons)
        assertEquals(listOf(HideReason.Safety), hiddenCallbacks)
    }

    @Test
    fun timedHide_whenWatcherUnavailable_atExpected() {
        val scheduler = FakeScheduler()
        val view = FakeCurtainView()
        val controller = CurtainController(scheduler, view)
        val hiddenCallbacks = mutableListOf<HideReason>()
        controller.onHidden = { hiddenCallbacks.add(it) }

        controller.show(sampleTarget(), expectedMs = 800L, watcherAvailable = false)
        assertTrue(controller.isShowing)

        scheduler.advanceBy(799L)
        assertTrue(controller.isShowing)

        scheduler.advanceBy(1L)
        assertFalse(controller.isShowing)
        assertEquals(listOf(HideReason.Timed), view.hiddenReasons)
        assertEquals(listOf(HideReason.Timed), hiddenCallbacks)
    }

    @Test
    fun noTimedHide_whenWatcherAvailable() {
        val scheduler = FakeScheduler()
        val view = FakeCurtainView()
        val controller = CurtainController(scheduler, view)

        controller.show(sampleTarget(), expectedMs = 800L, watcherAvailable = true)
        scheduler.advanceBy(800L)

        assertTrue(controller.isShowing)
        assertTrue(view.hiddenReasons.isEmpty())
    }

    @Test
    fun ready_hidesImmediately_andCancelsTimers() {
        val scheduler = FakeScheduler()
        val view = FakeCurtainView()
        val controller = CurtainController(scheduler, view)
        val hiddenCallbacks = mutableListOf<HideReason>()
        controller.onHidden = { hiddenCallbacks.add(it) }

        controller.show(sampleTarget(), expectedMs = 800L, watcherAvailable = false)
        controller.ready()

        assertFalse(controller.isShowing)
        assertEquals(listOf(HideReason.Ready), view.hiddenReasons)
        assertEquals(listOf(HideReason.Ready), hiddenCallbacks)

        scheduler.advanceBy(5000L)
        assertEquals(listOf(HideReason.Ready), view.hiddenReasons)
        assertEquals(listOf(HideReason.Ready), hiddenCallbacks)
        assertEquals(0, view.showProgressCount)
    }

    @Test
    fun progress_shownAfter800_only() {
        val scheduler = FakeScheduler()
        val view = FakeCurtainView()
        val controller = CurtainController(scheduler, view)

        controller.show(sampleTarget(), expectedMs = 3500L, watcherAvailable = true)
        scheduler.advanceBy(799L)
        assertEquals(0, view.showProgressCount)

        scheduler.advanceBy(1L)
        assertEquals(1, view.showProgressCount)

        // Check that ready before 800ms prevents showProgress
        val scheduler2 = FakeScheduler()
        val view2 = FakeCurtainView()
        val controller2 = CurtainController(scheduler2, view2)
        controller2.show(sampleTarget(), expectedMs = 3500L, watcherAvailable = true)
        scheduler2.advanceBy(500L)
        controller2.ready()
        scheduler2.advanceBy(1000L)
        assertEquals(0, view2.showProgressCount)
    }

    @Test
    fun onHidden_calledOnceForEveryReason() {
        // 1. Ready
        run {
            val scheduler = FakeScheduler()
            val view = FakeCurtainView()
            val controller = CurtainController(scheduler, view)
            val reasons = mutableListOf<HideReason>()
            controller.onHidden = { reasons.add(it) }
            controller.show(sampleTarget(), expectedMs = 800L, watcherAvailable = false)
            controller.ready()
            controller.ready()
            scheduler.advanceBy(5000L)
            assertEquals(listOf(HideReason.Ready), reasons)
        }

        // 2. Timed
        run {
            val scheduler = FakeScheduler()
            val view = FakeCurtainView()
            val controller = CurtainController(scheduler, view)
            val reasons = mutableListOf<HideReason>()
            controller.onHidden = { reasons.add(it) }
            controller.show(sampleTarget(), expectedMs = 800L, watcherAvailable = false)
            scheduler.advanceBy(5000L)
            controller.ready()
            assertEquals(listOf(HideReason.Timed), reasons)
        }

        // 3. Safety
        run {
            val scheduler = FakeScheduler()
            val view = FakeCurtainView()
            val controller = CurtainController(scheduler, view)
            val reasons = mutableListOf<HideReason>()
            controller.onHidden = { reasons.add(it) }
            controller.show(sampleTarget(), expectedMs = 3500L, watcherAvailable = true)
            scheduler.advanceBy(5000L)
            controller.fail()
            assertEquals(listOf(HideReason.Safety), reasons)
        }

        // 4. Failed
        run {
            val scheduler = FakeScheduler()
            val view = FakeCurtainView()
            val controller = CurtainController(scheduler, view)
            val reasons = mutableListOf<HideReason>()
            controller.onHidden = { reasons.add(it) }
            controller.show(sampleTarget(), expectedMs = 800L, watcherAvailable = false)
            controller.fail()
            controller.fail()
            scheduler.advanceBy(5000L)
            assertEquals(listOf(HideReason.Failed), reasons)
        }
    }
}
