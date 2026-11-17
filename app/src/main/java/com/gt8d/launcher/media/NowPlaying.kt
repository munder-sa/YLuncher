package com.gt8d.launcher.media

import android.graphics.Bitmap
import com.gt8d.launcher.media.lyrics.LyricsQuery
import com.gt8d.launcher.media.lyrics.LyricsIdentity
import com.gt8d.launcher.media.lyrics.LyricsRequest

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
    val lyricsIdentity: LyricsIdentity = LyricsIdentity(),
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
    val lyricsIdentity: LyricsIdentity = LyricsIdentity(),
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
    lyricsIdentity = lyricsIdentity,
)

internal fun lyricsIdentity(pkg: String, ids: LyricsMetadataProbe.Result): LyricsIdentity {
    val standard = ids.standard.value
    val apple = ids.apple
    val id = standard?.takeIf {
        pkg == "com.apple.android.music" && it.isNotEmpty() &&
            it.all { c -> c in '0'..'9' } && it.any { c -> c != '0' } &&
            (apple.type == null || apple.value == it)
    }
    return LyricsIdentity(appleMusicId = id)
}

val NowPlaying.lyricsQuery: LyricsQuery?
    get() = LyricsQuery.of(metaTitle, artist, album, durationMs)

val NowPlaying.lyricsRequest: LyricsRequest?
    get() = lyricsQuery?.let { LyricsRequest(it, lyricsIdentity) }

fun NowPlaying.positionAt(nowMs: Long): Long {
    if (positionMs < 0L) return 0L
    val raw = if (playing && positionUpdatedAt > 0L && nowMs > positionUpdatedAt) {
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
    if (s.positionMs < 0L) return 0L
    val raw = if (s.playing && s.positionUpdatedAt > 0L && nowMs > s.positionUpdatedAt) {
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
