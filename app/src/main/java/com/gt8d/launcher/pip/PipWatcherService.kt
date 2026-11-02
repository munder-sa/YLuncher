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

    // SystemUI の PiP の接続が、PiP の窓の root への ACTION_EXPAND を PiP の解除(expandLeavePip)として扱う(AOSP Android 15)。
    fun expand(pkg: String): Boolean = try {
        val root = windows.firstOrNull { it.isInPictureInPictureMode && it.root?.packageName?.toString() == pkg }?.root
        val id = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id
        val advertised = root?.actionList?.any { it.id == id } == true
        val result = advertised && root!!.performAction(id)
        DiagLog.d("PiP", "expand pkg=$pkg root=${root != null} advertised=$advertised result=$result")
        result
    } catch (e: Exception) {
        DiagLog.d("PiP", "expand failed: $e")
        false
    }

    companion object {
        @Volatile var instance: PipWatcherService? = null
    }
}
