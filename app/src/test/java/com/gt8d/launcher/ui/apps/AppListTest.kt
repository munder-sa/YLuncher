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

    @Test fun registeredEntries_excludesHiddenAndPreservesAllActivitiesInLabelOrder() {
        val all = buildAppList(listOf(AppListEntry("b", "B", "B"),
            AppListEntry("a", "A", "A"), AppListEntry("a", "Other", "Z")) +
            com.gt8d.launcher.apps.HiddenApps.packages.flatMap {
                listOf(AppListEntry(it, "Main", "Hidden"), AppListEntry(it, "Other", "Other"))
            }, "own")
        assertEquals(listOf("A", "B", "Z"), all.map { it.label })
        assertEquals(listOf(all[0], all[2]),
            registeredEntries(all, com.gt8d.launcher.apps.HiddenApps.packages + setOf("a", "missing")))
        assertEquals(emptyList<AppListEntry>(), registeredEntries(all, emptySet()))
    }

    @Test fun seed_excludesHiddenFromEverySource() {
        val hidden = com.gt8d.launcher.apps.HiddenApps.packages.toList()
        val config = Defaults.build(emptySet()).copy(panelApps = hidden, dockApps = hidden,
            homeApps = hidden, screenSets = listOf(ScreenSet("hidden", Slot.App(hidden[0]), Slot.App(hidden[1]))))
        assertEquals(listOf("visible"), seedAppList(config, hidden.toSet() + "visible", hidden.toSet() + "visible"))
    }

    @Test fun toggleRegistered_appendsOrRemovesPackage() {
        assertEquals(listOf("a", "b", "c"), toggleRegistered(listOf("a", "b"), "c"))
        assertEquals(listOf("b"), toggleRegistered(listOf("a", "b", "a"), "a"))
        assertEquals(listOf("a"), toggleRegistered(emptyList(), "a"))
    }
}
