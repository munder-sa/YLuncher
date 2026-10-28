package com.gt8d.launcher.config

import kotlinx.serialization.Serializable

const val MAX_PANEL_APPS = 5
const val MAX_DOCK_APPS = 4

@Serializable
data class LauncherConfig(
    val version: Int = 1,
    val screenSets: List<ScreenSet>,
    val homeApps: List<String>,
    val panelApps: List<String>,
    val dockApps: List<String>,
    val idleApp: String,
    val link: LinkConfig = LinkConfig(),
    val gesture: GestureConfig = GestureConfig(),
)

@Serializable
data class ScreenSet(
    val id: String,
    val left: Slot.App,
    val right: Slot,
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
    val stripWidthPx: Int = 8,
    val glow: Boolean = true,
    val longPressMs: Int = 1000,
)
