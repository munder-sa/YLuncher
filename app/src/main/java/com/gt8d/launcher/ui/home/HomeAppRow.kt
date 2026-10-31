package com.gt8d.launcher.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
fun HomeAppRow(apps: List<AppInfo>, onLaunch: (String) -> Unit, modifier: Modifier = Modifier) {
    if (apps.isEmpty()) return
    BoxWithConstraints(modifier.testTag("home_apps")) {
        val count = apps.size
        val size = ((maxWidth - 8.dp * (count - 1)) / count).coerceIn(72.dp, 96.dp)
        val gap = if (count == 1) 0.dp else
            ((maxWidth - size * count) / (count - 1)).coerceIn(8.dp, 48.dp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (app in apps) {
                Box(Modifier.size(size)
                    .semantics { contentDescription = app.label + if (app.installed) "" else "(アプリが見つかりません)" }
                    .clickable { if (app.installed) onLaunch(app.pkg) }
                    .alpha(if (app.installed) 1f else 0.4f)
                    .testTag(if (app.installed) "home_app_${app.pkg}" else "home_missing_${app.pkg}"),
                    contentAlignment = Alignment.Center) {
                    if (app.icon != null) Image(app.icon, null, Modifier.fillMaxSize(),
                        colorFilter = if (app.installed) null else
                            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }))
                    else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Palette.Panel),
                        contentAlignment = Alignment.Center) {
                        Text(app.label.firstOrNull()?.toString() ?: "?", color = Color.White, fontSize = Dims.BodyText)
                    }
                }
            }
        }
    }
}
