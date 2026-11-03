package com.gt8d.launcher.media.lyrics

import java.io.InputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The stream is valid only inside consume; neither the source nor its JSON tree is retained. */
fun interface AmllIndexTransport {
    suspend fun get(consume: suspend (Int, InputStream?) -> Unit)
}

class AmllIndexUrlConnectionTransport(
    private val userAgent: String,
    private val open: (String) -> HttpURLConnection = { java.net.URL(it).openConnection() as HttpURLConnection },
) : AmllIndexTransport {
    override suspend fun get(consume: suspend (Int, InputStream?) -> Unit): Unit = withContext(Dispatchers.IO) {
        ensureActive()
        val connection = open(URL)
        val stream = AtomicReference<InputStream?>()
        // The resource owner remains suspended until all stream consumption is complete.
        kotlinx.coroutines.coroutineScope {
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation {
                        try { stream.getAndSet(null)?.close() } catch (_: Exception) { }
                        connection.disconnect()
                    }
                    // Kept pending until the parent cancels it in finally.
                }
            }
            try {
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                ensureActive()
                val code = connection.responseCode
                if (code == 200) {
                    val input = connection.inputStream
                    stream.set(input)
                    val expected = connection.contentLengthLong
                    stream.set(object : FilterInputStream(input) {
                        private var received = 0L
                        private fun counted(count: Int): Int {
                            if (count < 0 && expected >= 0 && received != expected) throw IOException("truncated index")
                            if (count > 0) received += count
                            return count
                        }
                        override fun read(): Int = input.read().also { counted(if (it < 0) -1 else 1) }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = counted(input.read(bytes, offset, length))
                    })
                }
                ensureActive()
                consume(code, stream.get())
                ensureActive()
            } catch (e: Exception) {
                ensureActive()
                throw e
            } finally {
                owner.cancel()
                try { stream.getAndSet(null)?.close() } finally { connection.disconnect() }
            }
        }
    }

    companion object {
        const val URL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/metadata/raw-lyrics-index.jsonl"
    }
}
