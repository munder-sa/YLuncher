package com.gt8d.launcher.media.lyrics

import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

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
    credentialStore: AppleCredentialStore? = null,
    private val persistenceScope: CoroutineScope? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
) {
    init {
        require(credentialStore == null || persistenceScope != null) { "Apple Music persistence requires a scope" }
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow(AppleMusicConnection())
    val state: StateFlow<AppleMusicConnection> = mutableState.asStateFlow()
    private val mutableRetention = MutableStateFlow(AppleMusicRetentionState())
    val retention: StateFlow<AppleMusicRetentionState> = mutableRetention.asStateFlow()
    private val persistence = credentialStore?.let { AppleMusicPersistence(it, io) }

    // All these fields use lock. Epoch identity additionally identifies the target of disk work.
    private var epoch = Epoch(0L)
    private var restoreAllowed = true
    private var readStarted = false
    private var restoreJob: Job? = null
    private var restoreCandidate: StoredAppleCredential? = null
    private var retryAtMillis: Long? = null

    // Credentials stay private, including the unverified restore candidate above.
    private var userToken: String? = null
    private val operations = mutableSetOf<Job>()

    suspend fun connect(input: String) = connect(input, AppleCredentialSource.MANUAL, null)

    suspend fun connectFromWeb(input: String, cookieExpiresAtEpochMillis: Long? = null) =
        connect(input, AppleCredentialSource.WEB, cookieExpiresAtEpochMillis?.takeIf { it >= 0L })

    private suspend fun connect(input: String, source: AppleCredentialSource, expiry: Long?) {
        var started: Epoch? = null
        try {
            coroutineScope {
                val operation = coroutineContext.job
                val reset = synchronized(lock) {
                    checkActive(operation)
                    disableRestoreLocked()
                    resetLocked(AppleMusicStatus.CHECKING, newOperation = operation).also { clearingLocked(it.epoch) }
                }
                val target = reset.epoch
                started = target
                val generation = target.generation
                try {
                    cancel(reset.previous)
                    synchronized(lock) { checkCurrent(operation, generation, AppleMusicStatus.CHECKING) }
                    if (persistence != null && !clearSaved(target)) return@coroutineScope
                    synchronized(lock) { checkCurrent(operation, generation, AppleMusicStatus.CHECKING) }
                    val token = cleanToken(input)
                    val result = validate(token)
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
                    if (persistence != null && token != null && result is AppleConnectResult.Ready &&
                        STOREFRONT.matches(result.storefront)
                    ) {
                        save(operation, target, StoredAppleCredential(token, result.storefront, source, now(), expiry))
                    }
                } finally {
                    synchronized(lock) { operations.remove(operation) }
                }
            }
        } catch (_: CancellationException) {
            // Also covers cancellation at coroutineScope's return boundary. An old scope
            // must never clear the credentials installed by a newer connect.
            val reset = synchronized(lock) {
                if (started != null && currentLocked(checkNotNull(started))) {
                    resetLocked(AppleMusicStatus.UNCONFIGURED).also { clearingLocked(it.epoch) }
                } else null
            }
            if (reset != null) {
                cancel(reset.previous)
                // Also reclaims a write whose successful IO return was lost to prompt cancellation.
                if (persistence != null) clearSaved(reset.epoch)
            }
            throw cancelled()
        }
    }

    fun disconnect(): Job? {
        val (reset, cleanup) = synchronized(lock) {
            disableRestoreLocked()
            val reset = resetLocked(AppleMusicStatus.UNCONFIGURED)
            reset to deletionLocked(reset.epoch)
        }
        cancel(reset.previous)
        cleanup?.start()
        return cleanup
    }

    fun restoreIfNeeded(): Job? {
        var previous = emptyList<Job>()
        val job = synchronized(lock) {
            if (persistence == null || !restoreAllowed) return null
            restoreJob?.takeUnless { it.isCompleted }?.let { return it }
            if (mutableState.value.status == AppleMusicStatus.CONNECTED ||
                (readStarted && restoreCandidate == null)
            ) return null
            val now = elapsedNow()
            retryAtMillis?.takeIf { it > now }?.let {
                mutableState.value = mutableState.value.copy(retryAfterSec = remainingSeconds(it, now))
                return null
            }
            val read = !readStarted
            readStarted = true
            retryAtMillis = null
            val target = Epoch(mutableState.value.generation + 1L)
            val pending = checkNotNull(persistenceScope).launch(start = CoroutineStart.LAZY) {
                restore(coroutineContext.job, target, read)
            }
            restoreJob = pending
            val reset = resetLocked(AppleMusicStatus.CHECKING, newOperation = pending, target = target)
            previous = reset.previous
            if (read) retentionLocked(target, AppleMusicRetentionStatus.READING)
            pending
        }
        cancel(previous)
        job.start()
        return job
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
                var cleanup: Job? = null
                val previous = synchronized(lock) {
                    checkCurrent(operation, expectedGeneration, AppleMusicStatus.CONNECTED)
                    if (result.reason == AppleLyricsReason.AUTH_REQUIRED) {
                        // The reporting fetch can return AUTH_REQUIRED; cancel only its peers.
                        operations.remove(operation)
                        disableRestoreLocked()
                        val reset = resetLocked(AppleMusicStatus.NEEDS_ATTENTION, AppleLyricsReason.AUTH_REQUIRED)
                        cleanup = deletionLocked(reset.epoch)
                        reset.previous
                    } else emptyList()
                }
                cancel(previous)
                cleanup?.start()
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

    private suspend fun validate(token: String?): AppleConnectResult = try {
        if (token == null) AppleConnectResult.Unavailable(AppleLyricsReason.INVALID) else connectRequest(token)
    } catch (_: CancellationException) {
        throw cancelled()
    } catch (_: IOException) {
        AppleConnectResult.Unavailable(AppleLyricsReason.NETWORK)
    } catch (_: Exception) {
        AppleConnectResult.Unavailable(AppleLyricsReason.INVALID)
    }

    private suspend fun restore(operation: Job, target: Epoch, read: Boolean) {
        try {
            if (read) checkNotNull(persistence).access {
                if (!synchronized(lock) { currentLocked(target) && operation.isActive }) return@access
                val result = try {
                    this.read()
                } catch (_: CancellationException) {
                    throw cancelled()
                } catch (_: Exception) {
                    AppleCredentialReadResult.Failed(AppleCredentialFailure.STORAGE)
                }
                synchronized(lock) {
                    checkCurrent(operation, target.generation, AppleMusicStatus.CHECKING)
                    when (result) {
                        AppleCredentialReadResult.Missing -> {
                            mutableState.value = AppleMusicConnection(generation = target.generation)
                            retentionLocked(target, AppleMusicRetentionStatus.NONE)
                        }
                        is AppleCredentialReadResult.Failed -> {
                            mutableState.value = AppleMusicConnection(
                                AppleMusicStatus.NEEDS_ATTENTION, target.generation, reason = AppleLyricsReason.INVALID,
                            )
                            retentionLocked(target, AppleMusicRetentionStatus.ERROR, AppleMusicRetentionProblem.READ)
                        }
                        is AppleCredentialReadResult.Ready -> {
                            restoreCandidate = result.credential
                            retentionLocked(target, AppleMusicRetentionStatus.SAVED)
                        }
                    }
                }
            }
            val credential = synchronized(lock) {
                checkActive(operation)
                if (!currentLocked(target)) throw cancelled()
                restoreCandidate
            } ?: return
            val token = cleanToken(credential.token)
            val result = validate(token)
            var cleanup: Job? = null
            val previous = synchronized(lock) {
                checkCurrent(operation, target.generation, AppleMusicStatus.CHECKING)
                if (restoreCandidate !== credential) throw cancelled()
                if (result is AppleConnectResult.Ready && token != null && STOREFRONT.matches(result.storefront)) {
                    userToken = token
                    retryAtMillis = null
                    mutableState.value = AppleMusicConnection(
                        AppleMusicStatus.CONNECTED, target.generation, result.storefront,
                    )
                    emptyList()
                } else if (result is AppleConnectResult.Unavailable && result.reason == AppleLyricsReason.AUTH_REQUIRED) {
                    operations.remove(operation)
                    disableRestoreLocked()
                    val reset = resetLocked(AppleMusicStatus.NEEDS_ATTENTION, AppleLyricsReason.AUTH_REQUIRED)
                    cleanup = deletionLocked(reset.epoch)
                    reset.previous
                } else {
                    val failure = result as? AppleConnectResult.Unavailable
                    val now = elapsedNow()
                    val deadline = retryDeadline(now, maxOf(60L, failure?.retryAfterSec ?: 0L))
                    retryAtMillis = deadline
                    mutableState.value = AppleMusicConnection(
                        AppleMusicStatus.NEEDS_ATTENTION, target.generation,
                        reason = failure?.reason ?: AppleLyricsReason.INVALID,
                        retryAfterSec = remainingSeconds(deadline, now),
                    )
                    emptyList()
                }
            }
            cancel(previous)
            cleanup?.start()
            cleanup?.join()
            checkActive(operation)
        } catch (_: CancellationException) {
            synchronized(lock) {
                if (currentLocked(target)) {
                    userToken = null
                    val now = elapsedNow()
                    retryAtMillis = retryDeadline(now, 60L)
                    mutableState.value = AppleMusicConnection(
                        AppleMusicStatus.NEEDS_ATTENTION, target.generation, reason = AppleLyricsReason.INVALID,
                        retryAfterSec = remainingSeconds(checkNotNull(retryAtMillis), now),
                    )
                    if (mutableRetention.value.status == AppleMusicRetentionStatus.READING) {
                        retentionLocked(target, AppleMusicRetentionStatus.ERROR, AppleMusicRetentionProblem.READ)
                    }
                }
            }
            throw cancelled()
        } finally {
            synchronized(lock) { operations.remove(operation) }
        }
    }

    private suspend fun save(operation: Job, target: Epoch, credential: StoredAppleCredential) {
        checkNotNull(persistence).access {
            val start = synchronized(lock) {
                if (!canSaveLocked(operation, target, credential)) false else {
                    retentionLocked(target, AppleMusicRetentionStatus.SAVING)
                    canSaveLocked(operation, target, credential)
                }
            }
            if (!start) return@access
            var interrupted = false
            val written = try {
                write(credential)
            } catch (_: CancellationException) {
                interrupted = true
                false
            } catch (_: Exception) {
                false
            }
            val keep = synchronized(lock) {
                if (written && canSaveLocked(operation, target, credential)) {
                    retentionLocked(target, AppleMusicRetentionStatus.SAVED)
                    // Publishing may synchronously notify an observer that cancels or disconnects.
                    canSaveLocked(operation, target, credential)
                } else false
            }
            if (!keep) {
                // Still holding the disk gate: no newer write can be erased by this cleanup.
                val cleared = clearSafely()
                synchronized(lock) {
                    when {
                        !cleared -> retentionLocked(target, AppleMusicRetentionStatus.ERROR, AppleMusicRetentionProblem.CLEAR)
                        !interrupted && canSaveLocked(operation, target, credential) ->
                            retentionLocked(target, AppleMusicRetentionStatus.SESSION_ONLY, AppleMusicRetentionProblem.SAVE)
                        else -> retentionLocked(target, AppleMusicRetentionStatus.NONE)
                    }
                }
            }
            if (interrupted) throw cancelled()
        }
        checkActive(operation)
    }

    private fun canSaveLocked(operation: Job, target: Epoch, credential: StoredAppleCredential): Boolean =
        currentLocked(target) && operation.isActive && userToken == credential.token &&
            mutableState.value.status == AppleMusicStatus.CONNECTED

    private suspend fun clearSaved(target: Epoch): Boolean = checkNotNull(persistence).access {
        val start = synchronized(lock) {
            clearingLocked(target)
            currentLocked(target)
        }
        if (!start) return@access false
        val cleared = clearSafely()
        synchronized(lock) {
            if (!currentLocked(target)) return@synchronized false
            if (!cleared && mutableState.value.status == AppleMusicStatus.CHECKING) {
                mutableState.value = AppleMusicConnection(
                    AppleMusicStatus.NEEDS_ATTENTION, target.generation, reason = AppleLyricsReason.INVALID,
                )
            }
            retentionLocked(target, if (cleared) AppleMusicRetentionStatus.NONE else AppleMusicRetentionStatus.ERROR,
                if (cleared) null else AppleMusicRetentionProblem.CLEAR)
            cleared && currentLocked(target)
        }
    }

    // Called under lock; LAZY prevents an unconfined application scope from doing IO under lock.
    private fun deletionLocked(target: Epoch): Job? {
        if (persistence == null || !currentLocked(target)) return null
        val job = checkNotNull(persistenceScope).launch(start = CoroutineStart.LAZY) { clearSaved(target) }
        clearingLocked(target)
        return job
    }

    private fun AppleCredentialStore.clearSafely(): Boolean = try { clear() } catch (_: Exception) { false }

    private fun disableRestoreLocked() {
        restoreAllowed = false
        restoreCandidate = null
        retryAtMillis = null
    }

    private fun clearingLocked(target: Epoch) {
        if (persistence != null) retentionLocked(target, AppleMusicRetentionStatus.CLEARING)
    }

    private fun retentionLocked(target: Epoch, status: AppleMusicRetentionStatus, problem: AppleMusicRetentionProblem? = null) {
        if (currentLocked(target)) mutableRetention.value = AppleMusicRetentionState(status, problem)
    }

    private fun currentLocked(target: Epoch): Boolean = epoch === target && mutableState.value.generation == target.generation

    private fun now(): Long = try { clock().coerceAtLeast(0L) } catch (_: Exception) { 0L }

    private fun elapsedNow(): Long = try { monotonicClock().coerceAtLeast(0L) } catch (_: Exception) { 0L }

    // All callers hold lock. Cancellation itself is deliberately outside the lock,
    // and never joins old jobs that may ignore cancellation.
    private fun resetLocked(
        status: AppleMusicStatus,
        reason: AppleLyricsReason? = null,
        newOperation: Job? = null,
        target: Epoch = Epoch(mutableState.value.generation + 1L),
    ): Reset {
        userToken = null
        val previous = operations.toList()
        operations.clear()
        if (newOperation != null) operations.add(newOperation)
        // Register the child before publishing: an immediate observer may disconnect.
        epoch = target
        mutableState.value = AppleMusicConnection(status, target.generation, reason = reason)
        return Reset(target, previous)
    }

    private fun checkCurrent(operation: Job, generation: Long, status: AppleMusicStatus) {
        checkActive(operation)
        if (mutableState.value.generation != generation || mutableState.value.status != status) throw cancelled()
    }

    private class Credentials(val storefront: String, val token: String)
    private class Epoch(val generation: Long)
    private class Reset(val epoch: Epoch, val previous: List<Job>)

    @OptIn(ExperimentalCoroutinesApi::class)
    private class SessionCancellationException : CancellationException("Apple Music session operation cancelled"),
        CopyableThrowable<SessionCancellationException> {
        // Coroutine stack-trace recovery must not attach another exception as a cause.
        override fun createCopy(): SessionCancellationException? = null
    }

    private companion object {
        const val MAX_TOKEN_BYTES = 16 * 1024
        val STOREFRONT = Regex("[a-z]{2}")

        fun retryDeadline(now: Long, seconds: Long): Long =
            if (seconds > (Long.MAX_VALUE - now) / 1_000L) Long.MAX_VALUE else now + seconds * 1_000L

        fun remainingSeconds(deadline: Long, now: Long): Long {
            val millis = (deadline - now).coerceAtLeast(0L)
            return millis / 1_000L + if (millis % 1_000L == 0L) 0L else 1L
        }

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
