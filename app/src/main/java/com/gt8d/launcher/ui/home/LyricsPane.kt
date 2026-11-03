package com.gt8d.launcher.ui.home

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyricsQuery
import com.gt8d.launcher.media.positionAt
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.theme.Palette
import kotlin.math.roundToInt

@Composable
fun LyricsPane(state: LyricsState, now: NowPlaying, modifier: Modifier = Modifier,
               clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    val fade = if (state is LyricsState.Synced || state is LyricsState.Plain) {
        Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
            drawContent()
            val edge = 32.dp.toPx()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, edge / size.height to Color.Black,
                (size.height - edge) / size.height to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    } else Modifier
    Box(modifier.testTag("player_lyrics").clipToBounds().then(fade)) {
        when (state) {
            is LyricsState.Synced -> {
                val track = now.pkg to now.lyricsQuery
                val index by key(track, state.lines) { rememberLyricIndex(now, state.lines, clock) }
                val startsBeforeFirst = remember(track, state.lines) {
                    state.lines.firstOrNull()?.let { now.positionAt(clock()) < it.timeMs } ?: true
                }
                var resolved by remember(track, state.lines) { mutableStateOf(false) }
                val offset = remember(track, state.lines) { Animatable(0f) }
                var target by remember(track, state.lines) { mutableStateOf<Pair<Int, Float>?>(null) }
                var animate by remember(track, state.lines) { mutableStateOf(false) }
                LaunchedEffect(target, track, state.lines) {
                    val destination = target ?: return@LaunchedEffect
                    if (animate) offset.animateTo(destination.second, tween(300)) else offset.snapTo(destination.second)
                }
                Layout(content = {
                    state.lines.forEachIndexed { i, line ->
                        val current = i == index
                        Text(line.text, Modifier.fillMaxWidth().then(if (current) Modifier.testTag("player_lyrics_current") else Modifier),
                            color = if (current) Color.White else Palette.Muted,
                            fontSize = if (current) 26.sp else 20.sp,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                    val rows = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
                    val gap = 16.dp.roundToPx()
                    val selected = index.coerceAtLeast(0)
                    val center = rows.take(selected).sumOf { it.height + gap } + (rows.getOrNull(selected)?.height ?: 0) / 2f
                    val destination = constraints.maxHeight / 2f - center
                    if (target?.first != index) {
                        animate = (resolved || startsBeforeFirst) && target != null && index == target!!.first + 1
                    }
                    if (index >= 0) resolved = true
                    target = index to destination
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // Jumps use the measured destination in this frame, without waiting for an effect.
                        var y = (if (animate) offset.value else destination).roundToInt()
                        rows.forEach { row -> row.placeRelative(0, y); y += row.height + gap }
                    }
                }
            }
            is LyricsState.Plain -> Text(state.text, Modifier.fillMaxWidth(), color = Color.White,
                fontSize = 20.sp, textAlign = TextAlign.Center)
            LyricsState.Loading -> Text("歌詞を探しています…", Modifier.align(Alignment.Center),
                color = Palette.Muted, fontSize = 20.sp)
            else -> Unit
        }
    }
}
