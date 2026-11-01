package com.gt8d.launcher.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import com.gt8d.launcher.ui.layout.OneLineText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.OwnPackages
import com.gt8d.launcher.apps.AppCatalog
import com.gt8d.launcher.apps.FrequentApp
import com.gt8d.launcher.apps.dockAppsFor
import com.gt8d.launcher.apps.settingsComponent
import com.gt8d.launcher.config.DockMode
import com.gt8d.launcher.ui.panel.AppInfo
import com.gt8d.launcher.ui.panel.rememberPanelEnvironment
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
    frequent: List<FrequentApp>? = AppGraph.frequentApps.collectAsState().value,
    usageAvailable: Boolean = AppGraph.usageAvailable.collectAsState().value,
    settingsApp: AppInfo? = rememberListsSettingsApp(),
) {
    LaunchedEffect(Unit) { AppGraph.refreshFrequentApps() }
    val excluded = setOfNotNull(settingsApp?.pkg)
    SettingsAutoSaveNote()
    var picking by remember { mutableStateOf<String?>(null) }
    fun apps(cfg: LauncherConfig, section: String): List<String> = when (section) {
        "home" -> cfg.homeApps
        "panel" -> cfg.panelApps
        else -> cfg.dockApps.filterNot { it in excluded }
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
        launchablePackages().distinct().filterNot {
            it == ownPkg || it == OwnPackages.PANEL_HOST || (section == "dock" && it in excluded)
        }.sortedBy(labelFor).forEach { pkg ->
            SettingsRow(labelFor(pkg), "lists_${section}_pick_$pkg", modifier = Modifier.fillMaxWidth(),
                enabled = pkg !in current && (max == null || current.size < max), onClick = {
                if (section == "idle") onUpdate { it.copy(idleApp = pkg) }
                else change(section) { DailySettings.add(it, pkg, max) }
                picking = null
            })
        }
        return
    }
    listOf("panel" to "パネルのアイコン", "dock" to "ドックのアプリ").forEach { (key, title) ->
        val list = apps(config, key)
        val prefix = "lists_${key}_"
        val auto = key == "dock" && config.dockMode == DockMode.Auto
        SettingsSection(title) {
            if (key == "dock") {
                SettingsSegments(listOf("自動(よく使う)" to "lists_dock_mode_auto",
                    "自分で選ぶ" to "lists_dock_mode_manual"),
                    if (auto) 0 else 1, { selected ->
                        onUpdate { it.copy(dockMode = if (selected == 0) DockMode.Auto else DockMode.Manual) }
                    })
            }
            if (auto) {
                if (!usageAvailable) Text("使用状況へのアクセスを許可すると、よく使うアプリが並びます",
                    color = Palette.Muted, fontSize = Dims.BodyText)
                val installed = launchablePackages().toSet()
                dockAppsFor(config.dockMode, config.dockApps, frequent, installed, excluded)
                    .forEachIndexed { index, pkg ->
                        Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(16.dp)
                            .testTag("lists_dock_auto_row_$pkg"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OneLineText("${index + 1}. ${labelFor(pkg)}", Modifier.weight(1f), fontSize = Dims.BodyText)
                            val count = frequent?.firstOrNull { it.pkg == pkg }?.count
                            OneLineText(count?.let { "7日で $it 回" } ?: "記録なし",
                                color = Palette.Muted, fontSize = Dims.BodyText)
                        }
                    }
            } else list.forEachIndexed { index, pkg ->
                Column(Modifier.fillMaxWidth().background(Palette.Panel).padding(16.dp).testTag("${prefix}row_$pkg")) {
                    OneLineText(labelFor(pkg), fontSize = Dims.BodyText)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DailyButton("▲", "${prefix}up_$pkg", enabled = index > 0) { change(key) { DailySettings.moveUp(it, pkg) } }
                        DailyButton("▼", "${prefix}down_$pkg", enabled = index < list.lastIndex) { change(key) { DailySettings.moveDown(it, pkg) } }
                        DailyButton("削除", "${prefix}remove_$pkg", kind = SettingsButtonKind.Danger) { change(key) { DailySettings.remove(it, pkg) } }
                    }
                }
            }
        }
        if (!auto) {
            val max = limit(key)
            val full = max != null && list.size >= max
            DailyButton("追加", "${prefix}add", Modifier.fillMaxWidth(), enabled = !full) { picking = key }
            if (full) Text("最大 $max 個まで", fontSize = Dims.BodyText)
        }
        if (key == "dock" && settingsApp != null) {
            Row(Modifier.fillMaxWidth().padding(16.dp).testTag("lists_dock_settings_row")) {
                OneLineText("設定", Modifier.weight(1f), fontSize = Dims.BodyText)
                OneLineText("固定", color = Palette.Muted, fontSize = Dims.BodyText)
            }
        }
    }
    Text("停止中に表示するアプリ", Modifier.padding(vertical = 8.dp), fontSize = Dims.BodyText)
    OneLineText(labelFor(config.idleApp), Modifier.testTag("lists_idle_row_${config.idleApp}"), fontSize = Dims.BodyText)
    DailyButton("変更", "lists_idle_change", Modifier.fillMaxWidth()) { picking = "idle" }
}

@Composable
private fun rememberListsSettingsApp(): AppInfo? {
    val context = LocalContext.current
    val catalog = remember(context) { AppCatalog(context) }
    val environment = rememberPanelEnvironment(catalog)
    return remember(context, environment.revision) {
        settingsComponent(context.packageManager)?.let {
            AppInfo(it.packageName, "設定", catalog.icon(it.packageName), true)
        }
    }
}

@Composable
internal fun DailyButton(
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: SettingsButtonKind = SettingsButtonKind.Secondary,
    onClick: () -> Unit,
) {
    SettingsButton(title, tag, modifier, enabled = enabled, kind = kind, onClick = onClick)
}
