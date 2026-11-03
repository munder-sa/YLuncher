package com.gt8d.launcher.media.lyrics

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

data class LyricLine(val timeMs: Long, val text: String)

sealed interface LyricsState {
    data object Off : LyricsState
    data object Loading : LyricsState
    data class Synced(val lines: List<LyricLine>) : LyricsState
    data class Plain(val text: String) : LyricsState
    data object Missing : LyricsState
    data object Offline : LyricsState
    data object Failed : LyricsState
}

data class LyricsQuery(val title: String, val artist: String, val album: String?, val durationSec: Long?) {
    val cacheKey: String
        get() {
            val json = JsonArray(listOf(title, artist, album ?: "", durationSec?.toString() ?: "")
                .map { JsonPrimitive(it.trim().lowercase()) }).toString()
            return MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }

    companion object {
        fun of(title: String?, artist: String?, album: String?, durationMs: Long?): LyricsQuery? {
            val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // Only this interval can round to 1..3600; check before adding to avoid overflow.
            val seconds = durationMs?.takeIf { it in 500L..3_600_499L }?.let { (it + 500) / 1000 }
            return LyricsQuery(cleanTitle, cleanArtist, album?.trim()?.takeIf { it.isNotEmpty() }, seconds)
        }
    }
}
