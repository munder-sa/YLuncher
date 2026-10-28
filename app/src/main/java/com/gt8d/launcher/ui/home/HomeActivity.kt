package com.gt8d.launcher.ui.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.R
import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.edge.EdgeAction
import com.gt8d.launcher.edge.EdgeStripService
import com.gt8d.launcher.split.AndroidActivityLauncher
import com.gt8d.launcher.ui.gesture.twoFingerSwipe
import com.gt8d.launcher.ui.panel.IconGrid
import com.gt8d.launcher.ui.panel.PanelScreen
import com.gt8d.launcher.ui.theme.LauncherTheme

class HomeActivity : ComponentActivity() {
    private val activityLauncher by lazy { AndroidActivityLauncher(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme {
                val latestConfig by AppGraph.latestConfig.collectAsState()
                val config = latestConfig ?: remember { Defaults.build(AppGraph.catalog.installed()) }
                val installedPkgs = remember(config) { AppGraph.catalog.installed() }
                val homeApps = config.homeApps.map { AppGraph.catalog.info(it, installedPkgs) }

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
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            IconGrid(
                                apps = homeApps,
                                columns = 4,
                                onLaunch = { pkg ->
                                    activityLauncher.startApp(pkg, adjacent = false)
                                },
                                onAdd = null,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight(),
                        ) {
                            PanelScreen(
                                inSplit = false,
                                onLaunchApp = { pkg ->
                                    activityLauncher.startApp(pkg, adjacent = false)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        EdgeStripService.ensureRunning(this)
    }
}
