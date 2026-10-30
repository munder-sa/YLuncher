package com.gt8d.launcher.ui

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.link.FullscreenBrowserActivity
import com.gt8d.launcher.ui.home.HomeActivity
import com.gt8d.launcher.ui.panel.PanelActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskAffinityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun affinity(activity: Class<*>) = app.packageManager.getActivityInfo(
        ComponentName(app, activity), 0,
    ).taskAffinity

    @Test fun home_hasOwnAffinity() {
        assertEquals("com.gt8d.launcher.home", affinity(HomeActivity::class.java))
    }

    @Test fun panel_keepsPackageAffinity() {
        assertEquals(app.packageName, affinity(PanelActivity::class.java))
    }

    @Test fun home_and_panel_differ() {
        assertNotEquals(affinity(HomeActivity::class.java), affinity(PanelActivity::class.java))
    }

    @Test fun fullscreenBrowser_keepsOwnAffinity() {
        assertEquals("com.gt8d.launcher.browser", affinity(FullscreenBrowserActivity::class.java))
    }

    @Test fun launcherIntent_resolvesToPanel() {
        assertEquals(
            ComponentName(app, PanelActivity::class.java),
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.component,
        )
    }
}
