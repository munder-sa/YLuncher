package com.gt8d.launcher.link

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.LinkConfig
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.split.SplitMode
import com.gt8d.launcher.ui.panel.PanelActivity
import com.gt8d.launcher.time.ElapsedClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LinkEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            DiagLog.d("Link", "recreated; link discarded")
            finish()
            return
        }
        var url: String? = null
        try {
            val homeFront = RecentFront.isFront(AppGraph.homeResumed, AppGraph.homePausedAtMs, ElapsedClock.nowMs())
            val panelFront = RecentFront.isFront(AppGraph.panelInSplit, AppGraph.panelSplitPausedAtMs, ElapsedClock.nowMs())
            val incoming = intent?.dataString ?: run {
                finish()
                return
            }
            url = incoming
            val ref = LinkRouter.referrerPackage(referrer?.toString())
            val pair = AppGraph.runner.lastOpenedPair
            val startedAt = AppGraph.runner.lastOpenedAtMs
            val other = pair != null && startedAt != null &&
                AppGraph.watcher.foregroundPkgsSince(startedAt).any {
                    it != pair.first && it != pair.second && it != packageName
                }
            val clues = FrontClues(
                homeResumed = homeFront,
                panelInSplit = panelFront,
                lastPair = pair,
                otherForegroundAfterLastPair = other,
                referrer = ref,
            )
            val flow = AppGraph.configForHome
            val config = flow.value
            if (config != null) {
                routeAndOpen(incoming, config.link, clues)
            } else {
                lifecycleScope.launch {
                    val loaded = try {
                        withTimeoutOrNull(CONFIG_WAIT_MS) { flow.filterNotNull().first() }
                    } catch (e: CancellationException) {
                        DiagLog.d("Link", "destroyed while waiting for config; link discarded")
                        return@launch
                    }
                    if (isDestroyed) {
                        DiagLog.d("Link", "destroyed while waiting for config; link discarded")
                        return@launch
                    }
                    routeAndOpen(incoming, loaded?.link ?: LinkConfig(), clues)
                }
            }
        } catch (e: Exception) {
            DiagLog.d("Link", "entry failed: $e")
            AppGraph.pendingUrl.take()
            url?.let { openFullscreen(applicationContext, it) }
            finish()
        }
    }

    private fun routeAndOpen(incoming: String, config: LinkConfig, clues: FrontClues) {
        try {
            val front = LinkRouter.detectFront(clues, packageName)
            val route = LinkRouter.route(incoming, config,
                front, AppGraph.selector.current() == SplitMode.Vendor)
            DiagLog.d("Link", "front=$front route=$route ref=${clues.referrer}")
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
            openFullscreen(applicationContext, incoming)
        } finally {
            finish()
        }
    }

    companion object {
        const val CONFIG_WAIT_MS = 1_500L
        private fun openFullscreen(context: Context, url: String) {
            runCatching {
                context.startActivity(Intent(context, FullscreenBrowserActivity::class.java)
                    .putExtra(EXTRA_URL, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { DiagLog.d("Link", "fullscreen failed: $it") }
        }
    }
}
