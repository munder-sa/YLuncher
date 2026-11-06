package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.diag.DiagLog
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppleLyricsClientTest {
    @Test fun validatesInputsWithoutNetwork() = runTest {
        val f = Fixture(this)
        val controls = ((0..31) + (127..159)).map { it.toChar() }
        val invalidTokens = listOf("", "   ", "\u3000", "x".repeat(TOKEN_LIMIT + 1),
            "é".repeat(TOKEN_LIMIT / 2) + "x") +
            controls.flatMap { listOf("a${it}b", "${it}a", "a${it}") }
        for (token in invalidTokens) {
            unavailable(f.client.connect(token), AppleLyricsReason.INVALID)
            result(f.client.fetch(request(), "gb", token), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        }
        for (storefront in listOf("", "g", "gbr", "GB", "gB", " g", "gb ", "日本", "g1", "g/", "gb\n")) {
            result(f.client.fetch(request(), storefront, USER_TOKEN), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        }
        for (id in listOf(null, "", "0", "00", "0".repeat(20), "1".repeat(21), "１", "١", "-1", "+1", "1.0", "1/2", "1?x", "1\n", " 1")) {
            result(f.client.fetch(request(id = id), "gb", USER_TOKEN), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        }
        for (blank in listOf("", " \t\n", "\u3000")) {
            result(f.client.fetch(request(title = blank), "gb", USER_TOKEN), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
            result(f.client.fetch(request(artist = blank), "gb", USER_TOKEN), LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
        }
        assertEquals(0, f.calls.size)

        for (token in listOf(USER_TOKEN, "x".repeat(TOKEN_LIMIT), "é".repeat(TOKEN_LIMIT / 2))) {
            assertEquals("gb", ready(f.client.connect(" $token\u3000")))
            assertEquals(token, f.apiCalls.last().headers["media-user-token"])
        }
        for (id in listOf("1", "00000000000000000001", "9".repeat(20))) {
            synced(f.client.fetch(request(id = id, title = " A ", artist = " B "), "zz", USER_TOKEN))
            assertEquals("$API/v1/catalog/zz/songs/$id/syllable-lyrics", f.apiCalls.last().url)
        }
        assertEquals(1, f.publicCalls.size)
        assertEquals(6, f.apiCalls.size)
    }

    @Test fun connectsUsingAccountStorefront() = runTest {
        val f = Fixture(this)
        assertEquals("gb", ready(f.client.connect(" $USER_TOKEN ")))
        assertEquals(listOf(PAGE, STOREFRONT), f.calls.map { it.url })
        assertEquals(mapOf("User-Agent" to APPLE_WEB_USER_AGENT), f.publicCalls.single().headers)
        val call = f.apiCalls.single()
        assertEquals(API_LIMIT, call.maxBytes)
        assertEquals(mapOf(
            "Authorization" to "Bearer $PUBLIC_TOKEN",
            "media-user-token" to USER_TOKEN,
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com/",
            "Accept" to "application/json",
            "User-Agent" to APPLE_WEB_USER_AGENT,
        ), call.headers)

        val badBodies = listOf(
            "{", "[]", "null", "true", "{}", """{"data":null}""", """{"data":{}}""",
            """{"data":[]}""", """{"data":[null]}""", """{"data":["gb"]}""", """{"data":[{}]}""",
            """{"data":[{"id":2}]}""", """{"data":[{"id":true}]}""", """{"data":[{"id":null}]}""",
            """{"data":[{"id":"GB"}]}""", """{"data":[{"id":"g"}]}""", """{"data":[{"id":"gbr"}]}""",
            """{"data":[{"id":"gb "}]}""", """{"data":[{"id":"日本"}]}""",
            """{"data":[{"id":"gb"},{"id":"jp"}]}""",
            """{"elsewhere":{"data":[{"id":"gb"}]}}""",
        )
        for (body in badBodies) {
            f.apiAnswer = { ok(body) }
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.INVALID)
        }
        for (code in listOf(201, 204, 301, 404, 500)) {
            f.apiAnswer = { AppleLyricsHttpResponse(code, STORE_BODY) }
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.HTTP)
        }
        val exact = paddedJson(STORE_BODY, API_LIMIT)
        assertEquals(API_LIMIT, exact.toByteArray(Charsets.UTF_8).size)
        f.apiAnswer = { ok(exact) }
        assertEquals("gb", ready(f.client.connect(USER_TOKEN)))
        for (response in listOf(ok(exact + " "), AppleLyricsHttpResponse(200, STORE_BODY, tooLarge = true))) {
            f.apiAnswer = { response }
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.INVALID)
        }
        assertEquals(1, f.publicCalls.size)
        assertEquals(1 + badBodies.size + 5 + 3, f.apiCalls.size)
    }

    @Test fun fetchesOnlyDirectWordLyrics() = runTest {
        val f = Fixture(this)
        assertEquals(listOf(LyricLine(1000, "あ😀 B", listOf(LyricWord(1000, 0), LyricWord(1500, 4)))),
            synced(f.client.fetch(request(), "gb", USER_TOKEN)))
        assertEquals(listOf(PAGE, LYRICS), f.calls.map { it.url })
        assertEquals(API_LIMIT, f.apiCalls.single().maxBytes)
        assertEquals("Bearer $PUBLIC_TOKEN", f.apiCalls.single().headers["Authorization"])
        assertEquals(USER_TOKEN, f.apiCalls.single().headers["media-user-token"])
        assertEquals(6, f.apiCalls.single().headers.size)
        assertFalse(f.apiCalls.single().headers.keys.any { it.equals("Cookie", ignoreCase = true) })

        val quoted = JsonPrimitive(WORDS).toString()
        for (body in listOf(
            """{"ttml":$quoted}""",
            """{"data":[{"ttml":$quoted}]}""",
            """{"data":[{"attributes":{"lyrics":{"ttml":$quoted}}}]}""",
            """{"data":[{"attributes":{"syllable-lyrics":{"ttml":$quoted}}}]}""",
            """{"included":[{"attributes":{"ttml":$quoted}}]}""",
        )) {
            f.apiAnswer = { ok(body) }
            result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.INVALID)
        }
        f.apiAnswer = { ok("""{"data":[],"included":[{"attributes":{"ttml":$quoted}}]}""") }
        result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Missing, AppleLyricsReason.NOT_FOUND)

        // A line just after one second distinguishes a supplied duration from a discarded duration.
        f.apiAnswer = { ok(lyricsBody(WORDS.replace("begin=\"1\"", "begin=\"1.001\""))) }
        result(f.client.fetch(request(duration = 1), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.INVALID)
        for (duration in listOf(null, Long.MIN_VALUE, -1L, 0L, 3601L, Long.MAX_VALUE, 2L, 3600L)) {
            synced(f.client.fetch(request(duration = duration), "gb", USER_TOKEN))
        }
        f.apiAnswer = { ok(lyricsBody("""<t:tt xmlns:t="http://www.w3.org/ns/ttml"><t:body><t:p begin="3600"><t:span begin="3600">A</t:span></t:p></t:body></t:tt>""")) }
        assertEquals(3_600_000L, synced(f.client.fetch(request(duration = 3600), "gb", USER_TOKEN)).single().timeMs)
        f.apiAnswer = { ok(lyricsBody(WORDS)) }
        synced(f.client.fetch(request(duration = 1), "gb", USER_TOKEN))
        assertEquals(1, f.publicCalls.size)
        assertTrue(f.apiCalls.all { it.url == LYRICS && it.maxBytes == API_LIMIT })
    }

    @Test fun classifiesMissingUnsupportedAndInvalid() = runTest {
        val f = Fixture(this)
        for (response in listOf(AppleLyricsHttpResponse(404, "not JSON"), ok("""{"data":[]}"""))) {
            f.apiAnswer = { response }
            result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Missing, AppleLyricsReason.NOT_FOUND)
        }
        f.apiAnswer = { ok(lyricsBody("""<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1">line only</p></body></tt>""")) }
        result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Missing, AppleLyricsReason.UNSUPPORTED)
        val invalidXml = listOf(
            "", " \n", "<", WORDS.dropLast(1),
            "<!DOCTYPE tt>" + WORDS,
            "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///not-read-apple-lyrics'>]>" + WORDS,
            "<!DOCTYPE tt SYSTEM 'https://invalid.example/not-requested'>" + WORDS,
            WORDS.replace("http://www.w3.org/ns/ttml", "urn:other"),
            WORDS.replace("<tt ", "<other ").replace("</tt>", "</other>"),
            "<other>$WORDS</other>",
            WORDS.replace(" xmlns=\"http://www.w3.org/ns/ttml\"", ""),
            WORDS.replace("1.5", "0.5"),
        )
        val item = """{"attributes":{"ttml":${JsonPrimitive(WORDS)}}}"""
        val invalidJson = listOf(
            "{", "[]", "null", "{}", """{"data":null}""", """{"data":{}}""", """{"data":"x"}""",
            """{"data":[null]}""", """{"data":[{}]}""", """{"data":["x"]}""",
            """{"data":[{"attributes":null}]}""", """{"data":[{"attributes":[]}]}""",
            """{"data":[{"attributes":{"ttml":1}}]}""", """{"data":[{"attributes":{"ttml":null}}]}""",
            """{"data":[{"attributes":{"ttml":true}}]}""", """{"data":[{"attributes":{"ttml":{}}}]}""",
            """{"data":[$item,$item]}""", """{"data":[$item],"errors":[{"detail":"synthetic"}]}""",
            """{"data":[],"errors":[{}]}""", """{"data":[$item],"errors":{}}""",
            """{"data":[$item],"errors":null}""", """{"data":[$item],"errors":"x"}""",
        )
        for (body in invalidXml.map(::lyricsBody) + invalidJson) {
            f.apiAnswer = { ok(body) }
            result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.INVALID)
        }
        f.apiAnswer = { ok("""{"data":[$item],"errors":[]}""") }
        synced(f.client.fetch(request(), "gb", USER_TOKEN))

        val exactTtml = paddedTtml(TTML_LIMIT)
        val exactJson = paddedJson(lyricsBody(WORDS), API_LIMIT)
        assertEquals(TTML_LIMIT, exactTtml.toByteArray(Charsets.UTF_8).size)
        assertEquals(API_LIMIT, exactJson.toByteArray(Charsets.UTF_8).size)
        for (body in listOf(lyricsBody(exactTtml), exactJson)) {
            f.apiAnswer = { ok(body) }
            synced(f.client.fetch(request(), "gb", USER_TOKEN))
        }
        for (response in listOf(ok(lyricsBody(paddedTtml(TTML_LIMIT + 1))), ok(exactJson + " "),
            AppleLyricsHttpResponse(200, lyricsBody(WORDS), tooLarge = true))) {
            f.apiAnswer = { response }
            result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.INVALID)
        }
        assertEquals(1, f.publicCalls.size)
        assertEquals(2 + 1 + invalidXml.size + invalidJson.size + 1 + 2 + 3, f.apiCalls.size)
    }

    @Test fun refreshesBearerAtMostOnceOnAuthenticationFailure() = runTest {
        for (connect in listOf(false, true)) {
            for (code in listOf(401, 403)) {
                for (rejectedAgain in listOf(false, true)) {
                    val f = Fixture(this)
                    val replacement = jwt("replacement")
                    f.publicAnswer = { call ->
                        if (call.url == PAGE) ok("<script src='/index.js'></script>")
                        else ok(if (f.publicCalls.size == 2) PUBLIC_TOKEN else replacement)
                    }
                    val old = (f.tokens.get() as AppleWebTokenResult.Ready).token
                    f.apiAnswer = { call ->
                        if (f.apiCalls.size == 1 || rejectedAgain) AppleLyricsHttpResponse(code, SECRET)
                        else if (call.url == STOREFRONT) ok(STORE_BODY) else ok(lyricsBody(WORDS))
                    }
                    if (connect) {
                        val actual = f.client.connect(USER_TOKEN)
                        if (rejectedAgain) unavailable(actual, AppleLyricsReason.AUTH_REQUIRED)
                        else assertEquals("gb", ready(actual))
                    } else {
                        val actual = f.client.fetch(request(), "gb", USER_TOKEN)
                        if (rejectedAgain) result(actual, LyricsState.Failed, AppleLyricsReason.AUTH_REQUIRED)
                        else synced(actual)
                    }
                    assertEquals(listOf(PAGE, ASSET, if (connect) STOREFRONT else LYRICS,
                        PAGE, ASSET, if (connect) STOREFRONT else LYRICS), f.calls.map { it.url })
                    assertEquals(listOf("Bearer $PUBLIC_TOKEN", "Bearer $replacement"),
                        f.apiCalls.map { it.headers["Authorization"] })
                    val fresh = (f.tokens.get() as AppleWebTokenResult.Ready).token
                    assertNotSame(old, fresh)
                    assertEquals(replacement, fresh.value)
                    f.tokens.invalidate(old)
                    assertSame(fresh, (f.tokens.get() as AppleWebTokenResult.Ready).token)
                    assertEquals(6, f.calls.size)
                }
            }
        }
        for (connect in listOf(false, true)) {
            val f = Fixture(this)
            f.apiAnswer = {
                if (f.apiCalls.size == 1) {
                    delay(if (connect) 29_000L else 4_000L)
                    AppleLyricsHttpResponse(401, "")
                } else {
                    delay(7_000)
                    if (connect) ok(STORE_BODY) else ok(lyricsBody(WORDS))
                }
            }
            val start = testScheduler.currentTime
            if (connect) unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.TIMEOUT)
            else result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.TIMEOUT)
            assertEquals(if (connect) 35_000L else 5_000L, testScheduler.currentTime - start)
            assertEquals(2, f.publicCalls.size)
            assertEquals(2, f.apiCalls.size)
        }
    }

    @Test fun respectsHostCooldownAcrossCalls() = runTest {
        for (code in listOf(429, 503)) {
            for (retry in listOf(null, -1L, 0L, 1L, 60L, 121L)) {
                for (connectFirst in listOf(false, true)) {
                    val f = Fixture(this)
                    f.apiAnswer = { AppleLyricsHttpResponse(code, "not JSON", retryAfterSec = retry) }
                    val seconds = if (retry == 121L) 121L else 60L
                    if (connectFirst) unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, seconds)
                    else result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.WAIT, seconds)
                    val old = (f.tokens.get() as AppleWebTokenResult.Ready).token
                    f.tokens.invalidate(old)
                    unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, seconds)
                    result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.WAIT, seconds)
                    f.now += seconds * 1000 - 1
                    unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, 1)
                    result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.WAIT, 1)
                    assertEquals(2, f.calls.size)
                    f.now++
                    f.apiAnswer = { if (it.url == STOREFRONT) ok(STORE_BODY) else ok(lyricsBody(WORDS)) }
                    assertEquals("gb", ready(f.client.connect(USER_TOKEN)))
                    synced(f.client.fetch(request(), "gb", USER_TOKEN))
                    assertEquals(2, f.publicCalls.size)
                    assertEquals(3, f.apiCalls.size)
                }
            }
        }
        for (now in listOf(Long.MIN_VALUE, NOW, Long.MAX_VALUE - 1500)) {
            val f = Fixture(this)
            f.now = now
            f.apiAnswer = { AppleLyricsHttpResponse(503, "", retryAfterSec = Long.MAX_VALUE) }
            val expected = when (now) {
                Long.MIN_VALUE -> 18_446_744_073_709_552L
                NOW -> 9_223_372_036_853_776L
                else -> 2L
            }
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, expected)
            result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.WAIT, expected)
            assertEquals(2, f.calls.size)
            f.now = Long.MAX_VALUE - 1
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, 1)
            f.now = Long.MAX_VALUE
            f.apiAnswer = { ok(STORE_BODY) }
            assertEquals("gb", ready(f.client.connect(USER_TOKEN)))
            assertEquals(3, f.calls.size)
        }

        // The second operation is already queued when the first publishes a cooldown.
        val concurrent = Fixture(this)
        val release = CompletableDeferred<Unit>()
        concurrent.apiAnswer = { release.await(); AppleLyricsHttpResponse(429, "", retryAfterSec = 90) }
        val first = async { concurrent.client.connect(USER_TOKEN) }
        runCurrent()
        val second = async { concurrent.client.fetch(request(), "gb", USER_TOKEN) }
        runCurrent()
        assertEquals(2, concurrent.calls.size)
        release.complete(Unit)
        unavailable(first.await(), AppleLyricsReason.WAIT, 90)
        result(second.await(), LyricsState.Failed, AppleLyricsReason.WAIT, 90)
        assertEquals(2, concurrent.calls.size)
    }

    @Test fun hostCooldownUsesMonotonicClockAcrossWallClockChanges() = runTest {
        for (code in listOf(429, 503)) {
            val f = Fixture(this)
            f.elapsed = 10_000L
            f.apiAnswer = { AppleLyricsHttpResponse(code, "", retryAfterSec = 60) }
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, 60)

            f.now = NOW + 3_600_000L
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, 60)
            result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.WAIT, 60)
            assertEquals(1, f.apiCalls.size)
            assertEquals(1, f.publicCalls.size)

            f.now = NOW - 3_600_000L
            f.elapsed = 69_999L
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, 1)
            assertEquals(1, f.apiCalls.size)
            f.elapsed = 70_000L
            f.apiAnswer = { ok(STORE_BODY) }
            assertEquals("gb", ready(f.client.connect(USER_TOKEN)))
            assertEquals(2, f.apiCalls.size)
            assertEquals(1, f.publicCalls.size)
        }
    }

    @Test fun classifiesProviderAndNetworkFailures() = runTest {
        val providerFailures: List<Pair<AppleLyricsReason, suspend (Call) -> AppleLyricsHttpResponse>> = listOf(
            AppleLyricsReason.NETWORK to { throw IOException(SECRET) },
            AppleLyricsReason.NETWORK to { throw IllegalStateException(SECRET) },
            AppleLyricsReason.TOKEN_UNAVAILABLE to { AppleLyricsHttpResponse(500, PUBLIC_TOKEN) },
            AppleLyricsReason.TOKEN_UNAVAILABLE to { AppleLyricsHttpResponse(200, PUBLIC_TOKEN, tooLarge = true) },
            AppleLyricsReason.TOKEN_UNAVAILABLE to { ok("no synthetic bearer here") },
            AppleLyricsReason.WAIT to { AppleLyricsHttpResponse(503, "", retryAfterSec = 120) },
        )
        for ((reason, answer) in providerFailures) {
            for (connect in listOf(false, true)) {
                val f = Fixture(this)
                f.publicAnswer = answer
                val retry = if (reason == AppleLyricsReason.WAIT) 120L else null
                if (connect) unavailable(f.client.connect(USER_TOKEN), reason, retry)
                else result(f.client.fetch(request(), "gb", USER_TOKEN),
                    if (reason == AppleLyricsReason.NETWORK) LyricsState.Offline else LyricsState.Failed, reason, retry)
                unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.WAIT, retry ?: 60L)
                assertEquals(1, f.publicCalls.size)
                assertEquals(0, f.apiCalls.size)
            }
        }
        val providerTimeout = Fixture(this)
        providerTimeout.publicAnswer = { awaitCancellation() }
        val start = testScheduler.currentTime
        unavailable(providerTimeout.client.connect(USER_TOKEN), AppleLyricsReason.TIMEOUT)
        assertEquals(30_000L, testScheduler.currentTime - start)
        assertEquals(1, providerTimeout.calls.size)
        result(providerTimeout.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.WAIT, 60)

        for (error in listOf(IOException(SECRET), IllegalStateException(SECRET), IllegalArgumentException(SECRET))) {
            val f = Fixture(this)
            f.apiAnswer = { throw error }
            val reason = if (error is IOException) AppleLyricsReason.NETWORK else AppleLyricsReason.INVALID
            val state = if (error is IOException) LyricsState.Offline else LyricsState.Failed
            result(f.client.fetch(request(), "gb", USER_TOKEN), state, reason)
            unavailable(f.client.connect(USER_TOKEN), reason)
            assertEquals(1, f.publicCalls.size)
            assertEquals(2, f.apiCalls.size)
        }
        for (code in listOf(201, 204, 301, 302, 400, 404, 408, 418, 500, 502, 504)) {
            val f = Fixture(this)
            f.apiAnswer = { AppleLyricsHttpResponse(code, lyricsBody(WORDS), location = LYRICS) }
            result(f.client.fetch(request(), "gb", USER_TOKEN),
                if (code == 404) LyricsState.Missing else LyricsState.Failed,
                if (code == 404) AppleLyricsReason.NOT_FOUND else AppleLyricsReason.HTTP)
            f.apiAnswer = { AppleLyricsHttpResponse(code, "{bad JSON") }
            unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.HTTP)
            assertEquals(1, f.publicCalls.size)
            assertEquals(2, f.apiCalls.size)
        }
        val refreshFailure = Fixture(this)
        refreshFailure.apiAnswer = { AppleLyricsHttpResponse(401, "") }
        refreshFailure.publicAnswer = { if (refreshFailure.publicCalls.size == 1) ok(PUBLIC_TOKEN) else throw IOException(SECRET) }
        result(refreshFailure.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Offline, AppleLyricsReason.NETWORK)
        assertEquals(2, refreshFailure.publicCalls.size)
        assertEquals(1, refreshFailure.apiCalls.size)
    }

    @Test fun boundsTimeAndPropagatesCancellation() = runTest {
        for (connect in listOf(false, true)) {
            val limit = if (connect) 35_000L else 5_000L
            for (elapsed in listOf(limit - 1, limit, limit + 1)) {
                val f = Fixture(this)
                f.apiAnswer = { delay(elapsed); if (connect) ok(STORE_BODY) else ok(lyricsBody(WORDS)) }
                val start = testScheduler.currentTime
                if (connect) {
                    val actual = f.client.connect(USER_TOKEN)
                    if (elapsed < limit) assertEquals("gb", ready(actual))
                    else unavailable(actual, AppleLyricsReason.TIMEOUT)
                } else {
                    val actual = f.client.fetch(request(), "gb", USER_TOKEN)
                    if (elapsed < limit) synced(actual) else result(actual, LyricsState.Failed, AppleLyricsReason.TIMEOUT)
                }
                assertEquals(minOf(elapsed, limit), testScheduler.currentTime - start)
            }
            for (throwsAfter in listOf(false, true)) {
                val f = Fixture(this)
                f.apiAnswer = {
                    withContext(NonCancellable) { delay(limit + 1) }
                    if (throwsAfter) throw IOException(SECRET)
                    if (connect) ok(STORE_BODY) else ok(lyricsBody(WORDS))
                }
                val start = testScheduler.currentTime
                if (connect) unavailable(f.client.connect(USER_TOKEN), AppleLyricsReason.TIMEOUT)
                else result(f.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.TIMEOUT)
                assertEquals(limit + 1, testScheduler.currentTime - start)
            }
            for (code in listOf(200, 401, 429)) {
                for (throwsAfter in listOf(false, true)) {
                    val f = Fixture(this)
                    f.apiAnswer = {
                        currentCoroutineContext().cancel()
                        if (throwsAfter) throw IOException(SECRET)
                        AppleLyricsHttpResponse(code, if (connect) STORE_BODY else lyricsBody(WORDS))
                    }
                    val cancelled = async {
                        if (connect) f.client.connect(USER_TOKEN) else f.client.fetch(request(), "gb", USER_TOKEN)
                    }
                    assertCancelled(cancelled)
                    assertEquals(2, f.calls.size)
                    // Neither rejected authentication nor a cancelled 429 may mutate client/provider state.
                    f.apiAnswer = { if (it.url == STOREFRONT) ok(STORE_BODY) else ok(lyricsBody(WORDS)) }
                    assertEquals("gb", ready(f.client.connect(USER_TOKEN)))
                    assertEquals(1, f.publicCalls.size)
                    assertEquals(2, f.apiCalls.size)
                }
            }
            for (duringPublic in listOf(false, true)) {
                val f = Fixture(this)
                if (duringPublic) f.publicAnswer = { awaitCancellation() }
                else f.apiAnswer = { awaitCancellation() }
                val pending = async {
                    if (connect) f.client.connect(USER_TOKEN) else f.client.fetch(request(), "gb", USER_TOKEN)
                }
                runCurrent()
                pending.cancel()
                assertCancelled(pending)
                assertEquals(if (duringPublic) 1 else 2, f.calls.size)
            }
            val outer = Fixture(this)
            outer.apiAnswer = { delay(100); ok(STORE_BODY) }
            val start = testScheduler.currentTime
            assertCancelled(async {
                withTimeout(50) {
                    if (connect) outer.client.connect(USER_TOKEN) else outer.client.fetch(request(), "gb", USER_TOKEN)
                }
            })
            assertEquals(50L, testScheduler.currentTime - start)
        }
        val initializing = Fixture(this)
        initializing.publicAnswer = { delay(29_000); ok(PUBLIC_TOKEN) }
        initializing.apiAnswer = { delay(5_999); ok(STORE_BODY) }
        val start = testScheduler.currentTime
        assertEquals("gb", ready(initializing.client.connect(USER_TOKEN)))
        assertEquals(34_999L, testScheduler.currentTime - start)
        val fetching = Fixture(this)
        fetching.publicAnswer = { delay(5_001); ok(PUBLIC_TOKEN) }
        val fetchStart = testScheduler.currentTime
        result(fetching.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.TIMEOUT)
        assertEquals(5_000L, testScheduler.currentTime - fetchStart)
        assertEquals(1, fetching.calls.size)

        val queued = Fixture(this)
        queued.apiAnswer = { delay(10_000); ok(STORE_BODY) }
        val owner = async { queued.client.connect(USER_TOKEN) }
        runCurrent()
        val queueStart = testScheduler.currentTime
        result(queued.client.fetch(request(), "gb", USER_TOKEN), LyricsState.Failed, AppleLyricsReason.TIMEOUT)
        assertEquals(5_000L, testScheduler.currentTime - queueStart)
        assertEquals(2, queued.calls.size)
        assertEquals("gb", ready(owner.await()))

        // Cancel on the return dispatch after parsing a successful response on IO.
        val delegate = StandardTestDispatcher(testScheduler)
        var afterIo: (() -> Unit)? = null
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                delegate.dispatch(context, Runnable { block.run(); afterIo?.invoke() })
            }
        }
        val returned = Fixture(this, io)
        returned.tokens.get()
        val cancelled = async { returned.client.fetch(request(), "gb", USER_TOKEN) }
        afterIo = { cancelled.cancel() }
        assertCancelled(cancelled)
        afterIo = null
        synced(returned.client.fetch(request(), "gb", USER_TOKEN))
        assertEquals(1, returned.publicCalls.size)
        assertEquals(2, returned.apiCalls.size)
    }

    @Test fun doesNotExposeCredentialsOrLyrics() = runTest {
        DiagLog.clearForTest()
        ShadowLog.clear()
        val f = Fixture(this)
        val outputs = mutableListOf<String>()
        outputs += f.client.connect(USER_TOKEN).toString()
        val syntheticLyrics = WORDS.replace("あ😀", LYRIC_SECRET)
        f.apiAnswer = { ok(lyricsBody(syntheticLyrics)) }
        val success = f.client.fetch(request(), "gb", USER_TOKEN)
        assertEquals("$LYRIC_SECRET B", synced(success).single().text)
        assertEquals("AppleLyricsResult(state=Synced, reason=OK, retryAfterSec=null)", success.toString())
        outputs += success.toString()
        for (response in listOf(ok("$SECRET $USER_TOKEN $PUBLIC_TOKEN $LYRIC_SECRET"),
            ok(lyricsBody(syntheticLyrics.dropLast(1))), AppleLyricsHttpResponse(500, LYRIC_SECRET))) {
            f.apiAnswer = { response }
            outputs += f.client.fetch(request(), "gb", USER_TOKEN).toString()
            outputs += f.client.connect(USER_TOKEN).toString()
        }
        for (error in listOf(IOException("$SECRET $USER_TOKEN $PUBLIC_TOKEN $LYRIC_SECRET",
            IllegalArgumentException(SECRET)), IllegalStateException("$SECRET $LYRIC_SECRET"))) {
            f.apiAnswer = { throw error }
            val actual = f.client.fetch(request(), "gb", USER_TOKEN)
            assertEquals(if (error is IOException) AppleLyricsReason.NETWORK else AppleLyricsReason.INVALID, actual.reason)
            outputs += actual.toString()
            outputs += f.client.connect(USER_TOKEN).toString()
        }
        for (reason in AppleLyricsReason.entries) {
            outputs += AppleLyricsResult(LyricsState.Plain(LYRIC_SECRET), reason, 60).toString()
            outputs += AppleConnectResult.Unavailable(reason, 60).toString()
        }
        for (output in outputs + DiagLog.dump() + ShadowLog.getLogs().map { it.msg }) {
            for (secret in listOf(USER_TOKEN, PUBLIC_TOKEN, LYRIC_SECRET, SECRET)) assertFalse(output.contains(secret))
        }
        assertEquals(emptyList<String>(), DiagLog.dump())
        assertTrue(f.calls.none { it.url.contains(USER_TOKEN) || it.url.contains(PUBLIC_TOKEN) })
        assertTrue(f.publicCalls.all { it.headers == mapOf("User-Agent" to APPLE_WEB_USER_AGENT) })
    }

    private class Fixture(scope: TestScope, val io: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler)) {
        var now = NOW
        var elapsed: Long? = null
        val calls = mutableListOf<Call>()
        val apiCalls get() = calls.filter { it.url.startsWith(API) }
        val publicCalls get() = calls.filter { !it.url.startsWith(API) }
        var publicAnswer: suspend (Call) -> AppleLyricsHttpResponse = { ok(PUBLIC_TOKEN) }
        var apiAnswer: suspend (Call) -> AppleLyricsHttpResponse = {
            if (it.url == STOREFRONT) ok(STORE_BODY) else ok(lyricsBody(WORDS))
        }
        private val transport = AppleLyricsTransport { url, headers, maxBytes ->
            assertSame(io, currentCoroutineContext()[ContinuationInterceptor])
            val call = Call(url, headers.toMap(), maxBytes)
            calls.add(call)
            if (url.startsWith(API)) apiAnswer(call) else publicAnswer(call)
        }
        val tokens = AppleWebTokenProvider(transport, { now }, io, monotonicClock = { elapsed ?: now })
        val client = AppleLyricsClient(transport, tokens, { now }, io, monotonicClock = { elapsed ?: now })
    }

    private class Call(val url: String, val headers: Map<String, String>, val maxBytes: Int)

    private companion object {
        const val API = "https://amp-api.music.apple.com"
        const val PAGE = "https://music.apple.com/us/browse"
        const val ASSET = "https://music.apple.com/index.js"
        const val STOREFRONT = "$API/v1/me/storefront"
        const val LYRICS = "$API/v1/catalog/gb/songs/123/syllable-lyrics"
        const val NOW = 1_000_000L
        const val TOKEN_LIMIT = 16 * 1024
        const val API_LIMIT = 2 * 1024 * 1024
        const val TTML_LIMIT = 512 * 1024
        const val USER_TOKEN = "SYNTHETIC_PRIVATE_USER_TOKEN"
        const val SECRET = "SYNTHETIC_EXCEPTION_DETAIL"
        const val LYRIC_SECRET = "SYNTHETIC_LYRIC_CONTENT"
        const val STORE_BODY = """{"data":[{"id":"gb"}]}"""
        const val WORDS = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div begin="1" end="3"><p begin="1" end="2"><span begin="1">あ😀</span> <span begin="1.5">B</span></p></div></body></tt>"""
        val PUBLIC_TOKEN = jwt("initial")

        fun request(id: String? = "123", title: String = "Synthetic title", artist: String = "Synthetic artist",
            duration: Long? = 60) = LyricsRequest(LyricsQuery(title, artist, null, duration), LyricsIdentity(appleMusicId = id))

        fun jwt(marker: String): String {
            fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
            return encode("""{"alg":"ES256","typ":"JWT"}""") + "." +
                encode("""{"exp":9223372036854775807,"dummy":"$marker"}""") + ".SYNTHETIC_SIGNATURE"
        }

        fun ok(body: String) = AppleLyricsHttpResponse(200, body)
        fun lyricsBody(xml: String) = JsonObject(mapOf("data" to JsonArray(listOf(
            JsonObject(mapOf("attributes" to JsonObject(mapOf("ttml" to JsonPrimitive(xml))))),
        )))).toString()

        fun padding(bytes: Int): String {
            require(bytes >= 0)
            return "é".repeat(bytes / 2) + "x".repeat(bytes % 2)
        }

        fun paddedJson(body: String, bytes: Int): String {
            val prefix = body.dropLast(1) + ",\"padding\":\""
            val suffix = "\"}"
            return prefix + padding(bytes - prefix.toByteArray(Charsets.UTF_8).size - suffix.length) + suffix
        }

        fun paddedTtml(bytes: Int): String {
            val pad = padding(bytes - WORDS.toByteArray(Charsets.UTF_8).size - 7)
            return WORDS.replace("<body>", "<!--$pad--><body>")
        }

        fun ready(actual: AppleConnectResult): String {
            assertTrue("expected Ready, got $actual", actual is AppleConnectResult.Ready)
            return (actual as AppleConnectResult.Ready).storefront
        }

        fun unavailable(actual: AppleConnectResult, reason: AppleLyricsReason, retry: Long? = null) {
            assertTrue("expected Unavailable, got $actual", actual is AppleConnectResult.Unavailable)
            actual as AppleConnectResult.Unavailable
            assertEquals(reason, actual.reason)
            assertEquals(retry, actual.retryAfterSec)
        }

        fun result(actual: AppleLyricsResult, state: LyricsState, reason: AppleLyricsReason, retry: Long? = null) {
            assertEquals(state, actual.state)
            assertEquals(reason, actual.reason)
            assertEquals(retry, actual.retryAfterSec)
        }

        fun synced(actual: AppleLyricsResult): List<LyricLine> {
            assertEquals(AppleLyricsReason.OK, actual.reason)
            assertNull(actual.retryAfterSec)
            assertTrue("expected Synced, got $actual", actual.state is LyricsState.Synced)
            return (actual.state as LyricsState.Synced).lines
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
