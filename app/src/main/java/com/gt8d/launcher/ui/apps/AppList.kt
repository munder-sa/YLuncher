package com.gt8d.launcher.ui.apps

import com.gt8d.launcher.apps.OwnPackages
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.Slot
import java.text.Collator
import java.util.Locale

data class AppListEntry(val pkg: String, val cls: String, val label: String)

fun seedAppList(config: LauncherConfig, recent: Set<String>, launchable: Set<String>): List<String> =
    (config.panelApps + config.dockApps + config.screenSets.flatMap { set ->
        listOfNotNull((set.left as? Slot.App)?.pkg, (set.right as? Slot.App)?.pkg)
    } + config.homeApps + recent).filter { it in launchable }.distinct()

fun registeredEntries(all: List<AppListEntry>, registered: Set<String>): List<AppListEntry> =
    all.filter { it.pkg in registered }

fun toggleRegistered(list: List<String>, pkg: String): List<String> =
    if (pkg in list) list.filterNot { it == pkg } else list + pkg

val STOCK_LAUNCHER_ENTRY = AppListEntry(
    pkg = "com.android.launcher3",
    cls = "com.android.launcher3.uioverrides.QuickstepLauncher",
    label = "純正ランチャー",
)

fun buildAppList(all: List<AppListEntry>, ownPkg: String): List<AppListEntry> {
    val collator = Collator.getInstance(Locale.JAPANESE)
    val rest = all
        .filter { it.pkg != ownPkg && it.pkg != OwnPackages.PANEL_HOST && it.pkg != STOCK_LAUNCHER_ENTRY.pkg }
        .distinctBy { it.pkg to it.cls }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
    return listOf(STOCK_LAUNCHER_ENTRY) + rest
}
