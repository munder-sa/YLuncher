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
import androidx.compose.ui.unit.dp
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.PanelView
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
    private val hidden get() = com.gt8d.launcher.apps.HiddenApps.packages
    private fun installHidden() {
        val pm = Shadows.shadowOf(app.packageManager)
        hidden.forEach { p ->
            listOf("Main", "Other").forEach { cls ->
                val component = ComponentName(p, "$p.$cls")
                pm.addOrUpdateActivity(ActivityInfo().apply {
                    packageName = p; name = component.className; nonLocalizedLabel = "Hidden $cls"
                })
                pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                })
            }
        }
    }

    @Test fun dockExcludesHiddenFromRankingSavedManualAndAutoFill() {
        install(); installHidden()
        AppGraph.setFrequentAppsForTest(hidden.map { com.gt8d.launcher.apps.FrequentApp(it, 99) }, true)
        try {
            var config by androidx.compose.runtime.mutableStateOf(Defaults.build(emptySet()).copy(
                dockApps = hidden.toList() + pkg, appListPkgs = emptyList()))
            rule.setContent { LauncherTheme { PanelScreen(false, homeConfig = config, onLaunchApp = {}) } }
            fun verify() {
                hidden.forEach { rule.onNodeWithTag("dock_app_$it").assertDoesNotExist() }
                rule.onNodeWithTag("dock_app_$pkg").assertIsDisplayed()
            }
            verify()
            rule.runOnIdle { AppGraph.setFrequentAppsForTest(null, false) }
            verify()
            rule.runOnIdle { config = config.copy(dockMode = com.gt8d.launcher.config.DockMode.Manual) }
            verify()
        } finally { AppGraph.clearFrequentAppsForTest() }
    }
    @Test fun dockAutoUsesRankingAndManualUsesConfiguredOrder() {
        install()
        val other = "example.other"
        val pm = Shadows.shadowOf(app.packageManager)
        val component = ComponentName(other, "$other.Main")
        pm.addOrUpdateActivity(ActivityInfo().apply { packageName = other; name = component.className })
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
        AppGraph.setFrequentAppsForTest(listOf(com.gt8d.launcher.apps.FrequentApp(other, 5),
            com.gt8d.launcher.apps.FrequentApp(pkg, 3)), true)
        try {
        var config by androidx.compose.runtime.mutableStateOf(Defaults.build(emptySet()).copy(dockApps = listOf(pkg, other)))
        rule.setContent { LauncherTheme { PanelScreen(false, homeConfig = config, onLaunchApp = {}) } }
        fun top(p: String) = rule.onNodeWithTag("dock_app_$p").getBoundsInRoot().top
        assertTrue(top(other) < top(pkg))
        rule.runOnIdle { config = config.copy(dockMode = com.gt8d.launcher.config.DockMode.Manual) }
        assertTrue(top(pkg) < top(other))
        } finally {
            AppGraph.clearFrequentAppsForTest()
        }
    }

    @Test fun dockSettingsResolvesWithoutLauncherCatalogEntryAndLaunchesComponent() {
        val component = ComponentName("system.settings", "system.settings.Main")
        val pm = Shadows.shadowOf(app.packageManager)
        pm.addOrUpdateActivity(ActivityInfo().apply {
            packageName = component.packageName; name = component.className
        })
        pm.addIntentFilterForActivity(component, IntentFilter(Settings.ACTION_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        })
        assertFalse(component.packageName in AppGraph.catalog.installed())
        var launched: ComponentName? = null
        rule.setContent { LauncherTheme { PanelScreen(true, onLaunchComponent = { launched = it }, onLaunchApp = {}) } }
        rule.onNodeWithTag("dock_settings").performClick()
        assertEquals(component, launched)
    }

    @Test fun appList_nullSeedsOnceToggleSurvivesRecomposeAndReopen() {
        install()
        host(appListPkgs = null)
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.appListPkgs == listOf(pkg) }
        val configFile = AppGraph.config.javaClass.getDeclaredField("file").let {
            it.isAccessible = true
            it.get(AppGraph.config) as java.io.File
        }
        val savedAt = configFile.lastModified()
        val savedText = configFile.readText()
        broadcast(WifiManager.WIFI_STATE_CHANGED_ACTION)
        openList()
        rule.onNodeWithTag("applist_$pkg").assertIsDisplayed()
        rule.onNodeWithTag("applist_close").performClick()
        openList()
        rule.runOnIdle {
            assertEquals(savedText, configFile.readText())
            assertEquals(savedAt, configFile.lastModified())
        }
        rule.onNodeWithTag("applist_edit").performClick()
        rule.onNodeWithTag("applist_$pkg").performClick()
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.appListPkgs == emptyList<String>() }
        rule.onNodeWithTag("applist_done").performClick()
        rule.onNodeWithTag("applist_$pkg").assertDoesNotExist()
        rule.onNodeWithTag("applist_close").performClick()
        openList()
        broadcast(WifiManager.WIFI_STATE_CHANGED_ACTION)
        rule.onNodeWithTag("applist_$pkg").assertDoesNotExist()
        assertEquals(emptyList<String>(), AppGraph.latestConfig.value!!.appListPkgs)
    }

    @Test fun appList_emptyRegistrationIsNotSeededAndToggleIsSaved() {
        install()
        runBlocking { AppGraph.config.update { Defaults.build(emptySet()).copy(panelApps = listOf(pkg), appListPkgs = emptyList()) } }
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.appListPkgs == emptyList<String>() }
        rule.setContent { LauncherTheme { PanelScreen(false, onLaunchApp = {}) } }
        openList()
        rule.onNodeWithTag("applist_$pkg").assertDoesNotExist()
        assertEquals(emptyList<String>(), AppGraph.latestConfig.value!!.appListPkgs)
        rule.onNodeWithTag("applist_edit").performClick()
        rule.onNodeWithTag("applist_$pkg").performClick()
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.appListPkgs == listOf(pkg) }
        rule.onNodeWithTag("applist_done").performClick()
        rule.onNodeWithTag("applist_$pkg").assertIsDisplayed()
    }
    @Test fun wideHome_appsAreBelowSetsAtBottomOfLeftColumn() {
        install()
        val config = Defaults.build(emptySet()).copy(appListPkgs = listOf(pkg))
        rule.setContent {
            LauncherTheme { PanelScreen(false, homeWide = true, homeConfig = config, onLaunchApp = {}) }
        }
        val apps = rule.onNodeWithTag("home_apps").assertIsDisplayed().getBoundsInRoot()
        val sets = rule.onNodeWithTag("home_sets").getBoundsInRoot()
        val player = rule.onNodeWithTag("home_player").getBoundsInRoot()
        assertTrue(apps.top >= sets.bottom)
        assertEquals(sets.left, apps.left)
        assertEquals(sets.right, apps.right)
        assertEquals(player.bottom, apps.bottom)
    }
    @Test fun wideHome_registeredOrderMatchesListAndLaunchesComponents() {
        install(); install("$pkg.B", "Label B")
        installHidden()
        val config = Defaults.build(emptySet()).copy(panelApps = emptyList(),
            appListPkgs = listOf(pkg) + hidden)
        rule.setContent { LauncherTheme {
            PanelScreen(false, homeWide = true, homeConfig = config, onLaunchApp = { launches.add(it) })
        } }
        hidden.forEach { rule.onNodeWithTag("home_app_$it").assertDoesNotExist() }
        val home = rule.onAllNodesWithTag("home_app_$pkg").fetchSemanticsNodes()
        assertEquals(2, home.size)
        assertTrue(home[0].boundsInRoot.left < home[1].boundsInRoot.left)
        rule.onAllNodesWithTag("home_app_$pkg")[1].assertContentDescriptionEquals("Label B").performClick()
        val intent = requireNotNull(Shadows.shadowOf(app).nextStartedActivity)
        assertEquals(ComponentName(pkg, "$pkg.B"), intent.component)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        assertTrue(launches.isEmpty())
        openList()
        hidden.forEach { rule.onNodeWithTag("applist_$it").assertDoesNotExist() }
        val entries = rule.onAllNodesWithTag("applist_$pkg").fetchSemanticsNodes()
        assertEquals(2, entries.size)
        rule.onAllNodesWithTag("applist_$pkg")[0].assert(hasText("Label A"))
        rule.onAllNodesWithTag("applist_$pkg")[1].assert(hasText("Label B"))
    }

    @Test fun wideHome_seedSavedOnceAndUnregisterRemovesRow() {
        install()
        val expected = Defaults.build(emptySet()).copy(panelApps = listOf(pkg))
        runBlocking { AppGraph.config.update { expected } }
        rule.waitUntil(5000L) { AppGraph.latestConfig.value == expected }
        rule.setContent { LauncherTheme { PanelScreen(false, homeWide = true, onLaunchApp = {}) } }
        rule.onNodeWithTag("home_app_$pkg").assertIsDisplayed()
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.appListPkgs == listOf(pkg) }
        val file = AppGraph.config.javaClass.getDeclaredField("file").let {
            it.isAccessible = true; it.get(AppGraph.config) as java.io.File
        }
        val saved = file.readText()
        val savedAt = file.lastModified()
        broadcast(WifiManager.WIFI_STATE_CHANGED_ACTION)
        openList()
        rule.onNodeWithTag("applist_close").performClick()
        rule.onNodeWithTag("home_app_$pkg").assertIsDisplayed()
        assertEquals(saved, file.readText())
        assertEquals(savedAt, file.lastModified())
        openList()
        rule.onNodeWithTag("applist_edit").performClick()
        rule.onNodeWithTag("applist_$pkg").performClick()
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.appListPkgs == emptyList<String>() }
        rule.onNodeWithTag("applist_done").performClick()
        rule.onNodeWithTag("applist_close").performClick()
        rule.onNodeWithTag("home_apps").assertDoesNotExist()
    }

    @Test fun wideHome_scrollSurvivesRecomposeAndAppList() {
        repeat(12) { install("$pkg.Entry$it", "Scroll %02d".format(it)) }
        val config = Defaults.build(emptySet()).copy(appListPkgs = listOf(pkg))
        rule.setContent { LauncherTheme {
            PanelScreen(false, homeWide = true, homeConfig = config, onLaunchApp = {})
        } }
        rule.onNodeWithTag("home_apps").performScrollToIndex(11)
        fun position() = rule.onNodeWithTag("home_apps").fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val before = position()
        assertTrue(before > 0f)
        broadcast(WifiManager.WIFI_STATE_CHANGED_ACTION)
        assertEquals(before, position(), 0f)
        openList()
        rule.onNodeWithTag("applist_close").performClick()
        assertEquals(before, position(), 0f)
        val area = rule.onNodeWithTag("home_apps").getBoundsInRoot()
        val last = rule.onAllNodesWithTag("home_app_$pkg").fetchSemanticsNodes().last().boundsInRoot
        val row = rule.onNodeWithTag("home_apps").fetchSemanticsNode().boundsInRoot
        assertTrue(last.left >= row.left && last.right <= row.right &&
            last.top >= row.top && last.bottom <= row.bottom)
        assertEquals(96.dp, area.bottom - area.top)
    }

    @Test fun wideHome_emptyRegistrationHasNoAppRow() {
        rule.setContent {
            LauncherTheme { PanelScreen(false, homeWide = true,
                homeConfig = Defaults.build(emptySet()).copy(panelApps = listOf(pkg), appListPkgs = emptyList()), onLaunchApp = {}) }
        }
        rule.onNodeWithTag("home_apps").assertDoesNotExist()
    }
    // ホームの操作の案内はユーザーの判断で出さない(2026-10-31、シンプルに)。
    @Test fun panelScreen_wideHome_showsNoHint() {
        rule.setContent {
            LauncherTheme { PanelScreen(false, homeWide = true, onLaunchApp = {}) }
        }
        rule.onNodeWithText("端からスワイプでセットを切り替え・長押しでホーム").assertDoesNotExist()
        rule.onNodeWithTag("home_hint").assertDoesNotExist()
    }

    @Test fun panelScreen_appListSettings_closesListAndOpensSettingsOnce() {
        var opened = 0
        rule.setContent {
            LauncherTheme { PanelScreen(false, onOpenSettings = { opened++ }, onLaunchApp = {}) }
        }
        openList()
        rule.onNodeWithTag("applist_settings").assertIsDisplayed().performClick()
        assertEquals(1, opened)
        rule.onNodeWithTag("applist_settings").assertDoesNotExist()
        rule.onNodeWithTag("applist_close").assertDoesNotExist()
        rule.onNodeWithTag("dock_applist").assertExists()
    }

    @Test fun panelScreen_permissionBanner_opensPermissionSettings() = checkPermissionSettings(false)
    @Test fun panelScreen_wideHome_permissionBanner_opensPermissionSettings() = checkPermissionSettings(true)

    private fun checkPermissionSettings(wide: Boolean) {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        ShadowSettings.setCanDrawOverlays(true)
        Shadows.shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        var opened = 0
        rule.setContent {
            LauncherTheme {
                PanelScreen(false, homeWide = wide, onOpenPermissionSettings = { opened++ }, onLaunchApp = {})
            }
        }
        rule.onNodeWithText("再生情報の表示に許可が必要です").assertIsDisplayed()
        rule.onNodeWithTag("permission_banner").assertIsDisplayed().performClick()
        assertEquals(1, opened)
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }

    @Test fun home_ignoresPlacement() {
        AppGraph.reportPanelPlacement(com.gt8d.launcher.split.PanelPlacement.Left)
        try {
            rule.setContent {
                LauncherTheme { PanelScreen(inSplit = false, onLaunchApp = {}) }
            }
            val dock = rule.onNodeWithTag("dock").getBoundsInRoot()
            val content = rule.onNodeWithTag("panel_view_segment").getBoundsInRoot()
            assertTrue(dock.left >= content.right)
        } finally { AppGraph.reportPanelPlacement(null) }
    }

    @Test fun panelScreen_viewSegment_savesAndSurvivesActivityRecreation() {
        runBlocking { AppGraph.config.update { Defaults.build(emptySet()) } }
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.panelView == PanelView.Apps }
        val controller = org.robolectric.Robolectric.buildActivity(ComponentActivity::class.java).setup()
        fun content() {
            controller.get().setContent {
                LauncherTheme { PanelScreen(inSplit = true, onLaunchApp = {}) }
            }
            controller.visible()
        }
        try {
            content()
            rule.onNodeWithTag("media_card").assertExists()
            rule.onNodeWithTag("panel_view_sets").performClick()
            rule.onNodeWithTag("media_card").assertDoesNotExist()
            for (set in Defaults.build(emptySet()).screenSets) {
                rule.onNodeWithTag("panel_set_${set.id}").assertExists()
            }
            rule.waitUntil(5000L) { AppGraph.latestConfig.value?.panelView == PanelView.Sets }
            controller.recreate()
            content()
            rule.onNodeWithTag("panel_view_sets").assertIsSelected()
            rule.onNodeWithTag("panel_sets").assertExists()
            for (set in Defaults.build(emptySet()).screenSets) {
                rule.onNodeWithTag("panel_set_${set.id}").assertExists()
            }
            rule.onNodeWithTag("media_card").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun panelScreen_wideHome_hasNoViewSegment() {
        rule.setContent {
            LauncherTheme { PanelScreen(inSplit = false, homeWide = true, onLaunchApp = {}) }
        }
        rule.onNodeWithTag("home_player").assertExists()
        rule.onNodeWithTag("panel_view_segment").assertDoesNotExist()
    }

    @Test fun panelScreen_sets_opensNumberAndGuardsWhileOpening() {
        val sets = (1..2).map {
            com.gt8d.launcher.config.ScreenSet("guard$it",
                com.gt8d.launcher.config.Slot.App("missing$it"), com.gt8d.launcher.config.Slot.Panel)
        }
        runBlocking { AppGraph.config.update { Defaults.build(emptySet()).copy(screenSets = sets, panelView = PanelView.Sets) } }
        rule.waitUntil(5000L) { AppGraph.latestConfig.value?.screenSets == sets }
        var added = 0
        rule.setContent {
            LauncherTheme { PanelScreen(inSplit = true, onNewSet = { added++ }, onLaunchApp = {}) }
        }
        rule.runOnIdle {
            AppGraph.curtain.ready()
            AppGraph.runner.abortBootToHome()
            AppGraph.runner.followIndex(0)
            com.gt8d.launcher.diag.DiagLog.clearForTest()
        }
        rule.onNodeWithTag("panel_set_guard2").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(2, AppGraph.runner.currentIndexFlow.value)
            assertNotNull(AppGraph.runner.opening.value)
        }
        val opening = AppGraph.runner.opening.value
        rule.onNodeWithTag("panel_set_guard2").assertIsSelected()
        rule.onNodeWithTag("panel_set_guard1").performScrollTo().performClick()
        rule.onNodeWithTag("panel_set_add").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(2, AppGraph.runner.currentIndexFlow.value)
            assertEquals(opening, AppGraph.runner.opening.value)
            assertEquals(0, added)
            assertEquals(listOf("2"), com.gt8d.launcher.diag.DiagLog.dump()
                .filter { it.contains("[SetRunner] open index=") }.map { it.substringAfter("open index=") })
            AppGraph.curtain.ready()
            assertNull(AppGraph.runner.opening.value)
        }
        rule.onNodeWithTag("panel_set_add").performScrollTo().performClick()
        assertEquals(1, added)
    }

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
    private fun host(split: Boolean = false, onComponent: ((ComponentName) -> Unit)? = null,
                     appListPkgs: List<String>? = listOf(pkg)) {
        val wasInstalled = pkg in AppGraph.catalog.installed()
        if (!wasInstalled) install()
        val latest = AppGraph.latestConfig
        val expected = Defaults.build(emptySet()).copy(panelApps = listOf(pkg), appListPkgs = appListPkgs)
        runBlocking { AppGraph.config.update { expected } }
        rule.waitUntil(5000L) { latest.value == expected }
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
        rule.onNodeWithTag("applist_all").performClick()
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
    @Test fun panelScreen_hiddenApps_absentFromRegisteredListAndDoNotLaunch() {
        val components = mutableListOf<ComponentName>()
        installHidden()
        host(true, { components.add(it) }, hidden.toList()); openList()
        hidden.forEach { rule.onNodeWithTag("applist_$it").assertDoesNotExist() }
        assertTrue(components.isEmpty())
        assertTrue(launches.isEmpty())
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        rule.onNodeWithTag("applist_close").assertIsDisplayed()
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
    @Test fun panelScreen_hiddenApps_absentFromAllAndEditing() {
        installHidden()
        host(true); openList()
        rule.onNodeWithTag("applist_all").performClick()
        hidden.forEach { rule.onNodeWithTag("applist_$it").assertDoesNotExist() }
        rule.onNodeWithTag("applist_edit").performClick()
        hidden.forEach { rule.onNodeWithTag("applist_$it").assertDoesNotExist() }
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
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
        rule.onNodeWithTag("missing_$pkg").performScrollTo().assert(hasContentDescription("$pkg(アプリが見つかりません)"))
        rule.runOnIdle { install() }
        broadcast(Intent.ACTION_PACKAGE_ADDED)
        rule.onNodeWithTag("icon_$pkg").performScrollTo().assert(hasContentDescription("Label A")).performClick()
        assertEquals(listOf(pkg), launches)
    }
    @Test fun panelScreen_packageRemoved_disablesConfiguredAppWithoutConfigChange() {
        install(); host()
        rule.onNodeWithTag("icon_$pkg").performScrollTo().performClick()
        assertEquals(listOf(pkg), launches)
        rule.runOnIdle { Shadows.shadowOf(app.packageManager).removePackage(pkg); launches.clear() }
        broadcast(Intent.ACTION_PACKAGE_REMOVED)
        rule.onNodeWithTag("missing_$pkg").performScrollTo().assert(hasContentDescription("$pkg(アプリが見つかりません)")).performClick()
        assertEquals(emptyList<String>(), launches)
    }
    @Test fun panelScreen_openAppList_updatesAfterPackageChange() {
        host(); openList()
        rule.onNodeWithTag("applist_all").performClick()
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
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(150)
        rule.onNodeWithTag("applist_all").performClick()
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
