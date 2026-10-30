package com.gt8d.launcher.panelhost

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

class PanelHostActivity : Activity() {
    private var panelLaunched = false
    private var hasResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panelLaunched = savedInstanceState?.getBoolean(KEY_PANEL_LAUNCHED) ?: false
        hasResumed = savedInstanceState?.getBoolean(KEY_HAS_RESUMED) ?: false

        if (savedInstanceState == null) {
            try {
                startActivity(
                    Intent().setComponent(
                        ComponentName(
                            "com.gt8d.launcher",
                            "com.gt8d.launcher.ui.panel.PanelActivity",
                        ),
                    ),
                )
                panelLaunched = true
            } catch (exception: Exception) {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (panelLaunched && hasResumed) {
            finish()
        }
        hasResumed = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PANEL_LAUNCHED, panelLaunched)
        outState.putBoolean(KEY_HAS_RESUMED, hasResumed)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val KEY_PANEL_LAUNCHED = "panel_launched"
        const val KEY_HAS_RESUMED = "has_resumed"
    }
}
