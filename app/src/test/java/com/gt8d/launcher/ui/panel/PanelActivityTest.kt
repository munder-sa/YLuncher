package com.gt8d.launcher.ui.panel

import android.app.Application
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Process
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.foreground.UsageStatsForegroundEvents
import com.gt8d.launcher.sets.SetRunnerDeps
import com.gt8d.launcher.curtain.CurtainController
import com.gt8d.launcher.curtain.CurtainView
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.time.FakeScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var controller: ActivityController<PanelActivity>? = null
    private lateinit var pkg: String
    private lateinit var savedConfig: LauncherConfig
    private var usageMode = 0
    private var savedSplit = false
    private var savedSplitPausedAt: Long? = null
    private val appCalls = mutableListOf<Pair<String, Boolean>>()
    private val componentCalls = mutableListOf<Pair<ComponentName, Boolean>>()
    private var safetyContents: String? = null
    private var savedHandled = false
    private lateinit var settingsIndexFile: java.io.File
    private var savedSettingsIndex: String? = null
    private var snapshotObserver: androidx.compose.runtime.snapshots.ObserverHandle? = null

    @Before fun observeSnapshotWrites() {
        // Robolectric の同一 JVM 内で再開される Compose の状態通知をメインスレッドへ送る。
        val handler = android.os.Handler(Looper.getMainLooper())
        snapshotObserver = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver {
            handler.post { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        }
    }
    @After fun stopObservingSnapshotWrites() { snapshotObserver?.dispose() }

    @Before fun prepare() {
        RuntimeEnvironment.setQualifiers("w1280dp-h1000dp")
        AppGraph.init(app)
        val safetyFile = java.io.File(app.filesDir, "safety.json")
        safetyContents = safetyFile.takeIf { it.exists() }?.readText()
        com.gt8d.launcher.safety.SafetyGuard.clear(app)
        savedHandled = AppGraph.bootHandled
        settingsIndexFile = AppGraph.config.javaClass.getDeclaredField("lastSetFile")
            .apply { isAccessible = true }.get(AppGraph.config) as java.io.File
        savedSettingsIndex = settingsIndexFile.takeIf { it.exists() }?.readText()
        savedSplit = AppGraph.panelInSplit
        savedSplitPausedAt = AppGraph.panelSplitPausedAtMs
        savedConfig = runBlocking { AppGraph.config.config.first() }
        val ops = app.getSystemService(AppOpsManager::class.java)
        usageMode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ERRORED)
        assertFalse(UsageStatsForegroundEvents(app).available())
    }
    @After fun cleanup() {
        controller?.let {
            fun dispose(view: View) {
                if (view is ComposeView) view.disposeComposition()
                if (view is ViewGroup) for (i in 0 until view.childCount) dispose(view.getChildAt(i))
            }
            dispose(it.get().window.decorView)
            it.pause().stop().destroy()
        }
        runBlocking { AppGraph.config.update { savedConfig } }
        if (::pkg.isInitialized) {
            shadowOf(app.packageManager).removePackage(pkg)
            @Suppress("UNCHECKED_CAST")
            val warm = AppGraph.warm.javaClass.getDeclaredField("warmPkgs")
                .apply { isAccessible = true }.get(AppGraph.warm) as MutableSet<String>
            warm.remove(pkg)
            AppGraph.catalog.invalidatePackage(pkg)
        }
        AppGraph.panelInSplit = savedSplit
        val safetyFile = java.io.File(app.filesDir, "safety.json")
        safetyContents?.let { safetyFile.writeText(it) } ?: safetyFile.delete()
        AppGraph.bootHandled = savedHandled
        savedSettingsIndex?.let { settingsIndexFile.writeText(it) } ?: settingsIndexFile.delete()
        AppGraph.pendingUrl.take()
        AppGraph.panelSplitPausedAtMs = savedSplitPausedAt
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, usageMode,
        )
    }
    private fun host(id: String, success: Boolean) {
        pkg = "example.panel.$id"
        assertFalse(app.packageManager.getInstalledPackages(0).any { it.packageName == pkg })
        val pm = shadowOf(app.packageManager)
        for (entry in listOf("A", "B")) {
            val component = ComponentName(pkg, "$pkg.$entry")
            pm.addOrUpdateActivity(ActivityInfo().apply {
                name = component.className; packageName = pkg; nonLocalizedLabel = "Entry $entry"
            })
            pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        assertEquals(ComponentName(pkg, "$pkg.A"), app.packageManager.getLaunchIntentForPackage(pkg)?.component)
        assertFalse(AppGraph.warm.isWarm(pkg))
        val latest = AppGraph.latestConfig
        runBlocking { AppGraph.config.update {
            Defaults.build(emptySet()).copy(panelApps = listOf(pkg), dockApps = emptyList(), idleApp = "example.other.idle")
        } }
        rule.waitUntil(5000L) { latest.value?.panelApps == listOf(pkg) }
        controller = Robolectric.buildActivity(PanelActivity::class.java).also { c ->
            c.get().launchApp = { p, adjacent ->
                assertFalse(AppGraph.warm.isWarm(pkg))
                appCalls.add(p to adjacent); success
            }
            c.get().launchComponent = { component, adjacent ->
                assertFalse(AppGraph.warm.isWarm(pkg))
                componentCalls.add(component to adjacent); success
            }
        }.create().start().resume().visible()
        rule.waitForIdle()
    }
    private fun icon() { rule.onNodeWithTag("icon_$pkg").performScrollTo().performClick() }
    private fun listB() {
        rule.onNodeWithTag("dock_applist").performScrollTo().performClick()
        rule.onNode(hasText("Entry B") and hasClickAction()).performClick()
    }
    @Test fun panelActivity_panelIcon_success_recordsWarmWithoutUsageAccess() {
        host("iconsuccess", true); icon()
        assertEquals(listOf(pkg to controller!!.get().isInMultiWindowMode), appCalls)
        assertTrue(AppGraph.warm.isWarm(pkg))
    }
    @Test fun panelActivity_panelIcon_failure_keepsColdWithoutUsageAccess() {
        host("iconfailure", false); icon()
        assertEquals(listOf(pkg to controller!!.get().isInMultiWindowMode), appCalls)
        assertFalse(AppGraph.warm.isWarm(pkg))
    }
    @Test fun panelActivity_appList_success_recordsSelectedPackageWithoutUsageAccess() {
        host("listsuccess", true); listB()
        assertTrue(AppGraph.warm.isWarm(pkg))
        assertEquals(listOf(ComponentName(pkg, "$pkg.B") to controller!!.get().isInMultiWindowMode), componentCalls)
        assertTrue(appCalls.isEmpty())
    }
    @Test fun panelActivity_appList_failure_keepsColdWithoutUsageAccess() {
        host("listfailure", false); listB()
        assertEquals(listOf(ComponentName(pkg, "$pkg.B") to controller!!.get().isInMultiWindowMode), componentCalls)
        assertTrue(appCalls.isEmpty())
        assertFalse(AppGraph.warm.isWarm(pkg))
        rule.onNodeWithTag("applist_close").assertDoesNotExist()
    }

    @Test fun splitButton_inSplit_opensNewSetInPanel() {
        splitPanel(); rule.onNodeWithTag("dock_split").performScrollTo().performClick()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.waitUntil(5000L) { rule.onNodeWithTag("set_left").isDisplayed() }
        rule.onNodeWithTag("set_left").assertIsDisplayed()
        assertTrue(controller!!.get().settingsShown)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
        rule.onNodeWithTag("settings_back").performClick()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onNodeWithTag("dock_split").assertExists()
        assertFalse(controller!!.get().settingsShown)
    }
    @Test fun splitButton_outsideSplit_startsSettingsWithNewSet() {
        controller = Robolectric.buildActivity(PanelActivity::class.java).create().start().resume().visible()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onNodeWithTag("dock_split").performScrollTo().performClick()
        val intent = shadowOf(controller!!.get()).nextStartedActivity
        assertEquals(com.gt8d.launcher.settings.SettingsActivity::class.java.name, intent?.component?.className)
        assertTrue(intent!!.getBooleanExtra("open_new_set", false))
    }
    @Test fun splitButton_whileOpening_doesNothing() = whileOpening {
        splitPanel(); rule.onNodeWithTag("dock_split").performScrollTo().performClick()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onNodeWithTag("set_left").assertDoesNotExist()
        assertFalse(controller!!.get().settingsShown)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }
    private fun splitPanel() {
        controller = Robolectric.buildActivity(PanelActivity::class.java)
        shadowOf(controller!!.get()).setInMultiWindowMode(true)
        assertTrue(controller!!.get().isInMultiWindowMode)
        controller!!.create().start().resume().visible()
        rule.waitForIdle()
    }

    @Test fun pauseInSplit_recordsSplitPausedAt() {
        splitPanel()
        assertNull(AppGraph.panelSplitPausedAtMs)
        controller!!.pause()
        assertEquals(android.os.SystemClock.elapsedRealtime(), AppGraph.panelSplitPausedAtMs)
        assertFalse(AppGraph.panelInSplit)
        controller!!.resume()
        assertNull(AppGraph.panelSplitPausedAtMs)
    }

    @Test fun pauseOutsideSplit_doesNotRecord() {
        controller = Robolectric.buildActivity(PanelActivity::class.java).create().start().resume()
        controller!!.pause()
        assertNull(AppGraph.panelSplitPausedAtMs)
    }

    @Test fun leaveSplit_clearsSplitPausedAt() {
        splitPanel()
        controller!!.pause()
        assertNotNull(AppGraph.panelSplitPausedAtMs)
        shadowOf(controller!!.get()).setInMultiWindowMode(false)
        controller!!.get().onMultiWindowModeChanged(false, app.resources.configuration)
        assertNull(AppGraph.panelSplitPausedAtMs)
        shadowOf(android.os.Looper.getMainLooper()).runToEndOfTasks()
    }

    private fun assertBrowserOnce() {
        val activity = controller!!.get()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("https://example.com", activity.shownBrowserUrl)
        assertNull(AppGraph.pendingUrl.take())
    }

    @Test fun browser_close_returnsToPanel() {
        AppGraph.pendingUrl.set("https://example.com")
        splitPanel()
        rule.onNodeWithTag("browser_close").performClick()
        rule.runOnIdle { assertNull(controller!!.get().shownBrowserUrl) }
        rule.onNodeWithTag("dock_applist").assertIsDisplayed()
        assertFalse(controller!!.get().isFinishing)
    }

    @Test fun browser_systemBack_returnsToPanel_thenBackIgnored() {
        AppGraph.pendingUrl.set("https://example.com")
        splitPanel()
        rule.runOnIdle { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertNull(controller!!.get().shownBrowserUrl) }
        rule.runOnIdle { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        assertFalse(controller!!.get().isFinishing)
    }

    @Test fun pendingUrl_whileBrowserShown_replacesUrl() {
        AppGraph.pendingUrl.set("https://example.com/a")
        splitPanel()
        assertEquals("https://example.com/a", controller!!.get().shownBrowserUrl)
        AppGraph.pendingUrl.set("https://example.com/b")
        controller!!.pause().resume()
        // Robolectric ではテスト間で Compose のグローバルな変更通知が止まることがあるため、Activity の中で書いた状態の通知を明示的に送る
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.waitForIdle()
        assertEquals("https://example.com/b", controller!!.get().shownBrowserUrl)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
        assertNull(AppGraph.pendingUrl.take())
    }

    @Test fun browserUrl_survivesRecreate() {
        AppGraph.pendingUrl.set("https://example.com")
        splitPanel()
        controller!!.recreate()
        rule.waitForIdle()
        assertEquals("https://example.com", controller!!.get().shownBrowserUrl)
    }

    @Test fun pendingUrl_whileOpening_isNotConsumedUntilOpeningEnds() {
        val runner = AppGraph.runner
        val field = runner.javaClass.getDeclaredField("d").apply { isAccessible = true }
        val original = field.get(runner) as SetRunnerDeps
        val scheduler = FakeScheduler()
        val curtain = CurtainController(scheduler, object : CurtainView {
            override fun show(target: CurtainTarget) {}
            override fun update(target: CurtainTarget) {}
            override fun showProgress() {}
            override fun hide(reason: HideReason) {}
        })
        val deps = original.copy(scheduler = scheduler, clock = scheduler, curtain = curtain, startHome = {})
        field.set(runner, deps)
        try {
            // 同じ AppGraph.runner を実際の openForBoot と安全タイマーで操作する。
            runner.openForBoot(1)
            assertNotNull(runner.opening.value)
            AppGraph.pendingUrl.set("https://example.com")
            splitPanel()
            assertNull(shadowOf(controller!!.get()).nextStartedActivity)
            val url = AppGraph.pendingUrl.take()
            assertEquals("https://example.com", url)
            AppGraph.pendingUrl.set(url!!)
            scheduler.advanceBy(10_000L)
            assertNull(runner.opening.value)
            shadowOf(Looper.getMainLooper()).idle()
            assertBrowserOnce()
        } finally {
            runner.abortBootToHome()
            field.set(runner, original)
            AppGraph.pendingUrl.take()
        }
    }

    @Test fun pendingUrl_notOpening_consumedOnResume() {
        assertNull(AppGraph.runner.opening.value)
        AppGraph.pendingUrl.set("https://example.com")
        try {
            splitPanel()
            assertBrowserOnce()
        } finally {
            AppGraph.pendingUrl.take()
        }
    }

    @Test fun back_doesNotFinishPanel() {
        host("back", true)
        rule.runOnIdle { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        assertFalse(controller!!.get().isFinishing)
    }

    @Test fun back_withAppListOpen_closesListOnly() {
        host("backlist", true)
        rule.onNodeWithTag("dock_applist").performScrollTo().performClick()
        rule.onNodeWithTag("applist_close").assertExists()
        rule.runOnIdle { controller!!.get().onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("applist_close").assertDoesNotExist()
        rule.onNodeWithTag("dock_applist").assertExists()
        assertFalse(controller!!.get().isFinishing)
    }

    private fun settingsLongPress() {
        rule.onNodeWithTag("dock_applist").performScrollTo().performTouchInput { longClick() }
    }
    private fun activateSafety() {
        com.gt8d.launcher.safety.SafetyGuard.recordStart(app)
        repeat(3) { com.gt8d.launcher.safety.SafetyGuard.recordCrash(app) }
        assertTrue(com.gt8d.launcher.safety.SafetyGuard.isFallbackActive(app))
    }
    private fun whileOpening(block: (FakeScheduler) -> Unit) {
        val runner = AppGraph.runner
        val field = runner.javaClass.getDeclaredField("d").apply { isAccessible = true }
        val original = field.get(runner) as SetRunnerDeps
        val scheduler = FakeScheduler()
        val curtain = CurtainController(scheduler, object : CurtainView {
            override fun show(target: CurtainTarget) {}
            override fun update(target: CurtainTarget) {}
            override fun showProgress() {}
            override fun hide(reason: HideReason) {}
        })
        field.set(runner, original.copy(scheduler = scheduler, clock = scheduler, curtain = curtain, startHome = {}))
        try {
            runner.openForBoot(1); assertNotNull(runner.opening.value); block(scheduler)
        } finally { runner.abortBootToHome(); field.set(runner, original) }
    }
    @Test fun longPressAppList_inSplit_showsSettingsInPanel() {
        splitPanel(); settingsLongPress()
        rule.onNodeWithTag("settings_close").assertIsDisplayed()
        assertTrue(controller!!.get().settingsShown)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }
    @Test fun longPressAppList_outsideSplit_startsSettingsActivity() {
        host("settings", true); settingsLongPress()
        assertEquals(com.gt8d.launcher.settings.SettingsActivity::class.java.name,
            shadowOf(controller!!.get()).nextStartedActivity?.component?.className)
        assertFalse(controller!!.get().settingsShown)
    }
    @Test fun longPressAppList_whileOpening_doesNothing() = whileOpening {
        splitPanel(); settingsLongPress()
        assertFalse(controller!!.get().settingsShown)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }
    @Test fun settings_close_returnsToPanel() {
        splitPanel(); settingsLongPress(); rule.onNodeWithTag("settings_close").performClick()
        rule.onNodeWithTag("dock_applist").assertIsDisplayed(); assertFalse(controller!!.get().settingsShown)
    }
    @Test fun settings_survivesRecreate() {
        splitPanel(); settingsLongPress(); controller!!.recreate(); rule.waitForIdle()
        assertTrue(controller!!.get().settingsShown); rule.onNodeWithTag("settings_item_sets").assertIsDisplayed()
    }
    @Test fun safetyMode_showsRecovery_andDoesNotForward() {
        activateSafety()
        controller = Robolectric.buildActivity(PanelActivity::class.java).create().start().resume().visible()
        rule.onNodeWithTag("safety_recover").assertIsDisplayed()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2000))
        assertNull(shadowOf(controller!!.get()).nextStartedActivity); assertFalse(controller!!.get().isFinishing)
        rule.onNodeWithTag("dock_applist").assertDoesNotExist()
    }
    @Test fun safetyMode_recover_clearsAndStartsHome() {
        activateSafety(); splitPanel(); rule.onNodeWithTag("safety_recover").performClick()
        assertFalse(com.gt8d.launcher.safety.SafetyGuard.isFallbackActive(app)); assertTrue(AppGraph.bootHandled)
        assertEquals(com.gt8d.launcher.ui.home.HomeActivity::class.java.name,
            shadowOf(controller!!.get()).nextStartedActivity?.component?.className)
        assertTrue(controller!!.get().isFinishing)
    }
    @Test fun safetyMode_stock_startsStockLauncher() {
        activateSafety(); splitPanel(); rule.onNodeWithTag("safety_stock").performClick()
        assertEquals(com.gt8d.launcher.apps.stockLauncherIntent().component,
            shadowOf(controller!!.get()).nextStartedActivity?.component)
        assertTrue(controller!!.get().isFinishing)
    }
    @Test fun safetyMode_doesNotConsumePendingUrl() = whileOpening { scheduler ->
        activateSafety(); AppGraph.pendingUrl.set("https://example.com"); splitPanel()
        scheduler.advanceBy(10_000); shadowOf(Looper.getMainLooper()).idle()
        assertNull(AppGraph.runner.opening.value)
        assertEquals("https://example.com", AppGraph.pendingUrl.take())
        assertNull(controller!!.get().shownBrowserUrl)
    }
    @Test fun settings_browserOpenedThenClosed_returnsToSettingsRoot() {
        splitPanel(); settingsLongPress(); rule.onNodeWithTag("settings_item_sets").performClick()
        AppGraph.pendingUrl.set("https://example.com"); controller!!.pause().resume()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.onNodeWithTag("browser_close").performClick()
        rule.onNodeWithTag("settings_item_sets").assertIsDisplayed()
        rule.onNodeWithTag("settings_placeholder").assertDoesNotExist()
    }
    @Test fun settings_leaveSplit_forwardsToHomeAfterWait() {
        splitPanel(); settingsLongPress()
        shadowOf(controller!!.get()).setInMultiWindowMode(false)
        controller!!.get().onMultiWindowModeChanged(false, app.resources.configuration)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1501))
        assertEquals(com.gt8d.launcher.ui.home.HomeActivity::class.java.name,
            shadowOf(controller!!.get()).nextStartedActivity?.component?.className)
        assertTrue(controller!!.get().isFinishing)
    }
}
