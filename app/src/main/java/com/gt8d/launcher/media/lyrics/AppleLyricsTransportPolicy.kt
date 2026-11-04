package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

internal object AppleLyricsTransportPolicy {
    const val WEB_HOST = "music.apple.com"
    const val API_HOST = "amp-api.music.apple.com"
    const val MAX_BYTES = 8 * 1024 * 1024
    const val INVALID_REQUEST = "Invalid Apple lyrics request"
    const val REQUEST_FAILED = "Apple lyrics request failed"
    val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME
        .withResolverStyle(ResolverStyle.STRICT)

    fun validateRequest(url: String, headers: Map<String, String>, maxBytes: Int): String {
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

    fun readBody(
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

    fun parseRetryAfter(header: String?, clock: () -> Long): Long? {
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

    fun nonSuccessResponse(
        host: String,
        code: Int,
        header: (String) -> String?,
        clock: () -> Long,
    ) = AppleLyricsHttpResponse(
        code, "", parseRetryAfter(header("Retry-After"), clock),
        location = if (host == WEB_HOST && code in REDIRECT_CODES) header("Location") else null,
    )

    private fun isHeaderNameChar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "!#$%&'*+-.^_`|~"
}
