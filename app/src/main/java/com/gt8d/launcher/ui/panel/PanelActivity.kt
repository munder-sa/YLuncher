package com.gt8d.launcher.ui.panel

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.R
import com.gt8d.launcher.apps.stockLauncherIntent
import com.gt8d.launcher.safety.SafetyGuard
import com.gt8d.launcher.settings.SettingsActivity
import com.gt8d.launcher.settings.SettingsActions
import com.gt8d.launcher.settings.SettingsHost
import com.gt8d.launcher.settings.SettingsRoute
import com.gt8d.launcher.settings.SafetyRecoveryScreen
import com.gt8d.launcher.settings.safetyRecover
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.link.BrowserPane
import com.gt8d.launcher.edge.EdgeAction
import com.gt8d.launcher.edge.EdgeStripService
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.ui.gesture.twoFingerSwipe
import com.gt8d.launcher.ui.home.HomeActivity
import com.gt8d.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

class PanelActivity : ComponentActivity() {
    private val forwarder by lazy { AppGraph.forwarderFactory() }
    private val activityLauncher by lazy { AndroidActivityLauncher(this) }
    internal var launchApp: (String, Boolean) -> Boolean =
        { pkg, adjacent -> activityLauncher.startApp(pkg, adjacent) }
    internal var launchComponent: (ComponentName, Boolean) -> Boolean =
        { component, adjacent -> activityLauncher.startComponent(component, adjacent) }
    private var inSplitState by mutableStateOf(false)
    private var browserUrl: String? by mutableStateOf(null)
    private var settingsOpen by mutableStateOf(false)
    private var settingsInitialRoute: SettingsRoute? = null
    internal val settingsShown: Boolean get() = settingsOpen
    private var safetyMode = false
    internal val shownBrowserUrl: String? get() = browserUrl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        safetyMode = SafetyGuard.isFallbackActive(this)
        settingsOpen = savedInstanceState?.getBoolean("settings_open") ?: false
        settingsInitialRoute = if (savedInstanceState?.getBoolean("settings_new_set") == true) SettingsRoute.SetEdit(null, true) else null
        if (savedInstanceState?.getString("settings_route") == "lists") settingsInitialRoute = SettingsRoute.Lists
        browserUrl = savedInstanceState?.getString("browser_url")
        inSplitState = isInMultiWindowMode
        onBackPressedDispatcher.addCallback(this) { DiagLog.d("Panel", "back ignored") }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppGraph.runner.opening.collect { if (it == null) consumePendingUrl() }
            }
        }
        setContent {
            LauncherTheme {
                if (safetyMode) {
                    SafetyRecoveryScreen(
                        onRecover = { safetyRecover(this); goHome() },
                        onStock = { runCatching { startActivity(stockLauncherIntent()) }; finish() },
                    )
                    return@LauncherTheme
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .twoFingerSwipe { action ->
                            when (action) {
                                EdgeAction.Next -> AppGraph.runner.next()
                                EdgeAction.Prev -> AppGraph.runner.prev()
                                else -> Unit
                            }
                        },
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.wallpaper),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val url = browserUrl
                    if (url != null) {
                        BrowserPane(url, onClose = { browserUrl = null }, modifier = Modifier.fillMaxSize())
                    } else if (settingsOpen) {
                        SettingsHost(SettingsActions.forApp(this@PanelActivity),
                            onExit = { settingsOpen = false }, onSafetyCleared = ::goHome,
                            initialRoute = settingsInitialRoute,
                            onOpenSet = { settingsOpen = false; AppGraph.runner.open(it) })
                    } else PanelScreen(
                        inSplit = inSplitState,
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
                        onLaunchComponent = { component ->
                            val succeeded = launchComponent(component, isInMultiWindowMode)
                            AppGraph.warm.recordAppLaunch(component.packageName, succeeded)
                        },
                        onLaunchApp = { pkg ->
                            AppGraph.warm.recordAppLaunch(
                                pkg,
                                launchApp(pkg, isInMultiWindowMode),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppGraph.panelInSplit = isInMultiWindowMode
        AppGraph.panelSplitPausedAtMs = null
        inSplitState = isInMultiWindowMode
        if (safetyMode) return
        EdgeStripService.ensureRunning(this)
        if (isInMultiWindowMode) {
            AppGraph.runner.panelResumedInSplit()
            consumePendingUrl()
        }
        forwarder.onShown(isInMultiWindowMode, ::goHome)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (!isInMultiWindowMode) AppGraph.panelSplitPausedAtMs = null
        inSplitState = isInMultiWindowMode
        AppGraph.panelInSplit = isInMultiWindowMode
        if (safetyMode) return
        forwarder.onMultiWindowChanged(isInMultiWindowMode)
        if (isInMultiWindowMode) {
            AppGraph.runner.panelResumedInSplit()
            consumePendingUrl()
        }
    }

    override fun onPause() {
        if (AppGraph.panelInSplit) AppGraph.panelSplitPausedAtMs = android.os.SystemClock.elapsedRealtime()
        AppGraph.panelInSplit = false
        forwarder.onHidden()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun consumePendingUrl() {
        if (safetyMode) return
        if (AppGraph.runner.opening.value != null) return
        if (!isInMultiWindowMode) return
        val url = AppGraph.pendingUrl.take() ?: return
        browserUrl = url
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("settings_open", settingsOpen)
        outState.putBoolean("settings_new_set", (settingsInitialRoute as? SettingsRoute.SetEdit)?.fromSplitButton == true)
        if (settingsInitialRoute == SettingsRoute.Lists) outState.putString("settings_route", "lists")
        outState.putString("browser_url", browserUrl)
        super.onSaveInstanceState(outState)
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }
}
