package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

fun interface AmllTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

class AmllUrlConnectionTransport(
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : AmllTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        ensureActive()
        suspendCancellableCoroutine { continuation ->
            val stream = AtomicReference<InputStream?>()
            val connection = open(url)
            continuation.invokeOnCancellation {
                try { stream.getAndSet(null)?.close() } catch (_: Exception) { }
                connection.disconnect()
            }
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val code = connection.responseCode
                val retry = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                var response = HttpResponse(code, "", retry)
                if (code == 200) {
                    val input = connection.inputStream
                    stream.set(input)
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (continuation.isActive) {
                        val count = input.read(buffer, 0, minOf(buffer.size, LIMIT - output.size() + 1))
                        if (count == -1) break
                        if (count > LIMIT - output.size()) {
                            response = response.copy(tooLarge = true)
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                    if (!response.tooLarge) response = response.copy(body = output.toString("UTF-8"))
                }
                if (continuation.isActive) continuation.resume(response)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            } finally {
                try { stream.getAndSet(null)?.close() } finally { connection.disconnect() }
            }
        }
    }

    companion object { const val LIMIT = 512 * 1024 }
}
