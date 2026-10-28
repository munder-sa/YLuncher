package com.gt8d.launcher.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitRatioTest {

    @Test
    fun ratio_vendorValues() {
        assertEquals(
            listOf("3:7", "2:3", "1:1", "3:2", "7:3"),
            SplitRatio.entries.map { it.vendorValue },
        )
        assertEquals(
            listOf("3:7", "4:6", "5:5", "6:4", "7:3"),
            SplitRatio.entries.map { it.label },
        )
    }

    @Test
    fun panelRatios_only46_55_64() {
        assertFalse(SplitRatio.R3_7.allowedFor(Slot.Panel))
        assertTrue(SplitRatio.R4_6.allowedFor(Slot.Panel))
        assertTrue(SplitRatio.R5_5.allowedFor(Slot.Panel))
        assertTrue(SplitRatio.R6_4.allowedFor(Slot.Panel))
        assertFalse(SplitRatio.R7_3.allowedFor(Slot.Panel))

        val appSlot = Slot.App("x")
        for (ratio in SplitRatio.entries) {
            assertTrue(ratio.allowedFor(appSlot))
        }
    }
}
