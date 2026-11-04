package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.diag.DiagLog
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
class AppleMusicRetentionTest {
    @Test fun persistsOnlyValidatedCurrentCredentials() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        val validated = CompletableDeferred<Unit>()
        f.connectAnswer = {
            assertEquals(0, f.store.writes.get())
            assertNull(f.store.value)
            validated.await()
            AppleConnectResult.Ready("jp")
        }
        f.store.onWrite = {
            assertRetention(f.session, AppleMusicRetentionStatus.SAVING)
            assertEquals(AppleMusicStatus.CONNECTED, f.session.state.value.status)
            f.store.value = it
            true
        }
        val pending = async { f.session.connect("  $TOKEN \u3000") }
        runCurrent()
        assertEquals(AppleMusicStatus.CHECKING, f.session.state.value.status)
        assertEquals(0, f.store.writes.get())
        validated.complete(Unit)
        pending.await()
        assertCredential(f.store.value, TOKEN, "jp", AppleCredentialSource.MANUAL, 1_000L, null)
        assertRetention(f.session, AppleMusicRetentionStatus.SAVED)
        assertEquals(listOf(TOKEN), f.connectCalls)

        f.connectAnswer = { AppleConnectResult.Ready("us") }
        f.now = -9L
        f.session.connectFromWeb(NEW_TOKEN, 999L)
        assertCredential(f.store.value, NEW_TOKEN, "us", AppleCredentialSource.WEB, 0L, 999L)
        f.session.connectFromWeb(TOKEN, -1L)
        assertCredential(f.store.value, TOKEN, "us", AppleCredentialSource.WEB, 0L, null)
        f.session.connectFromWeb(TOKEN)
        assertNull(f.store.value?.cookieExpiresAtEpochMillis)

        val writes = f.store.writes.get()
        for (input in listOf("", "\n$TOKEN", "x".repeat(16 * 1024 + 1))) {
            val calls = f.connectCalls.size
            f.session.connect(input)
            assertEquals(calls, f.connectCalls.size)
            assertNull(f.store.value)
            assertEquals(AppleLyricsReason.INVALID, f.session.state.value.reason)
        }
        for (answer in listOf(AppleConnectResult.Ready(SECRET),
            AppleConnectResult.Unavailable(AppleLyricsReason.NETWORK))) {
            f.connectAnswer = { answer }
            f.session.connect(TOKEN)
            assertNull(f.store.value)
            assertEquals(AppleMusicStatus.NEEDS_ATTENTION, f.session.state.value.status)
        }
        assertEquals(writes, f.store.writes.get())
        assertNull(f.session.restoreIfNeeded())

        val memory = AppleMusicSession({ AppleConnectResult.Ready("gb") }, { _, _, _ -> success() })
        memory.connectFromWeb(TOKEN, 5L)
        assertEquals(AppleMusicConnection(AppleMusicStatus.CONNECTED, 1L, "gb"), memory.state.value)
        assertRetention(memory, AppleMusicRetentionStatus.NONE)
        assertNull(memory.restoreIfNeeded())
        assertNull(memory.disconnect())
    }

    @Test fun restoresOnceAndRevalidatesBeforeConnecting() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        assertEquals(0, f.store.totalCalls())
        try {
            AppleMusicSession({ AppleConnectResult.Ready("gb") }, { _, _, _ -> success() }, f.store)
            fail("a persistent session must require an application scope")
        } catch (error: IllegalArgumentException) {
            assertSafe(error.stackTraceToString())
        }
        assertEquals(0, f.store.totalCalls())
        f.session.connect(TOKEN)
        val original = f.store.value
        val fresh = f.newSession()
        assertEquals(0, f.store.reads.get())
        val validated = CompletableDeferred<Unit>()
        f.connectAnswer = { validated.await(); AppleConnectResult.Ready("jp") }
        val restore = checkNotNull(fresh.restoreIfNeeded())
        assertSame(restore, fresh.restoreIfNeeded())
        assertRetention(fresh, AppleMusicRetentionStatus.READING)
        runCurrent()
        assertSame(restore, fresh.restoreIfNeeded())
        assertEquals(1, f.store.reads.get())
        assertEquals(2, f.connectCalls.size)
        assertRetention(fresh, AppleMusicRetentionStatus.SAVED)
        assertEquals(AppleMusicStatus.CHECKING, fresh.state.value.status)
        assertEquals(AppleLyricsReason.NOT_APPLICABLE, fresh.fetch(request(), fresh.state.value.generation).reason)
        assertTrue(f.fetchCalls.isEmpty())
        validated.complete(Unit)
        restore.join()
        assertEquals("jp", fresh.state.value.storefront)
        assertEquals(AppleMusicStatus.CONNECTED, fresh.state.value.status)
        assertNull(fresh.restoreIfNeeded())
        assertSame(original, f.store.value)
        assertEquals(1, f.store.writes.get())
        fresh.fetch(request(), fresh.state.value.generation)
        assertEquals(listOf("jp" to TOKEN), f.fetchCalls)

        val empty = Fixture(this, StandardTestDispatcher(testScheduler))
        checkNotNull(empty.session.restoreIfNeeded()).join()
        assertEquals(AppleMusicStatus.UNCONFIGURED, empty.session.state.value.status)
        assertRetention(empty.session, AppleMusicRetentionStatus.NONE)
        assertNull(empty.session.restoreIfNeeded())
        assertEquals(1, empty.store.reads.get())
        assertTrue(empty.connectCalls.isEmpty())
    }

    @Test fun transientRestoreFailuresKeepSavedCredentialsAndRespectCooldown() = runTest {
        val reasons = listOf(AppleLyricsReason.NETWORK, AppleLyricsReason.TIMEOUT, AppleLyricsReason.WAIT,
            AppleLyricsReason.TOKEN_UNAVAILABLE, AppleLyricsReason.HTTP, AppleLyricsReason.INVALID)
        for (reason in reasons) {
            for (retry in listOf(null, -1L, 1L, 123L, Long.MAX_VALUE)) {
                val f = Fixture(this, StandardTestDispatcher(testScheduler))
                val saved = credential()
                f.store.value = saved
                f.connectAnswer = { AppleConnectResult.Unavailable(reason, retry) }
                checkNotNull(f.session.restoreIfNeeded()).join()
                val deadline = if (retry == Long.MAX_VALUE) Long.MAX_VALUE
                    else 1_000L + maxOf(60L, retry ?: 0L) * 1_000L
                val remaining = (deadline - 1_000L) / 1_000L + if ((deadline - 1_000L) % 1_000L == 0L) 0L else 1L
                assertEquals(remaining, f.session.state.value.retryAfterSec)
                assertEquals(reason, f.session.state.value.reason)
                assertEquals(AppleMusicStatus.NEEDS_ATTENTION, f.session.state.value.status)
                assertRetention(f.session, AppleMusicRetentionStatus.SAVED)
                repeat(3) { assertNull(f.session.restoreIfNeeded()) }
                f.now = deadline - 1L
                assertNull(f.session.restoreIfNeeded())
                assertEquals(1L, f.session.state.value.retryAfterSec)
                assertEquals(1, f.connectCalls.size)
                assertSame(saved, f.store.value)
                assertEquals(0, f.store.clears.get())
                assertTrue(f.store.keyPresent)
                f.now = deadline
                f.connectAnswer = { AppleConnectResult.Ready("jp") }
                checkNotNull(f.session.restoreIfNeeded()).join()
                assertEquals(2, f.connectCalls.size)
                assertEquals(1, f.store.reads.get())
                assertEquals(0, f.store.writes.get())
                assertEquals(0, f.store.clears.get())
                assertEquals(AppleMusicStatus.CONNECTED, f.session.state.value.status)
            }
        }
        val edge = Fixture(this, StandardTestDispatcher(testScheduler))
        edge.store.value = credential()
        edge.now = Long.MAX_VALUE - 500L
        edge.connectAnswer = { AppleConnectResult.Unavailable(AppleLyricsReason.WAIT, Long.MAX_VALUE) }
        checkNotNull(edge.session.restoreIfNeeded()).join()
        assertEquals(1L, edge.session.state.value.retryAfterSec)
        assertNull(edge.session.restoreIfNeeded())
        assertEquals(1, edge.connectCalls.size)
    }

    @Test fun manualReplacementCannotResurrectPreviousAccount() = runTest {
        for (outcome in listOf("success", "failure", "cancel")) {
            val f = Fixture(this, StandardTestDispatcher(testScheduler))
            f.store.value = credential()
            val checking = CompletableDeferred<Unit>()
            f.connectAnswer = {
                assertNull(f.store.value)
                assertFalse(f.store.keyPresent)
                checking.complete(Unit)
                when (outcome) {
                    "success" -> AppleConnectResult.Ready("jp")
                    "failure" -> AppleConnectResult.Unavailable(AppleLyricsReason.AUTH_REQUIRED)
                    else -> awaitCancellation()
                }
            }
            val pending = async { f.session.connect(NEW_TOKEN) }
            checking.await()
            if (outcome == "cancel") { pending.cancel(); assertCancelled(pending) } else pending.await()
            assertEquals(if (outcome == "success") NEW_TOKEN else null, f.store.value?.token)
            assertNull(f.session.restoreIfNeeded())
            f.connectAnswer = { AppleConnectResult.Ready("jp") }
            val fresh = f.newSession()
            checkNotNull(fresh.restoreIfNeeded()).join()
            assertEquals(if (outcome == "success") AppleMusicStatus.CONNECTED else AppleMusicStatus.UNCONFIGURED,
                fresh.state.value.status)
            assertFalse(f.connectCalls.contains(TOKEN))
        }
        for (throws in listOf(false, true)) {
            val f = Fixture(this, StandardTestDispatcher(testScheduler))
            f.store.value = credential()
            f.store.onClear = { if (throws) throw IOException(SECRET) else false }
            f.session.connect(NEW_TOKEN)
            assertTrue(f.connectCalls.isEmpty())
            assertEquals(TOKEN, f.store.value?.token)
            assertEquals(AppleMusicStatus.NEEDS_ATTENTION, f.session.state.value.status)
            assertRetention(f.session, AppleMusicRetentionStatus.ERROR, AppleMusicRetentionProblem.CLEAR)
            assertNull(f.session.restoreIfNeeded())
        }
    }

    @Test fun lateRestoreCannotOverrideConnectOrDisconnect() = runTest {
        for (duringRead in listOf(true, false)) {
            for (replace in listOf(true, false)) {
                val f = Fixture(this, if (duringRead) Dispatchers.IO else StandardTestDispatcher(testScheduler))
                f.store.value = credential()
                val disk = BlockingOperation()
                val authentication = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                if (duringRead) f.store.onRead = {
                    val snapshot = checkNotNull(f.store.value)
                    disk.block()
                    AppleCredentialReadResult.Ready(snapshot)
                } else f.connectAnswer = {
                    entered.complete(Unit)
                    withContext(NonCancellable) { authentication.await() }
                    AppleConnectResult.Ready("gb")
                }
                val old = checkNotNull(f.session.restoreIfNeeded())
                runCurrent()
                try {
                    if (duringRead) disk.awaitEntered() else entered.await()
                    f.connectAnswer = { AppleConnectResult.Ready("jp") }
                    val replacement = if (replace) async { f.session.connect(NEW_TOKEN) } else null
                    val deletion = if (!replace) f.session.disconnect() else null
                    runCurrent()
                    assertEquals(if (replace) {
                        if (duringRead) AppleMusicStatus.CHECKING else AppleMusicStatus.CONNECTED
                    } else AppleMusicStatus.UNCONFIGURED, f.session.state.value.status)
                    assertTrue(old.isCancelled)
                    assertNull(f.session.restoreIfNeeded())
                    disk.release()
                    authentication.complete(Unit)
                    replacement?.await()
                    deletion?.join()
                    old.join()
                    assertEquals(if (replace) NEW_TOKEN else null, f.store.value?.token)
                    assertEquals(if (replace) "jp" else null, f.session.state.value.storefront)
                    assertRetention(f.session, if (replace) AppleMusicRetentionStatus.SAVED else AppleMusicRetentionStatus.NONE)
                    assertEquals(1, f.store.reads.get())
                } finally {
                    disk.release()
                    authentication.complete(Unit)
                }
            }
        }
    }

    @Test fun disconnectWinsAgainstInFlightSave() = runTest {
        val f = Fixture(this, Dispatchers.IO)
        val disk = BlockingOperation()
        f.store.onWrite = { disk.block(); f.store.value = it; true }
        val pending = async { f.session.connect(TOKEN) }
        runCurrent()
        try {
            disk.awaitEntered()
            assertRetention(f.session, AppleMusicRetentionStatus.SAVING)
            val deletion = checkNotNull(f.session.disconnect())
            assertEquals(AppleMusicStatus.UNCONFIGURED, f.session.state.value.status)
            assertRetention(f.session, AppleMusicRetentionStatus.CLEARING)
            assertFalse(deletion.isCompleted)
            assertEquals(AppleLyricsReason.NOT_APPLICABLE, f.session.fetch(request(), f.session.state.value.generation).reason)
            // Neither the memory transition nor a fetch needs the blocked disk gate.
            assertFalse(disk.released)
            disk.release()
            assertCancelled(pending)
            deletion.join()
            assertNull(f.store.value)
            assertFalse(f.store.keyPresent)
            assertRetention(f.session, AppleMusicRetentionStatus.NONE)
            assertNull(f.session.restoreIfNeeded())
            val fresh = f.newSession()
            checkNotNull(fresh.restoreIfNeeded()).join()
            assertEquals(AppleMusicStatus.UNCONFIGURED, fresh.state.value.status)
        } finally { disk.release() }
    }

    @Test fun cancelledSaveIsReclaimedWithoutDeletingNewerCredentials() = runTest {
        for (replace in listOf(false, true)) {
            val f = Fixture(this, Dispatchers.IO)
            val disk = BlockingOperation()
            f.store.onWrite = { if (it.token == TOKEN) disk.block(); f.store.value = it; true }
            val old = async { f.session.connect(TOKEN) }
            runCurrent()
            try {
                disk.awaitEntered()
                old.cancel()
                val newer = if (replace) async { f.session.connect(NEW_TOKEN) } else null
                runCurrent()
                disk.release()
                assertCancelled(old)
                newer?.await()
                assertEquals(if (replace) NEW_TOKEN else null, f.store.value?.token)
                assertRetention(f.session, if (replace) AppleMusicRetentionStatus.SAVED else AppleMusicRetentionStatus.NONE)
            } finally { disk.release() }
        }
        // Run the IO completion separately from the caller continuation to hit prompt cancellation.
        for (replace in listOf(false, true)) {
            val io = QueuedIo()
            val f = Fixture(this, io)
            val old = async { f.session.connect(TOKEN) }
            runCurrent() // old clear dispatched
            io.runAll()
            runCurrent() // auth finishes, save dispatched
            io.runAll() // file committed; caller has not resumed
            assertEquals(TOKEN, f.store.value?.token)
            assertRetention(f.session, AppleMusicRetentionStatus.SAVED)
            old.cancel()
            val newer = if (replace) async { f.session.connect(NEW_TOKEN) } else null
            repeat(5) { runCurrent(); io.runAll() }
            runCurrent()
            assertCancelled(old)
            newer?.await()
            assertEquals(if (replace) NEW_TOKEN else null, f.store.value?.token)
            assertRetention(f.session, if (replace) AppleMusicRetentionStatus.SAVED else AppleMusicRetentionStatus.NONE)
        }
    }

    @Test fun authFailureClearsOnlyCurrentSavedGeneration() = runTest {
        for (restore in listOf(true, false)) {
            val f = Fixture(this, StandardTestDispatcher(testScheduler))
            if (restore) {
                f.store.value = credential()
                f.connectAnswer = { AppleConnectResult.Unavailable(AppleLyricsReason.AUTH_REQUIRED) }
                checkNotNull(f.session.restoreIfNeeded()).join()
            } else {
                f.session.connect(TOKEN)
                f.fetchAnswer = { AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.AUTH_REQUIRED) }
                val ui = async { f.session.fetch(request(), f.session.state.value.generation) }
                assertEquals(AppleLyricsReason.AUTH_REQUIRED, ui.await().reason)
                ui.cancel()
                runCurrent()
            }
            assertEquals(AppleLyricsReason.AUTH_REQUIRED, f.session.state.value.reason)
            assertEquals(AppleMusicStatus.NEEDS_ATTENTION, f.session.state.value.status)
            assertNull(f.store.value)
            assertFalse(f.store.keyPresent)
            assertRetention(f.session, AppleMusicRetentionStatus.NONE)
            assertNull(f.session.restoreIfNeeded())
        }
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        f.session.connect(TOKEN)
        val initialClears = f.store.clears.get()
        for (reason in AppleLyricsReason.entries.filter { it != AppleLyricsReason.AUTH_REQUIRED }) {
            f.fetchAnswer = { AppleLyricsResult(LyricsState.Failed, reason) }
            f.session.fetch(request(), f.session.state.value.generation)
            assertEquals(initialClears, f.store.clears.get())
            assertEquals(TOKEN, f.store.value?.token)
        }
        val release = CompletableDeferred<Unit>()
        f.fetchAnswer = {
            withContext(NonCancellable) { release.await() }
            AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.AUTH_REQUIRED)
        }
        val old = async { f.session.fetch(request(), f.session.state.value.generation) }
        runCurrent()
        f.session.connect(NEW_TOKEN)
        val clears = f.store.clears.get()
        release.complete(Unit)
        assertCancelled(old)
        assertEquals(NEW_TOKEN, f.store.value?.token)
        assertEquals(clears, f.store.clears.get())
        assertRetention(f.session, AppleMusicRetentionStatus.SAVED)
    }

    @Test fun storageFailuresAreVisibleAndNeverReportedAsSaved() = runTest {
        for (throws in listOf(false, true)) {
            val read = Fixture(this, StandardTestDispatcher(testScheduler))
            read.store.onRead = {
                if (throws) throw IOException(SECRET) else AppleCredentialReadResult.Failed(AppleCredentialFailure.CRYPTO)
            }
            checkNotNull(read.session.restoreIfNeeded()).join()
            assertRetention(read.session, AppleMusicRetentionStatus.ERROR, AppleMusicRetentionProblem.READ)
            assertEquals(AppleMusicStatus.NEEDS_ATTENTION, read.session.state.value.status)
            assertNull(read.session.restoreIfNeeded())
            assertEquals(1, read.store.reads.get())
            assertEquals(0, read.store.writes.get())
            assertTrue(read.connectCalls.isEmpty())

            for (partial in listOf(false, true)) {
                for (cleanupFails in listOf(false, true)) {
                    val f = Fixture(this, StandardTestDispatcher(testScheduler))
                    f.store.onWrite = {
                        if (partial) f.store.value = it
                        if (throws) throw IOException(SECRET) else false
                    }
                    f.store.onClear = {
                        if (cleanupFails && f.store.writes.get() > 0) {
                            if (throws) throw IOException(SECRET) else false
                        } else { f.store.value = null; true }
                    }
                    f.session.connect(TOKEN)
                    assertEquals(AppleMusicStatus.CONNECTED, f.session.state.value.status)
                    assertEquals(AppleLyricsReason.OK, f.session.fetch(request(), f.session.state.value.generation).reason)
                    assertEquals(2, f.store.clears.get())
                    assertRetention(f.session,
                        if (cleanupFails) AppleMusicRetentionStatus.ERROR else AppleMusicRetentionStatus.SESSION_ONLY,
                        if (cleanupFails) AppleMusicRetentionProblem.CLEAR else AppleMusicRetentionProblem.SAVE)
                    if (!cleanupFails) { assertNull(f.store.value); assertFalse(f.store.keyPresent) }
                }
            }
            val clear = Fixture(this, StandardTestDispatcher(testScheduler))
            clear.session.connect(TOKEN)
            clear.store.onClear = { if (throws) throw IOException(SECRET) else false }
            val deletion = checkNotNull(clear.session.disconnect())
            assertRetention(clear.session, AppleMusicRetentionStatus.CLEARING)
            assertEquals(AppleMusicStatus.UNCONFIGURED, clear.session.state.value.status)
            deletion.join()
            assertRetention(clear.session, AppleMusicRetentionStatus.ERROR, AppleMusicRetentionProblem.CLEAR)
            assertNull(clear.session.restoreIfNeeded())
        }
    }

    @Test fun cancellationAndFailuresNeverExposeSecrets() = runTest {
        DiagLog.clearForTest()
        ShadowLog.clear()
        val outputs = mutableListOf<String>()
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        val states = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.session.state.collect { outputs += it.toString() }
        }
        val retention = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.session.retention.collect { outputs += it.toString() }
        }
        for (stage in listOf("network", "save")) {
            for (cancelJob in listOf(false, true)) {
                val failure = CancellationException(SECRET).apply { initCause(IOException(TOKEN)) }
                if (stage == "network") f.connectAnswer = {
                    if (cancelJob) currentCoroutineContext().cancel(failure)
                    throw failure
                } else {
                    var operation: Job? = null
                    f.connectAnswer = { operation = currentCoroutineContext().job; AppleConnectResult.Ready("gb") }
                    f.store.onWrite = {
                        f.store.value = it
                        if (cancelJob) { checkNotNull(operation).cancel(failure); true } else throw failure
                    }
                }
                val pending = async {
                    try { f.session.connect(TOKEN); fail("cancellation must propagate") }
                    catch (error: CancellationException) {
                        assertEquals(CANCEL_MESSAGE, error.message)
                        assertNull(error.cause)
                        assertTrue(error.suppressed.isEmpty())
                        outputs += error.stackTraceToString()
                        throw error
                    }
                }
                outputs += assertCancelled(pending).stackTraceToString()
                assertNull(f.store.value)
                assertEquals(AppleMusicStatus.UNCONFIGURED, f.session.state.value.status)
                assertRetention(f.session, AppleMusicRetentionStatus.NONE)
            }
        }
        for (error in listOf(IOException(SECRET), IllegalStateException(SECRET))) {
            f.connectAnswer = { throw error }
            f.session.connect(TOKEN)
            assertEquals(if (error is IOException) AppleLyricsReason.NETWORK else AppleLyricsReason.INVALID,
                f.session.state.value.reason)
            f.connectAnswer = { AppleConnectResult.Ready("gb") }
            f.store.onWrite = { f.store.value = it; throw error }
            f.session.connect(TOKEN)
            assertRetention(f.session, AppleMusicRetentionStatus.SESSION_ONLY, AppleMusicRetentionProblem.SAVE)
        }
        f.store.onClear = { throw IOException(SECRET) }
        checkNotNull(f.session.disconnect()).join()
        assertRetention(f.session, AppleMusicRetentionStatus.ERROR, AppleMusicRetentionProblem.CLEAR)
        outputs += listOf(f.session.state.toString(), f.session.retention.toString(), f.session.toString(), credential().toString())
        for (output in outputs + DiagLog.dump() + ShadowLog.getLogs().map { it.msg }) assertSafe(output)
        assertTrue(outputs.any { it.contains("SAVING") })
        assertTrue(outputs.any { it.contains("SESSION_ONLY") })
        assertTrue(outputs.any { it.contains("ERROR") })
        assertEquals(emptyList<String>(), DiagLog.dump())
        states.cancel()
        retention.cancel()
    }

    private class Fixture(private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
        val store = FakeStore()
        var now = 1_000L
        val connectCalls = mutableListOf<String>()
        val fetchCalls = mutableListOf<Pair<String, String>>()
        var connectAnswer: suspend (String) -> AppleConnectResult = { AppleConnectResult.Ready("gb") }
        var fetchAnswer: suspend () -> AppleLyricsResult = { success() }
        val session = newSession()
        fun newSession() = AppleMusicSession(
            connectRequest = { connectCalls += it; connectAnswer(it) },
            fetchRequest = { _, storefront, token -> fetchCalls += storefront to token; fetchAnswer() },
            credentialStore = store, persistenceScope = scope, io = io, clock = { now },
        )
    }

    private class FakeStore : AppleCredentialStore {
        @Volatile var value: StoredAppleCredential? = null
            set(value) { field = value; keyPresent = value != null }
        @Volatile var keyPresent = false
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val clears = AtomicInteger()
        var onRead: (() -> AppleCredentialReadResult)? = null
        var onWrite: ((StoredAppleCredential) -> Boolean)? = null
        var onClear: (() -> Boolean)? = null
        override fun read(): AppleCredentialReadResult {
            reads.incrementAndGet()
            return onRead?.invoke() ?: value?.let { AppleCredentialReadResult.Ready(it) } ?: AppleCredentialReadResult.Missing
        }
        override fun write(credential: StoredAppleCredential): Boolean {
            writes.incrementAndGet()
            return onWrite?.invoke(credential) ?: run { value = credential; true }
        }
        override fun clear(): Boolean {
            clears.incrementAndGet()
            return onClear?.invoke() ?: run { value = null; true }
        }
        fun totalCalls() = reads.get() + writes.get() + clears.get()
    }

    private class BlockingOperation {
        private val entered = CountDownLatch(1)
        private val finish = CountDownLatch(1)
        val released get() = finish.count == 0L
        fun block() {
            entered.countDown()
            assertTrue("disk operation timed out", finish.await(5, TimeUnit.SECONDS))
        }
        suspend fun awaitEntered() = withContext(Dispatchers.IO) {
            assertTrue("disk operation did not start", entered.await(5, TimeUnit.SECONDS))
        }
        fun release() { finish.countDown() }
    }

    private class QueuedIo : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runAll() {
            repeat(100) { (queue.poll() ?: return).run() }
            fail("unexpected IO dispatch loop")
        }
    }

    private companion object {
        const val TOKEN = "SYNTHETIC_RETENTION_OLD_TOKEN"
        const val NEW_TOKEN = "SYNTHETIC_RETENTION_NEW_TOKEN"
        const val SECRET = "SYNTHETIC_RETENTION_SECRET https://invalid.example/private"
        const val CANCEL_MESSAGE = "Apple Music session operation cancelled"
        fun credential() = StoredAppleCredential(TOKEN, "gb", AppleCredentialSource.WEB, 10L, 99L)
        fun success() = AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.OK)
        fun request() = LyricsRequest(LyricsQuery("Synthetic", "Synthetic", null, 60), LyricsIdentity(appleMusicId = "123"))
        fun assertRetention(session: AppleMusicSession, status: AppleMusicRetentionStatus, problem: AppleMusicRetentionProblem? = null) {
            assertEquals(AppleMusicRetentionState(status, problem), session.retention.value)
        }
        fun assertCredential(actual: StoredAppleCredential?, token: String, storefront: String, source: AppleCredentialSource,
            verified: Long, expiry: Long?) {
            assertNotNull(actual)
            assertEquals(token, actual?.token)
            assertEquals(storefront, actual?.storefront)
            assertEquals(source, actual?.source)
            assertEquals(verified, actual?.verifiedAtEpochMillis)
            assertEquals(expiry, actual?.cookieExpiresAtEpochMillis)
        }
        suspend fun assertCancelled(pending: Deferred<*>): CancellationException {
            try { pending.await() } catch (error: CancellationException) {
                assertTrue(pending.isCancelled)
                return error
            }
            throw AssertionError("cancellation must propagate")
        }
        fun assertSafe(output: String) {
            for (secret in listOf(TOKEN, NEW_TOKEN, SECRET, "https://invalid.example/private")) assertFalse(output.contains(secret))
        }
    }
}
