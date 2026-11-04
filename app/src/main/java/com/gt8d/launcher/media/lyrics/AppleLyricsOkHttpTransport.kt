package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.media.lyrics.AppleLyricsTransportPolicy.REQUEST_FAILED
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class AppleLyricsOkHttpTransport internal constructor(
    private val calls: Call.Factory,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppleLyricsTransport {
    constructor() : this(createClient())

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
    ): AppleLyricsHttpResponse {
        currentCoroutineContext().ensureActive()
        val requestHeaders = headers.toMap()
        val host = AppleLyricsTransportPolicy.validateRequest(url, requestHeaders, maxBytes)
        val result = withContext(Dispatchers.IO) {
            ensureActive()
            val outcome = suspendCancellableCoroutine<Result<AppleLyricsHttpResponse>> { continuation ->
                val resources = Resources()
                continuation.invokeOnCancellation { resources.release(cancelCall = true) }
                var cleanupCancellation: CancellationException? = null
                var failed = true
                val result = try {
                    continuation.context.ensureActive()
                    // Builder failures can contain header values or the original URL too.
                    val request = Request.Builder().url(url).get().apply {
                        header("Accept-Encoding", "identity")
                        requestHeaders.forEach { (name, value) ->
                            if (!name.equals("Accept-Encoding", ignoreCase = true)) header(name, value)
                        }
                    }.build()
                    val call = calls.newCall(request)
                    resources.call.set(call)
                    continuation.context.ensureActive()
                    val response = call.execute()
                    resources.response.set(response)
                    // The cancellation handler may have run before execute/newCall returned.
                    continuation.context.ensureActive()
                    val value = if (response.code == 200) {
                        val retry = AppleLyricsTransportPolicy.parseRetryAfter(response.header("Retry-After"), clock)
                        continuation.context.ensureActive()
                        val input = response.body.byteStream()
                        continuation.context.ensureActive()
                        AppleLyricsTransportPolicy.readBody(input, maxBytes, retry, continuation.context)
                    } else {
                        // Also enforce the policy for injected factories returning normal responses.
                        AppleLyricsTransportPolicy.nonSuccessResponse(host, response.code, response::header, clock)
                    }
                    continuation.context.ensureActive()
                    failed = false
                    Result.success(value)
                } catch (e: NonSuccessResponse) {
                    // This exception contains only the DTO, never a Request/Response or raw cause.
                    Result.success(e.value)
                } catch (e: CancellationException) {
                    Result.failure(e)
                } catch (_: Exception) {
                    Result.failure(IOException(REQUEST_FAILED))
                } finally {
                    cleanupCancellation = resources.release(cancelCall = failed)
                }
                // Do not publish a result until close has finished; it can trigger cancellation.
                if (continuation.isActive) {
                    val cancellation = cleanupCancellation
                    if (cancellation != null && result.exceptionOrNull() !is CancellationException) {
                        continuation.resume(Result.failure(cancellation))
                    } else {
                        continuation.resume(result)
                    }
                }
            }
            ensureActive()
            outcome
        }
        currentCoroutineContext().ensureActive()
        // Throw at the caller boundary, avoiding coroutine recovery attaching raw causes/copies.
        return result.getOrThrow()
    }

    private class Resources {
        val call = AtomicReference<Call?>()
        val response = AtomicReference<Response?>()

        fun release(cancelCall: Boolean): CancellationException? {
            var cancellation: CancellationException? = null
            fun cancel() {
                try {
                    call.getAndSet(null)?.cancel()
                } catch (e: CancellationException) {
                    if (cancellation == null) cancellation = e
                } catch (_: Exception) {
                    // Still close the response even when a factory's cancel implementation fails.
                }
            }
            // Cancel first to interrupt a blocked read/close. Late responses are closed in finally.
            if (cancelCall) cancel()
            try {
                response.getAndSet(null)?.close()
            } catch (e: CancellationException) {
                if (cancellation == null) cancellation = e
                cancel()
            } catch (_: Exception) {
                cancel()
            }
            // Retain the call through close so cancellation during close can still interrupt it.
            if (!cancelCall) call.set(null)
            return cancellation
        }
    }

    private class NonSuccessResponse(val value: AppleLyricsHttpResponse) :
        ProtocolException("Apple lyrics non-success response")

    internal companion object {
        fun createClient(clock: () -> Long = System::currentTimeMillis): OkHttpClient =
            OkHttpClient.Builder()
                .fastFallback(true)
                .retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .followRedirects(false)
                .followSslRedirects(false)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.code == 200) {
                        response
                    } else {
                        // 503 + Retry-After: 0 is retried even with retryOnConnectionFailure(false).
                        // A ProtocolException exits before OkHttp's HTTP follow-up processing.
                        val value = try {
                            AppleLyricsTransportPolicy.nonSuccessResponse(
                                chain.request().url.host, response.code, response::header, clock,
                            )
                        } finally {
                            try {
                                response.close()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Never expose close failures or replace the actual HTTP status.
                            }
                        }
                        throw NonSuccessResponse(value)
                    }
                }
                .build()
    }
}
