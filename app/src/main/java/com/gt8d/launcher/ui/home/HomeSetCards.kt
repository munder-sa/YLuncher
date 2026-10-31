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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
        val areaHeight = maxHeight
        val rows = (sets.size + columns - 1) / columns
        val addInGrid = sets.size % columns != 0
        val cardHeight = if (rows == 0) null else
            ((areaHeight - (if (addInGrid) 0.dp else 96.dp) - 24.dp * (rows - 1)) / rows).coerceIn(136.dp, 160.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().heightIn(min = areaHeight),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                for (row in sets.indices.toList().chunked(columns)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        for (index in row) {
                            val shape = RoundedCornerShape(24.dp)
                            run {
                                val set = sets[index]
                                val left = (set.slotAt(Side.Left) as? Slot.App)?.let { appInfo(it.pkg) }
                                val right = (set.slotAt(Side.Right) as? Slot.App)?.let { appInfo(it.pkg) }
                                val highlight = currentIndex == index + 1
                                Column(Modifier.weight(1f).height(requireNotNull(cardHeight)).clip(shape).background(Palette.Background1)
                                    .then(if (highlight) Modifier.border(4.dp, Palette.PanelAccent, shape) else Modifier)
                                    .clickable { onOpen(index + 1) }
                                    .semantics { selected = highlight; this[HomeSetHighlight] = highlight }
                                    .testTag("home_set_${set.id}").padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val ratio = set.ratio.label.split(':').map { it.toFloat() }
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        SetBlock(left, Modifier.weight(ratio[0]).fillMaxHeight().testTag("home_set_${set.id}_left"), Side.Left)
                                        SetBlock(right, Modifier.weight(ratio[1]).fillMaxHeight().testTag("home_set_${set.id}_right"), Side.Right)
                                    }
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(buildAnnotatedString {
                                            withStyle(SpanStyle(color = Palette.Muted)) { append("${index + 1}  ") }
                                            append(setLabel(set) { appInfo(it).label })
                                        }, modifier = Modifier.semantics { this[OneLineKey] = true },
                                            color = Color.White, fontSize = Dims.BodyText,
                                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        if (row.size < columns) {
                            AddSetCard(onAdd, Modifier.weight(1f).height(requireNotNull(cardHeight)))
                        }
                    }
                }
                if (!addInGrid) {
                    AddSetCard(onAdd, Modifier.fillMaxWidth().height(72.dp))
                }
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
    Box(modifier.clip(shape).background(Palette.Background0),
        contentAlignment = Alignment.Center) {
        if (app == null) {
            PanelIcon(Modifier.size(56.dp), mirrored = (side == Side.Left))
        } else {
            val description = app.label + if (app.installed) "" else "(アプリが見つかりません)"
            if (!app.installed) {
                Text("見つかりません", modifier = Modifier.semantics { contentDescription = description },
                    fontSize = Dims.BodyText, color = Palette.Muted,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            } else {
                Box(Modifier.size(56.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                    if (app.installed && app.icon != null) Image(app.icon, null, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().clip(shape).background(if (app.installed) Palette.Panel else Palette.Control),
                        contentAlignment = Alignment.Center) {
                        Text(app.label.firstOrNull()?.toString() ?: "?", color = if (app.installed) Color.White else Palette.Muted, fontSize = Dims.BodyText)
                    }
                }
            }
        }
    }
}
