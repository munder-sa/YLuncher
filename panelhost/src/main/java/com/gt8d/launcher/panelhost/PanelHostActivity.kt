package com.gt8d.launcher.panelhost

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

class PanelHostActivity : Activity() {
    private var panelLaunched = false
    private var hasResumed = false
    private var relaunchOnResume = false
    private var relaunchCount = 0
    private var lastRelaunchAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panelLaunched = savedInstanceState?.getBoolean(KEY_PANEL_LAUNCHED) ?: false
        hasResumed = savedInstanceState?.getBoolean(KEY_HAS_RESUMED) ?: false
        relaunchOnResume = savedInstanceState?.getBoolean(KEY_RELAUNCH_ON_RESUME) ?: false
        relaunchCount = savedInstanceState?.getInt(KEY_RELAUNCH_COUNT) ?: 0

        if (savedInstanceState == null) {
            launchPanel()
        }
    }

    private fun launchPanel() {
        try {
            startActivityForResult(
                Intent().setComponent(
                    ComponentName(
                        "com.gt8d.launcher",
                        "com.gt8d.launcher.ui.panel.PanelActivity",
                    ),
                ),
                REQUEST_PANEL,
            )
            panelLaunched = true
        } catch (exception: Exception) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PANEL && resultCode != RESULT_OK) {
            relaunchOnResume = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (panelLaunched && hasResumed) {
            // 上限は、パネルがすぐ落ちて開き直しが止まらなくなるのを防ぐため。間が空いていれば数え直す(入れ直しのたびに 1 回ずつ使うため)。
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRelaunchAtMs > RELAUNCH_WINDOW_MS) relaunchCount = 0
            if (relaunchOnResume && relaunchCount < MAX_RELAUNCH) {
                lastRelaunchAtMs = now
                relaunchOnResume = false
                relaunchCount++
                android.util.Log.d("GT8D", "[PanelHost] relaunch panel count=$relaunchCount")
                launchPanel()
            } else {
                finish()
            }
        }
        hasResumed = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PANEL_LAUNCHED, panelLaunched)
        outState.putBoolean(KEY_HAS_RESUMED, hasResumed)
        outState.putBoolean(KEY_RELAUNCH_ON_RESUME, relaunchOnResume)
        outState.putInt(KEY_RELAUNCH_COUNT, relaunchCount)
        super.onSaveInstanceState(outState)
    }

    internal companion object {
        const val REQUEST_PANEL = 1
        const val MAX_RELAUNCH = 3
        const val RELAUNCH_WINDOW_MS = 10_000L
        const val KEY_PANEL_LAUNCHED = "panel_launched"
        const val KEY_HAS_RESUMED = "has_resumed"
        const val KEY_RELAUNCH_ON_RESUME = "relaunch_on_resume"
        const val KEY_RELAUNCH_COUNT = "relaunch_count"
    }
}
