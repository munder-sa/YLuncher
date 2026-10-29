package com.gt8d.launcher.edge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.safety.SafetyGuard

class BootReceiver : BroadcastReceiver() {
    internal var bootModeCheck: () -> Unit = {
        runCatching {
            AppGraph.selector.onBoot(AppGraph.bridge.splitAvailable())
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (SafetyGuard.isFallbackActive(context)) return
        EdgeStripService.ensureRunning(context)
        bootModeCheck()
    }
}
