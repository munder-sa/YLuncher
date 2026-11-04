package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.media.lyrics.AppleConnectionTrace.Operation
import com.gt8d.launcher.media.lyrics.AppleConnectionTrace.Reason
import com.gt8d.launcher.media.lyrics.AppleConnectionTrace.Stage
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppleConnectionTraceTest {
    @Before fun clearLogs() {
        DiagLog.clearForTest()
        ShadowLog.clear()
    }

    @After fun resetLogs() = clearLogs()

    @Test fun fixedGrammarAndBoundedNumbers() {
        val lines = mutableListOf<String>()
        val trace = AppleConnectionTrace(Operation.CONNECT, { 0L }, { lines.add(it) })
        assertEquals(listOf("op=CONNECT stage=START elapsedMs=0"), lines)
        trace.mark(Stage.ACCOUNT_REQUEST_END, 100, 0, 1)
        assertEquals("op=CONNECT stage=ACCOUNT_REQUEST_END elapsedMs=0 code=100 bytes=0 asset=1", lines.last())
        trace.mark(Stage.ASSET_REQUEST_END, 599, 16 * 1024 * 1024, 8)
        assertEquals("op=CONNECT stage=ASSET_REQUEST_END elapsedMs=0 code=599 bytes=16777216 asset=8", lines.last())
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 99, 600, Int.MAX_VALUE)) {
            trace.mark(Stage.PAGE_REQUEST_END, invalid, Int.MAX_VALUE, invalid)
            assertEquals("op=CONNECT stage=PAGE_REQUEST_END elapsedMs=0 bytes=16777216", lines.last())
        }
        for (invalidAsset in listOf(0, 9, Int.MAX_VALUE)) {
            trace.mark(Stage.ASSET_REQUEST_END, bodyBytes = -1, asset = invalidAsset)
            assertEquals("op=CONNECT stage=ASSET_REQUEST_END elapsedMs=0 bytes=0", lines.last())
        }
        for (operation in Operation.entries) {
            val all = AppleConnectionTrace(operation, { 0L }, { lines.add(it) })
            Stage.entries.forEach { all.mark(it) }
            Reason.entries.forEach { all.finish(it) }
            AppleLyricsReason.entries.forEach {
                all.finish(it)
                assertTrue(lines.last().endsWith(" reason=${it.name}"))
            }
            AppleWebTokenFailure.entries.forEach {
                all.finish(it)
                assertTrue(lines.last().endsWith(" reason=${it.name}"))
            }
        }
        lines.forEach(::assertGrammar)
    }

    @Test fun elapsedTimeUsesMonotonicDifferenceAndHandlesNanoTimeWrap() {
        var now = -10_000_000_000L
        val lines = mutableListOf<String>()
        val trace = AppleConnectionTrace(Operation.CONNECT, { now }, { lines.add(it) })
        now += 1_999_999L
        trace.mark(Stage.REQUEST_MUTEX_WAIT)
        now += 5_000_001L
        trace.finish(Reason.READY)
        assertEquals(listOf(0L, 1L, 7L), lines.map { field(it, "elapsedMs").toLong() })

        now = Long.MAX_VALUE - 500_000L
        val wrapped = AppleConnectionTrace(Operation.PUBLIC_REFRESH, { now }, { lines.add(it) })
        now = Long.MIN_VALUE + 1_499_999L
        wrapped.finish(Reason.TIMEOUT)
        assertEquals("2", field(lines.last(), "elapsedMs"))
        lines.forEach(::assertGrammar)
    }

    @Test fun inputApiAcceptsOnlyClosedLabelsNumbersAndClockOrOutputCallbacks() {
        val type = AppleConnectionTrace::class.java
        val methods = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertEquals(setOf("mark", "finish", "toString"), methods.map { it.name }.toSet())
        val allowed = setOf(Stage::class.java, Reason::class.java, AppleLyricsReason::class.java,
            AppleWebTokenFailure::class.java, Int::class.javaObjectType)
        methods.forEach { method ->
            method.parameterTypes.forEach { assertTrue("unexpected diagnostic input type", it in allowed) }
        }
        val constructor = type.declaredConstructors.filterNot { it.isSynthetic }.single()
        assertEquals(listOf(Operation::class.java, Function0::class.java, Function1::class.java),
            constructor.parameterTypes.toList())
        val capturedSecret = SECRET
        val trace = AppleConnectionTrace(Operation.CONNECT, { 0L }, { error(capturedSecret) })
        assertEquals("AppleConnectionTrace(op=CONNECT)", trace.toString())
    }

    @Test fun sinkExceptionsCannotReplaceResultsOrOriginalCancellation() = runTest {
        for (error in listOf(IOException(SECRET), IllegalStateException(SECRET), CancellationException(SECRET))) {
            var writes = 0
            val trace = AppleConnectionTrace(Operation.CONNECT, { 0L }, { writes++; throw error })
            trace.mark(Stage.PUBLIC_BEARER_START)
            val result = AppleConnectResult.Ready("jp")
            trace.finish(Reason.READY)
            currentCoroutineContext().ensureActive()
            assertEquals("jp", result.storefront)
            assertEquals(3, writes)

            val original = CancellationException(USER_TOKEN)
            try {
                try {
                    throw original
                } finally {
                    trace.finish(Reason.CANCELLED)
                }
            } catch (actual: CancellationException) {
                assertSame(original, actual)
            }
            assertEquals(4, writes)
        }
        // Even a failing test clock must not introduce an operation failure or leak its message.
        val lines = mutableListOf<String>()
        val trace = AppleConnectionTrace(Operation.CONNECT, { throw IllegalStateException(SECRET) }, { lines.add(it) })
        trace.finish(Reason.INVALID)
        assertEquals(listOf("op=CONNECT stage=START elapsedMs=0",
            "op=CONNECT stage=END elapsedMs=0 reason=INVALID"), lines)
    }

    @Test fun enabledPageConnectionHasOrderedStagesAndSilentCachedGetAndFetch() = runTest {
        val f = Fixture(this)
        assertEquals("jp", (f.client.connect(USER_TOKEN) as AppleConnectResult.Ready).storefront)
        assertEquals(listOf(
            "CONNECT.START", "CONNECT.REQUEST_MUTEX_WAIT", "CONNECT.REQUEST_MUTEX_ACQUIRED", "CONNECT.PUBLIC_BEARER_START",
            "PUBLIC_REFRESH.START", "PUBLIC_REFRESH.PAGE_REQUEST_START", "PUBLIC_REFRESH.PAGE_REQUEST_END",
            "PUBLIC_REFRESH.PAGE_TOKEN_PARSE_START", "PUBLIC_REFRESH.PAGE_TOKEN_PARSE_END", "PUBLIC_REFRESH.END",
            "CONNECT.PUBLIC_BEARER_END", "CONNECT.ACCOUNT_REQUEST_START", "CONNECT.ACCOUNT_REQUEST_END", "CONNECT.END",
        ), events())
        assertEquals(listOf("PUBLIC_REFRESH.READY", "CONNECT.READY"), endings())
        val pageEnd = messages().single { field(it, "stage") == "PAGE_REQUEST_END" }
        assertEquals("200", field(pageEnd, "code"))
        assertEquals("$PUBLIC_TOKEN $SECRET".toByteArray(Charsets.UTF_8).size.toString(), field(pageEnd, "bytes"))
        val saved = messages()
        assertEquals(PUBLIC_TOKEN, (f.tokens.get() as AppleWebTokenResult.Ready).token.value)
        f.apiAnswer = { AppleLyricsHttpResponse(404, LYRIC_SECRET) }
        assertEquals(AppleLyricsReason.NOT_FOUND, f.client.fetch(
            LyricsRequest(LyricsQuery("title", "artist", null, 60L), LyricsIdentity(appleMusicId = "123")),
            "jp", USER_TOKEN,
        ).reason)
        assertEquals(saved, messages())
        assertEquals(1, f.publicCalls)
        assertEquals(2, f.apiCalls)
        assertSafeLogs()
    }

    @Test fun assetAndRedirectStagesUseOnlyOrdinalsAndSeparateParsing() = runTest {
        val f = Fixture(this)
        f.publicAnswer = {
            when (f.publicCalls) {
                1 -> AppleLyricsHttpResponse(302, "", location = "/$URL_SECRET")
                2 -> ok("<script src='/$URL_SECRET-one.js'></script><script src='/$URL_SECRET-two.js'></script>")
                3 -> AppleLyricsHttpResponse(404, SECRET)
                4 -> AppleLyricsHttpResponse(302, "", location = "/$URL_SECRET-final.js")
                5 -> ok("$PUBLIC_TOKEN $LYRIC_SECRET")
                else -> error("unexpected synthetic request")
            }
        }
        assertTrue(f.client.connect(USER_TOKEN) is AppleConnectResult.Ready)
        assertEquals(listOf(
            "START", "PAGE_REQUEST_START", "PAGE_REQUEST_END", "PAGE_REQUEST_START", "PAGE_REQUEST_END",
            "PAGE_TOKEN_PARSE_START", "PAGE_TOKEN_PARSE_END", "ASSET_LIST_PARSE_START", "ASSET_LIST_PARSE_END",
            "ASSET_REQUEST_START", "ASSET_REQUEST_END", "ASSET_REQUEST_START", "ASSET_REQUEST_END",
            "ASSET_REQUEST_START", "ASSET_REQUEST_END", "ASSET_TOKEN_PARSE_START", "ASSET_TOKEN_PARSE_END", "END",
        ), messages().filter { field(it, "op") == "PUBLIC_REFRESH" }.map { field(it, "stage") })
        val requests = messages().filter { field(it, "stage") in setOf("PAGE_REQUEST_END", "ASSET_REQUEST_END") }
        assertEquals(listOf("302", "200", "404", "302", "200"), requests.map { field(it, "code") })
        assertEquals(listOf("1", "2", "2"), requests.drop(2).map { field(it, "asset") })
        assertEquals(listOf("2", "2"), messages().filter {
            field(it, "stage").startsWith("ASSET_TOKEN_PARSE_")
        }.map { field(it, "asset") })
        assertEquals(5, f.publicCalls)
        assertEquals(1, f.apiCalls)
        assertSafeLogs()
    }

    @Test fun assetNumbersStopAtEight() = runTest {
        val f = Fixture(this)
        f.publicAnswer = {
            if (f.publicCalls == 1) ok((1..9).joinToString("") { "<script src='/$URL_SECRET-$it.js'></script>" })
            else ok(SECRET)
        }
        assertEquals(AppleWebTokenFailure.TOKEN_NOT_FOUND, (f.tokens.get() as AppleWebTokenResult.Unavailable).reason)
        assertEquals((1..8).map(Int::toString), messages().filter {
            field(it, "stage") == "ASSET_REQUEST_START"
        }.map { field(it, "asset") })
        assertEquals(9, f.publicCalls)
        assertEquals(listOf("PUBLIC_REFRESH.TOKEN_NOT_FOUND"), endings())
        assertSafeLogs()
    }

    @Test fun enabledFailuresUseFixedReasonsAndProviderCooldownIsSilent() = runTest {
        val failures: List<Pair<AppleWebTokenFailure, suspend () -> AppleLyricsHttpResponse>> = listOf(
            AppleWebTokenFailure.NETWORK to { throw IOException(SECRET) },
            AppleWebTokenFailure.HTTP to { AppleLyricsHttpResponse(500, SECRET) },
            AppleWebTokenFailure.TOO_LARGE to { AppleLyricsHttpResponse(200, PUBLIC_TOKEN, tooLarge = true) },
            AppleWebTokenFailure.TOKEN_NOT_FOUND to { ok(SECRET) },
            AppleWebTokenFailure.WAIT to { AppleLyricsHttpResponse(429, SECRET, retryAfterSec = 120) },
        )
        for ((reason, answer) in failures) {
            clearLogs()
            val f = Fixture(this)
            f.publicAnswer = answer
            val result = f.client.connect(USER_TOKEN) as AppleConnectResult.Unavailable
            val expected = when (reason) {
                AppleWebTokenFailure.NETWORK -> AppleLyricsReason.NETWORK
                AppleWebTokenFailure.WAIT -> AppleLyricsReason.WAIT
                else -> AppleLyricsReason.TOKEN_UNAVAILABLE
            }
            assertEquals(expected, result.reason)
            assertEquals(listOf("PUBLIC_REFRESH.${reason.name}", "CONNECT.${expected.name}"), endings())
            val before = messages()
            assertEquals(AppleWebTokenFailure.WAIT, (f.tokens.get() as AppleWebTokenResult.Unavailable).reason)
            assertEquals(before, messages())
            assertEquals(1, f.publicCalls)
            assertEquals(0, f.apiCalls)
            assertSafeLogs()
        }
        val accountFailures: List<Pair<AppleLyricsReason, suspend () -> AppleLyricsHttpResponse>> = listOf(
            AppleLyricsReason.NETWORK to { throw IOException(SECRET) },
            AppleLyricsReason.HTTP to { AppleLyricsHttpResponse(500, SECRET) },
            AppleLyricsReason.INVALID to { ok("$USER_TOKEN $LYRIC_SECRET") },
            AppleLyricsReason.WAIT to { AppleLyricsHttpResponse(503, SECRET) },
            AppleLyricsReason.AUTH_REQUIRED to { AppleLyricsHttpResponse(401, SECRET) },
        )
        for ((reason, answer) in accountFailures) {
            clearLogs()
            val f = Fixture(this)
            f.apiAnswer = answer
            assertEquals(reason, (f.client.connect(USER_TOKEN) as AppleConnectResult.Unavailable).reason)
            assertEquals("CONNECT.${reason.name}", endings().last())
            val attempts = if (reason == AppleLyricsReason.AUTH_REQUIRED) 2 else 1
            assertEquals(attempts, f.publicCalls)
            assertEquals(attempts, f.apiCalls)
            assertSafeLogs()
        }
        clearLogs()
        val invalid = Fixture(this)
        assertEquals(AppleLyricsReason.INVALID, (invalid.client.connect("\n$USER_TOKEN") as AppleConnectResult.Unavailable).reason)
        assertEquals(listOf("CONNECT.START", "CONNECT.END"), events())
        assertEquals(listOf("CONNECT.INVALID"), endings())
        assertEquals(0, invalid.publicCalls + invalid.apiCalls)
        assertSafeLogs()
    }

    @Test fun enabledTimeoutsKeepThirtyAndThirtyFiveSecondBounds() = runTest {
        for (duringPublic in listOf(true, false)) {
            clearLogs()
            val f = Fixture(this)
            if (duringPublic) f.publicAnswer = { awaitCancellation() }
            else f.apiAnswer = { awaitCancellation() }
            val start = testScheduler.currentTime
            assertEquals(AppleLyricsReason.TIMEOUT, (f.client.connect(USER_TOKEN) as AppleConnectResult.Unavailable).reason)
            assertEquals(if (duringPublic) 30_000L else 35_000L, testScheduler.currentTime - start)
            assertEquals(listOf(if (duringPublic) "PUBLIC_REFRESH.TIMEOUT" else "PUBLIC_REFRESH.READY", "CONNECT.TIMEOUT"), endings())
            assertEquals(1, f.publicCalls)
            assertEquals(if (duringPublic) 0 else 1, f.apiCalls)
            assertSafeLogs()
        }
    }

    @Test fun queuedConnectDistinguishesWaitingAcquisitionAndCancellation() = runTest {
        for (cancelWaiter in listOf(true, false)) {
            clearLogs()
            val f = Fixture(this)
            val release = CompletableDeferred<Unit>()
            f.apiAnswer = { release.await(); ok(STORE_BODY) }
            val owner = async { f.client.connect(USER_TOKEN) }
            runCurrent()
            val before = events().size
            val waiter = async { f.client.connect(USER_TOKEN) }
            runCurrent()
            assertEquals(listOf("CONNECT.START", "CONNECT.REQUEST_MUTEX_WAIT"), events().drop(before))
            assertEquals(1, f.apiCalls)
            if (cancelWaiter) {
                waiter.cancel()
                assertCancelled(waiter)
                assertEquals(listOf("CONNECT.START", "CONNECT.REQUEST_MUTEX_WAIT", "CONNECT.END"), events().drop(before))
                assertEquals("CONNECT.CANCELLED", endings().last())
            }
            release.complete(Unit)
            assertTrue(owner.await() is AppleConnectResult.Ready)
            if (!cancelWaiter) assertTrue(waiter.await() is AppleConnectResult.Ready)
            assertEquals(if (cancelWaiter) 1 else 2, events().count { it == "CONNECT.REQUEST_MUTEX_ACQUIRED" })
            assertEquals(if (cancelWaiter) 1 else 2, f.apiCalls)
            assertEquals(1, events().count { it == "PUBLIC_REFRESH.START" })
            assertSafeLogs()
        }
    }

    @Test fun enabledCancellationDoesNotPublishOrCacheAndKeepsSecretsOut() = runTest {
        for (duringPublic in listOf(true, false)) {
            for (selfCancel in listOf(true, false)) {
                clearLogs()
                val f = Fixture(this)
                val cancelledAnswer: suspend () -> AppleLyricsHttpResponse = {
                    if (selfCancel) {
                        currentCoroutineContext().cancel(CancellationException(USER_TOKEN))
                        throw IOException(SECRET)
                    }
                    awaitCancellation()
                }
                if (duringPublic) f.publicAnswer = cancelledAnswer else f.apiAnswer = cancelledAnswer
                val pending = async { f.client.connect(USER_TOKEN) }
                runCurrent()
                if (!selfCancel) pending.cancel(CancellationException(USER_TOKEN))
                assertCancelled(pending)
                assertEquals(listOf(if (duringPublic) "PUBLIC_REFRESH.CANCELLED" else "PUBLIC_REFRESH.READY", "CONNECT.CANCELLED"), endings())
                assertSafeLogs()
                f.publicAnswer = { ok(PUBLIC_TOKEN) }
                f.apiAnswer = { ok(STORE_BODY) }
                assertTrue(f.client.connect(USER_TOKEN) is AppleConnectResult.Ready)
                assertEquals(if (duringPublic) 2 else 1, f.publicCalls)
                assertEquals(if (duringPublic) 1 else 2, f.apiCalls)
                assertSafeLogs()
            }
        }
    }

    @Test fun cancellationOnReturnDispatchDoesNotLogReadyOrPublishProviderCache() = runTest {
        for (providerOnly in listOf(true, false)) {
            clearLogs()
            val delegate = StandardTestDispatcher(testScheduler)
            var afterIo: (() -> Unit)? = null
            val io = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    delegate.dispatch(context, Runnable { block.run(); afterIo?.invoke() })
                }
            }
            val f = Fixture(this, io)
            if (!providerOnly) {
                assertTrue(f.tokens.get() is AppleWebTokenResult.Ready)
                clearLogs()
            }
            val pending = async { if (providerOnly) f.tokens.get() else f.client.connect(USER_TOKEN) }
            afterIo = { pending.cancel(CancellationException(SECRET)) }
            assertCancelled(pending)
            assertEquals(listOf(if (providerOnly) "PUBLIC_REFRESH.CANCELLED" else "CONNECT.CANCELLED"), endings())
            afterIo = null
            assertTrue(f.client.connect(USER_TOKEN) is AppleConnectResult.Ready)
            assertEquals(if (providerOnly) 2 else 1, f.publicCalls)
            assertEquals(if (providerOnly) 1 else 2, f.apiCalls)
            assertSafeLogs()
        }
    }

    private class Fixture(scope: TestScope, io: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler)) {
        var publicCalls = 0
        var apiCalls = 0
        var publicAnswer: suspend () -> AppleLyricsHttpResponse = { ok("$PUBLIC_TOKEN $SECRET") }
        var apiAnswer: suspend () -> AppleLyricsHttpResponse = { ok(STORE_BODY) }
        private val transport = AppleLyricsTransport { url, headers, _ ->
            if (url.startsWith("https://music.apple.com/")) {
                publicCalls++
                assertEquals(mapOf("User-Agent" to APPLE_WEB_USER_AGENT), headers)
                publicAnswer()
            } else {
                apiCalls++
                assertEquals("Bearer $PUBLIC_TOKEN", headers["Authorization"])
                assertEquals(USER_TOKEN, headers["media-user-token"])
                apiAnswer()
            }
        }
        val tokens = AppleWebTokenProvider(transport, { 1_000_000L }, io, diagnosticsEnabled = true)
        val client = AppleLyricsClient(transport, tokens, { 1_000_000L }, io, diagnosticsEnabled = true)
    }

    private companion object {
        const val USER_TOKEN = "SYNTHETIC_PRIVATE_USER_TOKEN"
        const val SECRET = "SYNTHETIC_EXCEPTION_AND_RESPONSE_SECRET"
        const val LYRIC_SECRET = "SYNTHETIC_PRIVATE_LYRIC_CONTENT"
        const val URL_SECRET = "synthetic-private-asset-path"
        const val STORE_BODY = """{"data":[{"id":"jp"}],"private":"SYNTHETIC_EXCEPTION_AND_RESPONSE_SECRET"}"""
        val PUBLIC_TOKEN: String = run {
            fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
            encode("""{"alg":"ES256","typ":"JWT"}""") + "." +
                encode("""{"exp":2000,"secret":"$SECRET"}""") + ".SYNTHETIC_SIGNATURE"
        }
        val GRAMMAR = Regex("op=(CONNECT|PUBLIC_REFRESH) stage=([A-Z_]+) elapsedMs=([0-9]+)" +
            "(?: code=([0-9]+))?(?: bytes=([0-9]+))?(?: asset=([0-9]+))?(?: reason=([A-Z_]+))?")

        fun ok(body: String) = AppleLyricsHttpResponse(200, body)
        fun field(line: String, key: String): String = line.split(' ').single { it.startsWith("$key=") }.substringAfter('=')
        fun messages() = DiagLog.dump().map {
            assertTrue(it.contains("[AppleConnect] "))
            it.substringAfter("[AppleConnect] ")
        }
        fun events() = messages().map { "${field(it, "op")}.${field(it, "stage")}" }
        fun endings() = messages().filter { field(it, "stage") == "END" }.map { "${field(it, "op")}.${field(it, "reason")}" }

        fun assertGrammar(line: String) {
            val match = GRAMMAR.matchEntire(line)
            assertNotNull("diagnostic grammar", match)
            val parts = match!!.groupValues
            assertTrue(parts[2] in Stage.entries.map { it.name })
            assertTrue(parts[3].toLong() in 0L..Long.MAX_VALUE)
            if (parts[4].isNotEmpty()) assertTrue(parts[4].toInt() in 100..599)
            if (parts[5].isNotEmpty()) assertTrue(parts[5].toInt() in 0..16 * 1024 * 1024)
            if (parts[6].isNotEmpty()) assertTrue(parts[6].toInt() in 1..8)
            if (parts[7].isNotEmpty()) assertTrue(parts[7] in Reason.entries.map { it.name })
        }

        fun assertSafeLogs() {
            val forbidden = listOf(USER_TOKEN, PUBLIC_TOKEN, SECRET, LYRIC_SECRET, URL_SECRET,
                "music.apple.com", "Authorization", "media-user-token", "Bearer", "Exception", "jp")
            val logs = DiagLog.dump() + ShadowLog.getLogs().map { it.msg }
            logs.forEach { line -> forbidden.forEach { assertFalse("diagnostic leaked synthetic data", line.contains(it)) } }
            messages().forEach(::assertGrammar)
            ShadowLog.getLogs().filter { it.tag == "GT8D" }.forEach {
                assertTrue(it.msg.startsWith("[AppleConnect] "))
                assertGrammar(it.msg.substringAfter("[AppleConnect] "))
            }
        }

        suspend fun assertCancelled(deferred: Deferred<*>) {
            try {
                deferred.await()
                fail("cancellation was converted into a result")
            } catch (_: CancellationException) {
                assertTrue(deferred.isCancelled)
            }
        }
    }
}
