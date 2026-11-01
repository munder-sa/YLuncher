package com.gt8d.launcher.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@Composable
fun AppListScreen(
    entries: List<AppListEntry>,
    iconFor: (String) -> ImageBitmap?,
    onLaunch: (AppListEntry) -> Unit,
    onClose: () -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    iconRevision: Int = 0,
    onOpenSettings: (() -> Unit)? = null,
    registered: Set<String>? = null,
    onToggle: (String) -> Unit = {},
) {
    var all by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val registeredState = rememberLazyGridState()
    val selectedPkgs = registered ?: entries.map { it.pkg }.toSet()
    val visibleEntries = if (all || editing) entries else registeredEntries(entries, selectedPkgs)
    BackHandler {
        when {
            editing -> editing = false
            all -> all = false
            else -> onClose()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background0)
            .padding(8.dp),
    ) {
        BoxWithConstraints {
            val compact = maxWidth + 16.dp < 600.dp
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 72.dp).testTag("applist_header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (all && !editing) {
                    Box(
                        Modifier.size(72.dp).clickable { all = false }.testTag("applist_back"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = Color.White)
                    }
                }
                Text(
                    text = if (editing) "すべてのアプリ — 押して登録・外す" else if (all) "すべてのアプリ" else "アプリ一覧",
                    fontSize = Dims.BodyText,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (editing) {
                    Box(
                        Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)
                            .clip(RoundedCornerShape(12.dp)).background(Color.White)
                            .clickable { editing = false }.padding(horizontal = 24.dp).testTag("applist_done"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("完了", fontSize = Dims.BodyText, color = Palette.Background0)
                    }
                } else {
                    Row(
                        Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)
                            .border(1.dp, Palette.Control, RoundedCornerShape(12.dp))
                            .clickable { editing = true }.padding(horizontal = 16.dp).testTag("applist_edit"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.Edit, "編集", Modifier.size(32.dp), tint = Color.White)
                        if (!compact) Spacer(Modifier.width(8.dp))
                        if (!compact) Text("編集", fontSize = Dims.BodyText, color = Color.White)
                    }
                    if (onOpenSettings != null) {
                        Row(
                            Modifier.sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                                .clickable(onClick = onOpenSettings).padding(horizontal = 16.dp).testTag("applist_settings"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Default.Settings, "設定", Modifier.size(32.dp), tint = Color.White)
                            if (!compact) Spacer(Modifier.width(8.dp))
                            if (!compact) Text("設定", fontSize = Dims.BodyText, color = Color.White)
                        }
                    }
                    Box(
                        Modifier.sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                            .clickable(onClick = onClose).testTag("applist_close"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, "閉じる", Modifier.size(32.dp), tint = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (!all && !editing && visibleEntries.isEmpty()) {
            Text("登録したアプリはありません。「編集」で登録できます", color = Palette.Muted, fontSize = Dims.BodyText)
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            state = if (all || editing) gridState else registeredState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = visibleEntries,
                key = { "${it.pkg}/${it.cls}" },
            ) { entry ->
                val icon = remember(entry.pkg, iconRevision) { iconFor(entry.pkg) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                        .clickable { if (editing) onToggle(entry.pkg) else onLaunch(entry) }
                        .padding(8.dp)
                        .testTag("applist_${entry.pkg}"),
                ) {
                    val dim = if (editing && entry.pkg !in selectedPkgs) 0.5f else 1f
                    Box(Modifier.size(56.dp)) {
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = entry.label,
                                modifier = Modifier.size(56.dp).alpha(dim).testTag("applist_icon_${entry.pkg}"),
                            )
                        } else {
                            val initial = entry.label.firstOrNull()?.toString() ?: "?"
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(56.dp).alpha(dim)
                                    .testTag("applist_icon_${entry.pkg}")
                                    .clip(CircleShape).background(Palette.Panel),
                            ) {
                                Text(
                                    text = initial,
                                    fontSize = Dims.BodyText,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        }
                        if (editing) {
                            val checked = entry.pkg in selectedPkgs
                            Box(
                                Modifier.align(Alignment.TopEnd).size(24.dp)
                                    .border(2.dp, Color.White, CircleShape)
                                    .background(if (checked) Color.White else Color.Transparent, CircleShape)
                                    .testTag("applist_check_${entry.pkg}")
                                    .semantics { selected = checked },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checked) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = Palette.Background0)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = entry.label,
                        fontSize = Dims.BodyText,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(dim),
                    )
                }
            }
            if (!all && !editing) {
                item(key = "all_apps") {
                    Column(Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)
                        .clickable { all = true }.padding(8.dp).testTag("applist_all"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).border(1.dp, Palette.Control, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            Text("⋯", color = Palette.Muted, fontSize = Dims.BodyText)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("すべてのアプリ", color = Palette.Muted, fontSize = Dims.BodyText,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
