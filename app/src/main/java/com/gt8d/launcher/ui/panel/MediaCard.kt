package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.R
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.layout.OneLineText
import com.gt8d.launcher.ui.layout.PanelLayout
import java.util.Locale

@Composable
fun MediaCard(
    now: NowPlaying?,
    idleApp: AppInfo?,
    positionMs: Long,
    onAction: (MediaAction) -> Unit,
    onSeek: (Long) -> Unit,
    onLaunch: (String) -> Unit,
) {
    Surface(
        color = Palette.Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("media_card"),
    ) {
        if (now == null) {
            IdleMediaContent(idleApp = idleApp, onLaunch = onLaunch)
        } else {
            ActiveMediaContent(
                now = now,
                positionMs = positionMs,
                onAction = onAction,
                onSeek = onSeek,
            )
        }
    }
}

@Composable
private fun IdleMediaContent(
    idleApp: AppInfo?,
    onLaunch: (String) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val showName = maxWidth >= 300.dp
        if (idleApp != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                    .semantics { contentDescription = idleApp.label }
                    .clickable {
                        if (idleApp.installed) {
                            onLaunch(idleApp.pkg)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("idle_app"),
            ) {
                if (idleApp.icon != null) {
                    Image(
                        bitmap = idleApp.icon,
                        contentDescription = idleApp.label,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    val initial = idleApp.label.firstOrNull()?.toString() ?: "?"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = idleApp.label }
                            .clip(CircleShape)
                            .background(Palette.Background1),
                    ) {
                        Text(
                            text = initial,
                            fontSize = Dims.BodyText,
                            color = Color.White,
                        )
                    }
                }
                if (showName || !idleApp.installed) {
                    Spacer(modifier = Modifier.width(12.dp))
                    OneLineText(
                        text = if (idleApp.installed) idleApp.label else "アプリが見つかりません",
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveMediaContent(
    now: NowPlaying,
    positionMs: Long,
    onAction: (MediaAction) -> Unit,
    onSeek: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val bitmap = now.artwork
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = now.title,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("artwork"),
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.artwork_placeholder),
                    contentDescription = now.title,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("artwork"),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                OneLineText(
                    text = now.title,
                    fontSize = Dims.BodyText,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.testTag("title"),
                )
                if (now.artist != null) {
                    OneLineText(
                        text = now.artist,
                        fontSize = Dims.BodyText,
                        color = Color.LightGray,
                        modifier = Modifier.testTag("artist"),
                    )
                }
            }
        }

        val duration = now.durationMs
        if (duration != null && duration > 0L) {
            val clampedPos = positionMs.coerceIn(0L, duration)
            var dragPos by remember { mutableStateOf<Float?>(null) }
            var endpoint by remember { mutableStateOf<Long?>(null) }
            val sliderValue = dragPos ?: clampedPos.toFloat()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                    .semantics(mergeDescendants = true) {}
                    .testTag("progress"),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = { dragPos = it },
                    onValueChangeFinished = {
                        dragPos?.let { onSeek(endpoint ?: it.toLong()) }
                        endpoint = null
                        dragPos = null
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.Accent,
                        activeTrackColor = Palette.Accent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                        .pointerInput(duration) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                endpoint = null
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
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
            val displayPos = dragPos?.toLong() ?: clampedPos
            val remaining = (duration - displayPos).coerceAtLeast(0L)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OneLineText(
                    text = formatTime(displayPos),
                    fontSize = Dims.BodyText,
                    color = Color.White,
                )
                OneLineText(
                    text = "-${formatTime(remaining)}",
                    fontSize = Dims.BodyText,
                    color = Color.White,
                )
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val controls = listOf(MediaAction.Prev, if (now.playing) MediaAction.Pause else MediaAction.Play, MediaAction.Next) +
                if (now.hasFavorite) listOf(MediaAction.Favorite) else emptyList()
            val rows = PanelLayout.controlRows(maxWidth.value, controls.size)
            val perRow = if (rows == 1) controls.size else (controls.size + 1) / 2
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in controls.chunked(perRow)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (action in row) MediaControl(action, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaControl(action: MediaAction, onAction: (MediaAction) -> Unit) {
    val (icon, description, tag) = when (action) {
        MediaAction.Prev -> Triple(Icons.Default.SkipPrevious, "前へ", "btn_prev")
        MediaAction.Play -> Triple(Icons.Default.PlayArrow, "再生", "btn_playpause")
        MediaAction.Pause -> Triple(Icons.Default.Pause, "一時停止", "btn_playpause")
        MediaAction.Next -> Triple(Icons.Default.SkipNext, "次へ", "btn_next")
        MediaAction.Favorite -> Triple(Icons.Default.StarBorder, "お気に入り", "btn_favorite")
    }
    IconButton(onClick = { onAction(action) }, modifier = Modifier.size(72.dp).testTag(tag)) {
        Icon(icon, description, tint = Palette.Accent, modifier = Modifier.size(36.dp))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
