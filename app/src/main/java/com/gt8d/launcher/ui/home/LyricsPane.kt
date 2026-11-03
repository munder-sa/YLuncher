package com.gt8d.launcher.ui.home

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextLayoutResult
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
import com.gt8d.launcher.media.lyrics.LyricLine
import com.gt8d.launcher.ui.theme.Palette
import kotlin.math.roundToInt
import kotlin.math.abs

private fun lineEnd(lines: List<LyricLine>, index: Int, durationMs: Long?): Long {
    val start = lines[index].timeMs
    return lines.getOrNull(index + 1)?.timeMs
        ?: (start.coerceAtMost(Long.MAX_VALUE - 5000L) + 5000L)
            .let { if (durationMs != null) minOf(it, durationMs) else it }
}

internal fun wordFillPosition(lines: List<LyricLine>, index: Int, positionMs: Long, durationMs: Long?): Float {
    val line = lines.getOrNull(index) ?: return 0f
    val wordIndex = line.words.indexOfLast { it.timeMs <= positionMs }
    if (wordIndex < 0) return 0f
    val word = line.words[wordIndex]
    val next = line.words.getOrNull(wordIndex + 1)
    val end = next?.timeMs ?: lineEnd(lines, index, durationMs)
    val endChar = next?.charStart ?: line.text.length
    if (end <= word.timeMs) return endChar.toFloat()
    val fraction = ((positionMs.toDouble() - word.timeMs) / (end.toDouble() - word.timeMs)).coerceIn(0.0, 1.0)
    return (word.charStart + (endChar - word.charStart) * fraction).toFloat()
}

internal fun interludeLitDots(startMs: Long, endMs: Long, positionMs: Long): Int {
    if (positionMs < startMs) return 0
    if (endMs <= startMs) return 3
    return (((positionMs.toDouble() - startMs) / (endMs.toDouble() - startMs)) * 3)
        .coerceIn(0.0, 3.0).toInt()
}

@Composable
private fun InterludeDots(current: Boolean, startMs: Long, endMs: Long, position: State<Long>) {
    Box(Modifier.fillMaxWidth().then(if (current) Modifier.testTag("player_lyrics_interlude") else Modifier),
        contentAlignment = Alignment.CenterStart) {
        Text(" ", color = Color.Transparent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Canvas(Modifier.size(52.dp, 12.dp)) {
            val lit = interludeLitDots(startMs, endMs, position.value)
            repeat(3) { i ->
                drawCircle(Color.White.copy(alpha = if (!current) 0.25f else if (i < lit) 1f else 0.35f),
                    radius = 6.dp.toPx(), center = Offset((6 + i * 20).dp.toPx(), 6.dp.toPx()))
            }
        }
    }
}

internal fun lyricLineAlpha(distance: Int): Float = when (distance) {
    0, 1 -> 0.45f
    2 -> 0.35f
    else -> 0.25f
}

@Composable
private fun KaraokeLine(text: String, current: Boolean, alpha: Float, hasWords: Boolean, fill: State<Float>) {
    val textLayout = remember(text) { object { var result: TextLayoutResult? = null } }
    Box(Modifier.fillMaxWidth()) {
        Text(text, Modifier.fillMaxWidth().then(if (current) Modifier.testTag("player_lyrics_current") else Modifier),
            color = Color.White.copy(alpha = if (current && !hasWords) 1f else alpha), fontSize = 24.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (current && hasWords) Text(text, Modifier.fillMaxWidth().clearAndSetSemantics {
            testTag = "player_lyrics_fill"
        }
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                val progress = fill.value
                val result = textLayout.result
                if (progress > 0f && result != null) {
                    drawContent()
                    if (progress < text.length && text.isNotEmpty()) {
                        val offset = progress.toInt().coerceAtMost(text.lastIndex)
                        val line = result.getLineForOffset(offset)
                        val startX = result.getHorizontalPosition(offset, true)
                        val nextX = if (offset + 1 < result.getLineEnd(line)) {
                            result.getHorizontalPosition(offset + 1, true)
                        } else result.getLineRight(line)
                        val x = startX + (nextX - startX) * (progress - offset)
                        val top = result.getLineTop(line)
                        val bottom = result.getLineBottom(line)
                        clipRect(0f, top, size.width, bottom) {
                            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                                startX = x - 12.dp.toPx(), endX = x + 12.dp.toPx()),
                                blendMode = BlendMode.DstIn)
                        }
                        if (bottom < size.height) drawRect(Color.Transparent, Offset(0f, bottom),
                            Size(size.width, size.height - bottom), blendMode = BlendMode.Clear)
                    }
                }
            }, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayout.result = it })
    }
}

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
                val fill = remember(track, state.lines) { mutableFloatStateOf(0f) }
                val position = remember(now, track, state.lines) { mutableLongStateOf(now.positionAt(clock())) }
                LaunchedEffect(now, state.lines, index) {
                    position.longValue = now.positionAt(clock())
                    fill.floatValue = wordFillPosition(state.lines, index, position.longValue, now.durationMs)
                    while (now.playing) {
                        withFrameMillis {
                            position.longValue = now.positionAt(clock())
                            fill.floatValue = wordFillPosition(state.lines, index, position.longValue, now.durationMs)
                        }
                    }
                }
                val intro = index < 0 && state.lines.firstOrNull()?.let {
                    it.timeMs.toDouble() - position.longValue >= 5000
                } == true
                val startsBeforeFirst = remember(track, state.lines) {
                    state.lines.firstOrNull()?.let { now.positionAt(clock()) < it.timeMs } ?: true
                }
                val measured = remember(track, state.lines) {
                    object {
                        var resolved = false
                        var target: Pair<Int, Float>? = null
                        var animate = false
                    }
                }
                val offset = remember(track, state.lines) { Animatable(0f) }
                LaunchedEffect(index, track, state.lines) {
                    withFrameNanos { }
                    val destination = measured.target ?: return@LaunchedEffect
                    if (measured.animate) offset.animateTo(destination.second, tween(300)) else offset.snapTo(destination.second)
                }
                Layout(content = {
                    state.lines.forEachIndexed { i, line ->
                        val current = i == index
                        if (line.text == "♪" || (i == 0 && intro)) {
                            InterludeDots(current || (i == 0 && intro),
                                if (intro && i == 0) 0 else line.timeMs,
                                if (intro && i == 0) line.timeMs else lineEnd(state.lines, i, now.durationMs), position)
                        } else {
                            KaraokeLine(line.text, current, lyricLineAlpha(abs(i - index.coerceAtLeast(0))),
                                line.words.isNotEmpty(), fill)
                        }
                    }
                }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                    val rows = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
                    val gap = 16.dp.roundToPx()
                    val selected = index.coerceAtLeast(0)
                    val center = rows.take(selected).sumOf { it.height + gap } + (rows.getOrNull(selected)?.height ?: 0) / 2f
                    val destination = constraints.maxHeight / 2f - center
                    if (measured.target?.first != index) {
                        measured.animate = (measured.resolved || startsBeforeFirst) &&
                            measured.target != null && index == measured.target!!.first + 1
                    }
                    if (index >= 0) measured.resolved = true
                    measured.target = index to destination
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // Jumps use the measured destination in this frame, without waiting for an effect.
                        var y = (if (measured.animate) offset.value else destination).roundToInt()
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
