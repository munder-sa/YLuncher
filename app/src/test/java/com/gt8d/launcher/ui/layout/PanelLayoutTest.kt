package com.gt8d.launcher.ui.layout

import org.junit.Assert.*
import org.junit.Test

class PanelLayoutTest {
    @Test fun homePlayerWidth_cases() {
        assertEquals(360f, PanelLayout.homePlayerWidthDp(840f), 0f)
        assertEquals(430.33f, PanelLayout.homePlayerWidthDp(1291f), 0.01f)
        assertEquals(600f, PanelLayout.homePlayerWidthDp(2000f), 0f)
    }

    @Test fun homeSetColumns_cases() {
        assertEquals(1, PanelLayout.homeSetColumns(623f))
        assertEquals(2, PanelLayout.homeSetColumns(624f))
    }
    @Test fun columns_boundaries() {
        listOf(87f to 1, 88f to 1, 183f to 1, 184f to 2, 279f to 2, 280f to 3)
            .forEach { (width, expected) -> assertEquals(expected, PanelLayout.columns(width)) }
    }

    @Test fun controlRows_fitOrWrap() {
        assertEquals(2, PanelLayout.controlRows(311f, 4))
        assertEquals(1, PanelLayout.controlRows(312f, 4))
    }

    @Test fun homePanelWidth_cases() {
        assertNull(PanelLayout.homePanelWidthDp(839f))
        assertEquals(516f, PanelLayout.homePanelWidthDp(840f)!!, 0f)
        assertEquals(580.95f, PanelLayout.homePanelWidthDp(1291f)!!, 0.01f)
        assertEquals(640f, PanelLayout.homePanelWidthDp(2000f)!!, 0f)
    }
}
