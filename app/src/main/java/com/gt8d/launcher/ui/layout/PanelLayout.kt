package com.gt8d.launcher.ui.layout

import kotlin.math.floor

object PanelLayout {
    const val CELL_DP = 88f
    const val GAP_DP = 8f
    const val DOCK_DP = 88f
    const val HOME_SPLIT_MIN_DP = 840f

    fun homePlayerWidthDp(totalDp: Float): Float = (totalDp / 3f).coerceIn(360f, 600f)

    fun homeSetColumns(leftAreaDp: Float): Int = if (leftAreaDp >= 624f) 2 else 1

    fun columns(availableDp: Float): Int =
        floor((availableDp + GAP_DP) / (CELL_DP + GAP_DP)).toInt().coerceAtLeast(1)

    fun controlRows(availableDp: Float, buttons: Int): Int =
        if (buttons * 72f + (buttons - 1).coerceAtLeast(0) * GAP_DP <= availableDp) 1 else 2

    fun homePanelWidthDp(totalDp: Float): Float? =
        if (totalDp >= HOME_SPLIT_MIN_DP) (totalDp * 0.45f).coerceIn(516f, 640f) else null
}
