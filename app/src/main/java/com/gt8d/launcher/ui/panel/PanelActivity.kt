package com.gt8d.launcher.ui.panel

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.R
import com.gt8d.launcher.edge.EdgeAction
import com.gt8d.launcher.edge.EdgeStripService
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.ui.gesture.twoFingerSwipe
import com.gt8d.launcher.ui.home.HomeActivity
import com.gt8d.launcher.ui.theme.LauncherTheme

class PanelActivity : ComponentActivity() {
    private val forwarder by lazy { AppGraph.forwarderFactory() }
    private val activityLauncher by lazy { AndroidActivityLauncher(this) }
    private var inSplitState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inSplitState = isInMultiWindowMode
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
                        onLaunchApp = { pkg ->
                            activityLauncher.startApp(pkg, adjacent = isInMultiWindowMode)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inSplitState = isInMultiWindowMode
        EdgeStripService.ensureRunning(this)
        if (isInMultiWindowMode) {
            AppGraph.runner.panelResumedInSplit()
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
        forwarder.onMultiWindowChanged(isInMultiWindowMode)
        if (isInMultiWindowMode) {
            AppGraph.runner.panelResumedInSplit()
        }
    }

    override fun onPause() {
        forwarder.onHidden()
        super.onPause()
    }
}
