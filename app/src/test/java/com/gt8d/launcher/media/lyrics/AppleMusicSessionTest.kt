package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.diag.DiagLog
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppleMusicSessionTest {
    @Test fun startsUnconfiguredWithoutRequests() = runTest {
        val f = Fixture()
        assertEquals(AppleMusicConnection(), f.session.state.value)
        assertSame(f.session.state, f.session.state)
        result(f.session.fetch(request(), 0), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        result(f.session.fetch(request(), 1), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        assertTrue(f.connectCalls.isEmpty())
        assertTrue(f.fetchCalls.isEmpty())

        f.session.connect(TOKEN)
        val fresh = Fixture()
        assertEquals(AppleMusicConnection(), fresh.session.state.value)
        result(fresh.session.fetch(request(), f.session.state.value.generation),
            LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        assertTrue(fresh.connectCalls.isEmpty())
        assertTrue(fresh.fetchCalls.isEmpty())
        connection(f, AppleMusicStatus.CONNECTED, 1, "gb")
    }

    @Test fun restoreCooldownUsesMonotonicClockAcrossWallClockChanges() = runTest {
        var wallTime = 10_000_000L
        var elapsed = 10_000L
        var reads = 0
        var connects = 0
        var saved: StoredAppleCredential? = null
        val store = object : AppleCredentialStore {
            override fun read(): AppleCredentialReadResult {
                reads++
                return AppleCredentialReadResult.Ready(
                    StoredAppleCredential(TOKEN, "gb", AppleCredentialSource.MANUAL, wallTime),
                )
            }
            override fun write(credential: StoredAppleCredential): Boolean { saved = credential; return true }
            override fun clear(): Boolean { saved = null; return true }
        }
        val session = AppleMusicSession(
            connectRequest = {
                connects++
                if (connects == 1) AppleConnectResult.Unavailable(AppleLyricsReason.WAIT, 60)
                else AppleConnectResult.Ready("gb")
            },
            fetchRequest = { _, _, _ -> error("unexpected lyrics request") },
            credentialStore = store, persistenceScope = this, io = StandardTestDispatcher(testScheduler),
            clock = { wallTime }, monotonicClock = { elapsed },
        )
        checkNotNull(session.restoreIfNeeded()).join()
        assertEquals(AppleMusicStatus.NEEDS_ATTENTION, session.state.value.status)
        assertEquals(60L, session.state.value.retryAfterSec)

        wallTime += 3_600_000L
        assertNull(session.restoreIfNeeded())
        assertEquals(60L, session.state.value.retryAfterSec)
        assertEquals(1, connects)

        wallTime = 6_400_000L
        elapsed = 69_999L
        assertNull(session.restoreIfNeeded())
        assertEquals(1L, session.state.value.retryAfterSec)
        assertEquals(1, connects)
        elapsed = 70_000L
        checkNotNull(session.restoreIfNeeded()).join()
        assertEquals(AppleMusicStatus.CONNECTED, session.state.value.status)
        assertNull(session.state.value.retryAfterSec)
        assertEquals(2, connects)
        assertEquals(1, reads)

        session.connectFromWeb(TOKEN, 20_000_000L)
        assertEquals(3, connects)
        assertEquals(wallTime, checkNotNull(saved).verifiedAtEpochMillis)
        assertEquals(20_000_000L, checkNotNull(saved).cookieExpiresAtEpochMillis)
    }

    @Test fun validatesAndConnectsWithoutFetchingLyrics() = runTest {
        val f = Fixture()
        f.session.connect(TOKEN)
        var generation = 1L
        val controls = ((0..31) + (127..159)).map { it.toChar() }
        val invalid = listOf("", "   ", "\u3000", "x".repeat(TOKEN_LIMIT + 1),
            "é".repeat(TOKEN_LIMIT / 2) + "x", "😀".repeat(TOKEN_LIMIT / 4) + "x") +
            controls.flatMap { listOf("a${it}b", "${it}a", "a${it}") }
        for (input in invalid) {
            f.session.connect(input)
            connection(f, AppleMusicStatus.NEEDS_ATTENTION, ++generation, reason = AppleLyricsReason.INVALID)
            result(f.session.fetch(request(), generation), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        }
        assertEquals(listOf(TOKEN), f.connectCalls)
        assertTrue(f.fetchCalls.isEmpty())

        val valid = listOf("  $TOKEN \u3000", "x".repeat(TOKEN_LIMIT - 1), "x".repeat(TOKEN_LIMIT),
            "é".repeat(TOKEN_LIMIT / 2), "😀".repeat(TOKEN_LIMIT / 4))
        for (storefront in listOf("gb", "jp", "us")) {
            f.connectAnswer = {
                connection(f, AppleMusicStatus.CHECKING, generation)
                result(f.session.fetch(request(), generation), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
                AppleConnectResult.Ready(storefront)
            }
            for (input in valid) {
                ++generation
                f.session.connect(input)
                connection(f, AppleMusicStatus.CONNECTED, generation, storefront)
                assertEquals(input.trim(), f.connectCalls.last())
            }
        }
        for (storefront in listOf("", "g", "gbr", "GB", "g1", " gb", "gb ", "gb\n", "日本", TOKEN)) {
            f.connectAnswer = { AppleConnectResult.Ready(storefront) }
            f.session.connect(TOKEN)
            connection(f, AppleMusicStatus.NEEDS_ATTENTION, ++generation, reason = AppleLyricsReason.INVALID)
            result(f.session.fetch(request(), generation), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        }
        assertEquals(1 + valid.size * 3 + 10, f.connectCalls.size)
        assertTrue(f.fetchCalls.isEmpty())
    }

    @Test fun fetchUsesOnlyCurrentPrivateCredentials() = runTest {
        val f = Fixture()
        val firstRequest = request("123")
        f.session.connect(" $TOKEN ")
        val firstGeneration = f.session.state.value.generation
        assertSame(f.success, f.session.fetch(firstRequest, firstGeneration))
        assertEquals(listOf(FetchCall(firstRequest, "gb", TOKEN)), f.fetchCalls)
        assertSame(firstRequest, f.fetchCalls.single().request)

        f.connectAnswer = { AppleConnectResult.Ready("jp") }
        f.session.connect(NEW_TOKEN)
        val generation = f.session.state.value.generation
        result(f.session.fetch(firstRequest, firstGeneration), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        result(f.session.fetch(firstRequest, generation + 1), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        assertEquals(1, f.fetchCalls.size)
        val secondRequest = request("456")
        assertSame(f.success, f.session.fetch(secondRequest, generation))
        assertEquals(FetchCall(secondRequest, "jp", NEW_TOKEN), f.fetchCalls.last())
        assertEquals(2, f.fetchCalls.size)
        connection(f, AppleMusicStatus.CONNECTED, 2, "jp")
        assertSafe(f.session.state.value.toString())
        assertSafe(f.session.state.toString())
        assertSafe(f.session.toString())
    }

    @Test fun replacementCancelsOldOperationsAndRejectsLateResults() = runTest {
        for (oldConnect in listOf(true, false)) {
            for (outcome in LateOutcome.entries) {
                val f = Fixture()
                val release = CompletableDeferred<Unit>()
                lateinit var oldOperation: Job
                if (oldConnect) {
                    f.connectAnswer = {
                        oldOperation = currentCoroutineContext().job
                        withContext(NonCancellable) { release.await() }
                        lateConnect(outcome)
                    }
                } else {
                    f.session.connect(TOKEN)
                    f.fetchAnswer = { _, _, _ ->
                        oldOperation = currentCoroutineContext().job
                        withContext(NonCancellable) { release.await() }
                        lateFetch(outcome, f.success)
                    }
                }
                val old = async {
                    if (oldConnect) f.session.connect(TOKEN)
                    else f.session.fetch(request(), 1)
                }
                runCurrent()
                assertTrue(oldOperation.isActive)
                assertNotSame(currentCoroutineContext().job, oldOperation)
                f.connectAnswer = { AppleConnectResult.Ready("jp") }
                f.session.connect(NEW_TOKEN)
                connection(f, AppleMusicStatus.CONNECTED, 2, "jp")
                assertTrue(oldOperation.isCancelled)
                assertFalse(old.isCompleted)
                assertTrue(currentCoroutineContext().job.isActive)

                release.complete(Unit)
                val cancelled = assertCancelled(old)
                assertEquals(CANCEL_MESSAGE, cancelled.message)
                assertNull(cancelled.cause)
                assertEquals(0, cancelled.suppressed.size)
                connection(f, AppleMusicStatus.CONNECTED, 2, "jp")
                f.fetchAnswer = { _, _, _ -> f.success }
                assertSame(f.success, f.session.fetch(request("789"), 2))
                assertEquals(FetchCall(request("789"), "jp", NEW_TOKEN), f.fetchCalls.last())
                assertEquals(2, f.connectCalls.size)
                assertEquals(if (oldConnect) 1 else 2, f.fetchCalls.size)
            }
        }
    }

    @Test fun disconnectCancelsAndClearsCredentials() = runTest {
        val connected = Fixture()
        connected.session.connect(TOKEN)
        connected.session.disconnect()
        connection(connected, AppleMusicStatus.UNCONFIGURED, 2)
        connected.session.disconnect()
        connection(connected, AppleMusicStatus.UNCONFIGURED, 3)
        result(connected.session.fetch(request(), 1), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        result(connected.session.fetch(request(), 3), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        assertTrue(connected.fetchCalls.isEmpty())

        for (duringConnect in listOf(true, false)) {
            for (nonCooperative in listOf(true, false)) {
                val f = Fixture()
                val release = CompletableDeferred<Unit>()
                lateinit var operation: Job
                suspend fun pending() {
                    operation = currentCoroutineContext().job
                    if (nonCooperative) withContext(NonCancellable) { release.await() }
                    else awaitCancellation()
                }
                if (duringConnect) {
                    f.connectAnswer = { pending(); AppleConnectResult.Ready("gb") }
                } else {
                    f.session.connect(TOKEN)
                    f.fetchAnswer = { _, _, _ -> pending(); f.success }
                }
                val old = async {
                    if (duringConnect) f.session.connect(TOKEN) else f.session.fetch(request(), 1)
                }
                runCurrent()
                f.session.disconnect()
                // No scheduler advance is needed for clearing the public state or cancelling the child.
                connection(f, AppleMusicStatus.UNCONFIGURED, 2)
                assertTrue(operation.isCancelled)
                assertTrue(currentCoroutineContext().job.isActive)
                result(f.session.fetch(request(), 2), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
                release.complete(Unit)
                assertEquals(CANCEL_MESSAGE, assertCancelled(old).message)
                connection(f, AppleMusicStatus.UNCONFIGURED, 2)
                assertEquals(1, f.connectCalls.size)
                assertEquals(if (duringConnect) 0 else 1, f.fetchCalls.size)
                assertEquals(7, async { 7 }.await())
                f.connectAnswer = { AppleConnectResult.Ready("us") }
                f.fetchAnswer = { _, _, _ -> f.success }
                f.session.connect(NEW_TOKEN)
                assertSame(f.success, f.session.fetch(request(), 3))
                assertEquals(FetchCall(request(), "us", NEW_TOKEN), f.fetchCalls.last())
            }
        }
    }

    @Test fun authenticationFailureRequiresManualReconnect() = runTest {
        val f = Fixture()
        f.session.connect(TOKEN)
        val release = CompletableDeferred<Unit>()
        val peers = mutableListOf<Job>()
        val auth = AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.AUTH_REQUIRED)
        f.fetchAnswer = { request, _, _ ->
            if (request.identity.appleMusicId == "401") auth else {
                peers += currentCoroutineContext().job
                if (request.identity.appleMusicId == "1") awaitCancellation()
                withContext(NonCancellable) { release.await() }
                f.success
            }
        }
        val first = async { f.session.fetch(request("1"), 1) }
        val second = async { f.session.fetch(request("2"), 1) }
        runCurrent()
        assertEquals(2, peers.size)
        assertSame(auth, f.session.fetch(request("401"), 1))
        connection(f, AppleMusicStatus.NEEDS_ATTENTION, 2, reason = AppleLyricsReason.AUTH_REQUIRED)
        assertTrue(peers.all { it.isCancelled })
        assertTrue(currentCoroutineContext().job.isActive)
        result(f.session.fetch(request(), 1), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        result(f.session.fetch(request(), 2), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        assertEquals(1, f.connectCalls.size)
        assertEquals(3, f.fetchCalls.size)
        assertCancelled(first)

        f.connectAnswer = { AppleConnectResult.Ready("jp") }
        f.session.connect(NEW_TOKEN)
        connection(f, AppleMusicStatus.CONNECTED, 3, "jp")
        release.complete(Unit)
        assertCancelled(second)
        connection(f, AppleMusicStatus.CONNECTED, 3, "jp")
        f.fetchAnswer = { _, _, _ -> f.success }
        assertSame(f.success, f.session.fetch(request(), 3))
        assertEquals(FetchCall(request(), "jp", NEW_TOKEN), f.fetchCalls.last())
        assertEquals(listOf(TOKEN, NEW_TOKEN), f.connectCalls)
        assertEquals(4, f.fetchCalls.size)
    }

    @Test fun transientFailuresPreserveConnection() = runTest {
        val f = Fixture()
        f.session.connect(TOKEN)
        val connected = f.session.state.value
        val failures = listOf(
            AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.HTTP),
            AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.WAIT, 67),
            AppleLyricsResult(LyricsState.Offline, AppleLyricsReason.NETWORK),
            AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.INVALID),
            AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.TIMEOUT),
            AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND),
            AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.UNSUPPORTED),
            AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE),
            AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.TOKEN_UNAVAILABLE),
        )
        for (failure in failures) {
            f.fetchAnswer = { _, _, _ -> failure }
            assertSame(failure, f.session.fetch(request(), 1))
            assertEquals(connected, f.session.state.value)
        }
        for (error in listOf(IOException(DETAIL), IllegalArgumentException(DETAIL))) {
            f.fetchAnswer = { _, _, _ -> throw error }
            val actual = f.session.fetch(request(), 1)
            if (error is IOException) result(actual, LyricsState.Offline, AppleLyricsReason.NETWORK)
            else result(actual, LyricsState.Failed, AppleLyricsReason.INVALID)
            assertEquals(connected, f.session.state.value)
        }
        f.fetchAnswer = { _, _, _ -> f.success }
        assertSame(f.success, f.session.fetch(request(), 1))
        assertEquals(1, f.connectCalls.size)
        assertEquals(failures.size + 3, f.fetchCalls.size)
        assertTrue(f.fetchCalls.all { it.token == TOKEN && it.storefront == "gb" })
    }

    @Test fun cancellationAndExceptionsDoNotPublishStaleState() = runTest {
        for (observed in listOf(AppleMusicStatus.CHECKING, AppleMusicStatus.CONNECTED)) {
            val f = Fixture()
            lateinit var pending: Deferred<Unit>
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.session.state.collect {
                    if (it.status == observed) {
                        if (observed == AppleMusicStatus.CHECKING) f.session.disconnect()
                        else pending.cancel()
                    }
                }
            }
            pending = async { f.session.connect(TOKEN) }
            assertCancelled(pending)
            connection(f, AppleMusicStatus.UNCONFIGURED, 2)
            assertEquals(if (observed == AppleMusicStatus.CHECKING) 0 else 1, f.connectCalls.size)
            result(f.session.fetch(request(), 2), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
            assertTrue(f.fetchCalls.isEmpty())
            observer.cancel()
        }

        for (duringConnect in listOf(true, false)) {
            for (cancelInside in listOf(true, false)) {
                for (throwsAfter in listOf(true, false)) {
                    val f = Fixture()
                    suspend fun cancelledDependency() {
                        if (cancelInside) currentCoroutineContext().cancel() else {
                            try {
                                awaitCancellation()
                            } catch (_: CancellationException) {
                                // Simulate a dependency that hides cancellation and returns or throws IO.
                            }
                        }
                        if (throwsAfter) throw IOException(DETAIL)
                    }
                    if (duringConnect) f.connectAnswer = { cancelledDependency(); AppleConnectResult.Ready("gb") }
                    else {
                        f.session.connect(TOKEN)
                        f.fetchAnswer = { _, _, _ -> cancelledDependency(); f.success }
                    }
                    val pending = async {
                        if (duringConnect) f.session.connect(TOKEN) else f.session.fetch(request(), 1)
                    }
                    runCurrent()
                    if (!cancelInside) pending.cancel()
                    assertCancelled(pending)
                    if (duringConnect) connection(f, AppleMusicStatus.UNCONFIGURED, 2)
                    else connection(f, AppleMusicStatus.CONNECTED, 1, "gb")
                    assertTrue(currentCoroutineContext().job.isActive)
                }
            }
        }

        val f = Fixture()
        var generation = 0L
        for (error in listOf(IOException(DETAIL), IllegalStateException(DETAIL))) {
            f.connectAnswer = { throw error }
            f.session.connect(TOKEN)
            connection(f, AppleMusicStatus.NEEDS_ATTENTION, ++generation,
                reason = if (error is IOException) AppleLyricsReason.NETWORK else AppleLyricsReason.INVALID)
            result(f.session.fetch(request(), generation), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        }
        for (reason in listOf(AppleLyricsReason.AUTH_REQUIRED, AppleLyricsReason.HTTP, AppleLyricsReason.WAIT,
            AppleLyricsReason.TIMEOUT, AppleLyricsReason.TOKEN_UNAVAILABLE)) {
            f.connectAnswer = { AppleConnectResult.Unavailable(reason, 73) }
            f.session.connect(TOKEN)
            connection(f, AppleMusicStatus.NEEDS_ATTENTION, ++generation, reason = reason, retry = 73)
            result(f.session.fetch(request(), generation), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        }
        assertTrue(f.fetchCalls.isEmpty())

        // Even a dependency's late finally/exception after an outer cancellation cannot clear a new session.
        val late = Fixture()
        val release = CompletableDeferred<Unit>()
        var finished = false
        late.connectAnswer = {
            try {
                withContext(NonCancellable) { release.await() }
                throw IOException(DETAIL)
            } finally {
                finished = true
            }
        }
        val old = async { late.session.connect(TOKEN) }
        runCurrent()
        old.cancel()
        late.connectAnswer = { AppleConnectResult.Ready("us") }
        late.session.connect(NEW_TOKEN)
        assertFalse(finished)
        connection(late, AppleMusicStatus.CONNECTED, 2, "us")
        release.complete(Unit)
        assertCancelled(old)
        assertTrue(finished)
        connection(late, AppleMusicStatus.CONNECTED, 2, "us")
        assertSame(late.success, late.session.fetch(request(), 2))
        assertEquals(FetchCall(request(), "us", NEW_TOKEN), late.fetchCalls.single())
    }

    @Test fun doesNotExposeCredentialsOrLyrics() = runTest {
        DiagLog.clearForTest()
        ShadowLog.clear()
        val f = Fixture()
        val outputs = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.session.state.collect { outputs += it.toString() }
        }
        f.session.connect(TOKEN)
        val success = f.session.fetch(request(), 1)
        assertEquals(LYRICS, (success.state as LyricsState.Synced).lines.single().text)
        outputs += success.toString()
        outputs += f.session.toString()
        outputs += f.session.state.toString()
        for (error in listOf(IOException("$TOKEN $LYRICS $DETAIL", IllegalStateException(DETAIL)),
            IllegalArgumentException("$NEW_TOKEN $LYRICS $DETAIL"))) {
            f.fetchAnswer = { _, _, _ -> throw error }
            outputs += f.session.fetch(request(), f.session.state.value.generation).toString()
            f.connectAnswer = { throw error }
            f.session.connect(TOKEN)
            f.connectAnswer = { AppleConnectResult.Ready("gb") }
            f.session.connect(NEW_TOKEN)
        }
        f.connectAnswer = { AppleConnectResult.Ready("$TOKEN $LYRICS $DETAIL") }
        f.session.connect(TOKEN)
        connection(f, AppleMusicStatus.NEEDS_ATTENTION, 6, reason = AppleLyricsReason.INVALID)

        for (duringConnect in listOf(true, false)) {
            for (cancelJob in listOf(true, false)) {
                val secretCancellation = CancellationException("$TOKEN $LYRICS $DETAIL").apply {
                    initCause(IOException(DETAIL))
                }
                suspend fun cancelDependency(): Nothing {
                    if (cancelJob) currentCoroutineContext().cancel(secretCancellation)
                    throw secretCancellation
                }
                if (duringConnect) f.connectAnswer = { cancelDependency() }
                else {
                    f.connectAnswer = { AppleConnectResult.Ready("gb") }
                    f.session.connect(TOKEN)
                    f.fetchAnswer = { _, _, _ -> cancelDependency() }
                }
                val pending = async {
                    try {
                        if (duringConnect) f.session.connect(TOKEN)
                        else f.session.fetch(request(), f.session.state.value.generation)
                        fail("cancellation must propagate")
                    } catch (error: CancellationException) {
                        assertEquals(CANCEL_MESSAGE, error.message)
                        assertNull(error.cause)
                        assertEquals(0, error.suppressed.size)
                        outputs += error.stackTraceToString()
                        throw error
                    }
                }
                val error = assertCancelled(pending)
                assertEquals(CANCEL_MESSAGE, error.message)
                assertNull(error.cause)
                outputs += error.stackTraceToString()
            }
        }
        f.session.disconnect()
        outputs += f.session.toString()
        for (output in outputs + DiagLog.dump() + ShadowLog.getLogs().map { it.msg }) assertSafe(output)
        assertTrue(outputs.any { it.contains("CHECKING") })
        assertTrue(outputs.any { it.contains("CONNECTED") })
        assertTrue(outputs.any { it.contains("NEEDS_ATTENTION") })
        assertEquals(emptyList<String>(), DiagLog.dump())
    }

    private class Fixture {
        val connectCalls = mutableListOf<String>()
        val fetchCalls = mutableListOf<FetchCall>()
        val success = AppleLyricsResult(
            LyricsState.Synced(listOf(LyricLine(0, LYRICS, listOf(LyricWord(0, 0))))), AppleLyricsReason.OK,
        )
        var connectAnswer: suspend (String) -> AppleConnectResult = { AppleConnectResult.Ready("gb") }
        var fetchAnswer: suspend (LyricsRequest, String, String) -> AppleLyricsResult = { _, _, _ -> success }
        val session = AppleMusicSession(
            connectRequest = { token -> connectCalls += token; connectAnswer(token) },
            fetchRequest = { request, storefront, token ->
                fetchCalls += FetchCall(request, storefront, token)
                fetchAnswer(request, storefront, token)
            },
        )
    }

    private data class FetchCall(val request: LyricsRequest, val storefront: String, val token: String)
    private enum class LateOutcome { SUCCESS, AUTH_REQUIRED, IO_EXCEPTION, OTHER_EXCEPTION }

    private companion object {
        const val TOKEN = "SYNTHETIC_SESSION_PRIVATE_TOKEN"
        const val NEW_TOKEN = "SYNTHETIC_SESSION_REPLACEMENT_TOKEN"
        const val LYRICS = "SYNTHETIC_SESSION_LYRIC_CONTENT"
        const val DETAIL = "SYNTHETIC_SESSION_EXCEPTION_DETAIL"
        const val TOKEN_LIMIT = 16 * 1024
        const val CANCEL_MESSAGE = "Apple Music session operation cancelled"

        fun request(id: String = "123") = LyricsRequest(
            LyricsQuery("Synthetic title", "Synthetic artist", null, 60), LyricsIdentity(appleMusicId = id),
        )

        fun connection(f: Fixture, status: AppleMusicStatus, generation: Long, storefront: String? = null,
            reason: AppleLyricsReason? = null, retry: Long? = null) {
            assertEquals(AppleMusicConnection(status, generation, storefront, reason, retry), f.session.state.value)
        }

        fun result(actual: AppleLyricsResult, state: LyricsState, reason: AppleLyricsReason, retry: Long? = null) {
            assertEquals(state, actual.state)
            assertEquals(reason, actual.reason)
            assertEquals(retry, actual.retryAfterSec)
        }

        fun lateConnect(outcome: LateOutcome): AppleConnectResult = when (outcome) {
            LateOutcome.SUCCESS -> AppleConnectResult.Ready("gb")
            LateOutcome.AUTH_REQUIRED -> AppleConnectResult.Unavailable(AppleLyricsReason.AUTH_REQUIRED)
            LateOutcome.IO_EXCEPTION -> throw IOException(DETAIL)
            LateOutcome.OTHER_EXCEPTION -> throw IllegalArgumentException(DETAIL)
        }

        fun lateFetch(outcome: LateOutcome, success: AppleLyricsResult): AppleLyricsResult = when (outcome) {
            LateOutcome.SUCCESS -> success
            LateOutcome.AUTH_REQUIRED -> AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.AUTH_REQUIRED)
            LateOutcome.IO_EXCEPTION -> throw IOException(DETAIL)
            LateOutcome.OTHER_EXCEPTION -> throw IllegalArgumentException(DETAIL)
        }

        fun assertSafe(output: String) {
            for (secret in listOf(TOKEN, NEW_TOKEN, LYRICS, DETAIL)) assertFalse(output.contains(secret))
        }

        suspend fun assertCancelled(deferred: Deferred<*>): CancellationException {
            try {
                deferred.await()
            } catch (error: CancellationException) {
                assertTrue(deferred.isCancelled)
                return error
            }
            throw AssertionError("cancellation was converted into a result")
        }
    }
}
