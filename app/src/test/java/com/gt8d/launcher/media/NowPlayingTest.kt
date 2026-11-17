package com.gt8d.launcher.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingTest {
    @Test fun identitySurvivesSnapshotMapping() {
        val identity = com.gt8d.launcher.media.lyrics.LyricsIdentity(appleMusicId = "123")
        val now = snapshot().copy(lyricsIdentity = identity).toNowPlaying()
        assertEquals(identity, now.lyricsIdentity)
        assertEquals(identity, now.lyricsRequest!!.identity)
        assertEquals(now.lyricsQuery, now.lyricsRequest!!.query)
        assertNull(now.copy(metaTitle = null).lyricsRequest)
        assertEquals(com.gt8d.launcher.media.lyrics.LyricsIdentity(), snapshot().toNowPlaying().lyricsIdentity)
    }
    @Test fun metaTitleNullWhenFilledWithAppLabel() {
        val now = snapshot(title = null).toNowPlaying()
        assertNull(now.metaTitle)
        assertEquals("Music", now.title)
    }

    @Test fun albumPassedThrough() {
        val now = snapshot().copy(album = "Dummy album").toNowPlaying()
        assertEquals("Dummy album", now.album)
        assertEquals("Song", now.metaTitle)
        assertNull(snapshot().copy(album = "  ").toNowPlaying().album)
    }

    @Test fun lyricsQueryNullWithoutArtist() {
        assertNull(snapshot(artist = null).toNowPlaying().lyricsQuery)
    }

    @Test fun pick_pausedFallback_usesNewestAndKeepsFirstOnTie() {
        val older = snapshot(pkg = "older").copy(paused = true, positionUpdatedAt = 100)
        val newer = older.copy(pkg = "newer", positionUpdatedAt = 200)
        assertEquals(older, pickSession(listOf(older), null))
        assertEquals(newer, pickSession(listOf(older, newer), null))
        assertEquals(newer, pickSession(listOf(older, newer), "missing"))
        assertEquals(older, pickSession(listOf(older, newer.copy(positionUpdatedAt = 100)), null))
    }

    @Test fun pick_pausedFallback_rejectsMissingTitlesAndStopped() {
        for (title in listOf(null, "", "   ")) {
            assertNull(pickSession(listOf(snapshot(title = title).copy(paused = true)), null))
        }
        val paused = snapshot(pkg = "paused").copy(paused = true, positionUpdatedAt = 100)
        val stopped = snapshot(pkg = "stopped", positionUpdatedAt = 200)
        assertNull(pickSession(listOf(stopped), null))
        assertEquals(paused, pickSession(listOf(stopped, paused), null))
        assertEquals(stopped, pickSession(listOf(stopped, paused), "stopped"))
        val playing = snapshot(pkg = "playing", playing = true)
        assertEquals(playing, pickSession(listOf(stopped, paused, playing), "stopped"))
    }

    private fun snapshot(
        pkg: String = "com.example.music",
        appLabel: String = "Music",
        title: String? = "Song",
        artist: String? = "Artist",
        durationMs: Long = 180_000L,
        positionMs: Long = 0L,
        positionUpdatedAt: Long = 1000L,
        speed: Float = 1.0f,
        playing: Boolean = false,
        hasFavoriteAction: Boolean = false,
    ) = SessionSnapshot(
        pkg = pkg,
        appLabel = appLabel,
        title = title,
        artist = artist,
        artwork = null,
        durationMs = durationMs,
        positionMs = positionMs,
        positionUpdatedAt = positionUpdatedAt,
        speed = speed,
        playing = playing,
        hasFavoriteAction = hasFavoriteAction,
    )

    @Test
    fun pick_prefersPlaying() {
        val appleMusic = snapshot(pkg = "com.apple.android.music", appLabel = "Apple Music", playing = false)
        val youtube = snapshot(pkg = "com.google.android.youtube", appLabel = "YouTube", playing = true)

        val picked = pickSession(listOf(appleMusic, youtube), lastActivePkg = "com.apple.android.music")
        assertEquals(youtube, picked)

        val youtube2 = snapshot(pkg = "com.google.android.youtube", appLabel = "YouTube", playing = true)
        val applePlaying = snapshot(pkg = "com.apple.android.music", appLabel = "Apple Music", playing = true)
        val pickedMultiple = pickSession(listOf(youtube2, applePlaying), lastActivePkg = "com.apple.android.music")
        assertEquals(applePlaying, pickedMultiple)
    }

    @Test
    fun pick_fallsBackToLastActive() {
        val appleMusic = snapshot(pkg = "com.apple.android.music", appLabel = "Apple Music", playing = false)
        val youtube = snapshot(pkg = "com.google.android.youtube", appLabel = "YouTube", playing = false)

        val picked = pickSession(listOf(appleMusic, youtube), lastActivePkg = "com.google.android.youtube")
        assertEquals(youtube, picked)
    }

    @Test
    fun pick_none_returnsNull() {
        assertNull(pickSession(emptyList(), lastActivePkg = "com.apple.android.music"))

        val appleMusic = snapshot(pkg = "com.apple.android.music", appLabel = "Apple Music", playing = false)
        assertNull(pickSession(listOf(appleMusic), lastActivePkg = "com.google.android.youtube"))
        assertNull(pickSession(listOf(appleMusic), lastActivePkg = null))
    }

    @Test
    fun toNowPlaying_unknownDuration_hidesProgress() {
        val negDuration = snapshot(
            pkg = "com.example.radio",
            appLabel = "RadioApp",
            title = null,
            artist = "   ",
            durationMs = -1L,
            playing = true,
            hasFavoriteAction = true,
        ).toNowPlaying()
        assertNull(negDuration.durationMs)
        assertEquals("RadioApp", negDuration.title)
        assertNull(negDuration.artist)
        assertEquals(true, negDuration.hasFavorite)

        val zeroDuration = snapshot(
            pkg = "com.example.radio",
            appLabel = "RadioApp",
            title = "  ",
            artist = null,
            durationMs = 0L,
            playing = false,
            hasFavoriteAction = false,
        ).toNowPlaying()
        assertNull(zeroDuration.durationMs)
        assertEquals("RadioApp", zeroDuration.title)
        assertNull(zeroDuration.artist)
        assertEquals(false, zeroDuration.hasFavorite)
    }

    @Test
    fun positionAt_interpolatesAndClamps() {
        val s = snapshot(
            playing = true,
            speed = 1.0f,
            positionUpdatedAt = 1000L,
            positionMs = 10000L,
            durationMs = 12000L,
        )
        assertEquals(12000L, positionAt(s, nowMs = 6000L))
        assertEquals(11000L, positionAt(s, nowMs = 2000L))

        val paused = s.copy(playing = false, positionMs = 15000L)
        assertEquals(12000L, positionAt(paused, nowMs = 6000L))

        val unknownDuration = s.copy(durationMs = -1L, positionMs = 10000L)
        assertEquals(15000L, positionAt(unknownDuration, nowMs = 6000L))
    }

    @Test
    fun positionAt_guardsUnknownPositionZeroTimestampAndFutureTimestamp() {
        val unknownPosition = snapshot(
            playing = true,
            positionMs = -1L,
            positionUpdatedAt = 1000L,
        )
        assertEquals(0L, positionAt(unknownPosition, nowMs = 5000L))
        assertEquals(0L, unknownPosition.toNowPlaying().positionAt(nowMs = 5000L))

        val zeroTimestamp = snapshot(
            playing = true,
            positionMs = 2000L,
            positionUpdatedAt = 0L,
            durationMs = 10000L,
        )
        assertEquals(2000L, positionAt(zeroTimestamp, nowMs = 500_000L))
        assertEquals(2000L, zeroTimestamp.toNowPlaying().positionAt(nowMs = 500_000L))

        val futureTimestamp = zeroTimestamp.copy(positionUpdatedAt = 9000L)
        assertEquals(2000L, positionAt(futureTimestamp, nowMs = 5000L))
        assertEquals(2000L, futureTimestamp.toNowPlaying().positionAt(nowMs = 5000L))

        val trackStart = zeroTimestamp.copy(positionMs = 0L, positionUpdatedAt = 1000L, speed = 1f)
        assertEquals(1500L, positionAt(trackStart, nowMs = 2500L))
        assertEquals(1500L, trackStart.toNowPlaying().positionAt(nowMs = 2500L))
    }

    @Test
    fun nowPlaying_positionAt_interpolates() {
        val playing = NowPlaying(
            pkg = "com.example.music",
            title = "Song",
            artist = "Artist",
            artwork = null,
            durationMs = 10000L,
            playing = true,
            hasFavorite = false,
            positionMs = 1000L,
            positionUpdatedAt = 2000L,
            speed = 1f,
        )
        assertEquals(4000L, playing.positionAt(nowMs = 5000L))

        val paused = playing.copy(playing = false)
        assertEquals(1000L, paused.positionAt(nowMs = 5000L))
    }
}
