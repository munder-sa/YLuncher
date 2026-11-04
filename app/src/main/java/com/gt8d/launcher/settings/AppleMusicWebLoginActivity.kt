package com.gt8d.launcher.settings

// Adapted from the user-provided MusicToken MainActivity. Modified for direct session
// connection, strict Apple navigation, ephemeral views, and coordinated deletion.
// Attribution and licenses: assets/third_party/apple_music_webview.txt.

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.graphics.toArgb
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.media.lyrics.AppleMusicSession
import com.gt8d.launcher.media.lyrics.AppleWebTokenLogic
import com.gt8d.launcher.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class AppleMusicWebLoginDependencies(
    val session: AppleMusicSession,
    val webData: AppleMusicWebData,
    val cookies: AppleMusicCookieSource = AndroidAppleMusicCookies(),
)

open class AppleMusicWebLoginActivity : ComponentActivity() {
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var dependencies: AppleMusicWebLoginDependencies
    private lateinit var controller: AppleMusicWebLoginController
    private var registration: AppleMusicWebData.Registration? = null
    private lateinit var webFrame: FrameLayout
    private lateinit var origin: TextView
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var retryButton: Button
    private lateinit var desktopButton: Button
    private var mainWeb: WebView? = null
    private val popups = mutableListOf<WebView>()
    private val bootstrapping = mutableSetOf<WebView>()
    private var foreground = false
    private var failed = false
    private var desktop = false

    internal open fun loginDependencies() = AppleMusicWebLoginDependencies(AppGraph.appleMusic, AppGraph.appleMusicWebData)
    internal open fun newWebView(): WebView = WebView(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // No URL, token, saved hierarchy, or browser history is an input to this screen.
        super.onCreate(null)
        intent = Intent(this, AppleMusicWebLoginActivity::class.java)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setResult(RESULT_CANCELED)
        dependencies = loginDependencies()
        controller = AppleMusicWebLoginController(screenScope, dependencies.session, dependencies.webData, dependencies.cookies)
        buildLayout()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { browserBack() }
        })
        screenScope.launch {
            controller.state.collect { state ->
                connectButton.isEnabled = foreground && dependencies.webData.canStart && state.canConnect
                if (!failed && dependencies.webData.canStart) status.text = when (state.message) {
                    AppleMusicWebLoginMessage.WAITING -> "Apple Musicにログインしてください。"
                    AppleMusicWebLoginMessage.READY -> if (state.expiryKnown)
                        "このアカウントで接続できます。期限内でも再ログインが必要になる場合があります。"
                        else "このアカウントで接続できます。有効期限は取得できません。"
                    AppleMusicWebLoginMessage.EXPIRED, AppleMusicWebLoginMessage.AUTH_REQUIRED ->
                        "Apple Musicにもう一度ログインしてください。"
                    AppleMusicWebLoginMessage.CHECKING -> "接続を確認中"
                    AppleMusicWebLoginMessage.SAVING -> "接続情報を保存中"
                    AppleMusicWebLoginMessage.SAVED -> "この端末に保存済み"
                    AppleMusicWebLoginMessage.SESSION_ONLY -> "この起動中のみ接続できます"
                    AppleMusicWebLoginMessage.FAILED -> "接続または保存を確認できませんでした。設定画面で状態を確認してください。"
                }
                if (state.message == AppleMusicWebLoginMessage.SAVED || state.message == AppleMusicWebLoginMessage.SESSION_ONLY) {
                    // Settings observes Session's separate connection and retention flows.
                    setResult(RESULT_OK)
                    closeScreen()
                }
            }
        }
        screenScope.launch {
            dependencies.webData.state.collect { state ->
                retryButton.isEnabled = foreground && state != AppleMusicWebDataState.CLEARING
                if (state == AppleMusicWebDataState.CLEARING) {
                    connectButton.isEnabled = false
                    status.text = "Appleログイン情報を削除中"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        retryButton.isEnabled = dependencies.webData.canStart
        if (!failed) startBrowser()
    }

    override fun onPause() {
        foreground = false
        controller.pause()
        connectButton.isEnabled = false
        views().forEach { safely { it.onPause() } }
        super.onPause()
    }

    override fun onStop() {
        releaseBrowser()
        super.onStop()
    }

    override fun onDestroy() {
        releaseBrowser()
        screenScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // ComponentActivity may maintain its own registry, but none of it is exported here.
        super.onSaveInstanceState(Bundle())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) { /* Always start at the fixed URL. */ }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.Background0.toArgb())
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isSaveEnabled = false
        }
        root.addView(text("Apple Musicにログイン", 20f))
        val nav = LinearLayout(this)
        fun navButton(label: String, tag: String, action: () -> Unit): Button = button(label, tag, action).also {
            nav.addView(it, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        navButton("戻る", "apple_web_back", ::browserBack)
        navButton("再読込", "apple_web_reload", ::reload)
        desktopButton = navButton("PC表示", "apple_web_desktop", ::toggleDesktop)
        navButton("閉じる", "apple_web_close", ::closeScreen)
        root.addView(nav)
        origin = text(AppleWebTokenLogic.originLabel(APPLE_MUSIC_LOGIN_URL), 14f).apply { tag = "apple_web_origin" }
        root.addView(origin)
        webFrame = FrameLayout(this).apply {
            tag = "apple_web_frame"
            isSaveEnabled = false
            isSaveFromParentEnabled = false
        }
        root.addView(webFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text("Apple Musicにログインしてください。", 16f).apply { tag = "apple_web_status" }
        root.addView(status)
        retryButton = button("ログイン画面を再試行", "apple_web_retry") {
            if (foreground && dependencies.webData.canStart) { failed = false; startBrowser() }
        }.apply { visibility = View.GONE }
        root.addView(retryButton, LinearLayout.LayoutParams(-1, dp(60)))
        connectButton = button("このアカウントで接続", "apple_web_connect", controller::connect).apply { isEnabled = false }
        root.addView(connectButton, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(root)
    }

    private fun button(label: String, key: String, action: () -> Unit) = Button(this).apply {
        text = label
        tag = key
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Palette.Control.toArgb()))
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        isSaveEnabled = false
        setOnClickListener { action() }
    }

    private fun startBrowser() {
        if (!foreground || isFinishing || !dependencies.webData.canStart) return
        if (registration == null) registration = dependencies.webData.register(::closeScreen) ?: return
        try {
            if (mainWeb == null) {
                WebView.setWebContentsDebuggingEnabled(false)
                CookieManager.getInstance().setAcceptCookie(true)
                val view = createWebView()
                mainWeb = view
                webFrame.addView(view, FrameLayout.LayoutParams(-1, -1))
                view.loadUrl(APPLE_MUSIC_LOGIN_URL)
            }
            views().forEach { it.onResume() }
            retryButton.visibility = View.GONE
            controller.resume()
        } catch (_: Exception) { browserFailed() } catch (_: LinkageError) { browserFailed() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = newWebView()
        try {
            view.isSaveEnabled = false
            view.isSaveFromParentEnabled = false
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            view.setBackgroundColor(Color.WHITE)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = appleMusicUserAgent(WebSettings.getDefaultUserAgent(this@AppleMusicWebLoginActivity), desktop)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.setDownloadListener { _, _, _, _, _ -> /* Downloads stay disabled. */ }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean =
                    rejectNavigation(web, request.url.toString())
                @Deprecated("Required for older provider callbacks")
                override fun shouldOverrideUrlLoading(web: WebView, url: String): Boolean = rejectNavigation(web, url)
                override fun onPageStarted(web: WebView, url: String?, favicon: Bitmap?) {
                    if (rejectNavigation(web, url)) { safely { web.stopLoading() }; return }
                    if (web === activeWeb()) origin.text = AppleWebTokenLogic.originLabel(url)
                }
                override fun onPageFinished(web: WebView, url: String?) {
                    if (!live(web) || !allowed(web, url)) return
                    safely { CookieManager.getInstance().flush() }
                    controller.refresh()
                }
                override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (live(web) && request.isForMainFrame) status.text = "ページを読み込めませんでした。再読込をお試しください。"
                }
                override fun onReceivedSslError(web: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    if (live(web)) status.text = "安全な接続を確認できなかったため、読み込みを中止しました。"
                }
                override fun onReceivedHttpAuthRequest(web: WebView, handler: HttpAuthHandler, host: String, realm: String) = handler.cancel()
                override fun onReceivedClientCertRequest(web: WebView, request: ClientCertRequest) = request.cancel()
                override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (owns(web)) browserFailed()
                    return true
                }
            }
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                    callback.invoke(origin, false, false)
                }
                override fun onShowFileChooser(web: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                    callback.onReceiveValue(null)
                    return true
                }
                override fun onCreateWindow(parent: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean {
                    if (!live(parent) || popups.size >= 3 || !AppleWebTokenLogic.isAppleHttpsUrl(parent.url)) return false
                    val transport = result.obj as? WebView.WebViewTransport ?: return false
                    if (result.target == null) return false
                    return try {
                        val child = createWebView()
                        bootstrapping.add(child)
                        popups.add(child)
                        activeWebExcept(child)?.visibility = View.GONE
                        webFrame.addView(child, FrameLayout.LayoutParams(-1, -1))
                        transport.webView = child
                        result.sendToTarget()
                        origin.text = "Apple Music"
                        true
                    } catch (_: Exception) { browserFailed(); false }
                    catch (_: LinkageError) { browserFailed(); false }
                }
                override fun onCloseWindow(web: WebView) { if (live(web)) closePopup(web) }
            }
            return view
        } catch (error: Exception) { destroyView(view); throw error }
        catch (error: LinkageError) { destroyView(view); throw error }
    }

    private fun allowed(web: WebView, url: String?): Boolean {
        if (AppleWebTokenLogic.isAppleHttpsUrl(url)) { bootstrapping.remove(web); return true }
        return url == "about:blank" && web in bootstrapping
    }

    private fun rejectNavigation(web: WebView, url: String?): Boolean {
        if (live(web) && allowed(web, url)) return false
        if (live(web)) status.text = "この画面ではAppleのWebページを開いてください。"
        return true
    }

    private fun owns(web: WebView): Boolean = web === mainWeb || web in popups
    private fun live(web: WebView): Boolean = foreground && !isFinishing && dependencies.webData.canStart && owns(web)
    private fun views(): List<WebView> = listOfNotNull(mainWeb) + popups
    private fun activeWeb(): WebView? = popups.lastOrNull() ?: mainWeb
    private fun activeWebExcept(web: WebView): WebView? = popups.lastOrNull { it !== web } ?: mainWeb

    private fun closePopup(web: WebView) {
        val index = popups.indexOf(web)
        if (index < 0) return
        val removed = popups.subList(index, popups.size).toList()
        popups.subList(index, popups.size).clear()
        removed.reversed().forEach(::destroyView)
        activeWeb()?.let {
            it.visibility = View.VISIBLE
            origin.text = AppleWebTokenLogic.originLabel(it.url)
        }
        controller.refresh()
    }

    private fun browserBack() {
        val current = activeWeb()
        if (current == null || !live(current)) { closeScreen(); return }
        try {
            val history = current.copyBackForwardList()
            val previous = if (history.currentIndex > 0)
                history.getItemAtIndex(history.currentIndex - 1)?.url else null
            if (current.canGoBack() && AppleWebTokenLogic.isAppleHttpsUrl(previous)) {
                current.goBack()
                origin.text = AppleWebTokenLogic.originLabel(previous)
            } else if (current in popups) closePopup(current) else closeScreen()
        } catch (_: Exception) { browserFailed() } catch (_: LinkageError) { browserFailed() }
    }

    private fun reload() {
        val current = activeWeb() ?: return
        if (!live(current)) return
        try {
            if (allowed(current, current.url)) current.reload()
        } catch (_: Exception) { browserFailed() } catch (_: LinkageError) { browserFailed() }
    }

    private fun toggleDesktop() {
        if (!foreground || !dependencies.webData.canStart) return
        desktop = !desktop
        desktopButton.text = if (desktop) "スマホ表示" else "PC表示"
        releaseBrowser()
        failed = false
        startBrowser()
    }

    private fun closeScreen() {
        foreground = false
        releaseBrowser()
        finish()
    }

    private fun releaseBrowser() {
        controller.stop()
        registration?.close()
        registration = null
        val removed = views()
        mainWeb = null
        popups.clear()
        bootstrapping.clear()
        removed.reversed().forEach(::destroyView)
        origin.text = "Apple Music"
    }

    private fun destroyView(view: WebView) {
        bootstrapping.remove(view)
        (view.parent as? ViewGroup)?.removeView(view)
        safely { view.stopLoading() }
        // Drop clients/closures before destroy, and reject even queued callbacks via owns/live.
        safely { view.webChromeClient = null }
        safely { view.webViewClient = WebViewClient() }
        safely { view.setDownloadListener(null) }
        safely { view.destroy() }
    }

    private fun browserFailed() {
        failed = true
        releaseBrowser()
        status.text = "ログイン画面を利用できません。再試行するか、設定画面で手動入力してください。"
        connectButton.isEnabled = false
        retryButton.visibility = View.VISIBLE
    }

    private inline fun safely(action: () -> Unit) {
        try { action() } catch (_: Exception) { /* Never expose provider messages or URLs. */ }
        catch (_: LinkageError) { /* Provider unavailable. */ }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

internal fun appleMusicUserAgent(default: String, desktop: Boolean): String {
    val mobile = default.replace("; wv", "").replace(" Version/4.0", "")
    return if (desktop) mobile.replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
        .replace(" Mobile", "") else mobile
}
