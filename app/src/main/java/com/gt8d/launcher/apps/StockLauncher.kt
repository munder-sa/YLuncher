package com.gt8d.launcher.apps

import android.content.ComponentName
import android.content.Intent

fun stockLauncherIntent(): Intent {
    return Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(
            "com.android.launcher3",
            "com.android.launcher3.uioverrides.QuickstepLauncher",
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
