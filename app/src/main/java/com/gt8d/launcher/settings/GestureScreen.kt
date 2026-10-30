package com.gt8d.launcher.settings

import androidx.compose.foundation.layout.*
import com.gt8d.launcher.ui.layout.OneLineText
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.ui.theme.Dims

@Composable
fun GestureScreen(config: LauncherConfig, onUpdate: ((LauncherConfig) -> LauncherConfig) -> Unit) {
    OneLineText("スワイプ帯の幅", fontSize = Dims.BodyText)
    DailySettings.stripWidthChoices.chunked(2).forEach { choices ->
        Row(Modifier.fillMaxWidth()) {
            choices.forEach { width ->
                DailyButton("${width}px", "gesture_width_$width", Modifier.weight(1f), selected = config.gesture.stripWidthPx == width) {
                    onUpdate { it.copy(gesture = it.gesture.copy(stripWidthPx = width)) }
                }
            }
        }
    }
    OneLineText("光る演出", fontSize = Dims.BodyText)
    Switch(config.gesture.glow, { value -> onUpdate { it.copy(gesture = it.gesture.copy(glow = value)) } },
        Modifier.sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch).testTag("gesture_glow"))
    OneLineText("長押しの時間", fontSize = Dims.BodyText)
    DailySettings.longPressChoices.chunked(2).forEach { choices ->
        Row(Modifier.fillMaxWidth()) {
            choices.forEach { ms ->
                DailyButton(DailySettings.longPressLabel(ms), "gesture_longpress_$ms", Modifier.weight(1f), selected = config.gesture.longPressMs == ms) {
                    onUpdate { it.copy(gesture = it.gesture.copy(longPressMs = ms)) }
                }
            }
        }
    }
}
