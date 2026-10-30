package com.gt8d.launcher.settings

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.ui.theme.Dims

@Composable
fun BootSettingsScreen(config: LauncherConfig, onUpdate: ((LauncherConfig) -> LauncherConfig) -> Unit) {
    Text("車との接続を待ってから前回のセットを開く", fontSize = Dims.BodyText)
    Switch(config.boot.waitForCar, onCheckedChange = {}, enabled = false,
        modifier = Modifier.sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag("boot_wait_car"))
    Text("車での確認の後に使えるようになります", Modifier.testTag("boot_wait_car_note"), fontSize = Dims.BodyText)
}
