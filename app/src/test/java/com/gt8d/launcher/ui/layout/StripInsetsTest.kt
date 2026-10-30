package com.gt8d.launcher.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class StripInsetsTest {
    private val left = PixelRect(0, 0, 48, 1080)
    private val right = PixelRect(1872, 0, 1920, 1080)

    @Test fun fullWindow_overlapsBoth() {
        assertEquals(48 to 48, stripOverlapPx(PixelRect(0, 0, 1920, 1080), left, right))
    }
    @Test fun rightWindow_overlapsRightOnly() {
        assertEquals(0 to 48, stripOverlapPx(PixelRect(1152, 0, 1920, 1080), left, right))
    }
    @Test fun leftWindow_overlapsLeftOnly() {
        assertEquals(48 to 0, stripOverlapPx(PixelRect(0, 0, 768, 1080), left, right))
    }
    @Test fun absentStrip_hasNoInset() {
        assertEquals(0 to 48, stripOverlapPx(PixelRect(0, 0, 1920, 1080), null, right))
        assertEquals(48 to 0, stripOverlapPx(PixelRect(0, 0, 1920, 1080), left, null))
        assertEquals(0 to 0, stripOverlapPx(PixelRect(0, 0, 1920, 1080), null, null))
    }
    @Test fun widerStrip_isClampedToWindow() {
        assertEquals(20 to 0, stripOverlapPx(PixelRect(10, 0, 30, 1080), left, right))
    }
}
