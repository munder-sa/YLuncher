package com.gt8d.launcher.ui.home

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.boot.BootPhase
import com.gt8d.launcher.config.LastSetIndexFixture
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.safety.SafetyGuard
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val configFlow = MutableStateFlow<LauncherConfig?>(null)
    private var controller: ActivityController<HomeActivity>? = null
    private var secondController: ActivityController<HomeActivity>? = null
    private var controllerDestroyed = false
    // AppGraph の lazy repository は、別テストの Application の保存先を保持する。
    // 検証と後始末も、実際に repository が使うファイルに対して行う。
    private lateinit var lastSetIndexFixture: LastSetIndexFixture
    private val indexFile get() = lastSetIndexFixture.indexFile
    private var savedFlow: StateFlow<LauncherConfig?>? = null
    private var savedHandled = false
    private var savedHome = false
    private var savedPausedAt: Long? = null
    private var savedSafety: String? = null
    private val safetyFile get() = File(app.filesDir, "safety.json")

    @Before
    fun prepare() {
        AppGraph.init(app)
        savedFlow = AppGraph.configFlowForHomeTest
        savedHandled = AppGraph.bootHandled
        savedHome = AppGraph.homeResumed
        savedPausedAt = AppGraph.homePausedAtMs
        savedSafety = safetyFile.takeIf { it.exists() }?.readText()
        AppGraph.resetBootForTest()
        SafetyGuard.clear(app)
        AppGraph.configFlowForHomeTest = configFlow
        AppGraph.bootHandled = false
        lastSetIndexFixture = LastSetIndexFixture(AppGraph.config)
        AppGraph.config.lastSetIndex = 4
    }

    @After
    fun cleanup() {
        AppGraph.boot.stayHome()
        if (!controllerDestroyed) controller?.pause()?.stop()?.destroy()
        secondController?.pause()?.stop()?.destroy()
        AppGraph.configFlowForHomeTest = savedFlow
        AppGraph.bootHandled = savedHandled
        AppGraph.homeResumed = savedHome
        AppGraph.homePausedAtMs = savedPausedAt
        savedSafety?.let { safetyFile.writeText(it) } ?: safetyFile.delete()
        lastSetIndexFixture.close()
    }

    private fun resumeLoading() {
        assertTrue(SystemClock.elapsedRealtime() < HomeActivity.BOOT_WINDOW_MS)
        controller = Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun pause_recordsPausedAt_resumeClears() {
        resumeLoading()
        assertEquals(null, AppGraph.homePausedAtMs)
        controller!!.pause()
        assertEquals(SystemClock.elapsedRealtime(), AppGraph.homePausedAtMs)
        assertFalse(AppGraph.homeResumed)
        controller!!.resume()
        assertEquals(null, AppGraph.homePausedAtMs)
        assertTrue(AppGraph.homeResumed)
    }

    private fun loadedConfig() = LauncherConfig(
        screenSets = (1..4).map { ScreenSet("s$it", Slot.App("a"), Slot.Panel) },
        homeApps = emptyList(), panelApps = emptyList(), dockApps = emptyList(), idleApp = "a",
    )

    private fun waitingForResume(decide: () -> Unit): ActivityController<HomeActivity> {
        assertTrue(SystemClock.elapsedRealtime() < HomeActivity.BOOT_WINDOW_MS)
        val c = Robolectric.buildActivity(HomeActivity::class.java)
        controller = c
        c.get().onBootDecisionForTest = decide
        c.create().start()
        HomeActivity::class.java.getDeclaredMethod("awaitConfigAndHandleBoot")
            .apply { isAccessible = true }.invoke(c.get())
        configFlow.value = loadedConfig()
        shadowOf(Looper.getMainLooper()).idle()
        val job = HomeActivity::class.java.getDeclaredField("bootConfigJob")
            .apply { isAccessible = true }.get(c.get()) as Job
        assertTrue(job.isActive)
        assertEquals(Lifecycle.State.STARTED, c.get().lifecycle.currentState)
        return c
    }

    @Test fun homeActivity_loadedConfig_waitingForResume_rapidPauseResume_decidesOnce() {
        var decisions = 0
        val c = waitingForResume { decisions++ }
        assertEquals(0, decisions)
        c.resume().pause().resume().pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, decisions)
        assertTrue(AppGraph.bootHandled)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, decisions)
    }

    @Test fun homeActivity_loadedConfig_repeatedResumeWithoutBootCancel_decidesOnce() {
        var decisions = 0
        configFlow.value = loadedConfig()
        assertTrue(SystemClock.elapsedRealtime() < HomeActivity.BOOT_WINDOW_MS)
        controller = Robolectric.buildActivity(HomeActivity::class.java).also {
            it.get().onBootDecisionForTest = { decisions++ }
        }.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, decisions)
        repeat(3) {
            controller!!.pause().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, decisions)
            assertEquals(BootPhase.Countdown, AppGraph.boot.phase.value)
        }
    }

    private fun destroyLoading(decide: () -> Unit) {
        assertTrue(SystemClock.elapsedRealtime() < HomeActivity.BOOT_WINDOW_MS)
        controller = Robolectric.buildActivity(HomeActivity::class.java).also {
            it.get().onBootDecisionForTest = decide
        }.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        controller!!.pause().stop().destroy()
        controllerDestroyed = true
    }

    @Test fun homeActivity_destroyedWhileLoading_neverDecidesAfterConfigArrives() {
        var decisions = 0
        destroyLoading { decisions++ }
        configFlow.value = loadedConfig()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, decisions)
        assertFalse(AppGraph.bootHandled)
        assertEquals(BootPhase.Idle, AppGraph.boot.phase.value)
    }

    @Test fun homeActivity_recreatedAfterDestroyWhileLoading_onlyNewActivityDecides() {
        var oldDecisions = 0
        var newDecisions = 0
        destroyLoading { oldDecisions++ }
        secondController = Robolectric.buildActivity(HomeActivity::class.java).also {
            it.get().onBootDecisionForTest = { newDecisions++ }
        }.create().start().resume()
        configFlow.value = loadedConfig()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, oldDecisions)
        assertEquals(1, newDecisions)
        assertTrue(AppGraph.bootHandled)
        secondController!!.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, oldDecisions)
        assertEquals(1, newDecisions)
    }

    @Test fun homeActivity_destroyedWhileWaitingForResume_neverDecides() {
        var decisions = 0
        val c = waitingForResume { decisions++ }
        assertEquals(0, decisions)
        c.stop().destroy()
        controllerDestroyed = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, decisions)
        assertFalse(AppGraph.bootHandled)
        assertEquals(BootPhase.Idle, AppGraph.boot.phase.value)
    }

    @Test
    fun homeActivity_loadingConfig_doesNotHandleBootOrOverwriteIndex() {
        resumeLoading()
        assertFalse(AppGraph.bootHandled)
        assertEquals("4", indexFile.readText())
    }

    @Test
    fun homeActivity_loadedConfig_handlesBootOnlyOnce() {
        resumeLoading()
        assertFalse(AppGraph.bootHandled)
        configFlow.value = loadedConfig()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AppGraph.bootHandled)
        assertEquals(BootPhase.Countdown, AppGraph.boot.phase.value)
        AppGraph.boot.stayHome()
        repeat(2) {
            controller!!.pause().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AppGraph.bootHandled)
            assertEquals(BootPhase.StayedHome, AppGraph.boot.phase.value)
            assertEquals("4", indexFile.readText())
        }
    }

    @Test
    fun homeActivity_pausedBeforeConfig_doesNotStartBoot() {
        resumeLoading()
        controller!!.pause()
        configFlow.value = loadedConfig()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AppGraph.bootHandled)
        assertEquals(BootPhase.Idle, AppGraph.boot.phase.value)
        controller!!.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AppGraph.bootHandled)
        assertEquals(BootPhase.Countdown, AppGraph.boot.phase.value)
        AppGraph.boot.stayHome()
        controller!!.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BootPhase.StayedHome, AppGraph.boot.phase.value)
    }

    private fun showSafetyHome() {
        RuntimeEnvironment.setQualifiers("w1280dp-h1000dp")
        SafetyGuard.recordStart(app)
        SafetyGuard.recordCrash(app)
        SafetyGuard.recordCrash(app)
        SafetyGuard.recordCrash(app)
        assertTrue(SafetyGuard.isFallbackActive(app))

        configFlow.value = loadedConfig()
        controller = Robolectric.buildActivity(HomeActivity::class.java).setup().visible()
        rule.waitForIdle()
    }

    @Test
    fun homeActivity_safetyActive_showsActionsWithoutAutomaticLaunch() {
        showSafetyHome()
        rule.onNodeWithText("安全のため純正ランチャーを使っています").assertIsDisplayed()
        val stock = rule.onNodeWithTag("safety_open_stock")
        val unlock = rule.onNodeWithTag("safety_unlock")
        stock.assertIsDisplayed().assertTextEquals("純正ランチャーを開く").assertHeightIsAtLeast(72.dp)
        unlock.assertIsDisplayed().assertTextEquals("安全モードを解除").assertHeightIsAtLeast(72.dp)
        val stockBounds = stock.getUnclippedBoundsInRoot()
        val unlockBounds = unlock.getUnclippedBoundsInRoot()
        assertEquals(16.dp, unlockBounds.top - stockBounds.bottom)
        assertEquals(360.dp, stockBounds.right - stockBounds.left)
        assertEquals(360.dp, unlockBounds.right - unlockBounds.left)
        repeat(2) { attempt ->
            if (attempt > 0) {
                controller!!.pause().resume()
                rule.waitForIdle()
            }
            assertNull(shadowOf(controller!!.get()).nextStartedActivity)
            assertNull(shadowOf(app).nextStartedService)
            assertFalse(AppGraph.bootHandled)
            assertEquals(BootPhase.Idle, AppGraph.boot.phase.value)
            assertEquals("4", indexFile.readText())
        }
    }

    @Test
    fun homeActivity_safetyOpenStock_opensStockLauncher() {
        showSafetyHome()
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
        rule.onNodeWithTag("safety_open_stock").performClick()
        val started = shadowOf(controller!!.get()).nextStartedActivity

        assertEquals(
            ComponentName(
                "com.android.launcher3",
                "com.android.launcher3.uioverrides.QuickstepLauncher",
            ),
            started.component,
        )
    }

    @Test
    fun homeActivity_safetyUnlock_recreatesNormalHomeWithoutBootRestore() {
        showSafetyHome()
        val old = controller!!.get()
        rule.onNodeWithTag("safety_unlock").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        assertFalse(SafetyGuard.isFallbackActive(app))
        assertNotSame(old, controller!!.get())
        rule.onNodeWithText("安全のため純正ランチャーを使っています").assertDoesNotExist()
        rule.onNodeWithTag("safety_open_stock").assertDoesNotExist()
        rule.onNodeWithTag("safety_unlock").assertDoesNotExist()
        rule.onNodeWithTag("dock_applist").assertIsDisplayed()
        assertTrue(AppGraph.bootHandled)
        assertEquals(BootPhase.Idle, AppGraph.boot.phase.value)
        assertEquals("4", indexFile.readText())
        assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }
}
