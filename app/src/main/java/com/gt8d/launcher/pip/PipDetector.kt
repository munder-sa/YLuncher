package com.gt8d.launcher.pip

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

fun interface PipDetector { fun isInPip(pkg: String): Boolean }

object AndroidPipDetector : PipDetector {
    override fun isInPip(pkg: String): Boolean =
        PipWatcherService.instance?.pipPackages()?.contains(pkg) == true
}

fun pipWatcherEnabled(context: Context): Boolean {
    val own = ComponentName(context, PipWatcherService::class.java)
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == own } == true
}
