package com.gt8d.launcher.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun interface ArtworkFallback {
    /** 遅延・再試行つきで uri の画像を取りに行く。成功時は onResult をメインスレッドで1回だけ呼ぶ。返り値で取り消す。 */
    fun request(uri: String, onResult: (Bitmap) -> Unit): Cancel
    fun interface Cancel { fun cancel() }
}

/** The scope, requests, cancellation and network notifications all belong to the main thread. */
internal class ArtworkFallbackLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> Bitmap?,
    private val initialDelayMs: Long = 2_000,
    private val retryDelaysMs: List<Long> = listOf(1_500, 4_000),
    private val cacheSize: Int = 3,
) : ArtworkFallback {
    init {
        require(initialDelayMs >= 0 && retryDelaysMs.all { it >= 0 })
        require(cacheSize >= 0)
    }

    private class Pending(val uri: String, val onResult: (Bitmap) -> Unit) {
        var job: Job? = null
        var cancelled = false
        var delivered = false
    }

    private val cache = LinkedHashMap<String, Bitmap>(cacheSize, 0.75f, true)
    private val unresolved = mutableSetOf<Pending>()

    override fun request(uri: String, onResult: (Bitmap) -> Unit): ArtworkFallback.Cancel {
        val pending = Pending(uri, onResult)
        launchRequest(pending) {
            delay(initialDelayMs)
            val cached = cache[uri]
            if (cached != null) {
                deliver(pending, cached)
                return@launchRequest
            }
            for (attempt in 0..retryDelaysMs.size) {
                if (attempt > 0) delay(retryDelaysMs[attempt - 1])
                val bitmap = tryFetch(uri)
                if (bitmap != null) {
                    deliver(pending, bitmap)
                    return@launchRequest
                }
            }
            unresolved += pending
        }
        return ArtworkFallback.Cancel {
            pending.cancelled = true
            pending.job?.cancel()
            unresolved -= pending
        }
    }

    fun onNetworkAvailable() {
        for (pending in unresolved.toList()) {
            // Repeated network notifications must not overlap retries for the same request.
            if (pending.cancelled || pending.delivered || pending.job?.isActive == true) continue
            launchRequest(pending) {
                val bitmap = tryFetch(pending.uri)
                if (bitmap != null) deliver(pending, bitmap)
            }
        }
    }

    private fun launchRequest(pending: Pending, block: suspend () -> Unit) {
        // Assign before running, including when Main.immediate starts without dispatching.
        pending.job = scope.launch(start = CoroutineStart.LAZY) { block() }
        pending.job?.start()
    }

    private suspend fun tryFetch(uri: String): Bitmap? {
        currentCoroutineContext().ensureActive()
        val bitmap = try {
            fetch(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        return bitmap
    }

    private suspend fun deliver(pending: Pending, bitmap: Bitmap) {
        currentCoroutineContext().ensureActive()
        if (pending.cancelled || pending.delivered) return
        cache[pending.uri] = bitmap
        while (cache.size > cacheSize) cache.remove(cache.keys.first())
        unresolved -= pending
        pending.delivered = true
        pending.onResult(bitmap)
    }
}

internal suspend fun fetchArtworkBitmap(
    client: OkHttpClient,
    uri: String,
    maxBytes: Long = 8L * 1024 * 1024,
    maxSide: Int = 512,
): Bitmap? = withContext(Dispatchers.IO) {
    require(maxBytes in 1..Int.MAX_VALUE.toLong())
    require(maxSide > 0)
    ensureActive()
    val request = Request.Builder().url(uri).get().build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful || response.body.contentLength() > maxBytes) return@withContext null
        val bytes = ByteArrayOutputStream().use { output ->
            response.body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    ensureActive()
                    // Read at most one byte past the limit, including chunked/unknown lengths.
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
                    if (count == -1) break
                    total += count
                    if (total > maxBytes) return@withContext null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
        val longest = maxOf(options.outWidth, options.outHeight).toLong()
        var sample = 1
        while ((longest + sample - 1) / sample > maxSide) {
            if (sample > Int.MAX_VALUE / 2) return@withContext null
            sample *= 2
        }
        ensureActive()
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
