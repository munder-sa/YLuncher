package com.gt8d.launcher.pip

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

fun interface PipDetector {
    fun isInPip(pkg: String): Boolean
    // PiP を解除する(ユーザー補助の ACTION_EXPAND)。送れたら true。解除の完了は isInPip で確かめる。
    fun expand(pkg: String): Boolean = false
}

object AndroidPipDetector : PipDetector {
    override fun isInPip(pkg: String): Boolean =
        PipWatcherService.instance?.pipPackages()?.contains(pkg) == true
    override fun expand(pkg: String): Boolean = PipWatcherService.instance?.expand(pkg) == true
}

fun pipWatcherEnabled(context: Context): Boolean {
    val own = ComponentName(context, PipWatcherService::class.java)
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == own } == true
}
