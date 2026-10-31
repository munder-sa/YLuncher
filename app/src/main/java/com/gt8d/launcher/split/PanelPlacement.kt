package com.gt8d.launcher.split

enum class PanelPlacement { Left, Right, Full }

fun placementOf(
    windowLeft: Int,
    windowRight: Int,
    screenWidth: Int,
    inMultiWindow: Boolean,
): PanelPlacement? = when {
    windowRight.toLong() - windowLeft <= 0 || screenWidth <= 0 -> null
    !inMultiWindow -> PanelPlacement.Full
    windowLeft <= 0 && windowRight >= screenWidth -> PanelPlacement.Full
    windowLeft <= 0 -> PanelPlacement.Left
    windowRight >= screenWidth -> PanelPlacement.Right
    else -> null
}
