package com.gt8d.launcher.ui.panel

import android.app.Application
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import androidx.compose.ui.semantics.SemanticsProperties
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

internal class PanelTestLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun stop() { registry.currentState = Lifecycle.State.CREATED }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelScreenTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val pkg = "example.selected"
    private val launches = mutableListOf<String>()
    private lateinit var owner: PanelTestLifecycle
    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            RuntimeEnvironment.setQualifiers("w1280dp-h1000dp")
            val pm = Shadows.shadowOf(app.packageManager)
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            pm.addOrUpdateActivity(ActivityInfo().apply { name = cn.className; packageName = cn.packageName })
            pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
            AppGraph.init(app)
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()
    private fun install(cls: String = "$pkg.A", label: String = "Label A") {
        val pm = Shadows.shadowOf(app.packageManager)
        val cn = ComponentName(pkg, cls)
        pm.addOrUpdateActivity(ActivityInfo().apply {
            name = cls; packageName = pkg; nonLocalizedLabel = label
        })
        pm.getInternalMutablePackageInfo(pkg).applicationInfo?.nonLocalizedLabel = "Label A"
        pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
    private fun host(split: Boolean = false, onComponent: ((ComponentName) -> Unit)? = null) {
        val wasInstalled = pkg in AppGraph.catalog.installed()
        if (!wasInstalled) install()
        val latest = AppGraph.latestConfig
        runBlocking { AppGraph.config.update { Defaults.build(emptySet()).copy(panelApps = listOf(pkg)) } }
        rule.waitUntil(5000L) { latest.value?.panelApps == listOf(pkg) }
        if (!wasInstalled) Shadows.shadowOf(app.packageManager).removePackage(pkg)
        rule.setContent {
            owner = androidx.compose.runtime.remember { PanelTestLifecycle().also { it.resume() } }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LauncherTheme { PanelScreen(split, onComponent) { launches.add(it) } }
            }
        }
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.panelApps == listOf(pkg) }
        rule.waitForIdle()
    }
    private fun openList() { rule.onNodeWithTag("dock_applist").performScrollTo().performClick() }
    private fun broadcast(action: String) {
        rule.runOnIdle { app.sendBroadcast(Intent(action, Uri.parse("package:$pkg"))) }
        rule.waitForIdle()
    }
    private fun choose(label: String) {
        rule.onNode(hasText(label) and hasClickAction()).performClick()
    }
    @Test fun panelScreen_appListSelection_notifiesComponentCallbackOnce() {
        val components = mutableListOf<ComponentName>()
        install(); install("$pkg.B", "Label B")
        host(onComponent = { components.add(it) }); openList(); choose("Label B")
        assertEquals(listOf(ComponentName(pkg, "$pkg.B")), components)
        assertTrue(launches.isEmpty())
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        rule.onNodeWithTag("applist_close").assertDoesNotExist()
    }
    @Test fun panelScreen_stockLauncher_doesNotNotifyComponentCallback() {
        val components = mutableListOf<ComponentName>()
        host(true, { components.add(it) }); openList(); choose("純正ランチャー")
        assertTrue(components.isEmpty())
        assertTrue(launches.isEmpty())
        assertEquals(ComponentName("com.android.launcher3", "com.android.launcher3.uioverrides.QuickstepLauncher"),
            Shadows.shadowOf(app).nextStartedActivity?.component)
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        rule.onNodeWithTag("applist_close").assertDoesNotExist()
    }
    @Test fun panelScreen_appListSelection_launchesSelectedComponent() {
        install(); install("$pkg.B", "Label B")
        assertEquals("$pkg.A", app.packageManager.getLaunchIntentForPackage(pkg)?.component?.className)
        host(); openList(); choose("Label B")
        assertEquals(ComponentName(pkg, "$pkg.B"), Shadows.shadowOf(app).nextStartedActivity?.component)
        assertEquals(emptyList<String>(), launches)
        rule.onNodeWithTag("applist_close").assertDoesNotExist()
    }
    @Test fun panelScreen_appListSelection_inSplit_usesAdjacentFlags() {
        install(); host(true); openList(); choose("Label A")
        val intent = requireNotNull(Shadows.shadowOf(app).nextStartedActivity)
        for (flag in listOf(Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) {
            assertEquals(flag, intent.flags and flag)
        }
    }
    @Test fun panelScreen_stockLauncher_doesNotUseAdjacentFlags() {
        host(true); openList(); choose("純正ランチャー")
        val intent = requireNotNull(Shadows.shadowOf(app).nextStartedActivity)
        assertEquals(ComponentName("com.android.launcher3", "com.android.launcher3.uioverrides.QuickstepLauncher"), intent.component)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
    @Test fun panelScreen_resume_updatesPermissionBannerWithoutMediaChange() {
        ShadowSettings.setCanDrawOverlays(false)
        host()
        rule.onNodeWithTag("permission_banner").assertIsDisplayed()
        rule.runOnIdle {
            owner.stop()
            Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "${app.packageName}/.media.MediaWatcher")
            ShadowSettings.setCanDrawOverlays(true)
            Shadows.shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED,
            )
            owner.resume()
        }
        rule.onNodeWithTag("permission_banner").assertDoesNotExist()
    }
    @Test fun panelScreen_packageAdded_enablesConfiguredAppWithoutConfigChange() {
        host()
        rule.onNodeWithTag("missing_$pkg").performScrollTo().assert(hasText("アプリが見つかりません"))
        rule.runOnIdle { install() }
        broadcast(Intent.ACTION_PACKAGE_ADDED)
        rule.onNodeWithTag("icon_$pkg").performScrollTo().assert(hasText("Label A")).performClick()
        assertEquals(listOf(pkg), launches)
    }
    @Test fun panelScreen_packageRemoved_disablesConfiguredAppWithoutConfigChange() {
        install(); host()
        rule.onNodeWithTag("icon_$pkg").performScrollTo().performClick()
        assertEquals(listOf(pkg), launches)
        rule.runOnIdle { Shadows.shadowOf(app.packageManager).removePackage(pkg); launches.clear() }
        broadcast(Intent.ACTION_PACKAGE_REMOVED)
        rule.onNodeWithTag("missing_$pkg").performScrollTo().assert(hasText("アプリが見つかりません")).performClick()
        assertEquals(emptyList<String>(), launches)
    }
    @Test fun panelScreen_openAppList_updatesAfterPackageChange() {
        host(); openList()
        rule.onNodeWithText("Label A").assertDoesNotExist()
        rule.runOnIdle { install() }
        broadcast(Intent.ACTION_PACKAGE_ADDED)
        rule.onNodeWithText("Label A").assertIsDisplayed()
        rule.runOnIdle { Shadows.shadowOf(app.packageManager).removePackage(pkg) }
        broadcast(Intent.ACTION_PACKAGE_REMOVED)
        rule.onNodeWithText("Label A").assertDoesNotExist()
        rule.onNodeWithTag("applist_close").assertIsDisplayed()
    }
    @Test fun panelScreen_radioChange_keepsAppListScroll() {
        repeat(150) { install("$pkg.Entry$it", "Scroll %03d".format(it)) }
        host(); openList()
        val grid = rule.onNode(hasScrollToIndexAction())
        grid.performScrollToIndex(90)
        fun position() = grid.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = position()
        assertTrue(before > 0f)
        for (action in listOf(WifiManager.WIFI_STATE_CHANGED_ACTION, BluetoothAdapter.ACTION_STATE_CHANGED)) {
            rule.runOnIdle { app.sendBroadcast(Intent(action)) }
            rule.waitForIdle()
            assertEquals(before, position(), 0f)
        }
    }
}
