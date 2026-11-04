package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.media.lyrics.AppleLyricsTransportPolicy.REDIRECT_CODES
import com.gt8d.launcher.media.lyrics.AppleLyricsTransportPolicy.REQUEST_FAILED
import com.gt8d.launcher.media.lyrics.AppleLyricsTransportPolicy.WEB_HOST
import com.gt8d.launcher.media.lyrics.AppleLyricsTransportPolicy.parseRetryAfter
import com.gt8d.launcher.media.lyrics.AppleLyricsTransportPolicy.readBody
import com.gt8d.launcher.media.lyrics.AppleLyricsTransportPolicy.validateRequest
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

fun interface AppleLyricsTransport {
    suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): AppleLyricsHttpResponse
}

class AppleLyricsHttpResponse(
    val code: Int,
    val body: String,
    val retryAfterSec: Long? = null,
    val tooLarge: Boolean = false,
    val location: String? = null,
) {
    override fun toString() = "AppleLyricsHttpResponse(code=$code, tooLarge=$tooLarge)"
}

class AppleLyricsUrlConnectionTransport(
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val clock: () -> Long = System::currentTimeMillis,
) : AppleLyricsTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
    ): AppleLyricsHttpResponse {
        currentCoroutineContext().ensureActive()
        val requestHeaders = headers.toMap()
        val host = validateRequest(url, requestHeaders, maxBytes)
        val result = withContext(Dispatchers.IO) {
            ensureActive()
            val outcome = suspendCancellableCoroutine<Result<AppleLyricsHttpResponse>> { continuation ->
                val resources = Resources()
                continuation.invokeOnCancellation { resources.release() }
                var cleanupCancellation: CancellationException? = null
                val result: Result<AppleLyricsHttpResponse> = try {
                    continuation.context.ensureActive()
                    val connection = open(url)
                    resources.connection.set(connection)
                    // Cancellation may have happened while open/inputStream was returning.
                    continuation.context.ensureActive()
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    requestHeaders.forEach { (name, value) ->
                        if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                            connection.setRequestProperty(name, value)
                        }
                    }
                    continuation.context.ensureActive()
                    val code = connection.responseCode
                    continuation.context.ensureActive()
                    val retry = parseRetryAfter(connection.getHeaderField("Retry-After"), clock)
                    val location = if (host == WEB_HOST && code in REDIRECT_CODES) {
                        connection.getHeaderField("Location")
                    } else {
                        null
                    }
                    val response = if (code == HttpURLConnection.HTTP_OK) {
                        continuation.context.ensureActive()
                        val input = connection.inputStream
                        resources.stream.set(input)
                        continuation.context.ensureActive()
                        readBody(input, maxBytes, retry, continuation.context)
                    } else {
                        AppleLyricsHttpResponse(code, "", retry, location = location)
                    }
                    continuation.context.ensureActive()
                    Result.success(response)
                } catch (e: CancellationException) {
                    Result.failure(e)
                } catch (_: Exception) {
                    // Neither the cause nor suppressed exceptions may retain request data.
                    Result.failure(IOException(REQUEST_FAILED))
                } finally {
                    cleanupCancellation = resources.release()
                }
                // Keep the continuation pending through cleanup, including EOF/close races.
                if (continuation.isActive) {
                    val cancellation = cleanupCancellation
                    if (cancellation != null && result.exceptionOrNull() !is CancellationException) {
                        continuation.resume(Result.failure(cancellation))
                    } else {
                        continuation.resume(result)
                    }
                }
            }
            ensureActive()
            outcome
        }
        currentCoroutineContext().ensureActive()
        // Throw at the caller boundary so coroutine stack recovery cannot attach a cause
        // to the sanitized IOException or copy an explicitly thrown CancellationException.
        return result.getOrThrow()
    }

    private class Resources {
        val connection = AtomicReference<HttpURLConnection?>()
        val stream = AtomicReference<InputStream?>()

        fun release(): CancellationException? {
            var cancellation: CancellationException? = null
            try {
                stream.getAndSet(null)?.close()
            } catch (e: CancellationException) {
                cancellation = e
            } catch (_: Exception) {
                // Cleanup failures must not expose raw messages or replace the request result.
            }
            try {
                connection.getAndSet(null)?.disconnect()
            } catch (e: CancellationException) {
                if (cancellation == null) cancellation = e
            } catch (_: Exception) {
                // Still attempt disconnect even when closing the stream failed.
            }
            return cancellation
        }
    }

}
