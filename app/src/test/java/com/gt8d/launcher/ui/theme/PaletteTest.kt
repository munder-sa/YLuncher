package com.gt8d.launcher.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteTest {

    @Test
    fun palette_matchesDesign() {
        assertEquals(Color(0xFF0B0B0F), Palette.Background0)
        assertEquals(Color(0xFF17171D), Palette.Background1)
        assertEquals(Color(0xFF2E2E38), Palette.Panel)
        assertEquals(Color(0xFF8FE63B), Palette.Accent)
        assertEquals(Color(0xFFFFFFFF), Palette.PanelAccent)
    }
}
