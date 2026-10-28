package com.gt8d.launcher

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.safety.SafetyGuard

class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SafetyGuard.recordStart(this)
        Handler(Looper.getMainLooper()).postDelayed({ SafetyGuard.markStable(this) }, 10_000L)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { SafetyGuard.recordCrash(this) }
            runCatching { DiagLog.d("Crash", "${throwable::class.java.simpleName}: ${throwable.message}") }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
