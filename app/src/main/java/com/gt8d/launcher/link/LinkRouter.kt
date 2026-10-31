package com.gt8d.launcher.link

import com.gt8d.launcher.apps.OwnPackages
import com.gt8d.launcher.config.LinkConfig
import com.gt8d.launcher.config.LinkMode

sealed interface FrontState {
    data object Home : FrontState
    data class AppPanel(val left: String?) : FrontState
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
)

sealed interface LinkRoute {
    data object FullscreenBrowser : LinkRoute
    data class SplitWithBrowser(val left: String) : LinkRoute
}

object LinkRouter {
    const val CHROME_PKG = "com.android.chrome"
    private const val ANDROID_APP_PREFIX = "android-app://"

    fun referrerPackage(raw: String?): String? {
        if (raw == null || !raw.startsWith(ANDROID_APP_PREFIX)) return null
        val pkg = raw.removePrefix(ANDROID_APP_PREFIX).substringBefore('/')
        return pkg.ifEmpty { null }
    }

    fun detectFront(clues: FrontClues, ownPkg: String): FrontState {
        if (clues.homeResumed) return FrontState.Home
        if (clues.panelInSplit) return FrontState.AppPanel(clues.lastPair?.first)
        val pair = clues.lastPair
        val ref = clues.referrer?.takeIf { it.isNotEmpty() }
        if (
            pair != null &&
            !OwnPackages.isOwn(pair.first) &&
            !OwnPackages.isOwn(pair.second) &&
            !clues.otherForegroundAfterLastPair &&
            ref != null &&
            (ref == pair.first || ref == pair.second)
        ) {
            return FrontState.AppApp(pair.first, pair.second)
        }
        if (ref != null && !OwnPackages.isOwn(ref)) {
            return FrontState.Fullscreen(ref)
        }
        return FrontState.Unknown
    }

    fun route(url: String, cfg: LinkConfig, front: FrontState, vendorUsable: Boolean): LinkRoute {
        if (cfg.mode == LinkMode.Chrome || !vendorUsable) return LinkRoute.FullscreenBrowser
        val left = when (front) {
            is FrontState.AppPanel -> front.left
            is FrontState.AppApp -> front.left
            is FrontState.Fullscreen -> front.pkg
            FrontState.Home, FrontState.Unknown -> null
        }
        return if (left == null || left == cfg.browserPkg) LinkRoute.FullscreenBrowser
            else LinkRoute.SplitWithBrowser(left)
    }
}
