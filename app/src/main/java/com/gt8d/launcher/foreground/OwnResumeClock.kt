package com.gt8d.launcher.foreground

import android.os.SystemClock

object OwnResumeClock {
    private val marks = mutableMapOf<String, Long>()

    @Synchronized
    fun mark(className: String, elapsedMs: Long = SystemClock.elapsedRealtime()) {
        marks[className] = elapsedMs
    }

    @Synchronized
    fun latest(): Pair<String, Long>? = marks.maxByOrNull { it.value }?.toPair()

    @Synchronized
    fun reset() {
        marks.clear()
    }
}
