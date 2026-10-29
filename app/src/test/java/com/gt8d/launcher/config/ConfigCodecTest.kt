package com.gt8d.launcher.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigCodecTest {

    @Test
    fun migrate_v1_nonDefaultSettings_preservesWholeConfig() {
        val input = """{"version":1,"screenSets":[{"id":"s1","left":{"pkg":"a"},"right":{"type":"com.gt8d.launcher.config.Slot.Panel"}},{"id":"s2","left":{"pkg":"b"},"right":{"type":"com.gt8d.launcher.config.Slot.App","pkg":"c"}}],"homeApps":["c","a"],"panelApps":["b","c"],"dockApps":["c","b"],"idleApp":"b","link":{"mode":"Chrome","chromeDomains":["example.org","example.net"]},"gesture":{"stripWidthPx":12,"glow":false,"longPressMs":800}}"""
        val expected = LauncherConfig(
            version = 2,
            screenSets = listOf(
                ScreenSet("s1", Slot.App("a"), Slot.Panel, SplitRatio.R5_5, null),
                ScreenSet("s2", Slot.App("b"), Slot.App("c"), SplitRatio.R5_5, null),
            ),
            homeApps = listOf("c", "a"),
            panelApps = listOf("b", "c"),
            dockApps = listOf("c", "b"),
            idleApp = "b",
            link = LinkConfig(LinkMode.Chrome, listOf("example.org", "example.net")),
            gesture = GestureConfig(12, false, 800),
            boot = BootConfig(waitForCar = false),
        )
        assertEquals(expected, ConfigCodec.decode(input))
    }

    @Test
    fun codec_roundTrip() {
        val original = LauncherConfig(
            version = 2,
            screenSets = listOf(
                ScreenSet(
                    id = "set1",
                    left = Slot.App(Defaults.PKG_YOUTUBE),
                    right = Slot.Panel,
                    ratio = SplitRatio.R6_4,
                    appPairLabel = "YouTube|GT8D パネル",
                ),
                ScreenSet(
                    id = "set2",
                    left = Slot.App(Defaults.PKG_YOUTUBE),
                    right = Slot.App(Defaults.PKG_APPLE_MUSIC),
                    ratio = SplitRatio.R4_6,
                    appPairLabel = null,
                ),
            ),
            homeApps = listOf(Defaults.PKG_YOUTUBE, Defaults.PKG_CHROME),
            panelApps = listOf(Defaults.PKG_APPLE_MUSIC),
            dockApps = listOf(Defaults.PKG_NEWPIPE),
            idleApp = Defaults.PKG_APPLE_MUSIC,
            link = LinkConfig(mode = LinkMode.Chrome, chromeDomains = listOf("accounts.google.com", "example.com")),
            gesture = GestureConfig(stripWidthPx = 12, glow = false, longPressMs = 800),
            boot = BootConfig(waitForCar = true),
        )

        val json = ConfigCodec.encode(original)
        val decoded = ConfigCodec.decode(json)

        assertEquals(original, decoded)
    }

    @Test
    fun migrate_v1_to_v2_keepsSettings() {
        val v1Json = """{"version":1,"screenSets":[{"id":"s1","left":{"pkg":"a"},"right":{"type":"com.gt8d.launcher.config.Slot.Panel"}}],"homeApps":["a","b"],"panelApps":[],"dockApps":[],"idleApp":"a","link":{"mode":"Chrome","chromeDomains":[]},"gesture":{"stripWidthPx":8,"glow":true,"longPressMs":1000}}"""

        val decoded = ConfigCodec.decode(v1Json)

        assertNotNull(decoded)
        decoded!!
        assertEquals(2, decoded.version)
        assertEquals(
            listOf(
                ScreenSet(
                    id = "s1",
                    left = Slot.App("a"),
                    right = Slot.Panel,
                    ratio = SplitRatio.R5_5,
                    appPairLabel = null,
                ),
            ),
            decoded.screenSets,
        )
        assertEquals(listOf("a", "b"), decoded.homeApps)
        assertEquals(LinkMode.Chrome, decoded.link.mode)
        assertEquals(BootConfig(waitForCar = false), decoded.boot)
    }

    @Test
    fun decode_corruptJson_returnsNull() {
        assertNull(ConfigCodec.decode("{ not valid json"))
        assertNull(ConfigCodec.decode(""))
    }

    @Test
    fun decode_version3_returnsNull() {
        val valid = Defaults.build(setOf(Defaults.PKG_YOUTUBE))
        assertNotNull(ConfigCodec.decode(ConfigCodec.encode(valid.copy(version = 2))))
        val futureJson = ConfigCodec.encode(valid.copy(version = 3))
        assertNull(ConfigCodec.decode(futureJson))
    }
}
