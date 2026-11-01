package com.gt8d.launcher.apps

import com.gt8d.launcher.sets.SetRunner
import com.gt8d.launcher.split.VendorBridge

object HiddenApps {
    val packages: Set<String> = setOf(SetRunner.STOCK_LAUNCHER_PKG, VendorBridge.VENDOR_PKG)

    fun isHidden(pkg: String): Boolean = pkg in packages
}
