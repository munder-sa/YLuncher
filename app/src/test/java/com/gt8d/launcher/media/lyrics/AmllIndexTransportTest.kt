package com.gt8d.launcher.media.lyrics

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AmllIndexTransportTest {
    private class Connection(val input: InputStream, val code: Int = 200, val length: Long = -1) : HttpURLConnection(URL(AmllIndexUrlConnectionTransport.URL)) {
        var reads = 0
        @Volatile var disconnected = false
        override fun getResponseCode() = code
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream { reads++; return input }
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
    }
    @Test fun truncatedResponseIsRejected() = runBlocking {
        val connection = Connection(ByteArrayInputStream(byteArrayOf(65)), length = 2)
        try {
            AmllIndexUrlConnectionTransport("agent") { connection }.get { _, input -> input!!.readBytes() }
            fail("truncated response")
        } catch (_: IOException) { }
        assertTrue(connection.disconnected)
    }
    @Test fun redirectIsNotFollowed() = runBlocking {
        val connection = Connection(ByteArrayInputStream(byteArrayOf()), 302)
        AmllIndexUrlConnectionTransport("agent") { url -> assertEquals(AmllIndexUrlConnectionTransport.URL, url); connection }.get { code, input ->
            assertEquals(302, code); assertNull(input)
        }
        assertFalse(connection.instanceFollowRedirects); assertEquals(0, connection.reads)
        assertTrue(connection.disconnected)
        assertEquals("agent", connection.getRequestProperty("User-Agent"))
        assertEquals(5000, connection.connectTimeout); assertEquals(5000, connection.readTimeout)
    }
    @Test fun cancelClosesAndDisconnects() = runBlocking {
        val started = CountDownLatch(1); val released = CountDownLatch(1)
        val input = object : InputStream() {
            @Volatile var closed = false
            override fun read(): Int { started.countDown(); if (!released.await(5, TimeUnit.SECONDS)) throw IOException("timeout"); throw IOException("closed") }
            override fun close() { closed = true; released.countDown() }
        }
        val connection = Connection(input)
        val job = launch(Dispatchers.IO) {
            AmllIndexUrlConnectionTransport("agent") { connection }.get { _, stream -> stream!!.read() }
            fail("cancelled result")
        }
        assertTrue(started.await(5, TimeUnit.SECONDS)); job.cancelAndJoin()
        assertTrue(input.closed); assertTrue(connection.disconnected); assertTrue(job.isCancelled)
    }
}
