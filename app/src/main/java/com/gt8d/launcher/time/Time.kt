package com.gt8d.launcher.time

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

fun interface Clock {
    fun nowMs(): Long
}

fun interface Cancellable {
    fun cancel()
}

interface Scheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

class MainThreadScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

object ElapsedClock : Clock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
