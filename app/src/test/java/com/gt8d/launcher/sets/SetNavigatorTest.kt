package com.gt8d.launcher.sets

import org.junit.Assert.assertEquals
import org.junit.Test

class SetNavigatorTest {
    @Test
    fun next_wrapsToHome() {
        assertEquals(0, SetNavigator.next(3, 3))
        assertEquals(1, SetNavigator.next(0, 3))
    }

    @Test
    fun prev_wrapsToLast() {
        assertEquals(3, SetNavigator.prev(0, 3))
        assertEquals(0, SetNavigator.prev(1, 3))
    }

    @Test
    fun next_withNoSets_staysHome() {
        assertEquals(0, SetNavigator.next(0, 0))
        assertEquals(0, SetNavigator.prev(0, 0))
    }

    @Test
    fun clamp_outOfRange_goesHome() {
        assertEquals(0, SetNavigator.clamp(5, 3))
        assertEquals(0, SetNavigator.clamp(-1, 3))
        assertEquals(2, SetNavigator.clamp(2, 3))
    }
}
