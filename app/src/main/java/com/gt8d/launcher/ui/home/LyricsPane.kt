package com.gt8d.launcher.ui.home

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.delay

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
private fun InterludeDots(current: Boolean, startMs: Long, endMs: Long, position: State<Long>, emphasis: State<Float>) {
    val detailSemantics = LocalLyricsDetailSemantics.current
    val retained = remember { object { var lit = 0 } }
    Box(Modifier.fillMaxWidth().then(if (detailSemantics && current) Modifier.testTag("player_lyrics_interlude") else Modifier),
        contentAlignment = Alignment.CenterStart) {
        Text(" ", if (detailSemantics) Modifier else Modifier.clearAndSetSemantics {},
            color = Color.Transparent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Canvas(Modifier.size(52.dp, 12.dp)) {
            val lit = interludeLitDots(startMs, endMs, position.value)
            if (current) retained.lit = lit
            repeat(3) { i ->
                val activeAlpha = if (i < (if (current) lit else retained.lit)) 1f else 0.35f
                drawCircle(Color.White.copy(alpha = 0.25f + (activeAlpha - 0.25f) * emphasis.value),
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

internal val LyricAlphaKey = SemanticsPropertyKey<Float>("LyricAlpha")
internal class LyricDrawProbe { var alpha = 0f }
internal val LyricDrawProbeKey = SemanticsPropertyKey<LyricDrawProbe>("LyricDrawProbe")
internal class LyricsPaneObserver {
    var compositions = 0
    var measures = 0
    var placements = 0
    var frameUpdates = 0
}
internal val LocalLyricsPaneObserver = staticCompositionLocalOf<LyricsPaneObserver?> { null }
internal val LocalLyricsDetailSemantics = staticCompositionLocalOf { false }

@Composable
private fun lyricAnimatedValue(target: Float, animate: Boolean, index: Int): State<Float> {
    val value = remember { Animatable(target) }
    LaunchedEffect(target, index) {
        withFrameNanos { }
        if (animate) value.animateTo(target, tween(500, easing = FastOutSlowInEasing))
        else value.snapTo(target)
    }
    return if (animate) value.asState() else rememberUpdatedState(target)
}

@Composable
private fun KaraokeLine(text: String, current: Boolean, alpha: State<Float>, hasWords: Boolean, fill: State<Float>,
                        emphasis: State<Float>, probe: LyricDrawProbe) {
    val detailSemantics = LocalLyricsDetailSemantics.current
    val textLayout = remember(text) { object { var result: TextLayoutResult? = null } }
    val retained = remember(text) { object { var fill = 0f } }
    Box(Modifier.fillMaxWidth()) {
        Text(text, Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value; probe.alpha = this.alpha }
            .then(if (!detailSemantics) Modifier.clearAndSetSemantics {}
                else if (current) Modifier.testTag("player_lyrics_current") else Modifier),
            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (hasWords) Text(text, Modifier.fillMaxWidth().clearAndSetSemantics {
            if (detailSemantics) testTag = "player_lyrics_fill"
        }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; this.alpha = if (current) 1f else emphasis.value }
            .drawWithContent {
                if (current) retained.fill = fill.value
                val progress = if (current) fill.value else retained.fill
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
            }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayout.result = it })
    }
}

@Composable
fun LyricsPane(state: LyricsState, now: NowPlaying, modifier: Modifier = Modifier,
               clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    val observer = LocalLyricsPaneObserver.current
    val detailSemantics = LocalLyricsDetailSemantics.current
    SideEffect { observer?.let { it.compositions++ } }
    val fade = if (state is LyricsState.Synced || state is LyricsState.Plain) {
        Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
            drawContent()
            val edge = 32.dp.toPx()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, edge / size.height to Color.Black,
                (size.height - edge) / size.height to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    } else Modifier
    val lyricsSemantics = if (state is LyricsState.Synced && !detailSemantics) {
        Modifier.clearAndSetSemantics {
            contentDescription = "歌詞"
            testTag = "player_lyrics"
        }
    } else Modifier.testTag("player_lyrics")
    Box(modifier.then(lyricsSemantics).clipToBounds().then(fade)) {
        when (state) {
            is LyricsState.Synced -> {
                val track = now.pkg to now.lyricsQuery
                val index by key(track, state.lines) { rememberLyricIndex(now, state.lines, clock) }
                val fill = remember(track, state.lines) { mutableFloatStateOf(0f) }
                val position = remember(now, track, state.lines) { mutableLongStateOf(now.positionAt(clock())) }
                val intro by produceState(false, now, state.lines, index) {
                    do {
                        value = index < 0 && state.lines.firstOrNull()?.let {
                            it.timeMs.toDouble() - now.positionAt(clock()) >= 5000
                        } == true
                        if (!now.playing || index >= 0) break
                        delay(200)
                    } while (true)
                }
                val needsFrames = intro || state.lines.getOrNull(index)?.let {
                    it.words.isNotEmpty() || it.text == "♪"
                } == true
                LaunchedEffect(now, state.lines, index, needsFrames) {
                    position.longValue = now.positionAt(clock())
                    fill.floatValue = wordFillPosition(state.lines, index, position.longValue, now.durationMs)
                    while (now.playing && needsFrames) {
                        withFrameMillis {
                            observer?.let { it.frameUpdates++ }
                            position.longValue = now.positionAt(clock())
                            fill.floatValue = wordFillPosition(state.lines, index, position.longValue, now.durationMs)
                        }
                    }
                }
                val startsBeforeFirst = remember(track, state.lines) {
                    state.lines.firstOrNull()?.let { now.positionAt(clock()) < it.timeMs } ?: true
                }
                val history = remember(track, state.lines) { object { var index: Int? = null } }
                val animateAlpha = remember(track, state.lines, index) {
                    val previous = history.index
                    history.index = index
                    previous != null && (previous >= 0 || startsBeforeFirst) && index == previous + 1
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
                    if (measured.animate) offset.animateTo(destination.second, tween(800, easing = FastOutSlowInEasing)) else offset.snapTo(destination.second)
                }
                Layout(content = {
                    state.lines.forEachIndexed { i, line ->
                        key(track, state.lines, i) {
                            val current = i == index
                            val dotsCurrent = current || (i == 0 && intro)
                            val targetAlpha = if (current && line.words.isEmpty()) 1f
                                else lyricLineAlpha(abs(i - index.coerceAtLeast(0)))
                            val alpha = lyricAnimatedValue(targetAlpha, animateAlpha, index)
                            val emphasis = lyricAnimatedValue(if (dotsCurrent) 1f else 0f, animateAlpha, index)
                            val probe = remember { LyricDrawProbe() }
                            Box(if (detailSemantics) Modifier.testTag("player_lyrics_line_$i").semantics {
                                this[LyricAlphaKey] = targetAlpha
                                this[LyricDrawProbeKey] = probe
                            } else Modifier) {
                                if (line.text == "♪" || (i == 0 && intro)) {
                                    InterludeDots(dotsCurrent,
                                        if (intro && i == 0) 0 else line.timeMs,
                                        if (intro && i == 0) line.timeMs else lineEnd(state.lines, i, now.durationMs), position, emphasis)
                                } else {
                                    KaraokeLine(line.text, current, alpha,
                                        line.words.isNotEmpty(), fill, emphasis, probe)
                                }
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                    observer?.let { it.measures++ }
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
                    val animate = measured.animate
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        observer?.let { it.placements++ }
                        // Jumps use the measured destination in this frame, without waiting for an effect.
                        var y = destination.roundToInt()
                        rows.forEach { row ->
                            row.placeRelativeWithLayer(0, y) {
                                // Read on every layer update, including after a snap.
                                val animatedOffset = offset.value
                                translationY = if (animate) animatedOffset - destination else 0f
                            }
                            y += row.height + gap
                        }
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
