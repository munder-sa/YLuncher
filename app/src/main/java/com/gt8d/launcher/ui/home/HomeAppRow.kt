package com.gt8d.launcher.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.panel.AppInfo
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@Composable
fun HomeAppRow(
    apps: List<AppInfo>,
    onLaunch: (String) -> Unit,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    if (apps.isEmpty() && onAdd == null) return
    LazyRow(modifier.height(96.dp).testTag("home_apps"), state = state,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        items(apps) { app ->
            Box(Modifier.size(96.dp)
                .semantics { contentDescription = if (app.installed) app.label else "${app.label}(アプリが見つかりません)" }
                .clickable { if (app.installed) onLaunch(app.pkg) }
                .alpha(if (app.installed) 1f else 0.4f)
                .testTag(if (app.installed) "home_app_${app.pkg}" else "home_missing_${app.pkg}"), contentAlignment = Alignment.Center) {
                val icon = app.icon
                if (icon != null) Image(icon, null, Modifier.fillMaxSize(),
                    colorFilter = if (app.installed) null else
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }))
                else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Palette.Panel),
                    contentAlignment = Alignment.Center) {
                    Text(app.label.firstOrNull()?.toString() ?: "?", color = Color.White, fontSize = Dims.BodyText)
                }
            }
        }
        if (onAdd != null) item {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Panel)
                .semantics { contentDescription = "パネルのアイコンを追加" }
                .clickable { onAdd() }.testTag("home_app_add"), contentAlignment = Alignment.Center) {
                Text("＋", color = Palette.PanelAccent, fontSize = Dims.BodyText)
            }
        }
    }
}
