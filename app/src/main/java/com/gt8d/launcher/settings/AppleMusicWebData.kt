package com.gt8d.launcher.settings

import android.webkit.WebStorage
import androidx.annotation.MainThread
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class AppleMusicWebDataState { IDLE, CLEARING, CLEARED, FAILED }

/** Shared by settings and the acquisition activity. All entry points run on the main thread. */
class AppleMusicWebData(
    private val scope: CoroutineScope,
    private val backend: Backend = AndroidBackend(),
) {
    interface Backend {
        fun supported(): Boolean
        fun delete(onComplete: () -> Unit)
    }

    private val mutableState = MutableStateFlow(AppleMusicWebDataState.IDLE)
    val state: StateFlow<AppleMusicWebDataState> = mutableState.asStateFlow()
    val canStart: Boolean get() = state.value != AppleMusicWebDataState.CLEARING
    private val screens = mutableSetOf<Registration>()
    private var deletion: Job? = null

    inner class Registration internal constructor(private var closeScreen: (() -> Unit)?) : AutoCloseable {
        @MainThread override fun close() {
            screens.remove(this)
            closeScreen = null
        }

        internal fun closeForDeletion() {
            val action = closeScreen
            close()
            action?.invoke()
        }
    }

    @MainThread fun register(closeScreen: () -> Unit): Registration? {
        if (!canStart) return null
        return Registration(closeScreen).also { screens.add(it) }
    }

    @MainThread fun clear(): Job {
        deletion?.takeUnless { it.isCompleted }?.let { return it }
        // Publish the gate before closing any screens: their late callbacks must not reopen one.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var closed = true
                screens.toList().forEach {
                    try { it.closeForDeletion() } catch (_: Exception) { closed = false }
                    catch (_: LinkageError) { closed = false }
                }
                if (!closed || !backend.supported()) {
                    mutableState.value = AppleMusicWebDataState.FAILED
                    return@launch
                }
                // No timeout or UI-scope cancellation can claim that the deletion has finished.
                // The backend callback retains only this continuation, never an Activity.
                suspendCoroutine<Unit> { continuation ->
                    var completed = false
                    backend.delete {
                        if (!completed) {
                            completed = true
                            continuation.resume(Unit)
                        }
                    }
                }
                mutableState.value = AppleMusicWebDataState.CLEARED
            } catch (_: Exception) {
                mutableState.value = AppleMusicWebDataState.FAILED
            } catch (_: LinkageError) {
                mutableState.value = AppleMusicWebDataState.FAILED
            }
        }
        deletion = job
        mutableState.value = AppleMusicWebDataState.CLEARING
        job.start()
        return job
    }

    /** Construction does not access the WebView provider or its singleton storage. */
    private class AndroidBackend : Backend {
        override fun supported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

        override fun delete(onComplete: () -> Unit) {
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { onComplete() }
        }
    }
}
