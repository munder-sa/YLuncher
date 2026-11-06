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
import android.util.Log
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as DrawRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

private const val EDGE_TIMEOUT_SECONDS = 15L
private const val EDGE_TOLERANCE = 1
private const val EDGE_DRAW_TIMEOUT = "draw_and_two_frame_commits_timeout"
private const val EDGE_COPY_TIMEOUT = "pixel_copy_timeout"
private const val EDGE_DARK = 0xFF121212.toInt()
private const val EDGE_COLOR = 0xFF19354B.toInt()
private const val EDGE_TAG = "EdgeFadeProbe"

private enum class EdgeStrategy(val wireName: String, val code: Int) {
    FULL("full", 0), BANDS("bands", 1), NONE("none", 2);
}

private enum class EdgeFill { ZERO, INTERIOR, END }

private data class EdgeText(val name: String, val text: String, val interior: Float,
                            val lines: Int, val ellipsis: Boolean = false,
                            val rtl: Boolean = false)

// Original dummy strings only. Every scene contains every text category.
private val EDGE_TEXTS = listOf(
    EdgeText("single", "MINT kite", 2.5f, 1),
    EdgeText("multiline", "LIME arc\nVIOLET dot", 11.5f, 2),
    EdgeText("ellipsis", "MINT bridge LIME kite VIOLET dot MINT bridge LIME kite " +
        "VIOLET dot MINT bridge LIME kite VIOLET dot MINT bridge", 4.5f, 2, ellipsis = true),
    EdgeText("combining", "A\u0301V e\u0301 o\u0308 mint", 1.5f, 1),
    EdgeText("colorEmoji", "A 😀 🌈 B", 3.5f, 1),
    EdgeText("rtl", "אבג AB דה", 1.5f, 1, rtl = true),
)

private data class EdgeCase(
    val id: Int,
    val density: Float,
    val scale: Float,
    val emphasis: Float,
    val background: Int,
    val fill: EdgeFill,
    val heightDp: Int,
    val widthDp: Int = 240,
) {
    val motionDp get() = if (emphasis == 1f) 1.25f else -2.5f
    val widthPx get() = (widthDp * density).roundToInt()
    val heightPx get() = (heightDp * density).roundToInt()
    val fallback get() = heightPx <= 2f * 32f * density

    fun progress(text: EdgeText, animated: State<Float>?): Float =
        animated?.value?.let { it * text.text.length } ?: when (fill) {
            EdgeFill.ZERO -> 0f
            EdgeFill.INTERIOR -> text.interior
            EdgeFill.END -> text.text.length.toFloat()
        }

    fun json() = JSONObject().apply {
        put("id", id); put("density", density.toDouble()); put("fontScale", 1)
        put("scale", scale.toDouble()); put("alpha", emphasis.toDouble()); put("baseAlpha", 0.35)
        put("motionDp", motionDp.toDouble()); put("backgroundARGB", background.toLong() and 0xffffffffL)
        put("fill", fill.name); put("widthDp", widthDp); put("heightDp", heightDp)
        put("edgeDp", 32); put("fallback", fallback)
    }
}

private fun edgeCases(): List<EdgeCase> = buildList {
    for (density in listOf(1f, 1.4875f, 2f)) for (scale in listOf(1f, 0.9f)) {
        for (alpha in listOf(1f, 0.5f)) for (background in listOf(EDGE_DARK, EDGE_COLOR)) {
            for (fill in EdgeFill.entries) for (height in listOf(192, 64, 48)) {
                add(EdgeCase(size, density, scale, alpha, background, fill, height))
            }
        }
    }
}

private class EdgeFailure(val errorId: String, detail: String = errorId) : IllegalStateException(detail)

private class EdgeRowGeometry {
    var base: TextLayoutResult? = null
    var overlay: TextLayoutResult? = null
    var top = Float.NaN
    var height = 0
    var scale = Float.NaN
    var baseAlpha = Float.NaN
    var overlayAlpha = Float.NaN
    var placed = false
}

/** Counts explicit edge operations, not the Text/word/alpha layers common to all strategies. */
private class EdgeDrawOps {
    var records = 0
    var replays = 0
    var saves = 0
    var content = 0
    var fullLayers = 0 // Configured Offscreen layers; not an observed native saveLayer count.
    var area = 0.0 // Requested compositing rectangle area, NOT physical GPU allocation.
    var composeDuringRecord = 0
    var measureDuringRecord = 0
    var composeDuringReplay = 0
    var measureDuringReplay = 0
    var gradientStops: List<Float> = emptyList()
    val clips = mutableListOf<DrawRect>()
}

private class EdgeDrawCounts {
    var draws = 0
    var totalRecords = 0
    var totalReplays = 0
    var totalSaves = 0
    var maxRecords = 0
    var last: EdgeDrawOps? = null

    fun accept(ops: EdgeDrawOps) {
        draws++; totalRecords += ops.records; totalReplays += ops.replays; totalSaves += ops.saves
        maxRecords = maxOf(maxRecords, ops.records)
        last = ops
    }

    fun json() = JSONObject().apply {
        put("contentDrawPasses", draws); put("recordTotal", totalRecords)
        put("drawLayerTotal", totalReplays); put("saveLayerTotal", totalSaves)
        put("maxRecordPerPass", maxRecords)
        last?.let {
            put("record", it.records); put("drawLayer", it.replays); put("saveLayer", it.saves)
            put("drawContent", it.content); put("configuredFullOffscreen", it.fullLayers)
            put("compositeAreaPx2", it.area)
            put("composeDuringRecord", it.composeDuringRecord)
            put("measureDuringRecord", it.measureDuringRecord)
            put("composeDuringReplay", it.composeDuringReplay)
            put("measureDuringReplay", it.measureDuringReplay)
            put("gradientStops", JSONArray(it.gradientStops))
            put("clips", JSONArray(it.clips.map { rect ->
                JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
            }))
        }
    }
}

private class EdgeRequest(val case: EdgeCase, val strategy: EdgeStrategy) {
    val ready = CountDownLatch(1)
    val rows = List(EDGE_TEXTS.size) { EdgeRowGeometry() }
    val counts = EdgeDrawCounts()
    var compositions = 0
    var rowCompositions = 0
    var measures = 0
    var placements = 0
    var rect: Rect? = null
    var rootDrawn = false
    var composeHardware = false
    var viewHardware = false
    var commits = 0
    var commitPending = false
    var error: EdgeFailure? = null
    var afterContentDraw: () -> Unit = {}

    fun fail(id: String, cause: Exception? = null) {
        if (error == null) error = EdgeFailure(id,
            if (cause == null) id else "$id: ${cause.javaClass.simpleName}: ${cause.message}")
        ready.countDown()
    }

    fun drawing(block: (EdgeDrawOps) -> Unit) {
        val ops = EdgeDrawOps()
        try { block(ops) }
        catch (failure: Exception) { fail("edge_draw_exception", failure) }
        finally { counts.accept(ops) }
    }

    fun diagnostics() = counts.json().apply {
        put("compositions", compositions); put("measures", measures); put("placements", placements)
        put("rowCompositions", rowCompositions)
        put("commits", commits)
        put("hardware", if (!rootDrawn) -1 else if (composeHardware && viewHardware) 1 else 0)
    }
}

/** The old 663-case probe and production LyricsPane are deliberately not referenced here. */
class EdgeFadeActivity : ComponentActivity() {
    private var frame by mutableStateOf<EdgeRequest?>(null)
    private lateinit var host: FrameLayout
    private lateinit var content: ComposeView
    private var perf = false
    private var perfStarted = false
    private var perfFrames = 0L
    private var perfStrategy = EdgeStrategy.FULL
    internal val destroyed = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = if (intent.hasExtra("mode")) requireNotNull(intent.getStringExtra("mode")) {
            "EdgeFade mode must be a string"
        } else "proof"
        require(mode == "proof" || mode == "perf") { "unknown EdgeFade mode=$mode" }
        val strategy = intent.getStringExtra("strategy")
        perf = mode == "perf"
        if (perf) {
            perfStrategy = EdgeStrategy.entries.singleOrNull { it.wireName == strategy }
                ?: error("mode=perf requires strategy=full|bands|none; got $strategy")
            frame = EdgeRequest(EdgeCase(-1, 1.4875f, 1f, 1f, EDGE_COLOR,
                EdgeFill.ZERO, heightDp = 400, widthDp = 320), perfStrategy)
        } else require(!intent.hasExtra("strategy")) { "strategy is only valid with mode=perf" }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host = object : FrameLayout(this) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                afterWindowDraw(canvas.isHardwareAccelerated)
            }
        }
        content = ComposeView(this).apply {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    // The backdrop is outside every fade layer, including the capture ROI backdrop.
                    Box(Modifier.fillMaxSize().background(Color(EDGE_DARK)), contentAlignment = Alignment.Center) {
                        frame?.let { request ->
                            CompositionLocalProvider(
                                LocalDensity provides Density(request.case.density, fontScale = 1f),
                                LocalLayoutDirection provides LayoutDirection.Ltr,
                            ) {
                                key(request) {
                                    val progress = if (perf) remember { mutableFloatStateOf(0f) } else null
                                    if (progress != null) LaunchedEffect(request) {
                                        while (true) withFrameNanos { nanos ->
                                            // Identical frame-index sequence for all three strategies.
                                            progress.floatValue = (perfFrames % 240L).toFloat() / 239f
                                            perfFrames++
                                            if (perfStarted && perfFrames % 600L == 0L) {
                                                Log.i(EDGE_TAG, "event=1 strategy=${perfStrategy.code} frames=$perfFrames frameNs=$nanos")
                                            }
                                        }
                                    }
                                    EdgeScene(request, progress)
                                }
                            }
                        }
                    }
                }
            }
        }
        host.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(host)
        frame?.afterContentDraw = { afterWindowDraw(frame?.composeHardware == true) }
    }

    private fun afterWindowDraw(hardware: Boolean) {
        val request = frame ?: return
        if (perf) {
            if (!request.rootDrawn || isFinishing) return
            request.viewHardware = hardware && host.isHardwareAccelerated
            try {
                check(request.composeHardware && request.viewHardware) { "perf_not_hardware" }
                request.error?.let { throw it }
                if (perfStarted) return
                validateRect(request)
                perfStarted = true
                Log.i(EDGE_TAG, "event=0 strategy=${perfStrategy.code} frames=$perfFrames " +
                    "widthPx=${request.case.widthPx} heightPx=${request.case.heightPx} density=1.4875")
            } catch (failure: Exception) {
                Log.e(EDGE_TAG, "perf_failed: ${failure.message}")
                setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", failure.message))
                finish()
            }
            return
        }
        if (request.ready.count == 0L || request.commitPending) return
        request.viewHardware = hardware && host.isHardwareAccelerated
        if (!request.viewHardware) { request.fail("window_canvas_not_hardware"); return }
        if (!request.rootDrawn || request.rect == null ||
            request.rows.any { it.base == null || it.overlay == null || !it.placed }) return
        if (!request.composeHardware) { request.fail("compose_canvas_not_hardware"); return }
        request.commitPending = true
        host.viewTreeObserver.registerFrameCommitCallback {
            host.post {
                if (frame !== request || request.ready.count == 0L) return@post
                request.commits++
                if (request.commits == 2) request.ready.countDown()
                else host.postOnAnimation {
                    if (frame === request && request.ready.count != 0L) {
                        request.commitPending = false
                        host.invalidate()
                    }
                }
            }
        }
        // Registration happens after ViewRootImpl collected this draw's callbacks.
        // Always submit another frame, including while commitPending remains true.
        host.postOnAnimation {
            if (frame === request && request.ready.count != 0L) host.invalidate()
        }
    }

    private fun validateRect(request: EdgeRequest) {
        val rect = checkNotNull(request.rect)
        check(rect.width() == request.case.widthPx && rect.height() == request.case.heightPx) {
            "probe_rect_clipped_or_resized $rect"
        }
        val decor = window.decorView
        check(rect.left >= 0 && rect.top >= 0 && rect.right <= decor.width && rect.bottom <= decor.height) {
            "probe_rect_outside_window $rect"
        }
    }

    private fun present(case: EdgeCase, strategy: EdgeStrategy): EdgeRequest {
        check(Looper.myLooper() == Looper.getMainLooper() && !perf)
        return EdgeRequest(case, strategy).also { request ->
            // Cached parents need not dispatchDraw when a Compose child records again.
            request.afterContentDraw = {
                if (frame === request) afterWindowDraw(request.composeHardware)
            }
            frame = request
            host.invalidate()
        }
    }

    override fun onDestroy() {
        if (::content.isInitialized) content.disposeComposition() // Releases remembered GraphicsLayers.
        if (perfStarted) Log.i(EDGE_TAG, "event=2 strategy=${perfStrategy.code} frames=$perfFrames")
        super.onDestroy()
        destroyed.countDown()
    }

    internal fun runProof(report: (Bundle) -> Unit): EdgeProofRunner = EdgeProofRunner(report)

    internal inner class EdgeProofRunner(private val report: (Bundle) -> Unit) {
        private val cases = edgeCases()
        private val results = cases.map { it.json().apply {
            put("status", "unrun"); put("pass", -1); put("hardware", -1); put("maxDelta", -1)
            put("x", -1); put("y", -1)
        } }
        private val bitmaps = EdgeBitmaps()
        private var abortReason: String? = null

        private fun capture(case: EdgeCase, strategy: EdgeStrategy, row: JSONObject): EdgeShot {
            val request = edgeOnMain(EDGE_DRAW_TIMEOUT) { present(case, strategy) }
            var copy: EdgeCopy? = null
            try {
                edgeAwait(request.ready, EDGE_DRAW_TIMEOUT)
                request.error?.let { throw it }
                check(request.composeHardware && request.viewHardware && request.commits == 2)
                val rect = edgeOnMain(EDGE_DRAW_TIMEOUT) {
                    check(hasWindowFocus()) { "probe_window_not_focused" }
                    validateRect(request)
                    Rect(checkNotNull(request.rect))
                }
                val destination = EdgeCopy(Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888), bitmaps)
                copy = destination
                edgeOnMain(EDGE_COPY_TIMEOUT) { destination.submit(this@EdgeFadeActivity, rect) }
                edgeAwait(destination.ready, EDGE_COPY_TIMEOUT)
                if (destination.status != PixelCopy.SUCCESS) {
                    throw EdgeFailure(if (destination.status == PixelCopy.ERROR_TIMEOUT) EDGE_COPY_TIMEOUT
                        else "pixel_copy_failed", "pixel_copy_status=${destination.status}")
                }
                return EdgeShot(destination, rect, request)
            } catch (failure: Exception) {
                copy?.close()
                throw failure
            } finally {
                // Keep partial capture evidence even if the second capture fails.
                row.put(strategy.wireName, request.diagnostics())
                if (request.rootDrawn && (!request.composeHardware || !request.viewHardware)) row.put("hardware", 0)
            }
        }

        internal fun execute() {
            var previousTimeout: String? = null
            for ((index, case) in cases.withIndex()) {
                val row = results[index]
                row.put("pass", 1)
                var timeout: String? = null
                try {
                    val old = capture(case, EdgeStrategy.FULL, row)
                    try {
                        val candidate = capture(case, EdgeStrategy.BANDS, row)
                        try { evaluateEdge(row, case, old, candidate) }
                        finally { candidate.close() }
                    } finally { old.close() }
                } catch (failure: Exception) {
                    val id = (failure as? EdgeFailure)?.errorId ?: "capture_exception"
                    row.edgeRequire(false, id, "${failure.javaClass.simpleName}: ${failure.message}")
                    if (id == EDGE_DRAW_TIMEOUT || id == EDGE_COPY_TIMEOUT) timeout = id
                }
                row.put("status", if (row.getInt("pass") == 1) "pass" else "fail")
                val repeated = timeout != null && timeout == previousTimeout
                previousTimeout = timeout
                if (repeated) abortReason = "two_consecutive_$timeout"
                if (index < 5 || (index + 1) % 25 == 0 || repeated) report(bundle(includeCases = false))
                if (repeated) break // Both failures AND all remaining unrun rows are retained.
            }
        }

        internal fun cleanupBitmaps() = bitmaps.awaitReleased()

        internal fun bundle(includeCases: Boolean = true) = Bundle().apply {
            val ran = results.filter { it.getInt("pass") >= 0 }
            val failed = ran.filter { it.getInt("pass") == 0 }
            val worst = ran.maxByOrNull { it.getInt("maxDelta") }
            putInt("planned", cases.size); putInt("count", ran.size)
            putInt("pass", ran.size - failed.size); putInt("fail", failed.size)
            putInt("unrun", cases.size - ran.size); putInt("tolerance", EDGE_TOLERANCE)
            putInt("componentScale", 255); putInt("maxDelta", worst?.getInt("maxDelta") ?: -1)
            putInt("worstCase", worst?.getInt("id") ?: -1)
            putInt("x", worst?.optInt("x", -1) ?: -1); putInt("y", worst?.optInt("y", -1) ?: -1)
            putString("component", worst?.optString("component", ""))
            putLong("oldARGB", worst?.optLong("oldARGB", -1L) ?: -1L)
            putLong("newARGB", worst?.optLong("newARGB", -1L) ?: -1L)
            putInt("hardware", when {
                ran.any { it.getInt("hardware") == 0 } -> 0
                ran.isNotEmpty() && ran.all { it.getInt("hardware") == 1 } -> 1
                else -> -1
            })
            putIntArray("failedCase", failed.map { it.getInt("id") }.toIntArray())
            putIntArray("unrunCase", results.filter { it.getInt("pass") < 0 }.map { it.getInt("id") }.toIntArray())
            putInt("bitmapsCreated", bitmaps.created); putInt("bitmapsRecycled", bitmaps.recycled)
            putInt("pendingBitmaps", bitmaps.created - bitmaps.recycled)
            abortReason?.let { putString("abortReason", it) }
            if (includeCases) {
                putString("casesJson", JSONArray(results).toString())
                putString("samplesJson", JSONArray(EDGE_TEXTS.map { sample -> JSONObject().apply {
                    put("name", sample.name); put("text", sample.text); put("interior", sample.interior.toDouble())
                    put("lines", sample.lines); put("ellipsis", sample.ellipsis); put("rtl", sample.rtl)
                } }).toString())
            }
        }
    }
}

@Composable
private fun EdgeScene(request: EdgeRequest, progress: State<Float>?) {
    val case = request.case
    SideEffect { request.compositions++ }
    val fade = when (request.strategy) {
        EdgeStrategy.FULL -> referenceEdgeFade(request, progress)
        EdgeStrategy.BANDS -> candidateEdgeFade(request, progress)
        EdgeStrategy.NONE -> Modifier.drawWithContent {
            progress?.value // Draw-only invalidation, identical to the other two strategies.
            request.drawing { ops ->
                ops.clips.add(DrawRect(0f, 0f, size.width, size.height))
                ops.content++
                drawContent()
            }
        }
    }
    Box(Modifier.width(case.widthDp.dp).height(case.heightDp.dp)
        .onGloballyPositioned { coordinates ->
            val p = coordinates.positionInWindow()
            val x = p.x.roundToInt(); val y = p.y.roundToInt()
            if (abs(p.x - x) > 0.001f || abs(p.y - y) > 0.001f) request.fail("fractional_capture_origin")
            request.rect = Rect(x, y, x + coordinates.size.width, y + coordinates.size.height)
        }
        .background(Color(case.background))
        .drawWithContent {
            drawContent()
            request.composeHardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
            request.rootDrawn = true
            request.afterContentDraw()
        }) {
        Layout(modifier = Modifier.fillMaxSize().clipToBounds().then(fade), content = {
            EDGE_TEXTS.forEachIndexed { index, sample ->
                CompositionLocalProvider(LocalLayoutDirection provides
                    if (sample.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    EdgeKaraokeRow(request, index, progress)
                }
            }
        }) { measurables, constraints ->
            request.measures++
            val rows = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
            val h = constraints.maxHeight.toFloat()
            val edge = 32.dp.toPx()
            // Cross the exterior and both internal band boundaries; the two middle rows overlap.
            val centers = listOf(0f, edge, h / 2f - 8.dp.toPx(), h / 2f + 8.dp.toPx(), h - edge, h)
            layout(constraints.maxWidth, constraints.maxHeight) {
                request.placements++
                rows.forEachIndexed { index, row ->
                    val targetTop = centers[index] - row.height / 2f
                    val y = targetTop.roundToInt()
                    row.placeRelativeWithLayer(0, y) {
                        val top = targetTop + case.motionDp * case.density
                        translationY = top - y
                        scaleX = case.scale; scaleY = case.scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        request.rows[index].apply {
                            this.top = top; height = row.height; scale = case.scale; placed = true
                        }
                    }
                }
            }
        }
    }
}

/** Independent literal reference: LyricsPane's full Offscreen + lyricEdgeBrush formula. */
private fun referenceEdgeFade(request: EdgeRequest, progress: State<Float>?) =
    Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithCache {
        val edge = 32.dp.toPx()
        val upper = edge / size.height
        val lower = (size.height - edge) / size.height
        val mask = Brush.verticalGradient(0f to Color.Transparent, upper to Color.Black,
            lower to Color.Black, 1f to Color.Transparent)
        onDrawWithContent {
            progress?.value
            request.drawing { ops ->
                ops.fullLayers = 1; ops.area = size.width.toDouble() * size.height
                ops.gradientStops = listOf(0f, upper, lower, 1f)
                ops.clips.add(DrawRect(0f, 0f, size.width, size.height)); ops.content++
                drawContent()
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
        }
    }

@Composable
private fun candidateEdgeFade(request: EdgeRequest, progress: State<Float>?): Modifier {
    if (request.case.fallback) {
        // Deliberately preserve the legacy crossed/duplicate stops for h <= 2*edge.
        // Do not sort, clamp or invent a different short-height fade.
        return Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithCache {
            val band = 32.dp.toPx()
            val firstOpaque = band / size.height
            val lastOpaque = (size.height - band) / size.height
            val gradient = Brush.verticalGradient(
                0f to Color.Transparent, firstOpaque to Color.Black,
                lastOpaque to Color.Black, 1f to Color.Transparent)
            onDrawWithContent {
                progress?.value
                request.drawing { ops ->
                    ops.fullLayers = 1; ops.area = size.width.toDouble() * size.height
                    ops.gradientStops = listOf(0f, firstOpaque, lastOpaque, 1f)
                    ops.clips.add(DrawRect(0f, 0f, size.width, size.height)); ops.content++
                    drawContent()
                    drawRect(gradient, blendMode = BlendMode.DstIn)
                }
            }
        }
    }
    val recorded = rememberGraphicsLayer()
    return Modifier.drawWithCache {
        val band = 32.dp.toPx()
        val w = size.width
        val h = size.height
        val top = DrawRect(0f, 0f, w, band)
        val middle = DrawRect(0f, band, w, h - band)
        val bottom = DrawRect(0f, h - band, w, h)
        val firstOpaque = band / h
        val lastOpaque = (h - band) / h
        // Coordinates are the WHOLE viewport's; neither band translates or rescales this brush.
        val gradient = Brush.verticalGradient(
            0f to Color.Transparent, firstOpaque to Color.Black,
            lastOpaque to Color.Black, 1f to Color.Transparent,
            startY = 0f, endY = h)
        val paint = Paint()
        onDrawWithContent {
            progress?.value
            request.drawing { ops ->
                ops.gradientStops = listOf(0f, firstOpaque, lastOpaque, 1f)
                val compositions = request.compositions + request.rowCompositions
                val measures = request.measures
                ops.records++
                // Use the draw-scope record extension, which redirects drawContent's context
                // to the recording canvas. Do not call the low-level density/size overload.
                recorded.record {
                    ops.content++
                    this@onDrawWithContent.drawContent()
                }
                ops.composeDuringRecord = request.compositions + request.rowCompositions - compositions
                ops.measureDuringRecord = request.measures - measures
                val replayCompositions = request.compositions + request.rowCompositions
                val replayMeasures = request.measures
                fun replay(bounds: DrawRect, mask: Boolean) {
                    ops.clips.add(bounds)
                    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                        if (mask) {
                            val canvas = drawContext.canvas
                            canvas.saveLayer(bounds, paint)
                            ops.saves++; ops.area += bounds.width.toDouble() * bounds.height
                            try {
                                ops.replays++; drawLayer(recorded)
                                drawRect(gradient, size = Size(w, h), blendMode = BlendMode.DstIn)
                            } finally { canvas.restore() }
                        } else {
                            ops.replays++; drawLayer(recorded)
                        }
                    }
                }
                replay(top, true)
                replay(middle, false)
                replay(bottom, true)
                ops.composeDuringReplay = request.compositions + request.rowCompositions - replayCompositions
                ops.measureDuringReplay = request.measures - replayMeasures
            }
        }
    }
}

@Composable
private fun EdgeKaraokeRow(request: EdgeRequest, index: Int, progress: State<Float>?) {
    SideEffect { request.rowCompositions++ }
    val sample = EDGE_TEXTS[index]
    val geometry = request.rows[index]
    Box(Modifier.fillMaxWidth()) {
        Text(sample.text, Modifier.fillMaxWidth().graphicsLayer {
            alpha = 0.35f
            geometry.baseAlpha = alpha
        },
            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { geometry.base = it })
        Text(sample.text, Modifier.fillMaxWidth().graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            alpha = request.case.emphasis
            geometry.overlayAlpha = alpha
        }.drawWithCache {
            val boundary = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                startX = 0f, endX = 24.dp.toPx())
            onDrawWithContent {
                val result = geometry.overlay
                if (result == null) request.fail("word_draw_before_layout")
                else {
                    val fill = request.case.progress(sample, progress)
                    if (fill > 0f) {
                        drawContent()
                        if (fill < sample.text.length) {
                            // Same UTF-16 boundary, RTL behavior and 24dp mask as KaraokeLine.
                            val offset = fill.toInt().coerceAtMost(sample.text.lastIndex)
                            val line = result.getLineForOffset(offset)
                            val startX = result.getHorizontalPosition(offset, true)
                            val nextX = if (offset + 1 < result.getLineEnd(line)) {
                                result.getHorizontalPosition(offset + 1, true)
                            } else result.getLineRight(line)
                            val x = startX + (nextX - startX) * (fill - offset)
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
                }
            }
        }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { geometry.overlay = it })
    }
}

/** Track destinations through late PixelCopy callbacks; never recycle an in-flight bitmap. */
private class EdgeBitmaps {
    private val lock = Object()
    var created = 0
        private set
    var recycled = 0
        private set

    fun acquired() = synchronized(lock) { created++ }
    fun release(bitmap: Bitmap) = synchronized(lock) {
        bitmap.recycle(); recycled++; lock.notifyAll()
    }

    fun awaitReleased(): Boolean = synchronized(lock) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EDGE_TIMEOUT_SECONDS)
        while (created != recycled) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return@synchronized false
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        true
    }
}

private class EdgeCopy(val bitmap: Bitmap, private val tracker: EdgeBitmaps) {
    private val lock = Any()
    val ready = CountDownLatch(1)
    var status = -1
        private set
    private var submitted = false
    private var completed = false
    private var closed = false
    private var recycled = false

    init { tracker.acquired() }

    fun submit(activity: Activity, rect: Rect) = synchronized(lock) {
        if (closed) return@synchronized
        submitted = true
        try {
            PixelCopy.request(activity.window, Rect(rect), bitmap, { result ->
                synchronized(lock) {
                    status = result; completed = true
                    if (closed) recycle()
                    ready.countDown()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (failure: Exception) {
            completed = true
            throw failure
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        if (!submitted || completed) recycle()
    }

    private fun recycle() {
        if (!recycled) { tracker.release(bitmap); recycled = true }
    }
}

private class EdgeShot(val copy: EdgeCopy, val rect: Rect, val request: EdgeRequest) {
    val bitmap get() = copy.bitmap
    fun close() = copy.close()
}

private fun JSONObject.edgeRequire(ok: Boolean, id: String, detail: String = id) {
    if (!ok) {
        put("pass", 0)
        (optJSONArray("errors") ?: JSONArray().also { put("errors", it) }).put(detail)
        (optJSONArray("errorIds") ?: JSONArray().also { put("errorIds", it) }).put(id)
    }
}

private fun edgeGeometryEqual(a: TextLayoutResult, b: TextLayoutResult): Boolean {
    if (a.size != b.size || a.lineCount != b.lineCount || a.layoutInput.text != b.layoutInput.text ||
        a.layoutInput.layoutDirection != b.layoutInput.layoutDirection) return false
    for (line in 0 until a.lineCount) {
        if (a.getLineStart(line) != b.getLineStart(line) || a.getLineEnd(line) != b.getLineEnd(line) ||
            a.isLineEllipsized(line) != b.isLineEllipsized(line) ||
            a.getLineTop(line) != b.getLineTop(line) || a.getLineBottom(line) != b.getLineBottom(line) ||
            a.getLineBaseline(line) != b.getLineBaseline(line) ||
            a.getLineLeft(line) != b.getLineLeft(line) || a.getLineRight(line) != b.getLineRight(line)) return false
    }
    return (0..a.layoutInput.text.length).all {
        a.getHorizontalPosition(it, true) == b.getHorizontalPosition(it, true) &&
            a.getHorizontalPosition(it, false) == b.getHorizontalPosition(it, false)
    }
}

private fun evaluateEdge(row: JSONObject, case: EdgeCase, old: EdgeShot, candidate: EdgeShot) {
    row.put("hardware", 1) // Both captures already rejected software and required two commits.
    row.put("rect", JSONArray(listOf(old.rect.left, old.rect.top, old.rect.width(), old.rect.height())))
    row.edgeRequire(old.rect == candidate.rect, "capture_rect_changed")
    // Preserve pixel evidence even if a later geometry/counter check throws.
    compareEdgePixels(row, case, old.bitmap, candidate.bitmap)
    val geometry = JSONArray()
    for (i in EDGE_TEXTS.indices) {
        val a = old.request.rows[i]; val b = candidate.request.rows[i]
        val baseA = checkNotNull(a.base); val textA = checkNotNull(a.overlay)
        val baseB = checkNotNull(b.base); val textB = checkNotNull(b.overlay)
        row.edgeRequire(edgeGeometryEqual(baseA, textA) && edgeGeometryEqual(baseB, textB) &&
            edgeGeometryEqual(textA, textB), "text_geometry_$i")
        row.edgeRequire(a.top == b.top && a.height == b.height && a.scale == b.scale &&
            a.scale == case.scale, "row_transform_$i")
        row.edgeRequire(a.baseAlpha == 0.35f && b.baseAlpha == 0.35f &&
            a.overlayAlpha == case.emphasis && b.overlayAlpha == case.emphasis, "row_alpha_$i")
        row.edgeRequire(textA.lineCount == EDGE_TEXTS[i].lines &&
            textA.isLineEllipsized(textA.lineCount - 1) == EDGE_TEXTS[i].ellipsis, "fixture_text_$i")
        geometry.put(JSONArray(listOf(a.top, a.height, a.scale, textA.lineCount,
            textA.isLineEllipsized(textA.lineCount - 1), case.progress(EDGE_TEXTS[i], null),
            a.baseAlpha, a.overlayAlpha)))
    }
    row.put("rows", geometry) // [topPx, heightPx, scale, lines, ellipsis, fill, baseAlpha, overlayAlpha].
    fun visualRange(index: Int): Pair<Float, Float> {
        val g = old.request.rows[index]
        val top = g.top + (1f - g.scale) * g.height / 2f
        return top to top + g.height * g.scale
    }
    val edge = 32f * case.density
    val width = case.widthPx.toFloat(); val height = case.heightPx.toFloat()
    for ((index, boundary) in listOf(0 to 0f, 1 to edge, 4 to height - edge, 5 to height)) {
        val range = visualRange(index)
        row.edgeRequire(range.first < boundary && range.second > boundary, "fixture_crossing_$index")
    }
    val a = visualRange(2); val b = visualRange(3)
    row.edgeRequire(maxOf(a.first, b.first) < minOf(a.second, b.second), "fixture_overlap")

    // Independent numeric oracle. It does not use the candidate's split helper or image.
    val full = DrawRect(0f, 0f, width, height)
    val expectedClips = if (height <= 64f * case.density) listOf(full) else listOf(
        DrawRect(0f, 0f, width, 32f * case.density),
        DrawRect(0f, 32f * case.density, width, height - 32f * case.density),
        DrawRect(0f, height - 32f * case.density, width, height))
    val ref = checkNotNull(old.request.counts.last)
    val bands = checkNotNull(candidate.request.counts.last)
    val expectedStops = listOf(0f, edge / height, (height - edge) / height, 1f)
    row.edgeRequire(ref.gradientStops == expectedStops, "reference_gradient_stops")
    row.edgeRequire(bands.gradientStops == expectedStops, "candidate_gradient_stops")
    row.edgeRequire(ref.clips == listOf(full) && ref.fullLayers == 1 && ref.content == 1 &&
        ref.records == 0 && ref.replays == 0 && ref.saves == 0 &&
        ref.area == width.toDouble() * height, "reference_operations")
    row.edgeRequire(bands.clips == expectedClips, "candidate_clips")
    if (case.fallback) {
        row.edgeRequire(bands.records == 0 && bands.replays == 0 && bands.saves == 0 &&
            bands.fullLayers == 1 && bands.content == 1 && bands.area == ref.area &&
            candidate.request.counts.totalRecords == 0 && candidate.request.counts.totalReplays == 0 &&
            candidate.request.counts.totalSaves == 0, "fallback_operations")
    } else {
        // Allow only Float rectangle arithmetic rounding here, never pixel/color tolerance changes.
        val areaError = abs(bands.area - 2.0 * width * edge)
        row.edgeRequire(bands.records == 1 && candidate.request.counts.maxRecords == 1 &&
            bands.replays == 3 && bands.saves == 2 && bands.fullLayers == 0 && bands.content == 1 &&
            bands.composeDuringRecord == 0 && bands.measureDuringRecord == 0 &&
            bands.composeDuringReplay == 0 && bands.measureDuringReplay == 0 &&
            candidate.request.counts.totalRecords == candidate.request.counts.draws &&
            candidate.request.counts.totalReplays == 3 * candidate.request.counts.draws &&
            candidate.request.counts.totalSaves == 2 * candidate.request.counts.draws &&
            areaError <= width * Math.ulp(height).toDouble(), "band_operations")
    }
}

private fun compareEdgePixels(row: JSONObject, case: EdgeCase, old: Bitmap, candidate: Bitmap) {
    check(old.width == candidate.width && old.height == candidate.height) { "bitmap_size_mismatch" }
    val a = IntArray(old.width * old.height); val b = IntArray(a.size)
    old.getPixels(a, 0, old.width, 0, 0, old.width, old.height)
    candidate.getPixels(b, 0, old.width, 0, 0, old.width, old.height)
    val shifts = intArrayOf(16, 8, 0, 24) // RGBA, including every border pixel.
    val maxima = IntArray(4)
    val regionMax = IntArray(3)
    val ink = IntArray(3)
    var maximum = 0; var bad = 0; var worst = 0; var component = 0; var colored = 0
    for (i in a.indices) {
        val y = i / old.width + 0.5f
        val region = if (y < 32f * case.density) 0 else if (y >= old.height - 32f * case.density) 2 else 1
        var delta = 0
        for (c in shifts.indices) {
            val diff = abs(((a[i] ushr shifts[c]) and 255) - ((b[i] ushr shifts[c]) and 255))
            maxima[c] = maxOf(maxima[c], diff)
            delta = maxOf(delta, diff)
            if (diff > maximum) { maximum = diff; worst = i; component = c }
        }
        if (delta > EDGE_TOLERANCE) bad++
        regionMax[region] = maxOf(regionMax[region], delta)
        if (a[i] != case.background) ink[region]++
        if (case.background == EDGE_DARK) {
            val r = (a[i] ushr 16) and 255; val g = (a[i] ushr 8) and 255; val blue = a[i] and 255
            if (maxOf(r, g, blue) - minOf(r, g, blue) > EDGE_TOLERANCE) colored++
        }
    }
    row.put("maxDelta", maximum); row.put("differentPixels", bad)
    row.put("rgbaMax", JSONArray(maxima.toList())); row.put("regionMax", JSONArray(regionMax.toList()))
    row.put("ink", JSONArray(ink.toList())); row.put("x", worst % old.width); row.put("y", worst / old.width)
    row.put("component", listOf("R", "G", "B", "A")[component])
    row.put("oldARGB", a[worst].toLong() and 0xffffffffL); row.put("newARGB", b[worst].toLong() and 0xffffffffL)
    row.edgeRequire(maximum <= EDGE_TOLERANCE, "pixel_mismatch")
    row.edgeRequire(ink.sum() > 0, "reference_no_ink")
    if (!case.fallback) row.edgeRequire(ink.all { it > 0 }, "reference_missing_band_ink")
    if (case.background == EDGE_DARK) {
        row.put("coloredPixels", colored)
        row.edgeRequire(colored > 0, "fixture_no_color_emoji")
    }
}

private fun edgeAwait(latch: CountDownLatch, id: String) {
    check(Looper.myLooper() != Looper.getMainLooper()) { "runner_must_not_block_UI" }
    if (!latch.await(EDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw EdgeFailure(id, "$id after ${EDGE_TIMEOUT_SECONDS}s")
}

private fun <T> edgeOnMain(timeoutId: String = "main_dispatch_timeout", action: () -> T): T {
    check(Looper.myLooper() != Looper.getMainLooper()) { "runner_must_not_block_UI" }
    val task = FutureTask(Callable { action() })
    Handler(Looper.getMainLooper()).post(task)
    try { return task.get(EDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    catch (failure: TimeoutException) { throw EdgeFailure(timeoutId) }
    finally { if (!task.isDone) task.cancel(false) }
}

class EdgeFadeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var activity: EdgeFadeActivity? = null
        var runner: EdgeFadeActivity.EdgeProofRunner? = null
        var fatal: String? = null
        var activityDestroyed = false
        var bitmapCleanup = false
        val launched = AtomicReference<EdgeFadeActivity>()
        val stopStarting = AtomicBoolean(false)
        try {
            val launch = FutureTask(Callable {
                (startActivitySync(Intent(targetContext, EdgeFadeActivity::class.java)
                    .putExtra("mode", "proof").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as EdgeFadeActivity).also {
                    launched.set(it)
                    if (stopStarting.get()) Handler(Looper.getMainLooper()).post { it.finish() }
                }
            })
            Thread(launch, "edge-fade-start").apply { isDaemon = true; start() }
            activity = launch.get(EDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            runner = activity.runProof { sendStatus(0, it) }
            runner.execute()
        } catch (failure: Exception) {
            fatal = "${failure.javaClass.simpleName}: ${failure.message}"
        } finally {
            stopStarting.set(true)
            val closing = activity ?: launched.get()
            if (closing != null) {
                try {
                    edgeOnMain { closing.finish() }
                    edgeAwait(closing.destroyed, "activity_destroy_timeout")
                    activityDestroyed = true
                } catch (failure: Exception) {
                    fatal = listOfNotNull(fatal, "cleanup: ${failure.message}").joinToString("; ")
                }
            }
            try { bitmapCleanup = runner?.cleanupBitmaps() ?: true }
            catch (failure: Exception) {
                fatal = listOfNotNull(fatal, "bitmap cleanup: ${failure.message}").joinToString("; ")
            }
        }
        val bundle = runner?.bundle() ?: Bundle().apply {
            val cases = edgeCases()
            putInt("planned", cases.size); putInt("count", 0); putInt("pass", 0); putInt("fail", 0)
            putInt("unrun", cases.size); putInt("hardware", -1); putInt("maxDelta", -1)
            putInt("worstCase", -1); putInt("x", -1); putInt("y", -1)
            putInt("tolerance", EDGE_TOLERANCE); putInt("componentScale", 255)
            putInt("bitmapsCreated", 0); putInt("bitmapsRecycled", 0); putInt("pendingBitmaps", 0)
            putString("casesJson", JSONArray(cases.map { it.json().apply {
                put("status", "unrun"); put("pass", -1); put("maxDelta", -1); put("hardware", -1)
                put("x", -1); put("y", -1)
            } }).toString())
        }
        fatal?.let { bundle.putString("fatal", it) }
        bundle.putInt("activityDestroyed", if (activityDestroyed) 1 else 0)
        bundle.putInt("bitmapCleanup", if (bitmapCleanup) 1 else 0)
        bundle.putInt("cleanup", if (activityDestroyed && bitmapCleanup) 1 else 0)
        val success = fatal == null && !bundle.containsKey("abortReason") && bundle.getInt("fail") == 0 &&
            bundle.getInt("unrun") == 0 && bundle.getInt("hardware") == 1 && activityDestroyed && bitmapCleanup
        bundle.putInt("success", if (success) 1 else 0)
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, bundle)
    }
}
