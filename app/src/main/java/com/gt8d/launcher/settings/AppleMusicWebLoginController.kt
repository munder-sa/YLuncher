package com.gt8d.launcher.settings

import android.webkit.CookieManager
import androidx.annotation.MainThread
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import com.gt8d.launcher.media.lyrics.AppleLyricsReason
import com.gt8d.launcher.media.lyrics.AppleMusicRetentionStatus
import com.gt8d.launcher.media.lyrics.AppleMusicSession
import com.gt8d.launcher.media.lyrics.AppleMusicStatus
import com.gt8d.launcher.media.lyrics.AppleWebTokenLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val APPLE_MUSIC_LOGIN_URL = "https://music.apple.com/"

internal interface AppleMusicCookieSource {
    fun header(): String?
    fun supportsInfo(): Boolean
    fun info(): List<String>
}

/** No provider/singleton access until the acquisition screen is in the foreground. */
internal class AndroidAppleMusicCookies : AppleMusicCookieSource {
    override fun header(): String? = CookieManager.getInstance().getCookie(APPLE_MUSIC_LOGIN_URL)
    override fun supportsInfo(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)
    override fun info(): List<String> = CookieManagerCompat.getCookieInfo(CookieManager.getInstance(), APPLE_MUSIC_LOGIN_URL)
}

internal class AppleMusicWebCredential(private var value: String?, val expiry: Long?) {
    fun token(): String? = value
    fun release() { value = null }
    override fun toString(): String = "AppleMusicWebCredential(redacted)"
}

internal enum class AppleMusicWebLoginMessage {
    WAITING, READY, EXPIRED, CHECKING, SAVING, SAVED, SESSION_ONLY, AUTH_REQUIRED, FAILED,
}

internal data class AppleMusicWebLoginState(
    val canConnect: Boolean = false,
    val connecting: Boolean = false,
    val message: AppleMusicWebLoginMessage = AppleMusicWebLoginMessage.WAITING,
    val expiryKnown: Boolean = false,
)

/** Main-thread controller. Public state contains neither cookies nor page URLs. */
@MainThread
internal class AppleMusicWebLoginController(
    private val scope: CoroutineScope,
    private val session: AppleMusicSession,
    private val webData: AppleMusicWebData,
    private val cookies: AppleMusicCookieSource = AndroidAppleMusicCookies(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(AppleMusicWebLoginState())
    val state: StateFlow<AppleMusicWebLoginState> = mutableState.asStateFlow()
    private var foreground = false
    private var polling: Job? = null
    private var connecting: Job? = null
    private var pending: AppleMusicWebCredential? = null
    private var epoch = 0L

    fun resume() {
        if (foreground || !webData.canStart) return
        foreground = true
        polling = scope.launch {
            while (isActive && foreground && webData.canStart) {
                refresh()
                delay(1_500L)
            }
        }
    }

    fun pause() {
        foreground = false
        polling?.cancel()
        polling = null
        mutableState.value = state.value.copy(canConnect = false)
    }

    fun stop() {
        pause()
        epoch++
        pending?.release()
        pending = null
        connecting?.cancel()
        connecting = null
        mutableState.value = AppleMusicWebLoginState()
    }

    fun refresh() {
        if (!foreground || !webData.canStart || state.value.connecting) return
        if (state.value.message in listOf(AppleMusicWebLoginMessage.SAVED, AppleMusicWebLoginMessage.SESSION_ONLY)) return
        val credential = readCredential()
        try {
            val expired = credential?.expiry?.let { it <= clock() } == true
            val ready = credential != null && !expired
            val message = when {
                expired -> AppleMusicWebLoginMessage.EXPIRED
                !ready -> AppleMusicWebLoginMessage.WAITING
                // Keep the fixed failure explanation visible while offering another attempt.
                state.value.message in listOf(AppleMusicWebLoginMessage.AUTH_REQUIRED, AppleMusicWebLoginMessage.FAILED) ->
                    state.value.message
                else -> AppleMusicWebLoginMessage.READY
            }
            mutableState.value = AppleMusicWebLoginState(ready, message = message, expiryKnown = credential?.expiry != null)
        } finally { credential?.release() }
    }

    fun connect() {
        if (!foreground || !webData.canStart || !state.value.canConnect || state.value.connecting) return
        // Never reuse detection-time credentials, including on account change/logout.
        val credential = readCredential()
        if (credential == null || credential.expiry?.let { it <= clock() } == true) {
            credential?.release()
            mutableState.value = AppleMusicWebLoginState(message = if (credential == null)
                AppleMusicWebLoginMessage.WAITING else AppleMusicWebLoginMessage.EXPIRED)
            return
        }
        val ticket = ++epoch
        pending = credential
        mutableState.value = AppleMusicWebLoginState(connecting = true, message = AppleMusicWebLoginMessage.CHECKING)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val progress = launch {
                session.retention.collect { retention ->
                    if (ticket == epoch && state.value.connecting && retention.status == AppleMusicRetentionStatus.SAVING) {
                        mutableState.value = state.value.copy(message = AppleMusicWebLoginMessage.SAVING)
                    }
                }
            }
            try {
                session.connectFromWeb(credential.token() ?: return@launch, credential.expiry)
                if (ticket != epoch || !webData.canStart) return@launch
                val connection = session.state.value
                val message = when {
                    connection.status == AppleMusicStatus.CONNECTED -> when (session.retention.value.status) {
                        AppleMusicRetentionStatus.SAVED -> AppleMusicWebLoginMessage.SAVED
                        AppleMusicRetentionStatus.SESSION_ONLY, AppleMusicRetentionStatus.NONE -> AppleMusicWebLoginMessage.SESSION_ONLY
                        else -> AppleMusicWebLoginMessage.FAILED
                    }
                    connection.reason == AppleLyricsReason.AUTH_REQUIRED -> AppleMusicWebLoginMessage.AUTH_REQUIRED
                    else -> AppleMusicWebLoginMessage.FAILED
                }
                connecting = null
                mutableState.value = AppleMusicWebLoginState(message = message)
            } catch (_: CancellationException) {
                // Session owns cancellation cleanup and sanitizes dependency exceptions.
                if (ticket == epoch) mutableState.value = AppleMusicWebLoginState()
            } finally {
                progress.cancel()
                credential.release()
                if (pending === credential) pending = null
                if (ticket == epoch) connecting = null
            }
        }
        connecting = job
        job.start()
    }

    private fun readCredential(): AppleMusicWebCredential? {
        val token = try { AppleWebTokenLogic.findToken(cookies.header()) }
        catch (_: Exception) { null } catch (_: LinkageError) { null } ?: return null
        val expiry = try {
            if (cookies.supportsInfo()) cookies.info().firstNotNullOfOrNull {
                AppleWebTokenLogic.expirationFromSetCookie(it, token)
            } else null
        } catch (_: Exception) { null } catch (_: LinkageError) { null }
        return AppleMusicWebCredential(token, expiry)
    }
}
