package com.gt8d.launcher.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.PanelView
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class DebugPanelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.init(context.applicationContext as Application)
        when {
            intent.hasExtra("launch") -> {
                val pkg = intent.getStringExtra("launch") ?: return
                AppGraph.panelLaunchRequests.tryEmit(pkg)
            }
            intent.hasExtra("status") -> DiagLog.d(
                "DebugPanel",
                "status placement=${AppGraph.panelPlacement.value} view=${AppGraph.latestConfig.value?.panelView}",
            )
            intent.hasExtra("addLeftPanelSet") || intent.hasExtra("removeSet") -> {
                val pkg = intent.getStringExtra("addLeftPanelSet")
                val id = intent.getStringExtra("removeSet")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        var index = 0
                        AppGraph.config.update { config ->
                            if (pkg != null) {
                                val sets = config.screenSets.filterNot { it.id == "scenario-left" } +
                                    ScreenSet("scenario-left", Slot.Panel, Slot.App(pkg), SplitRatio.R4_6)
                                index = sets.size
                                config.copy(screenSets = sets)
                            } else {
                                config.copy(screenSets = config.screenSets.filterNot { it.id == id })
                            }
                        }
                        if (pkg != null) DiagLog.d("DebugPanel", "addLeftPanelSet index=$index")
                        else DiagLog.d("DebugPanel", "removeSet $id")
                    } finally {
                        pending.finish()
                    }
                }
            }
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
            intent.hasExtra("cancel") -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        val opening = AppGraph.runner.opening.value != null
                        DiagLog.d("DebugPanel", "cancel opening=$opening")
                        if (opening) AppGraph.runner.cancelOpening()
                    } finally {
                        pending.finish()
                    }
                }
            }
            intent.hasExtra("open") -> {
                val index = intent.getIntExtra("open", 0)
                val pending = goAsync()
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        if (withTimeoutOrNull(5_000L) { AppGraph.latestConfig.first { it != null } } == null) {
                            DiagLog.d("DebugPanel", "open failed: config not loaded")
                        } else if (AppGraph.runner.opening.value != null) {
                            DiagLog.d("DebugPanel", "open ignored while opening")
                        } else {
                            DiagLog.d("DebugPanel", "open index=$index")
                            AppGraph.runner.open(index)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> DiagLog.d("DebugPanel", "unknown")
        }
    }
}
