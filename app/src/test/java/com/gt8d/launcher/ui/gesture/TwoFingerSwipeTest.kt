package com.gt8d.launcher.ui.gesture

import com.gt8d.launcher.edge.EdgeAction
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoFingerSwipeTest {
    @Test
    fun twoFinger_swipeLeft_next() {
        assertEquals(EdgeAction.Next, TwoFingerSwipe.classify(2, -200f, 10f, 2f))
    }

    @Test
    fun twoFinger_swipeRight_prev() {
        assertEquals(EdgeAction.Prev, TwoFingerSwipe.classify(2, 200f, -10f, 2f))
    }

    @Test
    fun twoFinger_oneFinger_none() {
        assertEquals(EdgeAction.None, TwoFingerSwipe.classify(1, -400f, 0f, 2f))
    }

    @Test
    fun twoFinger_threeFingers_none() {
        assertEquals(EdgeAction.None, TwoFingerSwipe.classify(3, -400f, 0f, 2f))
    }

    @Test
    fun twoFinger_tooShort_none() {
        assertEquals(EdgeAction.None, TwoFingerSwipe.classify(2, -159f, 0f, 2f))
        assertEquals(EdgeAction.Next, TwoFingerSwipe.classify(2, -160f, 0f, 2f))
    }

    @Test
    fun twoFinger_vertical_none() {
        assertEquals(EdgeAction.None, TwoFingerSwipe.classify(2, -200f, 100f, 2f))
    }
}
