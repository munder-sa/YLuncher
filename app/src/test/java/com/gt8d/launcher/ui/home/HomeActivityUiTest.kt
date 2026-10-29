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
