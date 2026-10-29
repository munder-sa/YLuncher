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
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.link.BrowserActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inSplitState = isInMultiWindowMode
        onBackPressedDispatcher.addCallback(this) { DiagLog.d("Panel", "back ignored") }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppGraph.runner.opening.collect { if (it == null) consumePendingUrl() }
            }
        }
        setContent {
            LauncherTheme {
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
                    PanelScreen(
                        inSplit = inSplitState,
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
        inSplitState = isInMultiWindowMode
        EdgeStripService.ensureRunning(this)
        if (isInMultiWindowMode) {
            AppGraph.runner.panelResumedInSplit()
            consumePendingUrl()
        }
        forwarder.onShown(isInMultiWindowMode) {
            val intent = Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        inSplitState = isInMultiWindowMode
        AppGraph.panelInSplit = isInMultiWindowMode
        forwarder.onMultiWindowChanged(isInMultiWindowMode)
        if (isInMultiWindowMode) {
            AppGraph.runner.panelResumedInSplit()
            consumePendingUrl()
        }
    }

    override fun onPause() {
        AppGraph.panelInSplit = false
        forwarder.onHidden()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun consumePendingUrl() {
        if (AppGraph.runner.opening.value != null) return
        if (!isInMultiWindowMode) return
        val url = AppGraph.pendingUrl.take() ?: return
        startActivity(Intent(this, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
