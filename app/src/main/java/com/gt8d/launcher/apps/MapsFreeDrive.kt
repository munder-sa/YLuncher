package com.gt8d.launcher.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.gt8d.launcher.config.Defaults

object MapsFreeDrive {
    const val DRIVE_MODE_LAUNCHER = "com.gatolabo.drivemodelauncher"
    const val URI = "google.navigation:/?free=1&mode=d&entry=fnls"

    fun isMaps(pkg: String): Boolean = pkg == Defaults.PKG_MAPS
}

internal fun PackageManager.hasLauncherActivity(pkg: String): Boolean = try {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
    queryIntentActivities(intent, 0).isNotEmpty()
} catch (_: Exception) {
    false
}

class MapsFreeDriveRedirects(
    private val base: SplitRedirects,
    private val isInstalled: (String) -> Boolean,
) : SplitRedirects {
    override fun forPackage(pkg: String): SplitRedirect? {
        base.forPackage(pkg)?.let { return it }
        if (pkg != MapsFreeDrive.DRIVE_MODE_LAUNCHER || !isInstalled(Defaults.PKG_MAPS)) return null
        return SplitRedirect(Defaults.PKG_MAPS, null, Uri.parse(MapsFreeDrive.URI))
    }
}
