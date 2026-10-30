package com.gt8d.launcher.link

import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gt8d.launcher.ui.theme.Palette

const val EXTRA_URL = "url"

@Composable
fun BrowserPane(url: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pageTitle by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf(url) }
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "http" || uri.scheme == "https") return false
                    runCatching {
                        context.startActivity(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
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
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    LaunchedEffect(webView, url) {
        pageTitle = ""
        pageUrl = url
        webView.loadUrl(url)
    }
    val goBackOrClose: () -> Unit = {
        if (webView.canGoBack()) webView.goBack() else onClose()
    }
    BackHandler(onBack = goBackOrClose)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Panel).sizeIn(minHeight = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = goBackOrClose,
                modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp).testTag("browser_back")) {
                Text("戻る")
            }
            Text(pageTitle.ifEmpty { pageUrl }, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = {
                val current = webView.url ?: pageUrl
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current))
                        .setPackage(LinkRouter.CHROME_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                onClose()
            }, modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp).testTag("browser_chrome")) {
                Text("Chromeで開き直す")
            }
            TextButton(onClick = onClose,
                modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp).testTag("browser_close")) {
                Text("閉じる")
            }
        }
        AndroidView(factory = { webView },
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("browser_web"))
    }
}
