package com.gt8d.launcher.ui.panel

import com.gt8d.launcher.sets.Opening
import com.gt8d.launcher.time.Cancellable
import com.gt8d.launcher.time.Scheduler

class PanelForwarder(
    private val opening: () -> Opening?,
    private val scheduler: Scheduler,
) {
    companion object {
        const val NO_FLAG_WAIT_MS = 1500L
    }

    private var showing: Boolean = false
    private var inMultiWindow: Boolean = false
    private var forwardAction: (() -> Unit)? = null
    private var forwarded: Boolean = false
    private var timer: Cancellable? = null

    fun onShown(inMultiWindow: Boolean, forward: () -> Unit) {
        cancelTimer()
        showing = true
        this.inMultiWindow = inMultiWindow
        this.forwardAction = forward
        this.forwarded = false
        evaluate()
    }

    fun onMultiWindowChanged(inMultiWindow: Boolean) {
        this.inMultiWindow = inMultiWindow
        if (inMultiWindow) {
            cancelTimer()
        } else {
            evaluate()
        }
    }

    fun onOpeningCleared() {
        if (showing && !inMultiWindow && !forwarded && opening() == null) {
            cancelTimer()
            triggerForward()
        }
    }

    fun onHidden() {
        cancelTimer()
        showing = false
        forwardAction = null
    }

    private fun evaluate() {
        cancelTimer()
        if (!showing || inMultiWindow || forwarded) return
        if (opening() != null) return
        timer = scheduler.schedule(NO_FLAG_WAIT_MS) {
            if (showing && !inMultiWindow && !forwarded && opening() == null) {
                triggerForward()
            }
        }
    }

    private fun triggerForward() {
        if (forwarded) return
        forwarded = true
        cancelTimer()
        forwardAction?.invoke()
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }
}
