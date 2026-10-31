package com.gt8d.launcher.ui.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiIndicatorTest {
    @Test fun disabled_isOff() {
        assertEquals(WifiIndicator.Off, wifiIndicatorOf(false, true, 4, 4))
        assertEquals(WifiIndicator.Off, wifiIndicatorOf(false, false, 0, 4))
    }
    @Test fun enabledWithoutConnection_isDisconnected() {
        assertEquals(WifiIndicator.Disconnected, wifiIndicatorOf(true, false, 4, 4))
    }
    @Test fun fourLevels_mapDirectly() {
        for (level in 0..4) assertEquals(WifiIndicator.Connected(level), wifiIndicatorOf(true, true, level, 4))
    }
    @Test fun threeLevels_roundToNearest() {
        listOf(0, 1, 3, 4).forEachIndexed { level, bars ->
            assertEquals(WifiIndicator.Connected(bars), wifiIndicatorOf(true, true, level, 3))
        }
    }
    @Test fun nonPositiveMax_isZero() {
        for (max in listOf(0, -1)) assertEquals(WifiIndicator.Connected(0), wifiIndicatorOf(true, true, 4, max))
    }
    @Test fun outOfRangeLevels_areClamped() {
        assertEquals(WifiIndicator.Connected(0), wifiIndicatorOf(true, true, Int.MIN_VALUE, 4))
        assertEquals(WifiIndicator.Connected(4), wifiIndicatorOf(true, true, Int.MAX_VALUE, 4))
        assertEquals(WifiIndicator.Connected(4), wifiIndicatorOf(true, true, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
