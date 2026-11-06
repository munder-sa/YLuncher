package com.gt8d.launcher.foreground

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gt8d.launcher.apps.usageResumedQuery
import com.gt8d.launcher.diag.DiagLog
import kotlin.math.abs

class UsageStatsForegroundEvents(
    private val context: Context,
    private val queryResumed: ((Long) -> List<ResumeEvent>)? = null,
) : ForegroundEvents {
    data class RawEvent(val pkg: String, val className: String?, val type: Int, val wallMs: Long)

    var queryRawEvents: ((Long, Long) -> List<RawEvent>)? = null
    var wallClock: () -> Long = System::currentTimeMillis
    var elapsedClock: () -> Long = SystemClock::elapsedRealtime
    private var skewMs = 0L
    private var loggedSkewMs = 0L

    private data class Snapshot(
        val sinceMs: Long,
        val nowWall: Long,
        val nowElapsed: Long,
        val mark: Pair<String, Long>?,
        val skewMs: Long,
        val ownPkg: String,
    ) {
        val wallMinusElapsed = nowWall - nowElapsed
        val begin = minOf(sinceMs, mark?.second ?: sinceMs) + wallMinusElapsed - 3000L
        val end = nowWall + 1000L
    }

    override fun available(): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    override fun resumedSince(sinceMs: Long): List<ResumeEvent> {
        val result = prepareRead(sinceMs).read()
        commitRead(result)
        return result.resumed
    }

    override fun prepareRead(sinceMs: Long): ForegroundRead {
        // Capture all mutable inputs on main. The worker never consults either
        // OwnResumeClock or the current correction/clock/query hooks.
        val directQuery = queryResumed
        if (directQuery != null) return ForegroundRead {
            readSafely { ForegroundReadResult(directQuery(sinceMs).toList()) }
        }
        val snapshot = readSafely {
            Snapshot(
                sinceMs, wallClock(), elapsedClock(),
                OwnResumeClock.latest()?.takeIf { sinceMs - it.second <= MARK_WINDOW_MS },
                skewMs, context.packageName,
            )
        }
        val rawQuery = queryRawEvents
        return ForegroundRead { readSafely { readSnapshot(snapshot, rawQuery) } }
    }

    private fun readSnapshot(snapshot: Snapshot, rawQuery: ((Long, Long) -> List<RawEvent>)?): ForegroundReadResult {
        val events = rawQuery?.invoke(snapshot.begin, snapshot.end) ?: run {
            val manager = context.getSystemService(UsageStatsManager::class.java)
                ?: throw ForegroundEventsUnavailableException()
            val usageEvents = manager.queryEvents(usageResumedQuery(snapshot.begin, snapshot.end))
                ?: throw ForegroundEventsUnavailableException()
            buildList {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val pkg = event.packageName ?: continue
                    add(RawEvent(pkg, event.className, event.eventType, event.timeStamp))
                }
            }
        }
        val candidate = snapshot.mark?.let { mark ->
            events.asSequence()
                .filter { it.type == UsageEvents.Event.ACTIVITY_RESUMED &&
                    it.pkg == snapshot.ownPkg && it.className == mark.first }
                .map { it.wallMs - snapshot.wallMinusElapsed - mark.second }
                .filter { abs(it) <= 3000L }
                .minByOrNull { abs(it) }
        }
        val correction = candidate ?: snapshot.skewMs
        val resumed = events.filter { it.type == UsageEvents.Event.ACTIVITY_RESUMED }
            .map { ResumeEvent(it.pkg, it.wallMs - snapshot.wallMinusElapsed - correction) }
            .filter { it.timeMs >= snapshot.sinceMs }
        return ForegroundReadResult(resumed, candidate)
    }

    override fun commitRead(result: ForegroundReadResult) {
        // Only accepted results reach this main-thread state/log boundary.
        val candidate = result.skewMs ?: return
        skewMs = candidate
        if (abs(skewMs - loggedSkewMs) >= 100L) {
            DiagLog.d("Foreground", "skew=$skewMs")
            loggedSkewMs = skewMs
        }
    }

    private inline fun <T> readSafely(read: () -> T): T {
        return try {
            read()
        } catch (error: ForegroundEventsUnavailableException) {
            throw error
        } catch (cause: Throwable) {
            throw ForegroundEventsUnavailableException(cause)
        }
    }

    private companion object {
        const val MARK_WINDOW_MS = 60_000L
    }
}
