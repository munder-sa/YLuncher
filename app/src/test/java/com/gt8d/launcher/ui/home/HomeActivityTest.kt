package com.gt8d.launcher.ui.home

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.boot.BootPhase
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeActivityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val configFlow = MutableStateFlow<LauncherConfig?>(null)
    private var controller: ActivityController<HomeActivity>? = null
    private var secondController: ActivityController<HomeActivity>? = null
    private var controllerDestroyed = false
    // AppGraph の lazy repository は、別テストの Application の保存先を保持する。
    // 検証と後始末も、実際に repository が使うファイルに対して行う。
    private val indexFile get() = AppGraph.config.javaClass.getDeclaredField("lastSetFile")
        .apply { isAccessible = true }.get(AppGraph.config) as File
    private var savedIndex: String? = null
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
        savedIndex = indexFile.takeIf { it.exists() }?.readText()
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
        savedIndex?.let { indexFile.writeText(it) } ?: indexFile.delete()
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

    @Test
    fun homeActivity_safetyActive_startsStockLauncher() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SafetyGuard.clear(app)
        SafetyGuard.recordStart(app)
        SafetyGuard.recordCrash(app)
        SafetyGuard.recordCrash(app)
        SafetyGuard.recordCrash(app)
        assertTrue(SafetyGuard.isFallbackActive(app))

        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        val started = shadowOf(activity).nextStartedActivity

        assertEquals(
            ComponentName(
                "com.android.launcher3",
                "com.android.launcher3.uioverrides.QuickstepLauncher",
            ),
            started.component,
        )
    }
}
