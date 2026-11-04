package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
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
                    val retry = parseRetryAfter(connection.getHeaderField("Retry-After"))
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

    private fun validateRequest(url: String, headers: Map<String, String>, maxBytes: Int): String {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException(INVALID_REQUEST)
        }
        require(
            uri.scheme == "https" && uri.host in setOf(WEB_HOST, API_HOST) &&
                (uri.port == -1 || uri.port == 443) &&
                uri.rawUserInfo == null && uri.rawFragment == null &&
                maxBytes in 1..MAX_BYTES,
        ) { INVALID_REQUEST }
        val names = mutableSetOf<String>()
        headers.forEach { (name, value) ->
            // HTTP token names also exclude whitespace disguises of Cookie/Authorization.
            require(name.isNotEmpty() && name.all { isHeaderNameChar(it) }) { INVALID_REQUEST }
            require(value.none { it == '\r' || it == '\n' }) { INVALID_REQUEST }
            val normalizedName = name.lowercase(Locale.ROOT)
            require(names.add(normalizedName)) { INVALID_REQUEST }
            require(normalizedName != "cookie") { INVALID_REQUEST }
            if (normalizedName == "authorization" || normalizedName == "media-user-token") {
                require(uri.host == API_HOST) { INVALID_REQUEST }
            }
            if (normalizedName == "accept-encoding") {
                require(value.equals("identity", ignoreCase = true)) { INVALID_REQUEST }
            }
        }
        return uri.host
    }

    private fun readBody(
        input: InputStream,
        maxBytes: Int,
        retry: Long?,
        context: CoroutineContext,
    ): AppleLyricsHttpResponse {
        val output = ByteArrayOutputStream(minOf(8192, maxBytes))
        val buffer = ByteArray(minOf(8192, maxBytes + 1))
        while (true) {
            context.ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
            context.ensureActive()
            if (count == -1) break
            if (count > maxBytes - output.size()) {
                return AppleLyricsHttpResponse(200, "", retry, tooLarge = true)
            }
            output.write(buffer, 0, count)
        }
        return AppleLyricsHttpResponse(200, output.toString("UTF-8"), retry)
    }

    private fun parseRetryAfter(header: String?): Long? {
        val value = header?.trim() ?: return null
        if (value.isNotEmpty() && value.all { it in '0'..'9' }) return value.toLongOrNull()
        val deadline = try {
            ZonedDateTime.parse(value, HTTP_DATE).toInstant()
        } catch (_: DateTimeException) {
            return null
        }
        val now = Instant.ofEpochMilli(clock())
        if (!deadline.isAfter(now)) return 0L
        // Instant/Duration avoid overflowing a millisecond subtraction at either Long bound.
        val remaining = Duration.between(now, deadline)
        return remaining.seconds + if (remaining.nano == 0) 0L else 1L
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

    private companion object {
        const val WEB_HOST = "music.apple.com"
        const val API_HOST = "amp-api.music.apple.com"
        const val MAX_BYTES = 8 * 1024 * 1024
        const val INVALID_REQUEST = "Invalid Apple lyrics request"
        const val REQUEST_FAILED = "Apple lyrics request failed"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME
            .withResolverStyle(ResolverStyle.STRICT)

        fun isHeaderNameChar(char: Char): Boolean =
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "!#$%&'*+-.^_`|~"
    }
}
