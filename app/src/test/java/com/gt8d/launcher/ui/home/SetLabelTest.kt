package com.gt8d.launcher.ui.home

import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.SplitRatio
import org.junit.Assert.assertEquals
import org.junit.Test

class SetLabelTest {
    private val labelFor: (String) -> String = { if (it == "youtube") "YouTube" else "地図" }
    private fun set(left: Slot, right: Slot) = ScreenSet("s1", left, right, SplitRatio.R6_4)

    @Test fun rightPanel() {
        assertEquals("YouTube | パネル", setLabel(set(Slot.App("youtube"), Slot.Panel), labelFor))
    }
    @Test fun leftPanel() {
        assertEquals("パネル | YouTube", setLabel(set(Slot.Panel, Slot.App("youtube")), labelFor))
    }
    @Test fun bothApps() {
        assertEquals("YouTube | 地図", setLabel(set(Slot.App("youtube"), Slot.App("maps")), labelFor))
    }
    @Test fun numbered() {
        assertEquals("12  YouTube | パネル", numberedSetLabel(12, set(Slot.App("youtube"), Slot.Panel), labelFor))
    }
}
