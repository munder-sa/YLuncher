package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppleMusicRestoreTriggerTest {
    @Test fun offOtherPackagesAndInvalidMetadataDoNotRestore() = runTest {
        val playing = MutableStateFlow<NowPlaying?>(song())
        val enabled = MutableStateFlow(false)
        var calls = 0
        val watch = observeAppleMusicRestore(backgroundScope, playing, enabled) { calls++ }
        runCurrent()
        assertEquals(0, calls)
        playing.value = song().copy(pkg = "other.music")
        enabled.value = true
        runCurrent()
        assertEquals(0, calls)
        for (value in listOf(null, "", "0", "000", "-1", "1a", "１２", "123456789012345678901")) {
            playing.value = song().copy(lyricsIdentity = LyricsIdentity(appleMusicId = value))
            runCurrent()
            assertEquals(0, calls)
        }
        for (value in listOf(null, "", " ")) {
            playing.value = song().copy(metaTitle = value)
            runCurrent()
            assertEquals(0, calls)
            playing.value = song().copy(artist = value)
            runCurrent()
            assertEquals(0, calls)
        }
        watch.cancel()
    }

    @Test fun enablingAppleSongRequestsOnceAndPositionTicksAreIgnored() = runTest {
        val playing = MutableStateFlow<NowPlaying?>(song())
        val enabled = MutableStateFlow(false)
        var calls = 0
        observeAppleMusicRestore(backgroundScope, playing, enabled) { calls++ }
        runCurrent()
        enabled.value = true
        runCurrent()
        assertEquals(1, calls)
        repeat(10) {
            playing.value = song().copy(positionMs = it * 1_000L, positionUpdatedAt = it * 2_000L,
                playing = it % 2 == 0, speed = if (it % 2 == 0) 1f else 0f)
            runCurrent()
            assertEquals(1, calls)
        }
    }

    @Test fun metadataAndReenablingRequestAgainOnlyForApplicableSongs() = runTest {
        val playing = MutableStateFlow<NowPlaying?>(song())
        val enabled = MutableStateFlow(true)
        var calls = 0
        observeAppleMusicRestore(backgroundScope, playing, enabled) { calls++ }
        runCurrent()
        assertEquals(1, calls)
        playing.value = song().copy(lyricsIdentity = LyricsIdentity("456"))
        runCurrent()
        assertEquals(2, calls)
        playing.value = playing.value!!.copy(metaTitle = "Next track")
        runCurrent()
        assertEquals(3, calls)
        enabled.value = false
        runCurrent()
        playing.value = song()
        runCurrent()
        assertEquals(3, calls)
        enabled.value = true
        runCurrent()
        assertEquals(4, calls)
        playing.value = null
        runCurrent()
        assertEquals(4, calls)
    }

    @Test fun cancellingObserverScopeStopsRequests() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val playing = MutableStateFlow<NowPlaying?>(song())
        val enabled = MutableStateFlow(true)
        var calls = 0
        val watch = observeAppleMusicRestore(scope, playing, enabled) { calls++ }
        runCurrent()
        assertEquals(1, calls)
        scope.cancel()
        playing.value = song().copy(metaTitle = "Next")
        enabled.value = false
        runCurrent()
        enabled.value = true
        runCurrent()
        assertTrue(watch.isCancelled)
        assertEquals(1, calls)
    }

    private fun song() = NowPlaying("com.apple.android.music", "Song", "Artist", null, 200_000L,
        playing = true, hasFavorite = false, metaTitle = "Song", lyricsIdentity = LyricsIdentity("123"))
}
