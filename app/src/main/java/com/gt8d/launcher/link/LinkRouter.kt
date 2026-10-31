package com.gt8d.launcher.link

import com.gt8d.launcher.apps.OwnPackages
import com.gt8d.launcher.config.LinkConfig
import com.gt8d.launcher.config.LinkMode
import com.gt8d.launcher.config.Side

sealed interface FrontState {
    data object Home : FrontState
    data class AppPanel(val app: String?, val panelSide: Side) : FrontState
    data class AppBrowser(val app: String, val browserSide: Side) : FrontState
    data class AppApp(val left: String, val right: String) : FrontState
    data class Fullscreen(val pkg: String) : FrontState
    data object Unknown : FrontState
}

data class FrontClues(
    val homeResumed: Boolean,
    val panelInSplit: Boolean,
    val lastPair: Pair<String, String>?,
    val otherForegroundAfterLastPair: Boolean,
    val referrer: String?,
    val panelSide: Side? = null,
)

sealed interface LinkRoute {
    data object FullscreenBrowser : LinkRoute
    data class SplitWithBrowser(val app: String, val browserSide: Side) : LinkRoute
}

object LinkRouter {
    const val CHROME_PKG = "com.android.chrome"
    private const val ANDROID_APP_PREFIX = "android-app://"

    fun referrerPackage(raw: String?): String? {
        if (raw == null || !raw.startsWith(ANDROID_APP_PREFIX)) return null
        val pkg = raw.removePrefix(ANDROID_APP_PREFIX).substringBefore('/')
        return pkg.ifEmpty { null }
    }

    fun detectFront(clues: FrontClues, ownPkg: String, browserPkg: String): FrontState {
        if (clues.homeResumed) return FrontState.Home
        val pair = clues.lastPair
        if (clues.panelInSplit) {
            val pairSide = when (OwnPackages.PANEL_HOST) {
                pair?.first -> Side.Left
                pair?.second -> Side.Right
                else -> null
            }
            val app = pair?.let {
                if (it.first == OwnPackages.PANEL_HOST) it.second else it.first
            }
            return FrontState.AppPanel(app, clues.panelSide ?: pairSide ?: Side.Right)
        }
        val ref = clues.referrer?.takeIf { it.isNotEmpty() }
        if (
            pair != null &&
            !OwnPackages.isOwn(pair.first) &&
            !OwnPackages.isOwn(pair.second) &&
            !clues.otherForegroundAfterLastPair &&
            ref != null &&
            (ref == pair.first || ref == pair.second)
        ) {
            if (pair.first == browserPkg) return FrontState.AppBrowser(pair.second, Side.Left)
            if (pair.second == browserPkg) return FrontState.AppBrowser(pair.first, Side.Right)
            return FrontState.AppApp(pair.first, pair.second)
        }
        if (ref != null && !OwnPackages.isOwn(ref)) {
            return FrontState.Fullscreen(ref)
        }
        return FrontState.Unknown
    }

    fun route(url: String, cfg: LinkConfig, front: FrontState, vendorUsable: Boolean): LinkRoute {
        if (cfg.mode == LinkMode.Chrome || !vendorUsable) return LinkRoute.FullscreenBrowser
        val app = when (front) {
            is FrontState.AppPanel -> front.app
            is FrontState.AppBrowser -> front.app
            is FrontState.AppApp -> front.left
            is FrontState.Fullscreen -> front.pkg
            FrontState.Home, FrontState.Unknown -> null
        }
        val side = when (front) {
            is FrontState.AppPanel -> front.panelSide
            is FrontState.AppBrowser -> front.browserSide
            else -> Side.Right
        }
        return if (app == null || app == cfg.browserPkg) LinkRoute.FullscreenBrowser
            else LinkRoute.SplitWithBrowser(app, side)
    }
}
