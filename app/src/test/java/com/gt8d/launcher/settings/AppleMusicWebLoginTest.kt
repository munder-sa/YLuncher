package com.gt8d.launcher.settings

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.WindowManager
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.media.lyrics.*
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppleMusicWebLoginTest {
    private val activities = mutableListOf<ActivityController<TestActivity>>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun cleanup() {
        activities.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        activities.clear()
        appScope.cancel()
        TestActivity.dependencies = null
        TestActivity.failCreation = false
        TestActivity.created.clear()
    }

    @Test fun connectReadsCookieAgainAndCarriesOnlyMatchingExpiry() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val controller = f.controller(backgroundScope)
        controller.resume()
        runCurrent()
        assertTrue(controller.state.value.canConnect)
        f.cookies.value = "media-user-token=$SECOND"
        f.cookies.details = listOf(
            "media-user-token=$TOKEN; Expires=Wed, 09 Jun 2038 10:18:14 GMT",
            "media-user-token=$SECOND; Expires=Wed, 09 Jun 2038 10:18:14 GMT")
        controller.connect()
        runCurrent()
        assertEquals(listOf(SECOND), f.inputs)
        assertEquals(2, f.cookies.reads)
        assertEquals(AppleCredentialSource.WEB, f.store.value?.source)
        assertEquals(AppleWebTokenLogic.expirationFromSetCookie(f.cookies.details[1], SECOND),
            f.store.value?.cookieExpiresAtEpochMillis)
        assertEquals(AppleMusicWebLoginMessage.SAVED, controller.state.value.message)
        assertSafe(controller.state.value.toString(), f.session.state.value.toString(),
            f.session.retention.value.toString(), controller.toString())
        controller.stop()
    }

    @Test fun logoutBetweenDetectionAndClickNeverConnectsStaleCookie() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val controller = f.controller(backgroundScope)
        controller.resume()
        runCurrent()
        assertTrue(controller.state.value.canConnect)
        f.cookies.value = "other=value"
        controller.connect()
        runCurrent()
        assertTrue(f.inputs.isEmpty())
        assertFalse(controller.state.value.canConnect)
        assertEquals(AppleMusicWebLoginMessage.WAITING, controller.state.value.message)
        controller.stop()
    }

    @Test fun missingUnsupportedOrFailedExpiryIsUnknownAndExpiredCookieCannotConnect() = runTest {
        for (mode in 0..2) {
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
            f.cookies.supported = mode != 0
            f.cookies.infoFails = mode == 2
            val controller = f.controller(backgroundScope)
            controller.resume()
            runCurrent()
            assertTrue(controller.state.value.canConnect)
            assertFalse(controller.state.value.expiryKnown)
            controller.connect()
            runCurrent()
            assertNull(f.store.value?.cookieExpiresAtEpochMillis)
            assertEquals(AppleMusicWebLoginMessage.SAVED, controller.state.value.message)
            if (mode == 0) assertEquals(0, f.cookies.infoCalls)
            controller.stop()
        }
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.cookies.details = listOf("media-user-token=$TOKEN; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        val controller = f.controller(backgroundScope)
        controller.resume()
        runCurrent()
        assertFalse(controller.state.value.canConnect)
        assertEquals(AppleMusicWebLoginMessage.EXPIRED, controller.state.value.message)
        controller.connect()
        runCurrent()
        assertTrue(f.inputs.isEmpty())
        controller.stop()
    }

    @Test fun connectionIsNotCompleteDuringSaveAndSaveFailureIsSessionOnly() = runTest {
        for (saveWorks in listOf(true, false)) {
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
            val controller = f.controller(backgroundScope)
            var savingObserved = false
            f.store.onWrite = {
                savingObserved = true
                assertEquals(AppleMusicStatus.CONNECTED, f.session.state.value.status)
                assertEquals(AppleMusicRetentionStatus.SAVING, f.session.retention.value.status)
                assertTrue(controller.state.value.connecting)
                assertFalse(controller.state.value.canConnect)
                assertNotEquals(AppleMusicWebLoginMessage.SAVED, controller.state.value.message)
                saveWorks
            }
            controller.resume()
            runCurrent()
            controller.connect()
            runCurrent()
            assertTrue(savingObserved)
            assertEquals(if (saveWorks) AppleMusicWebLoginMessage.SAVED else AppleMusicWebLoginMessage.SESSION_ONLY,
                controller.state.value.message)
            assertEquals(if (saveWorks) AppleMusicRetentionStatus.SAVED else AppleMusicRetentionStatus.SESSION_ONLY,
                f.session.retention.value.status)
            controller.stop()
        }
    }

    @Test fun authenticationAndStorageFailuresNeverReportSavedOrLeakSecrets() = runTest {
        ShadowLog.clear()
        DiagLog.clearForTest()
        for (auth in listOf(true, false)) {
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
            if (auth) f.answer = { AppleConnectResult.Unavailable(AppleLyricsReason.AUTH_REQUIRED) }
            else f.store.clearWorks = false
            val controller = f.controller(backgroundScope)
            controller.resume()
            runCurrent()
            controller.connect()
            runCurrent()
            assertEquals(if (auth) AppleMusicWebLoginMessage.AUTH_REQUIRED else AppleMusicWebLoginMessage.FAILED,
                controller.state.value.message)
            assertNull(f.store.value)
            assertSafe(controller.state.value.toString())
            controller.stop()
        }
        assertSafe(*(DiagLog.dump() + ShadowLog.getLogs().map { it.msg }).toTypedArray())
    }

    @Test fun stopCancelsPendingConnectAndLateCompletionCannotReconnect() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<Unit>()
        lateinit var operation: Job
        f.answer = {
            operation = currentCoroutineContext().job
            withContext(NonCancellable) { pending.await() }
            AppleConnectResult.Ready("jp")
        }
        val controller = f.controller(backgroundScope)
        controller.resume()
        runCurrent()
        controller.connect()
        runCurrent()
        assertEquals(AppleMusicStatus.CHECKING, f.session.state.value.status)
        controller.stop()
        assertTrue(operation.isCancelled)
        pending.complete(Unit)
        runCurrent()
        assertEquals(AppleMusicStatus.UNCONFIGURED, f.session.state.value.status)
        assertNull(f.store.value)
        assertEquals(AppleMusicWebLoginState(), controller.state.value)
    }

    @Test fun pollsOnlyInForegroundAt1500msAndRejectsConnectDuringDeletion() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val controller = f.controller(backgroundScope)
        assertEquals(0, f.cookies.reads)
        controller.resume()
        controller.resume()
        runCurrent()
        assertEquals(1, f.cookies.reads)
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(1, f.cookies.reads)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, f.cookies.reads)
        controller.pause()
        advanceTimeBy(10_000)
        runCurrent()
        controller.connect()
        assertEquals(2, f.cookies.reads)
        assertTrue(f.inputs.isEmpty())
        controller.resume()
        runCurrent()
        assertEquals(3, f.cookies.reads)
        f.webData.clear()
        runCurrent()
        controller.connect()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, f.cookies.reads)
        assertTrue(f.inputs.isEmpty())
        assertEquals(AppleMusicWebDataState.CLEARING, f.webData.state.value)
        controller.stop()
        f.backend.complete()
        runCurrent()
    }

    @Test fun privateActivityUsesFixedUrlSecureSettingsAndNoSavedSecretState() {
        val cookies = Cookies()
        val activity = activity(cookies, Intent(Intent.ACTION_VIEW, Uri.parse(SECRET_URL)).putExtra("token", TOKEN)).get()
        val view = TestActivity.created.single()
        assertEquals(APPLE_MUSIC_LOGIN_URL, shadowOf(view).lastLoadedUrl)
        assertNull(activity.intent.data)
        assertNull(activity.intent.extras)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(view.isSaveEnabled)
        assertFalse(view.isSaveFromParentEnabled)
        val settings = view.settings
        assertTrue(settings.javaScriptEnabled)
        assertTrue(settings.domStorageEnabled)
        assertTrue(settings.supportMultipleWindows())
        assertTrue(settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        assertTrue(settings.mediaPlaybackRequiresUserGesture)
        assertTrue(settings.useWideViewPort)
        assertTrue(settings.loadWithOverviewMode)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getActivityInfo(ComponentName(app, AppleMusicWebLoginActivity::class.java), 0)
        assertFalse(info.exported)
        assertEquals(app.applicationInfo.processName, info.processName)
        val saved = Bundle()
        activities.single().saveInstanceState(saved)
        assertTrue(saved.isEmpty)
        assertSafe(activity.intent.toString(), saved.toString(), label(activity, "apple_web_origin"), label(activity, "apple_web_status"))
    }

    @Test fun popupLimitBootstrapBackReloadAndDesktopSwitchUseSafeCleanup() {
        val activity = activity().get()
        val main = TestActivity.created.single()
        main.loadUrl(APPLE_MUSIC_LOGIN_URL)
        val client = main.webViewClient
        assertTrue(client.shouldOverrideUrlLoading(main, "about:blank"))
        repeat(3) {
            assertTrue(popup(main))
            val child = TestActivity.created.last()
            assertFalse(child.webViewClient.shouldOverrideUrlLoading(child, "about:blank"))
            child.loadUrl("https://idmsa.apple.com/auth")
            assertFalse(child.webViewClient.shouldOverrideUrlLoading(child, "https://idmsa.apple.com/auth"))
            assertTrue(child.webViewClient.shouldOverrideUrlLoading(child, "about:blank"))
        }
        assertFalse(popup(main))
        assertEquals(4, frame(activity).childCount)
        val last = TestActivity.created.last()
        last.webChromeClient!!.onCloseWindow(last)
        assertTrue(last.destroyed)
        assertEquals(3, frame(activity).childCount)
        // A fresh popup has no safe back entry, so Back closes just that popup.
        assertTrue(popup(main))
        val empty = TestActivity.created.last()
        click(activity, "apple_web_back")
        assertTrue(empty.destroyed)
        assertFalse(activity.isFinishing)
        val active = TestActivity.created.last { !it.destroyed }
        click(activity, "apple_web_reload")
        assertEquals(1, active.reloads)
        val old = TestActivity.created.toList()
        click(activity, "apple_web_desktop")
        assertTrue(old.all { it.destroyed && it.stops > 0 })
        assertEquals(1, frame(activity).childCount)
        assertEquals(APPLE_MUSIC_LOGIN_URL, shadowOf(TestActivity.created.last()).lastLoadedUrl)
        assertEquals("スマホ表示", label(activity, "apple_web_desktop"))
        assertFalse(TestActivity.created.last().settings.userAgentString.contains(" Mobile"))
        assertFalse(popup(main)) // late callback from a destroyed view
    }

    @Test @Config(shadows = [SslHandlerShadow::class, HttpAuthHandlerShadow::class])
    fun externalNavigationSslPermissionsAuthAndConsoleAreRejectedWithoutSecretOutput() {
        ShadowLog.clear()
        DiagLog.clearForTest()
        val activity = activity().get()
        val view = TestActivity.created.single()
        val client = view.webViewClient
        for (url in listOf("https://example.com/$TOKEN", "intent://$TOKEN", "file:///$TOKEN",
            "content://$TOKEN", "http://music.apple.com/$TOKEN", "https://apple.com.example.org/$TOKEN", "about:blank")) {
            assertTrue(client.shouldOverrideUrlLoading(view, url))
        }
        assertFalse(client.shouldOverrideUrlLoading(view, SECRET_URL))
        client.onPageStarted(view, SECRET_URL, null)
        assertEquals("https://music.apple.com", label(activity, "apple_web_origin"))
        val sslHandler = ReflectionHelpers.newInstance(SslErrorHandler::class.java)
        client.onReceivedSslError(view, sslHandler,
            SslError(SslError.SSL_UNTRUSTED, SslCertificate("test", "test", Date(0), Date(1)), SECRET_URL))
        assertTrue(Shadow.extract<SslHandlerShadow>(sslHandler).cancelled)
        var denied = false
        view.webChromeClient!!.onPermissionRequest(object : PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse(SECRET_URL)
            override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)
            override fun grant(resources: Array<String>) { fail("Permission granted") }
            override fun deny() { denied = true }
        })
        assertTrue(denied)
        val authHandler = ReflectionHelpers.newInstance(HttpAuthHandler::class.java)
        client.onReceivedHttpAuthRequest(view, authHandler, SECRET_URL, TOKEN)
        assertTrue(Shadow.extract<HttpAuthHandlerShadow>(authHandler).cancelled)
        var certCancelled = false
        client.onReceivedClientCertRequest(view, object : ClientCertRequest() {
            override fun getKeyTypes() = arrayOf("RSA")
            override fun getPrincipals(): Array<Principal> = emptyArray()
            override fun getHost() = "music.apple.com"
            override fun getPort() = 443
            override fun proceed(privateKey: PrivateKey, chain: Array<X509Certificate>) { fail("Certificate accepted") }
            override fun ignore() { fail("Certificate request ignored instead of cancelled") }
            override fun cancel() { certCancelled = true }
        })
        assertTrue(certCancelled)
        assertTrue(view.webChromeClient!!.onConsoleMessage(ConsoleMessage(TOKEN, SECRET_URL, 1, ConsoleMessage.MessageLevel.ERROR)))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSafe(label(activity, "apple_web_origin"), label(activity, "apple_web_status"))
        assertSafe(*(DiagLog.dump() + ShadowLog.getLogs().map { it.msg + (it.throwable?.stackTraceToString() ?: "") }).toTypedArray())
    }

    @Test fun stopAndRecreationDestroyViewsDropHistoryAndOnlyResumePollingInForeground() {
        val cookies = Cookies()
        val controller = activity(cookies)
        val first = controller.get()
        val old = TestActivity.created.single()
        old.loadUrl(SECRET_URL)
        val saved = Bundle()
        controller.saveInstanceState(saved).pause()
        val reads = cookies.reads
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertEquals(reads, cookies.reads)
        controller.stop()
        assertTrue(old.destroyed)
        assertEquals(0, frame(first).childCount)
        controller.restart().start().resume()
        assertEquals(APPLE_MUSIC_LOGIN_URL, shadowOf(TestActivity.created.last()).lastLoadedUrl)
        assertEquals(reads + 1, cookies.reads)
        controller.pause().stop().destroy()
        val replacement = Robolectric.buildActivity(TestActivity::class.java).also { activities += it }
        replacement.create(saved).start().resume()
        assertEquals(APPLE_MUSIC_LOGIN_URL, shadowOf(TestActivity.created.last()).lastLoadedUrl)
        assertTrue(saved.isEmpty)
        assertNull(replacement.get().intent.extras)
        assertNull(shadowOf(replacement.get()).resultIntent)
    }

    @Test fun deletionClosesActivityAndLateCallbacksCannotRecreateOrConnect() {
        val backend = Backend()
        val data = AppleMusicWebData(appScope, backend)
        val cookies = Cookies()
        val activity = activity(cookies, data = data).get()
        val old = TestActivity.created.single()
        val chrome = old.webChromeClient!!
        val client = old.webViewClient
        data.clear()
        assertTrue(activity.isFinishing)
        assertTrue(old.destroyed)
        assertEquals(1, backend.deletes)
        assertEquals(AppleMusicWebDataState.CLEARING, data.state.value)
        val reads = cookies.reads
        client.onPageFinished(old, SECRET_URL)
        val message = popupMessage(old)
        assertFalse(chrome.onCreateWindow(old, false, true, message))
        click(activity, "apple_web_connect")
        assertEquals(reads, cookies.reads)
        assertEquals(0, frame(activity).childCount)
        backend.complete()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppleMusicWebDataState.CLEARED, data.state.value)
        assertEquals(0, frame(activity).childCount)
    }

    @Test fun providerAndRendererFailuresShowFixedRetryMessageAndManualFallback() {
        TestActivity.failCreation = true
        val activity = activity().get()
        assertFalse(activity.isFinishing)
        assertTrue(label(activity, "apple_web_status").contains("手動入力"))
        assertFalse(activity.window.decorView.findViewWithTag<Button>("apple_web_connect").isEnabled)
        assertSafe(label(activity, "apple_web_status"))
        TestActivity.failCreation = false
        click(activity, "apple_web_retry")
        val view = TestActivity.created.single()
        assertTrue(view.webViewClient.onRenderProcessGone(view, object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        }))
        assertTrue(view.destroyed)
        assertEquals(0, frame(activity).childCount)
        assertTrue(label(activity, "apple_web_status").contains("手動入力"))
        click(activity, "apple_web_close")
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test fun successfulWebConnectionReturnsOnlySuccessAndKeepsSessionResult() {
        val activity = activity().get()
        click(activity, "apple_web_connect")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
        assertEquals(AppleMusicStatus.CONNECTED, TestActivity.dependencies!!.session.state.value.status)
        assertTrue(TestActivity.created.all { it.destroyed })
        assertSafe(activity.intent.toString())
    }

    @Test fun userAgentUsesInstalledVersionAndSecretSnapshotHasFixedString() {
        val installed = "Mozilla/5.0 (Linux; Android 15; device; wv) AppleWebKit/537.36 Version/4.0 Chrome/156.0.12.34 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 15; device) AppleWebKit/537.36 Chrome/156.0.12.34 Mobile Safari/537.36",
            appleMusicUserAgent(installed, false))
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/156.0.12.34 Safari/537.36",
            appleMusicUserAgent(installed, true))
        val snapshot = AppleMusicWebCredential(TOKEN, null)
        assertEquals("AppleMusicWebCredential(redacted)", snapshot.toString())
        snapshot.release()
        assertNull(snapshot.token())
    }

    private fun activity(
        cookies: Cookies = Cookies(),
        intent: Intent = Intent(),
        data: AppleMusicWebData = AppleMusicWebData(appScope, Backend()),
    ): ActivityController<TestActivity> {
        TestActivity.dependencies = AppleMusicWebLoginDependencies(
            AppleMusicSession({ AppleConnectResult.Ready("jp") }, { _, _, _ -> error("Unexpected lyrics request") }), data, cookies)
        val controller = Robolectric.buildActivity(TestActivity::class.java, intent).also { activities += it }
        controller.create().start()
        assertEquals(0, cookies.reads)
        return controller.resume().visible()
    }

    private fun popupMessage(parent: WebView): Message = Message.obtain(Handler(Looper.getMainLooper()), Runnable {}).apply {
        obj = parent.WebViewTransport()
    }
    private fun popup(parent: WebView): Boolean = parent.webChromeClient?.onCreateWindow(parent, false, true, popupMessage(parent)) ?: false
    private fun click(activity: Activity, tag: String) { activity.window.decorView.findViewWithTag<Button>(tag).performClick() }
    private fun frame(activity: Activity): FrameLayout = activity.window.decorView.findViewWithTag("apple_web_frame")
    private fun label(activity: Activity, tag: String): String = activity.window.decorView.findViewWithTag<TextView>(tag).text.toString()
    private fun assertSafe(vararg outputs: String) {
        outputs.forEach { value -> listOf(TOKEN, SECOND, SECRET_URL).forEach { assertFalse("Secret in public output", value.contains(it)) } }
    }

    class TestActivity : AppleMusicWebLoginActivity() {
        override fun loginDependencies(): AppleMusicWebLoginDependencies = checkNotNull(dependencies)
        override fun newWebView(): WebView {
            if (failCreation) throw IllegalStateException(TOKEN)
            return TrackingWebView(this).also { created += it }
        }
        companion object {
            internal var dependencies: AppleMusicWebLoginDependencies? = null
            internal var failCreation = false
            internal val created = mutableListOf<TrackingWebView>()
        }
    }

    class TrackingWebView(activity: Activity) : WebView(activity) {
        var destroyed = false
        var stops = 0
        var reloads = 0
        override fun destroy() { destroyed = true; super.destroy() }
        override fun stopLoading() { stops++; super.stopLoading() }
        override fun reload() { reloads++; super.reload() }
    }

    @Implements(SslErrorHandler::class)
    class SslHandlerShadow {
        var cancelled = false
        @Implementation fun cancel() { cancelled = true }
        @Implementation fun proceed() { fail("SSL bypass") }
    }

    @Implements(HttpAuthHandler::class)
    class HttpAuthHandlerShadow {
        var cancelled = false
        @Implementation fun cancel() { cancelled = true }
        @Implementation fun proceed(username: String, password: String) { fail("HTTP auth accepted") }
    }

    private class Cookies : AppleMusicCookieSource {
        var value: String? = "media-user-token=$TOKEN"
        var details = emptyList<String>()
        var supported = true
        var infoFails = false
        var reads = 0
        var infoCalls = 0
        override fun header(): String? { reads++; return value }
        override fun supportsInfo() = supported
        override fun info(): List<String> {
            infoCalls++
            if (infoFails) throw IllegalStateException(TOKEN)
            return details
        }
    }

    private class Backend : AppleMusicWebData.Backend {
        var deletes = 0
        var callback: (() -> Unit)? = null
        override fun supported() = true
        override fun delete(onComplete: () -> Unit) { deletes++; callback = onComplete }
        fun complete() = checkNotNull(callback).invoke()
    }

    private class Store : AppleCredentialStore {
        var value: StoredAppleCredential? = null
        var onWrite: () -> Boolean = { true }
        var clearWorks = true
        override fun read() = AppleCredentialReadResult.Missing
        override fun write(credential: StoredAppleCredential): Boolean = onWrite().also { if (it) value = credential }
        override fun clear(): Boolean { if (clearWorks) value = null; return clearWorks }
    }

    private class Fixture(scope: CoroutineScope, io: kotlinx.coroutines.CoroutineDispatcher) {
        val store = Store()
        val cookies = Cookies()
        val backend = Backend()
        val webData = AppleMusicWebData(scope, backend)
        val inputs = mutableListOf<String>()
        var answer: suspend () -> AppleConnectResult = { AppleConnectResult.Ready("jp") }
        val session = AppleMusicSession({ inputs += it; answer() }, { _, _, _ -> error("Unexpected lyrics request") }, store, scope, io)
        fun controller(scope: CoroutineScope) = AppleMusicWebLoginController(scope, session, webData, cookies, clock = { 1_000L })
    }

    companion object {
        private const val TOKEN = "synthetic-web-secret-185"
        private const val SECOND = "synthetic-second-secret-185"
        private const val SECRET_URL = "https://music.apple.com/path/$TOKEN?token=$SECOND#$TOKEN"
    }
}
