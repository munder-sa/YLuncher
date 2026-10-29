package com.gt8d.launcher.link

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.LinkConfig
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.split.SplitMode
import com.gt8d.launcher.ui.panel.PanelActivity

class LinkEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var url: String? = null
        try {
            val incoming = intent?.dataString ?: return
            url = incoming
            val ref = LinkRouter.referrerPackage(referrer?.toString())
            val pair = AppGraph.runner.lastOpenedPair
            val startedAt = AppGraph.runner.lastOpenedAtMs
            val other = pair != null && startedAt != null &&
                AppGraph.watcher.foregroundPkgsSince(startedAt).any {
                    it != pair.first && it != pair.second && it != packageName
                }
            val front = LinkRouter.detectFront(FrontClues(
                homeResumed = AppGraph.homeResumed,
                panelInSplit = AppGraph.panelInSplit,
                lastPair = pair,
                otherForegroundAfterLastPair = other,
                referrer = ref,
            ), packageName)
            val route = LinkRouter.route(incoming, AppGraph.latestConfig.value?.link ?: LinkConfig(),
                front, AppGraph.selector.current() == SplitMode.Vendor)
            DiagLog.d("Link", "front=$front route=$route ref=$ref")
            when (route) {
                LinkRoute.Chrome -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(incoming))
                    .setPackage(LinkRouter.CHROME_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                LinkRoute.FullscreenBrowser -> openFullscreen(applicationContext, incoming)
                LinkRoute.PanelTaskBrowser -> {
                    AppGraph.pendingUrl.set(incoming)
                    startActivity(Intent(this, PanelActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                is LinkRoute.VendorSplitThenBrowser -> {
                    val context = applicationContext
                    AppGraph.pendingUrl.set(incoming)
                    val accepted = AppGraph.runner.openForLink(route.left) {
                        AppGraph.pendingUrl.take()?.let { openFullscreen(context, it) }
                    }
                    if (!accepted) {
                        AppGraph.pendingUrl.take()
                        openFullscreen(context, incoming)
                    }
                }
            }
        } catch (e: Exception) {
            DiagLog.d("Link", "entry failed: $e")
            AppGraph.pendingUrl.take()
            url?.let { openFullscreen(applicationContext, it) }
        } finally {
            finish()
        }
    }

    companion object {
        private fun openFullscreen(context: Context, url: String) {
            runCatching {
                context.startActivity(Intent(context, FullscreenBrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { DiagLog.d("Link", "fullscreen failed: $it") }
        }
    }
}
