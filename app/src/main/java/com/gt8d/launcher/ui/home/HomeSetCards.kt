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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import com.gt8d.launcher.ui.layout.OneLineText
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
        val rows = (sets.size + 1 + columns - 1) / columns
        val cardHeight = ((areaHeight - 24.dp * (rows - 1)) / rows).coerceIn(136.dp, 160.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().heightIn(min = areaHeight),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                for (row in (0..sets.size).toList().chunked(columns)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        for (index in row) {
                            val shape = RoundedCornerShape(28.dp)
                            if (index == sets.size) {
                                Box(Modifier.weight(1f).height(cardHeight).clip(shape).drawBehind {
                                    val stroke = 2.dp.toPx()
                                    drawRoundRect(Palette.PanelAccent,
                                        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                                        cornerRadius = CornerRadius(28.dp.toPx()),
                                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
                                }.clickable(onClick = onAdd).testTag("home_set_add"), contentAlignment = Alignment.Center) {
                                    OneLineText("＋ セットを追加", color = Palette.PanelAccent)
                                }
                            } else {
                                val set = sets[index]
                                val left = appInfo(set.left.pkg)
                                val right = (set.right as? Slot.App)?.let { appInfo(it.pkg) }
                                val highlight = currentIndex == index + 1
                                Column(Modifier.weight(1f).height(cardHeight).clip(shape).background(Palette.Background1)
                                    .then(if (highlight) Modifier.border(4.dp, Palette.PanelAccent, shape) else Modifier)
                                    .clickable { onOpen(index + 1) }
                                    .semantics { selected = highlight; this[HomeSetHighlight] = highlight }
                                    .testTag("home_set_${set.id}").padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val ratio = set.ratio.label.split(':').map { it.toFloat() }
                                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        SetBlock(left, Modifier.weight(ratio[0]).fillMaxHeight())
                                        SetBlock(right, Modifier.weight(ratio[1]).fillMaxHeight())
                                    }
                                    OneLineText("${left.label} | ${right?.label ?: "パネル"}")
                                }
                            }
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetBlock(app: AppInfo?, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(Palette.Background0)
        .then(if (app == null) Modifier.border(2.dp, Palette.PanelAccent, shape) else Modifier),
        contentAlignment = Alignment.Center) {
        if (app == null) {
            OneLineText("パネル", color = Palette.PanelAccent)
        } else {
            val description = app.label + if (app.installed) "" else "(アプリが見つかりません)"
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
