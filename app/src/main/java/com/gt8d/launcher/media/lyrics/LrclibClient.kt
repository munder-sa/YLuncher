package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.gt8d.launcher.diag.DiagLog
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class HttpResponse(val code: Int, val body: String, val retryAfterSec: Long?, val tooLarge: Boolean = false)

fun interface HttpTransport {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

class UrlConnectionTransport : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            if (stream == null) return HttpResponse(code, "", retryAfter)
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val limit = 512 * 1024
                val buffer = ByteArray(8192)
                while (true) {
                    // Read at most one byte beyond the limit to detect an oversized response.
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                    if (count == -1) break
                    if (output.size() + count > limit) return HttpResponse(code, "", retryAfter, true)
                    output.write(buffer, 0, count)
                }
                return HttpResponse(code, output.toString("UTF-8"), retryAfter)
            }
        } finally {
            connection.disconnect()
        }
    }
}

data class FetchResult(val state: LyricsState, val retryAfterSec: Long? = null, val cacheable: Boolean = true)

class LrclibClient(
    private val transport: HttpTransport,
    private val userAgent: String,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val jitter: () -> Long = { Random.nextLong(0, 301) },
) {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun fetch(q: LyricsQuery): FetchResult {
        val result = fetchResult(q)
        DiagLog.d("Lyrics", "result ${result.state.javaClass.simpleName} cacheable=${result.cacheable}")
        return result
    }

    private fun request(kind: String, url: String): HttpResponse {
        for (attempt in 1..3) {
            val started = System.nanoTime()
            val response = try {
                transport.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
            } catch (e: IOException) {
                DiagLog.d("Lyrics", "$kind try=$attempt code=${e.javaClass.simpleName} ms=${(System.nanoTime() - started) / 1_000_000} retryAfter=null")
                throw e
            }
            DiagLog.d("Lyrics", "$kind try=$attempt code=${response.code} ms=${(System.nanoTime() - started) / 1_000_000} retryAfter=${response.retryAfterSec}")
            if (response.code !in setOf(500, 502, 503, 504) || attempt == 3) return response
            sleep(response.retryAfterSec?.coerceIn(1L, 10L)?.times(1000L) ?: (attempt * 1000L + jitter()))
        }
        error("unreachable")
    }

    private fun fetchResult(q: LyricsQuery): FetchResult {
        val url = buildString {
            append("https://lrclib.net/api/get?track_name=${encode(q.title)}&artist_name=${encode(q.artist)}")
            q.album?.let { append("&album_name=${encode(it)}") }
            q.durationSec?.let { append("&duration=$it") }
        }
        val response = try {
            request("get", url)
        } catch (_: IOException) {
            return FetchResult(LyricsState.Offline)
        }
        if (response.code == 429) return FetchResult(LyricsState.Failed, response.retryAfterSec?.takeIf { it > 0 } ?: 60L)
        if (response.code == 404 || response.code == 400) return search(q, FetchResult(LyricsState.Missing))
        if (response.code in 500..599) return search(q, FetchResult(LyricsState.Failed, cacheable = false))
        if (response.tooLarge || response.code != 200) return FetchResult(LyricsState.Failed)
        val json = try {
            Json.parseToJsonElement(response.body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return FetchResult(LyricsState.Failed)
        if (json["instrumental"] == JsonPrimitive(true)) return FetchResult(LyricsState.Missing)
        fun string(name: String) = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        string("syncedLyrics")?.let {
            val lines = LrcParser.parse(it)
            if (lines.isNotEmpty()) return FetchResult(LyricsState.Synced(lines))
        }
        string("plainLyrics")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return search(q, FetchResult(LyricsState.Plain(it)))
        }
        return search(q, FetchResult(LyricsState.Missing))
    }

    private fun search(q: LyricsQuery, fallback: FetchResult): FetchResult {
        sleep(300)
        val failed = FetchResult(
            if (fallback.state is LyricsState.Plain) fallback.state else LyricsState.Failed,
            cacheable = false,
        )
        fun failure(retryAfterSec: Long? = null): FetchResult {
            DiagLog.d("Lyrics", "search candidates synced=0 plain=0 picked=none diff=null")
            return failed.copy(retryAfterSec = retryAfterSec)
        }
        val response = try {
            request("search",
                "https://lrclib.net/api/search?track_name=${encode(q.title)}&artist_name=${encode(q.artist)}",
            )
        } catch (_: IOException) {
            return failure()
        }
        if (response.code == 429) return failure(response.retryAfterSec?.takeIf { it > 0 } ?: 60L)
        if (response.tooLarge || response.code != 200) return failure()
        val entries = try {
            Json.parseToJsonElement(response.body) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        } ?: return failure()
        data class Candidate(val state: LyricsState, val sameAlbum: Boolean, val difference: Double)
        val candidates = entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            if (entry["instrumental"] == JsonPrimitive(true)) return@mapNotNull null
            fun string(name: String) = (entry[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val lines = string("syncedLyrics")?.let { LrcParser.parse(it) }.orEmpty()
            val plain = string("plainLyrics")?.trim()?.takeIf { it.isNotEmpty() }
            val state = if (lines.isNotEmpty()) LyricsState.Synced(lines)
                else plain?.let { LyricsState.Plain(it) } ?: return@mapNotNull null
            val difference = q.durationSec?.let { duration ->
                val seconds = (entry["duration"] as? JsonPrimitive)?.takeIf { !it.isString }
                    ?.content?.toDoubleOrNull() ?: return@mapNotNull null
                kotlin.math.abs(seconds - duration).takeIf { it <= 2.0 } ?: return@mapNotNull null
            } ?: 0.0
            val album = (entry["albumName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            Candidate(state, q.album != null && album?.trim()?.lowercase() == q.album.trim().lowercase(), difference)
        }
        val synced = candidates.filter { it.state is LyricsState.Synced }
        val plain = candidates.filter { it.state is LyricsState.Plain }
        val eligible = if (synced.isNotEmpty()) synced else if (fallback.state == LyricsState.Missing) plain else emptyList()
        val best = eligible.sortedWith(compareByDescending<Candidate> { it.sameAlbum }.thenBy { it.difference }).firstOrNull()
        val picked = when (best?.state) { is LyricsState.Synced -> "synced"; is LyricsState.Plain -> "plain"; else -> "none" }
        DiagLog.d("Lyrics", "search candidates synced=${synced.size} plain=${plain.size} picked=$picked diff=${best?.difference}")
        return best?.let { FetchResult(it.state) } ?: fallback
    }
}
