package com.gt8d.launcher.link

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.sets.SetRunnerDeps
import com.gt8d.launcher.split.SplitModeState
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class LinkEntryActivityTest {
    @Test fun appPanelFront_setsPendingUrl_withoutStartingActivity() {
        flow.value = Defaults.build(emptySet())
        AppGraph.panelInSplit = true
        val activity = open("https://example.com")
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("https://example.com", AppGraph.pendingUrl.take())
        assertEquals(0, openForLinkCalls)
        assertTrue(activity.isFinishing)
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val flow = MutableStateFlow<LauncherConfig?>(null)
    private var controller: ActivityController<LinkEntryActivity>? = null
    private var savedFlow: StateFlow<LauncherConfig?>? = null
    private var savedHome = false
    private var savedPanel = false
    private var savedHomeAt: Long? = null
    private var savedPanelAt: Long? = null
    private lateinit var savedDeps: SetRunnerDeps
    private lateinit var savedMode: SplitModeState
    private var openForLinkCalls = 0
    private val depsField get() = AppGraph.runner.javaClass.getDeclaredField("d").apply { isAccessible = true }
    private val modeField get() = AppGraph.selector.javaClass.getDeclaredField("state").apply { isAccessible = true }

    @Before fun prepare() {
        AppGraph.init(app)
        savedFlow = AppGraph.configFlowForHomeTest
        savedHome = AppGraph.homeResumed
        savedPanel = AppGraph.panelInSplit
        savedHomeAt = AppGraph.homePausedAtMs
        savedPanelAt = AppGraph.panelSplitPausedAtMs
        AppGraph.configFlowForHomeTest = flow
        AppGraph.homeResumed = false
        AppGraph.panelInSplit = false
        AppGraph.homePausedAtMs = null
        AppGraph.panelSplitPausedAtMs = null
        savedDeps = depsField.get(AppGraph.runner) as SetRunnerDeps
        // openForLink の最初の設定参照を数え、分割の実行前に断る。
        depsField.set(AppGraph.runner, savedDeps.copy(config = { openForLinkCalls++; null }))
        savedMode = AppGraph.selector.state
        modeField.set(AppGraph.selector, SplitModeState())
    }

    @After fun cleanup() {
        controller?.destroy()
        depsField.set(AppGraph.runner, savedDeps)
        modeField.set(AppGraph.selector, savedMode)
        AppGraph.configFlowForHomeTest = savedFlow
        AppGraph.homeResumed = savedHome
        AppGraph.panelInSplit = savedPanel
        AppGraph.homePausedAtMs = savedHomeAt
        AppGraph.panelSplitPausedAtMs = savedPanelAt
        AppGraph.pendingUrl.take()
    }

    private fun open(url: String): LinkEntryActivity {
        controller = Robolectric.buildActivity(LinkEntryActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://com.example.mail"))
        return controller!!.create().get()
    }

    @Test fun homeJustPaused_routesAsHome() {
        flow.value = Defaults.build(emptySet())
        AppGraph.homePausedAtMs = SystemClock.elapsedRealtime() - 500L
        val activity = open("https://example.com")
        assertEquals(ComponentName(app, FullscreenBrowserActivity::class.java), shadowOf(activity).nextStartedActivity.component)
        assertEquals(0, openForLinkCalls)
        assertTrue(activity.isFinishing)
    }

    @Test fun homePausedLongAgo_routesBySender() {
        flow.value = Defaults.build(emptySet())
        AppGraph.homePausedAtMs = SystemClock.elapsedRealtime() - 5000L
        open("https://example.com")
        assertEquals(1, openForLinkCalls)
    }

    private fun assertChrome(activity: LinkEntryActivity) {
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(LinkRouter.CHROME_PKG, started.`package`)
        assertEquals("https://accounts.google.com/", started.dataString)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun coldStart_waitsForConfig_thenRoutes() {
        val activity = open("https://accounts.google.com/")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        flow.value = Defaults.build(emptySet())
        shadowOf(Looper.getMainLooper()).idle()
        assertChrome(activity)
    }

    @Test fun coldStart_timeout_routesWithDefaults() {
        val activity = open("https://accounts.google.com/")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500L))
        assertChrome(activity)
    }

    @Test fun coldStart_usesFrontCluesFromArrival() {
        AppGraph.homePausedAtMs = SystemClock.elapsedRealtime() - 500L
        val activity = open("https://example.com")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000L))
        AppGraph.homePausedAtMs = null
        flow.value = Defaults.build(emptySet())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ComponentName(app, FullscreenBrowserActivity::class.java), shadowOf(activity).nextStartedActivity.component)
        assertEquals(0, openForLinkCalls)
        assertTrue(activity.isFinishing)
    }

    @Test fun recreated_doesNotRouteAgain() {
        val original = open("https://accounts.google.com/")
        controller!!.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(original).nextStartedActivity)
        assertFalse(original.isFinishing)

        controller!!.recreate()
        val recreated = controller!!.get()
        assertNotSame(original, recreated)
        flow.value = Defaults.build(emptySet())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500L))

        assertNull(shadowOf(original).nextStartedActivity)
        assertNull(shadowOf(recreated).nextStartedActivity)
        assertEquals(0, openForLinkCalls)
        assertTrue(recreated.isFinishing)
    }

    @Test fun destroyedWhileWaiting_discardsLink() {
        val activity = open("https://accounts.google.com/")
        shadowOf(Looper.getMainLooper()).idle()
        controller!!.destroy()
        controller = null
        flow.value = Defaults.build(emptySet())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500L))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        assertEquals(0, openForLinkCalls)
    }
}
