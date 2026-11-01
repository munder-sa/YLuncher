package com.gt8d.launcher.curtain

import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.time.Cancellable
import com.gt8d.launcher.time.Scheduler

sealed interface SlotPreview {
    data class App(val pkg: String, val label: String) : SlotPreview
    data object Panel : SlotPreview
}

data class CurtainTarget(
    val setName: String,
    val left: SlotPreview,
    val right: SlotPreview,
    val ratio: SplitRatio,
    val position: Int,
    val count: Int,
    val cancellable: Boolean = true,
)

enum class HideReason {
    Ready,
    Timed,
    Safety,
    Failed,
    Cancelled,
}

interface CurtainView {
    fun show(target: CurtainTarget)
    fun update(target: CurtainTarget)
    fun showProgress()
    fun hide(reason: HideReason)
}

class CurtainController(
    private val scheduler: Scheduler,
    private val view: CurtainView,
) {
    companion object {
        const val SAFETY_MS = 4000L
        const val PROGRESS_AFTER_MS = 800L
    }

    var isShowing: Boolean = false
        private set

    var onHidden: ((HideReason) -> Unit)? = null

    private val timers = mutableListOf<Cancellable>()
    private var viewFailed = false
    private var preparing = false
    private var generation = 0L
    private var safetyTimer: Cancellable? = null

    fun showPreparing(target: CurtainTarget) {
        showStage(target, 0L, true, true)
    }

    fun beginWaiting(expectedMs: Long, watcherAvailable: Boolean) {
        if (!isShowing || !preparing) return
        preparing = false
        safetyTimer?.cancel()
        scheduleWaiting(expectedMs, watcherAvailable)
    }

    fun show(target: CurtainTarget, expectedMs: Long, watcherAvailable: Boolean) {
        showStage(target, expectedMs, watcherAvailable, false)
    }

    private fun scheduleWaiting(expectedMs: Long, watcherAvailable: Boolean) {
        val id = generation
        safetyTimer = scheduler.schedule(SAFETY_MS) {
            if (id == generation && !preparing) hide(HideReason.Safety)
        }.also { timers.add(it) }
        if (!watcherAvailable) {
            timers.add(scheduler.schedule(expectedMs) {
                if (id == generation && !preparing) hide(HideReason.Timed)
            })
        }
    }

    private fun showStage(target: CurtainTarget, expectedMs: Long, watcherAvailable: Boolean, preparation: Boolean) {
        if (isShowing) {
            update(target)
            return
        }
        isShowing = true
        preparing = preparation
        val id = ++generation
        try {
            view.show(target)
        } catch (e: Exception) {
            DiagLog.d("Curtain", "show failed: $e")
            viewFailed = true
        }
        if (id != generation || !isShowing) return
        if (preparation) {
            safetyTimer = scheduler.schedule(5500L) {
                if (id == generation && preparing) hide(HideReason.Safety)
            }.also { timers.add(it) }
        } else scheduleWaiting(expectedMs, watcherAvailable)
        timers.add(
            scheduler.schedule(PROGRESS_AFTER_MS) {
                if (id == generation && isShowing && !viewFailed) {
                    view.showProgress()
                }
            },
        )
    }

    fun update(target: CurtainTarget) {
        if (!isShowing || viewFailed) return
        view.update(target)
    }

    fun ready() {
        hide(HideReason.Ready)
    }

    fun fail() {
        hide(HideReason.Failed)
    }

    fun cancel() {
        hide(HideReason.Cancelled)
    }

    private fun hide(reason: HideReason) {
        if (!isShowing) return
        isShowing = false
        preparing = false
        generation++
        timers.forEach { it.cancel() }
        timers.clear()
        try {
            if (!viewFailed) view.hide(reason)
        } finally {
            viewFailed = false
            onHidden?.invoke(reason)
        }
    }
}
