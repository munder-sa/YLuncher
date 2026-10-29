package com.gt8d.launcher.link

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gt8d.launcher.ui.theme.LauncherTheme
import com.gt8d.launcher.ui.theme.Palette

open class BrowserActivity : ComponentActivity() {
    companion object { const val EXTRA_URL = "url" }

    private var webView: WebView? = null
    private var pageTitle by mutableStateOf("")
    private var pageUrl by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        pageUrl = url
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "http" || uri.scheme == "https") return false
                    runCatching {
                        startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    pageUrl = url.orEmpty()
                    pageTitle = ""
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    pageTitle = title.orEmpty()
                }
            }
            loadUrl(url)
        }
        setContent {
            LauncherTheme {
                BackHandler { goBackOrFinish() }
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(Palette.Panel).sizeIn(minHeight = 72.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = ::goBackOrFinish, modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)) {
                            Text("戻る")
                        }
                        Text(pageTitle.ifEmpty { pageUrl }, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = {
                            val current = webView?.url ?: pageUrl
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current))
                                    .setPackage(LinkRouter.CHROME_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            finish()
                        }, modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)) {
                            Text("Chromeで開き直す")
                        }
                        TextButton(onClick = { finish() }, modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)) {
                            Text("閉じる")
                        }
                    }
                    AndroidView(factory = { requireNotNull(webView) }, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) finish() else {
            pageTitle = ""
            pageUrl = url
            webView?.loadUrl(url)
        }
    }

    private fun goBackOrFinish() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else finish()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
