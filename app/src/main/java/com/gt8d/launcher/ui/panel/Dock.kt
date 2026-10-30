package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.MAX_DOCK_APPS
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dock(
    status: DockStatus,
    dockApps: List<AppInfo>,
    showCar: Boolean,
    onHome: () -> Unit,
    onSplit: () -> Unit,
    onCar: () -> Unit,
    onAppList: () -> Unit,
    onAppListLongPress: () -> Unit,
    onLaunch: (String) -> Unit,
) {
    Surface(
        color = Palette.Background1,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(88.dp).testTag("dock"),
    ) {
        Column(
            modifier = Modifier
                .width(88.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.sizeIn(minWidth = Dims.MinTouch, minHeight = 36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = "Wi-Fi",
                    tint = if (status.wifiOn) Color.White else Color.White.copy(alpha = 0.35f),
                    modifier = Modifier
                        .size(20.dp)
                        .testTag("dock_wifi"),
                )
                if (status.bluetoothOn != null) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = "Bluetooth",
                        tint = if (status.bluetoothOn) Color.White else Color.White.copy(alpha = 0.35f),
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("dock_bt"),
                    )
                }
            }

            val visibleApps = dockApps.take(MAX_DOCK_APPS).filter { it.installed }
            for (app in visibleApps) {
                DockAppButton(
                    app = app,
                    onLaunch = onLaunch,
                )
            }

            DockIconButton(
                icon = Icons.Default.Home,
                contentDescription = "ホーム",
                tag = "dock_home",
                onClick = onHome,
            )

            DockIconButton(
                icon = Icons.Default.ViewColumn,
                contentDescription = "分割",
                tag = "dock_split",
                onClick = onSplit,
            )

            if (showCar) {
                DockIconButton(
                    icon = Icons.Default.DirectionsCar,
                    contentDescription = "車",
                    tag = "dock_car",
                    onClick = onCar,
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                    .combinedClickable(
                        onClick = onAppList,
                        onLongClick = onAppListLongPress,
                    )
                    .testTag("dock_applist"),
            ) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = "アプリ一覧",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun DockAppButton(
    app: AppInfo,
    onLaunch: (String) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
            .clickable { onLaunch(app.pkg) }
            .semantics { contentDescription = app.label }
            .testTag("dock_app_${app.pkg}"),
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        } else {
            val initial = app.label.firstOrNull()?.toString() ?: "?"
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Palette.Panel),
            ) {
                Text(
                    text = initial,
                    fontSize = Dims.BodyText,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun DockIconButton(
    icon: ImageVector,
    contentDescription: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
    }
}
