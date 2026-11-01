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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@Composable
fun HomeAppRow(
    entries: List<AppListEntry>,
    iconFor: (String) -> ImageBitmap?,
    onLaunch: (AppListEntry) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    if (entries.isEmpty()) return
    LazyRow(modifier.height(96.dp).testTag("home_apps"), state = state,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        items(entries) { entry ->
            Box(Modifier.size(96.dp)
                .semantics { contentDescription = entry.label }
                .clickable { onLaunch(entry) }
                .testTag("home_app_${entry.pkg}"), contentAlignment = Alignment.Center) {
                val icon = iconFor(entry.pkg)
                if (icon != null) Image(icon, null, Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Palette.Panel),
                    contentAlignment = Alignment.Center) {
                    Text(entry.label.firstOrNull()?.toString() ?: "?", color = Color.White, fontSize = Dims.BodyText)
                }
            }
        }
    }
}
