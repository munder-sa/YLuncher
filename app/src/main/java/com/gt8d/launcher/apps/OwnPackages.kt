package com.gt8d.launcher.apps

object OwnPackages {
    const val MAIN = "com.gt8d.launcher"
    const val PANEL_HOST = "com.gt8d.launcher.panel"
    fun isOwn(pkg: String?): Boolean = pkg == MAIN || pkg == PANEL_HOST
}
