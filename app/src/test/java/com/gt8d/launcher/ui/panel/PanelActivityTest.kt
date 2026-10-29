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
import com.gt8d.launcher.link.BrowserActivity
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
    private val appCalls = mutableListOf<Pair<String, Boolean>>()
    private val componentCalls = mutableListOf<Pair<ComponentName, Boolean>>()

    @Before fun prepare() {
        RuntimeEnvironment.setQualifiers("w1280dp-h1000dp")
        AppGraph.init(app)
        savedSplit = AppGraph.panelInSplit
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

    private fun splitPanel() {
        controller = Robolectric.buildActivity(PanelActivity::class.java)
        shadowOf(controller!!.get()).setInMultiWindowMode(true)
        assertTrue(controller!!.get().isInMultiWindowMode)
        controller!!.create().start().resume().visible()
        rule.waitForIdle()
    }

    private fun assertBrowserOnce() {
        val activity = controller!!.get()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(ComponentName(activity, BrowserActivity::class.java), started.component)
        assertEquals("https://example.com", started.getStringExtra(BrowserActivity.EXTRA_URL))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(AppGraph.pendingUrl.take())
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
}
