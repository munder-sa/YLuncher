package com.gt8d.launcher

import android.app.Application
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.safety.SafetyGuard

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SafetyGuard.recordStart(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { SafetyGuard.recordCrash(this) }
            runCatching { DiagLog.d("Crash", "${throwable::class.java.simpleName}: ${throwable.message}") }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
