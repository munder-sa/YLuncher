package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
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
    val mediaVisible: Boolean = true,
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
    val onToggleMedia: () -> Unit = {},
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
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val minimumClock = measurer.measure(
                "00:00", TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"),
                softWrap = false, maxLines = 1, density = density,
            )
            val inlineToggle = minimumClock.size.width <= with(density) {
                (maxWidth - Dims.MinTouch - 8.dp).toPx()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inlineToggle) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FitClock(clockText, clockHeight, Modifier.weight(1f))
                        PanelMediaToggle(state, actions)
                    }
                } else {
                    FitClock(clockText, clockHeight)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PanelMediaToggle(state, actions)
                    }
                }
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
                                    color = Palette.PanelAccent,
                                )
                            }
                        }
                    }

                    if (state.mediaVisible) MediaCard(
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

@Composable
private fun PanelMediaToggle(state: PanelState, actions: PanelActions) {
    Box(Modifier.size(Dims.MinTouch)
        .toggleable(value = state.mediaVisible, role = Role.Switch, onValueChange = { actions.onToggleMedia() })
        .semantics { stateDescription = if (state.mediaVisible) "表示中" else "非表示" }
        .testTag("panel_media_toggle")) {
        Surface(
            modifier = Modifier.fillMaxSize(), shape = CircleShape,
            color = if (state.mediaVisible) Palette.PanelAccent else Palette.Panel,
            border = if (state.mediaVisible) null else BorderStroke(2.dp, Palette.Divider),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.QueueMusic, contentDescription = "ミニプレイヤー",
                    tint = if (state.mediaVisible) Palette.Background0 else Palette.PanelAccent,
                    modifier = Modifier.size(32.dp))
            }
        }
        if (!state.mediaVisible && state.now?.playing == true) {
            Surface(Modifier.align(Alignment.TopEnd).size(20.dp).testTag("panel_media_playing_badge"),
                shape = CircleShape, color = Palette.PanelAccent) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp), tint = Palette.Background0)
                }
            }
        }
    }
}

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
