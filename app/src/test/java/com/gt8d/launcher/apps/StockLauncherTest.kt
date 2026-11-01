package com.gt8d.launcher.apps

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.ContextWrapper
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StockLauncherTest {
    @Test fun startStockLauncher_successUsesExistingIntent() {
        var received: Intent? = null
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { received = intent }
        }
        assertTrue(startStockLauncher(context))
        val expected = stockLauncherIntent()
        assertEquals(expected.component, received!!.component)
        assertEquals(expected.action, received!!.action)
        assertEquals(expected.flags, received!!.flags)
    }

    @Test fun startStockLauncher_exceptionReturnsFalse() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { throw IllegalStateException("failure") }
        }
        assertFalse(startStockLauncher(context))
    }
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
