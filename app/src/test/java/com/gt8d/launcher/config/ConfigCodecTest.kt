package com.gt8d.launcher.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigCodecTest {

    @Test
    fun codec_roundTrip() {
        val original = LauncherConfig(
            version = 1,
            screenSets = listOf(
                ScreenSet("set1", Slot.App(Defaults.PKG_YOUTUBE), Slot.Panel),
                ScreenSet("set2", Slot.App(Defaults.PKG_YOUTUBE), Slot.App(Defaults.PKG_APPLE_MUSIC)),
            ),
            homeApps = listOf(Defaults.PKG_YOUTUBE, Defaults.PKG_CHROME),
            panelApps = listOf(Defaults.PKG_APPLE_MUSIC),
            dockApps = listOf(Defaults.PKG_NEWPIPE),
            idleApp = Defaults.PKG_APPLE_MUSIC,
            link = LinkConfig(mode = LinkMode.Chrome, chromeDomains = listOf("accounts.google.com", "example.com")),
            gesture = GestureConfig(stripWidthPx = 12, glow = false, longPressMs = 800),
        )

        val json = ConfigCodec.encode(original)
        val decoded = ConfigCodec.decode(json)

        assertEquals(original, decoded)
    }

    @Test
    fun decode_corruptJson_returnsNull() {
        assertNull(ConfigCodec.decode("{ not valid json"))
        assertNull(ConfigCodec.decode(""))
    }

    @Test
    fun decode_futureVersion_returnsNull() {
        val valid = Defaults.build(setOf(Defaults.PKG_YOUTUBE))
        val futureJson = ConfigCodec.encode(valid.copy(version = 2))
        assertNull(ConfigCodec.decode(futureJson))
    }
}
