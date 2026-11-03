package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

data class FetchResult(val state: LyricsState, val retryAfterSec: Long? = null)

class LrclibClient(private val transport: HttpTransport, private val userAgent: String) {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun fetch(q: LyricsQuery): FetchResult {
        val url = buildString {
            append("https://lrclib.net/api/get?track_name=${encode(q.title)}&artist_name=${encode(q.artist)}")
            q.album?.let { append("&album_name=${encode(it)}") }
            q.durationSec?.let { append("&duration=$it") }
        }
        val response = try {
            transport.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        } catch (_: IOException) {
            return FetchResult(LyricsState.Offline)
        }
        if (response.code == 429) return FetchResult(LyricsState.Failed, response.retryAfterSec?.takeIf { it > 0 } ?: 60L)
        if (response.code == 404 || response.code == 400) return search(q, FetchResult(LyricsState.Missing))
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
        val response = try {
            transport.get(
                "https://lrclib.net/api/search?track_name=${encode(q.title)}&artist_name=${encode(q.artist)}",
                mapOf("User-Agent" to userAgent, "Accept" to "application/json")
            )
        } catch (_: IOException) {
            return fallback
        }
        if (response.code == 429) return fallback.copy(retryAfterSec = response.retryAfterSec?.takeIf { it > 0 } ?: 60L)
        if (response.tooLarge || response.code != 200) return fallback
        val entries = try {
            Json.parseToJsonElement(response.body) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        } ?: return fallback
        data class Candidate(val state: LyricsState.Synced, val sameAlbum: Boolean, val difference: Double)
        val candidates = entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            if (entry["instrumental"] == JsonPrimitive(true)) return@mapNotNull null
            val synced = (entry["syncedLyrics"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@mapNotNull null
            val lines = LrcParser.parse(synced)
            if (lines.isEmpty()) return@mapNotNull null
            val difference = q.durationSec?.let { duration ->
                val seconds = (entry["duration"] as? JsonPrimitive)?.takeIf { !it.isString }
                    ?.content?.toDoubleOrNull() ?: return@mapNotNull null
                kotlin.math.abs(seconds - duration).takeIf { it <= 2.0 } ?: return@mapNotNull null
            } ?: 0.0
            val album = (entry["albumName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            Candidate(LyricsState.Synced(lines), q.album != null && album?.trim()?.lowercase() == q.album.trim().lowercase(), difference)
        }
        val best = candidates.sortedWith(compareByDescending<Candidate> { it.sameAlbum }.thenBy { it.difference }).firstOrNull()
        return best?.let { FetchResult(it.state) } ?: fallback
    }
}
