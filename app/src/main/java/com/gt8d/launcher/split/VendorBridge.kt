package com.gt8d.launcher.split

import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.diag.DiagLog

class VendorBridge(private val launcher: ActivityLauncher) {
    companion object {
        const val VENDOR_PKG = "com.sd.factoryconfig"
        const val SPLIT_CLS = "com.sd.factoryconfig.SplitActivity"
        const val EXTRA_PACKAGES = "split_shortcur_package"   // 綴りは原文のまま
        const val EXTRA_PERCENT = "split_percent"
    }

    fun splitAvailable(): Boolean {
        val available = launcher.canResolve(VENDOR_PKG, SPLIT_CLS)
        DiagLog.d("Vendor", "splitAvailable=$available")
        return available
    }

    fun openSplit(left: String, right: String, ratio: SplitRatio): LaunchResult {
        DiagLog.d("Vendor", "openSplit left=$left right=$right ratio=${ratio.vendorValue}")
        if (!splitAvailable()) {
            DiagLog.d("Vendor", "openSplit failed: EntryMissing")
            return LaunchResult.Failed(FailReason.EntryMissing)
        }
        val spec = LaunchSpec(
            pkg = VENDOR_PKG,
            cls = SPLIT_CLS,
            extras = mapOf(
                EXTRA_PACKAGES to "$left|$right",
                EXTRA_PERCENT to ratio.vendorValue,
            ),
        )
        return try {
            launcher.start(spec)
            DiagLog.d("Vendor", "openSplit started")
            LaunchResult.Started
        } catch (t: Throwable) {
            DiagLog.d("Vendor", "openSplit exception: ${t.message}")
            LaunchResult.Failed(FailReason.Exception)
        }
    }

    val carHomeAvailable: Boolean
        get() = false

    fun switchToCarScreen(): Boolean {
        DiagLog.d("Vendor", "switchToCarScreen: unavailable")
        return false
    }
}

class VendorSplitLauncher(private val bridge: VendorBridge) : SplitLauncher {
    override fun open(left: String, right: String, ratio: SplitRatio): LaunchResult {
        return bridge.openSplit(left, right, ratio)
    }
}
