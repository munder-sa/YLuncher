package com.gt8d.launcher.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.PanelView
import com.gt8d.launcher.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DebugPanelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context.applicationContext as Application)
        when {
            intent.hasExtra("view") -> {
                val view = when (intent.getStringExtra("view")) {
                    "apps" -> PanelView.Apps
                    "sets" -> PanelView.Sets
                    else -> {
                        DiagLog.d("DebugPanel", "unknown")
                        return
                    }
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AppGraph.config.update { it.copy(panelView = view) }
                        DiagLog.d("DebugPanel", "view=$view saved")
                    } finally {
                        pending.finish()
                    }
                }
            }
            intent.hasExtra("open") -> {
                val index = intent.getIntExtra("open", 0)
                if (AppGraph.runner.opening.value != null) {
                    DiagLog.d("DebugPanel", "open ignored while opening")
                } else {
                    DiagLog.d("DebugPanel", "open index=$index")
                    AppGraph.runner.open(index)
                }
            }
            else -> DiagLog.d("DebugPanel", "unknown")
        }
    }
}
