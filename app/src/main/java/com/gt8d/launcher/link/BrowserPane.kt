package com.gt8d.launcher.link

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.gt8d.launcher.ui.layout.OneLineText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.theme.Dims
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

const val EXTRA_URL = "url"

@Composable
fun BrowserPane(url: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var siteName by remember { mutableStateOf<String?>(null) }
    var appName by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
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
                    siteName = null
                    appName = null
                    pageTitle = null
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    pageTitle = view.title
                    view.evaluateJavascript("(function(){var og=document.querySelector('meta[property=\"og:site_name\"]');var app=document.querySelector('meta[name=\"application-name\"]');return JSON.stringify([og?og.content:null,app?app.content:null]);})()") { raw ->
                        val names = SiteLabel.parseJsString(raw)?.let {
                            try {
                                Json.parseToJsonElement(it) as? JsonArray
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        }
                        siteName = (names?.getOrNull(0) as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                        appName = (names?.getOrNull(1) as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                    }
                }
            }
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    LaunchedEffect(webView, url) {
        siteName = null
        appName = null
        pageTitle = null
        pageUrl = url
        webView.loadUrl(url)
    }
    val goBackOrClose: () -> Unit = {
        if (webView.canGoBack()) webView.goBack() else onClose()
    }
    BackHandler(onBack = goBackOrClose)
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 600.dp
            Row(
                Modifier.fillMaxWidth().background(Palette.Panel).sizeIn(minHeight = 72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = goBackOrClose,
                    modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp).testTag("browser_back")) {
                    OneLineText("戻る", color = Palette.Accent, fontWeight = FontWeight.SemiBold,
                        fontSize = Dims.BodyText)
                }
                OneLineText(SiteLabel.label(siteName, appName, pageTitle, pageUrl), modifier = Modifier.weight(1f),
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = Dims.BodyText)
                val openChrome = {
                    val current = webView.url ?: pageUrl
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current))
                            .setPackage(LinkRouter.CHROME_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    onClose()
                }
                if (compact) {
                    IconButton(onClick = openChrome, modifier = Modifier.size(Dims.MinTouch).testTag("browser_chrome")) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Chromeで開き直す", tint = Palette.Accent)
                    }
                } else TextButton(onClick = openChrome, modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp).testTag("browser_chrome")) {
                    OneLineText("Chromeで開き直す", color = Palette.Accent, fontWeight = FontWeight.SemiBold,
                        fontSize = Dims.BodyText)
                }
                TextButton(onClick = onClose,
                    modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp).testTag("browser_close")) {
                    OneLineText("閉じる", color = Palette.Accent, fontWeight = FontWeight.SemiBold,
                        fontSize = Dims.BodyText)
                }
            }
        }
        AndroidView(factory = { webView },
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("browser_web"))
    }
}
