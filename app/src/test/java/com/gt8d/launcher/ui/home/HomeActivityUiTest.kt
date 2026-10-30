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
            homeApps = listOf(pkg), panelApps = emptyList(), dockApps = emptyList(), idleApp = "example.other.idle",
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
        shadowOf(app.packageManager).removePackage(pkg)
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
    }
    private fun assertLaunch() {
        rule.onNodeWithTag("icon_$pkg").performScrollTo().performClick()
        assertEquals(component, shadowOf(controller!!.get()).nextStartedActivity?.component)
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
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

    @Test fun homeActivity_packageAdded_enablesHomeAppWithoutConfigChange() {
        host()
        val media = MediaWatcher.nowPlaying.value
        rule.onNodeWithTag("missing_$pkg").assertExists()
        val config = flow.value
        rule.runOnIdle { install() }
        broadcast(Intent.ACTION_PACKAGE_ADDED)
        rule.onNodeWithTag("icon_$pkg").assert(hasText("Home App"))
        assertSame(config, flow.value)
        assertSame(media, MediaWatcher.nowPlaying.value)
        assertLaunch()
    }
    @Test fun homeActivity_packageRemoved_disablesHomeAppWithoutConfigChange() {
        install(); host()
        val config = flow.value
        val media = MediaWatcher.nowPlaying.value
        rule.onNodeWithTag("icon_$pkg").assertExists()
        rule.runOnIdle { shadowOf(app.packageManager).removePackage(pkg) }
        broadcast(Intent.ACTION_PACKAGE_REMOVED)
        rule.onNodeWithTag("missing_$pkg").assert(hasText("アプリが見つかりません")).performClick()
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
        assertSame(config, flow.value)
        assertSame(media, MediaWatcher.nowPlaying.value)
    }
    @Test fun homeActivity_packageChanged_refreshesHomeAppWithoutConfigChange() {
        install(); host()
        val config = flow.value
        val media = MediaWatcher.nowPlaying.value
        rule.onNodeWithTag("icon_$pkg").assert(hasText("Home App"))
        rule.runOnIdle { install("Changed Home") }
        broadcast(Intent.ACTION_PACKAGE_CHANGED)
        rule.onNodeWithTag("icon_$pkg").assert(hasText("Changed Home"))
        assertSame(config, flow.value)
        assertEquals(listOf(pkg), flow.value!!.homeApps)
        assertSame(media, MediaWatcher.nowPlaying.value)
    }
    @Test fun homeActivity_resume_refreshesHomeAppWithoutPackageBroadcast() {
        host()
        val activity = controller!!.get()
        val config = flow.value
        val media = MediaWatcher.nowPlaying.value
        rule.onNodeWithTag("missing_$pkg").assertExists()
        rule.runOnIdle { controller!!.pause(); install(); controller!!.resume() }
        rule.onNodeWithTag("icon_$pkg").assert(hasText("Home App"))
        assertSame(activity, controller!!.get())
        assertSame(config, flow.value)
        assertSame(media, MediaWatcher.nowPlaying.value)
        assertLaunch()
    }
}
