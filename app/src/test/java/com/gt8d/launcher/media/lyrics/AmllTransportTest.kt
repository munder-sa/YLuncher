package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AmllTransportTest {
    private class Connection(private val input: InputStream, private val code: Int = 200) : HttpURLConnection(URL("https://example.test")) {
        @Volatile var disconnected = false
        var reads = 0
        override fun getResponseCode() = code
        override fun getInputStream(): InputStream { reads++; return input }
        override fun disconnect() { disconnected = true }
        override fun connect() { }
        override fun usingProxy() = false
        override fun getHeaderField(name: String?) = if (name == "Retry-After") "120" else null
    }

    @Test fun ttmlLimit512KiB() = runBlocking {
        for (size in listOf(512 * 1024, 512 * 1024 + 1)) {
            var closed = false
            val input = object : ByteArrayInputStream(ByteArray(size) { 'A'.code.toByte() }) {
                override fun close() { closed = true; super.close() }
            }
            val connection = Connection(input)
            val result = AmllUrlConnectionTransport { connection }.get("https://example.test", mapOf("User-Agent" to "agent"))
            assertEquals(size > 512 * 1024, result.tooLarge)
            assertEquals(if (size == 512 * 1024) size else 0, result.body.length)
            assertTrue(closed); assertTrue(connection.disconnected)
            assertEquals(5000, connection.connectTimeout); assertEquals(5000, connection.readTimeout)
            assertEquals("agent", connection.getRequestProperty("User-Agent"))
            assertEquals(120L, result.retryAfterSec)
        }
    }

    @Test fun redirectIsNotFollowed() = runBlocking {
        val connection = Connection(ByteArrayInputStream(byteArrayOf()), 302)
        val response = AmllUrlConnectionTransport { connection }.get("https://example.test", emptyMap())
        assertEquals(302, response.code)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(0, connection.reads)
        assertTrue(connection.disconnected)
    }

    @Test fun cancelClosesAndDisconnects() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val input = object : InputStream() {
            @Volatile var closed = false
            override fun read(): Int {
                started.countDown()
                if (!released.await(5, TimeUnit.SECONDS)) throw IOException("test read timed out")
                throw IOException("closed")
            }
            override fun close() { closed = true; released.countDown() }
        }
        val connection = Connection(input)
        val job = launch(Dispatchers.IO) {
            AmllUrlConnectionTransport { connection }.get("https://example.test", emptyMap())
            fail("cancelled response must not escape")
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(input.closed)
        assertTrue(connection.disconnected)
    }
}
