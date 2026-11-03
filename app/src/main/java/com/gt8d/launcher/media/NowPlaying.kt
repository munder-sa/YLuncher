package com.gt8d.launcher.media

import android.graphics.Bitmap
import com.gt8d.launcher.media.lyrics.LyricsQuery

data class SessionSnapshot(
    val pkg: String,
    val appLabel: String,
    val title: String?,
    val artist: String?,
    val artwork: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    val playing: Boolean,
    val hasFavoriteAction: Boolean,
    val paused: Boolean = false,
    val album: String? = null,
)

data class NowPlaying(
    val pkg: String,
    val title: String,
    val artist: String?,
    val artwork: Bitmap?,
    val durationMs: Long?,
    val playing: Boolean,
    val hasFavorite: Boolean,
    val positionMs: Long = 0L,
    val positionUpdatedAt: Long = 0L,
    val speed: Float = 1f,
    val album: String? = null,
    val metaTitle: String? = null,
)

fun SessionSnapshot.toNowPlaying(): NowPlaying = NowPlaying(
    pkg = pkg,
    title = title?.takeIf { it.isNotBlank() } ?: appLabel,
    artist = artist?.takeIf { it.isNotBlank() },
    artwork = artwork,
    durationMs = durationMs.takeIf { it > 0L },
    playing = playing,
    hasFavorite = hasFavoriteAction,
    positionMs = positionMs,
    positionUpdatedAt = positionUpdatedAt,
    speed = speed,
    album = album?.takeIf { it.isNotBlank() },
    metaTitle = title?.takeIf { it.isNotBlank() },
)

val NowPlaying.lyricsQuery: LyricsQuery?
    get() = LyricsQuery.of(metaTitle, artist, album, durationMs)

fun NowPlaying.positionAt(nowMs: Long): Long {
    val raw = if (playing) {
        positionMs + ((nowMs - positionUpdatedAt) * speed).toLong()
    } else {
        positionMs
    }
    return if (durationMs != null && durationMs > 0L) {
        raw.coerceIn(0L, durationMs)
    } else {
        raw.coerceAtLeast(0L)
    }
}

fun positionAt(s: SessionSnapshot, nowMs: Long): Long {
    val raw = if (s.playing) {
        s.positionMs + ((nowMs - s.positionUpdatedAt) * s.speed).toLong()
    } else {
        s.positionMs
    }
    return if (s.durationMs > 0L) {
        raw.coerceIn(0L, s.durationMs)
    } else {
        raw.coerceAtLeast(0L)
    }
}

fun pickSession(sessions: List<SessionSnapshot>, lastActivePkg: String?): SessionSnapshot? {
    if (sessions.isEmpty()) return null
    val playingSessions = sessions.filter { it.playing }
    if (playingSessions.isNotEmpty()) {
        return playingSessions.firstOrNull { it.pkg == lastActivePkg } ?: playingSessions.first()
    }
    sessions.firstOrNull { it.pkg == lastActivePkg }?.let { return it }
    return sessions.filter { it.paused && !it.title.isNullOrBlank() }
        .maxByOrNull { it.positionUpdatedAt }
}
