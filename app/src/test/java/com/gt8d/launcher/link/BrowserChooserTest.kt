package com.gt8d.launcher.link

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserChooserTest {
    @Test fun choose_preferredInstalled() {
        assertEquals("other", BrowserChooser.choose("other", listOf("com.android.chrome", "other")))
    }

    @Test fun choose_fallbackChromeThenBrave() {
        assertEquals("com.android.chrome", BrowserChooser.choose("missing", listOf("other", "com.brave.browser", "com.android.chrome")))
        assertEquals("com.brave.browser", BrowserChooser.choose("missing", listOf("other", "com.brave.browser")))
        assertEquals("other", BrowserChooser.choose("missing", listOf("other", "last")))
    }

    @Test fun choose_none_null() {
        assertNull(BrowserChooser.choose("missing", emptyList()))
    }

    @Test fun installedBrowsers_excludesOwn_sortedByLabel() {
        val pm = ApplicationProvider.getApplicationContext<Application>().packageManager
        val shadow = shadowOf(pm)
        listOf("z.pkg" to "Alpha", "a.pkg" to "Zulu", "com.gt8d.launcher" to "Own",
            "com.gt8d.launcher.panel" to "Panel", "z.pkg" to "Alpha").forEachIndexed { index, (pkg, label) ->
            val component = ComponentName(pkg, "Activity$index")
            shadow.addOrUpdateActivity(ActivityInfo().apply {
                name = component.className
                packageName = pkg
                nonLocalizedLabel = label
            })
            shadow.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("https")
                addDataScheme("http")
            })
        }
        assertEquals(listOf("z.pkg", "a.pkg"), BrowserChooser.installedBrowsers(pm))
    }
}
