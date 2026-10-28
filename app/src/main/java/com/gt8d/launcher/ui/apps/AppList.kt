package com.gt8d.launcher.ui.apps

import java.text.Collator
import java.util.Locale

data class AppListEntry(val pkg: String, val cls: String, val label: String)

val STOCK_LAUNCHER_ENTRY = AppListEntry(
    pkg = "com.android.launcher3",
    cls = "com.android.launcher3.uioverrides.QuickstepLauncher",
    label = "純正ランチャー",
)

fun buildAppList(all: List<AppListEntry>, ownPkg: String): List<AppListEntry> {
    val collator = Collator.getInstance(Locale.JAPANESE)
    val rest = all
        .filter { it.pkg != ownPkg && it.pkg != STOCK_LAUNCHER_ENTRY.pkg }
        .distinctBy { it.pkg to it.cls }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
    return listOf(STOCK_LAUNCHER_ENTRY) + rest
}
