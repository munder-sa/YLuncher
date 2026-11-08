package com.gt8d.launcher.apps

import com.gt8d.launcher.config.DockMode
import com.gt8d.launcher.config.MAX_DOCK_APPS

private const val HOME_REOPEN_DEBOUNCE_MS = 2_000L

data class FrequentApp(val pkg: String, val count: Int)

fun rankFrequent(
    launches: List<UsageLaunch>,
    eligible: Set<String>,
    limit: Int = MAX_DOCK_APPS,
): List<FrequentApp> {
    val counts = mutableMapOf<String, Int>()
    val last = mutableMapOf<String, Long>()
    var previous: String? = null
    var beforeHome: String? = null
    for (launch in launches.sortedBy { it.wallMs }) {
        val pkg = launch.pkg
        if (pkg == UsageHistory.HOME_MARKER) {
            beforeHome = previous
            previous = null
            continue
        }
        if (pkg !in eligible) continue
        if (pkg != previous) {
            val lastResume = last[pkg]
            val quickHomeReopen = pkg == beforeHome && lastResume != null &&
                launch.wallMs - lastResume < HOME_REOPEN_DEBOUNCE_MS
            if (!quickHomeReopen) counts[pkg] = counts.getOrDefault(pkg, 0) + 1
        }
        last[pkg] = launch.wallMs
        previous = pkg
        beforeHome = null
    }
    return counts.keys.sortedWith(compareByDescending<String> { counts.getValue(it) }
        .thenByDescending { last.getValue(it) }.thenBy { it })
        .take(limit.coerceAtLeast(0)).map { FrequentApp(it, counts.getValue(it)) }
}

fun dockAppsFor(
    mode: DockMode,
    manual: List<String>,
    ranked: List<FrequentApp>?,
    installed: Set<String>,
    exclude: Set<String> = emptySet(),
): List<String> {
    val candidates = if (mode == DockMode.Manual) manual
        else ranked.orEmpty().map { it.pkg } + manual
    return candidates.filter { it in installed && it !in exclude }.distinct().take(MAX_DOCK_APPS)
}
