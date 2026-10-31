package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.*
import com.gt8d.launcher.ui.home.PanelIcon
import com.gt8d.launcher.ui.home.setLabel
import com.gt8d.launcher.ui.layout.OneLineKey
import com.gt8d.launcher.ui.layout.OneLineText
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

internal val PanelSetFallbackBackground = SemanticsPropertyKey<Color>("PanelSetFallbackBackground")

@Composable
fun PanelSetCards(
    sets: List<ScreenSet>, appInfo: (String) -> AppInfo, currentIndex: Int,
    onOpen: (Int) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier,
    beforeCards: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(modifier) {
    val cardHeight = maxHeight.coerceAtMost(104.dp).coerceAtLeast(72.dp)
    Column(Modifier.fillMaxSize().testTag("panel_sets").verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        beforeCards()
        sets.forEachIndexed { index, set ->
            val highlight = currentIndex == index + 1
            Row(Modifier.fillMaxWidth().height(cardHeight).clip(shape).background(Palette.Background1)
                .then(if (highlight) Modifier.border(3.dp, Color.White, shape) else Modifier)
                .clickable { onOpen(index + 1) }.semantics { selected = highlight }
                .testTag("panel_set_${set.id}").padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                for (side in listOf(Side.Left, Side.Right)) {
                    if (side == Side.Right) Spacer(Modifier.width(4.dp))
                    val app = (set.slotAt(side) as? Slot.App)?.let { appInfo(it.pkg) }
                    if (app == null) PanelIcon(Modifier.size(48.dp), mirrored = side == Side.Left)
                    else Box(Modifier.size(48.dp).semantics {
                        contentDescription = app.label + if (app.installed) "" else "(アプリが見つかりません)"
                    }, contentAlignment = Alignment.Center) {
                        if (app.installed && app.icon != null) Image(app.icon, null, Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                            .background(if (app.installed) Palette.Panel else Palette.Control)
                            .semantics { this[PanelSetFallbackBackground] = if (app.installed) Palette.Panel else Palette.Control },
                            contentAlignment = Alignment.Center) {
                            Text(app.label.firstOrNull()?.toString() ?: "?",
                                color = if (app.installed) Color.White else Palette.Muted, fontSize = Dims.BodyText)
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = Palette.Muted)) { append("${index + 1}  ") }
                    append(setLabel(set) { appInfo(it).label })
                }, Modifier.weight(1f).semantics { this[OneLineKey] = true },
                    color = Color.White, fontSize = Dims.BodyText,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(72.dp).clip(shape).border(1.dp, Palette.Control, shape)
            .clickable(role = Role.Button, onClick = onAdd)
            .semantics { contentDescription = "セットを追加" }.testTag("panel_set_add"),
            contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(24.dp), tint = Palette.Muted)
                OneLineText("セットを追加", color = Palette.Muted, fontSize = Dims.BodyText)
            }
        }
    }
    }
}
