package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.media.lyrics.AppleConnectionTrace.Stage
import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val APPLE_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

class AppleWebToken(val value: String, val expiresAtEpochSec: Long) {
    override fun toString() = "AppleWebToken(redacted)"
}

enum class AppleWebTokenFailure { NETWORK, HTTP, TOO_LARGE, TOKEN_NOT_FOUND, TIMEOUT, WAIT }

sealed interface AppleWebTokenResult {
    class Ready(val token: AppleWebToken) : AppleWebTokenResult {
        override fun toString() = "AppleWebTokenResult.Ready(redacted)"
    }

    class Unavailable(val reason: AppleWebTokenFailure, val retryAfterSec: Long? = null) : AppleWebTokenResult {
        override fun toString() = "AppleWebTokenResult.Unavailable(reason=$reason, retryAfterSec=$retryAfterSec)"
    }
}

class AppleWebTokenProvider(
    private val transport: AppleLyricsTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val diagnosticsEnabled: Boolean = false,
) {
    private val mutex = Mutex()
    private var cached: AppleWebToken? = null
    private var retryAtMillis: Long? = null

    suspend fun get(): AppleWebTokenResult {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            val now = clock()
            cached?.takeIf { isFresh(it.expiresAtEpochSec, now) }?.let {
                return@withLock AppleWebTokenResult.Ready(it)
            }
            retryAtMillis?.takeIf { it > now }?.let {
                return@withLock AppleWebTokenResult.Unavailable(AppleWebTokenFailure.WAIT, remainingSeconds(it, now))
            }
            // Keep publication outside withContext: a cancelled return dispatch must not cache a result.
            val trace = if (diagnosticsEnabled) AppleConnectionTrace(AppleConnectionTrace.Operation.PUBLIC_REFRESH) else null
            try {
                val result = withContext(io) {
                    withTimeoutOrNull(30_000L) { refresh(trace) }
                        ?: AppleWebTokenResult.Unavailable(AppleWebTokenFailure.TIMEOUT)
                }
                currentCoroutineContext().ensureActive()
                when (result) {
                    is AppleWebTokenResult.Ready -> {
                        cached = result.token
                        retryAtMillis = null
                    }
                    is AppleWebTokenResult.Unavailable -> {
                        retryAtMillis = retryDeadline(clock(), maxOf(60L, result.retryAfterSec ?: 0L))
                    }
                }
                when (result) {
                    is AppleWebTokenResult.Ready -> trace?.finish(AppleConnectionTrace.Reason.READY)
                    is AppleWebTokenResult.Unavailable -> trace?.finish(result.reason)
                }
                result
            } catch (e: CancellationException) {
                trace?.finish(AppleConnectionTrace.Reason.CANCELLED)
                throw e
            }
        }
    }

    suspend fun invalidate(rejected: AppleWebToken) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (cached === rejected) cached = null
        }
    }

    private suspend fun refresh(trace: AppleConnectionTrace?): AppleWebTokenResult = try {
        val budget = DownloadBudget()
        val page = readFollowingRedirects(URI("$WEB_ROOT/us/browse"), budget, trace)
        if (page.code != 200) throw RefreshFailure(AppleWebTokenFailure.HTTP)
        trace?.mark(Stage.PAGE_TOKEN_PARSE_START)
        val pageToken = findToken(page.body)
        trace?.mark(Stage.PAGE_TOKEN_PARSE_END)
        if (pageToken != null) {
            AppleWebTokenResult.Ready(pageToken)
        } else {
            var token: AppleWebToken? = null
            trace?.mark(Stage.ASSET_LIST_PARSE_START)
            val assets = findAssets(page.body)
            trace?.mark(Stage.ASSET_LIST_PARSE_END)
            for ((index, url) in assets.withIndex()) {
                currentCoroutineContext().ensureActive()
                val response = readFollowingRedirects(url, budget, trace, index + 1)
                if (response.code == 200) {
                    trace?.mark(Stage.ASSET_TOKEN_PARSE_START, asset = index + 1)
                    token = findToken(response.body)
                    trace?.mark(Stage.ASSET_TOKEN_PARSE_END, asset = index + 1)
                }
                if (token != null) break
            }
            token?.let { AppleWebTokenResult.Ready(it) }
                ?: AppleWebTokenResult.Unavailable(AppleWebTokenFailure.TOKEN_NOT_FOUND)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RefreshFailure) {
        AppleWebTokenResult.Unavailable(e.reason, e.retryAfterSec)
    } catch (_: Exception) {
        AppleWebTokenResult.Unavailable(AppleWebTokenFailure.TOKEN_NOT_FOUND)
    }

    private suspend fun readFollowingRedirects(
        initial: URI,
        budget: DownloadBudget,
        trace: AppleConnectionTrace?,
        asset: Int? = null,
    ): AppleLyricsHttpResponse {
        var url = initial
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(MAX_RESPONSE_BYTES, budget.remaining)
            if (limit <= 0) throw RefreshFailure(AppleWebTokenFailure.TOO_LARGE)
            trace?.mark(if (asset == null) Stage.PAGE_REQUEST_START else Stage.ASSET_REQUEST_START, asset = asset)
            val response = try {
                transport.get(url.toASCIIString(), mapOf("User-Agent" to APPLE_WEB_USER_AGENT), limit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw RefreshFailure(AppleWebTokenFailure.NETWORK)
            }
            currentCoroutineContext().ensureActive()
            val bytes = response.body.toByteArray(Charsets.UTF_8).size
            trace?.mark(
                if (asset == null) Stage.PAGE_REQUEST_END else Stage.ASSET_REQUEST_END,
                httpCode = response.code, bodyBytes = bytes, asset = asset,
            )
            if (response.tooLarge || bytes > limit) throw RefreshFailure(AppleWebTokenFailure.TOO_LARGE)
            budget.remaining -= bytes
            if (response.code == 429 || response.code == 503) {
                throw RefreshFailure(AppleWebTokenFailure.WAIT, maxOf(60L, response.retryAfterSec ?: 0L))
            }
            if (response.code !in REDIRECT_CODES) return response
            if (redirects == 3) throw RefreshFailure(AppleWebTokenFailure.HTTP)
            val location = response.location?.takeIf { it.isNotBlank() }
                ?: throw RefreshFailure(AppleWebTokenFailure.HTTP)
            url = safeUrl(url, location) ?: throw RefreshFailure(AppleWebTokenFailure.HTTP)
            redirects++
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun findToken(document: String): AppleWebToken? {
        val seen = HashSet<String>()
        var best: AppleWebToken? = null
        for (match in JWT.findAll(document)) {
            currentCoroutineContext().ensureActive()
            val value = match.value
            if (!seen.add(value)) continue
            if (value.length <= MAX_TOKEN_BYTES) {
                val expiry = tokenExpiry(value)
                // exp only controls cache lifetime; this is not signature verification.
                if (expiry != null && isFresh(expiry, clock()) &&
                    (best == null || expiry > best.expiresAtEpochSec)
                ) {
                    best = AppleWebToken(value, expiry)
                }
            }
            if (seen.size == 20) break
        }
        currentCoroutineContext().ensureActive()
        return best
    }

    private fun tokenExpiry(value: String): Long? = try {
        val payload = value.substringAfter('.').substringBefore('.')
        val decoded = Base64.getUrlDecoder().decode(payload)
        val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decoded)).toString()
        val claims = Json.parseToJsonElement(json) as? JsonObject
        val exp = claims?.get("exp") as? JsonPrimitive
        if (exp == null || exp.isString || !POSITIVE_INTEGER.matches(exp.content)) null
        else exp.content.toLongOrNull()?.takeIf { it > 0 }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun findAssets(html: String): List<URI> {
        val context = currentCoroutineContext()
        val urls = linkedSetOf<URI>()
        var cursor = 0
        while (cursor < html.length) {
            context.ensureActive()
            val start = html.indexOf('<', cursor)
            if (start < 0) break
            if (html.startsWith("<!--", start)) {
                val end = html.indexOf("-->", start + 4)
                if (end < 0) break
                cursor = end + 3
                continue
            }
            var nameEnd = start + 1
            while (nameEnd < html.length && html[nameEnd].isTagNameChar()) nameEnd++
            val name = html.substring(start + 1, nameEnd).lowercase(Locale.ROOT)
            val end = tagEnd(html, nameEnd, context)
            if (end < 0) break
            cursor = end + 1
            if (nameEnd < html.length &&
                (html[nameEnd].isHtmlSpace() || html[nameEnd] == '>' || html[nameEnd] == '/') &&
                (name == "script" || name == "link")
            ) {
                val attrs = quotedAttributes(html, nameEnd, end, context)
                val target = if (name == "script") attrs["src"] else {
                    val rel = attrs["rel"]?.let { decodeReferences(it, context) }
                    if (rel?.split(HTML_SPACE)?.any { it.equals("modulepreload", ignoreCase = true) } == true) {
                        attrs["href"]
                    } else null
                }
                val decoded = target?.let { decodeReferences(it, context) }
                val url = decoded?.let { safeUrl(URI(WEB_ROOT), it) }
                if (url != null && url.path.endsWith(".js")) urls.add(url)
            }
            // Tag-like text in raw-text elements is not another HTML tag.
            if (name in RAW_TEXT_TAGS) {
                var closing = cursor
                while (true) {
                    context.ensureActive()
                    closing = html.indexOf("</$name", closing, ignoreCase = true)
                    if (closing < 0) return orderedAssets(urls)
                    val afterName = closing + name.length + 2
                    if (afterName == html.length || html[afterName].isHtmlSpace() || html[afterName] == '>') break
                    closing = afterName
                }
                cursor = closing
            }
        }
        context.ensureActive()
        return orderedAssets(urls)
    }

    private fun tagEnd(html: String, start: Int, context: CoroutineContext): Int {
        var quote: Char? = null
        for (index in start until html.length) {
            if (index % 4096 == 0) context.ensureActive()
            val char = html[index]
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') quote = char
            else if (char == '>') return index
        }
        return -1
    }

    private fun quotedAttributes(html: String, start: Int, end: Int, context: CoroutineContext): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        var cursor = start
        while (cursor < end) {
            context.ensureActive()
            if (html[cursor].isHtmlSpace() || html[cursor] == '/') {
                cursor++
                continue
            }
            val nameStart = cursor
            while (cursor < end && !html[cursor].isHtmlSpace() && html[cursor] !in "=/") cursor++
            val name = html.substring(nameStart, cursor).lowercase(Locale.ROOT)
            while (cursor < end && html[cursor].isHtmlSpace()) cursor++
            if (cursor == end || html[cursor] != '=') continue
            cursor++
            while (cursor < end && html[cursor].isHtmlSpace()) cursor++
            val first = seen.add(name)
            if (cursor == end) break
            val quote = html[cursor]
            if (quote != '\'' && quote != '"') {
                while (cursor < end && !html[cursor].isHtmlSpace()) cursor++
                continue
            }
            val valueStart = ++cursor
            while (cursor < end && html[cursor] != quote) cursor++
            if (first && name in setOf("src", "href", "rel")) attrs[name] = html.substring(valueStart, cursor)
            cursor++
        }
        return attrs
    }

    private fun decodeReferences(value: String, context: CoroutineContext): String? {
        val result = StringBuilder()
        var cursor = 0
        while (cursor < value.length) {
            if (cursor % 4096 == 0) context.ensureActive()
            if (value[cursor] != '&') {
                result.append(value[cursor++])
                continue
            }
            val end = value.indexOf(';', cursor + 1)
            if (end < 0) return null
            val reference = value.substring(cursor + 1, end)
            val named = when (reference) {
                "amp" -> '&'
                "quot" -> '"'
                "apos" -> '\''
                "lt" -> '<'
                "gt" -> '>'
                else -> null
            }
            if (named != null) result.append(named) else {
                val hex = reference.startsWith("#x", ignoreCase = true)
                if (!reference.startsWith('#')) return null
                val digits = reference.substring(if (hex) 2 else 1)
                if (digits.isEmpty() || !digits.all { it in '0'..'9' || (hex && it.lowercaseChar() in 'a'..'f') }) return null
                val codePoint = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
                if (codePoint !in 1..0x10ffff || codePoint in 0xd800..0xdfff) return null
                result.appendCodePoint(codePoint)
            }
            cursor = end + 1
        }
        return result.toString()
    }

    private fun safeUrl(base: URI, value: String): URI? = try {
        val url = base.resolve(URI(value))
        if (!url.scheme.equals("https", ignoreCase = true) || !url.host.equals("music.apple.com", ignoreCase = true) ||
            url.port !in setOf(-1, 443) || url.rawUserInfo != null || url.rawFragment != null
        ) null else {
            // Canonical origin also satisfies the transport's exact host/scheme checks.
            URI(WEB_ROOT + url.rawPath + (url.rawQuery?.let { "?$it" } ?: ""))
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }

    private fun orderedAssets(urls: Set<URI>) = urls.sortedBy { if ("/index" in it.path) 0 else 1 }.take(8)

    private class DownloadBudget(var remaining: Int = 16 * 1024 * 1024)

    private class RefreshFailure(val reason: AppleWebTokenFailure, val retryAfterSec: Long? = null) :
        Exception("Apple web token unavailable")

    private companion object {
        const val WEB_ROOT = "https://music.apple.com"
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_TOKEN_BYTES = 16 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val RAW_TEXT_TAGS = setOf("script", "style", "textarea", "title")
        val JWT = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
        val HTML_SPACE = Regex("[ \\t\\r\\n\\u000c]+")
        val THOUSAND: BigInteger = BigInteger.valueOf(1000L)
        val MAX_LONG: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)

        fun isFresh(expiry: Long, nowMillis: Long) = expiry > Math.floorDiv(nowMillis, 1000L) + 60L

        fun retryDeadline(now: Long, seconds: Long): Long = BigInteger.valueOf(now)
            .add(BigInteger.valueOf(seconds).multiply(THOUSAND)).min(MAX_LONG).toLong()

        fun remainingSeconds(deadline: Long, now: Long): Long = BigInteger.valueOf(deadline)
            .subtract(BigInteger.valueOf(now)).add(BigInteger.valueOf(999L)).divide(THOUSAND).toLong()

        fun Char.isHtmlSpace() = this in " \t\r\n\u000c"
        fun Char.isTagNameChar() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == ':'
    }
}
