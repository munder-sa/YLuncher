package com.gt8d.launcher.boot

import com.gt8d.launcher.time.Cancellable
import com.gt8d.launcher.time.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BootPhase { Idle, Countdown, Opening, Done, StayedHome }

class BootSequence(
    private val scheduler: Scheduler,
    private val safetyActive: () -> Boolean,
    private val openLast: () -> Unit,
    private val splitAppeared: () -> Boolean,
    private val goHome: () -> Unit,
    private val record: (String) -> Unit,
) {
    companion object {
        const val COUNTDOWN_MS = 3000L
        const val BOOT_LIMIT_MS = 10000L
        const val POLL_MS = 200L
    }

    private val _phase = MutableStateFlow(BootPhase.Idle)
    val phase: StateFlow<BootPhase> = _phase.asStateFlow()

    private val _secondsLeft = MutableStateFlow(3)
    val secondsLeft: StateFlow<Int> = _secondsLeft.asStateFlow()

    private val activeTimers = mutableListOf<Cancellable>()
    private var cancelledDuringOpening = false
    private var goHomeCalled = false

    fun start() {
        if (_phase.value != BootPhase.Idle) return
        if (safetyActive()) {
            record("boot_skipped_safety")
            _phase.value = BootPhase.StayedHome
            return
        }
        _phase.value = BootPhase.Countdown
        _secondsLeft.value = 3

        activeTimers.add(
            scheduler.schedule(1000L) {
                if (_phase.value == BootPhase.Countdown) {
                    _secondsLeft.value = 2
                }
            },
        )
        activeTimers.add(
            scheduler.schedule(2000L) {
                if (_phase.value == BootPhase.Countdown) {
                    _secondsLeft.value = 1
                }
            },
        )
        activeTimers.add(
            scheduler.schedule(COUNTDOWN_MS) {
                if (_phase.value == BootPhase.Countdown) {
                    performOpen()
                }
            },
        )
    }

    fun openNow() {
        if (_phase.value != BootPhase.Countdown) return
        cancelTimers()
        performOpen()
    }

    fun stayHome() {
        when (_phase.value) {
            BootPhase.Countdown -> {
                cancelTimers()
                record("boot_stay_home")
                _phase.value = BootPhase.StayedHome
            }
            BootPhase.Opening -> {
                if (!cancelledDuringOpening) {
                    cancelledDuringOpening = true
                    record("boot_cancel")
                }
            }
            else -> Unit
        }
    }

    private fun performOpen() {
        cancelTimers()
        _phase.value = BootPhase.Opening
        openLast()

        fun schedulePoll() {
            val timer = scheduler.schedule(POLL_MS) {
                if (_phase.value != BootPhase.Opening) return@schedule
                if (splitAppeared()) {
                    onSplitAppeared()
                } else {
                    schedulePoll()
                }
            }
            activeTimers.add(timer)
        }

        schedulePoll()

        val limitTimer = scheduler.schedule(BOOT_LIMIT_MS) {
            if (_phase.value != BootPhase.Opening) return@schedule
            onTimeout()
        }
        activeTimers.add(limitTimer)
    }

    private fun onSplitAppeared() {
        cancelTimers()
        if (cancelledDuringOpening) {
            callGoHomeOnce()
            _phase.value = BootPhase.StayedHome
        } else {
            _phase.value = BootPhase.Done
        }
    }

    private fun onTimeout() {
        cancelTimers()
        if (cancelledDuringOpening) {
            callGoHomeOnce()
            _phase.value = BootPhase.StayedHome
        } else {
            record("boot_timeout")
            _phase.value = BootPhase.Done
        }
    }

    private fun callGoHomeOnce() {
        if (!goHomeCalled) {
            goHomeCalled = true
            goHome()
        }
    }

    private fun cancelTimers() {
        activeTimers.forEach { it.cancel() }
        activeTimers.clear()
    }
}
