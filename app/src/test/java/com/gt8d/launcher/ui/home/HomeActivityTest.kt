package com.gt8d.launcher.ui.home

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.safety.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeActivityTest {

    @Test
    fun homeActivity_safetyActive_startsStockLauncher() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SafetyGuard.clear(app)
        SafetyGuard.recordStart(app)
        SafetyGuard.recordCrash(app)
        SafetyGuard.recordCrash(app)
        SafetyGuard.recordCrash(app)
        assertTrue(SafetyGuard.isFallbackActive(app))

        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val started = shadowOf(activity).nextStartedActivity

        assertEquals(
            ComponentName(
                "com.android.launcher3",
                "com.android.launcher3.uioverrides.QuickstepLauncher",
            ),
            started.component,
        )
    }
}
