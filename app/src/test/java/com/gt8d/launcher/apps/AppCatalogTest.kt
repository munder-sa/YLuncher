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
}
