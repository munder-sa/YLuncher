package com.gt8d.launcher.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gt8d.launcher.ui.layout.PanelLayout

@Composable
fun HomeContent(panel: @Composable (homeWide: Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The panel is always invoked from this same composition position.
        panel(maxWidth.value >= PanelLayout.HOME_SPLIT_MIN_DP)
    }
}
