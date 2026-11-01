package com.gt8d.launcher.apps

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

fun settingsComponent(pm: PackageManager): ComponentName? {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage("com.suding.settings")
    val activity = pm.queryIntentActivities(launcher, 0).firstOrNull()?.activityInfo
        ?: pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo
        ?: return null
    return ComponentName(activity.packageName, activity.name)
}
