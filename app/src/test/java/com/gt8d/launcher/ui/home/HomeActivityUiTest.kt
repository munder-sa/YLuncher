package com.gt8d.launcher.ui.home

import android.app.Application
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Process
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.media.MediaWatcher
import com.gt8d.launcher.safety.SafetyGuard
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeActivityUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val pkg = "example.home.wiring"
    private val component = ComponentName(pkg, "$pkg.Main")
    private lateinit var flow: MutableStateFlow<LauncherConfig?>
    private var controller: ActivityController<HomeActivity>? = null
    private var savedFlow: StateFlow<LauncherConfig?>? = null
    private var savedHandled = false
    private var savedHome = false
    private var savedPausedAt: Long? = null
    private var safety: String? = null
    private lateinit var safetyFile: File
    private var index: String? = null
    private lateinit var indexFile: File
    private var usageMode = 0

    @Before fun prepare() {
        RuntimeEnvironment.setQualifiers("w1280dp-h1000dp")
        AppGraph.init(app)
        AppGraph.appCatalogStateForTest = com.gt8d.launcher.apps.AppCatalogState(app, AppGraph.catalog,
            kotlinx.coroutines.test.UnconfinedTestDispatcher())
        assertFalse(app.packageManager.getInstalledPackages(0).any { it.packageName == pkg })
        savedFlow = AppGraph.configFlowForHomeTest
        savedHandled = AppGraph.bootHandled
        savedHome = AppGraph.homeResumed
        savedPausedAt = AppGraph.homePausedAtMs
        safetyFile = File(app.filesDir, "safety.json")
        safety = safetyFile.takeIf { it.exists() }?.readText()
        SafetyGuard.clear(app)
        indexFile = AppGraph.config.javaClass.getDeclaredField("lastSetFile")
            .apply { isAccessible = true }.get(AppGraph.config) as File
        index = indexFile.takeIf { it.exists() }?.readText()
        val ops = app.getSystemService(AppOpsManager::class.java)
        usageMode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ERRORED)
        flow = MutableStateFlow(Defaults.build(emptySet()).copy(
            screenSets = listOf(ScreenSet("home-test", Slot.App(pkg), Slot.Panel)),
            homeApps = listOf(pkg), panelApps = emptyList(), dockApps = emptyList(), idleApp = "example.other.idle",
            appListPkgs = emptyList(),
        ))
        AppGraph.configFlowForHomeTest = flow
        AppGraph.bootHandled = true
    }

    @After fun cleanup() {
        controller?.let {
            (it.get().window.decorView as android.view.ViewGroup).let { root ->
                fun dispose(view: android.view.View) {
                    if (view is ComposeView) view.disposeComposition()
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) dispose(view.getChildAt(i))
                }
                dispose(root)
            }
            it.pause().stop().destroy()
        }
        shadowOf(app.packageManager).removeActivity(component)
        shadowOf(app.packageManager).removePackage(pkg)
        AppGraph.appCatalogStateForTest?.close()
        AppGraph.appCatalogStateForTest = null
        AppGraph.catalog.invalidatePackage(pkg)
        AppGraph.configFlowForHomeTest = savedFlow
        AppGraph.bootHandled = savedHandled
        AppGraph.homeResumed = savedHome
        AppGraph.homePausedAtMs = savedPausedAt
        safety?.let { safetyFile.writeText(it) } ?: safetyFile.delete()
        index?.let { indexFile.writeText(it) } ?: indexFile.delete()
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, usageMode,
        )
    }

    private fun install(label: String = "Home App") {
        val pm = shadowOf(app.packageManager)
        pm.addOrUpdateActivity(ActivityInfo().apply {
            name = component.className; packageName = pkg; nonLocalizedLabel = label
        })
        pm.getInternalMutablePackageInfo(pkg).applicationInfo!!.nonLocalizedLabel = label
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
    private fun host() {
        controller = Robolectric.buildActivity(HomeActivity::class.java).create().start().resume().visible()
        rule.waitForIdle()
    }
    private fun broadcast(action: String) {
        rule.runOnIdle { app.sendBroadcast(Intent(action, Uri.parse("package:$pkg"))) }
        rule.waitForIdle()
        assertNull(AppGraph.appCatalogState.snapshot.value.failure)
    }
    private fun assertOpen() {
        val runner = AppGraph.runner
        val field = runner.javaClass.getDeclaredField("d").apply { isAccessible = true }
        val original = field.get(runner) as com.gt8d.launcher.sets.SetRunnerDeps
        val index = runner.currentIndex
        val scheduler = com.gt8d.launcher.time.FakeScheduler()
        field.set(runner, original.copy(config = { flow.value }, installed = { emptySet() },
            scheduler = scheduler, clock = scheduler, startHome = {},
            // カードの配線(開き始め)を見るため、ホーム表示中の即時完了(Task 099)は外す。
            homeVisible = { false }))
        try {
            runner.followIndex(0)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            rule.onNodeWithTag("home_set_home-test").performScrollTo().performClick()
            assertEquals(1, runner.currentIndex)
            assertEquals(pkg, runner.opening.value?.left)
            assertNull(shadowOf(controller!!.get()).nextStartedActivity)
        } finally {
            original.curtain.ready()
            scheduler.advanceBy(10000L)
            runner.followIndex(index)
            field.set(runner, original)
        }
    }

    @Test fun splitButton_startsSettingsWithNewSet() {
        host()
        rule.onNodeWithTag("dock_split").performScrollTo().performClick()
        val intent = shadowOf(controller!!.get()).nextStartedActivity
        assertEquals(com.gt8d.launcher.settings.SettingsActivity::class.java.name, intent?.component?.className)
        assertTrue(intent!!.getBooleanExtra("open_new_set", false))
    }
    @Test fun homeAppRow_panelAppLaunchesWithoutAdjacentFlags() {
        install()
        flow.value = flow.value!!.copy(panelApps = listOf(pkg), appListPkgs = emptyList())
        host()
        rule.onNodeWithTag("home_app_$pkg").performClick()
        val intent = requireNotNull(shadowOf(controller!!.get()).nextStartedActivity)
        assertEquals(component, intent.component)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
    @Test fun longPressAppList_startsSettingsActivity() {
        host()
        rule.onNodeWithTag("dock_applist").performScrollTo().performTouchInput { longClick() }
        assertEquals(com.gt8d.launcher.settings.SettingsActivity::class.java.name,
            shadowOf(controller!!.get()).nextStartedActivity?.component?.className)
    }
    @Test fun longPressAppList_whileOpening_doesNothing() {
        val runner = AppGraph.runner
        val field = runner.javaClass.getDeclaredField("d").apply { isAccessible = true }
        val original = field.get(runner) as com.gt8d.launcher.sets.SetRunnerDeps
        val scheduler = com.gt8d.launcher.time.FakeScheduler()
        field.set(runner, original.copy(scheduler = scheduler, clock = scheduler, startHome = {}))
        try {
            runner.openForBoot(1); assertNotNull(runner.opening.value); host()
            rule.onNodeWithTag("dock_applist").performScrollTo().performTouchInput { longClick() }
            assertNull(shadowOf(controller!!.get()).nextStartedActivity)
        } finally { runner.abortBootToHome(); field.set(runner, original) }
    }
    @Test fun createdInSafety_thenCleared_recreatesOnResume() {
        SafetyGuard.recordStart(app); repeat(3) { SafetyGuard.recordCrash(app) }
        AppGraph.config.lastSetIndex = 1
        AppGraph.bootHandled = false
        host()
        rule.onNodeWithText("安全のため純正ランチャーを使っています").assertIsDisplayed()
        val old = controller!!.get()
        controller!!.pause()
        com.gt8d.launcher.settings.safetyRecover(app)
        val phase = AppGraph.boot.phase.value
        controller!!.resume()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
        assertNotSame(old, controller!!.get())
        rule.onNodeWithText("安全のため純正ランチャーを使っています").assertDoesNotExist()
        rule.onNodeWithTag("dock_applist").assertIsDisplayed()
        assertTrue(AppGraph.bootHandled); assertEquals(phase, AppGraph.boot.phase.value)
    }

    @Test fun homeActivity_packageAdded_enablesSetCardWithoutConfigChange() {
        host()
        val media = MediaWatcher.nowPlaying.value
        rule.onNodeWithContentDescription("$pkg(アプリが見つかりません)", useUnmergedTree = true).assertExists()
        val config = flow.value
        rule.runOnIdle { install() }
        broadcast(Intent.ACTION_PACKAGE_ADDED)
        rule.onNodeWithContentDescription("Home App", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("home_set_home-test").assert(hasContentDescription("1  Home App | パネル"))
        assertSame(config, flow.value)
        assertSame(media, MediaWatcher.nowPlaying.value)
        assertOpen()
    }
    @Test fun homeActivity_packageRemoved_marksSetCardMissingWithoutConfigChange() {
        install(); host()
        val config = flow.value
        val media = MediaWatcher.nowPlaying.value
        rule.onNodeWithContentDescription("Home App", useUnmergedTree = true).assertExists()
        rule.runOnIdle {
            shadowOf(app.packageManager).removeActivity(component)
            shadowOf(app.packageManager).removePackage(pkg)
        }
        broadcast(Intent.ACTION_PACKAGE_REMOVED)
        rule.onNodeWithContentDescription("$pkg(アプリが見つかりません)", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("home_set_home-test").assert(hasContentDescription("1  $pkg | パネル"))
        assertOpen()
        assertSame(config, flow.value)
        assertSame(media, MediaWatcher.nowPlaying.value)
    }
    @Test fun homeActivity_packageChanged_refreshesSetCardWithoutConfigChange() {
        install(); host()
        val config = flow.value
        val media = MediaWatcher.nowPlaying.value
        rule.onNodeWithContentDescription("Home App", useUnmergedTree = true).assertExists()
        rule.runOnIdle { install("Changed Home") }
        broadcast(Intent.ACTION_PACKAGE_CHANGED)
        rule.onNodeWithContentDescription("Changed Home", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("home_set_home-test").assert(hasContentDescription("1  Changed Home | パネル"))
        assertSame(config, flow.value)
        assertEquals(listOf(pkg), flow.value!!.homeApps)
        assertSame(media, MediaWatcher.nowPlaying.value)
    }
    @Test fun homeActivity_resume_refreshesSetCardWithoutPackageBroadcast() {
        host()
        val activity = controller!!.get()
        val config = flow.value
        val media = MediaWatcher.nowPlaying.value
        rule.onNodeWithContentDescription("$pkg(アプリが見つかりません)", useUnmergedTree = true).assertExists()
        rule.runOnIdle {
            controller!!.pause(); install()
            app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:$pkg")))
        }
        rule.waitForIdle()
        rule.runOnIdle { controller!!.resume() }
        rule.onNodeWithContentDescription("Home App", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("home_set_home-test").assert(hasContentDescription("1  Home App | パネル"))
        assertSame(activity, controller!!.get())
        assertSame(config, flow.value)
        assertSame(media, MediaWatcher.nowPlaying.value)
        assertOpen()
    }
}
