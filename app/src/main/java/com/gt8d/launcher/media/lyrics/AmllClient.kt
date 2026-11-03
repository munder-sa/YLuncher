package com.gt8d.launcher.media.lyrics

import android.util.Xml
import com.gt8d.launcher.diag.DiagLog
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** Fixed folders and safe path components only; the index cannot supply a URL. */
data class AmllTarget(val folder: String, val id: String) {
    val safe: Boolean get() = when (folder) {
        "am-lyrics" -> validAppleId(id) != null
        "raw-lyrics" -> Regex("[A-Za-z0-9._-]+\\.ttml").matches(id)
        else -> false
    }
}

enum class AmllReason { DIRECT_ID, INDEX_ISRC, INDEX_TEXT, NOT_FOUND, AMBIGUOUS, UNSUPPORTED, UNAVAILABLE, INVALID, NETWORK, HTTP, WAIT }

sealed interface AmllMatch {
    data class Unique(val target: AmllTarget) : AmllMatch
    data object NotFound : AmllMatch
    data object Ambiguous : AmllMatch
    data object Unavailable : AmllMatch
}

fun interface AmllIndexLookup {
    /** A non-null validated ISRC must be matched before text, with conflicting metadata rejected. */
    suspend fun resolve(query: LyricsQuery, isrc: String?): AmllMatch
}

data class AmllResult(
    val state: LyricsState,
    val reason: AmllReason,
    val target: AmllTarget? = null,
    val source: String = "AMLL TTML DB",
    val retryAfterSec: Long? = null,
    val hostWaitUntil: Map<String, Long> = emptyMap(),
    val cacheable: Boolean = false,
)

internal fun validAppleId(value: String?): String? = value?.takeIf {
    it.isNotEmpty() && it.all { c -> c in '0'..'9' } && it.any { c -> c != '0' }
}

internal fun validIsrc(value: String?): String? = value?.uppercase(Locale.ROOT)?.replace("-", "")
    ?.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}").matches(it) }

class AmllClient(
    private val transport: AmllTransport,
    private val userAgent: String,
    private val index: AmllIndexLookup = AmllIndexLookup { _, _ -> AmllMatch.Unavailable },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val hostWait = mutableMapOf<String, Long>()
    private val requestWait = mutableMapOf<LyricsRequest, Long>()

    suspend fun fetch(request: LyricsRequest): AmllResult = withContext(Dispatchers.IO) {
        ensureActive()
        synchronized(requestWait) {
            requestWait.entries.removeAll { it.value <= clock() }
            requestWait[request]
        }?.let { return@withContext result(LyricsState.Failed, AmllReason.WAIT, wait = it) }
        if (request.query.title.isBlank() || request.query.artist.isBlank()) {
            return@withContext result(LyricsState.Missing, AmllReason.UNAVAILABLE)
        }
        val id = validAppleId(request.identity.appleMusicId)
        if (id != null) {
            val direct = download(AmllTarget("am-lyrics", id), request, AmllReason.DIRECT_ID)
            if (direct.reason !in setOf(AmllReason.NOT_FOUND, AmllReason.UNSUPPORTED)) return@withContext direct
            val indexed = lookup(request, direct)
            // Without a normal index, the direct 404/unsupported result remains definitive.
            return@withContext if (indexed.reason == AmllReason.UNAVAILABLE) direct else indexed
        }
        lookup(request)
    }

    private suspend fun lookup(request: LyricsRequest, direct: AmllResult? = null): AmllResult {
        val isrc = validIsrc(request.identity.isrc)
        return when (val match = index.resolve(request.query, isrc)) {
            is AmllMatch.Unique -> if (direct != null && match.target == direct.target) direct
                else if (match.target.safe) download(match.target, request,
                if (isrc != null) AmllReason.INDEX_ISRC else AmllReason.INDEX_TEXT)
                else result(LyricsState.Failed, AmllReason.INVALID)
            AmllMatch.NotFound -> result(LyricsState.Missing, AmllReason.NOT_FOUND, cacheable = true)
            AmllMatch.Ambiguous -> result(LyricsState.Missing, AmllReason.AMBIGUOUS, cacheable = true)
            AmllMatch.Unavailable -> result(LyricsState.Failed, AmllReason.UNAVAILABLE)
        }
    }

    private suspend fun download(target: AmllTarget, request: LyricsRequest, reason: AmllReason): AmllResult {
        var failure = AmllReason.HTTP
        for (url in urls(target)) {
            val host = URI(url).host
            val wait = synchronized(hostWait) { hostWait[host] }
            if (wait != null && wait > clock()) { failure = AmllReason.WAIT; continue }
            DiagLog.d("Lyrics", "amll try id=${validAppleId(request.identity.appleMusicId) ?: "none"} url=$host")
            val started = System.nanoTime()
            val response = try {
                transport.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/ttml+xml"))
            } catch (e: IOException) {
                DiagLog.d("Lyrics", "amll code=${e.javaClass.simpleName} ms=${(System.nanoTime() - started) / 1_000_000}")
                failure = AmllReason.NETWORK
                continue
            }
            DiagLog.d("Lyrics", "amll code=${response.code} ms=${(System.nanoTime() - started) / 1_000_000}")
            kotlin.coroutines.coroutineContext.ensureActive()
            if (response.code == 404) return result(LyricsState.Missing, AmllReason.NOT_FOUND, target, true)
            if (response.code == 429 || response.code == 503) {
                val seconds = response.retryAfterSec?.takeIf { it > 0 } ?: 60L
                synchronized(hostWait) { hostWait[host] = deadline(seconds) }
            }
            if (response.tooLarge) {
                DiagLog.d("Lyrics", "amll parse Invalid response-too-large")
                failure = AmllReason.INVALID
                continue
            }
            if (response.code != 200) {
                failure = AmllReason.HTTP
                if (response.code == 429 || response.code in 500..599) continue
                break
            }
            // A non-TTML XML/HTML response must not become a cacheable unsupported song.
            if (!isCompleteTtml(response.body)) {
                DiagLog.d("Lyrics", "amll parse Invalid incomplete-or-non-ttml")
                failure = AmllReason.INVALID
                continue
            }
            when (val parsed = TtmlParser.parse(response.body, request.query.durationSec?.let { it * 1000 }) {
                DiagLog.d("Lyrics", "amll parse $it")
            }) {
                is TtmlParseResult.WordSynced -> return result(LyricsState.Synced(parsed.lines), reason, target, true)
                TtmlParseResult.Unsupported -> return result(LyricsState.Missing, AmllReason.UNSUPPORTED, target, true)
                TtmlParseResult.Invalid -> failure = AmllReason.INVALID
            }
        }
        val until = deadline(60)
        synchronized(requestWait) { requestWait[request] = until }
        return result(if (failure == AmllReason.NETWORK) LyricsState.Offline else LyricsState.Failed,
            failure, target, wait = until)
    }

    /** Reject error documents and truncated XML before Unsupported can become a negative entry. */
    private suspend fun isCompleteTtml(xml: String): Boolean {
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) return false
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(StringReader(xml))
            var depth = 0
            var closed = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                kotlin.coroutines.coroutineContext.ensureActive()
                when (event) {
                    XmlPullParser.DOCDECL -> return false
                    XmlPullParser.START_TAG -> {
                        if (depth == 0 && (closed || parser.name != "tt" || parser.namespace != "http://www.w3.org/ns/ttml")) return false
                        if (++depth > 64) return false
                    }
                    XmlPullParser.END_TAG -> { if (--depth == 0) closed = true }
                }
                event = parser.nextToken()
            }
            closed && depth == 0
        } catch (_: XmlPullParserException) { false }
    }

    private fun deadline(seconds: Long): Long {
        val now = clock()
        return if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
    }

    private fun result(state: LyricsState, reason: AmllReason, target: AmllTarget? = null,
        cacheable: Boolean = false, wait: Long? = null): AmllResult {
        val waits = synchronized(hostWait) { hostWait.filterValues { it > clock() } }
        val until = maxOf(wait ?: clock(), waits.values.maxOrNull() ?: clock())
        val remaining = (until - clock()).coerceAtLeast(0)
        return AmllResult(state, reason, target, retryAfterSec = if (remaining > 0) remaining / 1000 + (if (remaining % 1000 > 0) 1 else 0) else null,
            hostWaitUntil = waits, cacheable = cacheable)
    }

    companion object {
        internal fun urls(target: AmllTarget): List<String> {
            require(target.safe)
            return if (target.folder == "am-lyrics") urls(target.id) else listOf(
                "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/raw-lyrics/${target.id}",
                "https://amll-ttml-db.gbclstudio.cn/raw-lyrics/${target.id}",
            )
        }
        internal fun urls(id: String) = listOf(
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics/$id.ttml",
            "https://amll-ttml-db.stevexmh.net/am/$id",
            "https://amll-ttml-db.gbclstudio.cn/am-lyrics/$id.ttml",
        )
    }
}
