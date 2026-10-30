package com.gt8d.launcher.settings

import androidx.activity.compose.BackHandler
import com.gt8d.launcher.ui.layout.OneLineText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.MAX_DOCK_APPS
import com.gt8d.launcher.config.MAX_PANEL_APPS
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@Composable
fun ListsScreen(
    config: LauncherConfig,
    onUpdate: ((LauncherConfig) -> LauncherConfig) -> Unit,
    labelFor: (String) -> String = { AppGraph.catalog.label(it) },
    launchablePackages: () -> List<String> = { AppGraph.catalog.launchables().map { it.pkg } },
) {
    var picking by remember { mutableStateOf<String?>(null) }
    fun apps(cfg: LauncherConfig, section: String): List<String> = when (section) {
        "home" -> cfg.homeApps
        "panel" -> cfg.panelApps
        else -> cfg.dockApps
    }
    fun limit(section: String): Int? = when (section) {
        "panel" -> MAX_PANEL_APPS
        "dock" -> MAX_DOCK_APPS
        else -> null
    }
    fun change(section: String, transform: (List<String>) -> List<String>) {
        onUpdate { cfg ->
            val changed = transform(apps(cfg, section))
            when (section) {
                "home" -> cfg.copy(homeApps = changed)
                "panel" -> cfg.copy(panelApps = changed)
                else -> cfg.copy(dockApps = changed)
            }
        }
    }
    val section = picking
    if (section != null) {
        BackHandler { picking = null }
        DailyButton("アプリの選択を戻る", "lists_${section}_pick_back") { picking = null }
        val ownPkg = LocalContext.current.packageName
        val current = if (section == "idle") emptyList() else apps(config, section)
        val max = limit(section)
        launchablePackages().distinct().filterNot { it == ownPkg }.sortedBy(labelFor).forEach { pkg ->
            DailyButton(labelFor(pkg), "lists_${section}_pick_$pkg", Modifier.fillMaxWidth(),
                enabled = pkg !in current && (max == null || current.size < max)) {
                if (section == "idle") onUpdate { it.copy(idleApp = pkg) }
                else change(section) { DailySettings.add(it, pkg, max) }
                picking = null
            }
        }
        return
    }
    listOf("home" to "ホームのアイコン", "panel" to "パネルのアイコン", "dock" to "右端バーのよく使うアプリ").forEach { (key, title) ->
        OneLineText(title, fontSize = Dims.BodyText, modifier = Modifier.padding(vertical = 8.dp))
        val list = apps(config, key)
        val prefix = "lists_${key}_"
        list.forEachIndexed { index, pkg ->
            Column(Modifier.fillMaxWidth().testTag("${prefix}row_$pkg")) {
                OneLineText(labelFor(pkg), fontSize = Dims.BodyText)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DailyButton("▲", "${prefix}up_$pkg", enabled = index > 0) { change(key) { DailySettings.moveUp(it, pkg) } }
                    DailyButton("▼", "${prefix}down_$pkg", enabled = index < list.lastIndex) { change(key) { DailySettings.moveDown(it, pkg) } }
                    DailyButton("削除", "${prefix}remove_$pkg") { change(key) { DailySettings.remove(it, pkg) } }
                }
            }
        }
        val max = limit(key)
        val full = max != null && list.size >= max
        DailyButton("追加", "${prefix}add", Modifier.fillMaxWidth(), enabled = !full) { picking = key }
        if (full) Text("最大 $max 個まで", fontSize = Dims.BodyText)
    }
    Text("停止中に表示するアプリ", Modifier.padding(vertical = 8.dp), fontSize = Dims.BodyText)
    OneLineText(labelFor(config.idleApp), Modifier.testTag("lists_idle_row_${config.idleApp}"), fontSize = Dims.BodyText)
    DailyButton("変更", "lists_idle_change", Modifier.fillMaxWidth()) { picking = "idle" }
}

@Composable
internal fun DailyButton(
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    Button(onClick, modifier.padding(vertical = 4.dp)
        .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
        .testTag(tag).then(if (selected == null) Modifier else Modifier.semantics { this.selected = selected }),
        enabled = enabled, colors = ButtonDefaults.buttonColors(
            containerColor = if (selected == false) Palette.Panel else Palette.Accent,
            contentColor = if (selected == false) MaterialTheme.colorScheme.onSurface else Palette.Background0,
        )) { OneLineText(title, fontSize = Dims.BodyText, color = LocalContentColor.current) }
}
