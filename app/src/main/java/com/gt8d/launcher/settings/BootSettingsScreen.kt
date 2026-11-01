package com.gt8d.launcher.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@Composable
fun BootSettingsScreen(config: LauncherConfig, onUpdate: ((LauncherConfig) -> LauncherConfig) -> Unit) {
    SettingsAutoSaveNote()
    SettingsSection {
        Column(Modifier.fillMaxWidth().background(Palette.Panel).padding(16.dp)) {
            Text("車との接続を待ってから前回のセットを開く", fontSize = Dims.BodyText, color = Palette.Muted)
            Switch(config.boot.waitForCar, onCheckedChange = {}, enabled = false,
                modifier = Modifier.sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag("boot_wait_car"))
            Text("車での確認の後に使えるようになります", Modifier.testTag("boot_wait_car_note"),
                fontSize = Dims.BodyText, color = Palette.Muted)
        }
    }
}
