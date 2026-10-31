package com.gt8d.launcher.link

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.OwnPackages
import com.gt8d.launcher.config.LinkConfig
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.split.SplitMode
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
                    it != pair.first && it != pair.second && !OwnPackages.isOwn(it)
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
            url?.let { incoming -> openWithConfig(incoming, LinkConfig()) }
            finish()
        }
    }

    private fun routeAndOpen(incoming: String, config: LinkConfig, clues: FrontClues) {
        try {
            val front = LinkRouter.detectFront(clues, packageName)
            val browser = BrowserChooser.choose(config.browserPkg, BrowserChooser.installedBrowsers(packageManager))
            if (browser == null) {
                DiagLog.d("Link", "no browser")
                return
            }
            val route = LinkRouter.route(incoming, config.copy(browserPkg = browser),
                front, AppGraph.selector.current() == SplitMode.Vendor)
            DiagLog.d("Link", "front=$front route=$route ref=${clues.referrer}")
            val context = applicationContext
            val sendView = { openBrowser(context, incoming, browser) }
            when (route) {
                LinkRoute.FullscreenBrowser -> sendView()
                is LinkRoute.SplitWithBrowser -> {
                    if (!AppGraph.runner.openForLink(route.left, browser, onReady = sendView, onNotSplit = sendView)) {
                        sendView()
                    }
                }
            }
        } catch (e: Exception) {
            DiagLog.d("Link", "entry failed: $e")
            openWithConfig(incoming, config)
        } finally {
            finish()
        }
    }

    private fun openWithConfig(url: String, config: LinkConfig) {
        runCatching {
            val browser = BrowserChooser.choose(config.browserPkg, BrowserChooser.installedBrowsers(packageManager))
            if (browser == null) DiagLog.d("Link", "no browser")
            else openBrowser(applicationContext, url, browser)
        }.onFailure { DiagLog.d("Link", "browser lookup failed: $it") }
    }

    companion object {
        const val CONFIG_WAIT_MS = 1_500L
        private fun openBrowser(context: Context, url: String, browser: String) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setPackage(browser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { DiagLog.d("Link", "browser VIEW failed: $it") }
        }
    }
}
