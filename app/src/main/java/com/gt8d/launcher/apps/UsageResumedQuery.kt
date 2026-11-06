package com.gt8d.launcher.apps

import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery

internal fun usageResumedQuery(beginWallMs: Long, endWallMs: Long): UsageEventsQuery =
    UsageEventsQuery.Builder(beginWallMs, endWallMs)
        .setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED)
        .build()
