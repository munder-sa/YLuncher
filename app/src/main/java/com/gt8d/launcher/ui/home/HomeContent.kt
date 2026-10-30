package com.gt8d.launcher.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.layout.PanelLayout
import com.gt8d.launcher.ui.panel.AppInfo
import com.gt8d.launcher.ui.panel.IconGrid

@Composable
fun HomeContent(
    homeApps: List<AppInfo>,
    onLaunchHome: (String) -> Unit,
    panel: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = PanelLayout.homePanelWidthDp(maxWidth.value)
        Row(Modifier.fillMaxSize()) {
            if (panelWidth != null) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(16.dp)
                        .verticalScroll(rememberScrollState()).testTag("home_grid"),
                ) {
                    IconGrid(homeApps, onLaunch = onLaunchHome, onAdd = null, modifier = Modifier)
                }
            }
            // The panel is always invoked from this same composition position.
            panel(if (panelWidth == null) Modifier.fillMaxSize() else Modifier.width(panelWidth.dp).fillMaxHeight())
        }
    }
}
