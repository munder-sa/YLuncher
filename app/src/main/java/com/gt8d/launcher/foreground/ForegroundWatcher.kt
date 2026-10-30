package com.gt8d.launcher.foreground

import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.OwnPackages
import java.util.concurrent.ConcurrentHashMap

data class ResumeEvent(val pkg: String, val timeMs: Long)

class ForegroundEventsUnavailableException(cause: Throwable? = null) : RuntimeException(cause)

interface ForegroundEvents {
    fun available(): Boolean
    fun resumedSince(sinceMs: Long): List<ResumeEvent>
}

class ForegroundWatcher(private val events: ForegroundEvents) {
    private var lastQuerySucceeded: Boolean = true

    private fun readResumed(sinceMs: Long): List<ResumeEvent>? {
        if (!events.available()) {
            lastQuerySucceeded = false
            return null
        }
        return try {
            events.resumedSince(sinceMs).also { lastQuerySucceeded = true }
        } catch (_: ForegroundEventsUnavailableException) {
            lastQuerySucceeded = false
            null
        }
    }

    fun foregroundPkgsSince(sinceMs: Long): Set<String> {
        return (readResumed(sinceMs) ?: return emptySet())
            .filter { it.timeMs >= sinceMs }
            .mapTo(HashSet()) { it.pkg }
    }

    fun available(): Boolean = events.available() && lastQuerySucceeded

    fun bothResumed(left: String, right: String, sinceMs: Long): Boolean {
        val resumed = (readResumed(sinceMs) ?: return false)
            .filter { it.timeMs >= sinceMs }
            .mapTo(HashSet()) { it.pkg }
        return left in resumed && right in resumed
    }

    fun lastForegroundSince(sinceMs: Long): String? {
        return (readResumed(sinceMs) ?: return null)
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

    fun recordAppLaunch(pkg: String, succeeded: Boolean) {
        if (succeeded) markForeground(pkg)
    }

    fun isWarm(pkg: String): Boolean {
        if (pkg == ownPkg) return ownTaskExists()
        if (pkg == OwnPackages.PANEL_HOST) return AppGraph.panelAlive
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
