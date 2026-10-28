package com.gt8d.launcher.edge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gt8d.launcher.AppGraph

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            EdgeStripService.ensureRunning(context)
            runCatching {
                AppGraph.selector.onBoot(AppGraph.bridge.splitAvailable())
            }
        }
    }
}
