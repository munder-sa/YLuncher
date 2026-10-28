package com.gt8d.launcher.spike

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SpikePanelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "PANEL",
                    color = Color.White,
                    fontSize = 96.sp,
                )
                PanelButton("Maps 隣へ(NEW_TASK+ADJACENT+MULTIPLE_TASK)") {
                    launchFromPanel(
                        name = "Maps 隣へ(NEW_TASK+ADJACENT+MULTIPLE_TASK)",
                        pkg = "com.google.android.apps.maps",
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                    )
                }
                PanelButton("Maps 隣へ(NEW_TASK+ADJACENT)") {
                    launchFromPanel(
                        name = "Maps 隣へ(NEW_TASK+ADJACENT)",
                        pkg = "com.google.android.apps.maps",
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT,
                    )
                }
                PanelButton("Apple Music 隣へ(NEW_TASK+ADJACENT+MULTIPLE_TASK)") {
                    launchFromPanel(
                        name = "Apple Music 隣へ(NEW_TASK+ADJACENT+MULTIPLE_TASK)",
                        pkg = "com.apple.android.music",
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                    )
                }
            }
        }
    }

    private fun launchFromPanel(name: String, pkg: String, flags: Int) {
        val intent = this.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "FAIL not-installed $pkg")
            return
        }
        intent.addFlags(flags)
        val flagsHex = "0x${Integer.toHexString(intent.flags)}"
        try {
            this.startActivity(intent)
            Log.i(TAG, "PANEL-BTN $name flags=$flagsHex -> ok")
        } catch (e: Exception) {
            Log.w(TAG, "PANEL-BTN $name flags=$flagsHex -> FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GT8D-SPIKE"
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minWidth = 72.dp, minHeight = 72.dp),
    ) {
        Text(
            text = label,
            fontSize = 20.sp,
        )
    }
}
