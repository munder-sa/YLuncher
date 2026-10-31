package com.gt8d.launcher.link

import com.gt8d.launcher.config.LinkConfig
import com.gt8d.launcher.config.LinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkRouterTest {
    @Test fun lastPair_withHost_isNotAppApp() {
        assertEquals(FrontState.Fullscreen("a"), LinkRouter.detectFront(
            clues(lastPair = "a" to "com.gt8d.launcher.panel", referrer = "a"), own))
        assertEquals(FrontState.Fullscreen("a"), LinkRouter.detectFront(
            clues(lastPair = "com.gt8d.launcher.panel" to "a", referrer = "a"), own))
    }

    @Test fun referrer_host_isIgnored() {
        assertEquals(FrontState.Unknown,
            LinkRouter.detectFront(clues(referrer = "com.gt8d.launcher.panel"), own))
    }

    private val own = "com.gt8d.launcher"
    private val cfg = LinkConfig()
    private val url = "https://example.com/"

    private fun clues(
        homeResumed: Boolean = false,
        panelInSplit: Boolean = false,
        lastPair: Pair<String, String>? = null,
        otherForegroundAfterLastPair: Boolean = false,
        referrer: String? = null,
    ) = FrontClues(
        homeResumed = homeResumed,
        panelInSplit = panelInSplit,
        lastPair = lastPair,
        otherForegroundAfterLastPair = otherForegroundAfterLastPair,
        referrer = referrer,
    )

    @Test
    fun referrer_parsesAndroidAppScheme() {
        assertEquals(
            "jp.unext.mediaplayer",
            LinkRouter.referrerPackage("android-app://jp.unext.mediaplayer"),
        )
        assertEquals(
            "org.videolan.vlc",
            LinkRouter.referrerPackage("android-app://org.videolan.vlc/path"),
        )
        assertNull(LinkRouter.referrerPackage(null))
        assertNull(LinkRouter.referrerPackage(""))
        assertNull(LinkRouter.referrerPackage("https://example.com"))
    }

    @Test
    fun detect_homeResumed_isHome() {
        assertEquals(
            FrontState.Home,
            LinkRouter.detectFront(clues(homeResumed = true, panelInSplit = true), own),
        )
    }

    @Test
    fun detect_panelInSplit_isAppPanel() {
        assertEquals(
            FrontState.AppPanel(null),
            LinkRouter.detectFront(clues(panelInSplit = true), own),
        )
    }

    @Test
    fun detect_lastPairAndReferrerMatches_isAppApp() {
        val pair = "org.videolan.vlc" to "com.apple.android.music"
        assertEquals(
            FrontState.AppApp("org.videolan.vlc", "com.apple.android.music"),
            LinkRouter.detectFront(
                clues(lastPair = pair, referrer = "com.apple.android.music"),
                own,
            ),
        )
    }

    @Test
    fun detect_lastPairWithPanel_notAppApp() {
        val pair = "org.videolan.vlc" to own
        assertEquals(
            FrontState.Fullscreen("org.videolan.vlc"),
            LinkRouter.detectFront(
                clues(lastPair = pair, referrer = "org.videolan.vlc"),
                own,
            ),
        )
    }

    @Test
    fun detect_otherForegroundAfterPair_isFullscreen() {
        val pair = "org.videolan.vlc" to "com.apple.android.music"
        assertEquals(
            FrontState.Fullscreen("com.apple.android.music"),
            LinkRouter.detectFront(
                clues(
                    lastPair = pair,
                    otherForegroundAfterLastPair = true,
                    referrer = "com.apple.android.music",
                ),
                own,
            ),
        )
    }

    @Test
    fun detect_referrerOnly_isFullscreen() {
        assertEquals(
            FrontState.Fullscreen("jp.unext.mediaplayer"),
            LinkRouter.detectFront(clues(referrer = "jp.unext.mediaplayer"), own),
        )
    }

    @Test
    fun detect_referrerIsOwn_isUnknown() {
        assertEquals(
            FrontState.Unknown,
            LinkRouter.detectFront(clues(referrer = own), own),
        )
    }

    @Test
    fun detect_noClues_isUnknown() {
        assertEquals(
            FrontState.Unknown,
            LinkRouter.detectFront(clues(), own),
        )
    }


    @Test fun home_fullscreen() {
        assertEquals(LinkRoute.FullscreenBrowser, LinkRouter.route(url, cfg, FrontState.Home, true))
    }
    @Test fun fullscreenApp_splitWithSender() {
        assertEquals(LinkRoute.SplitWithBrowser("x"), LinkRouter.route(url, cfg, FrontState.Fullscreen("x"), true))
    }
    @Test fun appPanel_splitWithLastLeft() {
        val front = LinkRouter.detectFront(clues(panelInSplit = true, lastPair = "a" to own), own)
        assertEquals(FrontState.AppPanel("a"), front)
        assertEquals(LinkRoute.SplitWithBrowser("a"), LinkRouter.route(url, cfg, front, true))
    }
    @Test fun appPanel_unknownLeft_fullscreen() {
        assertEquals(LinkRoute.FullscreenBrowser, LinkRouter.route(url, cfg, FrontState.AppPanel(null), true))
    }
    @Test fun appApp_splitWithLeft() {
        assertEquals(LinkRoute.SplitWithBrowser("a"), LinkRouter.route(url, cfg, FrontState.AppApp("a", "b"), true))
    }
    @Test fun modeFull_alwaysFullscreen() {
        listOf(FrontState.Home, FrontState.Unknown, FrontState.Fullscreen("x"), FrontState.AppPanel("a"),
            FrontState.AppApp("a", "b")).forEach {
            assertEquals(LinkRoute.FullscreenBrowser, LinkRouter.route(url, cfg.copy(mode = LinkMode.Chrome), it, true))
        }
    }
    @Test fun vendorUnusable_fullscreen() {
        listOf(FrontState.Fullscreen("x"), FrontState.AppApp("a", "b"), FrontState.AppPanel("a")).forEach {
            assertEquals(LinkRoute.FullscreenBrowser, LinkRouter.route(url, cfg, it, false))
        }
    }
    @Test fun googleLogin_noLongerSpecial() {
        listOf("accounts.google.com", "ACCOUNTS.google.com", "a.accounts.google.com", "accounts.google.com.evil.example").forEach {
            assertEquals(LinkRoute.SplitWithBrowser("x"),
                LinkRouter.route("https://$it/signin", cfg, FrontState.Fullscreen("x"), true))
        }
    }
    @Test fun leftIsBrowser_fullscreen() {
        listOf(FrontState.Fullscreen(cfg.browserPkg), FrontState.AppPanel(cfg.browserPkg),
            FrontState.AppApp(cfg.browserPkg, "b")).forEach {
            assertEquals(LinkRoute.FullscreenBrowser, LinkRouter.route(url, cfg, it, true))
        }
    }
    @Test
    fun route_badUrl_notChrome() {
        assertEquals(
            LinkRoute.FullscreenBrowser,
            LinkRouter.route("not a url", cfg, FrontState.Home, vendorUsable = true),
        )
    }

    @Test
    fun route_unknown_fullscreenBrowser() {
        assertEquals(
            LinkRoute.FullscreenBrowser,
            LinkRouter.route(url, cfg, FrontState.Unknown, vendorUsable = true),
        )
    }
}
