package com.gt8d.launcher.ui.panel

import com.gt8d.launcher.sets.Opening
import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelForwarderTest {

    @Test
    fun forwarder_openingStartsDuringWait_suppressesTimerThenForwardsOnClear() {
        val scheduler = FakeScheduler()
        var opening: Opening? = null
        var forwarded = 0
        val forwarder = PanelForwarder({ opening }, scheduler)
        forwarder.onShown(false) { forwarded++ }
        scheduler.advanceBy(1000L)
        opening = Opening("a", "b", 1000L)
        scheduler.advanceBy(500L)
        assertEquals(0, forwarded)
        scheduler.advanceBy(5000L)
        assertEquals(0, forwarded)
        opening = null
        forwarder.onOpeningCleared()
        assertEquals(1, forwarded)
        forwarder.onOpeningCleared()
        scheduler.advanceBy(5000L)
        assertEquals(1, forwarded)
    }

    @Test
    fun forwarder_staleClearWhileOpening_doesNotForward() {
        val scheduler = FakeScheduler()
        var opening: Opening? = Opening("a", "b", 1000L)
        var forwarded = 0
        val forwarder = PanelForwarder({ opening }, scheduler)
        forwarder.onShown(false) { forwarded++ }
        forwarder.onOpeningCleared()
        assertEquals(0, forwarded)
        opening = null
        forwarder.onOpeningCleared()
        assertEquals(1, forwarded)
    }

    @Test
    fun forwarder_openingStartsDuringWait_thenHidden_neverForwards() {
        val scheduler = FakeScheduler()
        var opening: Opening? = null
        var forwarded = 0
        val forwarder = PanelForwarder({ opening }, scheduler)
        forwarder.onShown(false) { forwarded++ }
        scheduler.advanceBy(1000L)
        opening = Opening("a", "b", 1000L)
        forwarder.onHidden()
        scheduler.advanceBy(5500L)
        opening = null
        forwarder.onOpeningCleared()
        assertEquals(0, forwarded)
    }

    @Test
    fun forwarder_noForwardWhileOpening() {
        val scheduler = FakeScheduler()
        var currentOpening: Opening? = Opening("a", "b", 100L)
        val forwarder = PanelForwarder(opening = { currentOpening }, scheduler = scheduler)
        var forwarded = 0

        forwarder.onShown(inMultiWindow = false) { forwarded++ }
        scheduler.advanceBy(10000L)

        assertEquals(0, forwarded)
    }

    @Test
    fun forwarder_forwardAfterOpeningClearedIfOutside() {
        val scheduler = FakeScheduler()
        var currentOpening: Opening? = Opening("a", "b", 100L)
        val forwarder = PanelForwarder(opening = { currentOpening }, scheduler = scheduler)
        var forwarded = 0

        forwarder.onShown(inMultiWindow = false) { forwarded++ }
        scheduler.advanceBy(10000L)
        assertEquals(0, forwarded)

        currentOpening = null
        forwarder.onOpeningCleared()

        assertEquals(1, forwarded)
    }

    @Test
    fun forwarder_noFlag_waits1500_thenForward() {
        val scheduler = FakeScheduler()
        val forwarder = PanelForwarder(opening = { null }, scheduler = scheduler)
        var forwarded = 0

        forwarder.onShown(inMultiWindow = false) { forwarded++ }
        scheduler.advanceBy(1499L)
        assertEquals(0, forwarded)

        scheduler.advanceBy(1L)
        assertEquals(1, forwarded)
    }

    @Test
    fun forwarder_enteringSplitWithin1500_cancels() {
        val scheduler = FakeScheduler()
        val forwarder = PanelForwarder(opening = { null }, scheduler = scheduler)
        var forwarded = 0

        forwarder.onShown(inMultiWindow = false) { forwarded++ }
        scheduler.advanceBy(1000L)
        forwarder.onMultiWindowChanged(true)
        scheduler.advanceBy(1000L)

        assertEquals(0, forwarded)
    }

    @Test
    fun forwarder_inSplit_neverForwards() {
        val scheduler = FakeScheduler()
        var currentOpening: Opening? = Opening("a", "b", 100L)
        val forwarder = PanelForwarder(opening = { currentOpening }, scheduler = scheduler)
        var forwarded = 0

        forwarder.onShown(inMultiWindow = true) { forwarded++ }
        currentOpening = null
        forwarder.onOpeningCleared()
        scheduler.advanceBy(5000L)

        assertEquals(0, forwarded)
    }

    @Test
    fun forwarder_atMostOncePerShow() {
        val scheduler = FakeScheduler()
        val forwarder = PanelForwarder(opening = { null }, scheduler = scheduler)
        var forwarded = 0

        forwarder.onShown(inMultiWindow = false) { forwarded++ }
        scheduler.advanceBy(1500L)
        assertEquals(1, forwarded)

        forwarder.onOpeningCleared()
        assertEquals(1, forwarded)
    }
}
