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

    @Test fun homeSeparatesRepeatedAppLaunches() {
        assertEquals(listOf(FrequentApp("a", 3)), rankFrequent(
            listOf(launch("a", 0), launch(UsageHistory.HOME_MARKER, 1000), launch("a", 5000),
                launch(UsageHistory.HOME_MARKER, 6000), launch("a", 10000)), setOf("a")))
    }

    @Test fun homeReopenUnderTwoSecondsCountsOnce() {
        assertEquals(listOf(FrequentApp("a", 1)), rankFrequent(
            listOf(launch("a", 0), launch(UsageHistory.HOME_MARKER, 500), launch("a", 1500)), setOf("a")))
    }

    @Test fun homeReopenAtTwoSecondsCountsAgain() {
        assertEquals(listOf(FrequentApp("a", 2)), rankFrequent(
            listOf(launch("a", 0), launch(UsageHistory.HOME_MARKER, 500), launch("a", 2000)), setOf("a")))
    }

    @Test fun panelResumeDoesNotSeparateRepeatedAppLaunches() {
        assertEquals(listOf(FrequentApp("a", 1)), rankFrequent(
            listOf(launch("a", 0), launch("panel", 1000), launch("a", 5000)), setOf("a")))
    }

    @Test fun differentAppAfterHomeAllowsLaterLaunchToCount() {
        assertEquals(listOf(FrequentApp("a", 2), FrequentApp("b", 1)), rankFrequent(
            listOf(launch("a", 0), launch(UsageHistory.HOME_MARKER, 1000), launch("b", 3000),
                launch("a", 3500)), setOf("a", "b")))
        assertEquals(listOf(FrequentApp("a", 2), FrequentApp("b", 1)), rankFrequent(
            listOf(launch("a", 0), launch(UsageHistory.HOME_MARKER, 500), launch("b", 1000),
                launch("a", 1500)), setOf("a", "b")))
    }

    @Test fun homeMarkersAloneAndAtStartNeverBecomeCandidates() {
        assertEquals(emptyList<FrequentApp>(), rankFrequent(
            listOf(launch(UsageHistory.HOME_MARKER, 0), launch(UsageHistory.HOME_MARKER, 1000)), setOf("a")))
        assertEquals(listOf(FrequentApp("a", 1)), rankFrequent(
            listOf(launch(UsageHistory.HOME_MARKER, 0), launch("a", 1000)), setOf("a")))
    }

    @Test fun homeReopenUsesLatestResumeIncludingUncountedResumes() {
        assertEquals(listOf(FrequentApp("a", 1)), rankFrequent(
            listOf(launch("a", 0), launch("a", 3000), launch(UsageHistory.HOME_MARKER, 3500),
                launch("a", 4500), launch(UsageHistory.HOME_MARKER, 5000), launch("a", 6000)), setOf("a")))
    }

    @Test fun uncountedHomeReopenUpdatesRankingTime() {
        assertEquals(listOf(FrequentApp("z", 1), FrequentApp("a", 1)), rankFrequent(
            listOf(launch("a", 0), launch("z", 0), launch(UsageHistory.HOME_MARKER, 500),
                launch("z", 1500)), setOf("a", "z")))
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
