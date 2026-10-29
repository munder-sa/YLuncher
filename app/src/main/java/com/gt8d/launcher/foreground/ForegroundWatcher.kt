package com.gt8d.launcher.foreground

import java.util.concurrent.ConcurrentHashMap

data class ResumeEvent(val pkg: String, val timeMs: Long)

interface ForegroundEvents {
    fun available(): Boolean
    fun resumedSince(sinceMs: Long): List<ResumeEvent>
}

class ForegroundWatcher(private val events: ForegroundEvents) {
    fun foregroundPkgsSince(sinceMs: Long): Set<String> {
        if (!events.available()) return emptySet()
        return events.resumedSince(sinceMs)
            .filter { it.timeMs >= sinceMs }
            .mapTo(HashSet()) { it.pkg }
    }

    fun available(): Boolean = events.available()

    fun bothResumed(left: String, right: String, sinceMs: Long): Boolean {
        if (!events.available()) return false
        val resumed = events.resumedSince(sinceMs)
            .filter { it.timeMs >= sinceMs }
            .mapTo(HashSet()) { it.pkg }
        return left in resumed && right in resumed
    }

    fun lastForegroundSince(sinceMs: Long): String? {
        if (!events.available()) return null
        return events.resumedSince(sinceMs)
            .filter { it.timeMs >= sinceMs }
            .maxByOrNull { it.timeMs }
            ?.pkg
    }
}

class WarmTracker(
    private val ownPkg: String,
    private val ownTaskExists: () -> Boolean,
) {
    private val warmPkgs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun markForeground(pkg: String) {
        warmPkgs.add(pkg)
    }

    fun isWarm(pkg: String): Boolean {
        if (pkg == ownPkg) return ownTaskExists()
        return pkg in warmPkgs
    }

    fun expectedDelayMs(left: String, right: String): Long {
        return if (isWarm(left) && isWarm(right)) WARM_MS else COLD_MS
    }

    companion object {
        const val WARM_MS = 800L
        const val COLD_MS = 3500L
    }
}
