package com.gt8d.launcher.link

import com.gt8d.launcher.config.LinkConfig
import com.gt8d.launcher.config.LinkMode
import java.net.URI
import java.util.Locale

sealed interface FrontState {
    data object Home : FrontState
    data object AppPanel : FrontState
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
    data object Chrome : LinkRoute
    data object FullscreenBrowser : LinkRoute
    data object PanelTaskBrowser : LinkRoute
    data class VendorSplitThenBrowser(val left: String) : LinkRoute
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
        if (clues.panelInSplit) return FrontState.AppPanel
        val pair = clues.lastPair
        val ref = clues.referrer?.takeIf { it.isNotEmpty() }
        if (
            pair != null &&
            pair.first != ownPkg &&
            pair.second != ownPkg &&
            !clues.otherForegroundAfterLastPair &&
            ref != null &&
            (ref == pair.first || ref == pair.second)
        ) {
            return FrontState.AppApp(pair.first, pair.second)
        }
        if (ref != null && ref != ownPkg) {
            return FrontState.Fullscreen(ref)
        }
        return FrontState.Unknown
    }

    fun route(url: String, cfg: LinkConfig, front: FrontState, vendorUsable: Boolean): LinkRoute {
        if (cfg.mode == LinkMode.Chrome) return LinkRoute.Chrome

        val host = try {
            URI(url).host?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
        if (host != null && cfg.chromeDomains.any { domain ->
                val d = domain.lowercase(Locale.ROOT)
                d.isNotEmpty() && (host == d || host.endsWith(".$d"))
            }
        ) {
            return LinkRoute.Chrome
        }

        return when (front) {
            FrontState.Home -> LinkRoute.FullscreenBrowser
            FrontState.AppPanel -> LinkRoute.PanelTaskBrowser
            is FrontState.AppApp ->
                if (vendorUsable) LinkRoute.VendorSplitThenBrowser(front.left)
                else LinkRoute.FullscreenBrowser
            is FrontState.Fullscreen ->
                if (vendorUsable) LinkRoute.VendorSplitThenBrowser(front.pkg)
                else LinkRoute.FullscreenBrowser
            FrontState.Unknown -> LinkRoute.FullscreenBrowser
        }
    }
}
