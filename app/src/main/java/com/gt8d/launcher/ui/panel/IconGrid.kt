package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

private sealed interface GridCell {
    data class App(val info: AppInfo) : GridCell
    data class Add(val onClick: () -> Unit) : GridCell
}

@Composable
fun IconGrid(
    apps: List<AppInfo>,
    columns: Int,
    onLaunch: (String) -> Unit,
    onAdd: (() -> Unit)?,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val cells = buildList {
        apps.forEach { add(GridCell.App(it)) }
        if (onAdd != null) {
            add(GridCell.Add(onAdd))
        }
    }

    val rows = cells.chunked(safeColumns)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (cell in row) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (cell) {
                            is GridCell.App -> AppCell(
                                app = cell.info,
                                onLaunch = onLaunch,
                            )
                            is GridCell.Add -> AddCell(onAdd = cell.onClick)
                        }
                    }
                }
                val emptyCount = safeColumns - row.size
                repeat(emptyCount) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppCell(
    app: AppInfo,
    onLaunch: (String) -> Unit,
) {
    val tag = if (app.installed) "icon_${app.pkg}" else "missing_${app.pkg}"
    val labelText = if (app.installed) app.label else "アプリが見つかりません"
    val alpha = if (app.installed) 1f else 0.4f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
            .clickable {
                if (app.installed) {
                    onLaunch(app.pkg)
                }
            }
            .padding(8.dp)
            .alpha(alpha)
            .testTag(tag),
    ) {
        if (app.icon != null) {
            val colorFilter = if (app.installed) {
                null
            } else {
                ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            }
            Image(
                bitmap = app.icon,
                contentDescription = labelText,
                colorFilter = colorFilter,
                modifier = Modifier.size(56.dp),
            )
        } else {
            val initial = app.label.firstOrNull()?.toString() ?: "?"
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = labelText,
            fontSize = Dims.BodyText,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddCell(
    onAdd: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
            .clickable { onAdd() }
            .padding(8.dp)
            .testTag("icon_add"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Palette.Panel),
        ) {
            Text(
                text = "＋",
                fontSize = Dims.BodyText,
                fontWeight = FontWeight.Bold,
                color = Palette.Accent,
            )
        }
    }
}
