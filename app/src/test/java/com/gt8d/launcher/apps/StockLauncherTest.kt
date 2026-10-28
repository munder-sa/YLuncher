package com.gt8d.launcher.apps

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StockLauncherTest {
    @Test
    fun stockLauncherIntent_explicitComponent() {
        val intent = stockLauncherIntent()
        assertEquals(
            ComponentName(
                "com.android.launcher3",
                "com.android.launcher3.uioverrides.QuickstepLauncher",
            ),
            intent.component,
        )
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }
}
