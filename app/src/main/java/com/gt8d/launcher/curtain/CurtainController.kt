package com.gt8d.launcher.curtain

import com.gt8d.launcher.config.SplitRatio
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
)

enum class HideReason {
    Ready,
    Timed,
    Safety,
    Failed,
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

    fun show(target: CurtainTarget, expectedMs: Long, watcherAvailable: Boolean) {
        if (isShowing) {
            update(target)
            return
        }
        isShowing = true
        view.show(target)
        timers.add(
            scheduler.schedule(SAFETY_MS) {
                hide(HideReason.Safety)
            },
        )
        if (!watcherAvailable) {
            timers.add(
                scheduler.schedule(expectedMs) {
                    hide(HideReason.Timed)
                },
            )
        }
        timers.add(
            scheduler.schedule(PROGRESS_AFTER_MS) {
                if (isShowing) {
                    view.showProgress()
                }
            },
        )
    }

    fun update(target: CurtainTarget) {
        if (!isShowing) return
        view.update(target)
    }

    fun ready() {
        hide(HideReason.Ready)
    }

    fun fail() {
        hide(HideReason.Failed)
    }

    private fun hide(reason: HideReason) {
        if (!isShowing) return
        isShowing = false
        timers.forEach { it.cancel() }
        timers.clear()
        try {
            view.hide(reason)
        } finally {
            onHidden?.invoke(reason)
        }
    }
}
