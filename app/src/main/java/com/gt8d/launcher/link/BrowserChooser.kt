package com.gt8d.launcher.link

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.gt8d.launcher.apps.OwnPackages

object BrowserChooser {
    fun installedBrowsers(pm: PackageManager): List<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_ALL)
            .filter { it.activityInfo?.packageName?.let { pkg -> !OwnPackages.isOwn(pkg) } == true }
            .sortedWith(compareBy({ it.loadLabel(pm).toString() }, { it.activityInfo.packageName }))
            .map { it.activityInfo.packageName }.distinct()

    fun choose(preferred: String, installed: List<String>): String? =
        listOf(preferred, "com.android.chrome", "com.brave.browser").firstOrNull { it in installed }
            ?: installed.firstOrNull()
}
