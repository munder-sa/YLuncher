package com.gt8d.launcher.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class UsageLaunch(val pkg: String, val wallMs: Long)

class UsageHistory(
    private val context: Context,
    private val query: ((beginWallMs: Long, endWallMs: Long) -> List<UsageLaunch>)? = null,
) {
    fun available(): Boolean = try {
        context.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    fun launchesSince(sinceWallMs: Long): List<UsageLaunch>? {
        return try {
            if (!available()) null else {
                val end = System.currentTimeMillis()
                val launches = query?.invoke(sinceWallMs, end) ?: run {
                    val manager = context.getSystemService(UsageStatsManager::class.java)
                        ?: return null
                    val events = manager.queryEvents(sinceWallMs, end) ?: return null
                    buildList {
                        val event = UsageEvents.Event()
                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                                val pkg = event.packageName ?: continue
                                add(UsageLaunch(pkg, event.timeStamp))
                            }
                        }
                    }
                }
                launches.filter { it.wallMs >= sinceWallMs && it.wallMs <= end }.sortedBy { it.wallMs }
            }
        } catch (_: Throwable) { null }
    }
}
