package com.gt8d.launcher.ui.home

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.ui.theme.ArtworkColors
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.theme.extractArtworkColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

internal fun artworkSampleSize(width: Int, height: Int): Pair<Int, Int> {
    val scale = minOf(1.0, 32.0 / maxOf(width, height))
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
}

internal fun sampleArtworkColors(bitmap: Bitmap): ArtworkColors? {
    var copy: Bitmap? = null
    var sampled: Bitmap? = null
    return try {
        if (bitmap.isRecycled) return null
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            copy
        }
        val (width, height) = artworkSampleSize(source.width, source.height)
        sampled = Bitmap.createScaledBitmap(source, width, height, false)
        val pixels = IntArray(width * height)
        sampled.getPixels(pixels, 0, width, 0, 0, width, height)
        extractArtworkColors(pixels)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        if (sampled !== bitmap && sampled !== copy) sampled?.recycle()
        if (copy !== bitmap) copy?.recycle()
    }
}

private data class ImageKey(val track: TrackKey, val width: Int, val height: Int)
private fun NowPlaying?.imageKey(): ImageKey? = this?.artwork?.let {
    ImageKey(TrackKey(pkg, metaTitle, artist, album), it.width, it.height)
}
private data class TrackKey(val pkg: String, val title: String?, val artist: String?, val album: String?)

/** One current image, including failed results; clients share both pending work and completed colors. */
internal class ArtworkBackgroundHolder(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val extract: suspend (Bitmap) -> ArtworkColors? = { sampleArtworkColors(it) },
) {
    val colors = MutableStateFlow<ArtworkColors?>(null)
    private val clients = linkedMapOf<Any, NowPlaying?>()
    private var image: ImageKey? = null
    private var generation = 0L
    private var job: Job? = null
    private var completed = false
    private var retained: ArtworkColors? = null
    private class Request(var generation: Long)
    private var request: Request? = null

    fun update(client: Any, now: NowPlaying?) {
        clients[client] = now
        select(now)
    }

    fun remove(client: Any) {
        clients.remove(client)
        generation++
        if (clients.isNotEmpty()) {
            select(clients.values.last())
        } else {
            job?.cancel()
            job = null
            request = null
            colors.value = null
        }
    }

    fun colorsFor(now: NowPlaying?): ArtworkColors? =
        if (now.imageKey() == image) colors.value else null

    fun imageColorsFor(now: NowPlaying?): ArtworkColors? =
        if (now.imageKey() == image) colors.value else null

    private fun select(now: NowPlaying?) {
        generation++
        val next = now.imageKey()
        if (next != image) {
            job?.cancel()
            job = null
            request = null
            image = next
            retained = null
            completed = false
            colors.value = null
        }
        if (next == null) return
        if (completed) {
            colors.value = retained
            return
        }
        request?.let {
            it.generation = generation
            return
        }
        val bitmap = now?.artwork ?: return
        val target = Request(generation)
        request = target
        job = scope.launch {
            val started = SystemClock.elapsedRealtime()
            DiagLog.d("Artwork", "extract start key=${next.track.title}/${next.width}x${next.height} config=${bitmap.config}")
            var failure = "no valid pixels or unreadable bitmap"
            val result = try {
                withContext(dispatcher) { extract(bitmap) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
                null
            } catch (error: OutOfMemoryError) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
                null
            }
            if (result == null) {
                DiagLog.d("Artwork", "extract failed $failure")
            } else {
                val light = "#%06X".format(result.light and 0xffffff)
                val dark = "#%06X".format(result.dark and 0xffffff)
                DiagLog.d("Artwork", "extract done $light/$dark ms=${SystemClock.elapsedRealtime() - started}")
            }
            if (request === target && target.generation == generation && image == next &&
                clients.isNotEmpty()) {
                retained = result
                completed = true
                request = null
                job = null
                colors.value = result
            }
        }
    }
}

private val sharedArtworkBackground = ArtworkBackgroundHolder(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
internal val LocalArtworkBackgroundHolder = staticCompositionLocalOf { sharedArtworkBackground }

@Composable
fun rememberArtworkBackground(now: NowPlaying?): State<ArtworkColors?> {
    val holder = LocalArtworkBackgroundHolder.current
    val client = remember { Any() }
    SideEffect { holder.update(client, now) }
    DisposableEffect(client, holder) {
        onDispose { holder.remove(client) }
    }
    if (now?.artwork == null) return remember { mutableStateOf(null) }
    val observed = holder.colors.collectAsState()
    return remember(holder, now, observed.value) { mutableStateOf(holder.imageColorsFor(now)) }
}

fun Modifier.artworkBackground(colors: ArtworkColors?): Modifier = drawWithCache {
    val brush = colors?.let {
        Brush.linearGradient(listOf(Color(it.light), Color(it.dark)), Offset.Zero, Offset(size.width, size.height))
    }
    onDrawBehind {
        if (brush == null) drawRect(Palette.Panel) else drawRect(brush)
    }
}
