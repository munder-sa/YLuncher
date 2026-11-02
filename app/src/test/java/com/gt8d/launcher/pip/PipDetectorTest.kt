package com.gt8d.launcher.pip

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PipDetectorTest {
    @Test fun pipWatcherEnabled_matchesOnlyOwnService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val original = Settings.Secure.getString(context.contentResolver, key)
        val own = ComponentName(context, PipWatcherService::class.java)
        try {
            for (value in listOf(null, "", "other.app/.Service", "invalid")) {
                Settings.Secure.putString(context.contentResolver, key, value)
                assertFalse(pipWatcherEnabled(context))
            }
            for (value in listOf(own.flattenToString(), "other.app/.Service:${own.flattenToShortString()}")) {
                Settings.Secure.putString(context.contentResolver, key, value)
                assertTrue(pipWatcherEnabled(context))
            }
        } finally {
            Settings.Secure.putString(context.contentResolver, key, original)
        }
    }

    @Test fun androidPipDetector_withoutService_returnsFalse() {
        assertNull(PipWatcherService.instance)
        assertFalse(AndroidPipDetector.isInPip("example.app"))
    }
}
