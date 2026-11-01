package com.gt8d.launcher.apps

import com.gt8d.launcher.config.DockMode
import com.gt8d.launcher.config.MAX_DOCK_APPS

data class FrequentApp(val pkg: String, val count: Int)

fun rankFrequent(
    launches: List<UsageLaunch>,
    eligible: Set<String>,
    limit: Int = MAX_DOCK_APPS,
): List<FrequentApp> {
    val counts = mutableMapOf<String, Int>()
    val last = mutableMapOf<String, Long>()
    var previous: String? = null
    // 追記の仕様: eligible 外を除いてから連続 RESUMED をまとめる。
    for (launch in launches.sortedBy { it.wallMs }.filter { it.pkg in eligible }) {
        if (previous != launch.pkg) counts[launch.pkg] = counts.getOrDefault(launch.pkg, 0) + 1
        last[launch.pkg] = launch.wallMs
        previous = launch.pkg
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
