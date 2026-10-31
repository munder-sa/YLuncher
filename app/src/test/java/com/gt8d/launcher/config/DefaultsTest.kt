package com.gt8d.launcher.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultsTest {

    private val allPackages = setOf(
        Defaults.PKG_VIDEO,
        Defaults.PKG_VLC,
        Defaults.PKG_UNEXT,
        Defaults.PKG_PRIME_VIDEO,
        Defaults.PKG_TVER,
        Defaults.PKG_ABEMA,
        Defaults.PKG_APPLE_MUSIC,
        Defaults.PKG_NEWPIPE,
        Defaults.PKG_YOUTUBE,
        Defaults.PKG_CHROME,
        Defaults.PKG_MAPS,
    )

    @Test
    fun build_allInstalled_matchesSpec() {
        val cfg = Defaults.build(allPackages)

        assertEquals(2, cfg.version)
        assertEquals(
            listOf(
                ScreenSet("set1", Slot.App(Defaults.PKG_YOUTUBE), Slot.Panel, SplitRatio.R6_4),
                ScreenSet("set2", Slot.App(Defaults.PKG_YOUTUBE), Slot.App(Defaults.PKG_APPLE_MUSIC), SplitRatio.R6_4),
                ScreenSet("set3", Slot.App(Defaults.PKG_YOUTUBE), Slot.App(Defaults.PKG_MAPS), SplitRatio.R5_5),
            ),
            cfg.screenSets,
        )
        assertEquals(
            listOf(
                Defaults.PKG_VIDEO,
                Defaults.PKG_VLC,
                Defaults.PKG_UNEXT,
                Defaults.PKG_PRIME_VIDEO,
                Defaults.PKG_TVER,
                Defaults.PKG_ABEMA,
                Defaults.PKG_APPLE_MUSIC,
                Defaults.PKG_NEWPIPE,
                Defaults.PKG_YOUTUBE,
                Defaults.PKG_CHROME,
                Defaults.PKG_MAPS,
            ),
            cfg.homeApps,
        )
        assertEquals(
            listOf(
                Defaults.PKG_APPLE_MUSIC,
                Defaults.PKG_YOUTUBE,
                Defaults.PKG_NEWPIPE,
                Defaults.PKG_MAPS,
                Defaults.PKG_CHROME,
            ),
            cfg.panelApps,
        )
        assertEquals(
            listOf(
                Defaults.PKG_NEWPIPE,
                Defaults.PKG_CHROME,
                Defaults.PKG_YOUTUBE,
                Defaults.PKG_APPLE_MUSIC,
            ),
            cfg.dockApps,
        )
        assertEquals(Defaults.PKG_APPLE_MUSIC, cfg.idleApp)
        assertEquals("com.android.chrome", cfg.link.browserPkg)
        assertEquals(LinkMode.InAppSplit, cfg.link.mode)
        assertEquals(listOf("accounts.google.com"), cfg.link.chromeDomains)
        assertEquals(GestureConfig(stripWidthPx = 48, glow = true, longPressMs = 1000), cfg.gesture)
    }

    @Test
    fun build_skipsNotInstalled() {
        val installedWithoutVlc = allPackages - Defaults.PKG_VLC
        val cfg = Defaults.build(installedWithoutVlc)

        assertFalse(cfg.homeApps.contains(Defaults.PKG_VLC))
        assertEquals(10, cfg.homeApps.size)
        assertEquals(3, cfg.screenSets.size)
        assertEquals(Defaults.PKG_APPLE_MUSIC, cfg.idleApp)
    }

    @Test
    fun resolve_dropsUninstalledFromLists() {
        val cfg = Defaults.build(allPackages)
        val remaining = allPackages - setOf(Defaults.PKG_YOUTUBE, Defaults.PKG_VLC)
        val resolved = cfg.resolve(remaining)

        assertFalse(resolved.homeApps.contains(Defaults.PKG_YOUTUBE))
        assertFalse(resolved.homeApps.contains(Defaults.PKG_VLC))
        assertFalse(resolved.panelApps.contains(Defaults.PKG_YOUTUBE))
        assertFalse(resolved.dockApps.contains(Defaults.PKG_YOUTUBE))
        assertEquals(cfg.screenSets, resolved.screenSets)
        assertEquals(cfg.idleApp, resolved.idleApp)
    }
}
