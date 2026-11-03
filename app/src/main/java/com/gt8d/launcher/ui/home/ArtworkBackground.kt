package com.gt8d.launcher.ui.home

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.gt8d.launcher.media.NowPlaying
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
        copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        val (width, height) = artworkSampleSize(copy.width, copy.height)
        sampled = Bitmap.createScaledBitmap(copy, width, height, false)
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

private class ImageKey(val bitmap: Bitmap, val generation: Int) {
    override fun equals(other: Any?) = other is ImageKey && bitmap === other.bitmap && generation == other.generation
    override fun hashCode() = 31 * System.identityHashCode(bitmap) + generation
}
private fun NowPlaying?.imageKey(): ImageKey? = this?.artwork?.let {
    ImageKey(it, it.generationId)
}
private data class TrackKey(val pkg: String, val title: String?, val artist: String?, val album: String?, val duration: Long?)
private fun NowPlaying?.trackKey() = this?.let { TrackKey(it.pkg, it.metaTitle, it.artist, it.album, it.durationMs) }

/** One current image, including failed results; clients share both pending work and completed colors. */
internal class ArtworkBackgroundHolder(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val extract: suspend (Bitmap) -> ArtworkColors? = { sampleArtworkColors(it) },
) {
    val colors = MutableStateFlow<ArtworkColors?>(null)
    private val clients = linkedMapOf<Any, NowPlaying?>()
    private var image: ImageKey? = null
    private var track: TrackKey? = null
    private var generation = 0L
    private var job: Job? = null
    private var completed = false
    private var retained: ArtworkColors? = null
    private class Request(val image: ImageKey, var track: TrackKey?, var generation: Long)
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
        if (now.imageKey() == image && now.trackKey() == track) colors.value else null

    fun imageColorsFor(now: NowPlaying?): ArtworkColors? =
        if (now.imageKey() == image) colors.value else null

    private fun select(now: NowPlaying?) {
        generation++
        val next = now.imageKey()
        track = now.trackKey()
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
            it.track = track
            it.generation = generation
            return
        }
        val target = Request(next, track, generation)
        request = target
        job = scope.launch {
            val result = try {
                withContext(dispatcher) { extract(next.bitmap) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            if (request === target && target.generation == generation && image == next &&
                next.bitmap.generationId == next.generation && track == target.track && clients.isNotEmpty()) {
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
