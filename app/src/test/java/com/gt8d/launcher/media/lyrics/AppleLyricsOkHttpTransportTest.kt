package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class AppleLyricsOkHttpTransportTest {
    @Test fun rejectsUnsafeRequestsBeforeNewCall() = runBlocking {
        data class Input(val url: String = API_URL, val headers: Map<String, String> = emptyMap(), val limit: Int = 1024)
        val inputs = listOf(
            Input("https://example.test/$SECRET"),
            Input("https://music.apple.com.evil.test/$SECRET"),
            Input("https://amp-api.music.apple.com.evil.test/$SECRET"),
            Input("https://sub.music.apple.com/$SECRET"),
            Input("https://music.apple.com./$SECRET"),
            Input("http://music.apple.com/$SECRET"),
            Input("http://amp-api.music.apple.com/$SECRET"),
            Input("https://$SECRET@music.apple.com/"),
            Input("https://user:$SECRET@amp-api.music.apple.com/"),
            Input("https://music.apple.com:80/$SECRET"),
            Input("https://amp-api.music.apple.com:444/$SECRET"),
            Input("https://music.apple.com/$SECRET#fragment"),
            Input("https://amp-api.music.apple.com/$SECRET#"),
            Input("https://music.apple.com/%zz$SECRET"),
            Input("https://music.apple.com/bad uri/$SECRET"),
            Input("https://[broken/$SECRET"),
            Input("/$SECRET"),
            Input(WEB_URL, mapOf("Authorization" to SECRET)),
            Input(WEB_URL, mapOf("aUtHoRiZaTiOn" to SECRET)),
            Input(WEB_URL, mapOf("media-user-token" to SECRET)),
            Input(WEB_URL, mapOf("MEDIA-USER-TOKEN" to SECRET)),
            Input(WEB_URL, mapOf("Cookie" to SECRET)),
            Input(headers = mapOf("cOoKiE" to SECRET)),
            Input(headers = mapOf("X-$SECRET\rInjected" to "value")),
            Input(headers = mapOf("X-$SECRET\nInjected" to "value")),
            Input(headers = mapOf("User-Agent" to "$SECRET\rInjected")),
            Input(headers = mapOf("User-Agent" to "$SECRET\nInjected")),
            Input(headers = mapOf("Cookie " to SECRET)),
            Input(headers = mapOf("Authorization\t" to SECRET)),
            Input(headers = mapOf("" to SECRET)),
            Input(headers = linkedMapOf("User-Agent" to SECRET, "user-agent" to SECRET)),
            Input(headers = linkedMapOf("Authorization" to SECRET, "AUTHORIZATION" to SECRET)),
            Input(headers = linkedMapOf("Accept-Encoding" to "identity", "accept-encoding" to "identity")),
            Input(headers = mapOf("Accept-Encoding" to "gzip")),
            Input(headers = mapOf("accept-encoding" to "identity, gzip")),
            Input(limit = 0), Input(limit = -1), Input(limit = MAX_BYTES + 1), Input(limit = Int.MAX_VALUE),
        )
        val calls = AtomicInteger()
        val transport = AppleLyricsOkHttpTransport(Call.Factory {
            calls.incrementAndGet()
            throw AssertionError("unsafe request reached newCall")
        })
        inputs.forEachIndexed { index, input ->
            try {
                transport.get(input.url, input.headers, input.limit)
                fail("unsafe request $index was accepted")
            } catch (e: IllegalArgumentException) {
                assertEquals("Invalid Apple lyrics request", e.message)
                assertRedacted(e)
            }
            assertEquals(0, calls.get())
        }
        // These pass the unchanged common policy but OkHttp's Builder rejects them.
        for (value in listOf("$SECRET\u0000", "$SECRET\u007f", "$SECRET\u0100")) {
            try {
                transport.get(API_URL, mapOf("X-Test" to value), 1)
                fail("invalid builder header was accepted")
            } catch (e: IOException) {
                assertEquals("Apple lyrics request failed", e.message)
                assertRedacted(e)
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun productionClientKeepsStandardSecurityAndDisablesHttpAutomation() {
        val client = AppleLyricsOkHttpTransport.createClient()
        assertTrue(client.fastFallback)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(5000, client.connectTimeoutMillis)
        assertEquals(5000, client.readTimeoutMillis)
        assertEquals(5000, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertSame(Dns.SYSTEM, client.dns)
        assertNull(client.proxy)
        assertSame(BASE_CLIENT.proxySelector, client.proxySelector)
        assertSame(BASE_CLIENT.hostnameVerifier, client.hostnameVerifier)
        assertEquals(BASE_CLIENT.sslSocketFactory.javaClass, client.sslSocketFactory.javaClass)
        assertArrayEquals(BASE_CLIENT.x509TrustManager!!.acceptedIssuers, client.x509TrustManager!!.acceptedIssuers)
        assertEquals(BASE_CLIENT.certificatePinner, client.certificatePinner)
        assertEquals(BASE_CLIENT.connectionSpecs, client.connectionSpecs)
        assertEquals(BASE_CLIENT.protocols, client.protocols)
        assertEquals(0, client.interceptors.size)
        assertEquals(1, client.networkInterceptors.size)
        assertSame(EventListener.NONE, client.eventListenerFactory.create(client.newCall(Request.Builder().url(API_URL).build())))
    }

    @Test fun readsUtf8WithinLimitWithSafeHeadersOnIo() = runBlocking {
        data class BodyCase(val bytes: ByteArray, val limit: Int, val text: String, val oversized: Boolean)
        val text = "あ🎵é\n".repeat(4)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val cases = listOf(
            BodyCase(bytes, bytes.size, text, false),
            BodyCase(bytes, bytes.size - 1, "", true),
            BodyCase(bytes + ByteArray(100), bytes.size - 1, "", true),
            BodyCase(byteArrayOf(65), 1, "A", false),
            BodyCase(byteArrayOf(), MAX_BYTES, "", false),
            BodyCase(ByteArray(9000) { 66 }, 9000, "B".repeat(9000), false),
            BodyCase(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 65), 4, "\uFEFFA", false),
        )
        for (case in cases) {
            for (url in listOf(WEB_URL, "https://amp-api.music.apple.com:443/lyrics?secret=$SECRET")) {
                val input = TrackingInput(case.bytes, maxChunk = 3)
                val body = TestBody(input)
                val factory = Factory(body)
                val caller = Thread.currentThread()
                factory.onNewCall = { assertNotSame(caller, Thread.currentThread()) }
                factory.onExecute = { assertNotSame(caller, Thread.currentThread()) }
                val headers = linkedMapOf("User-Agent" to "test-agent")
                if (url != WEB_URL) {
                    headers["aUtHoRiZaTiOn"] = "Bearer $SECRET"
                    headers["Media-User-Token"] = SECRET
                    headers["aCcEpT-EnCoDiNg"] = "IDENTITY"
                }
                val response = AppleLyricsOkHttpTransport(factory).get(url, headers, case.limit)
                assertEquals(200, response.code)
                assertEquals(case.text, response.body)
                assertEquals(case.oversized, response.tooLarge)
                assertNull(response.retryAfterSec)
                assertNull(response.location)
                assertEquals(minOf(case.bytes.size, case.limit + 1), input.bytesRead)
                assertEquals(if (case.oversized) 0 else 1, input.eofReads)
                var received = 0
                input.reads.forEach { (requested, count) ->
                    assertTrue(requested > 0)
                    assertTrue(requested <= minOf(8192, case.limit - received + 1))
                    if (count > 0) received += count
                }
                assertEquals(1, body.closes.get())
                assertEquals(1, input.closes.get())
                assertEquals(1, body.sourceRequests.get())
                val call = factory.call.get()!!
                assertEquals(0, call.cancels.get())
                assertEquals(1, call.executes.get())
                assertEquals("GET", call.request().method)
                val expected = listOf("Accept-Encoding" to "identity") +
                    headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }.toList()
                assertEquals(expected, call.request().headers.toList())
                assertNull(call.request().header("Cookie"))
            }
        }
    }

    @Test fun syntheticNon200NeverReadsBodyAndRestrictsLocation() = runBlocking {
        val location = "http://unvalidated.example.test/$SECRET#fragment"
        for (url in listOf(WEB_URL, API_URL)) {
            for (code in listOf(301, 302, 303, 307, 308, 300, 304, 305, 204, 401, 404, 408, 429, 500, 503)) {
                val input = TrackingInput(SECRET.toByteArray())
                val body = TestBody(input)
                val factory = Factory(body, code, mapOf("Location" to location, "Retry-After" to "120"))
                val response = AppleLyricsOkHttpTransport(factory).get(url, emptyMap(), 1)
                assertEquals(code, response.code)
                assertEquals("", response.body)
                assertFalse(response.tooLarge)
                assertEquals(120L, response.retryAfterSec)
                val publicRedirect = url == WEB_URL && code in setOf(301, 302, 303, 307, 308)
                assertEquals(if (publicRedirect) location else null, response.location)
                assertEquals(0, body.sourceRequests.get())
                assertTrue(input.reads.isEmpty())
                assertEquals(1, body.closes.get())
                assertEquals(1, input.closes.get())
                assertEquals(1, factory.call.get()!!.executes.get())
                assertFalse(response.toString().contains(SECRET))
            }
        }
    }

    @Test fun preservesRetryAfterDatesOverflowAndRounding() = runBlocking {
        data class RetryCase(val header: String?, val now: Long, val expected: Long?)
        val epoch = "Thu, 01 Jan 1970 00:00:00 GMT"
        val future = "Thu, 01 Jan 1970 00:02:00 GMT"
        val cases = listOf(
            RetryCase(null, 0, null), RetryCase("0", 0, 0), RetryCase("120", 0, 120), RetryCase(" 120\t", 0, 120),
            RetryCase(Long.MAX_VALUE.toString(), Long.MIN_VALUE, Long.MAX_VALUE),
            RetryCase("-1", 0, null), RetryCase("-0", 0, null), RetryCase("+1", 0, null),
            RetryCase("9223372036854775808", 0, null), RetryCase("99999999999999999999999999999", 0, null),
            RetryCase("", 0, null), RetryCase("1.5", 0, null), RetryCase("1e3", 0, null), RetryCase(SECRET, 0, null),
            RetryCase(future, 0, 120), RetryCase(future, 1, 120), RetryCase(future, 119_001, 1),
            RetryCase(future, 120_000, 0), RetryCase(future, 120_001, 0), RetryCase(epoch, -1, 1),
            RetryCase(epoch, Long.MIN_VALUE, 9_223_372_036_854_776L), RetryCase(epoch, Long.MAX_VALUE, 0),
            RetryCase("Tue, 30 Feb 2021 00:00:00 GMT", 0, null), RetryCase("Fri, 01 Jan 1970 00:00:00 GMT", 0, null),
            RetryCase("Thu, 01 Jan 1970 00:02:00 GMT $SECRET", 0, null),
        )
        for (case in cases) {
            for (code in listOf(200, 429)) {
                val body = TestBody(TrackingInput(byteArrayOf()))
                val factory = Factory(body, code, case.header?.let { mapOf("Retry-After" to it) } ?: emptyMap())
                val response = AppleLyricsOkHttpTransport(factory, clock = { case.now }).get(API_URL, emptyMap(), 1)
                assertEquals(case.expected, response.retryAfterSec)
                assertEquals(code, response.code)
                assertEquals(1, body.closes.get())
            }
        }
    }

    @Test fun redactsFailuresAndReleasesCallsAndResponses() = runBlocking {
        for (phase in listOf("newCall", "execute", "clock", "source", "read")) {
            val failure = IOException("$SECRET: $phase", IOException(SECRET)).apply { addSuppressed(IOException(SECRET)) }
            val input = TrackingInput("private body $SECRET".toByteArray(), maxChunk = 2).apply {
                if (phase == "read") { readFailure = failure; failAfterBytes = 2 }
                closeFailure = IOException(SECRET)
            }
            val body = TestBody(input)
            if (phase == "source") body.onSource = { throw failure }
            val factory = Factory(body, headers = mapOf("Retry-After" to "Thu, 01 Jan 1970 00:02:00 GMT"))
            if (phase == "newCall") factory.onNewCall = { throw failure }
            if (phase == "execute") factory.onExecute = { throw failure }
            factory.onCancel = { throw IOException(SECRET) }
            try {
                AppleLyricsOkHttpTransport(factory, clock = { if (phase == "clock") throw failure else 0 })
                    .get(API_URL, mapOf("Authorization" to SECRET), 1024)
                fail("injected failure was lost")
            } catch (e: IOException) {
                assertEquals("Apple lyrics request failed", e.message)
                assertRedacted(e)
            }
            assertEquals(if (phase == "newCall") 0 else 1, factory.call.get()!!.cancels.get())
            assertEquals(if (phase in listOf("newCall", "execute")) 0 else 1, body.closes.get())
            if (phase == "read") assertEquals(2, input.bytesRead)
        }
        val body = TestBody(TrackingInput(SECRET.toByteArray()).apply { closeFailure = IOException(SECRET) })
        val response = AppleLyricsOkHttpTransport(Factory(body)).get(API_URL, emptyMap(), 1024)
        assertEquals(SECRET, response.body)
        assertEquals("AppleLyricsHttpResponse(code=200, tooLarge=false)", response.toString())
        assertEquals(1, body.closes.get())
    }

    @Test fun explicitCancellationSurvivesEveryExceptionBoundary() = runBlocking {
        for (phase in listOf("newCall", "execute", "clock", "source", "read", "close", "cancel")) {
            val cancellation = CancellationException("injected cancellation")
            val input = TrackingInput(byteArrayOf(65)).apply {
                if (phase == "read") readFailure = cancellation
                if (phase == "close") closeFailure = cancellation
                if (phase == "cancel") readFailure = IOException(SECRET)
            }
            val body = TestBody(input)
            if (phase == "source") body.onSource = { throw cancellation }
            val factory = Factory(body, headers = mapOf("Retry-After" to "Thu, 01 Jan 1970 00:02:00 GMT"))
            if (phase == "newCall") factory.onNewCall = { throw cancellation }
            if (phase == "execute") factory.onExecute = { throw cancellation }
            if (phase == "cancel") factory.onCancel = { throw cancellation }
            try {
                AppleLyricsOkHttpTransport(factory, clock = { if (phase == "clock") throw cancellation else 0 })
                    .get(API_URL, emptyMap(), 1)
                fail("CancellationException was lost at $phase")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }
            assertEquals(if (phase in listOf("newCall", "execute")) 0 else 1, body.closes.get())
            assertEquals(if (phase == "newCall") 0 else 1, factory.call.get()!!.cancels.get())
        }
    }

    @Test(timeout = 30_000) fun cancellationClosesLateResourcesAndUnblocksWaits() = runBlocking {
        for (phase in listOf("newCall", "execute", "source", "read", "close")) {
            val started = CountDownLatch(1)
            val released = CountDownLatch(1)
            val cancellation = CancellationException("test cancellation")
            val block: () -> Unit = { started.countDown(); awaitLatch(released) }
            val input = TrackingInput(byteArrayOf(65)).apply {
                if (phase == "read") onRead = { block(); throw IOException(SECRET) }
                if (phase == "close") onClose = { block(); throw IOException(SECRET) }
            }
            val body = TestBody(input)
            if (phase == "source") body.onSource = block
            val factory = Factory(body)
            if (phase == "newCall") factory.onNewCall = block
            if (phase == "execute") factory.onExecute = block
            factory.onCancel = { released.countDown() }
            val returned = AtomicBoolean()
            val job = async(Dispatchers.IO) {
                AppleLyricsOkHttpTransport(factory).get(API_URL, emptyMap(), 1).also { returned.set(true) }
            }
            try {
                awaitLatch(started)
                job.cancel(cancellation)
                if (phase == "newCall") released.countDown()
                assertSame(cancellation, awaitCancellation(job))
                assertFalse(returned.get())
                assertEquals(1, factory.call.get()!!.cancels.get())
                assertEquals(if (phase == "newCall") 0 else 1, body.closes.get())
                assertEquals(if (phase == "newCall") 0 else 1, input.closes.get())
                if (phase in listOf("newCall", "execute", "source")) assertTrue(input.reads.isEmpty())
            } finally {
                job.cancel()
                released.countDown()
                withTimeout(5000) { job.join() }
            }
        }
    }

    @Test(timeout = 15_000) fun cancellationBeforeCallAndAtEofOrCloseNeverReturnsResult() = runBlocking {
        val cancellation = CancellationException("test cancellation")
        val factory = Factory(TestBody(TrackingInput(byteArrayOf())))
        val entered = AtomicBoolean()
        val preCancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(cancellation)
            entered.set(true)
            AppleLyricsOkHttpTransport(factory).get(API_URL, emptyMap(), 1)
            fail("pre-cancelled call returned")
        }
        withTimeout(5000) { preCancelled.join() }
        assertTrue(entered.get())
        assertTrue(preCancelled.isCancelled)
        assertEquals(0, factory.calls.get())
        for (phase in listOf("eof", "close")) {
            lateinit var job: Deferred<AppleLyricsHttpResponse>
            val input = TrackingInput(byteArrayOf(65))
            val body = TestBody(input)
            val finishingFactory = Factory(body)
            val cancel: () -> Unit = { job.cancel(cancellation) }
            if (phase == "eof") input.onEof = cancel else input.onClose = cancel
            job = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                AppleLyricsOkHttpTransport(finishingFactory).get(API_URL, emptyMap(), 1)
            }
            job.start()
            assertSame(cancellation, awaitCancellation(job))
            assertEquals(1, body.closes.get())
            assertEquals(1, input.closes.get())
            assertEquals(1, finishingFactory.call.get()!!.cancels.get())
        }
    }

    @Test(timeout = 15_000) fun cancellationOnReturnDispatchWinsOverCompletedResponse() = runBlocking {
        val queued = CountDownLatch(1)
        val queue = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue.add(block)
                queued.countDown()
            }
        }
        val body = TestBody(TrackingInput(byteArrayOf(65)))
        val factory = Factory(body)
        val returned = AtomicBoolean()
        val job = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            AppleLyricsOkHttpTransport(factory).get(API_URL, emptyMap(), 1).also { returned.set(true) }
        }
        try {
            awaitLatch(queued)
            assertEquals(1, body.closes.get())
            val cancellation = CancellationException("return dispatch cancellation")
            job.cancel(cancellation)
            queue.poll(5, TimeUnit.SECONDS)!!.run()
            assertSame(cancellation, awaitCancellation(job))
            assertFalse(returned.get())
            assertEquals(1, body.closes.get())
        } finally {
            job.cancel()
            while (queue.isNotEmpty()) queue.poll()?.run()
            withTimeout(5000) { job.join() }
        }
    }

    @Test(timeout = 30_000) fun realOkHttpDoesNotRetry503408401OrRedirect() = runBlocking {
        Loopback().use { fixture ->
            val cases = listOf(503 to "0", 408 to "0", 401 to "120", 302 to "0", 307 to "0",
                503 to "9223372036854775808", 429 to "Thu, 01 Jan 1970 00:02:00 GMT")
            var requests = 0
            // Verify one-origin reuse before the error cases also connect to the second host:
            // HTTP/1.1 keeps a separate pooled connection for each origin.
            repeat(2) {
                fixture.server.enqueue(MockResponse.Builder().body("あ🎵")
                    .addHeader("Set-Cookie", "session=$SECRET").addHeader("Cache-Control", "max-age=3600").build())
                assertEquals("あ🎵", fixture.transport.get(WEB_URL, emptyMap(), 7).body)
                assertEquals(++requests, fixture.server.requestCount)
                assertNull(fixture.server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Cookie"])
            }
            assertEquals(1, fixture.client.connectionPool.connectionCount())
            assertEquals(1, fixture.client.connectionPool.idleConnectionCount())
            for (url in listOf(WEB_URL, API_URL)) {
                for ((code, retry) in cases) {
                    fixture.server.enqueue(MockResponse.Builder().code(code)
                        .addHeader("Retry-After", retry).addHeader("Location", "/next?secret=$SECRET")
                        .addHeader("Set-Cookie", "session=$SECRET").body(SECRET).build())
                    val response = fixture.transport.get(url, emptyMap(), 1)
                    requests++
                    assertEquals(code, response.code)
                    assertEquals(when (retry) {
                        "0" -> 0L; "120", "Thu, 01 Jan 1970 00:02:00 GMT" -> 120L; else -> null
                    }, response.retryAfterSec)
                    assertEquals("", response.body)
                    assertFalse(response.tooLarge)
                    assertEquals(if (url == WEB_URL && code in setOf(302, 307)) "/next?secret=$SECRET" else null, response.location)
                    assertEquals(requests, fixture.server.requestCount)
                    val recorded = fixture.server.takeRequest(5, TimeUnit.SECONDS)!!
                    assertEquals("GET", recorded.method)
                    assertEquals("identity", recorded.headers["Accept-Encoding"])
                    assertNull(recorded.headers["Cookie"])
                }
            }
        }
    }

    @Test(timeout = 20_000) fun fastFallbackConnectsIpv4WhileIpv6IsStillBlockedAndClosesLoser() = runBlocking {
        val sockets = RacingSocketFactory()
        val ipv6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
        Loopback(sockets, listOf(ipv6, IPV4)).use { fixture ->
            fixture.server.enqueue(MockResponse(body = "ok"))
            val job = async(Dispatchers.IO) { fixture.transport.get(WEB_URL, emptyMap(), 2) }
            try {
                awaitLatch(sockets.ipv6Started)
                awaitLatch(sockets.ipv4Connected)
                // The IPv6 connect is released only by loser cancellation, never by elapsed time.
                val response = withTimeout(5000) { job.await() }
                assertEquals("ok", response.body)
                assertTrue(sockets.ipv4BeatIpv6.get())
                awaitLatch(sockets.ipv6Closed)
                awaitLatch(sockets.ipv6Exited)
                assertTrue(sockets.ipv6Socket.get()!!.isClosed)
                assertEquals(1, sockets.ipv6Attempts.get())
                assertEquals(1, sockets.ipv4Attempts.get())
                assertEquals(1, fixture.server.requestCount)
                assertFalse(fixture.client.retryOnConnectionFailure)
            } finally {
                job.cancel()
                sockets.releaseIpv6.countDown()
                withTimeout(5000) { job.join() }
            }
        }
    }

    private class TrackingInput(bytes: ByteArray, private val maxChunk: Int = Int.MAX_VALUE) : ByteArrayInputStream(bytes) {
        val closes = AtomicInteger()
        val reads = mutableListOf<Pair<Int, Int>>()
        var bytesRead = 0
        var eofReads = 0
        var readFailure: Exception? = null
        var failAfterBytes = 0
        var closeFailure: Exception? = null
        var onRead: () -> Unit = {}
        var onEof: () -> Unit = {}
        var onClose: () -> Unit = {}
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            onRead()
            if (bytesRead >= failAfterBytes) readFailure?.let { throw it }
            val count = super.read(bytes, offset, minOf(length, maxChunk))
            reads += length to count
            if (count == -1) { eofReads++; onEof() } else bytesRead += count
            return count
        }
        override fun close() {
            closes.incrementAndGet()
            onClose()
            super.close()
            closeFailure?.let { throw it }
        }
    }

    private class TestBody(private val input: InputStream) : ResponseBody() {
        val closes = AtomicInteger()
        val sourceRequests = AtomicInteger()
        var onSource: () -> Unit = {}
        // BufferedSource is sealed in Okio 3.16.4. Keep the instrumented InputStream
        // behind a JVM proxy so the bounded-read assertions still see every read.
        private val buffered = java.lang.reflect.Proxy.newProxyInstance(
            BufferedSource::class.java.classLoader,
            arrayOf(BufferedSource::class.java),
        ) { _, method, _ ->
            if (method.name != "inputStream") throw AssertionError("unexpected BufferedSource access")
            input
        } as BufferedSource
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource { sourceRequests.incrementAndGet(); onSource(); return buffered }
        override fun close() { closes.incrementAndGet(); input.close() }
    }

    private class FakeCall(private val request: Request, private val action: () -> Response) :
        Call by BASE_CLIENT.newCall(request) {
        val cancels = AtomicInteger()
        val executes = AtomicInteger()
        var onCancel: () -> Unit = {}
        override fun request() = request
        override fun execute(): Response { executes.incrementAndGet(); return action() }
        override fun enqueue(responseCallback: Callback) = throw AssertionError("expected execute on IO")
        override fun cancel() { cancels.incrementAndGet(); onCancel() }
        override fun isCanceled() = cancels.get() != 0
        override fun isExecuted() = executes.get() != 0
    }

    private class Factory(
        private val body: TestBody,
        private val code: Int = 200,
        private val headers: Map<String, String> = emptyMap(),
    ) : Call.Factory {
        val calls = AtomicInteger()
        val call = AtomicReference<FakeCall?>()
        var onNewCall: () -> Unit = {}
        var onExecute: () -> Unit = {}
        var onCancel: () -> Unit = {}
        override fun newCall(request: Request): Call {
            calls.incrementAndGet()
            val result = FakeCall(request) {
                onExecute()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                    .headers(Headers.Builder().apply { headers.forEach { (k, v) -> add(k, v) } }.build())
                    .body(body).build()
            }.also { it.onCancel = { onCancel() } }
            call.set(result)
            onNewCall()
            return result
        }
    }

    private class Loopback(socketFactory: SocketFactory? = null, addresses: List<InetAddress> = listOf(IPV4)) : AutoCloseable {
        val server = MockWebServer()
        val client: OkHttpClient
        val transport: AppleLyricsOkHttpTransport
        init {
            val certificate = HeldCertificate.Builder().commonName("localhost")
                .addSubjectAlternativeName("music.apple.com").addSubjectAlternativeName("amp-api.music.apple.com").build()
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            server.useHttps(serverCertificates.sslSocketFactory())
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.start(IPV4, 0)
            client = AppleLyricsOkHttpTransport.createClient { 0 }.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .dns { hostname ->
                    check(hostname in setOf("music.apple.com", "amp-api.music.apple.com"))
                    check(addresses.all { it.isLoopbackAddress })
                    addresses
                }
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .apply { if (socketFactory != null) socketFactory(socketFactory) }
                .build()
            transport = AppleLyricsOkHttpTransport(Call.Factory { validated ->
                check(validated.url.port == 443)
                client.newCall(validated.newBuilder().url(validated.url.newBuilder().port(server.port).build()).build())
            }, clock = { 0 })
        }
        override fun close() {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }
    }

    private class RacingSocketFactory : SocketFactory() {
        val ipv6Started = CountDownLatch(1)
        val ipv4Connected = CountDownLatch(1)
        val ipv6Closed = CountDownLatch(1)
        val ipv6Exited = CountDownLatch(1)
        val releaseIpv6 = CountDownLatch(1)
        val ipv4BeatIpv6 = AtomicBoolean()
        val ipv6Socket = AtomicReference<Socket?>()
        val ipv6Attempts = AtomicInteger()
        val ipv4Attempts = AtomicInteger()
        override fun createSocket(): Socket = object : Socket() {
            private var ipv6 = false
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val address = (endpoint as InetSocketAddress).address
                check(address.isLoopbackAddress)
                ipv6 = address is Inet6Address
                if (ipv6) {
                    ipv6Attempts.incrementAndGet()
                    ipv6Socket.set(this)
                    ipv6Started.countDown()
                    try {
                        awaitLatch(releaseIpv6)
                        throw SocketException("synthetic IPv6 closed")
                    } finally {
                        ipv6Exited.countDown()
                    }
                } else {
                    ipv4Attempts.incrementAndGet()
                    awaitLatch(ipv6Started)
                    ipv4BeatIpv6.set(releaseIpv6.count == 1L)
                    super.connect(endpoint, timeout)
                    ipv4Connected.countDown()
                }
            }
            override fun close() {
                super.close()
                if (ipv6) {
                    ipv6Closed.countDown()
                    releaseIpv6.countDown()
                }
            }
        }
        override fun createSocket(host: String, port: Int): Socket = throw AssertionError("unexpected socket overload")
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            throw AssertionError("unexpected socket overload")
        override fun createSocket(host: InetAddress, port: Int): Socket = throw AssertionError("unexpected socket overload")
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            throw AssertionError("unexpected socket overload")
    }

    private suspend fun awaitCancellation(job: Deferred<*>): CancellationException {
        val cause = AtomicReference<Throwable?>()
        val registration = job.invokeOnCompletion { cause.set(it) }
        try {
            withTimeout(5000) { job.join() }
            try { job.await(); throw AssertionError("cancelled response returned") } catch (e: CancellationException) {
                assertTrue(cause.get() is CancellationException)
                assertEquals(cause.get()?.message, e.message)
                return cause.get() as CancellationException
            }
        } finally { registration.dispose() }
    }

    private fun assertRedacted(error: Exception) {
        assertNull(error.cause)
        assertEquals(0, error.suppressed.size)
        assertFalse(error.toString().contains(SECRET))
        assertFalse(error.stackTraceToString().contains(SECRET))
    }

    private companion object {
        const val SECRET = "dummy-apple-lyrics-secret-189"
        const val API_URL = "https://amp-api.music.apple.com/lyrics?secret=$SECRET"
        const val WEB_URL = "https://music.apple.com/lyrics?secret=$SECRET"
        const val MAX_BYTES = 8 * 1024 * 1024
        val BASE_CLIENT = OkHttpClient()
        val IPV4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        fun awaitLatch(latch: CountDownLatch) {
            assertTrue("test synchronization timed out", latch.await(5, TimeUnit.SECONDS))
        }
    }
}
