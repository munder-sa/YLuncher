package com.gt8d.launcher.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListScreenTest {
    private val ownPkg = "com.gt8d.launcher"

    @Test
    fun appList_alwaysIncludesStockLauncher() {
        val youtube = AppListEntry(
            "com.google.android.youtube",
            "com.google.android.youtube.HomeActivity",
            "YouTube",
        )
        val result1 = buildAppList(listOf(youtube), ownPkg)
        assertEquals(2, result1.size)
        assertEquals(STOCK_LAUNCHER_ENTRY, result1.first())

        val launcher3Other = AppListEntry(
            "com.android.launcher3",
            "com.android.launcher3.Launcher",
            "Quickstep",
        )
        val result2 = buildAppList(listOf(youtube, launcher3Other), ownPkg)
        val launcher3Items = result2.filter { it.pkg == "com.android.launcher3" }
        assertEquals(listOf(STOCK_LAUNCHER_ENTRY), launcher3Items)
    }

    @Test
    fun appList_sortedByLabel() {
        val entries = listOf(
            AppListEntry("y", "y.Main", "YouTube"),
            AppListEntry("c", "c.Main", "chrome"),
            AppListEntry("a", "a.Main", "Apple Music"),
        )
        val result = buildAppList(entries, ownPkg)
        assertEquals(
            listOf("純正ランチャー", "Apple Music", "chrome", "YouTube"),
            result.map { it.label },
        )
    }

    @Test
    fun appList_excludesOwnPanel() {
        val ownPanel = AppListEntry(
            "com.gt8d.launcher",
            "com.gt8d.launcher.ui.panel.PanelActivity",
            "GT8D パネル",
        )
        val youtube = AppListEntry(
            "com.google.android.youtube",
            "com.google.android.youtube.HomeActivity",
            "YouTube",
        )
        val result = buildAppList(listOf(ownPanel, youtube), ownPkg)
        assertEquals(2, result.size)
        assertTrue(result.none { it.pkg == "com.gt8d.launcher" })
    }
}
