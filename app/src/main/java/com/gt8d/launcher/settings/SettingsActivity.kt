package com.gt8d.launcher.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.ui.home.HomeActivity
import com.gt8d.launcher.ui.theme.LauncherTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme {
                SettingsHost(SettingsActions.forApp(this), onExit = ::finish, onSafetyCleared = {
                    startActivity(Intent(this, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    })
                    finish()
                }, initialRoute = if (intent.getBooleanExtra("open_new_set", false)) SettingsRoute.SetEdit(null, true) else null,
                    onOpenSet = { AppGraph.runner.open(it); finish() })
            }
        }
    }
}
