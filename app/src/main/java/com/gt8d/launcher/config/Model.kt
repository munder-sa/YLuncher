package com.gt8d.launcher.config

import kotlinx.serialization.Serializable

const val CONFIG_VERSION = 2
const val MAX_PANEL_APPS = 5
const val MAX_DOCK_APPS = 4

@Serializable
enum class SplitRatio(val label: String, val vendorValue: String) {
    R3_7("3:7", "3:7"),
    R4_6("4:6", "2:3"),
    R5_5("5:5", "1:1"),
    R6_4("6:4", "3:2"),
    R7_3("7:3", "7:3");

    companion object {
        val forPanel: List<SplitRatio> = listOf(R4_6, R5_5, R6_4)
    }
}

fun SplitRatio.allowedFor(right: Slot): Boolean = when (right) {
    is Slot.Panel -> this in SplitRatio.forPanel
    is Slot.App -> true
}

@Serializable
data class BootConfig(
    val waitForCar: Boolean = false,
)

@Serializable
data class LauncherConfig(
    val version: Int = CONFIG_VERSION,
    val screenSets: List<ScreenSet>,
    val homeApps: List<String>,
    val panelApps: List<String>,
    val dockApps: List<String>,
    val idleApp: String,
    val link: LinkConfig = LinkConfig(),
    val gesture: GestureConfig = GestureConfig(),
    val boot: BootConfig = BootConfig(),
)

@Serializable
data class ScreenSet(
    val id: String,
    val left: Slot.App,
    val right: Slot,
    val ratio: SplitRatio = SplitRatio.R5_5,
    val appPairLabel: String? = null,
)

@Serializable
sealed interface Slot {
    @Serializable
    data class App(val pkg: String) : Slot

    @Serializable
    data object Panel : Slot
}

@Serializable
data class LinkConfig(
    val mode: LinkMode = LinkMode.InAppSplit,
    val chromeDomains: List<String> = listOf("accounts.google.com"),
)

@Serializable
enum class LinkMode {
    InAppSplit,
    Chrome,
}

@Serializable
data class GestureConfig(
    val stripWidthPx: Int = 48,
    val glow: Boolean = true,
    val longPressMs: Int = 1000,
)
