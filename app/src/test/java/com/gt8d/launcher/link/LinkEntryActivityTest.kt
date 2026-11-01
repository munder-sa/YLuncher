package com.gt8d.launcher.link

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import com.gt8d.launcher.curtain.*
import com.gt8d.launcher.foreground.*
import com.gt8d.launcher.time.FakeScheduler
import com.gt8d.launcher.split.SplitLauncher
import com.gt8d.launcher.split.LaunchResult
import com.gt8d.launcher.split.FailReason
import com.gt8d.launcher.config.SplitRatio
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.sets.SetRunnerDeps
import com.gt8d.launcher.split.SplitModeState
import com.gt8d.launcher.split.PanelPlacement
import com.gt8d.launcher.split.VendorBridge
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
import org.robolectric.shadows.ShadowToast
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.notice.Notice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class LinkEntryActivityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val flow = MutableStateFlow<LauncherConfig?>(null)
    private var controller: ActivityController<LinkEntryActivity>? = null
    private var savedFlow: StateFlow<LauncherConfig?>? = null
    private var savedHome = false
    private var savedPanel = false
    private var savedHomeAt: Long? = null
    private var savedPanelAt: Long? = null
    private var savedPlacement: PanelPlacement? = null
    private var savedPair: Pair<String, String>? = null
    private var savedReadyAt: Long? = null
    private val readyAtField get() = AppGraph.runner.javaClass.getDeclaredField("lastReadyAtMs").apply { isAccessible = true }
    private val pairField get() = AppGraph.runner.javaClass.getDeclaredField("lastOpenedPair").apply { isAccessible = true }
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
        savedPlacement = AppGraph.panelPlacement.value
        savedPair = AppGraph.runner.lastOpenedPair
        savedReadyAt = AppGraph.runner.lastReadyAtMs
        readyAtField.set(AppGraph.runner, null)
        AppGraph.reportPanelPlacement(null)
        pairField.set(AppGraph.runner, null)
        AppGraph.configFlowForHomeTest = flow
        AppGraph.homeResumed = false
        AppGraph.panelInSplit = false
        AppGraph.homePausedAtMs = null
        AppGraph.panelSplitPausedAtMs = null
        registerBrowser("com.android.chrome")
        savedDeps = depsField.get(AppGraph.runner) as SetRunnerDeps
        // openForLink の最初の設定参照を数え、分割の実行前に断る。
        depsField.set(AppGraph.runner, savedDeps.copy(config = { openForLinkCalls++; null }))
        savedMode = AppGraph.selector.state
        modeField.set(AppGraph.selector, SplitModeState())
    }

    @After fun cleanup() {
        controller?.destroy()
        testCurtain?.fail()
        depsField.set(AppGraph.runner, savedDeps)
        modeField.set(AppGraph.selector, savedMode)
        AppGraph.configFlowForHomeTest = savedFlow
        AppGraph.homeResumed = savedHome
        AppGraph.panelInSplit = savedPanel
        AppGraph.homePausedAtMs = savedHomeAt
        AppGraph.panelSplitPausedAtMs = savedPanelAt
        AppGraph.reportPanelPlacement(savedPlacement)
        pairField.set(AppGraph.runner, savedPair)
        readyAtField.set(AppGraph.runner, savedReadyAt)
    }

    private fun open(url: String, referrer: String = "com.example.mail"): LinkEntryActivity {
        controller = Robolectric.buildActivity(LinkEntryActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://$referrer"))
        return controller!!.create().get()
    }

    @Test fun homeJustPaused_routesAsHome() {
        flow.value = Defaults.build(emptySet())
        AppGraph.homePausedAtMs = SystemClock.elapsedRealtime() - 500L
        val activity = open("https://example.com")
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.android.chrome")
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
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(LinkRouter.CHROME_PKG, started.`package`)
        assertEquals("https://accounts.google.com/", started.dataString)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(app).nextStartedActivity)
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
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.android.chrome")
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

    private var testCurtain: CurtainController? = null
    private fun registerBrowser(pkg: String) {
        val component = ComponentName(pkg, "$pkg.Main")
        shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
            name = component.className; packageName = pkg
        })
        shadowOf(app.packageManager).addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https"); addDataScheme("http")
        })
    }
    // removePackage は intent filter を残し、ブラウザの問い合わせが NPE になるため、activity ごと外す。
    private fun unregisterBrowser(pkg: String) {
        shadowOf(app.packageManager).removeActivity(ComponentName(pkg, "$pkg.Main"))
    }
    private fun assertView(intent: Intent?, url: String, pkg: String) {
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(url, intent.dataString)
        assertEquals(pkg, intent.`package`)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    private data class SplitFixture(
        val scheduler: FakeScheduler,
        val events: MutableList<ResumeEvent>,
        val calls: MutableList<Pair<String, String>>,
    )
    private fun splitFixture(fail: Boolean = false): SplitFixture {
        val scheduler = FakeScheduler()
        val events = mutableListOf<ResumeEvent>()
        val calls = mutableListOf<Pair<String, String>>()
        val curtain = CurtainController(scheduler, object : CurtainView {
            override fun show(target: CurtainTarget) {}
            override fun update(target: CurtainTarget) {}
            override fun showProgress() {}
            override fun hide(reason: HideReason) {}
        })
        curtain.onHidden = savedDeps.curtain.onHidden
        testCurtain = curtain
        val watcher = ForegroundWatcher(object : ForegroundEvents {
            override fun available() = true
            override fun resumedSince(sinceMs: Long) = events.filter { it.timeMs in sinceMs..scheduler.nowMs() }
        })
        depsField.set(AppGraph.runner, savedDeps.copy(
            config = { flow.value }, installed = { setOf("com.example.mail", "y", "com.android.chrome") },
            watcher = watcher, scheduler = scheduler, clock = scheduler, curtain = curtain,
            warm = WarmTracker(app.packageName) { true }, labelFor = { it }, startHome = {},
            vendor = object : SplitLauncher {
                override fun open(left: String, right: String, ratio: SplitRatio): LaunchResult {
                    assertEquals(SplitRatio.R5_5, ratio)
                    calls.add(left to right)
                    return if (fail) LaunchResult.Failed(FailReason.Exception) else LaunchResult.Started
                }
            },
            apps = object : com.gt8d.launcher.split.ActivityLauncher {
                override fun canResolve(pkg: String, cls: String) = true
                override fun start(spec: com.gt8d.launcher.split.LaunchSpec) {}
                override fun startApp(pkg: String, adjacent: Boolean) = true
                override fun isResizeable(pkg: String) = true
            },
        ))
        return SplitFixture(scheduler, events, calls)
    }
    @Test fun fullscreen_sendsViewToChosenBrowser() {
        registerBrowser("com.brave.browser")
        flow.value = Defaults.build(emptySet()).let { it.copy(link = it.link.copy(browserPkg = "com.brave.browser")) }
        AppGraph.homeResumed = true
        val activity = open("https://example.com")
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.brave.browser")
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(0, openForLinkCalls)
        assertTrue(activity.isFinishing)
    }
    @Test fun split_callsOpenForLinkWithBrowser() {
        flow.value = Defaults.build(emptySet())
        val fixture = splitFixture()
        val activity = open("https://example.com")
        assertEquals(listOf("com.example.mail" to "com.android.chrome"), fixture.calls)
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(activity.isFinishing)
        controller!!.destroy(); controller = null
        fixture.events.add(ResumeEvent("com.example.mail", 100))
        fixture.events.add(ResumeEvent("com.android.chrome", 120))
        fixture.scheduler.advanceBy(200)
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.android.chrome")
        fixture.scheduler.advanceBy(10000)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun split_failed_sendsViewOnce() {
        flow.value = Defaults.build(emptySet())
        val fixture = splitFixture(fail = true)
        open("https://example.com")
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.android.chrome")
        fixture.scheduler.advanceBy(10000)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun split_safety_sendsViewOnceAfterActivityDestroyed() {
        flow.value = Defaults.build(emptySet())
        val fixture = splitFixture()
        open("https://example.com")
        assertNull(shadowOf(app).nextStartedActivity)
        controller!!.destroy(); controller = null
        fixture.scheduler.advanceBy(3999)
        assertNull(shadowOf(app).nextStartedActivity)
        fixture.scheduler.advanceBy(1)
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.android.chrome")
        fixture.scheduler.advanceBy(10000)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun appPanelFront_splitWithLastLeft() {
        flow.value = Defaults.build(emptySet())
        val fixture = splitFixture()
        AppGraph.runner.javaClass.getDeclaredField("lastOpenedPair").apply {
            isAccessible = true; set(AppGraph.runner, "com.example.mail" to "com.gt8d.launcher.panel")
        }
        AppGraph.panelInSplit = true
        open("https://example.com")
        assertEquals(listOf("com.example.mail" to "com.android.chrome"), fixture.calls)
        assertNull(shadowOf(app).nextStartedActivity)
        fixture.events.add(ResumeEvent("com.example.mail", 100))
        fixture.events.add(ResumeEvent("com.android.chrome", 120))
        fixture.scheduler.advanceBy(200)
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.android.chrome")
    }
    @Test fun leftPanel_opensBrowserLeft() {
        flow.value = Defaults.build(emptySet())
        val fixture = splitFixture()
        pairField.set(AppGraph.runner, "com.gt8d.launcher.panel" to "y")
        AppGraph.panelInSplit = true
        AppGraph.reportPanelPlacement(PanelPlacement.Left)
        val activity = open("https://example.com")
        assertEquals(listOf("com.android.chrome" to "y"), fixture.calls)
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(activity.isFinishing)
        fixture.events.add(ResumeEvent("com.android.chrome", 100))
        fixture.events.add(ResumeEvent("y", 120))
        fixture.scheduler.advanceBy(200)
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.android.chrome")
        fixture.scheduler.advanceBy(10000)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun missingPreferredBrowser_secondLink_keepsChosenBrowserOrder() {
        flow.value = Defaults.build(emptySet()).let {
            it.copy(link = it.link.copy(browserPkg = "missing.browser"))
        }
        val fixture = splitFixture()
        pairField.set(AppGraph.runner, "com.android.chrome" to "com.example.mail")
        val activity = open("https://example.com")
        assertEquals(listOf("com.android.chrome" to "com.example.mail"), fixture.calls)
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(activity.isFinishing)
        fixture.events.add(ResumeEvent("com.android.chrome", 100))
        fixture.events.add(ResumeEvent("com.example.mail", 120))
        fixture.scheduler.advanceBy(200)
        assertView(shadowOf(app).nextStartedActivity, "https://example.com", "com.android.chrome")
        fixture.scheduler.advanceBy(10000)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun vendorSplitActivity_isNotOtherForeground() {
        assertVendorSplitForeground(realOtherApp = false)
    }

    @Test fun vendorSplitActivity_realOtherApp_stillCounts() {
        assertVendorSplitForeground(realOtherApp = true)
    }

    private fun assertVendorSplitForeground(realOtherApp: Boolean) {
        flow.value = Defaults.build(emptySet())
        val fixture = splitFixture()
        val startedAtField = AppGraph.runner.javaClass.getDeclaredField("lastOpenedAtMs").apply {
            isAccessible = true
        }
        val eventsField = AppGraph.watcher.javaClass.getDeclaredField("events").apply {
            isAccessible = true
        }
        val savedStartedAt = startedAtField.get(AppGraph.runner)
        val savedEvents = eventsField.get(AppGraph.watcher)
        val packages = listOf(VendorBridge.VENDOR_PKG, "com.android.chrome", "y") +
            if (realOtherApp) listOf("maps") else emptyList()
        try {
            pairField.set(AppGraph.runner, "com.android.chrome" to "y")
            startedAtField.set(AppGraph.runner, 0L)
            eventsField.set(AppGraph.watcher, object : ForegroundEvents {
                override fun available() = true
                override fun resumedSince(sinceMs: Long) = packages.map { ResumeEvent(it, 100L) }
            })
            val activity = open("https://example.com", referrer = "y")
            val expectedPair = if (realOtherApp) "y" to "com.android.chrome"
                else "com.android.chrome" to "y"
            assertEquals(listOf(expectedPair), fixture.calls)
            assertNull(shadowOf(app).nextStartedActivity)
            assertTrue(activity.isFinishing)
        } finally {
            eventsField.set(AppGraph.watcher, savedEvents)
            startedAtField.set(AppGraph.runner, savedStartedAt)
        }
    }

    @Test fun noBrowser_doesNothing() {
        // 出し方(Toast・幕待ち)は NoticeOverlayTest で確かめる。ここでは NoBrowser を渡したことを見る。
        DiagLog.clearForTest()
        unregisterBrowser("com.android.chrome")
        flow.value = Defaults.build(emptySet())
        val activity = open("https://example.com")
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(0, openForLinkCalls)
        assertTrue(activity.isFinishing)
        assertTrue(DiagLog.dump().any { "[Notice] show NoBrowser text=${Notice.NoBrowser.text}" in it })
    }

    @Test fun noBrowser_afterRouteException_notifies() {
        // 出し方(Toast・幕待ち)は NoticeOverlayTest で確かめる。ここでは NoBrowser を渡したことを見る。
        DiagLog.clearForTest()
        flow.value = Defaults.build(emptySet())
        depsField.set(AppGraph.runner, savedDeps.copy(config = {
            unregisterBrowser("com.android.chrome")
            throw IllegalStateException("route failed")
        }))
        val activity = open("https://example.com")
        assertTrue(DiagLog.dump().any { "[Notice] show NoBrowser text=${Notice.NoBrowser.text}" in it })
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    class FailingBrowserApplication : Application() {
        override fun startActivity(intent: Intent) {
            if (intent.action == Intent.ACTION_VIEW) throw android.content.ActivityNotFoundException("browser failed")
            super.startActivity(intent)
        }
    }

    @Test @Config(application = FailingBrowserApplication::class)
    fun browserViewFailure_notifies() {
        flow.value = Defaults.build(emptySet())
        AppGraph.homeResumed = true
        val activity = open("https://example.com")
        assertEquals(Notice.BrowserFailed.text, ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test @Config(application = FailingBrowserApplication::class)
    fun browserViewFailure_afterRouteException_notifies() {
        flow.value = Defaults.build(emptySet())
        depsField.set(AppGraph.runner, savedDeps.copy(config = { throw IllegalStateException("route failed") }))
        val activity = open("https://example.com")
        assertEquals(Notice.BrowserFailed.text, ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test fun transientStockLauncherBeforeReady_isIgnored() {
        assertStockLauncherBeforeReady(realOtherApp = false)
    }

    @Test fun transientStockLauncherBeforeReady_otherAppAfterReady_stillCounts() {
        assertStockLauncherBeforeReady(realOtherApp = true)
    }

    private fun assertStockLauncherBeforeReady(realOtherApp: Boolean) {
        flow.value = Defaults.build(emptySet())
        val fixture = splitFixture()
        val eventsField = AppGraph.watcher.javaClass.getDeclaredField("events").apply {
            isAccessible = true
        }
        val savedEvents = eventsField.get(AppGraph.watcher)
        try {
            assertTrue(AppGraph.runner.openForLink("com.android.chrome", "y", {}, {}))
            fixture.events.add(ResumeEvent("com.android.launcher3", 50L))
            fixture.events.add(ResumeEvent("com.android.chrome", 100L))
            fixture.events.add(ResumeEvent("y", 120L))
            fixture.scheduler.advanceBy(200L)
            assertEquals(0L, AppGraph.runner.lastOpenedAtMs)
            assertEquals(200L, AppGraph.runner.lastReadyAtMs)
            if (realOtherApp) fixture.events.add(ResumeEvent("maps", 250L))
            fixture.scheduler.advanceBy(100L)
            eventsField.set(AppGraph.watcher, object : ForegroundEvents {
                override fun available() = true
                override fun resumedSince(sinceMs: Long) = fixture.events.filter {
                    it.timeMs in sinceMs..fixture.scheduler.nowMs()
                }
            })
            val activity = open("https://example.com", referrer = "y")
            val expectedPair = if (realOtherApp) "y" to "com.android.chrome"
                else "com.android.chrome" to "y"
            assertEquals(listOf("com.android.chrome" to "y", expectedPair), fixture.calls)
            assertNull(shadowOf(app).nextStartedActivity)
            assertTrue(activity.isFinishing)
        } finally {
            eventsField.set(AppGraph.watcher, savedEvents)
        }
    }
}
