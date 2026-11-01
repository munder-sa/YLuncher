package com.gt8d.launcher.ui.apps

import com.gt8d.launcher.config.Defaults
import com.gt8d.launcher.config.ScreenSet
import com.gt8d.launcher.config.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

class AppListTest {
    @Test fun seed_unionFiltersLaunchableAndDeduplicates() {
        val config = Defaults.build(emptySet()).copy(
            panelApps = listOf("panel", "shared", "missing"),
            dockApps = listOf("dock", "shared"), homeApps = listOf("home", "shared"),
            screenSets = listOf(ScreenSet("a", Slot.App("left"), Slot.App("right")),
                ScreenSet("b", Slot.Panel, Slot.App("shared"))),
        )
        val expected = setOf("panel", "dock", "home", "left", "right", "shared", "recent")
        val result = seedAppList(config, setOf("recent", "shared", "unlaunchable"), expected)
        assertEquals(expected, result.toSet())
        assertEquals(expected.size, result.size)
    }

    @Test fun registeredEntries_preservesStockFirstAndAllActivities() {
        val all = buildAppList(listOf(AppListEntry("b", "B", "B"),
            AppListEntry("a", "A", "A"), AppListEntry("a", "Other", "Z")), "own")
        assertEquals(listOf(all[0], all[1], all[3]),
            registeredEntries(all, setOf(STOCK_LAUNCHER_ENTRY.pkg, "a", "missing")))
        assertEquals(emptyList<AppListEntry>(), registeredEntries(all, emptySet()))
    }

    @Test fun toggleRegistered_appendsOrRemovesPackage() {
        assertEquals(listOf("a", "b", "c"), toggleRegistered(listOf("a", "b"), "c"))
        assertEquals(listOf("b"), toggleRegistered(listOf("a", "b", "a"), "a"))
        assertEquals(listOf("a"), toggleRegistered(emptyList(), "a"))
    }
}
