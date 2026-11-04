package com.gt8d.launcher.media.lyrics

import java.io.IOException
import java.util.Base64
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppleWebTokenProviderTest {
    @Test fun findsPageTokenAndReusesUntilExpiry() = runTest {
        val f = Fixture(this)
        val winner = jwt(1120, "first")
        val replacement = jwt(2000, "replacement")
        f.respond(
            ok(listOf(jwt(1060), jwt(1110), winner, jwt(1120, "tie"), jwt(1100)).joinToString("\n") +
                "<script src='/unused.js'></script>"),
            ok(replacement),
        )
        val first = ready(f.provider.get())
        assertEquals(winner, first.value)
        assertEquals(1120L, first.expiresAtEpochSec)
        assertSame(first, ready(f.provider.get()))
        f.now = 1_059_999L
        assertSame(first, ready(f.provider.get()))
        assertEquals(1, f.calls.size)
        f.now = 1_060_000L
        val next = ready(f.provider.get())
        assertEquals(replacement, next.value)
        assertNotSame(first, next)
        assertSame(next, ready(f.provider.get()))
        assertEquals(listOf(PAGE, PAGE), f.calls.map { it.url })
        assertPublicRequests(f)
    }

    @Test fun extractsAndOrdersSafeAssets() = runTest {
        val invalid = listOf(
            "https://other.test/external.js", "https://music.apple.com.evil.test/a.js",
            "https://sub.music.apple.com/a.js", "https://music.apple.com./a.js",
            "http://music.apple.com/a.js", "https://user@music.apple.com/a.js",
            "https://music.apple.com:444/a.js", "/fragment.js#x", "/empty-fragment.js#",
            "/bad%zz.js", "https://[broken/a.js", "/space here.js", "/not-js.css?a=.js",
            "/unknown&bogus;.js", "/no-semicolon&amp.js", "/null&#0;.js",
            "/surrogate&#xD800;.js", "/out-of-range&#x110000;.js", "/empty&#x;.js",
            "/signed&#+47;.js", "/overflow&#99999999999999999999999;.js",
            "/quote&quot;.js", "/less&lt;.js", "/greater&gt;.js",
        )
        val html = """
            <!-- <script src='/comment.js'></script><link rel='modulepreload' href='/comment-link.js'> -->
            <div data-text="<script src='/inside-attribute.js'>"></div>
            <script>const text = "<script src='/inside-script.js'>";</script>
            <style>/* <script src='/inside-style.js'></script> */</style>
            <script src=/unquoted.js></script>
            <link rel=modulepreload href='/unquoted-rel.js'>
            <link rel='notmodulepreload' href='/wrong-rel.js'>
            <link rel='modulepreload&unknown;' href='/bad-rel.js'>
            ${invalid.joinToString("\n") { "<script src='$it'></script>" }}
            <script data-extra='>' SRC='plain.js'></script>
            <ScRiPt defer SrC="/index-one.js?x=1&amp;y=2"></ScRiPt>
            <LINK HREF='&#x2F;index-two&#46;js' data-x='x' REL='preload MoDuLePrElOaD stylesheet'>
            <script src='https://music.apple.com:443/plain.js'></script>
            <script src='/index-one.js?x=1&#38;y=2'></script>
            <script src='/ap&apos;os.js'></script>
            <script src='/assets/&#233;.js'></script>
            <link href="/preload.js" REL="preload&#9;modulepreload">
            <script src='/extra1.js'></script>
            <script src='/extra2.js'></script>
            <script src='/extra3.js'></script>
            <script src='/extra4.js'></script>
            <script src='/index-last.js'></script>
            <!-- <script src='/unterminated-comment.js'></script>
        """.trimIndent()
        val f = Fixture(this)
        f.answer = { if (f.calls.size == 1) ok(html) else AppleLyricsHttpResponse(404, "") }
        unavailable(f.provider.get(), AppleWebTokenFailure.TOKEN_NOT_FOUND)
        assertEquals(
            listOf(PAGE) + listOf(
                "/index-one.js?x=1&y=2", "/index-two.js", "/index-last.js", "/plain.js",
                "/ap'os.js", "/assets/%C3%A9.js", "/preload.js", "/extra1.js",
            ).map { ROOT + it },
            f.calls.map { it.url },
        )
        assertPublicRequests(f)

        val success = Fixture(this)
        val token = jwt(2000)
        success.respond(ok("<script src='/first.js'></script><script src='/second.js'></script>"),
            AppleLyricsHttpResponse(500, token), ok(token))
        assertEquals(token, ready(success.provider.get()).value)
        assertEquals(3, success.calls.size)
    }

    @Test fun followsOnlyBoundedSameOriginRedirects() = runTest {
        val token = jwt(2000)
        for (code in listOf(301, 302, 303, 307, 308)) {
            val f = Fixture(this)
            f.respond(
                redirect(code, "next"),
                redirect(code, "HTTPS://MUSIC.APPLE.COM:443/absolute"),
                redirect(code, "//music.apple.com/final"),
                ok(token),
            )
            assertEquals(token, ready(f.provider.get()).value)
            assertEquals(listOf(PAGE, "$ROOT/us/next", "$ROOT/absolute", "$ROOT/final"), f.calls.map { it.url })
            assertPublicRequests(f)
        }
        val tooMany = Fixture(this)
        tooMany.answer = { redirect(302, "/again") }
        unavailable(tooMany.provider.get(), AppleWebTokenFailure.HTTP)
        assertEquals(4, tooMany.calls.size)
        assertPublicRequests(tooMany)

        for (location in listOf(
            null, "", " ", "https://outside.test/a", "//outside.test/a",
            "https://music.apple.com.evil.test/a", "https://sub.music.apple.com/a",
            "https://music.apple.com./a", "https://user@music.apple.com/a",
            "https://@music.apple.com/a", "https://user:pass@music.apple.com/a",
            "https://music.apple.com:80/a", "https://music.apple.com:444/a",
            "/a#fragment", "/a#", "http://music.apple.com/a", "https://[broken/",
            "/bad%zz", "/bad uri", "/bad\nuri", "javascript:alert(1)",
        )) {
            val f = Fixture(this)
            f.respond(redirect(302, location))
            unavailable(f.provider.get(), AppleWebTokenFailure.HTTP)
            assertEquals(1, f.calls.size)
            assertPublicRequests(f)
        }
        for (code in listOf(300, 304, 305, 306, 404)) {
            val f = Fixture(this)
            f.respond(redirect(code, "/next"))
            unavailable(f.provider.get(), AppleWebTokenFailure.HTTP)
            assertEquals(1, f.calls.size)
        }

        val assets = Fixture(this)
        assets.respond(redirect(302, "/other/page"), ok("<script src='asset.js'></script>"),
            redirect(307, "redirected.js"), ok(token))
        assertEquals(token, ready(assets.provider.get()).value)
        assertEquals(listOf(PAGE, "$ROOT/other/page", "$ROOT/asset.js", "$ROOT/redirected.js"), assets.calls.map { it.url })
        assertPublicRequests(assets)
        val badAsset = Fixture(this)
        badAsset.respond(ok("<script src='/one.js'></script><script src='/two.js'></script>"),
            redirect(308, "https://outside.test/a.js"))
        unavailable(badAsset.provider.get(), AppleWebTokenFailure.HTTP)
        assertEquals(2, badAsset.calls.size)
    }

    @Test fun rejectsInvalidAndOversizedTokens() = runTest {
        val invalidPayloads = listOf(
            "{", "[]", "null", "{}", "{\"exp\":\"2000\"}", "{\"exp\":2000.0}",
            "{\"exp\":2e3}", "{\"exp\":true}", "{\"exp\":null}", "{\"exp\":{}}",
            "{\"exp\":-2000}", "{\"exp\":0}", "{\"exp\":999}", "{\"exp\":1060}",
            "{\"exp\":9223372036854775808}", "{\"exp\":+2000}", "{\"exp\":02000}",
        )
        val invalidTokens = invalidPayloads.map { jwtPayload(it) } + listOf(
            "eyJdummy.A.SIGNATURE", "eyJdummy._w.SIGNATURE", "eyJdummy.e30", "eyJdummy..SIGNATURE",
            "eyJdummy.++++.SIGNATURE",
            jwtPayload(Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xff.toByte())), encoded = true),
        )
        for (value in invalidTokens) {
            val f = Fixture(this)
            f.respond(ok(value))
            unavailable(f.provider.get(), AppleWebTokenFailure.TOKEN_NOT_FOUND)
            assertEquals(1, f.calls.size)
        }
        val ordinary = jwt(2000)
        val atLimit = ordinary.substringBeforeLast('.') + "." + "S".repeat(TOKEN_LIMIT - ordinary.substringBeforeLast('.').length - 1)
        assertEquals(TOKEN_LIMIT, atLimit.toByteArray(Charsets.UTF_8).size)
        val fits = Fixture(this)
        fits.respond(ok(atLimit))
        assertEquals(atLimit, ready(fits.provider.get()).value)
        val oversized = Fixture(this)
        oversized.respond(ok(atLimit + "S"))
        unavailable(oversized.provider.get(), AppleWebTokenFailure.TOKEN_NOT_FOUND)

        val expired = (1..20).map { jwt(900, "expired-$it") }
        val twentyOnly = Fixture(this)
        twentyOnly.respond(ok((expired + ordinary).joinToString(" ")))
        unavailable(twentyOnly.provider.get(), AppleWebTokenFailure.TOKEN_NOT_FOUND)
        val distinct = Fixture(this)
        distinct.respond(ok((expired.take(19) + List(30) { expired.first() } + ordinary + jwt(3000)).joinToString(" ")))
        assertEquals(ordinary, ready(distinct.provider.get()).value)
        val oversizedCounts = Fixture(this)
        oversizedCounts.respond(ok((listOf(atLimit + "S") + expired.take(19) + ordinary).joinToString(" ")))
        unavailable(oversizedCounts.provider.get(), AppleWebTokenFailure.TOKEN_NOT_FOUND)

        val maxExpiry = Fixture(this)
        maxExpiry.respond(ok(jwt(Long.MAX_VALUE)))
        assertEquals(Long.MAX_VALUE, ready(maxExpiry.provider.get()).expiresAtEpochSec)
        val token = AppleWebToken(ordinary, 2000)
        assertEquals("AppleWebToken(redacted)", token.toString())
        assertEquals("AppleWebTokenResult.Ready(redacted)", AppleWebTokenResult.Ready(token).toString())
        for (reason in AppleWebTokenFailure.entries) {
            val output = AppleWebTokenResult.Unavailable(reason, 60).toString()
            assertFalse(output.contains(ordinary))
            assertFalse(output.contains(SECRET))
            assertEquals("AppleWebTokenResult.Unavailable(reason=$reason, retryAfterSec=60)", output)
        }
    }

    @Test fun boundsDownloadSizesAndTotalTime() = runTest {
        val token = jwt(2000)
        val exact = Fixture(this)
        exact.respond(ok(padded(token + "\n", RESPONSE_LIMIT)))
        assertEquals(token, ready(exact.provider.get()).value)
        assertEquals(RESPONSE_LIMIT, exact.calls.single().maxBytes)

        for (response in listOf(ok(padded("", RESPONSE_LIMIT + 1)),
            AppleLyricsHttpResponse(200, token, tooLarge = true))) {
            val f = Fixture(this)
            f.respond(response)
            unavailable(f.provider.get(), AppleWebTokenFailure.TOO_LARGE)
            assertEquals(1, f.calls.size)
            unavailable(f.provider.get(), AppleWebTokenFailure.WAIT, 60)
            assertEquals(1, f.calls.size)
        }

        val page = padded("<script src='/first.js'></script><script src='/last.js'></script>", RESPONSE_LIMIT)
        for (extra in listOf(0, 1)) {
            val f = Fixture(this)
            f.respond(ok(page), ok(padded("", RESPONSE_LIMIT - 1234)), ok(padded(token + "\n", 1234 + extra)))
            val result = f.provider.get()
            if (extra == 0) assertEquals(token, ready(result).value)
            else unavailable(result, AppleWebTokenFailure.TOO_LARGE)
            assertEquals(listOf(RESPONSE_LIMIT, RESPONSE_LIMIT, 1234), f.calls.map { it.maxBytes })
        }
        val exhausted = Fixture(this)
        exhausted.respond(ok(page), ok(padded("", RESPONSE_LIMIT)))
        unavailable(exhausted.provider.get(), AppleWebTokenFailure.TOO_LARGE)
        assertEquals(2, exhausted.calls.size)

        val jsTooLarge = Fixture(this)
        jsTooLarge.respond(ok("<script src='/large.js'></script>"), AppleLyricsHttpResponse(200, "", tooLarge = true))
        unavailable(jsTooLarge.provider.get(), AppleWebTokenFailure.TOO_LARGE)
        assertEquals(2, jsTooLarge.calls.size)

        val timed = Fixture(this)
        timed.answer = {
            delay(11_000)
            when (timed.calls.size) {
                1 -> ok("<script src='/one.js'></script><script src='/two.js'></script>")
                2 -> ok("")
                else -> ok(token)
            }
        }
        val started = testScheduler.currentTime
        unavailable(timed.provider.get(), AppleWebTokenFailure.TIMEOUT)
        assertEquals(30_000L, testScheduler.currentTime - started)
        assertEquals(3, timed.calls.size)
        unavailable(timed.provider.get(), AppleWebTokenFailure.WAIT, 60)
        assertEquals(3, timed.calls.size)

        for (duration in listOf(29_999L, 30_000L, 30_001L)) {
            val f = Fixture(this)
            f.answer = { delay(duration); ok(token) }
            val before = testScheduler.currentTime
            val result = f.provider.get()
            if (duration < 30_000) assertEquals(token, ready(result).value)
            else unavailable(result, AppleWebTokenFailure.TIMEOUT)
            assertEquals(minOf(duration, 30_000L), testScheduler.currentTime - before)
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun deduplicatesConcurrentRefreshAndInvalidatesByIdentity() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        val token = jwt(2000)
        f.answer = { release.await(); ok(token) }
        val requests = List(12) { async { f.provider.get() } }
        runCurrent()
        assertEquals(1, f.calls.size)
        assertTrue(requests.all { !it.isCompleted })
        release.complete(Unit)
        val values = requests.awaitAll().map { ready(it) }
        values.forEach { assertSame(values.first(), it) }
        assertEquals(1, f.calls.size)

        val first = values.first()
        f.provider.invalidate(AppleWebToken(first.value, first.expiresAtEpochSec))
        assertSame(first, ready(f.provider.get()))
        assertEquals(1, f.calls.size)
        f.provider.invalidate(first)
        val second = ready(f.provider.get())
        assertNotSame(first, second)
        assertEquals(first.value, second.value)
        assertEquals(2, f.calls.size)
        f.provider.invalidate(first)
        assertSame(second, ready(f.provider.get()))
        assertEquals(2, f.calls.size)
        f.provider.invalidate(second)
        assertNotSame(second, ready(f.provider.get()))
        assertEquals(3, f.calls.size)
    }

    @Test fun failuresRespectCooldown() = runTest {
        val token = jwt(2000)
        val failures: List<Pair<AppleWebTokenFailure, suspend (Request) -> AppleLyricsHttpResponse>> = listOf(
            AppleWebTokenFailure.NETWORK to { throw IOException(SECRET) },
            AppleWebTokenFailure.NETWORK to { throw IllegalStateException(SECRET) },
            AppleWebTokenFailure.HTTP to { AppleLyricsHttpResponse(500, SECRET) },
            AppleWebTokenFailure.TOKEN_NOT_FOUND to { ok(SECRET) },
        )
        for ((reason, response) in failures) {
            val f = Fixture(this)
            f.answer = response
            val result = f.provider.get()
            unavailable(result, reason)
            assertFalse(result.toString().contains(SECRET))
            f.answer = { ok(token) }
            unavailable(f.provider.get(), AppleWebTokenFailure.WAIT, 60)
            f.now += 59_999
            unavailable(f.provider.get(), AppleWebTokenFailure.WAIT, 1)
            assertEquals(1, f.calls.size)
            f.now++
            assertEquals(token, ready(f.provider.get()).value)
            assertEquals(2, f.calls.size)
        }

        for (code in listOf(429, 503)) {
            for (retry in listOf(null, -5L, 0L, 1L, 120L, Long.MAX_VALUE)) {
                val f = Fixture(this)
                f.respond(AppleLyricsHttpResponse(code, SECRET, retryAfterSec = retry), ok(jwt(Long.MAX_VALUE)))
                val wait = maxOf(60L, retry ?: 0L)
                unavailable(f.provider.get(), AppleWebTokenFailure.WAIT, wait)
                val remaining = if (retry == Long.MAX_VALUE) 9_223_372_036_853_776L else wait
                unavailable(f.provider.get(), AppleWebTokenFailure.WAIT, remaining)
                assertEquals(1, f.calls.size)
                f.now = if (retry == Long.MAX_VALUE) Long.MAX_VALUE - 1 else NOW + wait * 1000 - 1
                unavailable(f.provider.get(), AppleWebTokenFailure.WAIT, 1)
                assertEquals(1, f.calls.size)
                f.now++
                assertEquals(Long.MAX_VALUE, ready(f.provider.get()).expiresAtEpochSec)
                assertEquals(2, f.calls.size)
            }
            val js = Fixture(this)
            js.respond(ok("<script src='/one.js'></script><script src='/two.js'></script>"),
                AppleLyricsHttpResponse(code, SECRET, retryAfterSec = 120))
            unavailable(js.provider.get(), AppleWebTokenFailure.WAIT, 120)
            assertEquals(2, js.calls.size)
            unavailable(js.provider.get(), AppleWebTokenFailure.WAIT, 120)
            assertEquals(2, js.calls.size)
        }

        val negativeClock = Fixture(this)
        negativeClock.now = Long.MIN_VALUE
        negativeClock.respond(AppleLyricsHttpResponse(429, "", retryAfterSec = Long.MAX_VALUE))
        unavailable(negativeClock.provider.get(), AppleWebTokenFailure.WAIT, Long.MAX_VALUE)
        unavailable(negativeClock.provider.get(), AppleWebTokenFailure.WAIT, 18_446_744_073_709_552L)
        assertEquals(1, negativeClock.calls.size)

        val invalidateDuringWait = Fixture(this)
        invalidateDuringWait.respond(ok(jwt(1100)), AppleLyricsHttpResponse(500, SECRET), ok(token))
        val old = ready(invalidateDuringWait.provider.get())
        invalidateDuringWait.now = 1_040_000
        unavailable(invalidateDuringWait.provider.get(), AppleWebTokenFailure.HTTP)
        invalidateDuringWait.provider.invalidate(old)
        invalidateDuringWait.provider.invalidate(AppleWebToken(old.value, old.expiresAtEpochSec))
        unavailable(invalidateDuringWait.provider.get(), AppleWebTokenFailure.WAIT, 60)
        assertEquals(2, invalidateDuringWait.calls.size)
        invalidateDuringWait.now += 60_000
        assertEquals(token, ready(invalidateDuringWait.provider.get()).value)
        assertEquals(3, invalidateDuringWait.calls.size)
    }

    @Test fun cancellationDoesNotPublishOrCache() = runTest {
        val token = jwt(2000)
        val waiting = Fixture(this)
        val release = CompletableDeferred<Unit>()
        waiting.answer = { release.await(); ok(token) }
        val owner = async { waiting.provider.get() }
        runCurrent()
        val waiter = async { waiting.provider.get() }
        runCurrent()
        assertEquals(1, waiting.calls.size)
        waiter.cancel()
        assertCancelled(waiter)
        release.complete(Unit)
        val shared = ready(owner.await())
        assertSame(shared, ready(waiting.provider.get()))
        assertEquals(1, waiting.calls.size)

        val inFlight = Fixture(this)
        inFlight.answer = { awaitCancellation() }
        val cancelledOwner = async { inFlight.provider.get() }
        runCurrent()
        val survivingWaiter = async { inFlight.provider.get() }
        runCurrent()
        assertEquals(1, inFlight.calls.size)
        inFlight.answer = { ok(token) }
        cancelledOwner.cancel()
        assertCancelled(cancelledOwner)
        assertEquals(token, ready(survivingWaiter.await()).value)
        assertEquals(2, inFlight.calls.size)

        // Cancellation immediately before a fake transport returns success or throws an ordinary error.
        for (failure in listOf(false, true)) {
            val f = Fixture(this)
            f.answer = {
                currentCoroutineContext().cancel()
                if (failure) throw IOException(SECRET)
                ok(token)
            }
            val cancelled = async { f.provider.get() }
            assertCancelled(cancelled)
            f.answer = { ok(token) }
            assertEquals(token, ready(f.provider.get()).value)
            assertEquals(2, f.calls.size)
        }

        // Cancel after IO has computed its result, while the caller's return dispatch is still queued.
        for (firstResponse in listOf(ok(token), AppleLyricsHttpResponse(500, SECRET))) {
            val delegate = StandardTestDispatcher(testScheduler)
            var afterIo: (() -> Unit)? = null
            val io = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    delegate.dispatch(context, Runnable {
                        block.run()
                        afterIo?.invoke()
                    })
                }
            }
            val f = Fixture(this, io)
            f.respond(firstResponse, ok(token))
            val cancelled = async { f.provider.get() }
            afterIo = { cancelled.cancel() }
            assertCancelled(cancelled)
            afterIo = null
            assertEquals(token, ready(f.provider.get()).value)
            assertEquals(2, f.calls.size)
        }

        val thrown = Fixture(this)
        thrown.answer = { throw CancellationException(SECRET) }
        assertCancelled(async { thrown.provider.get() })
        thrown.answer = { ok(token) }
        assertEquals(token, ready(thrown.provider.get()).value)
        assertEquals(2, thrown.calls.size)

        val parentTimeout = Fixture(this)
        parentTimeout.answer = { delay(100); ok(token) }
        assertCancelled(async { withTimeout(50) { parentTimeout.provider.get() } })
        parentTimeout.answer = { ok(token) }
        assertEquals(token, ready(parentTimeout.provider.get()).value)
        assertEquals(2, parentTimeout.calls.size)
    }

    private class Fixture(scope: TestScope, val io: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler)) {
        var now = NOW
        val calls = mutableListOf<Request>()
        var answer: suspend (Request) -> AppleLyricsHttpResponse = { ok("") }
        val provider = AppleWebTokenProvider(AppleLyricsTransport { url, headers, maxBytes ->
            assertSame(io, currentCoroutineContext()[ContinuationInterceptor])
            val request = Request(url, headers.toMap(), maxBytes)
            calls.add(request)
            answer(request)
        }, { now }, io)

        fun respond(vararg responses: AppleLyricsHttpResponse) {
            val pending = ArrayDeque(responses.toList())
            answer = {
                assertFalse("unexpected extra request", pending.isEmpty())
                pending.removeFirst()
            }
        }
    }

    private data class Request(val url: String, val headers: Map<String, String>, val maxBytes: Int)

    private companion object {
        const val ROOT = "https://music.apple.com"
        const val PAGE = "$ROOT/us/browse"
        const val NOW = 1_000_000L
        const val RESPONSE_LIMIT = 8 * 1024 * 1024
        const val TOKEN_LIMIT = 16 * 1024
        const val SECRET = "PRIVATE_SYNTHETIC_TOKEN_DETAIL"

        fun ok(body: String) = AppleLyricsHttpResponse(200, body)
        fun redirect(code: Int, location: String?) = AppleLyricsHttpResponse(code, "", location = location)
        fun jwt(exp: Long, marker: String = SECRET) = jwtPayload("{\"exp\":$exp,\"dummy\":\"$marker\"}")
        fun jwtPayload(payload: String, encoded: Boolean = false): String {
            fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
            return encode("{\"alg\":\"ES256\",\"typ\":\"JWT\"}") + "." +
                (if (encoded) payload else encode(payload)) + ".SYNTHETIC_SIGNATURE"
        }

        fun ready(result: AppleWebTokenResult): AppleWebToken {
            assertTrue("expected Ready, got $result", result is AppleWebTokenResult.Ready)
            return (result as AppleWebTokenResult.Ready).token
        }

        fun unavailable(result: AppleWebTokenResult, reason: AppleWebTokenFailure, retry: Long? = null) {
            assertTrue("expected Unavailable, got $result", result is AppleWebTokenResult.Unavailable)
            result as AppleWebTokenResult.Unavailable
            assertEquals(reason, result.reason)
            assertEquals(retry, result.retryAfterSec)
        }

        fun assertPublicRequests(f: Fixture) {
            val expected = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
            assertEquals(expected, APPLE_WEB_USER_AGENT)
            f.calls.forEach { assertEquals(mapOf("User-Agent" to expected), it.headers) }
        }

        fun padded(prefix: String, bytes: Int): String {
            val remaining = bytes - prefix.toByteArray(Charsets.UTF_8).size
            require(remaining >= 0)
            return prefix + "é".repeat(remaining / 2) + "x".repeat(remaining % 2)
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
