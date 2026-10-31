package com.gt8d.launcher.split

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelPlacementTest {
    @Test fun left() = assertEquals(PanelPlacement.Left, placementOf(0, 768, 1920, true))
    @Test fun right() = assertEquals(PanelPlacement.Right, placementOf(1167, 1920, 1920, true))
    @Test fun fullInMultiWindow() = assertEquals(PanelPlacement.Full, placementOf(0, 1920, 1920, true))
    @Test fun fullOutsideMultiWindow() = assertEquals(PanelPlacement.Full, placementOf(0, 1920, 1920, false))
    @Test fun unknown() = assertEquals(null, placementOf(300, 1200, 1920, true))
    @Test fun narrowOutsideMultiWindow() = assertEquals(PanelPlacement.Full, placementOf(0, 768, 1920, false))
    @Test fun zeroWidth() = assertEquals(null, placementOf(0, 0, 1920, true))
    @Test fun negativeWidth() = assertEquals(null, placementOf(100, 0, 1920, true))
    @Test fun zeroScreenWidth() = assertEquals(null, placementOf(0, 768, 0, true))
    @Test fun negativeScreenWidth() = assertEquals(null, placementOf(0, 768, -1, true))
}
