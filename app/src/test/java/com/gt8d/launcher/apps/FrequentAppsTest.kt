package com.gt8d.launcher.apps

import com.gt8d.launcher.config.DockMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FrequentAppsTest {
    @Test fun hiddenExcludedFromManualRankingAndAutoFill() {
        val hidden = HiddenApps.packages.toList()
        val manual = hidden + listOf("a", "b", "c", "d", "e")
        val installed = manual.toSet()
        assertEquals(listOf("a", "b", "c", "d"), dockAppsFor(DockMode.Manual,
            manual, null, installed, HiddenApps.packages))
        assertEquals(listOf("a", "b", "c", "d"), dockAppsFor(DockMode.Auto,
            manual, null, installed, HiddenApps.packages))
        assertEquals(listOf("e", "a", "b", "c"), dockAppsFor(DockMode.Auto,
            manual, hidden.map { FrequentApp(it, 99) } + FrequentApp("e", 1), installed, HiddenApps.packages))
    }
    private fun launch(pkg: String, time: Long) = UsageLaunch(pkg, time)

    @Test fun consecutiveResumesCountOnceAndUpdateLastTime() {
        assertEquals(listOf(FrequentApp("a", 1), FrequentApp("b", 1)), rankFrequent(
            listOf(launch("a", 1), launch("a", 10), launch("b", 10)), setOf("a", "b")))
        assertEquals(listOf(FrequentApp("a", 2), FrequentApp("b", 1)), rankFrequent(
            listOf(launch("a", 1), launch("a", 10), launch("b", 11), launch("a", 12)), setOf("a", "b")))
    }

    @Test fun ineligibleRemovedBeforeCounting() {
        assertEquals(listOf(FrequentApp("a", 1)), rankFrequent(
            listOf(launch("a", 1), launch("excluded", 2), launch("a", 3)), setOf("a")))
    }

    @Test fun countThenLastTimeThenNameAndLimit() {
        val launches = listOf(launch("a", 1), launch("b", 2), launch("a", 3),
            launch("z", 10), launch("d", 10), launch("c", 10), launch("b", 10), launch("e", 10))
        assertEquals(listOf(FrequentApp("b", 2), FrequentApp("a", 2), FrequentApp("c", 1), FrequentApp("d", 1)),
            rankFrequent(launches, setOf("a", "b", "c", "d", "e", "z")))
        assertEquals(emptyList<FrequentApp>(), rankFrequent(launches, emptySet()))
        assertEquals(1, rankFrequent(launches, setOf("a", "b"), 1).size)
    }

    @Test fun equalTimestampsKeepInputOrder() {
        assertEquals(listOf(FrequentApp("a", 2), FrequentApp("b", 1)), rankFrequent(
            listOf(launch("a", 1), launch("b", 1), launch("a", 1)), setOf("a", "b")))
    }

    @Test fun manualUsesInstalledOrderAndLimitExcludingSettings() {
        assertEquals(listOf("d", "c", "b", "a"), dockAppsFor(DockMode.Manual,
            listOf("missing", "settings", "d", "c", "b", "a", "e"),
            listOf(FrequentApp("e", 9)), setOf("a", "b", "c", "d", "e", "settings"), setOf("settings")))
    }

    @Test fun autoFillsWithoutDuplicatesAndFiltersMissingAndSettings() {
        assertEquals(listOf("b", "a", "c", "d"), dockAppsFor(DockMode.Auto,
            listOf("a", "b", "settings", "missing", "c", "d", "e"),
            listOf(FrequentApp("settings", 99), FrequentApp("missing", 8), FrequentApp("b", 7), FrequentApp("a", 6)),
            setOf("a", "b", "c", "d", "e", "settings"), setOf("settings")))
    }

    @Test fun nullRankingFallsBackToManual() {
        assertEquals(listOf("b", "a"), dockAppsFor(DockMode.Auto,
            listOf("missing", "b", "a"), null, setOf("a", "b")))
    }
}
