package com.gt8d.launcher.pip

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.gt8d.launcher.diag.DiagLog

class PipWatcherService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun pipPackages(): Set<String> = try {
        windows.filter { it.isInPictureInPictureMode }
            .mapNotNull { it.root?.packageName?.toString() }.toSet()
    } catch (e: Exception) {
        DiagLog.d("PiP", "windows failed: $e")
        emptySet()
    }

    companion object {
        @Volatile var instance: PipWatcherService? = null
    }
}
