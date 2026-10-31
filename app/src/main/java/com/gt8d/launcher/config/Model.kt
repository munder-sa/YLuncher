package com.gt8d.launcher.config

import kotlinx.serialization.Serializable

const val CONFIG_VERSION = 3
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

fun SplitRatio.allowedFor(left: Slot, right: Slot): Boolean =
    if (left is Slot.Panel || right is Slot.Panel) this in SplitRatio.forPanel else true

fun SplitRatio.mirrored(): SplitRatio = when (this) {
    SplitRatio.R3_7 -> SplitRatio.R7_3
    SplitRatio.R4_6 -> SplitRatio.R6_4
    SplitRatio.R5_5 -> SplitRatio.R5_5
    SplitRatio.R6_4 -> SplitRatio.R4_6
    SplitRatio.R7_3 -> SplitRatio.R3_7
}

@Serializable
enum class Side { Left, Right }

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
    val panelView: PanelView = PanelView.Apps,
)

@Serializable
enum class PanelView { Apps, Sets }

@Serializable
data class ScreenSet(
    val id: String,
    val left: Slot,
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

val ScreenSet.panelSide: Side? get() = when {
    left is Slot.Panel -> Side.Left
    right is Slot.Panel -> Side.Right
    else -> null
}
val ScreenSet.isValid: Boolean get() = !(left is Slot.Panel && right is Slot.Panel)
fun ScreenSet.slotAt(side: Side): Slot = if (side == Side.Left) left else right
fun ScreenSet.appPkgAt(side: Side): String? = (slotAt(side) as? Slot.App)?.pkg
fun ScreenSet.swapped(): ScreenSet =
    copy(left = right, right = left, ratio = ratio.mirrored(), appPairLabel = null)

@Serializable
data class LinkConfig(
    val mode: LinkMode = LinkMode.InAppSplit,
    // 保存データとの互換のため残す。振り分けには使わない。
    val chromeDomains: List<String> = listOf("accounts.google.com"),
    val browserPkg: String = "com.android.chrome",
)

@Serializable
enum class LinkMode {
    InAppSplit, // ブラウザで分割(保存名は維持)
    Chrome, // 全画面で開く(保存名は維持)
}

@Serializable
data class GestureConfig(
    val stripWidthPx: Int = 48,
    val glow: Boolean = true,
    val longPressMs: Int = 1000,
)
