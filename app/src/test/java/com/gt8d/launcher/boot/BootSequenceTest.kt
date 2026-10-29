package com.gt8d.launcher.boot

import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class BootSequenceTest {

    @Test
    fun timeout_withoutCancel_requestsHomeOnce() {
        val env = TestEnv()
        env.boot.start()
        env.boot.openNow()
        env.scheduler.advanceBy(10000L)
        assertEquals(1, env.homed)
        assertEquals(listOf("boot_timeout"), env.records)
        assertEquals(BootPhase.Done, env.boot.phase.value)
        env.scheduler.advanceBy(20000L)
        assertEquals(1, env.homed)
        assertEquals(listOf("boot_timeout"), env.records)
        assertEquals(1, env.opened)
    }

    @Test
    fun countdown_timeoutAt10000FromStart() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(3000L)
        assertEquals(1, env.opened)
        env.scheduler.advanceBy(6999L)
        assertEquals(BootPhase.Opening, env.boot.phase.value)
        env.scheduler.advanceBy(1L)
        assertEquals(BootPhase.Done, env.boot.phase.value)
        assertEquals(1, env.homed)
    }

    @Test
    fun openNow_doesNotResetStartDeadline() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(500L)
        env.boot.openNow()
        env.scheduler.advanceBy(9499L)
        assertEquals(BootPhase.Opening, env.boot.phase.value)
        env.scheduler.advanceBy(1L)
        assertEquals(BootPhase.Done, env.boot.phase.value)
    }

    @Test
    fun cancelDuringOpening_usesOriginalDeadline() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(3000L)
        env.boot.stayHome()
        env.scheduler.advanceBy(6999L)
        assertEquals(0, env.homed)
        env.scheduler.advanceBy(1L)
        assertEquals(1, env.homed)
        assertEquals(BootPhase.StayedHome, env.boot.phase.value)
        assertEquals(listOf("boot_cancel"), env.records)
    }

    private class TestEnv(
        var safety: Boolean = false,
        var appeared: Boolean = false,
    ) {
        val scheduler = FakeScheduler()
        var opened = 0
        var homed = 0
        val records = mutableListOf<String>()

        val boot = BootSequence(
            scheduler = scheduler,
            safetyActive = { safety },
            openLast = { opened++ },
            splitAppeared = { appeared },
            goHome = { homed++ },
            record = { records.add(it) },
        )
    }

    @Test
    fun countdown_opensAfter3000() {
        val env = TestEnv()
        env.boot.start()
        assertEquals(3, env.boot.secondsLeft.value)

        env.scheduler.advanceBy(1000L)
        assertEquals(2, env.boot.secondsLeft.value)

        env.scheduler.advanceBy(1000L)
        assertEquals(1, env.boot.secondsLeft.value)

        env.scheduler.advanceBy(999L)
        assertEquals(0, env.opened)

        env.scheduler.advanceBy(1L)
        assertEquals(1, env.opened)
        assertEquals(BootPhase.Opening, env.boot.phase.value)
    }

    @Test
    fun openNow_opensImmediately() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(500L)

        env.boot.openNow()
        assertEquals(1, env.opened)
        assertEquals(BootPhase.Opening, env.boot.phase.value)

        env.scheduler.advanceBy(5000L)
        assertEquals(1, env.opened)
    }

    @Test
    fun stayHomeDuringCountdown_neverOpens() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(1000L)

        env.boot.stayHome()
        env.scheduler.advanceBy(20000L)

        assertEquals(0, env.opened)
        assertEquals(0, env.homed)
        assertEquals(BootPhase.StayedHome, env.boot.phase.value)
        assertEquals(listOf("boot_stay_home"), env.records)
    }

    @Test
    fun stayHomeAfterOpen_goesHomeWhenSplitAppears() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(3000L)

        env.boot.stayHome()
        env.scheduler.advanceBy(1000L)
        assertEquals(0, env.homed)

        env.appeared = true
        env.scheduler.advanceBy(200L)
        assertEquals(1, env.homed)
        assertEquals(BootPhase.StayedHome, env.boot.phase.value)
        assertEquals(listOf("boot_cancel"), env.records)

        env.scheduler.advanceBy(20000L)
        assertEquals(1, env.homed)
    }

    @Test
    fun stayHomeAfterOpen_goesHomeAt10000() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(3000L)

        env.boot.stayHome()
        env.scheduler.advanceBy(6999L)
        assertEquals(0, env.homed)

        env.scheduler.advanceBy(1L)
        assertEquals(1, env.homed)
        assertEquals(BootPhase.StayedHome, env.boot.phase.value)
        assertEquals(listOf("boot_cancel"), env.records)
    }

    @Test
    fun bootTimeout_recordsAndStops_withoutFailureCount() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(3000L)

        env.scheduler.advanceBy(6999L)
        assertEquals(emptyList<String>(), env.records)
        assertEquals(BootPhase.Opening, env.boot.phase.value)

        env.scheduler.advanceBy(1L)
        assertEquals(listOf("boot_timeout"), env.records)
        assertEquals(BootPhase.Done, env.boot.phase.value)
        assertEquals(1, env.homed)
    }

    @Test
    fun splitAppears_done() {
        val env = TestEnv()
        env.boot.start()
        env.scheduler.advanceBy(3000L)

        env.appeared = true
        env.scheduler.advanceBy(200L)

        assertEquals(BootPhase.Done, env.boot.phase.value)
        assertEquals(emptyList<String>(), env.records)
        assertEquals(0, env.homed)
    }

    @Test
    fun safetyActive_doesNothing() {
        val env = TestEnv(safety = true)
        env.boot.start()
        env.scheduler.advanceBy(20000L)

        assertEquals(0, env.opened)
        assertEquals(BootPhase.StayedHome, env.boot.phase.value)
        assertEquals(listOf("boot_skipped_safety"), env.records)
    }

    @Test
    fun start_twice_opensOnce() {
        val env = TestEnv()
        env.boot.start()
        env.boot.start()
        env.scheduler.advanceBy(3000L)

        assertEquals(1, env.opened)
    }
}
