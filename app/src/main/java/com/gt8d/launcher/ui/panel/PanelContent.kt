package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.MAX_PANEL_APPS
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.layout.FitClock
import com.gt8d.launcher.ui.layout.OneLineText
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PanelState(
    val now: NowPlaying?,
    val idleApp: AppInfo?,
    val positionMs: Long,
    val panelApps: List<AppInfo>,
    val dockApps: List<AppInfo>,
    val dockStatus: DockStatus,
    val showCar: Boolean,
    val needsPermission: Boolean,
)

data class PanelActions(
    val onMediaAction: (MediaAction) -> Unit,
    val onSeek: (Long) -> Unit,
    val onLaunch: (String) -> Unit,
    val onAdd: () -> Unit,
    val onHome: () -> Unit,
    val onSplit: () -> Unit,
    val onCar: () -> Unit,
    val onAppList: () -> Unit,
    val onAppListLongPress: () -> Unit,
    val onOpenPermissions: () -> Unit,
)

@Composable
fun PanelContent(
    state: PanelState,
    actions: PanelActions,
) {
    var clockText by remember { mutableStateOf(LocalTime.now().format(ClockFormatter)) }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = LocalTime.now().format(ClockFormatter)
            delay(10_000L)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val clockHeight = maxHeight * 0.3f
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FitClock(clockText, clockHeight)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.needsPermission) {
                        Surface(
                            color = Palette.Panel,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                                .clickable(onClick = actions.onOpenPermissions)
                                .testTag("permission_banner"),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                                    .padding(horizontal = 12.dp),
                            ) {
                                OneLineText(
                                    text = "許可が必要",
                                    color = Palette.Accent,
                                )
                            }
                        }
                    }

                    MediaCard(
                        now = state.now,
                        idleApp = state.idleApp,
                        positionMs = state.positionMs,
                        onAction = actions.onMediaAction,
                        onSeek = actions.onSeek,
                        onLaunch = actions.onLaunch,
                    )

                    IconGrid(
                        apps = state.panelApps.take(MAX_PANEL_APPS),
                        onLaunch = actions.onLaunch,
                        onAdd = actions.onAdd,
                    )
                }
            }
        }

        Dock(
            status = state.dockStatus,
            dockApps = state.dockApps,
            showCar = state.showCar,
            onHome = actions.onHome,
            onSplit = actions.onSplit,
            onCar = actions.onCar,
            onAppList = actions.onAppList,
            onAppListLongPress = actions.onAppListLongPress,
            onLaunch = actions.onLaunch,
        )
    }
}

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
