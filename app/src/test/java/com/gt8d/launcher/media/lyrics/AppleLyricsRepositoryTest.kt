package com.gt8d.launcher.media.lyrics

import android.graphics.Bitmap
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyricsRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
class AppleLyricsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val connected = AppleMusicConnection(AppleMusicStatus.CONNECTED, 1, "jp")
    private val appleWords = words("PRIVATE-APPLE-SYNTHETIC-LYRIC")
    private val amllWords = words("PRIVATE-AMLL-SYNTHETIC-LYRIC")
    private val synced = LyricsState.Synced(listOf(LyricLine(1000, "PRIVATE-LRCLIB-SYNTHETIC-LYRIC")))
    private fun words(text: String) = LyricsState.Synced(listOf(
        LyricLine(1000, text, listOf(LyricWord(1000, 0), LyricWord(1500, 3))),
    ))
    private fun appleSuccess(state: LyricsState = appleWords) = AppleLyricsResult(state, AppleLyricsReason.OK)
    private fun amllSuccess() = AmllResult(amllWords, AmllReason.DIRECT_ID, AmllTarget("am-lyrics", "123"), cacheable = true)
    private fun song(title: String = "A", id: String? = "123") = NowPlaying(
        "com.apple.android.music", title, "Synthetic artist", null, 120_000, true, false,
        metaTitle = title, lyricsIdentity = LyricsIdentity(id),
    )
    private fun request(title: String = "A", id: String? = "123") = requireNotNull(song(title, id).lyricsRequest)
    private fun key(title: String = "A", connection: AppleMusicConnection = connected) =
        requireNotNull(AppleLyricsCache.key(request(title), connection))

    private inner class Fixture(
        val scope: TestScope,
        on: Boolean = true,
        val connection: StateFlow<AppleMusicConnection> = MutableStateFlow(connected),
        val appleAnswer: suspend (LyricsRequest, Long) -> AppleLyricsResult = { _, _ -> appleSuccess() },
        val amllAnswer: suspend (LyricsRequest) -> AmllResult = { AmllResult(LyricsState.Missing, AmllReason.NOT_FOUND) },
        val answer: suspend (LyricsQuery) -> FetchResult = { FetchResult(synced) },
    ) {
        val now = MutableStateFlow<NowPlaying?>(song())
        val enabled = MutableStateFlow(on)
        val appleCache = AppleLyricsCache { scope.testScheduler.currentTime }
        val cache = LyricsCache(folder.newFolder(), { scope.testScheduler.currentTime })
        val amllCache = AmllCache(folder.newFolder(), { scope.testScheduler.currentTime })
        val appleCalls = mutableListOf<Pair<LyricsRequest, Long>>()
        val appleTimes = mutableListOf<Long>()
        val amllCalls = mutableListOf<LyricsRequest>()
        val amllTimes = mutableListOf<Long>()
        val calls = mutableListOf<LyricsQuery>()
        val times = mutableListOf<Long>()
        val repo = LyricsRepository(
            scope.backgroundScope, now, enabled, cache,
            { calls += it; times += scope.testScheduler.currentTime; answer(it) },
            { scope.testScheduler.currentTime }, StandardTestDispatcher(scope.testScheduler), amllCache,
            { amllCalls += it; amllTimes += scope.testScheduler.currentTime; amllAnswer(it) },
            connection, appleCache,
            { request, generation ->
                appleCalls += request to generation
                appleTimes += scope.testScheduler.currentTime
                appleAnswer(request, generation)
            },
        )
        fun tick(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }
        fun toggle() {
            enabled.value = false
            scope.runCurrent()
            enabled.value = true
            scope.runCurrent()
        }
        fun assertNoDiskEntries() {
            assertNull(cache.get(request().query))
            assertNull(amllCache.get(request(), null))
        }
    }

    @Test fun appleWinsAndSkipsLowerSources() = runTest {
        val f = Fixture(this)
        f.tick(599)
        assertTrue(f.appleCalls.isEmpty())
        assertEquals(LyricsState.Loading, f.repo.state.value)
        f.tick(1)
        assertEquals(listOf(600L), f.appleTimes)
        assertEquals(listOf(request() to 1L), f.appleCalls)
        assertEquals(appleWords, f.repo.state.value)
        assertEquals(appleWords, f.appleCache.get(key())!!.state)
        f.assertNoDiskEntries()
        f.toggle()
        assertEquals(appleWords, f.repo.state.value) // Cache success is immediate, without 600ms.
        f.tick(300_000)
        assertEquals(1, f.appleCalls.size)
        assertTrue(f.amllCalls.isEmpty())
        assertTrue(f.calls.isEmpty())
        f.assertNoDiskEntries()

        val tooLarge = words("x".repeat(2 * 1024 * 1024 + 1))
        val large = Fixture(this, appleAnswer = { _, _ -> appleSuccess(tooLarge) })
        large.tick(600)
        assertEquals(tooLarge, large.repo.state.value)
        assertNull(large.appleCache.get(key()))
        assertTrue(large.amllCalls.isEmpty())
        large.assertNoDiskEntries()
    }

    @Test fun appleUpgradesBothExistingCaches() = runTest {
        for (legacy in listOf(amllWords, synced, LyricsState.Plain("Synthetic plain"), LyricsState.Missing)) {
            val pending = CompletableDeferred<AppleLyricsResult>()
            val f = Fixture(this, appleAnswer = { _, _ -> pending.await() })
            if (legacy == amllWords) {
                f.amllCache.put(request(), null, amllSuccess())
                f.cache.put(request().query, synced)
            } else f.cache.put(request().query, legacy)
            runCurrent()
            assertEquals(legacy, f.repo.state.value)
            f.tick(599)
            assertTrue(f.appleCalls.isEmpty())
            f.tick(1)
            assertEquals(1, f.appleCalls.size)
            assertEquals(legacy, f.repo.state.value)
            assertTrue(f.amllCalls.isEmpty())
            assertTrue(f.calls.isEmpty())
            pending.complete(appleSuccess())
            runCurrent()
            assertEquals(appleWords, f.repo.state.value)
            assertEquals(if (legacy == amllWords) synced else legacy, f.cache.get(request().query))
            assertEquals(if (legacy == amllWords) amllWords else null, f.amllCache.get(request(), null)?.state)
        }
    }

    @Test fun appleFailuresFallBackWithoutPoisoningCaches() = runTest {
        val failures = AppleLyricsReason.entries.filter { it != AppleLyricsReason.OK }.map { reason ->
            AppleLyricsResult(if (reason in listOf(AppleLyricsReason.NOT_FOUND, AppleLyricsReason.UNSUPPORTED,
                    AppleLyricsReason.NOT_APPLICABLE)) LyricsState.Missing else LyricsState.Failed, reason)
        } + listOf(
            AppleLyricsResult(LyricsState.Offline, AppleLyricsReason.NETWORK),
            appleSuccess(LyricsState.Plain("Rejected Apple plain")), appleSuccess(synced),
            appleSuccess(LyricsState.Synced(emptyList())), appleSuccess(LyricsState.Off),
            appleSuccess(LyricsState.Loading), AppleLyricsResult(appleWords, AppleLyricsReason.INVALID),
        )
        for (failure in failures) {
            for (legacy in listOf(amllWords, synced, LyricsState.Plain("Synthetic plain"), LyricsState.Missing)) {
                val pending = CompletableDeferred<AppleLyricsResult>()
                val f = Fixture(this, appleAnswer = { _, _ -> pending.await() })
                if (legacy == amllWords) f.amllCache.put(request(), null, amllSuccess())
                else f.cache.put(request().query, legacy)
                f.tick(600)
                assertEquals(legacy, f.repo.state.value)
                pending.complete(failure)
                runCurrent()
                assertEquals(legacy, f.repo.state.value)
                assertEquals(1, f.appleCalls.size)
                assertTrue(f.calls.isEmpty())
                assertEquals(if (legacy == amllWords) null else legacy, f.cache.get(request().query))
                assertEquals(if (legacy == amllWords) amllWords else null, f.amllCache.get(request(), null)?.state)
            }
        }
        val fromAmll = Fixture(this, appleAnswer = { _, _ -> failures.first() }, amllAnswer = { amllSuccess() })
        fromAmll.tick(600)
        assertEquals(amllWords, fromAmll.repo.state.value)
        assertEquals(1, fromAmll.amllCalls.size)
        assertTrue(fromAmll.calls.isEmpty())
        assertEquals(amllWords, fromAmll.amllCache.get(request(), null)!!.state)
        assertNull(fromAmll.cache.get(request().query))

        val exception = Fixture(this, appleAnswer = { _, _ -> error("PRIVATE-EXCEPTION-SECRET") })
        exception.tick(600)
        assertEquals(synced, exception.repo.state.value)
        assertEquals(synced, exception.cache.get(request().query))
        assertNull(exception.amllCache.get(request(), null))
        assertEquals(LyricsState.Failed, exception.appleCache.get(key())!!.state)
        assertEquals(AppleLyricsReason.NETWORK, exception.appleCache.get(key())!!.reason)
        exception.tick(300_000)
        assertEquals(1, exception.appleCalls.size)
        assertEquals(1, exception.calls.size)
    }

    @Test fun disabledOrIneligibleMakesNoAppleRequests() = runTest {
        val off = Fixture(this, on = false)
        off.tick(1000)
        off.now.value = song("B")
        off.tick(1000)
        assertEquals(LyricsState.Off, off.repo.state.value)
        assertTrue(off.appleCalls.isEmpty())
        assertTrue(off.amllCalls.isEmpty())
        assertTrue(off.calls.isEmpty())

        val ineligible = listOf(song().copy(pkg = "other.player")) +
            listOf(null, "", "0", "000", "１２３", "-1", "123 ", "9".repeat(21)).map { song(id = it) }
        for (now in ineligible) {
            val f = Fixture(this)
            f.now.value = now
            f.tick(600)
            assertTrue(f.appleCalls.isEmpty())
            assertEquals(1, f.amllCalls.size)
            assertEquals(1, f.calls.size)
            assertEquals(synced, f.repo.state.value)
        }
        val connections = AppleMusicStatus.entries.filter { it != AppleMusicStatus.CONNECTED }.map { connected.copy(status = it) } +
            listOf(null, "", "JP", "jpn", "j1").map { connected.copy(storefront = it) } + connected.copy(generation = -1)
        for (connection in connections) {
            val f = Fixture(this, connection = MutableStateFlow(connection))
            f.tick(600)
            assertTrue(f.appleCalls.isEmpty())
            assertEquals(1, f.amllCalls.size)
            assertEquals(synced, f.repo.state.value)
        }
        for (now in listOf(null, song().copy(metaTitle = null), song().copy(metaTitle = " "),
                song().copy(artist = null), song().copy(artist = " "))) {
            val f = Fixture(this)
            f.now.value = now
            f.tick(1000)
            assertEquals(LyricsState.Off, f.repo.state.value)
            assertTrue(f.appleCalls.isEmpty())
            assertTrue(f.amllCalls.isEmpty())
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun connectionCompletionFetchesTheSameTrack() = runTest {
        val connection = MutableStateFlow(connected.copy(status = AppleMusicStatus.CHECKING, storefront = null))
        val f = Fixture(this, connection = connection)
        f.tick(600)
        assertEquals(synced, f.repo.state.value)
        assertTrue(f.appleCalls.isEmpty())
        connection.value = connected
        f.tick(599)
        assertEquals(synced, f.repo.state.value)
        assertTrue(f.appleCalls.isEmpty())
        f.tick(1)
        assertEquals(appleWords, f.repo.state.value)
        connection.value = connected.copy(reason = AppleLyricsReason.WAIT, retryAfterSec = 120)
        f.tick(1000)
        assertEquals(1, f.appleCalls.size)
        assertEquals(1, f.amllCalls.size)
        assertEquals(1, f.calls.size)

        // A diagnostic-only update must not restart the debounce or an in-flight failure.
        val pending = CompletableDeferred<AppleLyricsResult>()
        val other = MutableStateFlow(connected)
        val failing = Fixture(this, connection = other, appleAnswer = { _, _ -> pending.await() })
        failing.tick(300)
        other.value = connected.copy(reason = AppleLyricsReason.NETWORK)
        failing.tick(300)
        assertEquals(1, failing.appleCalls.size)
        other.value = other.value.copy(retryAfterSec = 300)
        runCurrent()
        pending.complete(AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND))
        runCurrent()
        failing.tick(1000)
        assertEquals(1, failing.appleCalls.size)
        assertEquals(1, failing.calls.size)
    }

    @Test fun positionPlaybackAndArtworkDoNotRefetch() = runTest {
        val f = Fixture(this)
        f.tick(600)
        for (now in listOf(song().copy(positionMs = 5000, positionUpdatedAt = 1000, speed = 2f),
                song().copy(playing = false), song().copy(artwork = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))) {
            f.now.value = now
            f.tick(1000)
        }
        assertEquals(1, f.appleCalls.size)
        f.now.value = song(id = "124")
        f.tick(600)
        f.now.value = song("B", "124")
        f.tick(600)
        assertEquals(listOf(request(), request(id = "124"), request("B", "124")), f.appleCalls.map { it.first })
        assertTrue(f.amllCalls.isEmpty())
        assertTrue(f.calls.isEmpty())
    }

    @Test fun rapidTrackChangesAndLateResultsAreDiscarded() = runTest {
        val rapid = Fixture(this)
        repeat(5) { rapid.now.value = song("Song $it"); rapid.tick(100) }
        rapid.tick(499)
        assertTrue(rapid.appleCalls.isEmpty())
        rapid.tick(1)
        assertEquals(listOf("Song 4"), rapid.appleCalls.map { it.first.query.title })

        val pending = CompletableDeferred<AppleLyricsResult>()
        var count = 0
        val f = Fixture(this, appleAnswer = { _, _ ->
            if (++count == 1) withContext(NonCancellable) { pending.await() }
            else appleSuccess(words("Fresh Apple result"))
        })
        f.tick(600)
        try {
            f.now.value = song("B")
            runCurrent()
            f.now.value = song()
            runCurrent()
        } finally {
            pending.complete(appleSuccess(words("Stale Apple result")))
        }
        runCurrent()
        assertNull(f.appleCache.get(key()))
        assertEquals(LyricsState.Loading, f.repo.state.value)
        f.assertNoDiskEntries()
        f.tick(600)
        assertEquals(words("Fresh Apple result"), f.repo.state.value)
        assertEquals(listOf("A", "A"), f.appleCalls.map { it.first.query.title })
        assertEquals(words("Fresh Apple result"), f.appleCache.get(key())!!.state)
    }

    @Test fun offCancelsAndPreventsCacheWrites() = runTest {
        for (throws in listOf(false, true)) {
            val pending = CompletableDeferred<Unit>()
            val unrelated = CompletableDeferred<Unit>()
            val sibling = backgroundScope.launch { delay(1000); unrelated.complete(Unit) }
            val f = Fixture(this, appleAnswer = { _, _ ->
                withContext(NonCancellable) {
                    pending.await()
                    if (throws) error("PRIVATE-LATE-EXCEPTION") else appleSuccess()
                }
            })
            f.tick(600)
            try {
                f.enabled.value = false
                runCurrent()
                assertEquals(LyricsState.Off, f.repo.state.value)
                assertTrue(sibling.isActive)
            } finally {
                pending.complete(Unit)
            }
            runCurrent()
            assertEquals(LyricsState.Off, f.repo.state.value)
            assertNull(f.appleCache.get(key()))
            f.assertNoDiskEntries()
            assertTrue(f.amllCalls.isEmpty())
            assertTrue(f.calls.isEmpty())
            f.tick(400)
            assertTrue(unrelated.isCompleted)
            assertFalse(sibling.isCancelled)
        }
    }

    @Test fun disconnectAndReplacementClearOldCache() = runTest {
        val connection = MutableStateFlow(connected)
        val f = Fixture(this, connection = connection)
        f.tick(600)
        assertNotNull(f.appleCache.get(key()))
        connection.value = AppleMusicConnection(generation = 2)
        runCurrent()
        assertNull(f.appleCache.get(key()))
        f.tick(600)
        assertEquals(synced, f.repo.state.value)
        assertEquals(1, f.appleCalls.size)
        connection.value = connected.copy(generation = 3)
        f.tick(600)
        assertEquals(appleWords, f.repo.state.value)
        val thirdKey = key(connection = connection.value)
        assertNotNull(f.appleCache.get(thirdKey))
        f.enabled.value = false
        runCurrent()
        connection.value = connected.copy(generation = 4)
        runCurrent()
        assertNull(f.appleCache.get(thirdKey))
        assertEquals(LyricsState.Off, f.repo.state.value)
        assertEquals(2, f.appleCalls.size)

        for (disable in listOf(false, true)) {
            val pending = CompletableDeferred<AppleLyricsResult>()
            val replacement = MutableStateFlow(connected)
            val late = Fixture(this, connection = replacement, appleAnswer = { _, generation ->
                if (generation == 1L) withContext(NonCancellable) { pending.await() }
                else appleSuccess(words("New connection lyric"))
            })
            late.tick(600)
            val oldKey = key("Old cached song")
            late.appleCache.put(oldKey, appleSuccess())
            try {
                if (disable) { late.enabled.value = false; runCurrent() }
                replacement.value = AppleMusicConnection(generation = 2)
                runCurrent()
                assertNull(late.appleCache.get(oldKey)) // Does not wait for pending's non-cooperative IO.
                replacement.value = connected.copy(generation = 3)
                runCurrent()
                late.appleCache.activateGeneration(1)
                late.appleCache.put(oldKey, appleSuccess())
                assertNull(late.appleCache.get(oldKey))
            } finally {
                pending.complete(appleSuccess(words("Old connection lyric")))
            }
            runCurrent()
            assertNull(late.appleCache.get(key()))
            assertNull(late.appleCache.get(key(connection = replacement.value)))
            late.assertNoDiskEntries()
            if (disable) {
                assertEquals(LyricsState.Off, late.repo.state.value)
                late.enabled.value = true
            }
            late.tick(600)
            assertEquals(words("New connection lyric"), late.repo.state.value)
            assertEquals(listOf(1L, 3L), late.appleCalls.map { it.second })
            assertEquals(words("New connection lyric"), late.appleCache.get(key(connection = replacement.value))!!.state)
        }
    }

    @Test fun authenticationFailureUsesSessionStateAndFallback() = runTest {
        var connectCalls = 0
        var fetchCalls = 0
        val session = AppleMusicSession(
            { connectCalls++; AppleConnectResult.Ready("jp") },
            { _, _, _ ->
                fetchCalls++
                if (fetchCalls == 1) AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.AUTH_REQUIRED)
                else appleSuccess()
            },
        )
        session.connect("PRIVATE-SYNTHETIC-USER-TOKEN")
        val f = Fixture(this, connection = session.state, appleAnswer = session::fetch)
        f.cache.put(request().query, synced)
        runCurrent()
        f.appleCache.put(key("Previously cached"), appleSuccess())
        f.tick(600)
        assertEquals(AppleMusicStatus.NEEDS_ATTENTION, session.state.value.status)
        assertEquals(AppleLyricsReason.AUTH_REQUIRED, session.state.value.reason)
        assertEquals(2L, session.state.value.generation)
        assertNull(f.appleCache.get(key("Previously cached")))
        assertNull(f.appleCache.get(key()))
        assertEquals(synced, f.repo.state.value)
        f.tick(600)
        assertEquals(1, f.amllCalls.size)
        assertEquals(synced, f.repo.state.value)
        f.toggle()
        f.tick(300_000)
        assertEquals(1, fetchCalls)
        assertEquals(1, connectCalls)
        session.connect("PRIVATE-REPLACEMENT-USER-TOKEN")
        f.tick(600)
        assertEquals(2, connectCalls)
        assertEquals(2, fetchCalls)
        assertEquals(AppleMusicStatus.CONNECTED, session.state.value.status)
        assertEquals(3L, session.state.value.generation)
        assertEquals(appleWords, f.repo.state.value)
    }

    @Test fun cooldownAndLegacyRetriesDoNotRepeatApple() = runTest {
        val cases = listOf(
            AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.NETWORK) to 60_000L,
            AppleLyricsResult(LyricsState.Offline, AppleLyricsReason.WAIT, 90) to 90_000L,
            AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND) to 60_000L,
            AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.UNSUPPORTED) to 60_000L,
        )
        for ((failure, ttl) in cases) {
            val f = Fixture(this, appleAnswer = { _, _ -> failure })
            f.tick(600)
            val savedAt = testScheduler.currentTime
            assertEquals(synced, f.repo.state.value)
            f.toggle()
            f.tick(600)
            assertEquals(1, f.appleCalls.size)
            assertEquals(failure.state, f.appleCache.get(key())!!.state)
            f.enabled.value = false
            runCurrent()
            f.tick(savedAt + ttl - 601 - testScheduler.currentTime)
            f.enabled.value = true
            f.tick(600)
            assertEquals(savedAt + ttl - 1, testScheduler.currentTime)
            assertEquals(1, f.appleCalls.size)
            f.enabled.value = false
            runCurrent()
            f.tick(1)
            assertNull(f.appleCache.get(key()))
            f.enabled.value = true
            f.tick(600)
            assertEquals(2, f.appleCalls.size)
        }

        val start = testScheduler.currentTime
        val retries = Fixture(this,
            appleAnswer = { _, _ -> AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.NETWORK) },
            amllAnswer = { AmllResult(LyricsState.Failed, AmllReason.NETWORK) },
            answer = { FetchResult(LyricsState.Offline) },
        )
        retries.tick(100_600)
        assertEquals(listOf(600L, 10_600L, 40_600L, 100_600L), retries.times.map { it - start })
        assertEquals(listOf(600L, 100_600L), retries.amllTimes.map { it - start })
        assertEquals(1, retries.appleCalls.size)
        assertEquals(LyricsState.Offline, retries.repo.state.value)

        var lowerCalls = 0
        val succeeds = Fixture(this,
            appleAnswer = { _, _ -> AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.TIMEOUT) },
            answer = { FetchResult(if (++lowerCalls == 2) synced else LyricsState.Offline) },
        )
        succeeds.tick(10_600)
        assertEquals(synced, succeeds.repo.state.value)
        succeeds.tick(300_000)
        assertEquals(2, succeeds.calls.size)
        assertEquals(1, succeeds.amllCalls.size)
        assertEquals(1, succeeds.appleCalls.size)

        val missing = Fixture(this,
            appleAnswer = { _, _ -> AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.HTTP) },
            answer = { FetchResult(LyricsState.Missing) },
        )
        missing.tick(300_600)
        assertEquals(LyricsState.Missing, missing.repo.state.value)
        assertEquals(LyricsState.Missing, missing.cache.get(request().query))
        assertEquals(1, missing.appleCalls.size)
        assertEquals(1, missing.amllCalls.size)
        assertEquals(1, missing.calls.size)
    }

    @Test fun logsOnlyFixedAppleDiagnostics() = runTest {
        DiagLog.clearForTest()
        val f = Fixture(this, appleAnswer = { _, _ -> delay(25); appleSuccess() })
        f.tick(625)
        assertTrue(DiagLog.dump().any {
            it.contains("apple result accepted=true reason=OK cache=miss ms=25 lines=1 words=2")
        })
        assertTrue(DiagLog.dump().any { it.contains("amll result Synced source=APPLE") })
        f.toggle()
        assertTrue(DiagLog.dump().any { it.contains("accepted=true reason=OK cache=hit ms=0 lines=1 words=2") })
        val failure = Fixture(this, appleAnswer = { _, _ -> error("PRIVATE-SECRET-TOKEN https://private.test/body <tt>secret</tt>") })
        failure.tick(600)
        assertTrue(DiagLog.dump().any {
            it.contains("apple result accepted=false reason=NETWORK cache=miss ms=0 lines=0 words=0")
        })
        failure.toggle()
        failure.tick(600)
        assertTrue(DiagLog.dump().any { it.contains("accepted=false reason=NETWORK cache=hit") })
        assertTrue(DiagLog.dump().any { it.contains("amll result Synced source=LRCLIB") })
        val logs = DiagLog.dump().joinToString("\n")
        for (secret in listOf("PRIVATE-", "private.test", "<tt>", "IllegalStateException", "secret</tt>")) {
            assertFalse(secret, logs.contains(secret))
        }
    }
}
