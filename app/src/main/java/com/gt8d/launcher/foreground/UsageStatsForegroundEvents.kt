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
        return try {
            if (queryResumed != null) return queryResumed.invoke(sinceMs)
            val nowWall = wallClock()
            val nowElapsed = elapsedClock()
            val wallMinusElapsed = nowWall - nowElapsed
            // 古い目印で問い合わせの範囲を広げすぎない(直前 60 秒以内だけ使い、無ければ前の補正値を使う)
            val mark = OwnResumeClock.latest()?.takeIf { sinceMs - it.second <= MARK_WINDOW_MS }
            val begin = minOf(sinceMs, mark?.second ?: sinceMs) + wallMinusElapsed - 3000L
            val end = nowWall + 1000L
            val events = queryRawEvents?.invoke(begin, end) ?: run {
                val manager = context.getSystemService(UsageStatsManager::class.java)
                    ?: throw ForegroundEventsUnavailableException()
                val usageEvents = manager.queryEvents(usageResumedQuery(begin, end))
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
            if (mark != null) {
                val candidate = events.asSequence()
                    .filter { it.type == UsageEvents.Event.ACTIVITY_RESUMED &&
                        it.pkg == context.packageName && it.className == mark.first }
                    .map { it.wallMs - wallMinusElapsed - mark.second }
                    .filter { abs(it) <= 3000L }
                    .minByOrNull { abs(it) }
                if (candidate != null) {
                    skewMs = candidate
                    if (abs(skewMs - loggedSkewMs) >= 100L) {
                        DiagLog.d("Foreground", "skew=$skewMs")
                        loggedSkewMs = skewMs
                    }
                }
            }
            events.filter { it.type == UsageEvents.Event.ACTIVITY_RESUMED }
                .map { ResumeEvent(it.pkg, it.wallMs - wallMinusElapsed - skewMs) }
                .filter { it.timeMs >= sinceMs }
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
