package com.gt8d.launcher.spike

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.LauncherTheme
import com.gt8d.launcher.ui.theme.Palette

class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appCtx = applicationContext
        setContent {
            LauncherTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Palette.Background0)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SpikeButton("① YouTube｜Apple Music") {
                        SpikeActions.run(
                            appCtx,
                            "split",
                            bundleOf(
                                "left" to "com.google.android.youtube",
                                "right" to "com.apple.android.music",
                            ),
                        )
                    }
                    SpikeButton("② YouTube｜Spike パネル") {
                        SpikeActions.run(
                            appCtx,
                            "split",
                            bundleOf(
                                "left" to "com.google.android.youtube",
                                "right" to "PANEL",
                            ),
                        )
                    }
                    SpikeButton("③ 帯を表示") {
                        SpikeActions.run(appCtx, "overlay_on", null)
                    }
                    SpikeButton("帯を消す") {
                        SpikeActions.run(appCtx, "overlay_off", null)
                    }
                    SpikeButton("④ メディア一覧") {
                        SpikeActions.run(appCtx, "media_dump", null)
                    }
                    SpikeButton("⑤ YouTube｜Googleマップ") {
                        SpikeActions.run(
                            appCtx,
                            "split",
                            bundleOf(
                                "left" to "com.google.android.youtube",
                                "right" to "com.google.android.apps.maps",
                            ),
                        )
                    }
                    SpikeButton("⑤ YouTube｜Spike パネル") {
                        SpikeActions.run(
                            appCtx,
                            "split",
                            bundleOf(
                                "left" to "com.google.android.youtube",
                                "right" to "PANEL",
                            ),
                        )
                    }
                    SpikeButton("⑤ HOME へ") {
                        SpikeActions.run(appCtx, "home", null)
                    }
                    SpikeButton("通知アクセス設定を開く") {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        SpikeActions.startSafe(appCtx, "NOTIFICATION_LISTENER_SETTINGS", intent)
                    }
                    SpikeButton("オーバーレイ許可設定を開く") {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${appCtx.packageName}"),
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        SpikeActions.startSafe(appCtx, "MANAGE_OVERLAY_PERMISSION", intent)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpikeButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dims.MinTouch),
    ) {
        Text(
            text = label,
            fontSize = Dims.BodyText,
        )
    }
}
