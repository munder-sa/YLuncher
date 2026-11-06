package com.gt8d.launcher.foreground

import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.OwnPackages
import java.util.concurrent.ConcurrentHashMap

data class ResumeEvent(val pkg: String, val timeMs: Long)

class ForegroundEventsUnavailableException(cause: Throwable? = null) : RuntimeException(cause)

fun interface ForegroundRead {
    fun read(): ForegroundReadResult
}

data class ForegroundReadResult(val resumed: List<ResumeEvent>, val skewMs: Long? = null)

interface ForegroundEvents {
    fun available(): Boolean
    fun resumedSince(sinceMs: Long): List<ResumeEvent>

    // Legacy stateless fakes can keep implementing only resumedSince(). Production
    // captures its clocks/correction on main and overrides this with a pure read.
    fun prepareRead(sinceMs: Long): ForegroundRead =
        ForegroundRead { ForegroundReadResult(resumedSince(sinceMs).toList()) }

    fun commitRead(result: ForegroundReadResult) {}
}

class ForegroundWatcher(
    private val events: ForegroundEvents,
    val poll: ForegroundPoll = ForegroundPoll.Immediate,
) {
    // Preparation and commitment belong to main, including synchronous callers
    // (retry, Safety, warmup, LinkEntry). Only PreparedRead.read() runs on IO.
    private var lastQuerySucceeded: Boolean = true
    private var latestRequest = 0L
    private var committedRequest = 0L

    class PreparedRead internal constructor(
        private val owner: ForegroundWatcher,
        private val request: Long,
        private val read: ForegroundRead,
    ) {
        fun read(): ReadResult {
            val value = try {
                read.read()
            } catch (_: ForegroundEventsUnavailableException) {
                null
            }
            return ReadResult(owner, request, value)
        }
    }

    class ReadResult internal constructor(
        internal val owner: ForegroundWatcher,
        internal val request: Long,
        internal val value: ForegroundReadResult?,
    ) {
        // null is a failed query; an empty list is a successful query.
        val resumed: List<ResumeEvent>? get() = value?.resumed
    }

    fun prepareRead(sinceMs: Long): PreparedRead {
        val request = ++latestRequest
        val read = try {
            if (events.available()) events.prepareRead(sinceMs) else ForegroundRead {
                throw ForegroundEventsUnavailableException()
            }
        } catch (error: ForegroundEventsUnavailableException) {
            ForegroundRead { throw error }
        }
        return PreparedRead(this, request, read)
    }

    // The operation/permission guard must pass on main before calling this.
    // Merely checking available() never issues or invalidates a request.
    fun commitRead(result: ReadResult): Boolean {
        if (result.owner !== this || result.request != latestRequest || result.request <= committedRequest) return false
        committedRequest = result.request
        lastQuerySucceeded = result.value != null
        result.value?.let(events::commitRead)
        return true
    }

    private fun readResumed(sinceMs: Long): List<ResumeEvent>? {
        val result = prepareRead(sinceMs).read()
        return if (commitRead(result)) result.resumed else null
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
