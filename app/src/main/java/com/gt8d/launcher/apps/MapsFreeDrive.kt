package com.gt8d.launcher.apps

import android.content.Intent
import android.net.Uri
import com.gt8d.launcher.config.Defaults

object MapsFreeDrive {
    const val URI = "google.navigation:/?free=1&mode=d&entry=fnls"

    fun isMaps(pkg: String): Boolean = pkg == Defaults.PKG_MAPS

    fun intent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(URI)).setPackage(Defaults.PKG_MAPS)
}

class MapsFreeDriveRedirects(private val base: SplitRedirects) : SplitRedirects {
    override fun forPackage(pkg: String): SplitRedirect? {
        base.forPackage(pkg)?.let { return it }
        if (!MapsFreeDrive.isMaps(pkg)) return null
        return SplitRedirect(Defaults.PKG_MAPS, null, Uri.parse(MapsFreeDrive.URI))
    }
}
