package com.gt8d.launcher.ui.panel

import androidx.compose.ui.graphics.ImageBitmap

data class AppInfo(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val installed: Boolean,
)

enum class MediaAction { Play, Pause, Next, Prev, Favorite }

data class DockStatus(
    val wifiOn: Boolean,
    val bluetoothOn: Boolean?,
    val wifi: WifiIndicator = if (wifiOn) WifiIndicator.Connected(4) else WifiIndicator.Off,
    val cell: CellIndicator = CellIndicator.Unavailable,
)
