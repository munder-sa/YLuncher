package com.gt8d.launcher.curtain

import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurtainControllerTest {

    private class ThrowingShowView : CurtainView {
        var throwNext = true
        val hidden = mutableListOf<HideReason>()
        var progress = 0
        var updates = 0
        var shows = 0
        override fun show(target: CurtainTarget) {
            shows++
            if (throwNext) {
                throwNext = false
                throw IllegalStateException("show failed")
            }
        }
        override fun update(target: CurtainTarget) { updates++ }
        override fun showProgress() { progress++ }
        override fun hide(reason: HideReason) { hidden.add(reason) }
    }

    @Test fun showThrows_clearsStateCancelsTimersAndNotifiesOnce() {
        val scheduler = FakeScheduler()
        val view = ThrowingShowView()
        val controller = CurtainController(scheduler, view)
        val reasons = mutableListOf<HideReason>()
        controller.onHidden = { reasons.add(it) }
        val result = runCatching { controller.show(sampleTarget(), 800, true) }
        assertTrue("show must not throw: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(controller.isShowing)
        assertTrue(reasons.isEmpty())
        controller.update(sampleTarget("セット2"))
        scheduler.advanceBy(3999)
        assertTrue(reasons.isEmpty())
        scheduler.advanceBy(1)
        assertFalse(controller.isShowing)
        assertEquals(listOf(HideReason.Safety), reasons)
        scheduler.advanceBy(5000)
        assertEquals(listOf(HideReason.Safety), reasons)
        assertTrue(view.hidden.isEmpty())
        assertEquals(0, view.updates)
        assertEquals(0, view.progress)
    }

    @Test fun showThrows_readyNotifiesReadyOnce() {
        val scheduler = FakeScheduler()
        val view = ThrowingShowView()
        val controller = CurtainController(scheduler, view)
        val reasons = mutableListOf<HideReason>()
        controller.onHidden = { reasons.add(it) }
        controller.show(sampleTarget(), 800, true)
        controller.ready()
        assertFalse(controller.isShowing)
        assertEquals(listOf(HideReason.Ready), reasons)
        assertTrue(view.hidden.isEmpty())
        scheduler.advanceBy(4000)
        assertEquals(listOf(HideReason.Ready), reasons)
        assertTrue(view.hidden.isEmpty())
    }

    @Test fun showThrows_thenShowAgain_works() {
        val scheduler = FakeScheduler()
        val view = ThrowingShowView()
        val controller = CurtainController(scheduler, view)
        val reasons = mutableListOf<HideReason>()
        controller.onHidden = { reasons.add(it) }
        val result = runCatching { controller.show(sampleTarget(), 800, true) }
        assertTrue("first show must not throw", result.isSuccess)
        controller.fail()
        assertEquals(listOf(HideReason.Failed), reasons)
        assertTrue(view.hidden.isEmpty())
        controller.show(sampleTarget(), 800, true)
        assertEquals(2, view.shows)
        assertTrue(controller.isShowing)
        scheduler.advanceBy(3999)
        assertEquals(listOf(HideReason.Failed), reasons)
        scheduler.advanceBy(1)
        assertFalse(controller.isShowing)
        assertEquals(listOf(HideReason.Failed, HideReason.Safety), reasons)
        assertEquals(listOf(HideReason.Safety), view.hidden)
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

    @Test
    fun onHidden_calledEvenIfViewHideThrows() {
        val scheduler = FakeScheduler()
        val view = object : CurtainView {
            override fun show(target: CurtainTarget) {}
            override fun update(target: CurtainTarget) {}
            override fun showProgress() {}
            override fun hide(reason: HideReason) {
                throw RuntimeException("hide failed")
            }
        }
        val controller = CurtainController(scheduler, view)
        val reasons = mutableListOf<HideReason>()
        controller.onHidden = { reasons.add(it) }

        controller.show(sampleTarget(), expectedMs = 800L, watcherAvailable = false)
        runCatching { controller.ready() }

        assertEquals(listOf(HideReason.Ready), reasons)
    }
}
