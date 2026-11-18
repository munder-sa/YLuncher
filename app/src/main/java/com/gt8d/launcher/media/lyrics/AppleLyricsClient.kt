package com.gt8d.launcher.media.lyrics

import android.os.SystemClock
import android.util.Xml
import com.gt8d.launcher.media.lyrics.AppleConnectionTrace.Stage
import java.io.IOException
import java.io.StringReader
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.xmlpull.v1.XmlPullParser

enum class AppleLyricsReason {
    OK, NOT_APPLICABLE, AUTH_REQUIRED, NOT_FOUND, UNSUPPORTED, INVALID,
    NETWORK, HTTP, WAIT, TIMEOUT, TOKEN_UNAVAILABLE,
}

class AppleLyricsResult(
    val state: LyricsState,
    val reason: AppleLyricsReason,
    val retryAfterSec: Long? = null,
    val lineSynced: List<LyricLine>? = null,
    val detail: String? = null,
) {
    override fun toString() =
        "AppleLyricsResult(state=${state.javaClass.simpleName}, reason=$reason, retryAfterSec=$retryAfterSec)"
}

sealed interface AppleConnectResult {
    class Ready(val storefront: String) : AppleConnectResult {
        override fun toString() = "AppleConnectResult.Ready(storefront=$storefront)"
    }

    class Unavailable(val reason: AppleLyricsReason, val retryAfterSec: Long? = null) : AppleConnectResult {
        override fun toString() = "AppleConnectResult.Unavailable(reason=$reason, retryAfterSec=$retryAfterSec)"
    }
}

class AppleLyricsClient(
    private val transport: AppleLyricsTransport,
    private val tokens: AppleWebTokenProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val diagnosticsEnabled: Boolean = false,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
) {
    // Serialize provider/API access so a queued caller observes cooldown before either request.
    // Time spent waiting for this mutex is included in each caller's overall timeout.
    private val requestMutex = Mutex()
    private var retryAtMillis: Long? = null

    suspend fun connect(userToken: String): AppleConnectResult {
        val trace = if (diagnosticsEnabled) AppleConnectionTrace(AppleConnectionTrace.Operation.CONNECT) else null
        try {
            val outcome = bounded(
                35_000L, { reason, _ -> AppleConnectResult.Unavailable(reason) },
            ) {
                val cleanToken = cleanToken(userToken)
                    ?: return@bounded AppleConnectResult.Unavailable(AppleLyricsReason.INVALID)
                when (val result = authenticatedRequest("$API_ROOT/v1/me/storefront", cleanToken, trace)) {
                    is ApiResult.Unavailable -> AppleConnectResult.Unavailable(result.reason, result.retryAfterSec)
                    is ApiResult.Response -> {
                        if (result.value.code != 200) return@bounded AppleConnectResult.Unavailable(AppleLyricsReason.HTTP)
                        val json = parseObject(result.value.body)
                        val data = json?.get("data") as? JsonArray
                        val id = (data?.singleOrNull() as? JsonObject)?.get("id").stringValue()
                        if (id != null && STOREFRONT.matches(id)) AppleConnectResult.Ready(id)
                        else AppleConnectResult.Unavailable(AppleLyricsReason.INVALID)
                    }
                }
            }
            when (outcome) {
                is AppleConnectResult.Ready -> trace?.finish(AppleConnectionTrace.Reason.READY)
                is AppleConnectResult.Unavailable -> trace?.finish(outcome.reason)
            }
            return outcome
        } catch (e: CancellationException) {
            trace?.finish(AppleConnectionTrace.Reason.CANCELLED)
            throw e
        }
    }

    suspend fun fetch(request: LyricsRequest, storefront: String, userToken: String): AppleLyricsResult = bounded(
        5_000L, { reason, detail -> failed(reason, detail = detail) },
    ) {
        val cleanToken = cleanToken(userToken)
        val id = request.identity.appleMusicId
        if (cleanToken == null || !STOREFRONT.matches(storefront) || id == null ||
            !APPLE_ID.matches(id) || id.all { it == '0' } ||
            request.query.title.isBlank() || request.query.artist.isBlank()
        ) return@bounded AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)

        when (val result = authenticatedRequest("$API_ROOT/v1/catalog/$storefront/songs/$id/syllable-lyrics", cleanToken)) {
            is ApiResult.Unavailable -> failed(result.reason, result.retryAfterSec, result.detail)
            is ApiResult.Response -> when (result.value.code) {
                404 -> AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND)
                200 -> parseLyrics(result.value.body, request.query.durationSec)
                else -> failed(AppleLyricsReason.HTTP)
            }
        }
    }

    private suspend fun authenticatedRequest(
        url: String,
        userToken: String,
        trace: AppleConnectionTrace? = null,
    ): ApiResult {
        trace?.mark(Stage.REQUEST_MUTEX_WAIT)
        return requestMutex.withLock {
            trace?.mark(Stage.REQUEST_MUTEX_ACQUIRED)
            currentCoroutineContext().ensureActive()
            val now = monotonicClock()
            retryAtMillis?.takeIf { it > now }?.let {
                return@withLock ApiResult.Unavailable(AppleLyricsReason.WAIT, remainingSeconds(it, now))
            }
            repeat(2) { attempt ->
                trace?.mark(Stage.PUBLIC_BEARER_START)
                val tokenResult = tokens.get()
                trace?.mark(Stage.PUBLIC_BEARER_END)
                currentCoroutineContext().ensureActive()
                val token = when (tokenResult) {
                    is AppleWebTokenResult.Ready -> tokenResult.token
                    is AppleWebTokenResult.Unavailable -> return@withLock ApiResult.Unavailable(
                        when (tokenResult.reason) {
                            AppleWebTokenFailure.NETWORK -> AppleLyricsReason.NETWORK
                            AppleWebTokenFailure.TIMEOUT -> AppleLyricsReason.TIMEOUT
                            AppleWebTokenFailure.WAIT -> AppleLyricsReason.WAIT
                            else -> AppleLyricsReason.TOKEN_UNAVAILABLE
                        },
                        if (tokenResult.reason == AppleWebTokenFailure.WAIT) tokenResult.retryAfterSec else null,
                    )
                }
                trace?.mark(Stage.ACCOUNT_REQUEST_START)
                val response = transport.get(
                    url,
                    mapOf(
                        "Authorization" to "Bearer ${token.value}",
                        "media-user-token" to userToken,
                        "Origin" to "https://music.apple.com",
                        "Referer" to "https://music.apple.com/",
                        "Accept" to "application/json",
                        "User-Agent" to APPLE_WEB_USER_AGENT,
                    ),
                    MAX_RESPONSE_BYTES,
                )
                trace?.mark(Stage.ACCOUNT_REQUEST_END, httpCode = response.code)
                currentCoroutineContext().ensureActive()
                val tooLarge = response.tooLarge || response.body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES
                currentCoroutineContext().ensureActive()
                if (tooLarge) return@withLock ApiResult.Unavailable(AppleLyricsReason.INVALID, detail = "response-too-large")
                when (response.code) {
                    401, 403 -> {
                        if (attempt == 1) return@withLock ApiResult.Unavailable(AppleLyricsReason.AUTH_REQUIRED)
                        tokens.invalidate(token)
                        currentCoroutineContext().ensureActive()
                    }
                    429, 503 -> {
                        val receivedAt = monotonicClock()
                        val deadline = retryDeadline(receivedAt, maxOf(60L, response.retryAfterSec ?: 0L))
                        currentCoroutineContext().ensureActive()
                        retryAtMillis = deadline
                        return@withLock ApiResult.Unavailable(AppleLyricsReason.WAIT, remainingSeconds(deadline, receivedAt))
                    }
                    else -> return@withLock ApiResult.Response(response)
                }
            }
            ApiResult.Unavailable(AppleLyricsReason.AUTH_REQUIRED)
        }
    }

    private suspend fun parseLyrics(body: String, durationSec: Long?): AppleLyricsResult {
        val json = parseObject(body) ?: return failed(AppleLyricsReason.INVALID, detail = "json")
        if ("errors" in json) {
            val errors = json["errors"] as? JsonArray ?: return failed(AppleLyricsReason.INVALID, detail = "api-errors")
            if (errors.isNotEmpty()) return failed(AppleLyricsReason.INVALID, detail = "api-errors")
        }
        val data = json["data"] as? JsonArray ?: return failed(AppleLyricsReason.INVALID, detail = "data-shape")
        if (data.isEmpty()) return AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND)
        if (data.size != 1) return failed(AppleLyricsReason.INVALID, detail = "data-shape")
        val attributes = (data.single() as? JsonObject)?.get("attributes") as? JsonObject
        val ttml = attributes?.get("ttml").stringValue()
            ?: return failed(AppleLyricsReason.INVALID, detail = "ttml-missing-or-large")
        if (ttml.isBlank() || ttml.toByteArray(Charsets.UTF_8).size > MAX_TTML_BYTES) {
            return failed(AppleLyricsReason.INVALID, detail = "ttml-missing-or-large")
        }
        if (!hasTtmlRoot(ttml)) return failed(AppleLyricsReason.INVALID, detail = "ttml-root")
        val durationMs = durationSec?.takeIf { it in 1L..3600L }?.let { it * 1000L }
        var detail: String? = null
        val parsed = TtmlParser.parse(ttml, durationMs, allowLineSynced = true) { diagnostic ->
            if (diagnostic.startsWith("Unsupported ")) {
                detail = diagnostic.removePrefix("Unsupported ").takeIf { DETAIL_CODE.matches(it) }
            } else if (diagnostic.startsWith("Invalid ")) {
                detail = diagnostic.removePrefix("Invalid ").takeIf { DETAIL_CODE.matches(it) }
            }
        }
        currentCoroutineContext().ensureActive()
        return when (parsed) {
            is TtmlParseResult.WordSynced -> AppleLyricsResult(LyricsState.Synced(parsed.lines), AppleLyricsReason.OK)
            is TtmlParseResult.LineSynced -> AppleLyricsResult(
                LyricsState.Missing, AppleLyricsReason.UNSUPPORTED, lineSynced = parsed.lines,
            )
            TtmlParseResult.Unsupported -> AppleLyricsResult(
                LyricsState.Missing, AppleLyricsReason.UNSUPPORTED, detail = detail,
            )
            TtmlParseResult.Invalid -> failed(AppleLyricsReason.INVALID, detail = detail)
        }
    }

    private suspend fun parseObject(body: String): JsonObject? {
        currentCoroutineContext().ensureActive()
        val json = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        currentCoroutineContext().ensureActive()
        return json as? JsonObject
    }

    private suspend fun hasTtmlRoot(ttml: String): Boolean {
        currentCoroutineContext().ensureActive()
        if (ttml.contains("<!DOCTYPE", ignoreCase = true)) return false
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(ttml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            currentCoroutineContext().ensureActive()
            when (event) {
                XmlPullParser.DOCDECL -> return false
                XmlPullParser.START_TAG -> return parser.name == "tt" && parser.namespace == TTML_NAMESPACE
            }
            event = parser.nextToken()
            currentCoroutineContext().ensureActive()
        }
        return false
    }

    private suspend fun <T : Any> bounded(
        timeoutMillis: Long,
        failure: (AppleLyricsReason, String?) -> T,
        action: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val result = withTimeoutOrNull(timeoutMillis) {
            val outcome = try {
                withContext(io) {
                    currentCoroutineContext().ensureActive()
                    try {
                        action()
                    } finally {
                        // Check this IO job even if a dependency cancels it and then throws an ordinary error.
                        currentCoroutineContext().ensureActive()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                failure(AppleLyricsReason.NETWORK, null)
            } catch (_: Exception) {
                // Do not attach a response, credential, exception message, or cause to results.
                failure(AppleLyricsReason.INVALID, "exception")
            }
            // Even a non-cooperative dependency that throws after the deadline cannot publish a result.
            currentCoroutineContext().ensureActive()
            outcome
        } ?: failure(AppleLyricsReason.TIMEOUT, null)
        currentCoroutineContext().ensureActive()
        return result
    }

    private sealed interface ApiResult {
        class Response(val value: AppleLyricsHttpResponse) : ApiResult
        class Unavailable(val reason: AppleLyricsReason, val retryAfterSec: Long? = null,
            val detail: String? = null) : ApiResult
    }

    private companion object {
        const val API_ROOT = "https://amp-api.music.apple.com"
        const val TTML_NAMESPACE = "http://www.w3.org/ns/ttml"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_TTML_BYTES = 512 * 1024
        const val MAX_TOKEN_BYTES = 16 * 1024
        val STOREFRONT = Regex("[a-z]{2}")
        val APPLE_ID = Regex("[0-9]{1,20}")
        val DETAIL_CODE = Regex("[a-zA-Z-]{1,32}")
        val THOUSAND: BigInteger = BigInteger.valueOf(1000L)
        val MAX_LONG: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)

        fun cleanToken(value: String): String? {
            // Do not let trimming hide header control characters at either edge.
            if (value.any { it.isISOControl() }) return null
            return value.trim().takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN_BYTES }
        }

        fun kotlinx.serialization.json.JsonElement?.stringValue(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun failed(reason: AppleLyricsReason, retry: Long? = null, detail: String? = null) = AppleLyricsResult(
            if (reason == AppleLyricsReason.NETWORK) LyricsState.Offline else LyricsState.Failed,
            reason, retry, detail = detail?.takeIf { DETAIL_CODE.matches(it) },
        )

        fun retryDeadline(now: Long, seconds: Long): Long = BigInteger.valueOf(now)
            .add(BigInteger.valueOf(seconds).multiply(THOUSAND)).min(MAX_LONG).toLong()

        fun remainingSeconds(deadline: Long, now: Long): Long = BigInteger.valueOf(deadline)
            .subtract(BigInteger.valueOf(now)).add(BigInteger.valueOf(999L)).divide(THOUSAND).toLong()
    }
}
