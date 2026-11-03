package com.gt8d.launcher.media.lyrics

import android.graphics.Bitmap
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyricsQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val synced = LyricsState.Synced(listOf(LyricLine(1000, "Dummy line")))
    private fun song(title: String = "A") = NowPlaying(
        "dummy.pkg", title, "Dummy artist", null, 120_000, true, false, metaTitle = title,
    )

    private inner class Fixture(
        val scope: TestScope,
        on: Boolean = true,
        val answer: suspend (LyricsQuery) -> FetchResult = { FetchResult(synced) },
    ) {
        val now = MutableStateFlow<NowPlaying?>(song())
        val enabled = MutableStateFlow(on)
        val calls = mutableListOf<LyricsQuery>()
        val times = mutableListOf<Long>()
        val cache = LyricsCache(folder.newFolder(), { scope.testScheduler.currentTime })
        val repo = LyricsRepository(scope.backgroundScope, now, enabled, cache, {
            calls += it
            times += scope.testScheduler.currentTime
            answer(it)
        }, { scope.testScheduler.currentTime }, StandardTestDispatcher(scope.testScheduler))
        fun tick(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }
    }

    @Test fun offDoesNotFetch() = runTest {
        val f = Fixture(this, on = false)
        runCurrent()
        f.now.value = song("B")
        f.tick(1000)
        assertTrue(f.calls.isEmpty())
        assertEquals(LyricsState.Off, f.repo.state.value)
    }

    @Test fun noQueryDoesNotFetch() = runTest {
        val f = Fixture(this)
        f.now.value = song().copy(metaTitle = null)
        f.tick(1000)
        assertEquals(LyricsState.Off, f.repo.state.value)
        f.now.value = song().copy(artist = null)
        f.tick(1000)
        assertEquals(LyricsState.Off, f.repo.state.value)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun debounce600ms() = runTest {
        val f = Fixture(this)
        f.tick(599)
        assertTrue(f.calls.isEmpty())
        assertEquals(LyricsState.Loading, f.repo.state.value)
        f.tick(1)
        assertEquals(1, f.calls.size)
    }

    @Test fun rapidChangesFetchOnlyLast() = runTest {
        val f = Fixture(this)
        repeat(5) { f.now.value = song("Song $it"); f.tick(100) }
        f.tick(500)
        assertEquals(listOf(song("Song 4").lyricsQuery), f.calls)
    }

    @Test fun staleResponseIsDropped() = runTest {
        val pending = CompletableDeferred<FetchResult>()
        val other = LyricsState.Plain("Dummy B")
        val f = Fixture(this) { if (it.title == "A") pending.await() else FetchResult(other) }
        f.tick(600)
        f.now.value = song("B")
        runCurrent()
        pending.complete(FetchResult(synced))
        runCurrent()
        assertEquals(LyricsState.Loading, f.repo.state.value)
        f.tick(600)
        assertEquals(other, f.repo.state.value)
        assertEquals(listOf("A", "B"), f.calls.map { it.title })
        assertNull(f.cache.get(song().lyricsQuery!!))
    }

    @Test fun cancelWhileInFlight() = runTest {
        val pending = CompletableDeferred<FetchResult>()
        val f = Fixture(this) { pending.await() }
        f.tick(600)
        f.enabled.value = false
        runCurrent()
        assertEquals(LyricsState.Off, f.repo.state.value)
        pending.complete(FetchResult(synced))
        runCurrent()
        assertEquals(LyricsState.Off, f.repo.state.value)
        assertNull(f.cache.get(song().lyricsQuery!!))
    }

    @Test fun loadingThenSynced() = runTest {
        val pending = CompletableDeferred<FetchResult>()
        val f = Fixture(this) { pending.await() }
        runCurrent()
        assertEquals(LyricsState.Loading, f.repo.state.value)
        f.tick(600)
        assertEquals(LyricsState.Loading, f.repo.state.value)
        pending.complete(FetchResult(synced))
        runCurrent()
        assertEquals(synced, f.repo.state.value)
    }

    @Test fun cacheHitSkipsFetch() = runTest {
        val f = Fixture(this)
        f.cache.put(song().lyricsQuery!!, synced)
        runCurrent()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(synced, f.repo.state.value)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun resultIsCached() = runTest {
        val f = Fixture(this)
        f.tick(600)
        assertEquals(synced, f.cache.get(song().lyricsQuery!!))
        f.now.value = song("B")
        f.tick(600)
        f.now.value = song()
        runCurrent()
        assertEquals(synced, f.repo.state.value)
        assertEquals(2, f.calls.size)
    }

    @Test fun positionOrPlayStateChangeDoesNotRefetch() = runTest {
        val f = Fixture(this)
        f.tick(600)
        f.now.value = song().copy(positionMs = 5000)
        f.tick(1000)
        f.now.value = f.now.value!!.copy(playing = false)
        f.tick(1000)
        f.now.value = f.now.value!!.copy(artwork = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        f.tick(1000)
        assertEquals(1, f.calls.size)
    }

    @Test fun globalBackoffAcrossTracks() = runTest {
        val f = Fixture(this) { FetchResult(LyricsState.Failed, 30) }
        f.tick(600)
        f.tick(10_000)
        f.now.value = song("B")
        f.tick(19_999)
        assertEquals(1, f.calls.size)
        f.tick(1)
        assertEquals(listOf(600L, 30_600L), f.times)
        assertEquals("B", f.calls.last().title)
    }

    @Test fun backoffSurvivesToggle() = runTest {
        val f = Fixture(this) { FetchResult(LyricsState.Failed, 30) }
        f.tick(600)
        f.enabled.value = false
        runCurrent()
        f.tick(5000)
        f.enabled.value = true
        f.tick(24_999)
        assertEquals(1, f.calls.size)
        f.tick(1)
        assertEquals(listOf(600L, 30_600L), f.times)
    }

    @Test fun retriesThreeTimesAfterFirst() = runTest {
        val f = Fixture(this) { FetchResult(LyricsState.Offline) }
        f.tick(90_600)
        assertEquals(listOf(600L, 30_600L, 60_600L, 90_600L), f.times)
        assertEquals(LyricsState.Offline, f.repo.state.value)
        f.tick(200_000)
        assertEquals(4, f.calls.size)

        var count = 0
        val succeeds = Fixture(this) { FetchResult(if (++count == 3) synced else LyricsState.Offline) }
        succeeds.tick(60_600)
        assertEquals(3, succeeds.calls.size)
        assertEquals(synced, succeeds.repo.state.value)
        succeeds.tick(200_000)
        assertEquals(3, succeeds.calls.size)
    }

    @Test fun retryCountResetsOnTrackChangeAndToggle() = runTest {
        val f = Fixture(this) { FetchResult(LyricsState.Failed) }
        f.tick(90_600)
        assertEquals(4, f.calls.size)
        f.enabled.value = false
        runCurrent()
        f.enabled.value = true
        f.tick(90_600)
        assertEquals(8, f.calls.size)
        f.now.value = song("B")
        f.tick(90_600)
        assertEquals(12, f.calls.size)
        assertEquals(4, f.calls.count { it.title == "B" })
    }

    @Test fun fetchExceptionIsFailedAndRetried() = runTest {
        val f = Fixture(this) { throw IllegalStateException("Dummy failure") }
        f.tick(600)
        assertEquals(LyricsState.Failed, f.repo.state.value)
        f.tick(30_000)
        assertEquals(2, f.calls.size)
    }
}
