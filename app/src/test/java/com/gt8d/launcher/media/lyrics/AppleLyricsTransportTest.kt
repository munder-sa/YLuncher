package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AppleLyricsTransportTest {
    @Test fun rejectsUnsafeRequestsBeforeOpen() = runBlocking {
        data class Request(
            val url: String = API_URL,
            val headers: Map<String, String> = emptyMap(),
            val limit: Int = 1024,
        )

        val requests = listOf(
            Request("https://example.test/$SECRET"),
            Request("https://music.apple.com.evil.test/$SECRET"),
            Request("https://amp-api.music.apple.com.evil.test/$SECRET"),
            Request("https://sub.music.apple.com/$SECRET"),
            Request("https://music.apple.com./$SECRET"),
            Request("http://music.apple.com/$SECRET"),
            Request("http://amp-api.music.apple.com/$SECRET"),
            Request("https://$SECRET@music.apple.com/"),
            Request("https://user:$SECRET@amp-api.music.apple.com/"),
            Request("https://music.apple.com:80/$SECRET"),
            Request("https://amp-api.music.apple.com:444/$SECRET"),
            Request("https://music.apple.com/$SECRET#fragment"),
            Request("https://amp-api.music.apple.com/$SECRET#"),
            Request("https://music.apple.com/%zz$SECRET"),
            Request("https://music.apple.com/bad uri/$SECRET"),
            Request("https://[broken/$SECRET"),
            Request("/$SECRET"),
            Request(WEB_URL, mapOf("Authorization" to SECRET)),
            Request(WEB_URL, mapOf("aUtHoRiZaTiOn" to SECRET)),
            Request(WEB_URL, mapOf("media-user-token" to SECRET)),
            Request(WEB_URL, mapOf("MEDIA-USER-TOKEN" to SECRET)),
            Request(WEB_URL, mapOf("Cookie" to SECRET)),
            Request(headers = mapOf("cOoKiE" to SECRET)),
            Request(headers = mapOf("X-$SECRET\rInjected" to "value")),
            Request(headers = mapOf("X-$SECRET\nInjected" to "value")),
            Request(headers = mapOf("User-Agent" to "$SECRET\rInjected")),
            Request(headers = mapOf("User-Agent" to "$SECRET\nInjected")),
            Request(headers = mapOf("Cookie " to SECRET)),
            Request(headers = mapOf("Authorization\t" to SECRET)),
            Request(headers = mapOf("" to SECRET)),
            Request(headers = linkedMapOf("User-Agent" to SECRET, "user-agent" to SECRET)),
            Request(headers = linkedMapOf("Authorization" to SECRET, "AUTHORIZATION" to SECRET)),
            Request(headers = linkedMapOf("Accept-Encoding" to "identity", "accept-encoding" to "identity")),
            Request(headers = mapOf("Accept-Encoding" to "gzip")),
            Request(headers = mapOf("accept-encoding" to "identity, gzip")),
            Request(limit = 0),
            Request(limit = -1),
            Request(limit = MAX_BYTES + 1),
            Request(limit = Int.MAX_VALUE),
        )
        val opens = AtomicInteger()
        val transport = AppleLyricsUrlConnectionTransport(open = {
            opens.incrementAndGet()
            throw AssertionError("unsafe request reached open")
        })
        requests.forEachIndexed { index, request ->
            try {
                transport.get(request.url, request.headers, request.limit)
                fail("unsafe request $index was accepted")
            } catch (e: IllegalArgumentException) {
                assertEquals("Invalid Apple lyrics request", e.message)
                assertRedacted(e)
            }
            assertEquals("unsafe request $index reached open", 0, opens.get())
        }
    }

    @Test fun readsWithinLimitAndDropsOversizedBody() = runBlocking {
        data class BodyCase(val bytes: ByteArray, val limit: Int, val expected: String, val oversized: Boolean)
        val text = "あ🎵é\n".repeat(4)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val cases = listOf(
            BodyCase(bytes, bytes.size, text, false),
            BodyCase(bytes, bytes.size - 1, "", true),
            BodyCase(bytes + ByteArray(100), bytes.size - 1, "", true),
            BodyCase(byteArrayOf('A'.code.toByte()), 1, "A", false),
            BodyCase(byteArrayOf(), MAX_BYTES, "", false),
            BodyCase(ByteArray(9000) { 'B'.code.toByte() }, 9000, "B".repeat(9000), false),
        )
        for ((index, case) in cases.withIndex()) {
            for (url in listOf(WEB_URL, "https://amp-api.music.apple.com:443/lyrics?secret=$SECRET")) {
                val input = TrackingInput(case.bytes, maxChunk = if (index < 3) 3 else Int.MAX_VALUE)
                val connection = Connection(input, responseHeaders = mapOf("Location" to SECRET))
                val headers = linkedMapOf("User-Agent" to "test-agent")
                if (url != WEB_URL) {
                    headers["aUtHoRiZaTiOn"] = "Bearer $SECRET"
                    headers["Media-User-Token"] = SECRET
                    headers["aCcEpT-EnCoDiNg"] = "IDENTITY"
                }
                val callingThread = Thread.currentThread()
                val transport = AppleLyricsUrlConnectionTransport(open = {
                    assertEquals(url, it)
                    assertNotSame(callingThread, Thread.currentThread())
                    connection
                })
                val response = transport.get(url, headers, case.limit)
                assertEquals(200, response.code)
                assertEquals(case.expected, response.body)
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
                assertEquals(1, input.closes.get())
                assertEquals(1, connection.disconnects.get())
                assertEquals(1, connection.inputRequests.get())
                assertEquals(0, connection.errorRequests.get())
                assertEquals("GET", connection.requestMethod)
                assertEquals(5000, connection.connectTimeout)
                assertEquals(5000, connection.readTimeout)
                assertFalse(connection.instanceFollowRedirects)
                assertFalse(connection.useCaches)
                val expectedHeaders = listOf("Accept-Encoding" to "identity") +
                    headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }.toList()
                assertEquals(expectedHeaders, connection.requestHeaders)
            }
        }
    }

    @Test fun neverReadsErrorBodyOrFollowsRedirects() = runBlocking {
        val redirects = setOf(301, 302, 303, 307, 308)
        val location = "http://unvalidated.example.test/$SECRET#fragment"
        for (url in listOf(WEB_URL, API_URL)) {
            for (code in listOf(301, 302, 303, 307, 308, 300, 304, 305, 204, 401, 404, 429, 500)) {
                val input = TrackingInput(SECRET.toByteArray())
                val connection = Connection(input, code, mapOf("Location" to location, "Retry-After" to "120"))
                val response = AppleLyricsUrlConnectionTransport(open = { connection })
                    .get(url, emptyMap(), 1024)
                assertEquals(code, response.code)
                assertEquals("", response.body)
                assertFalse(response.tooLarge)
                assertEquals(120L, response.retryAfterSec)
                val publicRedirect = url == WEB_URL && code in redirects
                assertEquals(if (publicRedirect) location else null, response.location)
                assertEquals(publicRedirect, "Location" in connection.headerRequests)
                assertFalse(connection.instanceFollowRedirects)
                assertEquals(0, connection.inputRequests.get())
                assertEquals(0, connection.errorRequests.get())
                assertEquals(0, input.reads.size)
                assertEquals(0, input.closes.get())
                assertEquals(1, connection.disconnects.get())
            }
        }
    }

    @Test fun parsesRetryAfterWithoutOverflow() = runBlocking {
        data class RetryCase(val header: String?, val now: Long, val expected: Long?)
        val epoch = "Thu, 01 Jan 1970 00:00:00 GMT"
        val future = "Thu, 01 Jan 1970 00:02:00 GMT"
        val cases = listOf(
            RetryCase(null, 0, null),
            RetryCase("0", 0, 0),
            RetryCase("120", 0, 120),
            RetryCase(" 120\t", 0, 120),
            RetryCase(Long.MAX_VALUE.toString(), Long.MIN_VALUE, Long.MAX_VALUE),
            RetryCase("-1", 0, null),
            RetryCase("-0", 0, null),
            RetryCase("+1", 0, null),
            RetryCase("9223372036854775808", 0, null),
            RetryCase("99999999999999999999999999999", 0, null),
            RetryCase("", 0, null),
            RetryCase("1.5", 0, null),
            RetryCase("1e3", 0, null),
            RetryCase(SECRET, 0, null),
            RetryCase(future, 0, 120),
            RetryCase(future, 1, 120),
            RetryCase(future, 119_001, 1),
            RetryCase(future, 120_000, 0),
            RetryCase(future, 120_001, 0),
            RetryCase(epoch, -1, 1),
            RetryCase(epoch, Long.MIN_VALUE, 9_223_372_036_854_776L),
            RetryCase(epoch, Long.MAX_VALUE, 0),
            RetryCase("Tue, 30 Feb 2021 00:00:00 GMT", 0, null),
            RetryCase("Fri, 01 Jan 1970 00:00:00 GMT", 0, null),
            RetryCase("Thu, 01 Jan 1970 00:02:00 GMT $SECRET", 0, null),
        )
        cases.forEachIndexed { index, case ->
            val connection = Connection(
                TrackingInput(byteArrayOf()), 429,
                case.header?.let { mapOf("Retry-After" to it) } ?: emptyMap(),
            )
            val response = AppleLyricsUrlConnectionTransport(open = { connection }, clock = { case.now })
                .get(API_URL, emptyMap(), 1)
            assertEquals("Retry-After case $index", case.expected, response.retryAfterSec)
            assertEquals(0, connection.inputRequests.get())
            assertEquals(0, connection.errorRequests.get())
            assertEquals(1, connection.disconnects.get())
        }
    }

    @Test(timeout = 30_000) fun cancelClosesAndDisconnects() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        val input = object : InputStream() {
            override fun read(): Int {
                started.countDown()
                awaitLatch(released)
                throw IOException(SECRET)
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = read()
            override fun close() {
                closes.incrementAndGet()
                released.countDown()
                throw IOException(SECRET)
            }
        }
        val connection = Connection(input).apply { disconnectFailure = IOException(SECRET) }
        val returned = AtomicBoolean()
        val job = async(Dispatchers.IO) {
            AppleLyricsUrlConnectionTransport(open = { connection }).get(API_URL, emptyMap(), 1024)
                .also { returned.set(true) }
        }
        val cancellation = CancellationException("test cancellation")
        try {
            awaitLatch(started)
            job.cancel(cancellation)
            assertSame(cancellation, awaitCancellation(job))
            assertTrue(job.isCancelled)
            assertFalse(returned.get())
            assertEquals(1, closes.get())
            assertEquals(1, connection.disconnects.get())
        } finally {
            job.cancel()
            released.countDown()
            withTimeout(5000) { job.join() }
        }

        val opens = AtomicInteger()
        val entered = AtomicBoolean()
        val preCancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(cancellation)
            entered.set(true)
            AppleLyricsUrlConnectionTransport(open = {
                opens.incrementAndGet()
                Connection(TrackingInput(byteArrayOf()))
            }).get(API_URL, emptyMap(), 1)
            fail("pre-cancelled request returned")
        }
        withTimeout(5000) { preCancelled.join() }
        assertTrue(entered.get())
        assertTrue(preCancelled.isCancelled)
        assertEquals(0, opens.get())

        // Exercise resources arriving after the cancellation handler has already run.
        for (phase in listOf("open", "responseCode", "inputStream")) {
            val acquiring = CountDownLatch(1)
            val releaseAcquisition = CountDownLatch(1)
            val lateInput = TrackingInput(byteArrayOf(65))
            val lateConnection = Connection(lateInput)
            val block: () -> Unit = {
                acquiring.countDown()
                awaitLatch(releaseAcquisition)
            }
            if (phase == "responseCode") lateConnection.onResponse = block
            if (phase == "inputStream") lateConnection.onInput = block
            lateConnection.onDisconnect = { releaseAcquisition.countDown() }
            val lateReturned = AtomicBoolean()
            val lateJob = async(Dispatchers.IO) {
                AppleLyricsUrlConnectionTransport(open = {
                    if (phase == "open") block()
                    lateConnection
                }).get(API_URL, emptyMap(), 1).also { lateReturned.set(true) }
            }
            try {
                awaitLatch(acquiring)
                lateJob.cancel(cancellation)
                if (phase == "open") releaseAcquisition.countDown()
                assertSame(cancellation, awaitCancellation(lateJob))
                assertFalse(lateReturned.get())
                assertEquals(1, lateConnection.disconnects.get())
                assertEquals(if (phase == "inputStream") 1 else 0, lateInput.closes.get())
                assertEquals(0, lateInput.reads.size)
            } finally {
                lateJob.cancel()
                releaseAcquisition.countDown()
                withTimeout(5000) { lateJob.join() }
            }
        }

        for (phase in listOf("eof", "close", "disconnect")) {
            lateinit var finishing: Deferred<AppleLyricsHttpResponse>
            val endingInput = TrackingInput(byteArrayOf(65))
            val endingConnection = Connection(endingInput)
            val cancel: () -> Unit = { finishing.cancel(cancellation) }
            when (phase) {
                "eof" -> endingInput.onEof = cancel
                "close" -> endingInput.onClose = cancel
                "disconnect" -> endingConnection.onDisconnect = cancel
            }
            finishing = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                AppleLyricsUrlConnectionTransport(open = { endingConnection }).get(API_URL, emptyMap(), 1)
            }
            finishing.start()
            assertSame(cancellation, awaitCancellation(finishing))
            assertEquals(1, endingInput.closes.get())
            assertEquals(1, endingConnection.disconnects.get())
        }
    }

    @Test fun cleansUpAndRedactsFailures() = runBlocking {
        for (phase in listOf("open", "responseCode", "inputStream", "header", "read")) {
            val failure = IOException("$SECRET: $phase", IOException(SECRET)).apply {
                addSuppressed(IOException(SECRET))
            }
            val input = TrackingInput("private body $SECRET".toByteArray(), maxChunk = 2).apply {
                closeFailure = IOException(SECRET)
                if (phase == "read") {
                    readFailure = failure
                    failAfterBytes = 2
                }
            }
            val connection = Connection(input).apply {
                disconnectFailure = IOException(SECRET)
                when (phase) {
                    "responseCode" -> responseFailure = failure
                    "inputStream" -> inputFailure = failure
                    "header" -> headerFailure = failure
                }
            }
            val transport = AppleLyricsUrlConnectionTransport(open = {
                if (phase == "open") throw failure
                connection
            })
            try {
                transport.get(API_URL, mapOf("Authorization" to SECRET), 1024)
                fail("injected failure was lost")
            } catch (e: IOException) {
                assertEquals("Apple lyrics request failed", e.message)
                assertRedacted(e)
            }
            assertEquals(if (phase == "open") 0 else 1, connection.disconnects.get())
            assertEquals(if (phase == "read") 1 else 0, input.closes.get())
            assertEquals(if (phase == "read" || phase == "inputStream") 1 else 0, connection.inputRequests.get())
            assertEquals(0, connection.errorRequests.get())
            if (phase == "read") assertEquals(2, input.bytesRead)
        }

        // Ordinary cleanup errors do not leak, even following a successful response.
        for (phase in listOf("close", "disconnect", "both")) {
            val input = TrackingInput(SECRET.toByteArray()).apply {
                if (phase != "disconnect") closeFailure = IOException(SECRET)
            }
            val connection = Connection(input).apply {
                if (phase != "close") disconnectFailure = IOException(SECRET)
            }
            val response = AppleLyricsUrlConnectionTransport(open = { connection })
                .get(API_URL, emptyMap(), 1024)
            assertEquals(SECRET, response.body)
            assertEquals("AppleLyricsHttpResponse(code=200, tooLarge=false)", response.toString())
            assertEquals(1, input.closes.get())
            assertEquals(1, connection.disconnects.get())
        }

        // Explicit CancellationException instances must survive every failure boundary.
        for (phase in listOf("open", "responseCode", "inputStream", "header", "read", "close", "disconnect")) {
            val cancellation = CancellationException("injected cancellation")
            val input = TrackingInput(byteArrayOf(65)).apply {
                if (phase == "read") readFailure = cancellation
                if (phase == "close") closeFailure = cancellation
            }
            val connection = Connection(input).apply {
                when (phase) {
                    "responseCode" -> responseFailure = cancellation
                    "inputStream" -> inputFailure = cancellation
                    "header" -> headerFailure = cancellation
                    "disconnect" -> disconnectFailure = cancellation
                }
            }
            try {
                AppleLyricsUrlConnectionTransport(open = {
                    if (phase == "open") throw cancellation
                    connection
                }).get(API_URL, emptyMap(), 1)
                fail("CancellationException was lost")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }
            assertEquals(if (phase == "open") 0 else 1, connection.disconnects.get())
            assertEquals(if (phase in listOf("read", "close", "disconnect")) 1 else 0, input.closes.get())
        }

        val response = AppleLyricsHttpResponse(302, SECRET, 120, true, "https://example.test/$SECRET")
        assertEquals("AppleLyricsHttpResponse(code=302, tooLarge=true)", response.toString())
        assertFalse(response.toString().contains(SECRET))
    }

    private class TrackingInput(bytes: ByteArray, private val maxChunk: Int = Int.MAX_VALUE) : ByteArrayInputStream(bytes) {
        val closes = AtomicInteger()
        val reads = mutableListOf<Pair<Int, Int>>()
        var bytesRead = 0
        var eofReads = 0
        var readFailure: Exception? = null
        var failAfterBytes = 0
        var closeFailure: Exception? = null
        var onEof: () -> Unit = {}
        var onClose: () -> Unit = {}

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= failAfterBytes) readFailure?.let { throw it }
            val count = super.read(bytes, offset, minOf(length, maxChunk))
            reads += length to count
            if (count == -1) {
                eofReads++
                onEof()
            } else {
                bytesRead += count
            }
            return count
        }

        override fun close() {
            closes.incrementAndGet()
            onClose()
            super.close()
            closeFailure?.let { throw it }
        }
    }

    private class Connection(
        private val input: InputStream,
        private val code: Int = 200,
        private val responseHeaders: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(URL("https://music.apple.com/")) {
        val disconnects = AtomicInteger()
        val inputRequests = AtomicInteger()
        val errorRequests = AtomicInteger()
        val requestHeaders = mutableListOf<Pair<String, String>>()
        val headerRequests = mutableListOf<String>()
        var responseFailure: Exception? = null
        var inputFailure: Exception? = null
        var headerFailure: Exception? = null
        var disconnectFailure: Exception? = null
        var onResponse: () -> Unit = {}
        var onInput: () -> Unit = {}
        var onDisconnect: () -> Unit = {}

        override fun getResponseCode(): Int {
            onResponse()
            responseFailure?.let { throw it }
            return code
        }

        override fun getInputStream(): InputStream {
            inputRequests.incrementAndGet()
            onInput()
            inputFailure?.let { throw it }
            return input
        }

        override fun getErrorStream(): InputStream {
            errorRequests.incrementAndGet()
            return input
        }

        override fun getHeaderField(name: String): String? {
            headerRequests += name
            headerFailure?.let { throw it }
            return responseHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        override fun setRequestProperty(name: String, value: String) {
            requestHeaders += name to value
            super.setRequestProperty(name, value)
        }

        override fun disconnect() {
            disconnects.incrementAndGet()
            onDisconnect()
            disconnectFailure?.let { throw it }
        }

        override fun connect() = Unit
        override fun usingProxy() = false
    }

    private suspend fun awaitCancellation(job: Deferred<*>): CancellationException {
        // Observe the actual completion cause: await() may copy it for stack recovery.
        val completionCause = AtomicReference<Throwable?>()
        val registration = job.invokeOnCompletion { completionCause.set(it) }
        try {
            withTimeout(5000) { job.join() }
            try {
                job.await()
                throw AssertionError("cancelled response returned successfully")
            } catch (e: CancellationException) {
                val cause = completionCause.get()
                assertTrue("job must complete with cancellation", cause is CancellationException)
                assertEquals(cause?.message, e.message)
                return cause as CancellationException
            }
        } finally {
            registration.dispose()
        }
    }

    private fun awaitLatch(latch: CountDownLatch) {
        assertTrue("test synchronization timed out", latch.await(5, TimeUnit.SECONDS))
    }

    private fun assertRedacted(error: Exception) {
        assertNull(error.cause)
        assertEquals(0, error.suppressed.size)
        assertFalse(error.toString().contains(SECRET))
        assertFalse(error.stackTraceToString().contains(SECRET))
    }

    private companion object {
        const val SECRET = "dummy-apple-lyrics-secret-176a"
        const val API_URL = "https://amp-api.music.apple.com/lyrics?secret=$SECRET"
        const val WEB_URL = "https://music.apple.com/lyrics?secret=$SECRET"
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
