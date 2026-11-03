package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import com.gt8d.launcher.ui.layout.OneLineText
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekBar(
    durationMs: Long?,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    tag: String = "progress",
    showTimes: Boolean = true,
    timesTag: String = "progress_times",
) {
    val duration = durationMs ?: return
    if (duration <= 0L) return
    val automaticPosition by remember(position) {
        derivedStateOf { position() / 1000L * 1000L }
    }
    val clampedPos = automaticPosition.coerceIn(0L, duration)
    var dragPos by remember { mutableStateOf<Float?>(null) }
    var endpoint by remember { mutableStateOf<Long?>(null) }
    var dragged by remember { mutableStateOf(false) }
    val sliderValue = dragPos ?: clampedPos.toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(color = Palette.PanelAccent)) {
            Slider(
                value = sliderValue,
                onValueChange = { dragPos = it },
                onValueChangeFinished = {
                    if (dragged) dragPos?.let { onSeek(endpoint ?: it.toLong()) }
                    dragged = false
                    endpoint = null
                    dragPos = null
                },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Palette.PanelAccent,
                    activeTrackColor = Palette.PanelAccent,
                    inactiveTrackColor = Palette.PanelAccent.copy(alpha = 0.24f),
                    activeTickColor = Palette.PanelAccent,
                    inactiveTickColor = Palette.PanelAccent.copy(alpha = 0.24f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                    .pointerInput(duration) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            endpoint = null
                            dragged = false
                            dragPos = null
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if ((pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    dragged = true
                                }
                                endpoint = when {
                                    pointer.position.x <= 1f -> 0L
                                    pointer.position.x >= size.width - 1f -> duration
                                    else -> null
                                }
                            } while (pointer.pressed)
                        }
                    },
            )
        }
    }
    if (showTimes) {
        val displayPos = dragPos?.toLong() ?: clampedPos
        val remaining = (duration - displayPos).coerceAtLeast(0L)
        Row(
            modifier = Modifier.fillMaxWidth().testTag(timesTag),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OneLineText(formatTime(displayPos), fontSize = Dims.BodyText, color = Color.White)
            OneLineText("-${formatTime(remaining)}", fontSize = Dims.BodyText, color = Color.White)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
