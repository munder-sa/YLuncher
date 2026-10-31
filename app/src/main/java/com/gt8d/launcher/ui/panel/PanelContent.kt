package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.gt8d.launcher.split.PanelPlacement
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
import com.gt8d.launcher.config.PanelView
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.ui.home.HomeSetCards
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
    val view: PanelView = PanelView.Apps,
    val sets: List<ScreenSet> = emptyList(),
    val currentSetIndex: Int = 0,
    val setAppInfo: (String) -> AppInfo = { AppInfo(it, it, null, false) },
    val placement: PanelPlacement? = null,
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
    val onSelectView: (PanelView) -> Unit = {},
    val onOpenSet: (Int) -> Unit = {},
    val onNewSet: () -> Unit = {},
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
    CompositionLocalProvider(
        LocalLayoutDirection provides
            if (state.placement == PanelPlacement.Left) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val clockHeight = maxHeight * 0.3f
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FitClock(clockText, clockHeight)
                        PanelViewSegment(state, actions)
                        if (state.view == PanelView.Sets) {
                            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                                val cardsHeight = (maxHeight - if (state.needsPermission) 80.dp else 0.dp)
                                    .coerceAtLeast(136.dp)
                                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (state.needsPermission) PermissionBanner(actions.onOpenPermissions)
                                    HomeSetCards(state.sets, state.setAppInfo, state.currentSetIndex,
                                        actions.onOpenSet, actions.onNewSet,
                                        Modifier.fillMaxWidth().height(cardsHeight))
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (state.needsPermission) PermissionBanner(actions.onOpenPermissions)

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
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
    }
}

@Composable
private fun PanelViewSegment(state: PanelState, actions: PanelActions) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.fillMaxWidth().clip(shape).background(Palette.Background1)
        .selectableGroup().testTag("panel_view_segment").padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (view in PanelView.entries) {
            val selected = state.view == view
            val color = if (selected) Palette.Background0 else Color.White
            Row(Modifier.weight(1f).height(Dims.MinTouch).clip(shape)
                .then(if (selected) Modifier.background(Palette.PanelAccent) else Modifier)
                .selectable(selected = selected, role = Role.Tab, onClick = {
                    if (!selected) actions.onSelectView(view)
                }).testTag(if (view == PanelView.Apps) "panel_view_apps" else "panel_view_sets"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (view == PanelView.Apps) Icons.Default.Apps else SetIcon,
                    null, Modifier.size(28.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                OneLineText(if (view == PanelView.Apps) "アプリ" else "セット", color = color,
                    fontSize = Dims.BodyText, fontWeight = if (selected) FontWeight.Bold else null)
            }
        }
    }
}

@Composable
private fun PermissionBanner(onClick: () -> Unit) {
    Surface(color = Palette.Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier
        .fillMaxWidth().sizeIn(minHeight = Dims.MinTouch).clickable(onClick = onClick)
        .testTag("permission_banner")) {
        Box(Modifier.fillMaxWidth().sizeIn(minHeight = Dims.MinTouch).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) { OneLineText("許可が必要", color = Palette.PanelAccent) }
    }
}

private val SetIcon = ImageVector.Builder(name = "SetIcon", defaultWidth = 24.dp,
    defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(4f, 4f); lineTo(12f, 4f); quadTo(14f, 4f, 14f, 6f)
        lineTo(14f, 18f); quadTo(14f, 20f, 12f, 20f)
        lineTo(4f, 20f); quadTo(2f, 20f, 2f, 18f)
        lineTo(2f, 6f); quadTo(2f, 4f, 4f, 4f); close()
        moveTo(18f, 4f); lineTo(22f, 4f); quadTo(24f, 4f, 24f, 6f)
        lineTo(24f, 18f); quadTo(24f, 20f, 22f, 20f)
        lineTo(18f, 20f); quadTo(16f, 20f, 16f, 18f)
        lineTo(16f, 6f); quadTo(16f, 4f, 18f, 4f); close()
    }
}.build()

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
