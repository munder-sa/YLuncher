package com.gt8d.launcher.apps

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.Defaults
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SplitRedirectTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val reader = AndroidSplitRedirects(app.packageManager)
    private val source = "local.aibox.gptlive"
    private val target = "com.openai.chatgpt"

    @Test fun mapsWithoutBaseRedirect_returnsFreeDriveUri() {
        val redirects = MapsFreeDriveRedirects(SplitRedirects { null })
        val expected = SplitRedirect(Defaults.PKG_MAPS, null, Uri.parse("google.navigation:/?free=1&mode=d&entry=fnls"))
        assertEquals(expected, redirects.forPackage(Defaults.PKG_MAPS))
    }

    @Test fun mapsWithBaseRedirect_keepsBaseResult() {
        val redirect = SplitRedirect(target, null, null)
        val redirects = MapsFreeDriveRedirects(SplitRedirects { pkg -> redirect.takeIf { pkg == Defaults.PKG_MAPS } })
        assertSame(redirect, redirects.forPackage(Defaults.PKG_MAPS))
    }

    @Test fun otherPackageWithoutBaseRedirect_returnsNull() {
        val redirects = MapsFreeDriveRedirects(SplitRedirects { null })
        assertNull(redirects.forPackage(source))
    }

    private fun activity(pkg: String, cls: String = "$pkg.Main", metadata: Bundle? = null) {
        val pm = shadowOf(app.packageManager)
        val component = ComponentName(pkg, cls)
        pm.addOrUpdateActivity(ActivityInfo().apply {
            packageName = pkg; name = cls; metaData = metadata
        })
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
    }

    private fun metadata(component: String? = "$target/$target.ChatGptDeeplinkActivity", uri: String? = "https://chat.com/?mode=voice") = Bundle().apply {
        putString(AndroidSplitRedirects.PACKAGE, target)
        component?.let { putString(AndroidSplitRedirects.COMPONENT, it) }
        uri?.let { putString(AndroidSplitRedirects.URI, it) }
    }

    @Test fun allMetadata_isRead() {
        activity(target); activity(source, metadata = metadata())
        val redirect = reader.forPackage(source)!!
        assertEquals(target, redirect.targetPkg)
        assertEquals(ComponentName(target, "$target.ChatGptDeeplinkActivity"), redirect.component)
        assertEquals("https://chat.com/?mode=voice", redirect.uri.toString())
    }
    @Test fun packageOnly_hasNoComponentOrUri() {
        activity(target); activity(source, metadata = metadata(null, null))
        assertEquals(SplitRedirect(target, null, null), reader.forPackage(source))
    }
    @Test fun shortClass_isExpanded() {
        activity(target); activity(source, metadata = metadata("$target/.Voice"))
        assertEquals(ComponentName(target, "$target.Voice"), reader.forPackage(source)!!.component)
    }
    @Test fun malformedOrDifferentPackage_componentIsIgnoredAndUriRetained() {
        activity(target)
        for (component in listOf("other/.Voice", "$target/", "/Voice", "$target/a/b", "broken")) {
            activity(source, metadata = metadata(component))
            val redirect = reader.forPackage(source)!!
            assertNull(redirect.component)
            assertEquals("https://chat.com/?mode=voice", redirect.uri.toString())
        }
    }
    @Test fun selfEmptyOrMissingPackage_returnsNull() {
        activity(target)
        for (pkg in listOf(source, "", null)) {
            activity(source, metadata = Bundle().apply { pkg?.let { putString(AndroidSplitRedirects.PACKAGE, it) } })
            assertNull(reader.forPackage(source))
        }
    }
    @Test fun targetMissing_returnsNull_andReadsAgainAfterInstall() {
        activity(source, metadata = metadata())
        assertNull(reader.forPackage(source))
        activity(target)
        assertNotNull(reader.forPackage(source))
    }
    @Test fun multipleLaunchers_firstWithPackageMetadataIsUsed() {
        activity(target); activity(source, "$source.A")
        activity(source, "$source.B", metadata())
        assertEquals(target, reader.forPackage(source)!!.targetPkg)
    }
}
