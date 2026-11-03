package com.gt8d.launcher.media.lyrics

import android.graphics.Bitmap
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyricsQuery
import com.gt8d.launcher.media.toNowPlaying
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
    @Test fun appleMusicIdReachesAmllClient() = runTest {
        DiagLog.clearForTest()
        val metadata = android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID, "1537460612")
            .build()
        val pkg = "com.apple.android.music"
        val f = Fixture(this, amllAnswer = { wordResult() })
        f.now.value = com.gt8d.launcher.media.SessionSnapshot(
            pkg, "Apple Music", "Dummy song", "Dummy artist", null,
            250_000, 0, 0, 1f, true, false,
            lyricsIdentity = com.gt8d.launcher.media.lyricsIdentity(
                pkg, com.gt8d.launcher.media.LyricsMetadataProbe.read(metadata)),
        ).toNowPlaying()
        f.tick(600)
        assertEquals("1537460612", f.amllCalls.single().identity.appleMusicId)
        assertEquals(words, f.repo.state.value)
        assertTrue(f.calls.isEmpty())
        assertTrue(DiagLog.dump().any { it.contains("amll result Synced source=AMLL") })
    }

    @Test fun logsAmllResponseAndParseWithoutLyrics() = kotlinx.coroutines.runBlocking {
        DiagLog.clearForTest()
        val xml = requireNotNull(javaClass.getResource("/ttml/structure-like-real.ttml")).readText()
        val client = AmllClient(AmllTransport { _, _ -> HttpResponse(200, xml, null) }, "test")
        val result = client.fetch(LyricsRequest(
            LyricsQuery("Dummy song", "Dummy artist", null, 250), LyricsIdentity("1537460612")))
        assertTrue(result.state is LyricsState.Synced)
        val logs = DiagLog.dump()
        assertTrue(logs.any { it.contains("amll try id=1537460612 url=raw.githubusercontent.com") })
        assertTrue(logs.any { it.contains("amll code=200 ms=") })
        assertTrue(logs.any { it.contains("amll parse WordSynced lines=54") })
        assertFalse(logs.any { it.contains("ア") || it.contains("<tt") })
    }

    @Test fun logsAmllFailuresAndLrclibSelection() = runTest {
        DiagLog.clearForTest()
        val f = Fixture(this)
        f.tick(600)
        assertTrue(DiagLog.dump().any { it.contains("amll result Synced source=LRCLIB") })
        for ((body, expected) in listOf(
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1">private lyric</p></div></body></tt>""" to "Unsupported no-timed-words",
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="bad"/></body></tt>""" to "Invalid timing-or-limit",
        )) {
            DiagLog.clearForTest()
            AmllClient(AmllTransport { _, _ -> HttpResponse(200, body, null) }, "test")
                .fetch(LyricsRequest(song().lyricsQuery!!, LyricsIdentity("123")))
            assertTrue(DiagLog.dump().any { it.contains("amll parse $expected") })
            assertFalse(DiagLog.dump().any { it.contains("private lyric") })
        }
        DiagLog.clearForTest()
        AmllClient(AmllTransport { _, _ -> throw java.io.IOException("private exception detail") }, "test")
            .fetch(LyricsRequest(song().lyricsQuery!!, LyricsIdentity("123")))
        assertTrue(DiagLog.dump().any { it.contains("amll code=IOException ms=") })
        assertFalse(DiagLog.dump().any { it.contains("private exception detail") })
    }

    @get:Rule val folder = TemporaryFolder()
    private val synced = LyricsState.Synced(listOf(LyricLine(1000, "Dummy line")))
    private fun song(title: String = "A") = NowPlaying(
        "dummy.pkg", title, "Dummy artist", null, 120_000, true, false, metaTitle = title,
    )

    private inner class Fixture(
        val scope: TestScope,
        on: Boolean = true,
        val amllAnswer: suspend (LyricsRequest) -> AmllResult = {
            AmllResult(LyricsState.Missing, AmllReason.NOT_FOUND, cacheable = true)
        },
        val answer: suspend (LyricsQuery) -> FetchResult = { FetchResult(synced) },
    ) {
        val now = MutableStateFlow<NowPlaying?>(song())
        val enabled = MutableStateFlow(on)
        val calls = mutableListOf<LyricsQuery>()
        val times = mutableListOf<Long>()
        val cache = LyricsCache(folder.newFolder(), { scope.testScheduler.currentTime })
        val amllCache = AmllCache(folder.newFolder(), { scope.testScheduler.currentTime })
        val amllCalls = mutableListOf<LyricsRequest>()
        val amllTimes = mutableListOf<Long>()
        val repo = LyricsRepository(scope.backgroundScope, now, enabled, cache, {
            calls += it
            times += scope.testScheduler.currentTime
            answer(it)
        }, { scope.testScheduler.currentTime }, StandardTestDispatcher(scope.testScheduler), amllCache, {
            amllCalls += it
            amllTimes += scope.testScheduler.currentTime
            amllAnswer(it)
        })
        fun tick(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }
    }

    private val words = LyricsState.Synced(listOf(
        LyricLine(1000, "架空の朝", listOf(LyricWord(1000, 0), LyricWord(1500, 2))),
    ))
    private fun wordResult(cacheable: Boolean = true) = AmllResult(words, AmllReason.DIRECT_ID,
        AmllTarget("am-lyrics", "123"), cacheable = cacheable)

    @Test fun identityChangeRefetchesButPositionDoesNot() = runTest {
        val f = Fixture(this)
        f.tick(600)
        f.now.value = song().copy(positionMs = 2000, playing = false)
        f.tick(1000)
        assertEquals(1, f.amllCalls.size)
        f.now.value = song().copy(lyricsIdentity = LyricsIdentity("123"))
        f.tick(599)
        assertEquals(1, f.amllCalls.size)
        f.tick(1)
        assertEquals(listOf(LyricsIdentity(), LyricsIdentity("123")), f.amllCalls.map { it.identity })
        assertEquals(1, f.calls.size)
    }

    @Test fun amllUpgradesLegacyLrclibCache() = runTest {
        for (legacy in listOf(synced, LyricsState.Plain("架空"), LyricsState.Missing)) {
            val f = Fixture(this, amllAnswer = { wordResult() })
            f.cache.put(song().lyricsQuery!!, legacy)
            runCurrent()
            assertEquals(legacy, f.repo.state.value)
            f.tick(599)
            assertTrue(f.amllCalls.isEmpty())
            f.tick(1)
            assertEquals(words, f.repo.state.value)
            assertTrue(f.calls.isEmpty())
            assertEquals(legacy, f.cache.get(song().lyricsQuery!!))
            assertEquals(words, f.amllCache.get(LyricsRequest(song().lyricsQuery!!), null)!!.state)
        }
    }

    @Test fun amllFailureUsesLrclibCache() = runTest {
        for (legacy in listOf(synced, LyricsState.Plain("架空"))) {
            val f = Fixture(this, amllAnswer = { AmllResult(LyricsState.Offline, AmllReason.NETWORK) })
            f.cache.put(song().lyricsQuery!!, legacy)
            f.tick(600)
            assertEquals(legacy, f.repo.state.value)
            assertEquals(1, f.amllCalls.size)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun amllCachePrecedesLegacyCacheWithoutRequests() = runTest {
        val f = Fixture(this)
        f.cache.put(song().lyricsQuery!!, synced)
        f.amllCache.put(LyricsRequest(song().lyricsQuery!!), null, wordResult())
        runCurrent()
        assertEquals(words, f.repo.state.value)
        assertTrue(f.amllCalls.isEmpty())
        assertTrue(f.calls.isEmpty())
        f.tick(300_000)
        assertTrue(f.amllCalls.isEmpty())
        assertTrue(f.calls.isEmpty())
    }

    @Test fun sourcePriorityIsWordThenSyncedThenPlain() = runTest {
        val first = Fixture(this, amllAnswer = { wordResult() })
        first.tick(600)
        assertEquals(words, first.repo.state.value)
        assertTrue(first.calls.isEmpty())
        val second = Fixture(this)
        second.tick(600)
        assertEquals(synced, second.repo.state.value)
        val plain = LyricsState.Plain("架空")
        val third = Fixture(this) { FetchResult(plain) }
        third.tick(600)
        assertEquals(plain, third.repo.state.value)
    }

    @Test fun wordlessAmllUsesLrclib() = runTest {
        val f = Fixture(this, amllAnswer = {
            AmllResult(synced, AmllReason.DIRECT_ID, AmllTarget("am-lyrics", "123"), cacheable = true)
        })
        f.tick(600)
        assertEquals(synced, f.repo.state.value)
        assertEquals(1, f.calls.size)
        assertNull(f.amllCache.get(LyricsRequest(song().lyricsQuery!!), null))
    }

    @Test fun transientAmllFailureIsNotOverallMissing() = runTest {
        for (state in listOf(LyricsState.Offline, LyricsState.Failed)) {
            val f = Fixture(this, amllAnswer = { AmllResult(state, AmllReason.NETWORK) }) {
                FetchResult(LyricsState.Missing)
            }
            f.tick(600)
            assertEquals(state, f.repo.state.value)
            assertNull(f.amllCache.get(LyricsRequest(song().lyricsQuery!!), null))
            assertEquals(LyricsState.Missing, f.cache.get(song().lyricsQuery!!))
            f.tick(10_000)
            assertEquals(2, f.calls.size)
            assertEquals(1, f.amllCalls.size)
        }
    }

    @Test fun cacheableFalseIsPreserved() = runTest {
        val f = Fixture(this, amllAnswer = { wordResult(cacheable = false) })
        f.tick(600)
        assertEquals(words, f.repo.state.value)
        assertNull(f.amllCache.get(LyricsRequest(song().lyricsQuery!!), null))
        assertNull(f.cache.get(song().lyricsQuery!!))
        f.now.value = song("B")
        f.tick(600)
        f.now.value = song()
        f.tick(600)
        assertEquals(listOf("A", "B", "A"), f.amllCalls.map { it.query.title })
    }

    @Test fun negativeDoesNotPoisonOtherSource() = runTest {
        val f = Fixture(this)
        f.tick(600)
        assertEquals(synced, f.repo.state.value)
        assertEquals(LyricsState.Missing, f.amllCache.get(LyricsRequest(song().lyricsQuery!!), null)!!.state)
        assertEquals(synced, f.cache.get(song().lyricsQuery!!))
        val reverse = Fixture(this, amllAnswer = { wordResult() })
        reverse.cache.put(song().lyricsQuery!!, LyricsState.Missing)
        reverse.tick(600)
        assertEquals(words, reverse.repo.state.value)
    }

    @Test fun offMakesZeroRequestsIncludingIndex() = runTest {
        var indexCalls = 0
        var transportCalls = 0
        val client = AmllClient(AmllTransport { _, _ -> transportCalls++; error("unexpected transport") }, "test",
            AmllIndexLookup { _, _ -> indexCalls++; AmllMatch.NotFound })
        val f = Fixture(this, on = false, amllAnswer = client::fetch)
        f.tick(1000)
        f.now.value = song("B")
        f.tick(1000)
        assertTrue(f.amllCalls.isEmpty())
        assertTrue(f.calls.isEmpty())
        assertEquals(0, indexCalls)
        assertEquals(0, transportCalls)
    }

    @Test fun offDuringReadDisconnects() = runTest {
        val started = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        val input = object : java.io.InputStream() {
            @Volatile var closed = false
            override fun read(): Int {
                started.countDown()
                if (!released.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw java.io.IOException("test read timed out")
                }
                throw java.io.IOException("closed")
            }
            override fun close() { closed = true; released.countDown() }
        }
        val connection = object : java.net.HttpURLConnection(java.net.URL("https://example.test")) {
            @Volatile var disconnected = false
            override fun getResponseCode() = 200
            override fun getInputStream() = input
            override fun disconnect() { disconnected = true }
            override fun connect() { }
            override fun usingProxy() = false
        }
        val client = AmllClient(AmllUrlConnectionTransport { connection }, "test")
        val f = Fixture(this, amllAnswer = {
            try { client.fetch(it) } finally { finished.complete(Unit) }
        })
        f.now.value = song().copy(lyricsIdentity = LyricsIdentity("123"))
        f.tick(600)
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            f.enabled.value = false
            runCurrent()
        }
        finished.await()
        runCurrent()
        assertTrue(input.closed)
        assertTrue(connection.disconnected)
        assertEquals(LyricsState.Off, f.repo.state.value)
        assertTrue(f.calls.isEmpty())
        assertNull(f.amllCache.get(LyricsRequest(song().lyricsQuery!!, LyricsIdentity("123")), null))
    }

    @Test fun lateResultFromAToBToAIsDropped() = runTest {
        val pending = CompletableDeferred<AmllResult>()
        var count = 0
        val f = Fixture(this, amllAnswer = {
            if (++count == 1) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pending.await() }
            else AmllResult(LyricsState.Missing, AmllReason.NOT_FOUND)
        })
        f.tick(600)
        f.now.value = song("B")
        runCurrent()
        f.now.value = song()
        runCurrent()
        pending.complete(wordResult())
        runCurrent()
        assertNull(f.amllCache.get(LyricsRequest(song().lyricsQuery!!), null))
        f.tick(600)
        assertEquals(synced, f.repo.state.value)
        assertEquals(2, f.amllCalls.size)
    }

    @Test fun cancelDoesNotWriteCache() = runTest {
        val pending = CompletableDeferred<AmllResult>()
        val f = Fixture(this, amllAnswer = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pending.await() }
        })
        f.tick(600)
        f.enabled.value = false
        runCurrent()
        pending.complete(wordResult())
        runCurrent()
        assertEquals(LyricsState.Off, f.repo.state.value)
        assertNull(f.amllCache.get(LyricsRequest(song().lyricsQuery!!), null))
        assertNull(f.cache.get(song().lyricsQuery!!))
    }

    @Test fun retry10Then30Then60Seconds() = runTest {
        val f = Fixture(this, amllAnswer = { AmllResult(LyricsState.Failed, AmllReason.NETWORK) }) {
            FetchResult(LyricsState.Offline)
        }
        f.tick(100_600)
        assertEquals(listOf(600L, 10_600L, 40_600L, 100_600L), f.times)
        assertEquals(listOf(600L, 100_600L), f.amllTimes)
    }

    @Test fun hostCooldownsAreIndependent() = runTest {
        val f = Fixture(this, amllAnswer = {
            AmllResult(LyricsState.Failed, AmllReason.HTTP, retryAfterSec = 300,
                hostWaitUntil = mapOf("raw.githubusercontent.com" to 300_600))
        }) { FetchResult(LyricsState.Offline, retryAfterSec = 30) }
        f.tick(30_600)
        assertEquals(listOf(600L, 30_600L), f.times)
        assertEquals(listOf(600L), f.amllTimes)
    }

    @Test fun noAmllUpgradeTimerAfterLrclibSuccess() = runTest {
        val f = Fixture(this, amllAnswer = { AmllResult(LyricsState.Failed, AmllReason.NETWORK) })
        f.tick(600)
        assertEquals(synced, f.repo.state.value)
        f.tick(300_000)
        assertEquals(1, f.amllCalls.size)
        assertEquals(1, f.calls.size)
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
        assertTrue(f.amllCalls.isEmpty())
        assertEquals(LyricsState.Loading, f.repo.state.value)
        f.tick(1)
        assertEquals(1, f.calls.size)
        assertEquals(1, f.amllCalls.size)
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

    @Test fun retryIntervals10s30s60s() = runTest {
        val f = Fixture(this) { FetchResult(LyricsState.Offline) }
        f.tick(100_600)
        assertEquals(listOf(600L, 10_600L, 40_600L, 100_600L), f.times)
        assertEquals(LyricsState.Offline, f.repo.state.value)
        f.tick(200_000)
        assertEquals(4, f.calls.size)

        var count = 0
        val succeeds = Fixture(this) { FetchResult(if (++count == 3) synced else LyricsState.Offline) }
        succeeds.tick(40_600)
        assertEquals(3, succeeds.calls.size)
        assertEquals(synced, succeeds.repo.state.value)
        succeeds.tick(200_000)
        assertEquals(3, succeeds.calls.size)
    }

    @Test fun retryCountResetsOnTrackChangeAndToggle() = runTest {
        val f = Fixture(this) { FetchResult(LyricsState.Failed) }
        f.tick(100_600)
        assertEquals(4, f.calls.size)
        f.enabled.value = false
        runCurrent()
        f.enabled.value = true
        f.tick(100_600)
        assertEquals(8, f.calls.size)
        f.now.value = song("B")
        f.tick(100_600)
        assertEquals(12, f.calls.size)
        assertEquals(4, f.calls.count { it.title == "B" })
    }

    @Test fun fetchExceptionIsFailedAndRetried() = runTest {
        val f = Fixture(this) { throw IllegalStateException("Dummy failure") }
        f.tick(600)
        assertEquals(LyricsState.Failed, f.repo.state.value)
        f.tick(10_000)
        assertEquals(2, f.calls.size)
    }

    @Test fun notCacheableResultIsNotStored() = runTest {
        val plain = LyricsState.Plain("Dummy")
        val f = Fixture(this) { FetchResult(plain, cacheable = false) }
        f.tick(600)
        assertEquals(plain, f.repo.state.value)
        assertNull(f.cache.get(song().lyricsQuery!!))
        f.now.value = song("B")
        f.tick(600)
        f.now.value = song()
        f.tick(600)
        assertEquals(listOf("A", "B", "A"), f.calls.map { it.title })
        assertEquals(plain, f.repo.state.value)
    }

    @Test fun logsTrackAndCache() = runTest {
        DiagLog.clearForTest()
        val f = Fixture(this)
        f.tick(600)
        assertTrue(DiagLog.dump().any { it.contains("track title=A") })
        assertTrue(DiagLog.dump().any { it.contains("cache miss") })
    }
}
