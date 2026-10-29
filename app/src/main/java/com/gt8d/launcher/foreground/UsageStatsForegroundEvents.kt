package com.gt8d.launcher.foreground

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.SystemClock

class UsageStatsForegroundEvents(
    private val context: Context,
    private val queryResumed: ((Long) -> List<ResumeEvent>)? = null,
) : ForegroundEvents {

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
            val manager = context.getSystemService(UsageStatsManager::class.java)
                ?: throw ForegroundEventsUnavailableException()
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val wallMinusElapsed = nowWall - nowElapsed
            val begin = nowWall - (nowElapsed - sinceMs) - 1000L
            val end = nowWall + 1000L
            val usageEvents = manager.queryEvents(begin, end) ?: throw ForegroundEventsUnavailableException()
            val result = mutableListOf<ResumeEvent>()
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val pkg = event.packageName ?: continue
                    val timeMs = event.timeStamp - wallMinusElapsed
                    if (timeMs >= sinceMs) {
                        result.add(ResumeEvent(pkg = pkg, timeMs = timeMs))
                    }
                }
            }
            result
        } catch (error: ForegroundEventsUnavailableException) {
            throw error
        } catch (cause: Throwable) {
            throw ForegroundEventsUnavailableException(cause)
        }
    }
}
