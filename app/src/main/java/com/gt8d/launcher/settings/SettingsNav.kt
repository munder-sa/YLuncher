package com.gt8d.launcher.settings

sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data object Sets : SettingsRoute
    data object Lists : SettingsRoute
    data object Link : SettingsRoute
    data object Gesture : SettingsRoute
    data object Boot : SettingsRoute
    data object Permissions : SettingsRoute
    data object AppPairPrep : SettingsRoute
}

data class SettingsNav(val stack: List<SettingsRoute> = listOf(SettingsRoute.Root)) {
    val current: SettingsRoute get() = stack.last()
    fun push(route: SettingsRoute): SettingsNav = copy(stack = stack + route)
    fun pop(): SettingsNav? = if (stack.size == 1) null else copy(stack = stack.dropLast(1))
}
