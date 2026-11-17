package com.gt8d.launcher.media

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ArtworkFallbackLoaderTest {
    private val uri = "https://example.test/art.png"

    @Test fun waitsBeforeFetchingAndCancelSkipsFetch() = runTest(StandardTestDispatcher()) {
        var fetches = 0
        var results = 0
        val loader = ArtworkFallbackLoader(this, fetch = { fetches++; null })
        val cancel = loader.request(uri) { results++ }
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(0, fetches)
        assertEquals(0, results)
        cancel.cancel()
        advanceUntilIdle()
        loader.onNetworkAvailable()
        runCurrent()
        assertEquals(0, fetches)
        assertEquals(0, results)
    }

    @Test fun failureRetriesWithBackoffThenOnNetworkAvailable() = runTest(StandardTestDispatcher()) {
        val times = mutableListOf<Long>()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var available = false
        val results = mutableListOf<Bitmap>()
        val callbackThreads = mutableListOf<Thread>()
        val mainThread = Thread.currentThread()
        val loader = ArtworkFallbackLoader(this, fetch = {
            times += testScheduler.currentTime
            if (available) bitmap else null
        })
        loader.request(uri) { results += it; callbackThreads += Thread.currentThread() }
        advanceUntilIdle()
        assertEquals(listOf(2_000L, 3_500L, 7_500L), times)
        assertTrue(results.isEmpty())
        available = true
        loader.onNetworkAvailable()
        runCurrent()
        assertEquals(listOf(2_000L, 3_500L, 7_500L, 7_500L), times)
        assertEquals(listOf(bitmap), results)
        assertEquals(listOf(mainThread), callbackThreads)
        loader.onNetworkAvailable()
        advanceUntilIdle()
        assertEquals(4, times.size)
        assertEquals(1, results.size)
    }

    @Test fun successfulUriIsCachedForNextRequest() = runTest(StandardTestDispatcher()) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var fetches = 0
        val results = mutableListOf<Bitmap>()
        val loader = ArtworkFallbackLoader(this, fetch = { fetches++; bitmap })
        loader.request(uri) { results += it }
        advanceUntilIdle()
        assertEquals(1, fetches)
        assertSame(bitmap, results.single())
        loader.request(uri) { results += it }
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, results.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fetches)
        assertEquals(2, results.size)
        assertSame(bitmap, results[1])
        assertEquals(4_000L, testScheduler.currentTime)
    }

    @Test fun successfulCacheEvictsLeastRecentlyUsedUri() = runTest(StandardTestDispatcher()) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val fetched = mutableListOf<String>()
        val results = mutableListOf<Bitmap>()
        val loader = ArtworkFallbackLoader(this, fetch = { fetched += it; bitmap }, initialDelayMs = 0)
        for (name in listOf("a", "b", "c", "a", "d", "a", "b")) {
            loader.request("https://example.test/$name.png") { results += it }
            runCurrent()
        }
        assertEquals(listOf("a", "b", "c", "d", "b").map { "https://example.test/$it.png" }, fetched)
        assertEquals(7, results.size)
        assertTrue(results.all { it === bitmap })
        assertFalse(bitmap.isRecycled)
    }

    @Test fun exceptionsRetryButCancellationDoesNot() = runTest(StandardTestDispatcher()) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val times = mutableListOf<Long>()
        val results = mutableListOf<Bitmap>()
        val loader = ArtworkFallbackLoader(this, fetch = {
            times += testScheduler.currentTime
            if (times.size < 3) throw IOException("unavailable")
            bitmap
        })
        loader.request(uri) { results += it }
        advanceUntilIdle()
        assertEquals(listOf(2_000L, 3_500L, 7_500L), times)
        assertEquals(listOf(bitmap), results)
        var cancelledFetches = 0
        var cancelledResults = 0
        val cancelledLoader = ArtworkFallbackLoader(this, fetch = {
            cancelledFetches++
            throw CancellationException("cancelled")
        })
        cancelledLoader.request(uri) { cancelledResults++ }
        advanceUntilIdle()
        cancelledLoader.onNetworkAvailable()
        advanceUntilIdle()
        assertEquals(1, cancelledFetches)
        assertEquals(0, cancelledResults)
    }

    @Test fun cancelRemovesUnresolvedAndSuppressesInFlightResult() = runTest(StandardTestDispatcher()) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val pending = CompletableDeferred<Bitmap>()
        var waitForBitmap = false
        var fetches = 0
        var results = 0
        val loader = ArtworkFallbackLoader(this, fetch = {
            fetches++
            if (waitForBitmap) withContext(NonCancellable) { pending.await() } else null
        })
        val unresolved = loader.request(uri) { results++ }
        advanceUntilIdle()
        assertEquals(3, fetches)
        unresolved.cancel()
        loader.onNetworkAvailable()
        runCurrent()
        assertEquals(3, fetches)
        waitForBitmap = true
        val inFlight = loader.request(uri) { results++ }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(4, fetches)
        inFlight.cancel()
        pending.complete(bitmap)
        advanceUntilIdle()
        loader.onNetworkAvailable()
        runCurrent()
        assertEquals(4, fetches)
        assertEquals(0, results)
        loader.request(uri) { results++ }
        advanceUntilIdle()
        assertEquals(5, fetches) // A cancelled result must not enter the success cache.
        assertEquals(1, results)
    }

    @Test fun networkRetriesEachUnresolvedRequestOnceWithoutOverlapping() = runTest(StandardTestDispatcher()) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val pending = CompletableDeferred<Bitmap?>()
        val fetched = mutableListOf<String>()
        var networkRetry = false
        var available = false
        val results = mutableListOf<String>()
        val loader = ArtworkFallbackLoader(this, fetch = {
            fetched += it
            if (available) bitmap else if (networkRetry) pending.await() else null
        })
        val uris = listOf(uri, "https://example.test/other.png")
        uris.forEach { requestUri -> loader.request(requestUri) { results += requestUri } }
        advanceUntilIdle()
        uris.forEach { assertEquals(3, fetched.count { fetchedUri -> fetchedUri == it }) }
        networkRetry = true
        repeat(3) { loader.onNetworkAvailable() }
        runCurrent()
        loader.onNetworkAvailable()
        runCurrent()
        uris.forEach { assertEquals(4, fetched.count { fetchedUri -> fetchedUri == it }) }
        pending.complete(null)
        runCurrent()
        assertTrue(results.isEmpty())
        available = true
        val before = testScheduler.currentTime
        loader.onNetworkAvailable()
        runCurrent()
        assertEquals(before, testScheduler.currentTime)
        uris.forEach { assertEquals(5, fetched.count { fetchedUri -> fetchedUri == it }) }
        assertEquals(uris, results)
        loader.onNetworkAvailable()
        advanceUntilIdle()
        assertEquals(10, fetched.size)
        assertEquals(2, results.size)
    }

    @Test fun httpFetchDownsamplesAndRejectsOversizedBody() = runTest(StandardTestDispatcher()) {
        val source = Bitmap.createBitmap(1_024, 1_024, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use {
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        try {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val url = server.url("/art.png").newBuilder().host("127.0.0.1").build().toString()
                server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
                val bitmap = fetchArtworkBitmap(client, url, maxBytes = bytes.size.toLong())
                assertNotNull(bitmap)
                assertEquals(512, bitmap!!.width)
                assertEquals(512, bitmap.height)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
                assertNull(fetchArtworkBitmap(client, url, maxBytes = bytes.size.toLong() - 1))
                server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(bytes), 1_024).build())
                assertNull(fetchArtworkBitmap(client, url, maxBytes = bytes.size.toLong() - 1))
                server.enqueue(MockResponse.Builder().code(404).body(Buffer().write(bytes)).build())
                assertNull(fetchArtworkBitmap(client, url))
                server.enqueue(MockResponse.Builder().body("invalid image").build())
                assertNull(fetchArtworkBitmap(client, url))
                assertEquals(5, server.requestCount)
            }
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
