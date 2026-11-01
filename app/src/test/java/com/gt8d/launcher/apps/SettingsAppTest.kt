package com.gt8d.launcher.apps

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsAppTest {
    private val pm = ApplicationProvider.getApplicationContext<Application>().packageManager
    private fun register(pkg: String, launcher: Boolean): ComponentName {
        val component = ComponentName(pkg, "$pkg.MainActivity")
        shadowOf(pm).addOrUpdateActivity(ActivityInfo().apply {
            packageName = pkg; name = component.className
        })
        shadowOf(pm).addIntentFilterForActivity(component, if (launcher)
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        else IntentFilter(Settings.ACTION_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        return component
    }
    @Test fun sudingPreferredOverActionSettings() {
        register("fallback", false)
        val suding = register("com.suding.settings", true)
        assertEquals(suding, settingsComponent(pm))
    }
    @Test fun fallbackToActionSettings() {
        assertEquals(register("fallback", false), settingsComponent(pm))
    }
    @Test fun noActivityReturnsNull() { assertNull(settingsComponent(pm)) }
}
