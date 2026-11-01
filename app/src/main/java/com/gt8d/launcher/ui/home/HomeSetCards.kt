package com.gt8d.launcher.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Side
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.config.slotAt
import com.gt8d.launcher.ui.layout.OneLineText
import com.gt8d.launcher.ui.layout.OneLineKey
import com.gt8d.launcher.ui.layout.PanelLayout
import com.gt8d.launcher.ui.panel.AppInfo
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

val HomeSetHighlight = SemanticsPropertyKey<Boolean>("HomeSetHighlight")
internal val HomeSetFallbackBackground = SemanticsPropertyKey<Color>("HomeSetFallbackBackground")

@Composable
fun HomeSetCards(
    sets: List<ScreenSet>,
    appInfo: (String) -> AppInfo,
    currentIndex: Int,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.testTag("home_sets")) {
        val columns = PanelLayout.homeSetColumns(maxWidth.value)
        val addInGrid = sets.size % columns != 0
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
                for (row in sets.indices.toList().chunked(columns)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        for (index in row) {
                            val shape = RoundedCornerShape(24.dp)
                            run {
                                val set = sets[index]
                                val left = (set.slotAt(Side.Left) as? Slot.App)?.let { appInfo(it.pkg) }
                                val right = (set.slotAt(Side.Right) as? Slot.App)?.let { appInfo(it.pkg) }
                                val highlight = currentIndex == index + 1
                                Row(Modifier.weight(1f).height(104.dp).clip(shape).background(Palette.Background1, shape)
                                    .then(if (highlight) Modifier.border(3.dp, Color.White, shape) else Modifier)
                                    .clickable { onOpen(index + 1) }
                                    .semantics {
                                        selected = highlight; this[HomeSetHighlight] = highlight
                                        contentDescription = numberedSetLabel(index + 1, set) { appInfo(it).label }
                                    }
                                    .testTag("home_set_${set.id}").padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                        Text("${index + 1}", color = Palette.Muted, fontSize = Dims.BodyText)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    SetBlock(left, Modifier.size(48.dp).testTag("home_set_${set.id}_left"), Side.Left)
                                    Spacer(Modifier.width(4.dp))
                                    SetBlock(right, Modifier.size(48.dp).testTag("home_set_${set.id}_right"), Side.Right)
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        for (app in listOf(left, right)) {
                                            Text(if (app == null) "パネル" else if (app.installed) app.label else "見つかりません",
                                                Modifier.fillMaxWidth().semantics { this[OneLineKey] = true },
                                                color = if (app?.installed == false) Palette.Muted else Color.White,
                                                fontSize = Dims.BodyText, maxLines = 1, softWrap = false,
                                                overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        if (row.size < columns) {
                            AddSetCard(onAdd, Modifier.weight(1f).height(104.dp))
                        }
                    }
                }
                if (!addInGrid) {
                    AddSetCard(onAdd, Modifier.fillMaxWidth().height(72.dp))
                }
        }
    }
}

@Composable
private fun AddSetCard(onAdd: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.clip(shape).border(1.dp, Palette.Control, shape)
        .clickable(role = Role.Button, onClick = onAdd)
        .semantics { contentDescription = "セットを追加" }
        .testTag("home_set_add"), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp), tint = Palette.Muted)
            OneLineText("セットを追加", color = Palette.Muted, fontSize = Dims.BodyText)
        }
    }
}

@Composable
private fun SetBlock(app: AppInfo?, modifier: Modifier, side: Side) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier,
        contentAlignment = Alignment.Center) {
        if (app == null) {
            PanelIcon(Modifier.fillMaxSize(), mirrored = (side == Side.Left))
        } else {
            val description = app.label + if (app.installed) "" else "(アプリが見つかりません)"
                Box(Modifier.fillMaxSize().semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                    if (app.installed && app.icon != null) Image(app.icon, null, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().clip(shape).background(if (app.installed) Palette.Panel else Palette.Control)
                        .semantics { this[HomeSetFallbackBackground] = if (app.installed) Palette.Panel else Palette.Control },
                        contentAlignment = Alignment.Center) {
                        Text(app.label.firstOrNull()?.toString() ?: "?", color = if (app.installed) Color.White else Palette.Muted, fontSize = Dims.BodyText)
                    }
                }
        }
    }
}
