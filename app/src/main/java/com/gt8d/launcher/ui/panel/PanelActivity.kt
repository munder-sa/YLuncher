package com.gt8d.launcher.ui.panel

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.gt8d.launcher.config.Side
import com.gt8d.launcher.pip.PipDetector
import com.gt8d.launcher.pip.AndroidPipDetector
import com.gt8d.launcher.split.PanelPlacement
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.stockLauncherIntent
import com.gt8d.launcher.apps.AndroidSplitRedirects
import com.gt8d.launcher.apps.SplitRedirect
import com.gt8d.launcher.apps.SplitRedirects
import com.gt8d.launcher.safety.SafetyGuard
import com.gt8d.launcher.settings.SettingsActivity
import com.gt8d.launcher.settings.SettingsActions
import com.gt8d.launcher.settings.SettingsHost
import com.gt8d.launcher.settings.SettingsRoute
import com.gt8d.launcher.settings.SafetyRecoveryScreen
import com.gt8d.launcher.settings.safetyRecover
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.edge.EdgeAction
import com.gt8d.launcher.edge.EdgeStripService
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.split.placementOf
import com.gt8d.launcher.ui.gesture.twoFingerSwipe
import com.gt8d.launcher.ui.home.HomeActivity
import com.gt8d.launcher.ui.theme.LauncherTheme
import com.gt8d.launcher.ui.theme.screenSemantics
import com.gt8d.launcher.ui.layout.launcherBackground
import com.gt8d.launcher.ui.layout.stripSafePadding

class PanelActivity : ComponentActivity() {
    internal var pipDetector: PipDetector = AndroidPipDetector
    internal var reopen: (String, Side) -> Boolean = { pkg, side -> AppGraph.runner.reopenWithApp(pkg, side) }
    internal var pauseMedia: (String) -> Boolean = { pkg -> com.gt8d.launcher.media.MediaWatcher.pauseIfPlaying(pkg) }
    internal var resumeMedia: (String) -> Unit = { pkg -> com.gt8d.launcher.media.MediaWatcher.playPackage(pkg) }
    internal var openingDone: () -> Boolean = { AppGraph.runner.opening.value == null }
    private var watchingPlacement = false
    private val placementListener = ViewTreeObserver.OnGlobalLayoutListener { reportPlacement() }
    internal var placementBounds: () -> Triple<Int, Int, Int> = {
        val decor = window.decorView
        val origin = IntArray(2)
        decor.getLocationOnScreen(origin)
        Triple(origin[0], origin[0] + decor.width, windowManager.maximumWindowMetrics.bounds.width())
    }

    private fun reportPlacement() {
        if (!watchingPlacement) return
        val (left, right, screenWidth) = placementBounds()
        AppGraph.reportPanelPlacement(placementOf(left, right, screenWidth, isInMultiWindowMode))
    }

    private val forwarder by lazy { AppGraph.forwarderFactory() }
    private val activityLauncher by lazy { AndroidActivityLauncher(this) }
    internal var launchApp: (String, Boolean) -> Boolean =
        { pkg, adjacent -> activityLauncher.startApp(pkg, adjacent) }
    internal lateinit var redirects: SplitRedirects
    internal var launchRedirect: (SplitRedirect, Boolean) -> Boolean =
        { redirect, adjacent -> activityLauncher.startRedirect(redirect, adjacent) }
    internal var launchComponent: (ComponentName, Boolean) -> Boolean =
        { component, adjacent -> activityLauncher.startComponent(component, adjacent) }
    // PiP を全画面に戻す起動は、分割の中の画面を起動元にすると PiP のまま残る(実機で確認)。起動元の画面を持たないアプリの Context から開く。
    private val appLauncher by lazy { AndroidActivityLauncher(applicationContext) }
    internal var pipLaunchApp: (String) -> Boolean = { pkg -> appLauncher.startApp(pkg, false) }
    internal var pipLaunchComponent: (ComponentName) -> Boolean = { component -> appLauncher.startComponent(component, false) }
    private var inSplitState by mutableStateOf(false)
    private var settingsOpen by mutableStateOf(false)
    private var settingsInitialRoute: SettingsRoute? = null
    internal val settingsShown: Boolean get() = settingsOpen
    private var safetyMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 中継が、自分で閉じたのかプロセスごと消えたのかを見分けるため。
        setResult(RESULT_OK)
        redirects = AndroidSplitRedirects(packageManager)
        AppGraph.panelAlive = true
        safetyMode = SafetyGuard.isFallbackActive(this)
        settingsOpen = savedInstanceState?.getBoolean("settings_open") ?: false
        settingsInitialRoute = if (savedInstanceState?.getBoolean("settings_new_set") == true) SettingsRoute.SetEdit(null, true) else null
        if (savedInstanceState?.getString("settings_route") == "lists") settingsInitialRoute = SettingsRoute.Lists
        if (savedInstanceState?.getString("settings_route") == "permissions") settingsInitialRoute = SettingsRoute.Permissions
        inSplitState = isInMultiWindowMode
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.panelLaunchRequests.collect { pkg ->
                    val succeeded = onLaunchApp(pkg)
                    DiagLog.d("Panel", "launchRequest $pkg ${if (succeeded) "ok" else "failed"}")
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) { DiagLog.d("Panel", "back ignored") }
        setContent {
            val placement by AppGraph.panelPlacement.collectAsState()
            LauncherTheme {
                Box(Modifier.fillMaxSize().screenSemantics()) {
                    if (safetyMode) {
                        SafetyRecoveryScreen(
                            onRecover = { safetyRecover(this@PanelActivity); goHome() },
                            onStock = { runCatching { startActivity(stockLauncherIntent()) }; finish() },
                        )
                        return@Box
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .launcherBackground()
                            .stripSafePadding()
                            .twoFingerSwipe { action ->
                                when (action) {
                                    EdgeAction.Next -> AppGraph.runner.next()
                                    EdgeAction.Prev -> AppGraph.runner.prev()
                                    else -> Unit
                                }
                            },
                    ) {
                        if (settingsOpen) {
                            SettingsHost(SettingsActions.forApp(this@PanelActivity),
                                onExit = { settingsOpen = false }, onSafetyCleared = ::goHome,
                                initialRoute = settingsInitialRoute,
                                onOpenSet = { settingsOpen = false; AppGraph.runner.open(it) })
                        } else PanelScreen(
                            inSplit = inSplitState,
                            placement = placement,
                            onNewSet = {
                                if (isInMultiWindowMode) {
                                    settingsInitialRoute = SettingsRoute.SetEdit(null, true)
                                    settingsOpen = true
                                } else startActivity(Intent(this@PanelActivity, SettingsActivity::class.java).putExtra("open_new_set", true))
                            },
                            onEditLists = {
                                if (AppGraph.runner.opening.value != null) {
                                    DiagLog.d("Panel", "settings ignored while opening")
                                } else if (isInMultiWindowMode) {
                                    settingsInitialRoute = SettingsRoute.Lists
                                    settingsOpen = true
                                } else startActivity(Intent(this@PanelActivity, SettingsActivity::class.java).putExtra("open_route", "lists"))
                            },
                            onOpenSettings = {
                                if (AppGraph.runner.opening.value != null) {
                                    DiagLog.d("Panel", "settings ignored while opening")
                                } else if (isInMultiWindowMode) {
                                    settingsInitialRoute = null
                                    settingsOpen = true
                                } else startActivity(Intent(this@PanelActivity, SettingsActivity::class.java))
                            },
                            onOpenPermissionSettings = {
                                if (AppGraph.runner.opening.value != null) {
                                    DiagLog.d("Panel", "settings ignored while opening")
                                } else if (isInMultiWindowMode) {
                                    settingsInitialRoute = SettingsRoute.Permissions
                                    settingsOpen = true
                                } else startActivity(Intent(this@PanelActivity, SettingsActivity::class.java).putExtra("open_route", "permissions"))
                            },
                            onLaunchComponent = ::onLaunchComponent,
                            onLaunchApp = { pkg -> onLaunchApp(pkg) },
                        )
                    }
                }
            }
        }
    }

    private fun relaunchPip(pkg: String, launch: () -> Boolean): Boolean {
        val side = when (AppGraph.panelPlacement.value) {
            PanelPlacement.Left -> Side.Left
            PanelPlacement.Right -> Side.Right
            else -> null
        }
        // 再生中のまま組み直すと、分割の準備で裏に回ったアプリが PiP に入り直し、分割が失敗する(実機で確認)。組み直す間だけ止める。
        val paused = side != null && pauseMedia(pkg)
        // アプリからの startActivity では PiP が解除されない(実機で確認)。ユーザー補助の ACTION_EXPAND で解除し、送れなければ起動で試す。
        val expanded = pipDetector.expand(pkg)
        val succeeded = expanded || launch()
        AppGraph.warm.recordAppLaunch(pkg, succeeded)
        DiagLog.d("Panel", "pip relaunch pkg=$pkg expanded=$expanded paused=$paused resplit=${side ?: "none"}")
        if (side == null) return succeeded
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            // PiP のまま分割を組み直すと失敗し、SystemUI の分割が止まることがある。解除を確かめてから組み直す。
            val reopened = if (pipDetector.isInPip(pkg)) {
                DiagLog.d("Panel", "pip still pinned, skip resplit pkg=$pkg")
                false
            } else reopen(pkg, side)
            if (!paused) return@postDelayed
            if (!reopened) { resumeMedia(pkg); return@postDelayed }
            var waited = 0L
            fun resumeWhenDone() {
                if (openingDone() || waited >= PIP_RESUME_LIMIT_MS) {
                    DiagLog.d("Panel", "pip resume pkg=$pkg waited=$waited")
                    resumeMedia(pkg)
                } else {
                    waited += PIP_RESUME_POLL_MS
                    handler.postDelayed({ resumeWhenDone() }, PIP_RESUME_POLL_MS)
                }
            }
            resumeWhenDone()
        }, PIP_RESPLIT_DELAY_MS)
        return succeeded
    }

    internal fun onLaunchComponent(component: ComponentName): Boolean {
        val pkg = component.packageName
        if (isInMultiWindowMode && pipDetector.isInPip(pkg)) {
            return relaunchPip(pkg) { pipLaunchComponent(component) }
        }
        val succeeded = launchComponent(component, isInMultiWindowMode)
        AppGraph.warm.recordAppLaunch(pkg, succeeded)
        if (succeeded && isInMultiWindowMode) AppGraph.runner.panelLaunched(pkg)
        return succeeded
    }

    internal fun onLaunchApp(pkg: String): Boolean {
        val redirect = if (isInMultiWindowMode) redirects.forPackage(pkg) else null
        if (redirect != null) {
            val succeeded = if (redirect.component == null && redirect.uri == null) {
                launchApp(redirect.targetPkg, true)
            } else launchRedirect(redirect, true)
            AppGraph.warm.recordAppLaunch(redirect.targetPkg, succeeded)
            if (succeeded) {
                AppGraph.runner.panelLaunched(redirect.targetPkg)
                return true
            }
        }
        if (isInMultiWindowMode && pipDetector.isInPip(pkg)) {
            return relaunchPip(pkg) { pipLaunchApp(pkg) }
        }
        val succeeded = launchApp(pkg, isInMultiWindowMode)
        AppGraph.warm.recordAppLaunch(pkg, succeeded)
        if (succeeded && isInMultiWindowMode) AppGraph.runner.panelLaunched(pkg)
        return succeeded
    }

    override fun onStart() {
        super.onStart()
        watchingPlacement = true
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(placementListener)
        reportPlacement()
    }

    override fun onStop() {
        watchingPlacement = false
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(placementListener)
        AppGraph.reportPanelPlacement(null)
        super.onStop()
    }

    override fun onResume() {
        com.gt8d.launcher.foreground.OwnResumeClock.mark(javaClass.name)
        super.onResume()
        reportPlacement()
        AppGraph.runner.ownScreenResumed()
        AppGraph.panelInSplit = isInMultiWindowMode
        AppGraph.panelSplitPausedAtMs = null
        inSplitState = isInMultiWindowMode
        if (safetyMode) return
        EdgeStripService.ensureRunning(this)
        if (isInMultiWindowMode) {
            AppGraph.runner.panelResumedInSplit()
        }
        forwarder.onShown(isInMultiWindowMode, ::goHome)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        reportPlacement()
        if (!isInMultiWindowMode) AppGraph.panelSplitPausedAtMs = null
        inSplitState = isInMultiWindowMode
        AppGraph.panelInSplit = isInMultiWindowMode
        if (safetyMode) return
        forwarder.onMultiWindowChanged(isInMultiWindowMode)
        if (isInMultiWindowMode) {
            AppGraph.runner.panelResumedInSplit()
        }
    }

    override fun onPause() {
        if (AppGraph.panelInSplit) AppGraph.panelSplitPausedAtMs = android.os.SystemClock.elapsedRealtime()
        AppGraph.panelInSplit = false
        forwarder.onHidden()
        super.onPause()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) AppGraph.panelAlive = false
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("settings_open", settingsOpen)
        outState.putBoolean("settings_new_set", (settingsInitialRoute as? SettingsRoute.SetEdit)?.fromSplitButton == true)
        if (settingsInitialRoute == SettingsRoute.Lists) outState.putString("settings_route", "lists")
        if (settingsInitialRoute == SettingsRoute.Permissions) outState.putString("settings_route", "permissions")
        super.onSaveInstanceState(outState)
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

    companion object {
        internal const val PIP_RESPLIT_DELAY_MS = 1200L
        internal const val PIP_RESUME_POLL_MS = 200L
        internal const val PIP_RESUME_LIMIT_MS = 6000L
    }
}
