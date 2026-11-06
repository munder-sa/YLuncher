@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.gt8d.launcher.renderprobe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TIMEOUT_SECONDS = 15L
private const val TOLERANCE = 1
private const val PROGRESS_INTERVAL = 25
private const val DRAW_TIMEOUT = "draw_and_two_frame_commits_timeout"
private const val PIXEL_COPY_TIMEOUT = "pixel_copy_timeout"
private const val DARK = 0xFF121212.toInt()
private const val COLORED = 0xFF19354B.toInt()
private val DENSITIES = listOf(1f, 1.4875f, 2f)

internal data class Sample(
    val name: String,
    val text: String,
    val widthDp: Int,
    val lines: Int = 1,
    val ellipsis: Boolean = false,
    val interior: Float = 2.5f,
)

// All strings are synthetic. No media, account, storage or network access is involved.
private val SAMPLES = listOf(
    Sample("single", "MINT glow", 200),
    Sample("wrapped", "MINT bridge", 100, lines = 2),
    Sample("ellipsis", "MINT bridge MINT bridge MINT bridge MINT bridge", 100,
        lines = 2, ellipsis = true),
    Sample("mixedRtl", "AB אבג CD", 200, interior = 4.5f),
    Sample("rtlStart", "אבג AB דה", 200, interior = 1.5f),
    Sample("combining", "A\u0301V e\u0301 o\u0308", 200, interior = 1.5f),
    Sample("colorEmoji", "A 😀 🌈 B", 200, interior = 3.5f),
)

internal enum class FillRule {
    BELOW_ZERO, ZERO, START_AFTER, UTF16_INTERIOR, FIRST_LINE_END_BEFORE,
    FIRST_LINE_END, LAST_LINE_START_AFTER, END_BEFORE, END, ABOVE_END;

    fun resolve(sample: Sample, layout: TextLayoutResult): Float = when (this) {
        BELOW_ZERO -> -1f
        ZERO -> 0f
        START_AFTER -> 0.01f
        UTF16_INTERIOR -> sample.interior
        FIRST_LINE_END_BEFORE -> layout.getLineEnd(0) - 0.01f
        FIRST_LINE_END -> layout.getLineEnd(0).toFloat()
        LAST_LINE_START_AFTER -> layout.getLineStart(layout.lineCount - 1) + 0.01f
        END_BEFORE -> sample.text.length - 0.01f
        END -> sample.text.length.toFloat()
        ABOVE_END -> sample.text.length + 1f
    }
}

internal data class ProbeCase(
    val id: Int,
    val sample: Sample,
    val density: Float,
    val emphasis: Float,
    val background: Int,
    val rule: FillRule,
    val kind: String = "reference_shader",
    val fixedFill: Float? = null,
    val current: Boolean = emphasis == 1f,
) {
    val baseAlpha = 0.35f
    fun fill(layout: TextLayoutResult) = fixedFill ?: rule.resolve(sample, layout)
    fun json() = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("sample", sample.name)
        put("density", density.toDouble())
        put("fontScale", 1)
        put("emphasis", emphasis.toDouble())
        put("current", if (current) 1 else 0)
        put("baseAlpha", baseAlpha.toDouble())
        put("backgroundARGB", background.toLong() and 0xffffffffL)
        put("fillRule", if (fixedFill == null) rule.name else "FIXED")
        fixedFill?.let { put("fill", it.toDouble()) }
    }
}

private fun ordinaryCases(): List<ProbeCase> = buildList {
    for (sample in SAMPLES) for (density in DENSITIES) {
        for (emphasis in listOf(1f, 0.5f, 0f)) for (rule in FillRule.entries) {
            add(ProbeCase(size, sample, density, emphasis,
                if (rule.ordinal % 2 == 0) DARK else COLORED, rule))
        }
    }
}

private class ProbeFailure(val errorId: String, detail: String = errorId) : IllegalStateException(detail)

private class FrameRequest(
    val case: ProbeCase,
    val candidate: Boolean,
    val owner: LyricWordBrush?,
    val updateUniforms: Boolean,
) {
    val ready = CountDownLatch(1)
    val brush = owner?.textBrush()
    var baseLayout: TextLayoutResult? = null
    var overlayLayout: TextLayoutResult? = null
    var rect: Rect? = null
    var rootDrawn = false
    var composeHardware = false
    var viewHardware = false
    var committedFrames = 0
    var commitPending = false
    var error: ProbeFailure? = null
    var afterContentDraw: () -> Unit = {}

    fun fail(errorId: String, cause: Exception? = null) {
        if (error == null) error = ProbeFailure(errorId, if (cause == null) errorId
            else "$errorId: ${cause.javaClass.simpleName}: ${cause.message}")
        ready.countDown()
    }
}

/** Only the dummy row is displayed. Instrumentation controls case and old/new selection. */
class ProbeActivity : ComponentActivity() {
    private var frame by mutableStateOf<FrameRequest?>(null)
    private val owners = mutableMapOf<String, LyricWordBrush>()
    private lateinit var host: FrameLayout
    internal val destroyed = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host = object : FrameLayout(this) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                afterWindowDraw(canvas.isHardwareAccelerated)
            }
        }
        val content = ComposeView(this)
        content.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    frame?.let { request ->
                        CompositionLocalProvider(
                            LocalDensity provides Density(request.case.density, fontScale = 1f),
                            LocalLayoutDirection provides LayoutDirection.Ltr,
                        ) {
                            // Recompose Text; keep the row-owned RuntimeShader across requests.
                            key(request) { ProofRow(request) }
                        }
                    }
                }
            }
        }
        host.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(host)
    }

    private fun afterWindowDraw(hardware: Boolean) {
        val request = frame ?: return
        if (request.ready.count == 0L || request.commitPending) return
        request.viewHardware = hardware && host.isHardwareAccelerated
        if (!request.viewHardware) {
            request.fail("window_canvas_not_hardware")
            return
        }
        if (!request.rootDrawn || request.baseLayout == null || request.overlayLayout == null ||
            request.rect == null) return
        if (!request.composeHardware) {
            request.fail("compose_canvas_not_hardware")
            return
        }
        request.commitPending = true
        // Frame commit signals submission of the actual hardware draw, not just vsync.
        host.viewTreeObserver.registerFrameCommitCallback {
            host.post {
                if (frame !== request || request.ready.count == 0L) return@post
                request.committedFrames++
                if (request.committedFrames == 2) {
                    request.ready.countDown()
                } else {
                    // Submit a further frame after measurement/content draw and its commit.
                    host.postOnAnimation {
                        if (frame === request && request.ready.count != 0L) {
                            request.commitPending = false
                            host.invalidate()
                        }
                    }
                }
            }
        }
        // ViewRootImpl collects commit callbacks before drawing. This registration
        // needs another frame even while commitPending is true; waiting for the
        // callback before invalidating would leave it stranded.
        host.postOnAnimation {
            if (frame === request && request.ready.count != 0L) host.invalidate()
        }
    }

    private fun present(case: ProbeCase, candidate: Boolean, ownerKey: String, update: Boolean): FrameRequest {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = if (candidate) owners.getOrPut(ownerKey) { LyricWordBrush() } else null
        return FrameRequest(case, candidate, owner, update).also {
            // A dirty Compose child can re-record its display list without dispatchDraw
            // on its cached parent. Arm the first commit from the row's draw as well.
            it.afterContentDraw = {
                if (frame === it) afterWindowDraw(it.composeHardware)
            }
            frame = it
            host.invalidate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed.countDown()
    }

    // The runner is nested only to access the private request protocol; the public manifest
    // entry below is an ordinary Android Instrumentation, not an AndroidX test runner.
    internal fun runner() = CaptureRunner(this)

    internal class CaptureRunner(private val activity: ProbeActivity) {
        private val main = Handler(Looper.getMainLooper())

        private fun capture(case: ProbeCase, candidate: Boolean, ownerKey: String, update: Boolean): Shot {
            check(Looper.myLooper() != Looper.getMainLooper())
            val request = onMain { activity.present(case, candidate, ownerKey, update) }
            await(request.ready, "draw_and_two_frame_commits case=${case.id}", DRAW_TIMEOUT)
            request.error?.let { throw it }
            check(request.viewHardware && request.composeHardware && request.committedFrames == 2)
            val layout = checkNotNull(request.overlayLayout)
            check(sameGeometry(checkNotNull(request.baseLayout), layout)) { "base_overlay_layout_mismatch" }
            val rect = Rect(checkNotNull(request.rect))
            check(rect.width() == (case.sample.widthDp * case.density).roundToInt() &&
                rect.height() == (96f * case.density).roundToInt()) { "probe_rect_clipped_or_resized $rect" }
            onMain {
                check(activity.hasWindowFocus()) { "probe_window_not_focused" }
                val decor = activity.window.decorView
                check(rect.left >= 0 && rect.top >= 0 && rect.right <= decor.width && rect.bottom <= decor.height) {
                    "probe_rect_outside_window $rect"
                }
            }
            val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            val copyResult = AtomicReference<Int>()
            val destinationLock = Any()
            var completed = false
            var abandoned = false
            try {
                // Never capture the screen, system UI or another window. No file is written.
                onMain {
                    PixelCopy.request(activity.window, Rect(rect), bitmap, { status ->
                        synchronized(destinationLock) {
                            copyResult.set(status)
                            completed = true
                            if (abandoned) bitmap.recycle()
                            copied.countDown()
                        }
                    }, main)
                }
                await(copied, "pixel_copy case=${case.id}", PIXEL_COPY_TIMEOUT)
                val status = copyResult.get()
                if (status != PixelCopy.SUCCESS) {
                    throw ProbeFailure(if (status == PixelCopy.ERROR_TIMEOUT) PIXEL_COPY_TIMEOUT
                        else "pixel_copy_failed", "pixel_copy_status=$status")
                }
                return Shot(bitmap, rect, layout, case.fill(layout),
                    request.owner, request.owner?.updates ?: 0, request.committedFrames)
            } catch (failure: Exception) {
                synchronized(destinationLock) {
                    abandoned = true
                    // A late callback releases its destination after a timeout. If request()
                    // threw without submitting, its unreferenced bitmap is reclaimed by GC.
                    if (completed) bitmap.recycle()
                }
                throw failure
            }
        }

        private fun pair(case: ProbeCase, ownerKey: String): Pair<Shot, Shot> {
            val old = capture(case, false, ownerKey, true)
            try {
                return old to capture(case, true, ownerKey, true)
            } catch (failure: Exception) {
                old.close()
                throw failure
            }
        }

        internal fun execute(results: ProbeResults) {
            for (case in ordinaryCases()) {
                results.record(case) { row ->
                    val (old, new) = pair(case, "${case.sample.name}/${case.density}")
                    try { evaluate(row, case, old, new) }
                    finally { old.close(); new.close() }
                }
            }
            for (density in DENSITIES) ownership(density, results)
        }

        private fun ownership(density: Float, results: ProbeResults) {
            val sample = SAMPLES.first()
            val keyA = "reuse_A/$density"
            val keyB = "reuse_B/$density"
            var nextId = results.count
            fun spec(kind: String, fill: Float) = ProbeCase(nextId++, sample, density, 1f, DARK,
                FillRule.UTF16_INTERIOR, kind, fill, current = false)
            var baselineB: Shot? = null
            var previousA: Shot? = null
            var ownerA: LyricWordBrush? = null
            try {
                val seed = spec("owner_B_seed", 2.5f)
                results.record(seed) { row ->
                    val (old, new) = pair(seed, keyB)
                    baselineB = new
                    try { evaluate(row, seed, old, new) }
                    finally { old.close() }
                }
                for (fill in listOf(0f, 1.25f, 5.75f, sample.text.length.toFloat(), 0f)) {
                    val update = spec("owner_A_update", fill)
                    results.record(update) { row ->
                        val (old, new) = pair(update, keyA)
                        try {
                            evaluate(row, update, old, new)
                            val previous = previousA
                            if (previous != null) {
                                val change = difference(previous.bitmap, new.bitmap)
                                row.put("changedPixels", change.differentPixels)
                                row.requireResult(change.differentPixels > 0, "uniform_update_did_not_change_pixels")
                                row.requireResult(new.owner === ownerA, "owner_A_shader_replaced")
                                row.requireResult(new.updates > previous.updates, "uniform_update_not_observed")
                            }
                            baselineB?.let {
                                row.requireResult(it.owner !== new.owner && it.owner?.shader !== new.owner?.shader,
                                    "owners_share_shader")
                            }
                            ownerA = new.owner
                            previousA = new
                            previous?.close()
                        } finally {
                            old.close()
                            if (previousA !== new) new.close()
                        }
                    }
                    val untouched = spec("owner_B_unchanged", 2.5f)
                    results.record(untouched) { row ->
                        val baseline = checkNotNull(baselineB) { "owner_B_seed_unavailable" }
                        // Do NOT update B's uniforms after A changes. Re-render B's shader as-is.
                        val new = capture(untouched, true, keyB, update = false)
                        try {
                            evaluate(row, untouched, baseline, new)
                            row.requireResult(new.owner === baseline.owner, "owner_B_shader_replaced")
                            row.requireResult(new.updates == baseline.updates, "owner_B_was_updated")
                        } finally { new.close() }
                    }
                }
            } finally { baselineB?.close(); previousA?.close() }
        }
    }
}

@Composable
private fun ProofRow(request: FrameRequest) {
    val case = request.case
    val sample = case.sample
    val density = LocalDensity.current
    Box(Modifier.width(sample.widthDp.dp).height(96.dp)
        .onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            val x = position.x.roundToInt()
            val y = position.y.roundToInt()
            if (abs(position.x - x) > 0.001f || abs(position.y - y) > 0.001f) {
                request.fail("fractional_capture_origin")
            }
            request.rect = Rect(x, y, x + coordinates.size.width, y + coordinates.size.height)
        }
        .background(Color(case.background))
        .drawWithContent {
            drawContent()
            request.composeHardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
            request.rootDrawn = true
            request.afterContentDraw()
        }) {
        Text(sample.text, Modifier.fillMaxWidth().graphicsLayer { alpha = case.baseAlpha },
            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { request.baseLayout = it })

        val group = Modifier.fillMaxWidth().graphicsLayer {
            // Remove ONLY the current word-mask offscreen. A fading/old row still needs
            // group alpha, including at emphasis=1. Never use ModulateAlpha.
            compositingStrategy = if (!request.candidate || !case.current) {
                CompositingStrategy.Offscreen
            } else CompositingStrategy.Auto
            alpha = case.emphasis
        }
        val paint = if (request.candidate) Modifier.drawWithContent {
            val layout = request.overlayLayout
            if (layout == null) request.fail("candidate_draw_before_text_layout")
            else {
                try {
                    if (request.updateUniforms) checkNotNull(request.owner).update(case.fill(layout), layout, density)
                    drawContent()
                } catch (failure: Exception) {
                    request.fail("candidate_draw", failure)
                }
            }
        } else legacyMask(request)
        Text(sample.text, group.then(paint),
            color = if (request.candidate) Color.Unspecified else Color.White,
            style = if (request.candidate) LocalTextStyle.current.copy(brush = checkNotNull(request.brush))
                else LocalTextStyle.current,
            fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { request.overlayLayout = it })
    }
}

/**
 * Independent copy of KaraokeLine's overlay mask and drawLyricWordBoundary.
 * For old rows case.fill is the already-retained progress, sampled without animation.
 */
private fun legacyMask(request: FrameRequest) = Modifier.drawWithCache {
    val boundary = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
        startX = 0f, endX = 24.dp.toPx())
    onDrawWithContent {
        val result = request.overlayLayout
        if (result == null) request.fail("reference_draw_before_text_layout")
        else try {
            val text = request.case.sample.text
            val progress = request.case.fill(result)
            if (progress > 0f) {
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
                        val shift = x - 12.dp.toPx()
                        translate(left = shift) {
                            drawRect(boundary, topLeft = Offset(-shift, 0f), size = size,
                                blendMode = BlendMode.DstIn)
                        }
                    }
                    if (bottom < size.height) drawRect(Color.Transparent, Offset(0f, bottom),
                        Size(size.width, size.height - bottom), blendMode = BlendMode.Clear)
                }
            }
        } catch (failure: Exception) {
            request.fail("reference_draw", failure)
        }
    }
}

private class Shot(
    val bitmap: Bitmap,
    val rect: Rect,
    val layout: TextLayoutResult,
    val fill: Float,
    val owner: LyricWordBrush?,
    val updates: Int,
    val frames: Int,
) {
    fun close() = bitmap.recycle()
}

private fun sameGeometry(a: TextLayoutResult, b: TextLayoutResult): Boolean {
    if (a.size != b.size || a.lineCount != b.lineCount || a.layoutInput.text != b.layoutInput.text) return false
    for (line in 0 until a.lineCount) {
        if (a.getLineStart(line) != b.getLineStart(line) || a.getLineEnd(line) != b.getLineEnd(line) ||
            a.isLineEllipsized(line) != b.isLineEllipsized(line) ||
            a.getLineTop(line) != b.getLineTop(line) || a.getLineBottom(line) != b.getLineBottom(line) ||
            a.getLineBaseline(line) != b.getLineBaseline(line) ||
            a.getLineLeft(line) != b.getLineLeft(line) || a.getLineRight(line) != b.getLineRight(line)) return false
    }
    return (0..a.layoutInput.text.length).all {
        a.getHorizontalPosition(it, true) == b.getHorizontalPosition(it, true)
    }
}

private data class Difference(
    val maximum: Int,
    val differentPixels: Int,
    val x: Int,
    val y: Int,
    val old: Int,
    val new: Int,
)

private fun difference(old: Bitmap, new: Bitmap): Difference {
    check(old.width == new.width && old.height == new.height) { "bitmap_size_mismatch" }
    val a = IntArray(old.width * old.height)
    val b = IntArray(a.size)
    old.getPixels(a, 0, old.width, 0, 0, old.width, old.height)
    new.getPixels(b, 0, new.width, 0, 0, new.width, new.height)
    var maximum = 0
    var bad = 0
    var worst = 0
    for (i in a.indices) {
        var delta = 0
        // Every pixel, including all edges; every R/G/B/A component, including alpha.
        for (shift in 0..24 step 8) {
            delta = maxOf(delta, abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255)))
        }
        if (delta > TOLERANCE) bad++
        if (delta > maximum) { maximum = delta; worst = i }
    }
    return Difference(maximum, bad, worst % old.width, worst / old.width, a[worst], b[worst])
}

private fun JSONObject.requireResult(condition: Boolean, reason: String, detail: String = reason) {
    if (!condition) {
        put("pass", 0)
        val errors = optJSONArray("errors") ?: JSONArray().also { put("errors", it) }
        errors.put(detail)
        val errorIds = optJSONArray("errorIds") ?: JSONArray().also { put("errorIds", it) }
        errorIds.put(reason)
    }
}

private fun evaluate(row: JSONObject, case: ProbeCase, old: Shot, new: Shot) {
    val delta = difference(old.bitmap, new.bitmap)
    row.put("hardware", 1) // Both captures reject non-hardware canvases before PixelCopy.
    row.put("framesOld", old.frames)
    row.put("framesNew", new.frames)
    row.put("rect", JSONArray(listOf(old.rect.left, old.rect.top, old.rect.width(), old.rect.height())))
    row.put("fill", old.fill.toDouble())
    row.put("lines", old.layout.lineCount)
    row.put("ellipsis", if (old.layout.isLineEllipsized(old.layout.lineCount - 1)) 1 else 0)
    row.put("shaderUpdates", new.updates)
    row.put("maxDelta", delta.maximum)
    row.put("differentPixels", delta.differentPixels)
    row.put("x", delta.x)
    row.put("y", delta.y)
    row.put("oldARGB", delta.old.toLong() and 0xffffffffL)
    row.put("newARGB", delta.new.toLong() and 0xffffffffL)
    row.requireResult(delta.maximum <= TOLERANCE, "pixel_mismatch")
    row.requireResult(old.rect == new.rect, "capture_rect_changed")
    row.requireResult(old.fill == new.fill && sameGeometry(old.layout, new.layout), "text_geometry_changed")
    row.requireResult(old.layout.lineCount == case.sample.lines, "fixture_line_count")
    row.requireResult(old.layout.isLineEllipsized(old.layout.lineCount - 1) == case.sample.ellipsis,
        "fixture_ellipsis")

    val pixels = IntArray(old.bitmap.width * old.bitmap.height)
    old.bitmap.getPixels(pixels, 0, old.bitmap.width, 0, 0, old.bitmap.width, old.bitmap.height)
    val ink = pixels.count { it != case.background }
    row.put("inkPixels", ink)
    row.requireResult(ink > 0, "reference_has_no_ink")
    if (case.sample.name == "colorEmoji" && case.background == DARK) {
        val colored = pixels.count {
            val r = (it ushr 16) and 255
            val g = (it ushr 8) and 255
            val b = it and 255
            maxOf(r, g, b) - minOf(r, g, b) > TOLERANCE
        }
        row.put("coloredPixels", colored)
        row.requireResult(colored > 0, "fixture_has_no_color_emoji")
    }
}

internal class ProbeResults(private val reportProgress: (Bundle) -> Unit) {
    private val rows = mutableListOf<JSONObject>()
    private var previousTimeoutId: String? = null
    val count get() = rows.size

    internal fun progressBundle() = Bundle().apply {
        val failed = rows.count { it.getInt("pass") == 0 }
        putInt("completed", rows.size)
        putInt("pass", rows.size - failed)
        putInt("fail", failed)
        putInt("maxDelta", rows.maxOfOrNull { it.getInt("maxDelta") } ?: -1)
        putInt("lastCase", rows.lastOrNull()?.getInt("id") ?: -1)
        val errorIds = linkedSetOf<String>()
        for (row in rows) {
            val ids = row.optJSONArray("errorIds") ?: continue
            for (index in 0 until ids.length()) errorIds.add(ids.getString(index))
        }
        if (errorIds.isNotEmpty()) putString("errorIds", JSONArray(errorIds).toString())
    }

    private fun initialRow(case: ProbeCase) = case.json().apply {
        put("pass", 1)
        put("hardware", -1)
        put("maxDelta", -1)
    }

    // Pixel mismatches and isolated timeouts continue. Two consecutive timeouts
    // at the same capture stage abort only after both failed rows are retained.
    internal fun record(case: ProbeCase, compare: (JSONObject) -> Unit) {
        val row = initialRow(case)
        var timeoutId: String? = null
        try { compare(row) }
        catch (failure: Exception) {
            val errorId = (failure as? ProbeFailure)?.errorId ?: "capture_exception"
            row.requireResult(false, errorId, "${failure.javaClass.simpleName}: ${failure.message}")
            if (errorId == DRAW_TIMEOUT || errorId == PIXEL_COPY_TIMEOUT) timeoutId = errorId
            if (failure.message?.contains("not_hardware") == true) row.put("hardware", 0)
        }
        rows.add(row)
        val repeatedTimeout = timeoutId != null && timeoutId == previousTimeoutId
        previousTimeoutId = timeoutId
        if (count <= 5 || count % PROGRESS_INTERVAL == 0 || repeatedTimeout) reportProgress(progressBundle())
        check(!repeatedTimeout) { "consecutive_capture_timeout: $timeoutId" }
    }

    internal fun bundle(fatal: String?): Bundle {
        val failed = rows.filter { it.getInt("pass") == 0 }
        val worst = rows.maxByOrNull { it.getInt("maxDelta") }
        return Bundle().apply {
            putInt("planned", ordinaryCases().size + DENSITIES.size * 11)
            putInt("count", rows.size)
            putInt("pass", rows.size - failed.size)
            putInt("fail", failed.size)
            putInt("unrun", ordinaryCases().size + DENSITIES.size * 11 - rows.size)
            putInt("tolerance", TOLERANCE)
            putInt("componentScale", 255)
            putInt("maxDelta", worst?.getInt("maxDelta") ?: -1)
            putInt("hardware", when {
                rows.any { it.getInt("hardware") == 0 } -> 0
                rows.isNotEmpty() && rows.all { it.getInt("hardware") == 1 } -> 1
                else -> -1
            })
            putIntArray("failedCase", failed.map { it.getInt("id") }.toIntArray())
            putInt("worstCase", worst?.getInt("id") ?: -1)
            putInt("x", worst?.optInt("x", -1) ?: -1)
            putInt("y", worst?.optInt("y", -1) ?: -1)
            putLong("oldARGB", worst?.optLong("oldARGB", -1L) ?: -1L)
            putLong("newARGB", worst?.optLong("newARGB", -1L) ?: -1L)
            putString("casesJson", JSONArray(rows).toString())
            putString("samplesJson", JSONArray(SAMPLES.map { sample -> JSONObject().apply {
                put("name", sample.name); put("text", sample.text); put("widthDp", sample.widthDp)
                put("expectedLines", sample.lines); put("expectedEllipsis", if (sample.ellipsis) 1 else 0)
            } }).toString())
            if (fatal != null) putString("fatal", fatal)
        }
    }
}

private fun await(latch: CountDownLatch, label: String, timeoutId: String? = null) {
    if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        val detail = "timeout_${TIMEOUT_SECONDS}s: $label"
        if (timeoutId != null) throw ProbeFailure(timeoutId, detail)
        error(detail)
    }
}

private fun <T> onMain(action: () -> T): T {
    check(Looper.myLooper() != Looper.getMainLooper()) { "runner_must_not_block_UI" }
    val task = FutureTask(Callable { action() })
    Handler(Looper.getMainLooper()).post(task)
    try {
        return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } finally {
        if (!task.isDone) task.cancel(false)
    }
}

/** Same-APK platform runner: adb shell am instrument -w .../.ProbeInstrumentation. */
class ProbeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = ProbeResults { sendStatus(0, it) }
        var activity: ProbeActivity? = null
        var fatal: String? = null
        val launched = AtomicReference<ProbeActivity>()
        val stopStarting = AtomicBoolean(false)
        var activityDestroyed = false
        try {
            // startActivitySync itself can block; bound startup without blocking the main thread.
            val launch = FutureTask(Callable {
                (startActivitySync(Intent(targetContext, ProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProbeActivity).also {
                    launched.set(it)
                    if (stopStarting.get()) Handler(Looper.getMainLooper()).post { it.finish() }
                }
            })
            Thread(launch, "renderprobe-start").apply { isDaemon = true; start() }
            activity = launch.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            activity.runner().execute(results)
        } catch (failure: Exception) {
            fatal = "${failure.javaClass.simpleName}: ${failure.message}"
        } finally {
            stopStarting.set(true)
            val closing = activity ?: launched.get()
            if (closing != null) {
                try {
                    onMain { closing.finish() }
                    await(closing.destroyed, "activity_destroy")
                    activityDestroyed = true
                } catch (failure: Exception) {
                    fatal = listOfNotNull(fatal, "cleanup: ${failure.javaClass.simpleName}: ${failure.message}")
                        .joinToString("; ")
                }
            }
        }
        val bundle = results.bundle(fatal)
        bundle.putInt("activityDestroyed", if (activityDestroyed) 1 else 0)
        val success = fatal == null && bundle.getInt("unrun") == 0 && bundle.getInt("fail") == 0 &&
            bundle.getInt("hardware") == 1 && activityDestroyed
        bundle.putInt("success", if (success) 1 else 0)
        sendStatus(0, results.progressBundle())
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, bundle)
    }
}
