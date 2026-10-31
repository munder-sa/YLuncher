package com.gt8d.launcher.ui.panel

sealed interface WifiIndicator {
    data object Off : WifiIndicator
    data object Disconnected : WifiIndicator
    data class Connected(val bars: Int) : WifiIndicator
}

fun wifiIndicatorOf(enabled: Boolean, connected: Boolean, level: Int, maxLevel: Int): WifiIndicator {
    if (!enabled) return WifiIndicator.Off
    if (!connected) return WifiIndicator.Disconnected
    val bars = if (maxLevel <= 0) 0 else
        ((level.coerceIn(0, maxLevel).toLong() * 4 + maxLevel / 2) / maxLevel).toInt()
    return WifiIndicator.Connected(bars.coerceIn(0, 4))
}
