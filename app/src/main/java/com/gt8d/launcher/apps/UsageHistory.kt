package com.gt8d.launcher.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class UsageLaunch(val pkg: String, val wallMs: Long)

internal fun usageLaunchOf(pkg: String?, className: String?, timeStamp: Long, ownPkg: String): UsageLaunch? {
    if (pkg == null) return null
    val launchPkg = if (pkg == ownPkg && className == "com.gt8d.launcher.ui.home.HomeActivity") {
        UsageHistory.HOME_MARKER
    } else pkg
    return UsageLaunch(launchPkg, timeStamp)
}

class UsageHistory(
    private val context: Context,
    private val query: ((beginWallMs: Long, endWallMs: Long) -> List<UsageLaunch>)? = null,
) {
    internal var wallClock: () -> Long = System::currentTimeMillis

    fun available(): Boolean = try {
        context.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    fun launchesSince(sinceWallMs: Long): List<UsageLaunch>? {
        return try {
            if (!available()) null else {
                val end = wallClock()
                val launches = query?.invoke(sinceWallMs, end) ?: run {
                    val manager = context.getSystemService(UsageStatsManager::class.java)
                        ?: return null
                    val events = manager.queryEvents(usageResumedQuery(sinceWallMs, end)) ?: return null
                    buildList {
                        val event = UsageEvents.Event()
                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                                val launch = usageLaunchOf(
                                    event.packageName, event.className, event.timeStamp, context.packageName,
                                ) ?: continue
                                add(launch)
                            }
                        }
                    }
                }
                launches.filter { it.wallMs >= sinceWallMs && it.wallMs <= end }.sortedBy { it.wallMs }
            }
        } catch (_: Throwable) { null }
    }

    companion object {
        const val HOME_MARKER = "com.gt8d.launcher#home"
    }
}
