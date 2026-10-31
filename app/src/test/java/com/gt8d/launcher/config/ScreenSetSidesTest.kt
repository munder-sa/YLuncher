package com.gt8d.launcher.config

import org.junit.Assert.*
import org.junit.Test

class ScreenSetSidesTest {
    @Test fun mirrored_allRatios() {
        assertEquals(listOf(SplitRatio.R7_3, SplitRatio.R6_4, SplitRatio.R5_5, SplitRatio.R4_6, SplitRatio.R3_7),
            SplitRatio.entries.map { it.mirrored() })
    }

    @Test fun swapped_exchangesSidesAndRatioAndClearsLabel() {
        val set = ScreenSet("s", Slot.App("y"), Slot.Panel, SplitRatio.R6_4, "p")
        assertEquals(ScreenSet("s", Slot.Panel, Slot.App("y"), SplitRatio.R4_6, null), set.swapped())
        assertEquals(set.copy(appPairLabel = null), set.swapped().swapped())
    }

    @Test fun panelSide_andValidity() {
        val right = ScreenSet("s", Slot.App("a"), Slot.Panel)
        val left = right.swapped()
        val apps = right.copy(right = Slot.App("b"))
        assertEquals(Side.Right, right.panelSide)
        assertEquals(Side.Left, left.panelSide)
        assertNull(apps.panelSide)
        assertTrue(right.isValid)
        assertTrue(left.isValid)
        assertTrue(apps.isValid)
        assertFalse(right.copy(left = Slot.Panel).isValid)
    }

    @Test fun allowedFor_checksBothSides() {
        assertFalse(SplitRatio.R3_7.allowedFor(Slot.Panel, Slot.App("a")))
        assertTrue(SplitRatio.R4_6.allowedFor(Slot.Panel, Slot.App("a")))
        assertFalse(SplitRatio.R7_3.allowedFor(Slot.App("a"), Slot.Panel))
        SplitRatio.entries.forEach { assertTrue(it.allowedFor(Slot.App("a"), Slot.App("b"))) }
    }

    @Test fun slotAt_andAppPkgAt() {
        val set = ScreenSet("s", Slot.App("a"), Slot.Panel)
        assertEquals(Slot.App("a"), set.slotAt(Side.Left))
        assertEquals(Slot.Panel, set.slotAt(Side.Right))
        assertEquals("a", set.appPkgAt(Side.Left))
        assertNull(set.appPkgAt(Side.Right))
        assertNull(set.swapped().appPkgAt(Side.Left))
        assertEquals("a", set.swapped().appPkgAt(Side.Right))
    }
}
