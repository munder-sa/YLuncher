package com.gt8d.launcher.ui.panel

import com.gt8d.launcher.apps.FrequentApp
import com.gt8d.launcher.apps.HiddenApps
import com.gt8d.launcher.apps.dockAppsFor
import com.gt8d.launcher.config.LauncherConfig
import com.gt8d.launcher.config.MAX_PANEL_APPS
import com.gt8d.launcher.config.PanelView
import com.gt8d.launcher.config.Slot

internal data class InitialIconPlan(
    val foreground: Set<String>,
    val background: Set<String>,
    val dockPackages: List<String>,
)

internal fun initialIconPlan(
    config: LauncherConfig,
    view: PanelView,
    homeWide: Boolean,
    nowPkg: String?,
    frequent: List<FrequentApp>?,
    installed: Set<String>,
    settingsPkg: String?,
): InitialIconPlan {
    val sets = config.screenSets.flatMap {
        listOfNotNull((it.left as? Slot.App)?.pkg, (it.right as? Slot.App)?.pkg)
    }
    val dock = dockAppsFor(config.dockMode, config.dockApps, frequent, installed,
        setOfNotNull(settingsPkg) + HiddenApps.packages)
    val foreground = buildSet {
        if (homeWide || view == PanelView.Sets) addAll(sets)
        if (homeWide || view == PanelView.Apps) {
            // Include every page/scroll candidate, just as the content does.
            addAll(config.panelApps.take(MAX_PANEL_APPS))
            add(nowPkg ?: config.idleApp)
        }
        addAll(dock)
        settingsPkg?.let(::add)
    }.filter { it.isNotBlank() }.toSet()
    val required = (config.homeApps + config.panelApps + config.dockApps + config.idleApp +
        sets + frequent.orEmpty().map { it.pkg } + listOfNotNull(nowPkg, settingsPkg))
        .filter { it.isNotBlank() }.toSet()
    return InitialIconPlan(foreground, required - foreground, dock)
}
