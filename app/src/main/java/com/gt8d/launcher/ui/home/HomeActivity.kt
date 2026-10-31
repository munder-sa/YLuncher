package com.gt8d.launcher.ui.home

import android.os.Bundle
import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.stockLauncherIntent
import com.gt8d.launcher.boot.BootPhase
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.edge.EdgeAction
import com.gt8d.launcher.edge.EdgeStripService
import com.gt8d.launcher.safety.SafetyGuard
import com.gt8d.launcher.settings.SettingsActivity
import com.gt8d.launcher.settings.SettingsButton
import com.gt8d.launcher.settings.safetyRecover
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.ui.gesture.twoFingerSwipe
import com.gt8d.launcher.ui.panel.PanelScreen
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.LauncherTheme
import com.gt8d.launcher.ui.layout.launcherBackground
import com.gt8d.launcher.ui.layout.stripSafePadding
import com.gt8d.launcher.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeActivity : ComponentActivity() {
    companion object {
        const val BOOT_WINDOW_MS = 180_000L
    }

    private val activityLauncher by lazy { AndroidActivityLauncher(this) }
    private var bootConfigJob: Job? = null
    private var createdInSafety = false
    internal var onBootDecisionForTest: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdInSafety = SafetyGuard.isFallbackActive(this)
        if (createdInSafety) {
            setContent {
                LauncherTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Palette.Background0),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "安全のため純正ランチャーを使っています",
                                fontSize = Dims.BodyText,
                                color = Color.White,
                            )
                            // SettingsButton の上下各4dpの余白を含めて高さと間隔を調整する。
                            SettingsButton("純正ランチャーを開く", "safety_open_stock",
                                Modifier.width(360.dp).height(80.dp)) {
                                runCatching { startActivity(stockLauncherIntent()) }
                            }
                            SettingsButton("安全モードを解除", "safety_unlock",
                                Modifier.width(360.dp).height(80.dp)) {
                                safetyRecover(this@HomeActivity)
                                recreate()
                            }
                        }
                    }
                }
            }
            return
        }
        setContent {
            LauncherTheme {
                val latestConfig by AppGraph.configForHome.collectAsState()
                val config = latestConfig ?: remember { Defaults.build(AppGraph.catalog.installed()) }
                val bootPhase by AppGraph.boot.phase.collectAsState()
                val secondsLeft by AppGraph.boot.secondsLeft.collectAsState()

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
                    HomeContent { homeWide ->
                        Box(Modifier.fillMaxSize()) {
                            PanelScreen(
                                inSplit = false,
                                homeWide = homeWide,
                                homeConfig = config,
                                onNewSet = {
                                    startActivity(Intent(this@HomeActivity, SettingsActivity::class.java).putExtra("open_new_set", true))
                                },
                                onEditLists = {
                                    if (AppGraph.runner.opening.value != null) {
                                        DiagLog.d("Home", "settings ignored while opening")
                                    } else startActivity(Intent(this@HomeActivity, SettingsActivity::class.java).putExtra("open_route", "lists"))
                                },
                                onOpenSettings = {
                                    if (AppGraph.runner.opening.value != null) {
                                        DiagLog.d("Home", "settings ignored while opening")
                                    } else startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
                                },
                                onOpenPermissionSettings = {
                                    if (AppGraph.runner.opening.value != null) {
                                        DiagLog.d("Home", "settings ignored while opening")
                                    } else startActivity(Intent(this@HomeActivity, SettingsActivity::class.java).putExtra("open_route", "permissions"))
                                },
                                onLaunchComponent = { component ->
                                    val succeeded = activityLauncher.startComponent(component, adjacent = false)
                                    AppGraph.warm.recordAppLaunch(component.packageName, succeeded)
                                },
                                onLaunchApp = { pkg ->
                                    val succeeded = activityLauncher.startApp(pkg, adjacent = false)
                                    AppGraph.warm.recordAppLaunch(pkg, succeeded)
                                },
                            )
                        }
                    }

                    if (bootPhase == BootPhase.Countdown || bootPhase == BootPhase.Opening) {
                        BootScreen(
                            target = AppGraph.runner.targetFor(AppGraph.config.lastSetIndex),
                            secondsLeft = secondsLeft,
                            opening = bootPhase == BootPhase.Opening,
                            onOpenNow = AppGraph.boot::openNow,
                            onStayHome = AppGraph.boot::stayHome,
                            modifier = Modifier.align(Alignment.Center),
                            iconFor = AppGraph.catalog::icon,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (createdInSafety && !SafetyGuard.isFallbackActive(this)) {
            recreate()
            return
        }
        AppGraph.runner.ownScreenResumed()
        AppGraph.homeResumed = true
        AppGraph.homePausedAtMs = null
        if (SafetyGuard.isFallbackActive(this)) {
            return
        }
        EdgeStripService.ensureRunning(this)
        awaitConfigAndHandleBoot()
    }

    private fun awaitConfigAndHandleBoot() {
        if (AppGraph.bootHandled || bootConfigJob?.isActive == true) return
        bootConfigJob = lifecycleScope.launch {
            try {
                AppGraph.configForHome.filterNotNull().first()
                lifecycle.withResumed {
                    if (SafetyGuard.isFallbackActive(this@HomeActivity)) return@withResumed
                    if (AppGraph.bootHandled) return@withResumed
                    onBootDecisionForTest()
                    AppGraph.bootHandled = true
                    if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && AppGraph.config.lastSetIndex != 0) {
                        AppGraph.boot.start()
                    }
                }
            } finally {
                bootConfigJob = null
            }
        }
    }

    override fun onPause() {
        bootConfigJob?.cancel()
        bootConfigJob = null
        AppGraph.homePausedAtMs = SystemClock.elapsedRealtime()
        AppGraph.homeResumed = false
        super.onPause()
    }
}
