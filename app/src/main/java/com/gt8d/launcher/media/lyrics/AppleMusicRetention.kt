package com.gt8d.launcher.media.lyrics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AppleMusicRetentionStatus { NONE, READING, SAVING, SAVED, SESSION_ONLY, CLEARING, ERROR }
enum class AppleMusicRetentionProblem { READ, SAVE, CLEAR }

data class AppleMusicRetentionState(
    val status: AppleMusicRetentionStatus = AppleMusicRetentionStatus.NONE,
    val problem: AppleMusicRetentionProblem? = null,
)

/** The gate covers a disk operation AND its cleanup, including the IO return boundary. */
internal class AppleMusicPersistence(
    private val store: AppleCredentialStore,
    private val io: CoroutineDispatcher,
) {
    private val gate = Mutex()

    // Blocks contain only synchronous storage work and short memory checks, never authentication.
    // Callers check their original Job as NonCancellable deliberately replaces the context Job.
    suspend fun <T> access(block: AppleCredentialStore.() -> T): T = withContext(NonCancellable) {
        withContext(io) { gate.withLock { store.block() } }
    }
}
