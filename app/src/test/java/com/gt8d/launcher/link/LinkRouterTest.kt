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
            FrontState.AppPanel,
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

    @Test
    fun route_chromeMode_alwaysChrome() {
        assertEquals(
            LinkRoute.Chrome,
            LinkRouter.route(url, cfg.copy(mode = LinkMode.Chrome), FrontState.Home, vendorUsable = true),
        )
    }

    @Test
    fun route_googleLogin_goesChrome() {
        assertEquals(
            LinkRoute.Chrome,
            LinkRouter.route("https://accounts.google.com/signin", cfg, FrontState.Home, vendorUsable = true),
        )
        assertEquals(
            LinkRoute.Chrome,
            LinkRouter.route("https://ACCOUNTS.google.com/x", cfg, FrontState.Home, vendorUsable = true),
        )
        assertEquals(
            LinkRoute.Chrome,
            LinkRouter.route("https://a.accounts.google.com/x", cfg, FrontState.Home, vendorUsable = true),
        )
        assertEquals(
            LinkRoute.FullscreenBrowser,
            LinkRouter.route(
                "https://accounts.google.com.evil.example/x",
                cfg,
                FrontState.Home,
                vendorUsable = true,
            ),
        )
    }

    @Test
    fun route_badUrl_notChrome() {
        assertEquals(
            LinkRoute.FullscreenBrowser,
            LinkRouter.route("not a url", cfg, FrontState.Home, vendorUsable = true),
        )
    }

    @Test
    fun route_home_fullscreenBrowser() {
        assertEquals(
            LinkRoute.FullscreenBrowser,
            LinkRouter.route(url, cfg, FrontState.Home, vendorUsable = true),
        )
    }

    @Test
    fun route_appPanel_panelTaskBrowser() {
        assertEquals(
            LinkRoute.PanelTaskBrowser,
            LinkRouter.route(url, cfg, FrontState.AppPanel, vendorUsable = true),
        )
    }

    @Test
    fun route_appApp_vendorSplitWithLeft() {
        assertEquals(
            LinkRoute.VendorSplitThenBrowser("a"),
            LinkRouter.route(url, cfg, FrontState.AppApp("a", "b"), vendorUsable = true),
        )
    }

    @Test
    fun route_fullscreen_vendorSplitWithThatApp() {
        assertEquals(
            LinkRoute.VendorSplitThenBrowser("x"),
            LinkRouter.route(url, cfg, FrontState.Fullscreen("x"), vendorUsable = true),
        )
    }

    @Test
    fun route_unknown_fullscreenBrowser() {
        assertEquals(
            LinkRoute.FullscreenBrowser,
            LinkRouter.route(url, cfg, FrontState.Unknown, vendorUsable = true),
        )
    }

    @Test
    fun route_vendorUnusable_fullscreenBrowser() {
        assertEquals(
            LinkRoute.FullscreenBrowser,
            LinkRouter.route(url, cfg, FrontState.AppApp("a", "b"), vendorUsable = false),
        )
        assertEquals(
            LinkRoute.FullscreenBrowser,
            LinkRouter.route(url, cfg, FrontState.Fullscreen("x"), vendorUsable = false),
        )
        assertEquals(
            LinkRoute.PanelTaskBrowser,
            LinkRouter.route(url, cfg, FrontState.AppPanel, vendorUsable = false),
        )
    }
}
