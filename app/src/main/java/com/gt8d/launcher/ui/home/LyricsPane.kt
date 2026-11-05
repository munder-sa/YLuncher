package com.gt8d.launcher.ui.home

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
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
import com.gt8d.launcher.ui.theme.LyricsVisualStyle
import com.gt8d.launcher.ui.theme.lineTargetAlpha
import kotlin.math.roundToInt
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
internal fun InterludeDots(current: Boolean, startMs: Long, endMs: Long, position: State<Long>, emphasis: State<Float>) {
    val detailSemantics = LocalLyricsDetailSemantics.current
    val observer = LocalLyricsPaneObserver.current
    val probe = remember(observer) { observer?.let { InterludeDrawProbe() } }
    val retained = remember { object { var lit = 0 } }
    val litDots = remember(startMs, endMs, position) {
        derivedStateOf(structuralEqualityPolicy()) {
            interludeLitDots(startMs, endMs, position.value)
        }
    }
    val drawDots: DrawScope.() -> Unit = {
        // Retain the last displayed count, without subscribing inactive rows to position.
        val lit = if (current) litDots.value.also { retained.lit = it } else retained.lit
        val amount = emphasis.value
        probe?.let {
            it.startMs = startMs; it.endMs = endMs; it.current = current
            it.lit = lit; it.emphasis = amount
        }
        repeat(3) { i ->
            val activeAlpha = if (i < lit) 1f else 0.35f
            drawCircle(Color.White.copy(alpha = 0.25f + (activeAlpha - 0.25f) * amount),
                radius = 6.dp.toPx(), center = Offset((6 + i * 20).dp.toPx(), 6.dp.toPx()))
        }
    }
    Box(Modifier.fillMaxWidth().then(if (detailSemantics && current) Modifier.testTag("player_lyrics_interlude") else Modifier),
        contentAlignment = Alignment.CenterStart) {
        Text(" ", if (detailSemantics) Modifier else Modifier.clearAndSetSemantics {},
            color = Color.Transparent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Canvas(Modifier.size(52.dp, 12.dp)) {
            val observe = observer?.observeInterludeDraw
            if (observe != null && probe != null) observe(probe) { drawDots() } else drawDots()
        }
    }
}

internal class InterludeDrawProbe {
    var startMs = 0L
    var endMs = 0L
    var current = false
    var lit = 0
    var emphasis = 0f
}

internal val LyricAlphaKey = SemanticsPropertyKey<Float>("LyricAlpha")
internal class LyricDrawProbe {
    var alpha = 0f
    var top = 0f
    var scale = 1f
    var height = 0
    var origin = TransformOrigin(0f, 0.5f)
    var delayMs = 0
    var animated = false
}
internal val LyricDrawProbeKey = SemanticsPropertyKey<LyricDrawProbe>("LyricDrawProbe")
internal class LyricsPaneObserver {
    var observeInterludeDraw: ((InterludeDrawProbe, () -> Unit) -> Unit)? = null
    var constructedRows: Set<Int> = emptySet()
    var measuredRows: Set<Int> = emptySet()
    var overlayRows: Set<Int> = emptySet()
    var alphaJobs = 0
    var emphasisJobs = 0
    var maskBrushes = 0
    var wordBrushes = 0
    var probes: Map<Int, LyricDrawProbe> = emptyMap()
    var compositions = 0
    var measures = 0
    var placements = 0
    var frameUpdates = 0
    var motionFrames = 0
}
internal val LocalLyricsPaneObserver = staticCompositionLocalOf<LyricsPaneObserver?> { null }
internal val LocalLyricsDetailSemantics = staticCompositionLocalOf { false }

internal fun DrawScope.drawLyricWordBoundary(boundary: Brush, x: Float, top: Float, bottom: Float) {
    clipRect(0f, top, size.width, bottom) {
        val shift = x - 12.dp.toPx()
        translate(left = shift) {
            drawRect(boundary, topLeft = Offset(-shift, 0f), size = size, blendMode = BlendMode.DstIn)
        }
    }
    if (bottom < size.height) drawRect(Color.Transparent, Offset(0f, bottom),
        Size(size.width, size.height - bottom), blendMode = BlendMode.Clear)
}

internal fun lyricEdgeBrush(height: Float, edge: Float): Brush? =
    if (height > 0f) Brush.verticalGradient(0f to Color.Transparent, edge / height to Color.Black,
        (height - edge) / height to Color.Black, 1f to Color.Transparent) else null

@Composable
private fun lyricAnimatedValue(target: Float, animate: Boolean, immediateGeneration: Int,
                               onStart: () -> Unit = {}, onFinished: () -> Unit = {},
                               onStopped: () -> Unit = {}, initial: Float = target): State<Float> {
    val value = remember { Animatable(initial) }
    val history = remember { object { var target = initial; var immediate = immediateGeneration } }
    val needsJob = remember(target, immediateGeneration) {
        (history.target != target || history.immediate != immediateGeneration).also {
            history.target = target
            history.immediate = immediateGeneration
        }
    }
    if (needsJob) LaunchedEffect(target, immediateGeneration) {
        if (animate && value.value != target) {
            onStart()
            try {
                withFrameNanos { }
                value.animateTo(target, tween(LyricsVisualStyle.FadeMs, easing = FastOutSlowInEasing))
                onFinished()
            } finally { onStopped() }
        } else {
            value.snapTo(target)
            onFinished()
        }
    }
    return if (animate) value.asState() else rememberUpdatedState(target)
}

@Composable
private fun KaraokeLine(text: String, current: Boolean, alpha: State<Float>, hasWords: Boolean, fill: State<Float>,
                        emphasis: State<Float>, probe: LyricDrawProbe) {
    val detailSemantics = LocalLyricsDetailSemantics.current
    val observer = LocalLyricsPaneObserver.current
    val textLayout = remember(text) { object { var result: TextLayoutResult? = null } }
    val retained = remember(text) { object { var fill = 0f } }
    val currentState = rememberUpdatedState(current)
    val latestFill = rememberUpdatedState(fill)
    val boundaryMask = remember(text, textLayout, retained, observer) {
        Modifier.drawWithCache {
            val boundary = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                startX = 0f, endX = 24.dp.toPx())
            observer?.let { it.wordBrushes++ }
            onDrawWithContent {
                if (currentState.value) retained.fill = latestFill.value.value
                val progress = if (currentState.value) latestFill.value.value else retained.fill
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
                        drawLyricWordBoundary(boundary, x, top, bottom)
                    }
                }
            }
        }
    }
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
            .then(boundaryMask), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayout.result = it })
    }
}

@Composable
private fun WindowedLyrics(lines: List<LyricLine>, index: Int, intro: Boolean, track: Any,
                           position: State<Long>, fill: State<Float>, durationMs: Long?, startsBeforeFirst: Boolean) {
    val observer = LocalLyricsPaneObserver.current
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Start))
    // No whole-song TextLayoutResult cache. The plan owns only the current window's heights.
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val session = remember { object {
        var plan: LyricWindowPlan? = null
        var generation = 0L
        var lyricsGeneration = 0L
        var immediate = 0
        var appearanceAnimated = false
        var appearanceFrom = -1
        var activeFades = 0
        var movementFinished = -1L
        var finish: () -> Unit = {}
        var styleVersion = 0L
        var probes: Map<Int, LyricDrawProbe> = emptyMap()
    } }
    val song = remember(track, lines) { ++session.lyricsGeneration }
    val motion = remember(song) { LyricMotion() }
    var completed by remember(song) { mutableLongStateOf(-1L) }
    LaunchedEffect(motion) { motion.run { observer?.let { it.motionFrames++ } } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val font = resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
            style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All).value
        val styleKey = remember(style, resolver, direction, font) { (++session.styleVersion).toString() }
        val geometry = LyricWindowGeometry(constraints.maxWidth, constraints.maxHeight,
            density.density, density.fontScale, styleKey)
        val prepared = remember(song, index, intro, geometry, completed) {
            val previous = session.plan
            if (previous != null && previous.lyricsGeneration == song && previous.current == index &&
                previous.geometry == geometry && previous.intro == intro) return@remember previous
            session.appearanceFrom = previous?.current ?: index
            session.appearanceAnimated = previous != null && previous.lyricsGeneration == song &&
                previous.geometry == geometry && (previous.current >= 0 || startsBeforeFirst) &&
                index == previous.current + 1
            planLyricWindow(song, ++session.generation, lines.size, index, geometry,
                session.plan, motion.displayedTops(), intro = intro) { key ->
                measurer.measure(if (key.intro || lines[key.row].text == "♪") " " else lines[key.row].text,
                    style, overflow = TextOverflow.Ellipsis, maxLines = 2,
                    constraints = Constraints(maxWidth = geometry.widthPx)).size.height
            }.also { plan ->
                val generation = plan.generation
                session.plan = plan
                if (!session.appearanceAnimated) session.immediate++
                session.finish = {
                    val pending = session.plan
                    if (pending?.generation == generation && pending.animated &&
                        session.movementFinished == generation && session.activeFades == 0) {
                        val settled = pending.complete(generation, true, true)
                        session.plan = settled
                        motion.retainWindowRows(settled.rows.keys)
                        completed = generation
                        session.finish = {}
                    }
                }
                motion.prepare(plan) {
                    if (session.plan?.generation == generation) {
                        session.movementFinished = generation
                        session.finish()
                    }
                }
            }
        }
        val plan = prepared
        val animateAppearance = session.appearanceAnimated
        val immediate = session.immediate
        val stopped = { session.activeFades--; session.finish() }
        val probes = remember(plan) {
            plan.rows.keys.associateWith { session.probes[it] ?: LyricDrawProbe() }.also { session.probes = it }
        }
        SideEffect {
            observer?.let {
                it.constructedRows = plan.rows.keys
                it.probes = probes
            }
        }
        val entries = plan.rows.entries.toList()
        Layout(content = {
            entries.forEach { (i, _) ->
                key(song, i) {
                    val line = lines[i]
                    val current = i == index
                    val dotsCurrent = current || i == 0 && intro
                    val alpha = lyricAnimatedValue(lineTargetAlpha(i, index, line.words.isNotEmpty()),
                        animateAppearance, immediate, onStart = {
                            session.activeFades++; observer?.let { it.alphaJobs++ }
                        }, onStopped = stopped, initial = if (animateAppearance)
                            lineTargetAlpha(i, session.appearanceFrom, line.words.isNotEmpty())
                            else lineTargetAlpha(i, index, line.words.isNotEmpty()))
                    var overlayFinished by remember(dotsCurrent, immediate) { mutableStateOf(false) }
                    val retention = remember { object { var active = dotsCurrent } }
                    val keepOverlay = remember(dotsCurrent, immediate) {
                        (dotsCurrent || animateAppearance && retention.active).also { retention.active = it }
                    }
                    val emphasis = lyricAnimatedValue(if (dotsCurrent) 1f else 0f, animateAppearance,
                        immediate, onStart = { session.activeFades++; observer?.let { it.emphasisJobs++ } },
                        onFinished = { if (!dotsCurrent && keepOverlay) overlayFinished = true }, onStopped = stopped)
                    val overlay = line.words.isNotEmpty() && line.text != "♪" && !(i == 0 && intro) &&
                        (current || keepOverlay && !overlayFinished)
                    SideEffect { observer?.let { it.overlayRows = (it.overlayRows - i) + if (overlay) setOf(i) else emptySet() } }
                    DisposableEffect(Unit) { onDispose { observer?.let { it.overlayRows = it.overlayRows - i } } }
                    if (line.text == "♪" || i == 0 && intro) {
                        InterludeDots(dotsCurrent, if (intro && i == 0) 0 else line.timeMs,
                            if (intro && i == 0) line.timeMs else lineEnd(lines, i, durationMs), position, emphasis)
                    } else {
                        KaraokeLine(line.text, current, alpha, overlay, fill, emphasis, probes.getValue(i))
                    }
                }
            }
        }, modifier = Modifier.fillMaxSize()) { measurables, measuredConstraints ->
            observer?.let { it.measures++; it.measuredRows = plan.rows.keys }
            val rows = measurables.map { it.measure(Constraints(maxWidth = measuredConstraints.maxWidth)) }
            val motionRows = motion.windowRows
            layout(measuredConstraints.maxWidth, measuredConstraints.maxHeight) {
                observer?.let { it.placements++ }
                rows.forEachIndexed { slot, row ->
                    val (i, geometryRow) = entries[slot]
                    val y = geometryRow.targetTop.roundToInt()
                    val motionRow = motionRows.getValue(i)
                    row.placeRelativeWithLayer(0, y) {
                        val sample = motionRow.sample()
                        translationY = sample.top - y
                        scaleX = sample.scale
                        scaleY = sample.scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        probes.getValue(i).let {
                            it.top = sample.top; it.scale = sample.scale; it.height = row.height
                            it.origin = transformOrigin; it.delayMs = motionRow.delayMs; it.animated = motionRow.animated
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LyricsPane(state: LyricsState, now: NowPlaying, modifier: Modifier = Modifier,
               clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    val observer = LocalLyricsPaneObserver.current
    val detailSemantics = LocalLyricsDetailSemantics.current
    SideEffect { observer?.let { it.compositions++ } }
    val fade = remember(state is LyricsState.Synced || state is LyricsState.Plain, observer) {
        if (state is LyricsState.Synced || state is LyricsState.Plain) {
            Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithCache {
                val edge = 32.dp.toPx()
                val mask = lyricEdgeBrush(size.height, edge)
                observer?.let { it.maskBrushes++ }
                onDrawWithContent {
                    drawContent()
                    if (mask != null && size.width > 0f) drawRect(mask, blendMode = BlendMode.DstIn)
                }
            }
        } else Modifier
    }
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
                val history = remember(track, state.lines) { object { var index: Int? = null; var immediate = 0 } }
                val animateAlpha = remember(track, state.lines, index) {
                    val previous = history.index
                    history.index = index
                    val animate = previous != null && (previous >= 0 || startsBeforeFirst) && index == previous + 1
                    if (!animate) history.immediate++
                    animate
                }
                if (!detailSemantics) {
                    WindowedLyrics(state.lines, index, intro, track, position, fill, now.durationMs, startsBeforeFirst)
                } else {
                    val motion = remember(track, state.lines) { LyricMotion() }
                    val probes = remember(track, state.lines) { state.lines.map { LyricDrawProbe() } }
                    SideEffect { observer?.let {
                        it.constructedRows = state.lines.indices.toSet()
                        it.probes = probes.withIndex().associate { (i, probe) -> i to probe }
                    } }
                    LaunchedEffect(motion) {
                        motion.run { observer?.let { it.motionFrames++ } }
                    }
                    Layout(content = {
                        state.lines.forEachIndexed { i, line ->
                            key(track, state.lines, i) {
                                val current = i == index
                                val dotsCurrent = current || (i == 0 && intro)
                                val targetAlpha = lineTargetAlpha(i, index, line.words.isNotEmpty())
                                val alpha = lyricAnimatedValue(targetAlpha, animateAlpha, history.immediate,
                                    onStart = { observer?.let { it.alphaJobs++ } })
                                var overlayFinished by remember(dotsCurrent, history.immediate) { mutableStateOf(false) }
                                val retained = remember { object { var active = dotsCurrent } }
                                val keepOverlay = remember(dotsCurrent, history.immediate) {
                                    (dotsCurrent || animateAlpha && retained.active).also { retained.active = it }
                                }
                                val emphasis = lyricAnimatedValue(if (dotsCurrent) 1f else 0f, animateAlpha, history.immediate,
                                    onStart = { observer?.let { it.emphasisJobs++ } },
                                    onFinished = { if (!dotsCurrent && keepOverlay) overlayFinished = true })
                                val overlay = line.words.isNotEmpty() && line.text != "♪" && !(i == 0 && intro) &&
                                    (current || keepOverlay && !overlayFinished)
                                SideEffect { observer?.let { it.overlayRows = (it.overlayRows - i) + if (overlay) setOf(i) else emptySet() } }
                                DisposableEffect(Unit) { onDispose { observer?.let { it.overlayRows = it.overlayRows - i } } }
                                val probe = probes[i]
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
                                            overlay, fill, emphasis, probe)
                                    }
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
                        observer?.let { it.measures++; it.measuredRows = state.lines.indices.toSet() }
                        val rows = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
                        val gap = 16.dp.roundToPx()
                        val selected = index.coerceAtLeast(0)
                        val center = rows.take(selected).sumOf { it.height + gap } + (rows.getOrNull(selected)?.height ?: 0) / 2f
                        val destination = constraints.maxHeight / 2f - center
                        var top = destination
                        val targets = rows.map { row -> top.also { top += row.height + gap } }
                        motion.prepare(index, targets, rows.map { it.height }, constraints.maxHeight, constraints.maxWidth)
                        val motionRows = motion.rows
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            observer?.let { it.placements++ }
                            rows.forEachIndexed { i, row ->
                                val motionRow = motionRows[i]
                                val y = targets[i].roundToInt()
                                row.placeRelativeWithLayer(0, y) {
                                    val sample = motionRow.sample()
                                    translationY = sample.top - y
                                    scaleX = sample.scale
                                    scaleY = sample.scale
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                    probes[i].let {
                                        it.top = sample.top
                                        it.scale = sample.scale
                                        it.height = row.height
                                        it.origin = transformOrigin
                                        it.delayMs = motionRow.delayMs
                                        it.animated = motionRow.animated
                                    }
                                }
                            }
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
