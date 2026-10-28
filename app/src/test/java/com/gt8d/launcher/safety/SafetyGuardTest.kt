package com.gt8d.launcher.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    @Test
    fun markStable_clearsStreak_keepsFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SafetyGuard.clear(context)

        repeat(2) {
            SafetyGuard.recordStart(context)
            SafetyGuard.recordCrash(context)
        }
        SafetyGuard.markStable(context)

        SafetyGuard.recordStart(context)
        SafetyGuard.recordCrash(context)

        assertFalse(SafetyGuard.isFallbackActive(context))
    }

    @Test
    fun markStable_doesNotClearActiveFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SafetyGuard.clear(context)

        SafetyGuard.recordStart(context)
        repeat(3) {
            SafetyGuard.recordCrash(context)
        }
        SafetyGuard.recordStart(context)
        SafetyGuard.markStable(context)

        assertTrue(SafetyGuard.isFallbackActive(context))
    }
}
