package com.gt8d.launcher.apps

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppCatalogTest {
    @org.robolectric.annotation.Implements(className = "android.app.ApplicationPackageManager")
    class CountingPackageManager : org.robolectric.shadows.ShadowApplicationPackageManager() {
        var queries = 0
        @org.robolectric.annotation.Implementation
        public override fun getApplicationInfo(pkg: String, flags: Int): android.content.pm.ApplicationInfo {
            queries++
            return android.content.pm.ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = "Label" }
        }
    }

    @Config(shadows = [CountingPackageManager::class])
    @Test fun labelIsCached() {
        val countingPm = org.robolectric.shadow.api.Shadow.extract<CountingPackageManager>(app.packageManager)
        val before = countingPm.queries
        val cached = AppCatalog(app)
        assertEquals("Label", cached.label("example"))
        assertEquals("Label", cached.label("example"))
        assertEquals(before + 1, countingPm.queries)
        cached.invalidatePackage("example")
        assertEquals("Label", cached.label("example"))
        assertEquals(before + 2, countingPm.queries)
        cached.invalidateAllIcons()
        assertEquals("Label", cached.label("example"))
        assertEquals(before + 3, countingPm.queries)
    }

    @Test fun buildAppList_excludesPanelHost() {
        val entries = listOf(
            com.gt8d.launcher.ui.apps.AppListEntry("com.gt8d.launcher", "Home", "本体"),
            com.gt8d.launcher.ui.apps.AppListEntry("com.gt8d.launcher.panel", "Host", "中継"),
            com.gt8d.launcher.ui.apps.AppListEntry("other", "Main", "他"),
        )
        val hidden = HiddenApps.packages.flatMap {
            listOf(com.gt8d.launcher.ui.apps.AppListEntry(it, "Main", "Hidden"),
                com.gt8d.launcher.ui.apps.AppListEntry(it, "Other", "Other"))
        }
        assertEquals(listOf(entries.last()),
            com.gt8d.launcher.ui.apps.buildAppList(entries + hidden, "com.gt8d.launcher"))
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val pm = Shadows.shadowOf(app.packageManager)
    private val catalog = AppCatalog(app)
    private fun color(image: ImageBitmap?) = requireNotNull(image).asAndroidBitmap().getPixel(48, 48)
    private fun prepare() {
        pm.setApplicationIcon("a", ColorDrawable(Color.RED))
        pm.setApplicationIcon("b", ColorDrawable(Color.BLUE))
        assertEquals(Color.RED, color(catalog.icon("a")))
        assertEquals(Color.BLUE, color(catalog.icon("b")))
        pm.setApplicationIcon("a", ColorDrawable(Color.GREEN))
        pm.setApplicationIcon("b", ColorDrawable(Color.YELLOW))
    }
    @Test fun catalog_invalidatePackage_reloadsOnlyTargetIcon() {
        prepare()
        AppCatalog::class.java.getMethod("invalidatePackage", String::class.java).invoke(catalog, "a")
        assertEquals(Color.GREEN, color(catalog.icon("a")))
        assertEquals(Color.BLUE, color(catalog.icon("b")))
    }
    @Test fun catalog_invalidateAllIcons_reloadsCachedIcons() {
        prepare()
        AppCatalog::class.java.getMethod("invalidateAllIcons").invoke(catalog)
        assertEquals(Color.GREEN, color(catalog.icon("a")))
        assertEquals(Color.YELLOW, color(catalog.icon("b")))
    }

    @Test fun sharedReadInfo_usesFresh96pxImageWithoutChangingCompatibilityCache() {
        prepare()
        val info = catalog.readInfo("a", setOf("a"))
        assertEquals(96, info.icon!!.width)
        assertEquals(96, info.icon.height)
        assertEquals(Color.GREEN, color(info.icon))
        org.junit.Assert.assertTrue(info.installed)
        org.junit.Assert.assertFalse(catalog.readInfo("b", emptySet()).installed)
        assertEquals(Color.RED, color(catalog.icon("a")))
    }
}
