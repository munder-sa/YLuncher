package com.gt8d.launcher.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {
    @Test
    fun threeQuickCrashes_fallback() {
        assertTrue(SafetyGuard.shouldFallback(listOf(2000L, 3000L, 9000L)))
    }

    @Test
    fun slowCrash_resetsStreak() {
        assertFalse(SafetyGuard.shouldFallback(listOf(2000L, 15000L, 3000L)))
    }

    @Test
    fun twoQuickCrashes_noFallback() {
        assertFalse(SafetyGuard.shouldFallback(listOf(2000L, 3000L)))
    }
}
