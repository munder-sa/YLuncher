package com.gt8d.launcher.media.lyrics

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job

enum class AppleMusicStatus { UNCONFIGURED, CHECKING, CONNECTED, NEEDS_ATTENTION }

data class AppleMusicConnection(
    val status: AppleMusicStatus = AppleMusicStatus.UNCONFIGURED,
    val generation: Long = 0L,
    val storefront: String? = null,
    val reason: AppleLyricsReason? = null,
    val retryAfterSec: Long? = null,
)

class AppleMusicSession(
    private val connectRequest: suspend (String) -> AppleConnectResult,
    private val fetchRequest: suspend (LyricsRequest, String, String) -> AppleLyricsResult,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(AppleMusicConnection())
    val state: StateFlow<AppleMusicConnection> = mutableState.asStateFlow()

    // Only operation-local snapshots and this field may retain a validated credential.
    private var userToken: String? = null
    private val operations = mutableSetOf<Job>()

    suspend fun connect(input: String) {
        var startedGeneration: Long? = null
        try {
            coroutineScope {
                val operation = coroutineContext.job
                val previous = synchronized(lock) {
                    checkActive(operation)
                    startedGeneration = mutableState.value.generation + 1L
                    resetLocked(AppleMusicStatus.CHECKING, newOperation = operation)
                }
                val generation = checkNotNull(startedGeneration)
                try {
                    cancel(previous)
                    synchronized(lock) { checkCurrent(operation, generation, AppleMusicStatus.CHECKING) }
                    val token = cleanToken(input)
                    val result = try {
                        if (token == null) AppleConnectResult.Unavailable(AppleLyricsReason.INVALID)
                        else connectRequest(token)
                    } catch (_: CancellationException) {
                        throw cancelled()
                    } catch (_: IOException) {
                        AppleConnectResult.Unavailable(AppleLyricsReason.NETWORK)
                    } catch (_: Exception) {
                        AppleConnectResult.Unavailable(AppleLyricsReason.INVALID)
                    }
                    synchronized(lock) {
                        checkCurrent(operation, generation, AppleMusicStatus.CHECKING)
                        mutableState.value = when (result) {
                            is AppleConnectResult.Ready -> {
                                if (token != null && STOREFRONT.matches(result.storefront)) {
                                    userToken = token
                                    AppleMusicConnection(AppleMusicStatus.CONNECTED, generation, result.storefront)
                                } else {
                                    AppleMusicConnection(
                                        AppleMusicStatus.NEEDS_ATTENTION, generation, reason = AppleLyricsReason.INVALID,
                                    )
                                }
                            }
                            is AppleConnectResult.Unavailable -> AppleMusicConnection(
                                AppleMusicStatus.NEEDS_ATTENTION, generation,
                                reason = result.reason, retryAfterSec = result.retryAfterSec,
                            )
                        }
                    }
                } finally {
                    synchronized(lock) { operations.remove(operation) }
                }
            }
        } catch (_: CancellationException) {
            // Also covers cancellation at coroutineScope's return boundary. An old scope
            // must never clear the credentials installed by a newer connect.
            val previous = synchronized(lock) {
                if (startedGeneration != null && mutableState.value.generation == startedGeneration) {
                    resetLocked(AppleMusicStatus.UNCONFIGURED)
                } else emptyList()
            }
            cancel(previous)
            throw cancelled()
        }
    }

    fun disconnect() {
        val previous = synchronized(lock) { resetLocked(AppleMusicStatus.UNCONFIGURED) }
        cancel(previous)
    }

    suspend fun fetch(request: LyricsRequest, expectedGeneration: Long): AppleLyricsResult = try {
        coroutineScope {
            val operation = coroutineContext.job
            val credentials = synchronized(lock) {
                checkActive(operation)
                val connection = mutableState.value
                val token = userToken
                val storefront = connection.storefront
                if (connection.status != AppleMusicStatus.CONNECTED ||
                    connection.generation != expectedGeneration || token == null || storefront == null
                ) null else {
                    operations.add(operation)
                    Credentials(storefront, token)
                }
            } ?: return@coroutineScope AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)

            try {
                synchronized(lock) { checkCurrent(operation, expectedGeneration, AppleMusicStatus.CONNECTED) }
                val result = try {
                    fetchRequest(request, credentials.storefront, credentials.token)
                } catch (_: CancellationException) {
                    throw cancelled()
                } catch (_: IOException) {
                    AppleLyricsResult(LyricsState.Offline, AppleLyricsReason.NETWORK)
                } catch (_: Exception) {
                    AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.INVALID)
                }
                val previous = synchronized(lock) {
                    checkCurrent(operation, expectedGeneration, AppleMusicStatus.CONNECTED)
                    if (result.reason == AppleLyricsReason.AUTH_REQUIRED) {
                        // The reporting fetch can return AUTH_REQUIRED; cancel only its peers.
                        operations.remove(operation)
                        resetLocked(AppleMusicStatus.NEEDS_ATTENTION, AppleLyricsReason.AUTH_REQUIRED)
                    } else emptyList()
                }
                cancel(previous)
                checkActive(operation)
                result
            } finally {
                synchronized(lock) { operations.remove(operation) }
            }
        }
    } catch (_: CancellationException) {
        // Preserve cancellation semantics without publishing dependency messages or causes.
        throw cancelled()
    }

    // All callers hold lock. Cancellation itself is deliberately outside the lock,
    // and never joins old jobs that may ignore cancellation.
    private fun resetLocked(
        status: AppleMusicStatus,
        reason: AppleLyricsReason? = null,
        newOperation: Job? = null,
    ): List<Job> {
        userToken = null
        val previous = operations.toList()
        operations.clear()
        if (newOperation != null) operations.add(newOperation)
        // Register the child before publishing: an immediate observer may disconnect.
        mutableState.value = AppleMusicConnection(status, mutableState.value.generation + 1L, reason = reason)
        return previous
    }

    private fun checkCurrent(operation: Job, generation: Long, status: AppleMusicStatus) {
        checkActive(operation)
        if (mutableState.value.generation != generation || mutableState.value.status != status) throw cancelled()
    }

    private class Credentials(val storefront: String, val token: String)

    @OptIn(ExperimentalCoroutinesApi::class)
    private class SessionCancellationException : CancellationException("Apple Music session operation cancelled"),
        CopyableThrowable<SessionCancellationException> {
        // Coroutine stack-trace recovery must not attach another exception as a cause.
        override fun createCopy(): SessionCancellationException? = null
    }

    private companion object {
        const val MAX_TOKEN_BYTES = 16 * 1024
        val STOREFRONT = Regex("[a-z]{2}")

        fun cleanToken(input: String): String? {
            if (input.any { it.isISOControl() }) return null
            return input.trim().takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN_BYTES }
        }

        fun cancelled() = SessionCancellationException()

        fun checkActive(operation: Job) {
            if (!operation.isActive) throw cancelled()
        }

        fun cancel(operations: List<Job>) {
            operations.forEach { it.cancel(cancelled()) }
        }
    }
}
