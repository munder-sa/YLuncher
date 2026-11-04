package com.gt8d.launcher.media.lyrics

import java.net.URI
import java.net.URISyntaxException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Pure parsing only. Cookie values are opaque and are never decoded or logged. */
object AppleWebTokenLogic {
    private const val COOKIE_NAME = "media-user-token"
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_TOKEN_BYTES = 16 * 1024
    private const val MAX_URL_CHARS = 8192
    private val EXPIRY_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd-MMM-yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
    )

    fun findToken(cookieHeader: String?): String? {
        if (cookieHeader == null ||
            !isSafeText(cookieHeader, MAX_HEADER_BYTES, MAX_HEADER_BYTES)
        ) return null
        for (entry in cookieHeader.splitToSequence(';')) {
            val split = entry.indexOf('=')
            if (split < 0 || entry.substring(0, split).trim(' ') != COOKIE_NAME) continue
            val value = entry.substring(split + 1).trim(' ')
            if (value.isNotEmpty() && isSafeText(value, MAX_TOKEN_BYTES, MAX_TOKEN_BYTES)) {
                return value
            }
        }
        return null
    }

    /** Cookie expiry metadata in epoch milliseconds, not a server token validity guarantee. */
    fun expirationFromSetCookie(header: String?, expectedToken: String?): Long? {
        if (header == null || expectedToken.isNullOrEmpty() ||
            !isSafeText(expectedToken, MAX_TOKEN_BYTES, MAX_TOKEN_BYTES) ||
            expectedToken != findToken(header)
        ) return null
        for (entry in header.splitToSequence(';')) {
            val split = entry.indexOf('=')
            if (split < 0 || !entry.substring(0, split).trim(' ').equals("expires", ignoreCase = true)) {
                continue
            }
            val raw = entry.substring(split + 1).trim(' ')
            for (pattern in EXPIRY_PATTERNS) {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val position = ParsePosition(0)
                val parsed = try {
                    parser.parse(raw, position)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (parsed != null && position.index == raw.length) {
                    return parsed.time.takeIf { it >= 0L }
                }
            }
        }
        // Max-Age alone cannot establish an absolute expiry without the cookie's set time.
        return null
    }

    fun isAppleHttpsUrl(url: String?): Boolean = appleHttpsHost(url) != null

    fun originLabel(url: String?): String =
        appleHttpsHost(url)?.let { "https://$it" } ?: "Apple Music"

    private fun appleHttpsHost(url: String?): String? {
        if (url == null || !isSafeText(url, MAX_URL_CHARS)) return null
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) return null
        return host.takeIf { it == "apple.com" || it.endsWith(".apple.com") }
    }

    // Reject by character count first, then count UTF-8 bytes without allocating an encoded array.
    // Inspect the original text so trimming cannot hide controls or malformed surrogate pairs.
    private fun isSafeText(value: String, maxChars: Int, maxBytes: Int = Int.MAX_VALUE): Boolean {
        if (value.length > maxChars) return false
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isISOControl(char)) return false
            bytes += when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                Character.isHighSurrogate(char) -> {
                    if (index == value.length || !Character.isLowSurrogate(value[index])) return false
                    index++
                    4
                }
                Character.isLowSurrogate(char) -> return false
                else -> 3
            }
            if (bytes > maxBytes) return false
        }
        return true
    }
}
